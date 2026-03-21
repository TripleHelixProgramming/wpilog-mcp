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
import org.triplehelix.wpilogmcp.log.subsystems.MemoryEstimator;
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
 *   <li>{@link MemoryEstimator} - Memory usage estimation for eviction
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
   * Default maximum number of logs to keep in cache before evicting least recently used.
   */
  private static final int DEFAULT_MAX_LOADED_LOGS = 20;

  /**
   * Maximum number of records to scan when extracting metadata from a log file. This prevents
   * excessive memory/time usage on very large logs.
   */
  static final int MAX_METADATA_RECORDS = 2000;

  // Subsystems (initialized in constructor)
  private final StructDecoderRegistry decoderRegistry;
  private final MemoryEstimator memoryEstimator;
  private final SecurityValidator securityValidator;
  private final LogParser logParser;
  private final LogCache logCache;

  // Disk cache (initialized in constructor)
  private final DiskCache diskCache;
  private final CacheDirectory cacheDirectory;

  // RevLog integration (initialized in constructor)
  private final RevLogParser revLogParser;
  private final LogSynchronizer synchronizer;
  private final Map<String, SynchronizedLogs> syncCache = new ConcurrentHashMap<>();
  private final Map<String, java.util.concurrent.CompletableFuture<Void>> syncInProgress =
      new ConcurrentHashMap<>();
  private final java.util.concurrent.ExecutorService syncExecutor;
  private volatile boolean autoSyncEnabled = true;

  /**
   * Configured maximum number of logs. If null, uses memory-based limiting if configured,
   * otherwise uses DEFAULT_MAX_LOADED_LOGS.
   */
  private Integer maxLoadedLogs = null;

  /**
   * Maximum memory (in MB) to use for log cache. Only used if maxLoadedLogs is not explicitly set.
   * If null, count-based limiting is used.
   */
  private Long maxMemoryMb = null;

  /** Whether maxLoadedLogs was explicitly set (vs using default). */
  private boolean maxLogsExplicitlySet = false;

  /** Private constructor for singleton pattern. */
  private LogManager() {
    // Initialize subsystems
    this.decoderRegistry = new StructDecoderRegistry();
    this.memoryEstimator = new MemoryEstimator();
    this.securityValidator = new SecurityValidator();
    this.logParser = new LogParser(decoderRegistry);
    this.logCache = new LogCache(memoryEstimator);

    // Initialize disk cache
    this.cacheDirectory = new CacheDirectory();
    this.diskCache = new DiskCache(cacheDirectory, org.triplehelix.wpilogmcp.Version.VERSION);

    // Initialize RevLog subsystems
    this.revLogParser = createRevLogParser();
    this.synchronizer = new LogSynchronizer();
    this.syncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "revlog-sync");
      t.setDaemon(true);
      return t;
    });

    // Set default cache limits
    this.logCache.setMaxLoadedLogs(DEFAULT_MAX_LOADED_LOGS);
    this.logCache.setMaxMemoryMb(2048); // Default 2GB

    // Clean up syncCache when logs are evicted to prevent memory leaks
    // Clean up syncCache AND cancel in-progress syncs when logs are evicted
    this.logCache.setEvictionCallback(evictedPath -> {
      syncCache.remove(evictedPath);
      var pending = syncInProgress.remove(evictedPath);
      if (pending != null && !pending.isDone()) {
        pending.cancel(false);
        logger.debug("Cancelled sync for evicted log: {}", evictedPath);
      }
    });
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
   *
   * @param max The maximum number of logs, or null to use default/memory-based
   */
  public void setMaxLoadedLogs(Integer max) {
    this.maxLoadedLogs = max;
    this.maxLogsExplicitlySet = (max != null);
    if (max != null) {
      logger.info("Max loaded logs set to: {}", max);
      logCache.setMaxLoadedLogs(max);
    } else {
      // Reset to default or memory-based
      if (maxMemoryMb == null) {
        logCache.setMaxLoadedLogs(DEFAULT_MAX_LOADED_LOGS);
      } else {
        logCache.setMaxLoadedLogs(Integer.MAX_VALUE); // Memory-based only
      }
    }
  }

  /**
   * Sets the maximum memory (in MB) to use for log cache. Only takes effect if maxLoadedLogs is
   * not explicitly set.
   *
   * @param mb The maximum memory in MB, or null to disable memory-based limiting
   */
  public void setMaxMemoryMb(Long mb) {
    this.maxMemoryMb = mb;
    if (mb != null) {
      logger.info("Max memory for log cache set to: {} MB", mb);
      logCache.setMaxMemoryMb(mb.intValue());

      // If no explicit max logs, use memory-based only
      if (!maxLogsExplicitlySet) {
        logCache.setMaxLoadedLogs(Integer.MAX_VALUE);
      }
    }
  }

  /**
   * Gets the effective maximum number of loaded logs.
   *
   * @return The max logs limit currently in effect
   */
  public int getEffectiveMaxLoadedLogs() {
    if (maxLoadedLogs != null) {
      return maxLoadedLogs;
    }
    // If memory-based limiting is in effect, return a high number
    // (actual eviction is handled by memory checks)
    if (maxMemoryMb != null && !maxLogsExplicitlySet) {
      return Integer.MAX_VALUE;
    }
    return DEFAULT_MAX_LOADED_LOGS;
  }

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
  public ParsedLog loadLog(String path) throws IOException {
    Path filePath = Path.of(path).toAbsolutePath().normalize();

    // Validate path is allowed (or cache is already loaded)
    securityValidator.validateOrAllowCached(filePath, logCache::containsKey);

    // Check if already cached
    ParsedLog cachedLog = logCache.get(filePath.toString());
    if (cachedLog != null) {
      logger.debug("Returning cached log: {}", filePath);
      logCache.setActiveLogPath(filePath.toString());
      return cachedLog;
    }

    // Check file exists
    if (!Files.exists(filePath)) {
      throw new IOException("File not found: " + filePath);
    }

    // Check file size against available memory to prevent OOM.
    // Note: Struct-heavy logs (e.g., SwerveModuleState arrays) expand more than
    // simple numeric logs due to LinkedHashMap overhead per decoded field.
    // The 5x multiplier is conservative for typical FRC logs; worst case for
    // struct-dense data can be higher.
    long fileSizeBytes = Files.size(filePath);
    long fileSizeMb = fileSizeBytes / (1024 * 1024);
    Runtime runtime = Runtime.getRuntime();
    long availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    long estimatedHeapNeeded = fileSizeBytes * 5;
    if (estimatedHeapNeeded > availableMemory) {
      throw new IOException(
          String.format(
              "Log file too large to load safely: %s is %d MB, estimated heap needed ~%d MB, "
                  + "but only %d MB available. Increase JVM heap size (-Xmx) or use a smaller log file.",
              filePath.getFileName(), fileSizeMb, estimatedHeapNeeded / (1024 * 1024),
              availableMemory / (1024 * 1024)));
    }

    // Evict before parsing to free memory for the new log.
    // This prevents OOM when the cache is full and the new log is large.
    logCache.evictIfNeeded();

    // Try disk cache before full parse
    ParsedLog log = null;
    var diskCached = diskCache.load(filePath);
    if (diskCached.isPresent()) {
      log = diskCached.get();
      logger.info("Loaded from disk cache: {}", filePath.getFileName());
    } else {
      // Full parse (expensive)
      logger.info("Parsing log file: {}", filePath);
      log = logParser.parse(filePath);

      // Save to disk cache asynchronously
      diskCache.saveAsync(log, filePath);
    }

    // Add to in-memory cache and set as active
    logCache.put(filePath.toString(), log);
    logCache.setActiveLogPath(filePath.toString());
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
  }

  /**
   * Gets the currently active log (the target of queries).
   *
   * @return The active log, or null if no log is active
   */
  public ParsedLog getActiveLog() {
    String activePath = logCache.getActiveLogPath();
    if (activePath == null) {
      return null;
    }
    return logCache.get(activePath);
  }

  /**
   * Sets the active log by path. The log must already be loaded.
   *
   * @param path The path to the log to make active
   * @return true if the log was found and made active, false if not found
   */
  public boolean setActiveLog(String path) {
    Path filePath = Path.of(path).toAbsolutePath().normalize();
    String normalizedPath = filePath.toString();

    // Use atomic setActiveIfPresent to avoid TOCTOU race where the log
    // could be evicted between a containsKey check and setActiveLogPath call.
    if (logCache.setActiveIfPresent(normalizedPath)) {
      logger.info("Active log set to: {}", normalizedPath);
      return true;
    }

    logger.warn("Cannot set active log: log not found in cache: {}", normalizedPath);
    return false;
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
   * Gets all loaded logs as a map of path to ParsedLog.
   *
   * <p>Returns a snapshot copy — safe to iterate without holding locks.
   * Use this instead of accessing LogCache directly from tools.
   *
   * @return Map of file paths to their parsed logs
   * @since 0.5.0
   */
  public java.util.Map<String, ParsedLog> getAllLoadedLogs() {
    return logCache.getAllEntries();
  }

  /**
   * Gets the estimated memory usage of all cached logs in megabytes.
   *
   * @return Estimated memory usage in MB
   */
  public long getEstimatedMemoryUsageMb() {
    var stats = logCache.getStats();
    return (long) stats.get("estimatedMemoryMb");
  }

  /**
   * Gets the number of logs currently loaded in cache.
   *
   * @return The number of loaded logs
   */
  public int getLoadedLogCount() {
    return logCache.size();
  }

  /**
   * Resets the configuration to defaults (for testing).
   */
  public void resetConfiguration() {
    clearAllLogs();
    clearAllowedDirectories();
    maxLoadedLogs = null;
    maxMemoryMb = null;
    maxLogsExplicitlySet = false;
    logCache.setMaxLoadedLogs(DEFAULT_MAX_LOADED_LOGS);
    logCache.setMaxMemoryMb(2048);
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

      // Clear active log if it was the one unloaded
      if (normalizedPath.equals(logCache.getActiveLogPath())) {
        logCache.setActiveLogPath(null);
      }
    }
    return removed;
  }

  /**
   * Gets the path to the currently active log.
   *
   * @return The active log path, or null if no log is active
   */
  public String getActiveLogPath() {
    return logCache.getActiveLogPath();
  }

  /**
   * Gets the list of paths for all loaded logs.
   *
   * @return List of paths for logs currently in cache
   */
  public List<String> getLoadedLogPaths() {
    return logCache.getAllPaths();
  }

  /**
   * Gets the maximum number of logs setting.
   *
   * @return The configured max loaded logs, or null if using default/memory-based
   */
  public Integer getMaxLoadedLogs() {
    return maxLoadedLogs;
  }

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
    String activePath = logCache.getActiveLogPath();

    for (var entry : entries.entrySet()) {
      String path = entry.getKey();
      ParsedLog log = entry.getValue();
      long estimatedMemory = memoryEstimator.estimateLogMemory(log);
      boolean isActive = path.equals(activePath);

      result.add(
          new LoadedLogInfo(path, log.entryCount(), log.duration(), estimatedMemory, isActive));
    }

    return result;
  }

  /**
   * Gets statistics about the log cache.
   *
   * @return Map with cache statistics (size, memory usage, limits)
   * @since 0.4.0
   */
  public java.util.Map<String, Object> getCacheStats() {
    return logCache.getStats();
  }

  /**
   * Gets detailed memory statistics including heap usage.
   *
   * <p>This method provides both estimated memory usage (based on heuristics) and actual JVM heap
   * usage for comparison and validation.
   *
   * @return Map with memory statistics:
   *     <ul>
   *       <li>estimatedMemoryMb - Heuristic estimate of log cache memory usage
   *       <li>heapUsedMb - Actual JVM heap memory currently in use
   *       <li>heapMaxMb - Maximum heap memory available to JVM
   *       <li>heapFreeMb - Free heap memory available
   *       <li>heapUtilization - Percentage of heap currently used (0-100)
   *       <li>estimationAccuracy - Estimated/Actual ratio (1.0 = perfect estimate)
   *     </ul>
   *
   * @since 0.4.0
   */
  public java.util.Map<String, Object> getMemoryStats() {
    Runtime runtime = Runtime.getRuntime();

    // Get estimated memory from cache
    long estimatedBytes = memoryEstimator.estimateTotalMemory(logCache.getAllEntries().values());
    long estimatedMb = estimatedBytes / (1024 * 1024);

    // Get actual heap usage
    long heapMax = runtime.maxMemory();
    long heapTotal = runtime.totalMemory();
    long heapFree = runtime.freeMemory();
    long heapUsed = heapTotal - heapFree;

    long heapUsedMb = heapUsed / (1024 * 1024);
    long heapMaxMb = heapMax / (1024 * 1024);
    long heapFreeMb = heapFree / (1024 * 1024);
    double heapUtilization = (heapUsed * 100.0) / heapTotal;

    // Calculate estimation accuracy (estimated / actual)
    // Note: This is approximate since heap includes more than just log data
    double estimationAccuracy =
        heapUsedMb > 0 ? (double) estimatedMb / heapUsedMb : Double.NaN;

    var stats = new java.util.LinkedHashMap<String, Object>();
    stats.put("estimatedMemoryMb", estimatedMb);
    stats.put("heapUsedMb", heapUsedMb);
    stats.put("heapMaxMb", heapMaxMb);
    stats.put("heapFreeMb", heapFreeMb);
    stats.put("heapUtilization", String.format("%.1f%%", heapUtilization));
    stats.put(
        "estimationAccuracy",
        Double.isNaN(estimationAccuracy) ? "N/A" : String.format("%.2f", estimationAccuracy));
    stats.put("loadedLogCount", logCache.size());

    return stats;
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
   * Checks if revlog synchronization is currently in progress for the active log.
   *
   * @return true if a background sync is running
   * @since 0.5.0
   */
  public boolean isRevLogSyncInProgress() {
    String activePath = logCache.getActiveLogPath();
    if (activePath == null) return false;
    var future = syncInProgress.get(activePath);
    return future != null && !future.isDone();
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
   * Waits for the active log's revlog synchronization to complete.
   *
   * @param timeoutMs Maximum time to wait in milliseconds
   * @return true if sync completed (or was not in progress), false if timed out
   * @since 0.5.0
   */
  public boolean waitForRevLogSync(long timeoutMs) {
    String activePath = logCache.getActiveLogPath();
    if (activePath == null) return true;
    var future = syncInProgress.get(activePath);
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
   * Enables or disables automatic RevLog synchronization when loading wpilog files.
   *
   * @param enabled true to enable auto-sync (default), false to disable
   * @since 0.5.0
   */
  public void setAutoSyncEnabled(boolean enabled) {
    this.autoSyncEnabled = enabled;
    logger.info("RevLog auto-sync {}", enabled ? "enabled" : "disabled");
  }

  /**
   * Checks if automatic RevLog synchronization is enabled.
   *
   * @return true if auto-sync is enabled
   * @since 0.5.0
   */
  public boolean isAutoSyncEnabled() {
    return autoSyncEnabled;
  }

  /**
   * Gets the synchronized logs for the active wpilog, including any auto-synced revlogs.
   *
   * @return The SynchronizedLogs container, or null if no active log
   * @since 0.5.0
   */
  public SynchronizedLogs getSynchronizedLogs() {
    String activePath = logCache.getActiveLogPath();
    if (activePath == null) {
      return null;
    }
    return syncCache.get(activePath);
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
   * Manually synchronizes a revlog with the active wpilog.
   *
   * @param revlogPath Path to the revlog file
   * @return The sync result
   * @throws IOException if the files cannot be read
   * @since 0.5.0
   */
  public SyncResult syncRevLog(String revlogPath) throws IOException {
    ParsedLog wpilog = getActiveLog();
    if (wpilog == null) {
      throw new IllegalStateException("No active wpilog loaded");
    }

    Path revPath = Path.of(revlogPath).toAbsolutePath().normalize();
    ParsedRevLog revlog = revLogParser.parse(revPath);
    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    // Update sync cache atomically to prevent TOCTOU race
    String wpilogPath = logCache.getActiveLogPath();
    syncCache.compute(wpilogPath, (key, existing) -> {
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
  private void autoSyncRevLogsAsync(ParsedLog wpilog) {
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

    var future = java.util.concurrent.CompletableFuture.runAsync(() -> {
      SynchronizedLogs.Builder builder = new SynchronizedLogs.Builder().wpilog(wpilog);

      for (RevLogFileInfo revlogInfo : matchingRevLogs) {
        try {
          ParsedRevLog revlog = revLogParser.parse(revlogInfo.path());
          SyncResult result = synchronizer.synchronize(wpilog, revlog);

          builder.addRevLog(revlog, result);

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
   * Finds revlog files that match the given wpilog by being in the same directory
   * or having similar timestamps.
   */
  private List<RevLogFileInfo> findMatchingRevLogs(ParsedLog wpilog) {
    List<RevLogFileInfo> matching = new ArrayList<>();

    // First, look in the same directory as the wpilog
    Path wpilogPath = Path.of(wpilog.path());
    Path wpilogDir = wpilogPath.getParent();

    if (wpilogDir != null && Files.isDirectory(wpilogDir)) {
      try {
        scanDirectoryForRevLogs(wpilogDir, matching);
      } catch (IOException e) {
        logger.debug("Error scanning for revlogs in {}: {}", wpilogDir, e.getMessage());
      }
    }

    // Also check allowed directories
    var allowedDirs = securityValidator.getAllowedDirectories();
    for (Path dir : allowedDirs) {
      if (!Files.isDirectory(dir) || dir.equals(wpilogDir)) {
        continue;
      }
      try {
        scanDirectoryForRevLogs(dir, matching);
      } catch (IOException e) {
        logger.debug("Error scanning for revlogs in {}: {}", dir, e.getMessage());
      }
    }

    return matching;
  }

  /**
   * Scans a directory for revlog files.
   */
  private void scanDirectoryForRevLogs(Path dir, List<RevLogFileInfo> revlogs) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.revlog")) {
      for (Path path : stream) {
        if (Files.isRegularFile(path)) {
          try {
            long size = Files.size(path);
            // Create RevLogFileInfo with null timestamps (will be parsed later if needed)
            revlogs.add(new RevLogFileInfo(path, null, null, null, size));
          } catch (IOException e) {
            logger.debug("Error reading revlog metadata: {}", e.getMessage());
          }
        }
      }
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
   * @param isActive Whether this is the active log
   * @since 0.4.0
   */
  public record LoadedLogInfo(
      String path, int entryCount, double duration, long estimatedMemoryBytes, boolean isActive) {}

  // ==================== PUBLIC TEST ACCESSORS ====================
  // These methods provide access to internal state for testing without requiring reflection.
  // They are public to allow access from test classes in different packages.

  /** Test accessor: Gets the number of currently loaded logs. */
  public int testGetLoadedLogCount() {
    return logCache.size();
  }

  /** Test accessor: Checks if a specific log is in the cache. */
  public boolean testIsLogLoaded(String path) {
    Path normalized = Path.of(path).toAbsolutePath().normalize();
    return logCache.containsKey(normalized.toString());
  }

  /** Test accessor: Gets the active log path. */
  public String testGetActiveLogPath() {
    return logCache.getActiveLogPath();
  }

  /** Test accessor: Adds a log directly to the cache (for testing only). */
  public void testPutLog(String path, ParsedLog log) {
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

  /** Test accessor: Sets the active log path. */
  public void testSetActiveLogPath(String path) {
    logCache.setActiveLogPath(path);
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
