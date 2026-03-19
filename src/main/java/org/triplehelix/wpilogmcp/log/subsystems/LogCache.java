package org.triplehelix.wpilogmcp.log.subsystems;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.ParsedLog;

/**
 * Thread-safe LRU cache for parsed robot logs with memory-based and count-based eviction.
 *
 * <p>This cache maintains a fixed maximum number of loaded logs and/or maximum memory usage. When
 * limits are exceeded, the least recently used log is evicted (unless it's the active log).
 *
 * <p>Uses LinkedHashMap with access-order for LRU behavior and ReentrantReadWriteLock for
 * thread-safe concurrent reads.
 *
 * @since 0.4.0
 */
public class LogCache {
  private static final Logger logger = LoggerFactory.getLogger(LogCache.class);

  private final Map<String, ParsedLog> cache;
  private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
  private final MemoryEstimator memoryEstimator;

  private volatile String activeLogPath;
  private int maxLoadedLogs = 20;
  private int maxMemoryMb = 2048;

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
   * Gets the currently active log path.
   *
   * @return The active log path, or null if none
   */
  public String getActiveLogPath() {
    return activeLogPath;
  }

  /**
   * Sets the currently active log path.
   *
   * @param activeLogPath The active log path
   */
  public void setActiveLogPath(String activeLogPath) {
    this.activeLogPath = activeLogPath;
  }

  /**
   * Gets a log from the cache.
   *
   * @param path The log file path
   * @return The parsed log, or null if not in cache
   */
  public ParsedLog get(String path) {
    cacheLock.readLock().lock();
    try {
      return cache.get(path);
    } finally {
      cacheLock.readLock().unlock();
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
      cache.clear();
      activeLogPath = null;
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
   * Evicts logs if cache limits are exceeded.
   *
   * <p>Eviction strategy:
   *
   * <ul>
   *   <li>Count-based: If cache size exceeds maxLoadedLogs, evict LRU log
   *   <li>Memory-based: If estimated memory exceeds maxMemoryMb, evict LRU log
   *   <li>Never evicts the active log
   * </ul>
   *
   * After eviction, calls System.gc() to encourage release of memory-mapped buffers.
   */
  public void evictIfNeeded() {
    cacheLock.writeLock().lock();
    try {
      // Check count-based eviction
      if (cache.size() > maxLoadedLogs) {
        evictLeastRecentlyUsed("count limit (" + maxLoadedLogs + ")");
        return;
      }

      // Check memory-based eviction
      long estimatedMemoryBytes = memoryEstimator.estimateTotalMemory(cache.values());
      long estimatedMemoryMb = estimatedMemoryBytes / (1024 * 1024);

      if (estimatedMemoryMb > maxMemoryMb) {
        logger.info(
            "Estimated memory usage: {} MB (limit: {} MB) - evicting least recently used log",
            estimatedMemoryMb,
            maxMemoryMb);
        evictLeastRecentlyUsed("memory limit (" + maxMemoryMb + " MB)");
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
   */
  private void evictLeastRecentlyUsed(String reason) {
    if (cache.isEmpty()) {
      return;
    }

    // Find LRU entry (first entry in LinkedHashMap with access-order)
    String pathToEvict = null;
    for (String path : cache.keySet()) {
      // Skip active log
      if (path.equals(activeLogPath)) {
        continue;
      }
      pathToEvict = path;
      break; // First non-active entry is LRU
    }

    if (pathToEvict == null) {
      logger.warn("Cannot evict: only active log remains in cache");
      return;
    }

    cache.remove(pathToEvict);
    logger.info("Evicted log '{}' due to {}", pathToEvict, reason);

    // Suggest GC to release memory-mapped buffers
    System.gc();
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
          maxMemoryMb,
          "activeLog",
          activeLogPath != null ? activeLogPath : "none");
    } finally {
      cacheLock.readLock().unlock();
    }
  }
}
