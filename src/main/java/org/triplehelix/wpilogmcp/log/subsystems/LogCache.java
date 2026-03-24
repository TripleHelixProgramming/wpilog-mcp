package org.triplehelix.wpilogmcp.log.subsystems;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.ParsedLog;

/**
 * Thread-safe LRU cache for parsed robot logs with memory-based and count-based eviction.
 *
 * <p>This cache maintains a fixed maximum number of loaded logs and/or maximum memory usage. When
 * limits are exceeded, the least recently used log is evicted. Idle entries older than
 * {@code maxIdleMs} are also evicted periodically.
 *
 * <p>Uses LinkedHashMap with access-order for LRU behavior and ReentrantReadWriteLock for
 * thread safety. Note: {@code get()} requires a write lock because access-ordered
 * LinkedHashMap structurally modifies its internal linked list on every access.
 *
 * @since 0.4.0
 */
public class LogCache {
  private static final Logger logger = LoggerFactory.getLogger(LogCache.class);

  private final Map<String, ParsedLog> cache;
  private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
  private final MemoryEstimator memoryEstimator;

  /** Optional callback invoked when a log is evicted, for cleaning up associated resources. */
  private volatile java.util.function.Consumer<String> evictionCallback;

  /** Tracks the last access time for each cached log path (for idle eviction). */
  private final ConcurrentHashMap<String, Long> lastAccessTime = new ConcurrentHashMap<>();

  /** Maximum idle time in milliseconds before a log is eligible for idle eviction. */
  private volatile long maxIdleMs = 1_800_000; // 30 minutes

  private volatile int maxLoadedLogs = 20;
  private volatile int maxMemoryMb = 2048;

  /**
   * Creates a new LogCache with the specified memory estimator.
   *
   * @param memoryEstimator The memory estimator for calculating cache memory usage
   */
  public LogCache(MemoryEstimator memoryEstimator) {
    this.memoryEstimator = memoryEstimator;
    // LinkedHashMap with access-order for LRU behavior
    this.cache = new LinkedHashMap<>(16, 0.75f, true);
  }

  /**
   * Sets the maximum number of logs to keep in cache.
   *
   * @param maxLoadedLogs Maximum number of logs (must be > 0)
   */
  /**
   * Sets a callback to be invoked when a log is evicted from the cache.
   * Used to clean up associated resources (e.g., synchronized revlog data).
   *
   * @param callback A consumer that receives the evicted log's path
   */
  public void setEvictionCallback(java.util.function.Consumer<String> callback) {
    this.evictionCallback = callback;
  }

  public void setMaxLoadedLogs(int maxLoadedLogs) {
    if (maxLoadedLogs <= 0) {
      throw new IllegalArgumentException("maxLoadedLogs must be positive");
    }
    this.maxLoadedLogs = maxLoadedLogs;
    logger.info("Max loaded logs set to: {}", maxLoadedLogs);
  }

  /**
   * Sets the maximum memory usage in megabytes.
   *
   * @param maxMemoryMb Maximum memory in MB (must be > 0)
   */
  public void setMaxMemoryMb(int maxMemoryMb) {
    if (maxMemoryMb <= 0) {
      throw new IllegalArgumentException("maxMemoryMb must be positive");
    }
    this.maxMemoryMb = maxMemoryMb;
    logger.info("Max memory set to: {} MB", maxMemoryMb);
  }

