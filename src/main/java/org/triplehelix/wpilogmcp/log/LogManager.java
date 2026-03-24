package org.triplehelix.wpilogmcp.log;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.cache.CacheDirectory;
import org.triplehelix.wpilogmcp.cache.DiskCache;
import org.triplehelix.wpilogmcp.log.LogDirectory.RevLogFileInfo;
import org.triplehelix.wpilogmcp.log.subsystems.LogCache;
import org.triplehelix.wpilogmcp.log.subsystems.LogParser;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.log.subsystems.StructDecoderRegistry;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcDatabase;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader;
import org.triplehelix.wpilogmcp.sync.LogSynchronizer;
import org.triplehelix.wpilogmcp.sync.SyncResult;
import org.triplehelix.wpilogmcp.sync.SynchronizedLogs;
import org.triplehelix.wpilogmcp.sync.SynchronizedLogs.SyncedRevLog;

/**
 * Manages loading, caching, and accessing WPILOG files.
 *
 * <p>This class provides thread-safe log caching with LRU eviction. It delegates operations to
 * specialized subsystems:
 *
 * <ul>
 *   <li>{@link LogCache} - LRU cache with memory/count-based eviction
 *   <li>{@link LogParser} - WPILOG file parsing with struct decoding
 *   <li>{@link SecurityValidator} - Path validation to prevent traversal attacks
 *   <li>{@link StructDecoderRegistry} - Extensible struct decoder registry
 * </ul>
 *
 * @since 0.1.0 (refactored in 0.4.0)
 */
public class LogManager {
  private static final Logger logger = LoggerFactory.getLogger(LogManager.class);

  /** Singleton instance. */
  private static final LogManager INSTANCE = new LogManager();

  /**
   * Maximum number of records to scan when extracting metadata from a log file. This prevents
   * excessive memory/time usage on very large logs.
   */
  static final int MAX_METADATA_RECORDS = 2000;

  // Subsystems (initialized in constructor)
  private final StructDecoderRegistry decoderRegistry;
  private final SecurityValidator securityValidator;
  private final LogParser logParser;
  private final LogCache logCache;

  // Disk cache (initialized in constructor)
  private final DiskCache diskCache;
  private final CacheDirectory cacheDirectory;
  private final org.triplehelix.wpilogmcp.cache.SyncDiskCache syncDiskCache;

  // RevLog integration (initialized in constructor)
  private final RevLogParser revLogParser;
  private final LogSynchronizer synchronizer;
  private final Map<String, SynchronizedLogs> syncCache = new ConcurrentHashMap<>();
  private final Map<String, java.util.concurrent.CompletableFuture<Void>> syncInProgress =
      new ConcurrentHashMap<>();
  private final java.util.concurrent.ExecutorService syncExecutor;
  private final java.util.concurrent.ScheduledExecutorService evictionScheduler;
  private volatile boolean autoSyncEnabled = true;

  /** Per-path locks to prevent duplicate concurrent parses of the same log file. */
  private final ConcurrentHashMap<String, Object> loadLocks = new ConcurrentHashMap<>();

