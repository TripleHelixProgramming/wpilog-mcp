package org.triplehelix.wpilogmcp.log.subsystems;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.LogData;

/**
 * Thread-safe LRU cache for loaded log files, backed by Caffeine.
 *
 * <p>Eviction is driven by:
 * <ul>
 *   <li><b>Idle time</b> — entries expire after 30 minutes of inactivity (configurable)
 *   <li><b>Heap pressure</b> — when free heap drops below 15% of max, LRU entries are evicted
 * </ul>
 *
 * <p>When a log is evicted, its {@link org.triplehelix.wpilogmcp.log.LazyParsedLog} is closed
 * to release memory-mapped file resources, and an optional eviction callback is invoked.
 *
 * <p>No configuration is needed — the cache automatically adapts to available heap. Users
 * control total capacity via {@code WPILOG_MAX_HEAP} (JVM heap size).
 *
 * @since 0.4.0
 */
public class LogCache {
  private static final Logger logger = LoggerFactory.getLogger(LogCache.class);

  /** Evict when free heap drops below this fraction of max heap. */
  private static final double HEAP_PRESSURE_THRESHOLD = 0.15;

  /** Default idle expiration: 30 minutes. */
  private static final long DEFAULT_IDLE_MS = 1_800_000;

  private final Cache<String, LogData> cache;

  /** Optional callback invoked when a log is evicted, for cleaning up associated resources. */
  private volatile java.util.function.Consumer<String> evictionCallback;

  public LogCache() {
    this(DEFAULT_IDLE_MS);
  }

  /**
   * Creates a LogCache with a custom idle expiration (for testing).
   *
   * @param idleMs Maximum idle time in milliseconds before automatic eviction
   */
  public LogCache(long idleMs) {
    this.cache = Caffeine.newBuilder()
        .expireAfterAccess(idleMs, TimeUnit.MILLISECONDS)
        .removalListener(this::onRemoval)
        .executor(Runnable::run) // Run removal listener synchronously (same thread)
        .build();
  }

  /**
   * Sets a callback to be invoked when a log is evicted from the cache.
   *
   * @param callback A consumer that receives the evicted log's path
   */
  public void setEvictionCallback(java.util.function.Consumer<String> callback) {
    this.evictionCallback = callback;
  }

  /**
   * Sets the maximum idle time before eviction.
   *
   * <p>Caffeine does not support changing expiration policy after construction,
   * so this is a no-op retained for API compatibility. Use the constructor parameter instead.
   *
   * @param maxIdleMs Maximum idle time in milliseconds (ignored after construction)
   * @deprecated Idle timeout is set at construction time via {@link #LogCache(long)}. This method is a no-op.
   */
  @Deprecated
  public void setMaxIdleMs(long maxIdleMs) {
    logger.debug("setMaxIdleMs({}) called — idle timeout is set at construction time", maxIdleMs);
  }

  /**
   * Gets a cached log by path.
   */
  public LogData get(String path) {
    return cache.getIfPresent(path);
  }

  /** Puts a log into the cache. */
  public void put(String path, LogData log) {
    cache.put(path, log);
  }

  /** Removes a log from the cache. Returns the removed log, or null. */
  public LogData remove(String path) {
    LogData removed = cache.getIfPresent(path);
    if (removed != null) {
      cache.invalidate(path);
    }
    return removed;
  }

  /** Checks if the cache contains a log. */
  public boolean containsKey(String path) {
    return cache.getIfPresent(path) != null;
  }

  /** Clears all entries, closing LazyParsedLog instances. */
  public void clear() {
    cache.invalidateAll();
    // Force synchronous cleanup so removal listener runs before we return
    cache.cleanUp();
  }

  /**
   * Evicts logs based on heap pressure and idle time.
   *
   * <p>First triggers Caffeine's built-in idle expiration cleanup, then evicts LRU entries
   * while JVM free heap is below the pressure threshold.
   */
  public void evictIfNeeded() {
    // Trigger pending idle expirations
    cache.cleanUp();

    // Evict LRU entries while under heap pressure
    while (isUnderHeapPressure() && !cache.asMap().isEmpty()) {
      if (!evictLeastRecentlyUsed("heap pressure")) {
        break;
      }
    }
  }

  /** Evicts the least recently used log. Returns true if a log was evicted. */
  public boolean evictOne() {
    return evictLeastRecentlyUsed("making room for large file");
  }

  /** Returns true if the cache is empty. */
  public boolean isEmpty() {
    return cache.asMap().isEmpty();
  }

  /** Gets all cached entries (path -> LogData). Returns a snapshot copy. */
  public Map<String, LogData> getAllEntries() {
    return new LinkedHashMap<>(cache.asMap());
  }

  /** Gets cache statistics. */
  public Map<String, Object> getStats() {
    var rt = Runtime.getRuntime();
    long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    long maxMb = rt.maxMemory() / (1024 * 1024);

    return Map.of(
        "cached_logs", cache.asMap().size(),
        "heap_used_mb", usedMb,
        "heap_max_mb", maxMb,
        "heap_pressure_threshold", String.format("%.0f%%", HEAP_PRESSURE_THRESHOLD * 100));
  }

  // ==================== Internal ====================

  /** Caffeine removal listener — closes lazy logs and invokes the eviction callback. */
  private void onRemoval(String path, LogData log, RemovalCause cause) {
    if (path == null || log == null) return;

    closeIfLazy(log);

    if (cause != RemovalCause.REPLACED) {
      logger.info("Evicted log '{}' ({})", path, cause.name().toLowerCase());
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

  /** Returns true if JVM heap usage exceeds the pressure threshold. */
  private boolean isUnderHeapPressure() {
    var rt = Runtime.getRuntime();
    long maxMemory = rt.maxMemory();
    long usedMemory = rt.totalMemory() - rt.freeMemory();
    double freeRatio = 1.0 - ((double) usedMemory / maxMemory);
    return freeRatio < HEAP_PRESSURE_THRESHOLD;
  }

  /**
   * Evicts the least recently accessed entry from the cache.
   * Uses Caffeine's expireAfterAccess policy to find the oldest entry.
   */
  private boolean evictLeastRecentlyUsed(String reason) {
    var policy = cache.policy().expireAfterAccess();
    if (policy.isEmpty()) return false;

    // Caffeine's ageOf() gives the time since last access — find the oldest
    var oldest = policy.get().oldest(1);
    if (oldest.isEmpty()) return false;

    String pathToEvict = oldest.keySet().iterator().next();
    cache.invalidate(pathToEvict);
    logger.info("Force-evicted log '{}' due to {}", pathToEvict, reason);
    return true;
  }

  /** Closes a LazyParsedLog to release memory-mapped file resources. */
  private void closeIfLazy(LogData log) {
    if (log instanceof org.triplehelix.wpilogmcp.log.LazyParsedLog lazyLog) {
      try {
        lazyLog.close();
      } catch (Exception e) {
        logger.debug("Error closing lazy log: {}", e.getMessage());
      }
    }
  }
}