  /**
   * Gets a log from the cache.
   *
   * @param path The log file path
   * @return The parsed log, or null if not in cache
   */
  public ParsedLog get(String path) {
    // Must use writeLock because LinkedHashMap with accessOrder=true
    // structurally modifies the internal linked list on get() calls.
    // Using readLock here would allow concurrent structural modifications,
    // risking ConcurrentModificationException or infinite loops.
    cacheLock.writeLock().lock();
    try {
      ParsedLog log = cache.get(path);
      if (log != null) {
        lastAccessTime.put(path, System.currentTimeMillis());
      }
      return log;
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Puts a log into the cache.
   *
   * @param path The log file path
   * @param log The parsed log
   */
  public void put(String path, ParsedLog log) {
    cacheLock.writeLock().lock();
    try {
      cache.put(path, log);
      lastAccessTime.put(path, System.currentTimeMillis());
      logger.debug("Added log to cache: {}", path);
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Removes a log from the cache.
   *
   * @param path The log file path
   * @return The removed log, or null if not in cache
   */
  public ParsedLog remove(String path) {
    cacheLock.writeLock().lock();
    try {
      ParsedLog removed = cache.remove(path);
      if (removed != null) {
        lastAccessTime.remove(path);
        logger.debug("Removed log from cache: {}", path);
      }
      return removed;
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Clears all logs from the cache.
   */
  public void clear() {
    cacheLock.writeLock().lock();
    try {
      // Invoke eviction callback for each entry before clearing
      var callback = this.evictionCallback;
      if (callback != null) {
        for (String path : new java.util.ArrayList<>(cache.keySet())) {
          try {
            callback.accept(path);
          } catch (Exception e) {
            logger.warn("Eviction callback failed for '{}': {}", path, e.getMessage());
          }
        }
      }
      cache.clear();
      lastAccessTime.clear();
      logger.info("Cleared all logs from cache");
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Gets the number of logs currently in cache.
   *
   * @return The cache size
   */
  public int size() {
    cacheLock.readLock().lock();
    try {
      return cache.size();
    } finally {
      cacheLock.readLock().unlock();
    }
  }

  /**
   * Checks if a log is in the cache.
   *
   * @param path The log file path
   * @return true if the log is cached
   */
  public boolean containsKey(String path) {
    cacheLock.readLock().lock();
    try {
      return cache.containsKey(path);
    } finally {
      cacheLock.readLock().unlock();
    }
  }

  /**
   * Gets all log paths currently in cache.
   *
   * @return List of all cached log paths
   */
  public java.util.List<String> getAllPaths() {
    cacheLock.readLock().lock();
    try {
      return new java.util.ArrayList<>(cache.keySet());
    } finally {
      cacheLock.readLock().unlock();
    }
  }

  /**
   * Gets all entries currently in cache.
   *
   * @return Map of all cached entries (path -> ParsedLog)
   */
  public Map<String, ParsedLog> getAllEntries() {
    cacheLock.readLock().lock();
    try {
      return new LinkedHashMap<>(cache);
    } finally {
      cacheLock.readLock().unlock();
    }
  }

  /**
   * Evicts logs until cache is within both count and memory limits, and removes idle entries.
   *
   * <p>Loops until all limits are satisfied (or no entries remain to evict).
   * Also evicts entries that have been idle longer than {@code maxIdleMs}.
   */
  public void evictIfNeeded() {
    cacheLock.writeLock().lock();
    try {
      // Evict idle entries first
      evictIdle();

      // Snapshot volatile config at method entry for consistent behavior
      int currentMaxLogs = this.maxLoadedLogs;
      int currentMaxMemoryMb = this.maxMemoryMb;

      // Loop for count-based eviction
      while (cache.size() > currentMaxLogs) {
        if (!evictLeastRecentlyUsed("count limit (" + currentMaxLogs + ")")) {
          break; // No entries remain
        }
      }

      // Loop for memory-based eviction
      while (true) {
        long estimatedMemoryBytes = memoryEstimator.estimateTotalMemory(cache.values());
        long estimatedMemoryMb = estimatedMemoryBytes / (1024 * 1024);

        if (estimatedMemoryMb <= currentMaxMemoryMb) {
          break;
        }

        logger.info(
            "Estimated memory usage: {} MB (limit: {} MB) - evicting least recently used log",
            estimatedMemoryMb,
            currentMaxMemoryMb);
        if (!evictLeastRecentlyUsed("memory limit (" + currentMaxMemoryMb + " MB)")) {
          break; // No entries remain
        }
      }
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Evicts the least recently used log from the cache.
   *
   * <p>Internal method - assumes write lock is already held.
   *
   * @param reason The reason for eviction (for logging)
   * @return true if a log was evicted, false if cache is empty
   */
  private boolean evictLeastRecentlyUsed(String reason) {
    if (cache.isEmpty()) {
      return false;
    }

    // First entry in LinkedHashMap with access-order is LRU
    String pathToEvict = cache.keySet().iterator().next();

    cache.remove(pathToEvict);
    lastAccessTime.remove(pathToEvict);
    logger.info("Evicted log '{}' due to {}", pathToEvict, reason);

    // Notify callback (e.g., to clean up syncCache entries)
    var callback = this.evictionCallback;
    if (callback != null) {
      try {
        callback.accept(pathToEvict);
      } catch (Exception e) {
        logger.warn("Eviction callback failed for '{}': {}", pathToEvict, e.getMessage());
      }
    }

    return true;
  }

  /**
   * Evicts cache entries that have been idle longer than {@code maxIdleMs}.
   *
   * <p>Internal method - assumes write lock is already held.
   */
  private void evictIdle() {
    long now = System.currentTimeMillis();
    long currentMaxIdleMs = this.maxIdleMs;
    var idlePaths = new java.util.ArrayList<String>();

    for (String path : cache.keySet()) {
      Long lastAccess = lastAccessTime.get(path);
      if (lastAccess != null && (now - lastAccess) > currentMaxIdleMs) {
        idlePaths.add(path);
      }
    }

    for (String path : idlePaths) {
      long idleMs = now - lastAccessTime.getOrDefault(path, 0L);
      cache.remove(path);
      lastAccessTime.remove(path);
      logger.info("Evicted idle log '{}' (idle for {} ms)", path, idleMs);

      var callback = this.evictionCallback;
      if (callback != null) {
        try {
          callback.accept(path);
        } catch (Exception e) {
          logger.warn("Eviction callback failed for '{}': {}", path, e.getMessage());
        }
      }
    }
  }

  /**
   * Gets statistics about the cache.
   *
   * @return Map with cache statistics (size, estimatedMemoryMb, maxLogs, maxMemoryMb)
   */
  public Map<String, Object> getStats() {
    cacheLock.readLock().lock();
    try {
      long estimatedMemoryBytes = memoryEstimator.estimateTotalMemory(cache.values());
      long estimatedMemoryMb = estimatedMemoryBytes / (1024 * 1024);

      return Map.of(
          "size",
          cache.size(),
          "estimatedMemoryMb",
          estimatedMemoryMb,
          "maxLogs",
          maxLoadedLogs,
          "maxMemoryMb",
          maxMemoryMb);
    } finally {
      cacheLock.readLock().unlock();
    }
  }
}