  /** Private constructor for singleton pattern. */
  private LogManager() {
    // Initialize subsystems
    this.decoderRegistry = new StructDecoderRegistry();
    this.securityValidator = new SecurityValidator();
    this.logParser = new LogParser(decoderRegistry);
    this.logCache = new LogCache();

    // Initialize disk cache
    this.cacheDirectory = new CacheDirectory();
    this.diskCache = new DiskCache(cacheDirectory, org.triplehelix.wpilogmcp.Version.VERSION);
    this.syncDiskCache = new org.triplehelix.wpilogmcp.cache.SyncDiskCache(cacheDirectory);

    // Initialize RevLog subsystems
    this.revLogParser = createRevLogParser();
    this.synchronizer = new LogSynchronizer();
    this.syncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "revlog-sync");
      t.setDaemon(true);
      return t;
    });

    // Clean up syncCache and cancel in-progress syncs when logs are evicted
    this.logCache.setEvictionCallback(evictedPath -> {
      syncCache.remove(evictedPath);
      var pending = syncInProgress.remove(evictedPath);
      if (pending != null && !pending.isDone()) {
        pending.cancel(false);
        logger.debug("Cancelled sync for evicted log: {}", evictedPath);
      }
    });

    // Schedule periodic idle eviction every 5 minutes
    this.evictionScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "log-cache-evictor");
      t.setDaemon(true);
      return t;
    });
    this.evictionScheduler.scheduleAtFixedRate(
        () -> {
          try {
            logCache.evictIfNeeded();
          } catch (Exception e) {
            logger.warn("Periodic cache eviction failed: {}", e.getMessage());
          }
        },
        5, 5, java.util.concurrent.TimeUnit.MINUTES);
  }

  /**
   * Gets the singleton instance of LogManager.
   *
   * @return The singleton instance
   */
  public static LogManager getInstance() {
    return INSTANCE;
  }

  /**
   * Gets the struct decoder registry for registering custom decoders.
   *
   * @return The decoder registry
   * @since 0.4.0
   */
  public StructDecoderRegistry getDecoderRegistry() {
    return decoderRegistry;
  }

  /**
   * Sets the maximum number of logs to keep in cache.
   */

  /**
   * Adds a directory to the list of allowed directories for loading logs.
   *
   * <p>Only paths within allowed directories can be loaded. This prevents path traversal attacks
   * by restricting file access to explicitly allowed directories.
   *
   * <p>If no allowed directories are configured, all paths are allowed (backwards compatibility).
   *
   * @param directory The directory to allow (will be normalized to absolute path)
   */
  public void addAllowedDirectory(String directory) {
    if (directory != null && !directory.isBlank()) {
      securityValidator.addAllowedDirectory(directory);
    }
  }

  /**
   * Adds a directory to the list of allowed directories for loading logs.
   *
   * @param directory The directory path to allow
   */
  public void addAllowedDirectory(Path directory) {
    if (directory != null) {
      securityValidator.addAllowedDirectory(directory);
    }
  }

  /**
   * Gets the set of allowed directories.
   *
   * @return A copy of the allowed directories set
   */
  public java.util.Set<Path> getAllowedDirectories() {
    return securityValidator.getAllowedDirectories();
  }

  /**
   * Clears all allowed directories. After calling this, all paths will be allowed (backwards
   * compatibility mode).
   */
  public void clearAllowedDirectories() {
    securityValidator.clearAllowedDirectories();
  }

  /**
   * Loads a WPILOG file into memory and parses its contents.
   *
   * <p>If the log is already cached, returns the cached copy. Otherwise, parses the file and adds
   * it to the cache. May trigger eviction of the least recently used log if cache limits are
   * exceeded.
   *
   * @param path The file path (can be relative or absolute)
   * @return The parsed log
   * @throws IOException if the file cannot be read or is invalid, or if path is outside allowed
   *     directories
   */
  public LogData loadLog(String path) throws IOException {
    Path filePath = Path.of(path).toAbsolutePath().normalize();

    // Validate path is allowed (or cache is already loaded)
    securityValidator.validateOrAllowCached(filePath, logCache::containsKey);

    // Check if already cached (fast path, no lock needed)
    String normalizedPath = filePath.toString();
    LogData cachedLog = logCache.get(normalizedPath);
    if (cachedLog != null) {
      logger.debug("Returning cached log: {}", filePath);
      return cachedLog;
    }

    // Per-path lock to prevent duplicate concurrent parses of the same file
    Object lock = loadLocks.computeIfAbsent(normalizedPath, k -> new Object());
    try { synchronized (lock) {
        // Double-check cache after acquiring lock (another thread may have finished parsing)
        cachedLog = logCache.get(normalizedPath);
        if (cachedLog != null) {
          logger.debug("Returning cached log (loaded by another thread): {}", filePath);
          return cachedLog;
        }

        // Check file exists
        if (!Files.exists(filePath)) {
          throw new IOException("File not found: " + filePath);
        }

        // Evict cached logs to free memory for the new one.
        // With lazy loading, the main heap cost is the stashed DataLogRecord references
        // (~88 bytes per record). Aggressively evict until we have enough room.
        long fileSizeBytes = Files.size(filePath);
        logCache.evictIfNeeded();

        // If the file is large relative to available heap, evict more aggressively
        Runtime runtime = Runtime.getRuntime();
        long availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        boolean evicted = false;
        while (fileSizeBytes > availableMemory && !logCache.isEmpty()) {
          logger.info("Evicting logs to make room for {} ({} MB, {} MB available)",
              filePath.getFileName(), fileSizeBytes / (1024 * 1024), availableMemory / (1024 * 1024));
          logCache.evictOne();
          evicted = true;
          runtime = Runtime.getRuntime();
          availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        }
        if (evicted) {
          System.gc(); // Single GC hint after eviction loop, not per-iteration
        }

        // Lazy loading: single-pass scan collects entry metadata and stashes
        // lightweight DataLogRecord references (ByteBuffer slices into the memory-mapped
        // file — no data copying, no value decoding). Values are decoded on demand
        // when tools access specific entries via the Caffeine-backed cache.
        LogData log;
        try {
          long perLogBudgetBytes = getPerLogCacheBudgetBytes();
          var reader = new edu.wpi.first.util.datalog.DataLogReader(filePath.toString());
          log = new LazyParsedLog(filePath.toString(), reader,
              logParser.getDecoderRegistry(), perLogBudgetBytes);
        } catch (Exception e) {
          // If lazy scan fails (e.g., not a valid WPILOG), fall back to eager parse
          logger.debug("Lazy scan failed for {}, falling back to eager parse: {}",
              filePath.getFileName(), e.getMessage());
          log = logParser.parse(filePath);
        }

        // Add to in-memory cache
        logCache.put(normalizedPath, log);
        logger.debug(
            "Loaded log with {} entries spanning {} seconds",
            log.entryCount(), String.format("%.2f", log.duration()));

        // Auto-sync matching revlogs asynchronously (doesn't block the MCP response)
        if (autoSyncEnabled) {
          autoSyncRevLogsAsync(log);
        }

        // Evict again after adding the new log in case it pushed us over limits
        logCache.evictIfNeeded();

        return log;
    } } finally {
      // Do not remove lock entries — removal creates a race where a new lock object
      // can be created while another thread still holds the old one, defeating the
      // deduplication. Memory growth is bounded by the number of distinct paths loaded.
    }
  }

  /**
   * Gets a log by path, auto-loading from disk if not already cached.
   *
   * <p>This is the primary API for tools to access logs. If the log is already in cache,
   * returns it immediately. Otherwise, validates the path, parses the file, and caches it.
   *
   * @param path The file path (can be relative or absolute)
   * @return The parsed log (never null)
   * @throws IOException if the file cannot be read or is invalid, or path is not allowed
   */
  public LogData getOrLoad(String path) throws IOException {
    return loadLog(path);
  }


  /**
   * Clears all loaded logs from the cache.
   */
  public void clearAllLogs() {
    // Cancel any in-progress syncs
    syncInProgress.values().forEach(f -> f.cancel(false));
    syncInProgress.clear();
    logCache.clear();
    syncCache.clear();
    logger.info("Cleared all loaded logs");
  }

  /**
   * Unloads all logs from the cache (alias for clearAllLogs).
   */
  public void unloadAllLogs() {
    clearAllLogs();
  }

  /**
   * Gets all loaded logs as a map of path to LogData.
   *
   * <p>Returns a snapshot copy — safe to iterate without holding locks.
   * Use this instead of accessing LogCache directly from tools.
   *
   * @return Map of file paths to their parsed logs
   * @since 0.5.0
   */
  public java.util.Map<String, LogData> getAllLoadedLogs() {
    return logCache.getAllEntries();
  }

  /**
   * Gets the estimated memory usage of all cached logs in megabytes.
   *
   * @return Estimated memory usage in MB
   */
  public long getEstimatedMemoryUsageMb() {
    var rt = Runtime.getRuntime();
    return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
  }

  /**
   * Gets the number of logs currently loaded in cache.
   *
   * @return The number of loaded logs
   */
  public int getLoadedLogCount() {
    return logCache.getAllEntries().size();
  }

  /**
   * Resets the configuration to defaults (for testing).
   */
  public void resetConfiguration() {
    clearAllLogs();
    clearAllowedDirectories();
  }

  /**
   * Unloads a specific log from the cache.
   *
   * @param path The path to the log to unload
   * @return true if the log was found and removed, false otherwise
   */
  public boolean unloadLog(String path) {
    Path filePath = Path.of(path).toAbsolutePath().normalize();
    String normalizedPath = filePath.toString();

    boolean removed = logCache.remove(normalizedPath) != null;
    if (removed) {
      logger.info("Unloaded log: {}", normalizedPath);
      // Cancel any in-progress sync and clean up
      var future = syncInProgress.remove(normalizedPath);
      if (future != null) future.cancel(false);
      syncCache.remove(normalizedPath);
    }
    return removed;
  }

  /**
   * Gets the list of paths for all loaded logs.
   *
   * @return List of paths for logs currently in cache
   */
  public List<String> getLoadedLogPaths() {
    return new ArrayList<>(logCache.getAllEntries().keySet());
  }

  /**
   * Gets the maximum number of logs setting.
   *
   * @return The configured max loaded logs, or null if using default/memory-based
   */

  /**
   * Lists all available WPILOG files in the configured directories.
   *
   * @return List of log metadata, sorted by modification time (newest first)
   * @since 0.4.0
   */
  public List<LogMetadata> listAvailableLogs() {
    var allowedDirs = securityValidator.getAllowedDirectories();

    if (allowedDirs.isEmpty()) {
      logger.warn(
          "No allowed directories configured. Use addAllowedDirectory() to enable log discovery.");
      return Collections.emptyList();
    }

    List<LogMetadata> logs = new ArrayList<>();

    for (Path dir : allowedDirs) {
      if (!Files.isDirectory(dir)) {
        logger.debug("Skipping non-directory: {}", dir);
        continue;
      }

      try {
        scanDirectoryForLogs(dir, logs);
      } catch (IOException e) {
        logger.warn("Error scanning directory {}: {}", dir, e.getMessage());
      }
    }

    // Sort by modification time (newest first)
    logs.sort(Comparator.comparing(LogMetadata::lastModified).reversed());

    logger.info("Found {} WPILOG files across {} directories", logs.size(), allowedDirs.size());
    return logs;
  }

  /**
   * Scans a directory recursively for WPILOG files.
   *
   * @param dir The directory to scan
   * @param logs The list to add found logs to
   */
  private void scanDirectoryForLogs(Path dir, List<LogMetadata> logs) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path entry : stream) {
        if (Files.isDirectory(entry)) {
          // Recursively scan subdirectories
          scanDirectoryForLogs(entry, logs);
        } else if (entry.toString().endsWith(".wpilog")) {
          try {
            long size = Files.size(entry);
            long lastModified = Files.getLastModifiedTime(entry).toMillis();
            logs.add(new LogMetadata(entry.toString(), size, lastModified));
          } catch (IOException e) {
            logger.debug("Error reading metadata for {}: {}", entry, e.getMessage());
          }
        }
      }
    }
  }

  /**
   * Gets metadata about currently loaded logs.
   *
   * @return List of metadata for loaded logs
   * @since 0.4.0
   */
  public List<LoadedLogInfo> listLoadedLogs() {
    var entries = logCache.getAllEntries();
    var result = new ArrayList<LoadedLogInfo>();

    for (var entry : entries.entrySet()) {
      String path = entry.getKey();
      LogData log = entry.getValue();
      result.add(
          new LoadedLogInfo(path, log.entryCount(), log.duration(), 0));
    }

    return result;
  }


  // ==================== DISK CACHE CONFIGURATION ====================

  /**
   * Gets the disk cache instance.
   *
   * @return The disk cache
   * @since 0.5.0
   */
  public DiskCache getDiskCache() {
    return diskCache;
  }

  /**
   * Gets the sync disk cache instance.
   *
   * @return The sync disk cache
   * @since 0.8.0
   */
  public org.triplehelix.wpilogmcp.cache.SyncDiskCache getSyncDiskCache() {
    return syncDiskCache;
  }

  /**
   * Gets the cache directory resolver.
   *
   * @return The cache directory
   * @since 0.5.0
   */
  public CacheDirectory getCacheDirectory() {
    return cacheDirectory;
  }

  // ==================== REVLOG SYNC STATUS ====================

  /**
   * Checks if revlog synchronization is in progress for any loaded log.
   *
   * @return true if any background sync is running
   * @since 0.8.0
   */
  public boolean isAnyRevLogSyncInProgress() {
    return syncInProgress.values().stream().anyMatch(f -> !f.isDone());
  }

  /**
   * Checks if revlog synchronization is in progress for a specific log path.
   *
   * @param wpilogPath The wpilog file path
   * @return true if a background sync is running for this path
   * @since 0.5.0
   */
  public boolean isRevLogSyncInProgress(String wpilogPath) {
    var future = syncInProgress.get(wpilogPath);
    return future != null && !future.isDone();
  }

  /**
   * Waits for revlog synchronization to complete for the given log path.
   *
   * @param wpilogPath The wpilog file path
   * @param timeoutMs Maximum time to wait in milliseconds
   * @return true if sync completed (or was not in progress), false if timed out
   * @since 0.5.0
   */
  public boolean waitForRevLogSync(String wpilogPath, long timeoutMs) {
    if (wpilogPath == null) return true;
    String normalizedPath = Path.of(wpilogPath).toAbsolutePath().normalize().toString();
    var future = syncInProgress.get(normalizedPath);
    if (future == null || future.isDone()) return true;

    try {
      future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
      return true;
    } catch (java.util.concurrent.TimeoutException e) {
      return false;
    } catch (Exception e) {
      logger.warn("Error waiting for revlog sync: {}", e.getMessage());
      return true; // Don't block indefinitely on errors
    }
  }

  // ==================== REVLOG INTEGRATION ====================

  /**
   * Creates a RevLogParser with the DBC database loaded.
   */
  private RevLogParser createRevLogParser() {
    try {
      DbcDatabase dbc = new DbcLoader().load(null);
      return new RevLogParser(dbc);
    } catch (IOException e) {
      logger.warn("Failed to load DBC database, RevLog parsing will be limited: {}", e.getMessage());
      return new RevLogParser(DbcDatabase.empty());
    }
  }


  /**
   * Gets the synchronized logs for a specific wpilog path.
   *
   * @param wpilogPath The path to the wpilog
   * @return The SynchronizedLogs container, or null if not found
   * @since 0.5.0
   */
  public SynchronizedLogs getSynchronizedLogs(String wpilogPath) {
    Path filePath = Path.of(wpilogPath).toAbsolutePath().normalize();
    return syncCache.get(filePath.toString());
  }

  /**
   * Updates the synchronized logs for a specific wpilog path.
   * Used by tools like set_revlog_offset to replace sync results.
   *
   * @param wpilogPath The wpilog path key
   * @param syncLogs The new SynchronizedLogs instance
   * @since 0.5.0
   */
  public void updateSynchronizedLogs(String wpilogPath, SynchronizedLogs syncLogs) {
    syncCache.put(wpilogPath, syncLogs);
  }

  /**
   * Manually synchronizes a revlog with the specified wpilog.
   *
   * @param wpilogPath Path to the wpilog file (must be loaded)
   * @param revlogPath Path to the revlog file
   * @return The sync result
   * @throws IOException if the files cannot be read
   * @throws IllegalStateException if the wpilog is not loaded
   * @since 0.5.0
   */
  public SyncResult syncRevLog(String wpilogPath, String revlogPath) throws IOException {
    LogData wpilog = getOrLoad(wpilogPath);

    Path revPath = Path.of(revlogPath).toAbsolutePath().normalize();
    ParsedRevLog revlog = revLogParser.parse(revPath);
    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    // Update sync cache atomically to prevent TOCTOU race
    String normalizedWpilogPath = Path.of(wpilogPath).toAbsolutePath().normalize().toString();
    syncCache.compute(normalizedWpilogPath, (key, existing) -> {
      SynchronizedLogs.Builder builder = new SynchronizedLogs.Builder().wpilog(wpilog);
      if (existing != null) {
        for (SyncedRevLog synced : existing.revlogs()) {
          builder.addRevLog(synced.revlog(), synced.syncResult(), synced.canBusName());
        }
      }
      builder.addRevLog(revlog, result);
      return builder.build();
    });

    logger.info("Manually synced revlog: {} (confidence: {}, offset: {}ms)",
        revPath.getFileName(), result.confidenceLevel().getLabel(),
        result.offsetMillis());

    return result;
  }

  /**
   * Automatically discovers and syncs matching revlog files for the given wpilog.
   *
   * @param wpilog The parsed wpilog
   */
  /**
   * Starts asynchronous revlog synchronization for a wpilog.
   *
   * <p>Immediately puts a "pending" SynchronizedLogs (with no revlogs) into the sync cache
   * so tools can detect the in-progress state. The actual sync runs on a background thread.
   * When complete, the syncCache entry is atomically replaced with the final result.
   *
   * @param wpilog The parsed wpilog to sync revlogs for
   */
  private void autoSyncRevLogsAsync(LogData wpilog) {
    String wpilogPath = wpilog.path();

    // Put a placeholder immediately so tools see "sync pending" rather than null
    syncCache.put(wpilogPath, new SynchronizedLogs(wpilog));

    List<RevLogFileInfo> matchingRevLogs = findMatchingRevLogs(wpilog);

    if (matchingRevLogs.isEmpty()) {
      logger.debug("No matching .revlog files found for {}", wpilogPath);
      return; // Placeholder with 0 revlogs is the final state
    }

    logger.info("Starting async sync of {} revlog file(s) with {}",
        matchingRevLogs.size(), wpilogPath);

    // Compute wpilog fingerprint once for all revlog cache lookups
    final String wpilogFingerprint;
    try {
      wpilogFingerprint = org.triplehelix.wpilogmcp.cache.ContentFingerprint.compute(
          Path.of(wpilogPath));
    } catch (IOException e) {
      logger.debug("Cannot fingerprint wpilog for sync cache: {}", e.getMessage());
      // Fall through with null — will skip cache lookup/save
      startSyncWithoutCache(wpilog, matchingRevLogs, wpilogPath);
      return;
    }

    var future = java.util.concurrent.CompletableFuture.runAsync(() -> {
      SynchronizedLogs.Builder builder = new SynchronizedLogs.Builder().wpilog(wpilog);

      for (RevLogFileInfo revlogInfo : matchingRevLogs) {
        try {
          // Try sync disk cache first
          String revlogFp = org.triplehelix.wpilogmcp.cache.ContentFingerprint.compute(
              revlogInfo.path());
          var cached = syncDiskCache.load(wpilogFingerprint, revlogFp);

          if (cached.isPresent()) {
            var entry = cached.get();
            builder.addRevLog(entry.revlog(), entry.syncResult());
            continue;
          }

          // Cache miss — parse and correlate
          ParsedRevLog revlog = revLogParser.parse(revlogInfo.path());
          SyncResult result = synchronizer.synchronize(wpilog, revlog);

          builder.addRevLog(revlog, result);

          // Save to sync cache
          syncDiskCache.save(revlog, result, wpilogFingerprint, revlogFp);

          logger.info("Synced {} (confidence: {}, offset: {}ms)",
              revlogInfo.path().getFileName(),
              result.confidenceLevel().getLabel(),
              result.offsetMillis());

        } catch (Exception e) {
          logger.warn("Failed to sync revlog {}: {}", revlogInfo.path(), e.getMessage());
        }
      }

      // Atomically replace the placeholder with the final result
      syncCache.put(wpilogPath, builder.build());
      logger.info("RevLog sync complete for {}", Path.of(wpilogPath).getFileName());

    }, syncExecutor);

    syncInProgress.put(wpilogPath, future);
    future.whenComplete((result, error) -> syncInProgress.remove(wpilogPath));
  }

  /**
   * Fallback sync path when wpilog fingerprint cannot be computed (skips disk cache).
   */
  private void startSyncWithoutCache(LogData wpilog, List<RevLogFileInfo> matchingRevLogs,
      String wpilogPath) {
    var future = java.util.concurrent.CompletableFuture.runAsync(() -> {
      SynchronizedLogs.Builder builder = new SynchronizedLogs.Builder().wpilog(wpilog);
      for (RevLogFileInfo revlogInfo : matchingRevLogs) {
        try {
          ParsedRevLog revlog = revLogParser.parse(revlogInfo.path());
          SyncResult result = synchronizer.synchronize(wpilog, revlog);
          builder.addRevLog(revlog, result);
          logger.info("Synced {} (no cache, confidence: {}, offset: {}ms)",
              revlogInfo.path().getFileName(),
              result.confidenceLevel().getLabel(),
              result.offsetMillis());
        } catch (Exception e) {
          logger.warn("Failed to sync revlog {}: {}", revlogInfo.path(), e.getMessage());
        }
      }
      syncCache.put(wpilogPath, builder.build());
      logger.info("RevLog sync complete for {}", Path.of(wpilogPath).getFileName());
    }, syncExecutor);
    syncInProgress.put(wpilogPath, future);
    future.whenComplete((result, error) -> syncInProgress.remove(wpilogPath));
  }

  /** Tolerance for timestamp-based revlog matching (minutes). */
  private static final int REVLOG_MATCH_TOLERANCE_MINUTES = 5;

  /** Wider tolerance when using file modification time as fallback (minutes). */
  private static final int REVLOG_MTIME_TOLERANCE_MINUTES = 30;

  /**
   * Finds revlog files that match the given wpilog by time overlap.
   *
   * <p>Uses multiple timestamp sources to match even when files are in sibling directories
   * with unrelated filenames:
   * <ol>
   *   <li>SystemTime entries from the parsed wpilog (FPGA → wall clock mapping)</li>
   *   <li>Filename-embedded timestamps (e.g., FRC_25-03-21_10-30-00.wpilog)</li>
   *   <li>File modification time as last resort</li>
   * </ol>
   *
   * <p>Discovers revlogs by walking up to the configured scan depth under the configured logdir
   * and the wpilog's parent directory.
   */
  private List<RevLogFileInfo> findMatchingRevLogs(LogData wpilog) {
    // Step 1: Determine the wpilog's wall-clock time window
    long[] wallClockRange = estimateWallClockRange(wpilog);
    long wpilogStartMillis = wallClockRange[0];
    long wpilogEndMillis = wallClockRange[1];
    boolean usingMtimeFallback = wallClockRange[2] != 0;

    if (wpilogStartMillis <= 0) {
      logger.warn("Cannot determine wall-clock time for {}; skipping revlog matching",
          Path.of(wpilog.path()).getFileName());
      return List.of();
    }

    int toleranceMinutes = usingMtimeFallback
        ? REVLOG_MTIME_TOLERANCE_MINUTES
        : REVLOG_MATCH_TOLERANCE_MINUTES;
    long toleranceMillis = toleranceMinutes * 60_000L;
    long rangeStart = wpilogStartMillis - toleranceMillis;
    long rangeEnd = wpilogEndMillis + toleranceMillis;

    logger.debug("Revlog search window: {} to {} (tolerance: {} min, mtime fallback: {})",
        java.time.Instant.ofEpochMilli(rangeStart),
        java.time.Instant.ofEpochMilli(rangeEnd),
        toleranceMinutes, usingMtimeFallback);

    // Step 2: Discover all revlogs (walk the configured scan depth)
    var logDir = LogDirectory.getInstance();
    List<RevLogFileInfo> allRevLogs = new ArrayList<>();
    var seenPaths = new java.util.HashSet<Path>();

    // Scan configured logdir (the configured scan depth)
    if (logDir.isConfigured()) {
      try {
        for (var info : logDir.listRevLogFiles()) {
          if (seenPaths.add(info.path().toAbsolutePath().normalize())) {
            allRevLogs.add(info);
          }
        }
      } catch (IOException e) {
        logger.debug("Error listing revlogs from logdir: {}", e.getMessage());
      }
    }

    // Also scan the wpilog's parent directory tree (handles ad-hoc paths outside logdir)
    Path wpilogDir = Path.of(wpilog.path()).getParent();
    if (wpilogDir != null) {
      for (var info : logDir.listRevLogFilesInDirectory(wpilogDir)) {
        if (seenPaths.add(info.path().toAbsolutePath().normalize())) {
          allRevLogs.add(info);
        }
      }
    }

    if (allRevLogs.isEmpty()) {
      return List.of();
    }

    // Step 3: Filter by time overlap
    List<RevLogFileInfo> matching = new ArrayList<>();
    for (var revlog : allRevLogs) {
      Long revlogTimestamp = revlog.timestampMillis();
      if (revlogTimestamp != null) {
        // Revlog has a filename timestamp — use it for precise matching
        if (revlogTimestamp >= rangeStart && revlogTimestamp <= rangeEnd) {
          matching.add(revlog);
        }
      } else {
        // No filename timestamp — fall back to file modification time with wider tolerance
        try {
          long mtime = Files.getLastModifiedTime(revlog.path()).toMillis();
          long mtimeRangeStart = wpilogStartMillis - REVLOG_MTIME_TOLERANCE_MINUTES * 60_000L;
          long mtimeRangeEnd = wpilogEndMillis + REVLOG_MTIME_TOLERANCE_MINUTES * 60_000L;
          if (mtime >= mtimeRangeStart && mtime <= mtimeRangeEnd) {
            matching.add(revlog);
          }
        } catch (IOException e) {
          logger.debug("Cannot read mtime for {}: {}", revlog.path(), e.getMessage());
        }
      }
    }

    logger.debug("Found {} candidate revlog(s) out of {} total for {}",
        matching.size(), allRevLogs.size(), Path.of(wpilog.path()).getFileName());
    return matching;
  }

  /**
   * Computes the Caffeine per-log cache budget based on available heap.
   * Uses 60% of max heap as total budget, divided across cached logs (minimum 128 MB per log).
   */
  private long getPerLogCacheBudgetBytes() {
    long maxHeap = Runtime.getRuntime().maxMemory();
    long totalBudget = (long) (maxHeap * 0.6);
    int logCount = Math.max(1, logCache.getAllEntries().size());
    long perLog = Math.max(128L * 1024 * 1024, totalBudget / logCount);
    return perLog;
  }

  /**
   * Estimates the wall-clock time range of a wpilog file.
   *
   * <p>Tries three strategies in order:
   * <ol>
   *   <li>SystemTime entries (FPGA → wall clock mapping from the parsed log)</li>
   *   <li>Filename timestamp (parsed from standard WPILib naming convention)</li>
   *   <li>File modification time (last resort, less accurate)</li>
   * </ol>
   *
   * @param wpilog The parsed wpilog
   * @return Array of [startMillis, endMillis, usingMtimeFallback (0 or 1)]
   */
  private long[] estimateWallClockRange(LogData wpilog) {
    long durationMillis = (long) (wpilog.duration() * 1000);

    // Strategy 1: Extract wall-clock time from systemTime entries
    for (String entryName : wpilog.values().keySet()) {
      if (entryName.toLowerCase().contains("systemtime")) {
        List<TimestampedValue> values = wpilog.values().get(entryName);
        if (values != null && !values.isEmpty()) {
          // Find the first valid systemTime entry to anchor the time range
          for (var tv : values) {
            if (tv.value() instanceof Number num) {
              long wallClockMicros = num.longValue();
              double fpgaTime = tv.timestamp();
              // Compute wall-clock time at log start and end
              long startMillis = (wallClockMicros / 1000)
                  - (long) ((fpgaTime - wpilog.minTimestamp()) * 1000);
              long endMillis = startMillis + durationMillis;
              logger.debug("Wpilog wall-clock range from systemTime: {} to {}",
                  java.time.Instant.ofEpochMilli(startMillis),
                  java.time.Instant.ofEpochMilli(endMillis));
              return new long[]{startMillis, endMillis, 0};
            }
          }
        }
      }
    }

    // Strategy 2: Parse filename timestamp
    Path wpilogPath = Path.of(wpilog.path());
    Long creationTime = LogDirectory.getInstance().extractCreationTime(
        wpilogPath.getFileName().toString());
    if (creationTime != null) {
      long endMillis = creationTime + durationMillis;
      logger.debug("Wpilog wall-clock range from filename: {} to {}",
          java.time.Instant.ofEpochMilli(creationTime),
          java.time.Instant.ofEpochMilli(endMillis));
      return new long[]{creationTime, endMillis, 0};
    }

    // Strategy 3: File modification time (last resort — marks as mtime fallback)
    try {
      long mtime = Files.getLastModifiedTime(wpilogPath).toMillis();
      // mtime is approximately the end time; subtract duration to estimate start
      long startMillis = mtime - durationMillis;
      logger.debug("Wpilog wall-clock range from mtime (fallback): {} to {}",
          java.time.Instant.ofEpochMilli(startMillis),
          java.time.Instant.ofEpochMilli(mtime));
      return new long[]{startMillis, mtime, 1};
    } catch (IOException e) {
      logger.debug("Cannot read mtime for {}: {}", wpilogPath, e.getMessage());
      return new long[]{0, 0, 0};
    }
  }

  // ==================== NESTED RECORD CLASSES ====================
  // These were previously inner records and are now separate files,
  // but we keep them here for backwards compatibility with existing code
  // that imports ParsedLog, etc.

  /**
   * Metadata about an available WPILOG file.
   *
   * @param path The absolute file path
   * @param sizeBytes The file size in bytes
   * @param lastModified The last modified timestamp in milliseconds since epoch
   * @since 0.4.0
   */
  public record LogMetadata(String path, long sizeBytes, long lastModified) {}

  /**
   * Information about a loaded log in cache.
   *
   * @param path The file path
   * @param entryCount Number of entries in the log
   * @param duration Duration of the log in seconds
   * @param estimatedMemoryBytes Estimated memory usage
   * @since 0.4.0
   */
  public record LoadedLogInfo(
      String path, int entryCount, double duration, long estimatedMemoryBytes) {}

  // ==================== PUBLIC TEST ACCESSORS ====================
  // These methods provide access to internal state for testing without requiring reflection.
  // They are public to allow access from test classes in different packages.

  /** Test accessor: Checks if a specific log is in the cache. */
  public boolean testIsLogLoaded(String path) {
    Path normalized = Path.of(path).toAbsolutePath().normalize();
    return logCache.containsKey(normalized.toString());
  }

  /** Test accessor: Adds a log directly to the cache (for testing only). */
  public void testPutLog(String path, LogData log) {
    Path normalized = Path.of(path).toAbsolutePath().normalize();
    logCache.put(normalized.toString(), log);
  }

  /** Test accessor: Gets the security validator. */
  public SecurityValidator testGetSecurityValidator() {
    return securityValidator;
  }

  /** Test accessor: Gets the log cache. */
  public LogCache testGetLogCache() {
    return logCache;
  }

  /** Test accessor: Gets the decoder registry. */
  public StructDecoderRegistry testGetDecoderRegistry() {
    return decoderRegistry;
  }

  /** Test accessor: Triggers eviction check. */
  public void testEvictIfNeeded() {
    logCache.evictIfNeeded();
  }

  /** Test accessor: Checks if a log is in cache. */
  public boolean testContainsLog(String path) {
    return testIsLogLoaded(path);
  }

  /** Test accessor: Reads double from binary data. */
  public double testReadDouble(byte[] data, int offset) {
    return new org.triplehelix.wpilogmcp.log.subsystems.BinaryReader().readDouble(data, offset);
  }

  /** Test accessor: Reads float from binary data. */
  public float testReadFloat(byte[] data, int offset) {
    return new org.triplehelix.wpilogmcp.log.subsystems.BinaryReader().readFloat(data, offset);
  }

  /** Test accessor: Reads int32 from binary data. */
  public int testReadInt32(byte[] data, int offset) {
    return new org.triplehelix.wpilogmcp.log.subsystems.BinaryReader().readInt32(data, offset);
  }

  // Test accessors for struct decoding (delegate to registry)
  // Note: These methods slice the array from offset to support legacy test interface
  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodePose2d(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:Pose2d", sliced);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodePose3d(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:Pose3d", sliced);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodeTranslation2d(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:Translation2d", sliced);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodeTranslation3d(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:Translation3d", sliced);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodeRotation2d(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:Rotation2d", sliced);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodeRotation3d(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:Rotation3d", sliced);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodeTwist2d(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:Twist2d", sliced);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodeTwist3d(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:Twist3d", sliced);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodeChassisSpeeds(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:ChassisSpeeds", sliced);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodeSwerveModuleState(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:SwerveModuleState", sliced);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodeSwerveModulePosition(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:SwerveModulePosition", sliced);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodeTargetObservation(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:TargetObservation", sliced);
  }

  @SuppressWarnings("unchecked")
  java.util.List<java.util.Map<String, Object>> testDecodeTargetObservationArray(byte[] data) {
    return (java.util.List<java.util.Map<String, Object>>) decoderRegistry.decodeStruct("structarray:TargetObservation", data);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodePoseObservation(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:PoseObservation", sliced);
  }

  @SuppressWarnings("unchecked")
  java.util.List<java.util.Map<String, Object>> testDecodePoseObservationArray(byte[] data) {
    return (java.util.List<java.util.Map<String, Object>>) decoderRegistry.decodeStruct("structarray:PoseObservation", data);
  }

  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> testDecodeSwerveSample(byte[] data, int offset) {
    byte[] sliced = java.util.Arrays.copyOfRange(data, offset, data.length);
    return (java.util.Map<String, Object>) decoderRegistry.decodeStruct("struct:SwerveSample", sliced);
  }

  @SuppressWarnings("unchecked")
  java.util.List<java.util.Map<String, Object>> testDecodeSwerveSampleArray(byte[] data) {
    return (java.util.List<java.util.Map<String, Object>>) decoderRegistry.decodeStruct("structarray:SwerveSample", data);
  }
}
