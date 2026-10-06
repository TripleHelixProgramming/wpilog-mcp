/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.Version;
import org.triplehelix.wpilogmcp.cache.CacheDirectory;
import org.triplehelix.wpilogmcp.cache.ContentFingerprint;
import org.triplehelix.wpilogmcp.cache.DiskCache;
import org.triplehelix.wpilogmcp.cache.SyncDiskCache;
import org.triplehelix.wpilogmcp.config.ClientLeases;
import org.triplehelix.wpilogmcp.log.LogDirectory.RevLogFileInfo;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.log.subsystems.LogCache;
import org.triplehelix.wpilogmcp.log.subsystems.LogParser;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcDatabase;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.sync.LogSynchronizer;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;
import org.triplehelix.wpilogmcp.sync.SynchronizedLogs.SyncedRevLog;
import org.triplehelix.wpilogmcp.sync.SynchronizedLogs;

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
 * </ul>
 *
 * <p>Struct values are decoded by each log's own schemas
 * ({@link StructSchemas}).
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
  private final SecurityValidator securityValidator;
  private final org.triplehelix.wpilogmcp.store.StoreRegistry stores;
  private final LogParser logParser;
  private final LogCache logCache;

  // Disk cache (initialized in constructor)
  private final DiskCache diskCache;
  private final CacheDirectory cacheDirectory;
  private final SyncDiskCache syncDiskCache;

  // RevLog integration (initialized in constructor)
  private final RevLogParser revLogParser;
  private final LogSynchronizer synchronizer;
  private final Map<String, SynchronizedLogs> syncCache = new ConcurrentHashMap<>();
  private final Map<String, CompletableFuture<Void>> syncInProgress =
      new ConcurrentHashMap<>();
  private final ExecutorService syncExecutor;
  private final ScheduledExecutorService evictionScheduler;
  private volatile boolean autoSyncEnabled = true;

  /** Per-path locks to prevent duplicate concurrent parses of the same log file. */
  private final ConcurrentHashMap<String, Object> loadLocks = new ConcurrentHashMap<>();

  /** A loaded log with its file as the file looked just before the log was read. */
  private record Loaded(LogData log, FileSnapshot snapshot) {}

  /**
   * Each loaded log's file snapshot, by path. Kept beside the cache rather than in the log, so
   * the eager parser's logs and logs put by tests need no snapshot of their own; a path without
   * one is never reloaded.
   */
  private final ConcurrentHashMap<String, Loaded> loaded = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, LiveLog> captures = new ConcurrentHashMap<>();

  /** Active captures are pinned outside the evictable cache; their writer owns their index. */
  public void beginCapture(LiveLog log) throws IOException {
    securityValidator.validate(Path.of(log.path()));
    unloadLog(log.path());
    log.resume();
    captures.put(log.path(), log);
  }

  /** The completed writer-built index remains cached until ordinary eviction. */
  public void finishCapture(LiveLog log) throws IOException {
    log.finish();
    var snapshot = FileSnapshot.of(Path.of(log.path()));
    loaded.put(log.path(), new Loaded(log, snapshot));
    logCache.put(log.path(), log);
    captures.remove(log.path(), log);
  }

  public void relocateCapture(LiveLog log, Path from, Path to) throws IOException {
    log.relocate(to);
    if (captures.remove(from.toString(), log)) captures.put(to.toString(), log);
  }

  private LiveLog captureAt(Path path) throws IOException {
    var direct = captures.get(path.toString());
    if (direct != null || captures.isEmpty() || !Files.exists(path)) return direct;
    var real = path.toRealPath();
    for (var capture : captures.values()) {
      var candidate = Path.of(capture.path());
      if (Files.exists(candidate) && candidate.toRealPath().equals(real)) return capture;
    }
    return null;
  }

  /**
   * A reload forced by a change to the file, or a result discarded because the file changed
   * while a call read it.
   *
   * @param generation How many times this has happened to the path, starting at 1
   * @param at When
   * @param change What changed, as {@link FileSnapshot#describeChange} words it
   * @since 0.9.1
   */
  public record Reload(int generation, Instant at, String change) {}

  /** The latest reload of each path, for telling each session once. */
  private final ConcurrentHashMap<String, Reload> reloads = new ConcurrentHashMap<>();

  /**
   * For each session, the reload generation of each path at the session's last call on it. A
   * session that has not called for two hours is forgotten, which matches the transports: a
   * stdio client is one session for the life of the process, and an HTTP session expires after
   * an hour idle.
   */
  private final Cache<String,
      ConcurrentHashMap<String, Integer>> sessionsSeen =
      Caffeine.newBuilder()
          .expireAfterAccess(2, TimeUnit.HOURS).build();

  /**
   * The REV log candidates each wpilog's last synchronization started from, with each file's
   * snapshot, so a REV log copied in later, or one that grew, is noticed and synchronized.
   */
  private final ConcurrentHashMap<String, Map<Path, FileSnapshot>> revCandidates =
      new ConcurrentHashMap<>();

  /** When each wpilog's REV candidates were last compared with the directory (nanoTime). */
  private final ConcurrentHashMap<String, Long> revChecked = new ConcurrentHashMap<>();

  /** How often the REV log tools look for REV files that changed, at most. */
  static final long REV_RECHECK_INTERVAL_NANOS = 2_000_000_000L;

  /** Package-private so tests can use an instance of their own (e.g. to shut one down). */
  LogManager() {
    this(new LogSynchronizer());
  }

  /** A controlled synchronizer lets tests hold a real background read across eviction. */
  LogManager(LogSynchronizer synchronizer) {
    // Initialize subsystems
    this.securityValidator = new SecurityValidator(ClientLeases.getInstance());
    this.stores = new org.triplehelix.wpilogmcp.store.StoreRegistry(securityValidator, this);
    this.logParser = new LogParser();
    this.logCache = new LogCache();

    // Initialize disk cache
    this.cacheDirectory = new CacheDirectory();
    this.diskCache = new DiskCache(cacheDirectory, Version.VERSION);
    this.syncDiskCache = new SyncDiskCache(cacheDirectory);

    // Initialize RevLog subsystems
    this.revLogParser = createRevLogParser();
    this.synchronizer = synchronizer;
    this.syncExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "revlog-sync");
      t.setDaemon(true);
      return t;
    });

    // Clean up what belongs to an evicted log: its snapshot, its sync results, and a sync still
    // running for it. Only its own: the path may already hold a newer instance (a reload, or a
    // load after an expiry the cache reports late), whose records must stay
    this.logCache.setEvictionCallback((evictedPath, evicted) -> {
      loaded.computeIfPresent(evictedPath, (k, v) -> v.log() == evicted ? null : v);
      // The sync results and a pending sync belong to the newer instance only when the sync
      // cache already holds that instance's entry; otherwise they are the evicted log's
      boolean[] anotherInstance = {false};
      syncCache.computeIfPresent(evictedPath, (k, v) -> {
        if (v.wpilog() == evicted) return null;
        anotherInstance[0] = true;
        return v;
      });
      if (!anotherInstance[0]) {
        revCandidates.remove(evictedPath);
        var pending = syncInProgress.remove(evictedPath);
        if (pending != null && !pending.isDone()) {
          pending.cancel(false);
          logger.debug("Cancelled sync for evicted log: {}", evictedPath);
        }
      }
    });

    // Schedule periodic idle eviction every 5 minutes
    this.evictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
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
        5, 5, TimeUnit.MINUTES);

    // Shutdown is coordinated by Main (HTTP: stop transport → drain → shutdown LogManager).
    // No independent shutdown hook here — avoids race with in-flight requests.
  }

  /**
   * Shuts down all executors, caches, and background resources.
   *
   * <p>Called automatically via a JVM shutdown hook. Safe to call multiple times.
   *
   * @since 0.9.0
   */
  public void shutdown() {
    logger.info("Shutting down LogManager");
    stores.close();
    evictionScheduler.shutdownNow();
    syncExecutor.shutdownNow();
    try {
      if (!syncExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
        logger.debug("Sync executor did not terminate within 3 seconds");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    diskCache.shutdown();
    captures.values().forEach(log -> { log.finish(); log.close(); });
    captures.clear();
    logCache.clear();
  }

  /** One owner for HTTP, inbox, command, and listing access to each store. */
  public org.triplehelix.wpilogmcp.store.StoreRegistry stores() {
    return stores;
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
  public Set<Path> getAllowedDirectories() {
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
   * <p>If the log is already cached and its file is as it was when the log was loaded, returns
   * the cached copy. A file that changed since (its size, modification time, or identity) is
   * loaded again, because the loaded log would answer from the old copy, or, when the file was
   * overwritten in place, apply the old record offsets to new bytes. Otherwise, parses the file
   * and adds it to the cache. May trigger eviction of the least recently used log if cache limits
   * are exceeded.
   *
   * @param path The file path (can be relative or absolute)
   * @return The parsed log
   * @throws IOException if the file cannot be read or is invalid, or if path is outside allowed
   *     directories
   * @see #acquire(String) for a read that must remain valid across eviction
   */
  public LogData loadLog(String path) throws IOException {
    try (var use = acquire(path)) {
      return use.log();
    }
  }

  /**
   * A call owns a use until its result (including annotations or a stream) is complete. Eviction
   * may remove the cache entry meanwhile, but cannot unmap bytes that this call still decodes.
   * The snapshot travels with the use because eviction also removes the manager's snapshot.
   */
  public static final class LogUse implements AutoCloseable {
    private final LogData log;
    private final FileSnapshot snapshot;
    private final LogFileAccess.Lease readClaim;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LogUse(LogData log, FileSnapshot snapshot, LogFileAccess.Lease readClaim) {
      this.log = log;
      this.snapshot = snapshot;
      this.readClaim = readClaim;
    }

    public LogData log() {
      return log;
    }

    public FileSnapshot snapshot() {
      return snapshot;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        try {
          if (log instanceof LazyParsedLog lazy) lazy.releaseUse();
        } finally {
          readClaim.close();
        }
      }
    }
  }

  private static LogUse retain(LogData log, FileSnapshot snapshot) throws IOException {
    if (log instanceof LiveLog live) {
      var view = live.retainView();
      return view == null ? null : new LogUse(view, snapshot, view::close);
    }
    if (log instanceof LazyParsedLog lazy) {
      if (!lazy.retain()) return null;
      return new LogUse(log, snapshot, () -> {});
    }
    // Eager results hold no mapping, but moving their source mid-call would invalidate the
    // call's file snapshot too. Synthetic in-memory logs have no file to claim.
    var path = Path.of(log.path());
    var claim = Files.isRegularFile(path) ? LogFileAccess.read(path) : (LogFileAccess.Lease) () -> {};
    return new LogUse(log, snapshot, claim);
  }

  /**
   * Loads and retains as one operation. Taking a bare cached log and retaining it later would
   * let an eviction unmap it between those steps; an already retired instance is retried.
   */
  public LogUse acquire(String path) throws IOException {
    Path filePath = Path.of(path).toAbsolutePath().normalize();

    // Validate path is allowed (or cache is already loaded)
    securityValidator.validateOrAllowCached(filePath, logCache::containsKey);
    if (!Files.exists(filePath)) {
      var destination = stores.resolveMoved(filePath);
      if (!destination.equals(filePath)) securityValidator.validate(destination);
      filePath = destination;
    }
    LogFileAccess.checkReadable(filePath);

    // Check if already cached (fast path, no lock needed), and still the file on disk
    String normalizedPath = filePath.toString();
    var capture = captureAt(filePath);
    if (capture != null) {
      var use = retain(capture, null);
      if (use != null) return use;
    }
    LogData cachedLog = logCache.get(normalizedPath);
    if (cachedLog != null && changeSinceLoad(normalizedPath, cachedLog) == null) {
      logger.debug("Returning cached log: {}", filePath);
      var use = retain(cachedLog, snapshotOf(path, cachedLog));
      if (use != null) return use;
    }

    // Keep per-path locks: removing one could give two callers different locks for one file.
    Object lock = loadLocks.computeIfAbsent(normalizedPath, k -> new Object());
    synchronized (lock) {
      LogFileAccess.checkReadable(filePath);
      capture = captureAt(filePath);
      if (capture != null) {
        var use = retain(capture, null);
        if (use != null) return use;
      }
      // Double-check cache after acquiring lock (another thread may have finished parsing,
      // or reloaded the changed file)
      cachedLog = logCache.get(normalizedPath);
      if (cachedLog != null) {
        var change = changeSinceLoad(normalizedPath, cachedLog);
        if (change == null) {
          logger.debug("Returning cached log (loaded by another thread): {}", filePath);
          var use = retain(cachedLog, snapshotOf(path, cachedLog));
          if (use != null) return use;
          logCache.remove(normalizedPath, cachedLog);
        } else {
          logger.info("Reloading {}: {}", filePath.getFileName(), change);
          fileChanged(normalizedPath, cachedLog, change);
        }
      }

      // Check file exists, and is a file this process can read: each is a fact about the
      // caller's file, so each gets an explained error, not an internal one
      if (!Files.exists(filePath)) {
        throw new LogFileException("File not found: " + filePath);
      }
      if (Files.isDirectory(filePath)) {
        throw new LogFileException("Not a log file: " + filePath + " is a directory. Pass the "
            + "path of a .wpilog file (list_available_logs lists them).");
      }
      if (!Files.isReadable(filePath)) {
        throw new LogFileException("Log file cannot be read: " + filePath
            + " (no read permission for the user the server runs as)");
      }

      // Evict cached logs to free memory for the new one.
      // Lazy loading keeps compact record offsets and decoded values on the heap.
      long fileSizeBytes = Files.size(filePath);
      logCache.evictIfNeeded();

      // If the file is large relative to available heap, evict more aggressively
      if (logCache.makeRoomFor(fileSizeBytes)) {
        logger.info("Unloaded logs to make room for {} ({} MB)", filePath.getFileName(),
            fileSizeBytes / (1024 * 1024));
      }

      // DataLogReader maps the whole file into one int-indexed ByteBuffer, so a file over 2 GB
      // cannot be read: say so here, before the reader fails and the eager fallback rethrows.
      if (fileSizeBytes > Integer.MAX_VALUE) {
        throw LogFileException.tooLarge(filePath, fileSizeBytes);
      }

      // The file as it is before it is read: a change during the read shows against this,
      // and the next call reloads
      var snapshot = FileSnapshot.of(filePath);
      if (snapshot == null) {
        throw new LogFileException("File not found: " + filePath);
      }

      // A single scan records offsets into the owned mapping; values are decoded only when
      // requested. The mapping must outlive both its cache entry and any in-flight uses.
      LogData log;
      try {
        long perLogBudgetBytes = getPerLogCacheBudgetBytes();
        log = LazyParsedLog.open(filePath, perLogBudgetBytes);
      } catch (LogFileException e) {
        // Not a log at all (empty, zeros, another format): the eager parser would only say
        // the same
        throw e;
      } catch (Exception e) {
        // If lazy scan fails (e.g., not a valid WPILOG), fall back to eager parse
        logger.debug("Lazy scan failed for {}, falling back to eager parse: {}",
            filePath.getFileName(), e.getMessage());
        try {
          log = logParser.parse(filePath);
        } catch (FileNotFoundException | FileSystemException opened) {
          // The file could not be opened after all (removed, or its permissions changed,
          // since the checks above)
          throw new LogFileException("Log file could not be opened: " + filePath + " ("
              + opened.getMessage() + ")");
        }
      }

      // Retain before publishing: even immediate heap-pressure eviction must leave this
      // caller a readable mapping. A concurrent import may have reserved the source meanwhile.
      var use = retain(log, snapshot);
      try {
        LogFileAccess.checkReadable(filePath);
        loaded.put(normalizedPath, new Loaded(log, snapshot));
        logCache.put(normalizedPath, log);
        logger.debug(
            "Loaded log with {} entries spanning {} seconds",
            log.entryCount(), String.format("%.2f", log.duration()));

        // Auto-sync matching revlogs asynchronously (doesn't block the MCP response)
        if (autoSyncEnabled) {
          try {
            autoSyncRevLogsAsync(log);
          } catch (RejectedExecutionException e) {
            // The sync executor is shut down (the server is stopping): the log still loads
            logger.warn("RevLog sync skipped for {}: the sync executor is shut down",
                filePath.getFileName());
            syncCache.remove(normalizedPath);
          }
        }

        // Evict again after adding the new log in case it pushed us over limits
        logCache.evictIfNeeded();

        return use;
      } catch (IOException | RuntimeException | Error e) {
        use.close();
        if (log instanceof LazyParsedLog lazy) lazy.close();
        throw e;
      }
    }
  }

  public record Release(boolean released, String reason) {}

  /**
   * Evicts every spelling of a path (or directory subtree), then gives its in-flight calls up
   * to three seconds to finish. A move holds a file-access reservation across this wait and
   * the rename, so requests arriving during it cannot reopen the file. Rechecking the cache
   * also catches a load that was already scanning when the reservation was taken.
   */
  public Release release(Path path) throws IOException {
    securityValidator.validate(path);
    var real = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
    for (var capture : captures.values()) {
      var candidate = Path.of(capture.path());
      if (Files.exists(candidate) && candidate.toRealPath().startsWith(real)) {
        return new Release(false, "An active capture is still writing in " + path);
      }
    }
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    do {
      for (var entry : logCache.getAllEntries().entrySet()) {
        var cached = Path.of(entry.getKey());
        var resolved = Files.exists(cached) ? cached.toRealPath() : cached.toAbsolutePath().normalize();
        if (resolved.startsWith(real)) logCache.remove(entry.getKey(), entry.getValue());
      }
      long remaining = Math.max(0, deadline - System.nanoTime());
      if (LogFileAccess.awaitFree(real, Duration.ofNanos(Math.min(remaining, 100_000_000)))) {
        return new Release(true, null);
      }
    } while (System.nanoTime() < deadline);
    return new Release(false, "Log is still held by an in-flight call or reader after waiting 3 seconds: "
        + path + ". Retry when the call finishes, or import by copy.");
  }

  /**
   * Gets a log by path, auto-loading from disk if not already cached.
   *
   * <p>This unretained view is useful for cache inspection. Code that decodes values uses
   * {@link #acquire(String)} so an import or heap-pressure eviction cannot unmap its reader.
   *
   * @param path The file path (can be relative or absolute)
   * @return The parsed log (never null)
   * @throws IOException if the file cannot be read or is invalid, or path is not allowed
   */
  public LogData getOrLoad(String path) throws IOException {
    return loadLog(path);
  }

  // ==================== FILES THAT CHANGE AFTER LOADING ====================

  /**
   * What changed about a loaded log's file since the log was loaded, or null when nothing did,
   * when the log has no snapshot (put by a test, or parsed eagerly), or when the file's
   * attributes cannot be read now (the log keeps serving; a read that fails will say so).
   */
  private String changeSinceLoad(String normalizedPath, LogData log) {
    var entry = loaded.get(normalizedPath);
    if (entry == null || entry.log() != log) return null;
    return changeSince(normalizedPath, entry.snapshot());
  }

  /**
   * What changed about the file at {@code path} since {@code snapshot} was taken, or null when
   * nothing did (or the attributes cannot be read now).
   */
  private static String changeSince(String normalizedPath, FileSnapshot snapshot) {
    try {
      var now = FileSnapshot.of(Path.of(normalizedPath));
      return snapshot.sameAs(now) ? null : snapshot.describeChange(now);
    } catch (IOException e) {
      logger.debug("Cannot read the attributes of {}: {}", normalizedPath, e.getMessage());
      return null;
    }
  }

  /**
   * The file snapshot a loaded log was read from, for {@link #changeDuringCall}: taken by the
   * caller before its call, so that an eviction during the call (idle, heap pressure) does not
   * lose it. Null for a log without one.
   *
   * @param path The log's path
   * @param log The instance the caller holds
   * @since 0.9.1
   */
  public FileSnapshot snapshotOf(String path, LogData log) {
    var entry = loaded.get(Path.of(log.path()).toAbsolutePath().normalize().toString());
    return entry != null && entry.log() == log ? entry.snapshot() : null;
  }

  /**
   * Whether the file changed while a call read the log, and what changed. A result read across
   * a change may hold old data (the file was renamed into place) or mix old and new bytes (it
   * was overwritten in place), so the caller discards it. The changed log is unloaded here, so
   * the next call loads the file as it is now, and the change is recorded for the sessions that
   * used the log.
   *
   * @param path The log's path
   * @param log The instance the call read
   * @param before Its snapshot from {@link #snapshotOf}, or null when it has none
   * @return What changed, or null when the file is as it was
   * @since 0.9.1
   */
  public String changeDuringCall(String path, LogData log, FileSnapshot before) {
    if (before == null) return null;
    String normalizedPath = Path.of(log.path()).toAbsolutePath().normalize().toString();
    var change = changeSince(normalizedPath, before);
    if (change != null) {
      logger.info("{} changed while a call read it: {}", Path.of(normalizedPath).getFileName(),
          change);
      fileChanged(normalizedPath, log, change);
    }
    return change;
  }

  /**
   * As {@link #changeDuringCall}, for a call whose read of the memory-mapped file faulted: the
   * file was truncated or rewritten in place under the mapping. The fault counts as the change
   * even when the attributes show none (the same size written again within the file system's
   * time resolution), and what the attributes do show is added.
   *
   * @param path The log's path
   * @param log The instance the call read
   * @param before Its snapshot from {@link #snapshotOf}, or null when it has none
   * @param fault The fault's message
   * @return What changed, never null
   * @since 0.9.1
   */
  public String faultDuringCall(String path, LogData log, FileSnapshot before, String fault) {
    String normalizedPath = Path.of(log.path()).toAbsolutePath().normalize().toString();
    var change = "a read of the file faulted (" + fault + "), which happens when the file is "
        + "truncated or rewritten while it is loaded";
    var attributes = before == null ? null : changeSince(normalizedPath, before);
    if (attributes != null) change += "; " + attributes;
    logger.info("{} changed while a call read it: {}", Path.of(normalizedPath).getFileName(),
        change);
    fileChanged(normalizedPath, log, change);
    return change;
  }

  /**
   * Records that a log's file changed under it, and unloads that instance (if it is still the
   * one loaded), so the next call loads the file as it is now.
   *
   * @param path The log's path
   * @param log The instance read from the old file
   * @param change What changed, for the sessions that used the log
   * @since 0.9.1
   */
  public void fileChanged(String path, LogData log, String change) {
    String normalizedPath = Path.of(path).toAbsolutePath().normalize().toString();
    reloads.compute(normalizedPath, (k, previous) -> new Reload(
        previous == null ? 1 : previous.generation() + 1, Instant.now(), change));
    logCache.remove(normalizedPath, log instanceof LiveLog.View view ? view.source() : log);
  }

  /**
   * The reload a session has not been told about yet, if its last call on this log came before
   * one: a session is told once per reload, and a session that first used the log after the
   * reload is not told at all, since no result it holds came from the old file. Every call
   * through this method counts as the session's latest.
   *
   * @param sessionKey The session (the stdio client counts as one session)
   * @param path The log's path
   * @return The reload to report, or null
   * @since 0.9.1
   */
  public Reload reloadNoticeFor(String sessionKey, String path) {
    String normalizedPath = Path.of(path).toAbsolutePath().normalize().toString();
    var reload = reloads.get(normalizedPath);
    int current = reload == null ? 0 : reload.generation();
    var seen = sessionsSeen.get(sessionKey, k -> new ConcurrentHashMap<>());
    Integer previous = seen.put(normalizedPath, current);
    return previous != null && previous < current ? reload : null;
  }

  /**
   * Looks again for the REV logs that belong to a loaded wpilog, and synchronizes them again
   * when the set of candidates or any candidate file changed since the last synchronization: a
   * REV log copied off the robot after the wpilog, or copied again once it had grown. Called by
   * the REV log tools, at most once per {@value #REV_RECHECK_INTERVAL_NANOS} ns per log; a
   * synchronization still running is left to finish. An offset set by hand is kept for a REV log
   * whose file did not change.
   *
   * @param wpilogPath The wpilog's path
   * @return Whether a new synchronization was started
   * @since 0.9.1
   */
  public boolean refreshRevLogsIfChanged(String wpilogPath) {
    String normalizedPath = Path.of(wpilogPath).toAbsolutePath().normalize().toString();
    var wpilog = logCache.get(normalizedPath);
    var before = revCandidates.get(normalizedPath);
    if (wpilog == null || before == null || !autoSyncEnabled) return false;
    if (isRevLogSyncInProgress(normalizedPath)) return false;
    // One look per interval, claimed atomically: two calls at once must not both synchronize
    long now = System.nanoTime();
    boolean[] claimed = {false};
    revChecked.compute(normalizedPath, (k, last) -> {
      if (last != null && now - last < REV_RECHECK_INTERVAL_NANOS) return last;
      claimed[0] = true;
      return now;
    });
    if (!claimed[0]) return false;

    var candidates = findMatchingRevLogs(wpilog);
    var current = snapshotsOf(candidates);
    if (current.equals(before)) return false;
    logger.info("REV logs of {} changed on disk ({} candidate(s), was {}); synchronizing again",
        Path.of(normalizedPath).getFileName(), current.size(), before.size());
    var previous = syncCache.get(normalizedPath);
    startRevLogSync(wpilog, candidates, previous, before);
    return true;
  }

  /** Each candidate's file snapshot, by path (a file that vanished meanwhile is left out). */
  private static Map<Path, FileSnapshot> snapshotsOf(List<RevLogFileInfo> candidates) {
    var snapshots = new HashMap<Path, FileSnapshot>();
    for (var info : candidates) {
      try {
        var snapshot = FileSnapshot.of(info.path());
        if (snapshot != null) snapshots.put(info.path(), snapshot);
      } catch (IOException e) {
        logger.debug("Cannot read the attributes of {}: {}", info.path(), e.getMessage());
      }
    }
    return snapshots;
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
    loaded.clear();
    revCandidates.clear();
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
  public Map<String, LogData> getAllLoadedLogs() {
    var all = new HashMap<String, LogData>(logCache.getAllEntries()); all.putAll(captures);
    return Map.copyOf(all);
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
    return getAllLoadedLogs().size();
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
      loaded.remove(normalizedPath);
      revCandidates.remove(normalizedPath);
    }
    return removed;
  }

  /**
   * Gets the list of paths for all loaded logs.
   *
   * @return List of paths for logs currently in cache
   */
  public List<String> getLoadedLogPaths() {
    return new ArrayList<>(getAllLoadedLogs().keySet());
  }

  /**
   * Gets metadata about currently loaded logs.
   *
   * @return List of metadata for loaded logs
   * @since 0.4.0
   */
  public List<LoadedLogInfo> listLoadedLogs() {
    var entries = getAllLoadedLogs();
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
  public SyncDiskCache getSyncDiskCache() {
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
    String normalized = Path.of(wpilogPath).toAbsolutePath().normalize().toString();
    var future = syncInProgress.get(normalized);
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
      future.get(timeoutMs, TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException e) {
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
    syncCache.put(Path.of(wpilogPath).toAbsolutePath().normalize().toString(), syncLogs);
  }

  /**
   * Replaces a wpilog's synchronized logs atomically (two set_revlog_offset calls on different
   * buses must not lose one), when the wpilog has any.
   *
   * @param wpilogPath The wpilog path key
   * @param update The replacement, computed from the current value
   * @return The new value, or null when the wpilog has no synchronized logs
   * @since 0.9.0
   */
  public SynchronizedLogs updateSynchronizedLogs(String wpilogPath,
      UnaryOperator<SynchronizedLogs> update) {
    return syncCache.computeIfPresent(
        Path.of(wpilogPath).toAbsolutePath().normalize().toString(), (k, v) -> update.apply(v));
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
    try (var use = acquire(wpilogPath)) {
      var wpilog = use.log();

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
  }

  /**
   * Starts asynchronous revlog synchronization for a wpilog that was just loaded: finds the REV
   * logs recorded with it and synchronizes them in the background.
   *
   * @param wpilog The parsed wpilog to sync revlogs for
   */
  private void autoSyncRevLogsAsync(LogData wpilog) {
    startRevLogSync(wpilog, findMatchingRevLogs(wpilog), null, Map.of());
  }

  /**
   * Starts asynchronous synchronization of the given REV logs with a wpilog.
   *
   * <p>Immediately puts a "pending" SynchronizedLogs (with no revlogs) into the sync cache so
   * tools can detect the in-progress state. The actual sync runs on a background thread. When
   * complete, the syncCache entry is atomically replaced with the final result.
   *
   * <p>A synchronization run again because the REV files changed keeps an offset the user set
   * with {@code set_revlog_offset}: the previous result of a REV log whose file is as it was is
   * carried over when its offset was set by hand, since the data decides the automatic result
   * and that data did not change, while the user's choice is not the server's to drop.
   *
   * @param wpilog The parsed wpilog to sync revlogs for
   * @param matchingRevLogs The REV logs to synchronize
   * @param previous The wpilog's synchronized logs before this run, or null on first load
   * @param before The candidates' files before this run, to tell unchanged ones
   */
  private void startRevLogSync(LogData wpilog, List<RevLogFileInfo> matchingRevLogs,
      SynchronizedLogs previous, Map<Path, FileSnapshot> before) {
    String wpilogPath = wpilog.path();

    // What this run starts from, so a later look can tell whether the REV files changed
    revCandidates.put(wpilogPath, snapshotsOf(matchingRevLogs));

    // Put a placeholder immediately so tools see "sync pending" rather than null
    var placeholder = new SynchronizedLogs(wpilog);
    syncCache.put(wpilogPath, placeholder);

    if (matchingRevLogs.isEmpty()) {
      logger.debug("No matching .revlog files found for {}", wpilogPath);
      return; // Placeholder with 0 revlogs is the final state
    }

    logger.info("Starting async sync of {} revlog file(s) with {}",
        matchingRevLogs.size(), wpilogPath);

    // Compute wpilog fingerprint once for all revlog cache lookups
    String fingerprint;
    try {
      fingerprint = ContentFingerprint.compute(
          Path.of(wpilogPath));
    } catch (IOException e) {
      logger.debug("Cannot fingerprint wpilog for sync cache: {}", e.getMessage());
      fingerprint = null; // skip cache lookup/save
    }
    final String wpilogFingerprint = fingerprint;

    var future = CompletableFuture.runAsync(() -> {
      LogUse use;
      try {
        use = retain(wpilog, snapshotOf(wpilogPath, wpilog));
      } catch (IOException e) {
        logger.debug("Sync reader could not acquire {}: {}", wpilogPath, e.getMessage());
        return;
      }
      if (use == null) return;
      try (use) {
        SynchronizedLogs.Builder builder = new SynchronizedLogs.Builder().wpilog(wpilog);

        for (RevLogFileInfo revlogInfo : matchingRevLogs) {
          try {
            var kept = userOffsetToKeep(previous, before, revlogInfo);
            if (kept != null) {
              addRevLog(builder, kept.revlog(), kept.syncResult(), revlogInfo);
              logger.info("Kept the offset set by hand for {}", revlogInfo.path().getFileName());
              continue;
            }

            if (wpilogFingerprint != null) {
              // Try sync disk cache first. A sync depends on both files' names as well as their
              // contents (the REV name's time sets the coarse offset, the wpilog's name the
              // zone), and the decoded values on the DBC, so all of them are part of the key
              String revlogFp = revlogCacheKey(revlogInfo, revLogParser.dbcContentHash());
              String wpilogKey = wpilogFingerprint + "|" + Path.of(wpilogPath).getFileName();
              var cached = syncDiskCache.load(wpilogKey, revlogFp);

              if (cached.isPresent()) {
                var entry = cached.get();
                // Keyed by content: an identical file elsewhere reports its own path
                var revlog = entry.revlog().at(revlogInfo.path().toString(),
                    revlogInfo.filenameTimestamp());
                if (overlaps(wpilog, revlog, entry.syncResult())) {
                  addRevLog(builder, revlog, entry.syncResult(), revlogInfo);
                }
                continue;
              }

              // Cache miss — parse and correlate
              ParsedRevLog revlog = revLogParser.parse(revlogInfo.path());
              SyncResult result = synchronizer.synchronize(wpilog, revlog);

              if (overlaps(wpilog, revlog, result)) addRevLog(builder, revlog, result, revlogInfo);

              // Save to sync cache
              syncDiskCache.save(revlog, result, wpilogKey, revlogFp);

              logger.info("Synced {} (confidence: {}, offset: {}ms)",
                  revlogInfo.path().getFileName(),
                  result.confidenceLevel().getLabel(),
                  result.offsetMillis());
            } else {
              ParsedRevLog revlog = revLogParser.parse(revlogInfo.path());
              SyncResult result = synchronizer.synchronize(wpilog, revlog);
              if (overlaps(wpilog, revlog, result)) addRevLog(builder, revlog, result, revlogInfo);
              logger.info("Synced {} (no cache, confidence: {}, offset: {}ms)",
                  revlogInfo.path().getFileName(),
                  result.confidenceLevel().getLabel(),
                  result.offsetMillis());
            }
          } catch (Exception e) {
            logger.warn("Failed to sync revlog {}: {}", revlogInfo.path(), e.getMessage());
          }
        }

        completeSync(wpilogPath, placeholder, builder.build());
      }
    }, syncExecutor);

    syncInProgress.put(wpilogPath, future);
    future.whenComplete((result, error) -> syncInProgress.remove(wpilogPath));
  }

  /**
   * The previous result for a REV log to carry into a new synchronization: the one whose offset
   * the user set by hand, when the file is as it was. Null when there is none to keep.
   */
  private static SyncedRevLog userOffsetToKeep(SynchronizedLogs previous,
      Map<Path, FileSnapshot> before, RevLogFileInfo info) {
    if (previous == null) return null;
    var earlier = before.get(info.path());
    if (earlier == null) return null;
    try {
      if (!earlier.sameAs(FileSnapshot.of(info.path()))) return null;
    } catch (IOException e) {
      return null;
    }
    for (var synced : previous.revlogs()) {
      if (synced.syncResult().method() == SyncMethod.USER_PROVIDED
          && Path.of(synced.revlog().path()).equals(info.path())) {
        return synced;
      }
    }
    return null;
  }

  /**
   * The revlog half of a sync cache key: the file's content fingerprint, its name (the name's
   * time sets the coarse offset), and the hash of the DBC that decoded it, so a replaced DBC
   * does not serve values decoded by the old one.
   */
  static String revlogCacheKey(RevLogFileInfo info, String dbcHash) throws IOException {
    return ContentFingerprint.compute(info.path()) + "|"
        + info.filename() + "|dbc:" + dbcHash;
  }

  /**
   * Adds a revlog under the bus its file name carries ({@code REV_..._canivore.revlog}), or
   * under the inferred name (rio, then can1, can2, ...) when it carries none.
   */
  static void addRevLog(SynchronizedLogs.Builder builder, ParsedRevLog revlog, SyncResult result,
      RevLogFileInfo info) {
    if (info.canBusName() != null && !info.canBusName().isBlank()) {
      builder.addRevLog(revlog, result, info.canBusName());
    } else {
      builder.addRevLog(revlog, result);
    }
  }

  /**
   * Whether a REV log, placed on the wpilog's clock by its sync, overlaps the wpilog at all. REV
   * logs are candidates by their name's time with minutes of tolerance, so one recorded just
   * before or after (another session, or the boot before) is a candidate too: when it neither
   * correlates nor overlaps, it is not this log's data and is not attached. A failed sync is kept,
   * to be reported.
   */
  static boolean overlaps(LogData wpilog, ParsedRevLog revlog, SyncResult result) {
    if (!result.isSuccessful()) return true;
    double start = revlog.minTimestamp() + result.offsetSeconds();
    double end = revlog.maxTimestamp() + result.offsetSeconds();
    boolean overlap = end >= wpilog.minTimestamp() && start <= wpilog.maxTimestamp();
    if (!overlap) {
      logger.info("{} not attached to {}: placed at {}-{} s, outside the log's {}-{} s",
          Path.of(revlog.path()).getFileName(), Path.of(wpilog.path()).getFileName(),
          String.format("%.1f", start), String.format("%.1f", end),
          String.format("%.1f", wpilog.minTimestamp()), String.format("%.1f", wpilog.maxTimestamp()));
    }
    return overlap;
  }

  /**
   * Replaces this sync's placeholder with its result, atomically and only if the placeholder is
   * still there: a log unloaded, evicted, or reloaded while its sync ran is not brought back (the
   * result holds the parsed REV logs and the wpilog itself, which would never be released).
   */
  private void completeSync(String wpilogPath, SynchronizedLogs placeholder,
      SynchronizedLogs result) {
    if (syncCache.replace(wpilogPath, placeholder, result)) {
      logger.info("RevLog sync complete for {}", Path.of(wpilogPath).getFileName());
    } else {
      logger.debug("RevLog sync result for {} discarded: the log was unloaded or reloaded "
          + "while it ran", Path.of(wpilogPath).getFileName());
    }
  }

  /**
   * Waits until every sync submitted so far has finished running (the executor runs one at a
   * time, in order), including syncs whose log was unloaded. For tests.
   *
   * @return false if the wait timed out
   */
  boolean awaitSyncExecutorIdle(long timeoutMs) throws InterruptedException {
    var marker = syncExecutor.submit(() -> { });
    try {
      marker.get(timeoutMs, TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException e) {
      return false;
    } catch (ExecutionException e) {
      return true;
    }
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
   * <p>Discovers revlogs by walking up to the configured scan depth under each configured log
   * directory that holds the wpilog, and under the wpilog's parent directory. Other configured
   * directories are not searched: one may hold another robot's REV logs from the same event,
   * which would match by time.
   */
  private List<RevLogFileInfo> findMatchingRevLogs(LogData wpilog) {
    var storeRoot = StoreCatalog.containing(Path.of(wpilog.path()));
    if (storeRoot.isPresent()) {
      try {
        var store = StoreCatalog.read(storeRoot.get(), securityValidator);
        var real = Path.of(wpilog.path()).toRealPath();
        var source = store.files().stream().filter(f -> f.path().equals(real)).findFirst();
        if (source.isEmpty() || source.get().session() == null) return List.of();
        return store.files().stream()
            .filter(f -> f.manifestPath().equals(source.get().manifestPath()))
            .filter(f -> f.file().kind().equals("revlog") && f.file().matching() != null
                && f.file().matching().wpilogSha256().equals(source.get().file().sha256()))
            .map(f -> LogDirectory.getInstance().extractRevLogInfo(f.path())).toList();
      } catch (IOException e) {
        logger.warn("Cannot read store REV associations: {}", e.getMessage());
        return List.of();
      }
    }
    // A wall clock never seen being set may read the roboRIO's default date, which every boot
    // shares: REV logs named with it would match every such log
    var unconfirmed = WallClock.unconfirmedReason(wpilog);
    if (unconfirmed.isPresent()) {
      logger.info("{}: {}", Path.of(wpilog.path()).getFileName(), unconfirmed.get());
      return List.of();
    }

    // Step 1: Determine the wpilog's wall-clock time window, and the zone REV log names are in
    var zone = WallClock.revlogFilenameZone(wpilog);
    long[] wallClockRange = estimateWallClockRange(wpilog, zone.offset());
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

    logger.debug("Revlog search window: {} to {} (tolerance: {} min, mtime fallback: {}); "
            + "REV log names read as {}",
        Instant.ofEpochMilli(rangeStart),
        Instant.ofEpochMilli(rangeEnd),
        toleranceMinutes, usingMtimeFallback, zone.basis());

    // Step 2: Discover revlogs (walk the configured scan depth) in the configured directories
    // that hold the wpilog, and in the wpilog's own directory tree (handles ad-hoc paths outside
    // them). Other configured directories are left out: one may hold another robot's REV logs
    // from the same event, which match by time
    var logDir = LogDirectory.getInstance();
    var wpilogFile = Path.of(wpilog.path());
    var searchDirs = new ArrayList<>(logDir.directoriesContaining(wpilogFile));
    if (wpilogFile.getParent() != null) searchDirs.add(wpilogFile.getParent());
    List<RevLogFileInfo> allRevLogs = logDir.listRevLogFilesInDirectories(searchDirs);

    if (allRevLogs.isEmpty()) {
      return List.of();
    }

    // Step 3: Filter by time overlap
    List<RevLogFileInfo> matching = new ArrayList<>();
    for (var revlog : allRevLogs) {
      Long revlogTimestamp = revlog.parsedTimestamp() == null ? null
          : revlog.parsedTimestamp().toInstant(zone.offset()).toEpochMilli();
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
   *   <li>The wall-clock entry (FPGA → wall clock mapping from the parsed log)</li>
   *   <li>Filename timestamp, read in {@code filenameZone} (the zone REV log names are read in,
   *       so the two names compare directly)</li>
   *   <li>File modification time (last resort, less accurate)</li>
   * </ol>
   *
   * @param wpilog The parsed wpilog
   * @param filenameZone The offset REV log filename times are read in
   * @return Array of [startMillis, endMillis, usingMtimeFallback (0 or 1)]
   */
  private long[] estimateWallClockRange(LogData wpilog, ZoneOffset filenameZone) {
    long durationMillis = (long) (wpilog.duration() * 1000);

    // Strategy 1: the wall-clock entry (WPILib systemTime, AdvantageKit EpochTimeMicros), read
    // from its first reading after the clock was set (earlier readings are 1970 or a default
    // date, and extrapolation back to the log's start uses FPGA time)
    var anchor = WallClock.first(wpilog);
    if (anchor.isPresent()) {
      long startMillis = (anchor.get().epochMicros() / 1000)
          - (long) ((anchor.get().logTime() - wpilog.minTimestamp()) * 1000);
      long endMillis = startMillis + durationMillis;
      logger.debug("Wpilog wall-clock range from {}: {} to {}", WallClock.entry(wpilog).orElse("?"),
          Instant.ofEpochMilli(startMillis), Instant.ofEpochMilli(endMillis));
      return new long[]{startMillis, endMillis, 0};
    }

    // Strategy 2: Parse filename timestamp
    Path wpilogPath = Path.of(wpilog.path());
    Long creationTime = WallClock.filenameTime(wpilogPath.getFileName().toString())
        .map(t -> t.toInstant(filenameZone).toEpochMilli()).orElse(null);
    if (creationTime != null) {
      long endMillis = creationTime + durationMillis;
      logger.debug("Wpilog wall-clock range from filename: {} to {}",
          Instant.ofEpochMilli(creationTime),
          Instant.ofEpochMilli(endMillis));
      return new long[]{creationTime, endMillis, 0};
    }

    // Strategy 3: File modification time (last resort — marks as mtime fallback)
    try {
      long mtime = Files.getLastModifiedTime(wpilogPath).toMillis();
      // mtime is approximately the end time; subtract duration to estimate start
      long startMillis = mtime - durationMillis;
      logger.debug("Wpilog wall-clock range from mtime (fallback): {} to {}",
          Instant.ofEpochMilli(startMillis),
          Instant.ofEpochMilli(mtime));
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

  /** Test accessor: Triggers eviction check. */
  public void testEvictIfNeeded() {
    logCache.evictIfNeeded();
  }

  /** Test accessor: Checks if a log is in cache. */
  public boolean testContainsLog(String path) {
    return testIsLogLoaded(path);
  }

  /**
   * Test accessor: forgets when a wpilog's REV candidates were last compared with the directory,
   * so the next {@link #refreshRevLogsIfChanged} looks at once instead of waiting out the
   * interval.
   */
  public void testForgetRevCheck(String path) {
    revChecked.remove(Path.of(path).toAbsolutePath().normalize().toString());
  }
}
