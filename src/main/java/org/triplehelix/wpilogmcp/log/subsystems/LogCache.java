/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
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
 *   <li><b>Heap pressure</b> — when free heap stays below 15% of max after a garbage collection,
 *       LRU entries are evicted one at a time, with a collection after each, until it recovers
 * </ul>
 *
   * <p>When a log is evicted, its {@link org.triplehelix.wpilogmcp.log.LazyParsedLog} is retired:
   * cached values are dropped, but its mapping stays alive until its final acquired use closes.
   * An eviction callback cleans up the manager's other records for that instance.
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

  /**
   * What the cache reads from the heap. Tests substitute their own.
   */
  interface Heap {
    /** Bytes in use, which counts garbage until a collection removes it. */
    long usedBytes();

    long maxBytes();

    /** Collects garbage, so that {@link #usedBytes()} counts what is still referenced. */
    void collect();
  }

  /** The JVM's own heap. */
  private static final Heap JVM_HEAP = new Heap() {
    @Override
    public long usedBytes() {
      var rt = Runtime.getRuntime();
      return rt.totalMemory() - rt.freeMemory();
    }

    @Override
    public long maxBytes() {
      return Runtime.getRuntime().maxMemory();
    }

    @Override
    public void collect() {
      System.gc();
    }
  };

  private final Cache<String, LogData> cache;
  private final Heap heap;
  private final java.util.concurrent.ConcurrentHashMap<String, LogData> pinned =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Held while unloading for heap pressure or to make room, so that checks made at the same
   * time unload between them only what one would. It is taken by nothing else: reads and puts
   * never wait for it.
   */
  private final Object evictionLock = new Object();

  /**
   * Optional callback invoked with the path and the log when a log is evicted, for cleaning up
   * associated resources. It gets the instance because a path can be loaded again before the
   * callback runs for the old instance (an expired entry is reported when the cache next does
   * its upkeep, which may be after a reload): what belongs to the new instance must stay.
   */
  private volatile java.util.function.BiConsumer<String, LogData> evictionCallback;

  public LogCache() {
    this(DEFAULT_IDLE_MS);
  }

  /**
   * Creates a LogCache with a custom idle expiration (for testing).
   *
   * @param idleMs Maximum idle time in milliseconds before automatic eviction
   */
  public LogCache(long idleMs) {
    this(idleMs, JVM_HEAP);
  }

  /** A cache that judges heap pressure by {@code heap} (for testing). */
  LogCache(long idleMs, Heap heap) {
    this(idleMs, heap, com.github.benmanes.caffeine.cache.Ticker.systemTicker());
  }

  LogCache(long idleMs, com.github.benmanes.caffeine.cache.Ticker ticker) {
    this(idleMs, JVM_HEAP, ticker);
  }

  private LogCache(long idleMs, Heap heap, com.github.benmanes.caffeine.cache.Ticker ticker) {
    this.heap = heap;
    this.cache = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterAccess(idleMs, TimeUnit.MILLISECONDS)
        .removalListener(this::onRemoval)
        .executor(Runnable::run) // Run removal listener synchronously (same thread)
        .build();
  }

  /**
   * Sets a callback to be invoked when a log is evicted from the cache.
   *
   * @param callback A consumer that receives the evicted log's path and the evicted instance
   */
  public void setEvictionCallback(java.util.function.BiConsumer<String, LogData> callback) {
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

  /** Test-owned logs have no file to reload. Keep them until their admission scope unloads them. */
  public void putPinned(String path, LogData log) {
    synchronized (evictionLock) {
      cache.put(path, log);
      pinned.put(path, log);
    }
  }

  /** Removes a log from the cache. Returns the removed log, or null. */
  public LogData remove(String path) {
    LogData removed = cache.getIfPresent(path);
    if (removed != null) {
      cache.invalidate(path);
    }
    return removed;
  }

  /**
   * Removes a log only while the cache still holds that instance under the path: a reload or a
   * discard after the file changed must not remove a newer instance another call loaded since.
   *
   * @return Whether the instance was removed
   * @since 0.9.1
   */
  public boolean remove(String path, LogData log) {
    return cache.asMap().remove(path, log);
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
   *
   * <p>The heap's used bytes count garbage, and what an unloaded log held is garbage until it
   * is collected. So pressure is judged after a collection, and again after each unloaded log:
   * measuring without one sees no change and goes on to unload every log.
   */
  public void evictIfNeeded() {
    // Trigger pending idle expirations
    cache.cleanUp();

    // The ordinary case: no pressure, or nothing to unload. No collection is forced.
    if (!isUnderHeapPressure() || cache.asMap().isEmpty()) return;

    synchronized (evictionLock) {
      // Another check may have relieved the pressure while this one waited
      if (!isUnderHeapPressure()) return;
      heap.collect();
      while (isUnderHeapPressure() && evictLeastRecentlyUsed("heap pressure")) {
        heap.collect();
      }
    }
  }

  /** Evicts the least recently used log. Returns true if a log was evicted. */
  public boolean evictOne() {
    return evictLeastRecentlyUsed("making room for large file");
  }

  /**
   * Unloads least recently used logs until {@code bytes} of heap are free, or no log is left.
   * Free heap is judged after a collection, as in {@link #evictIfNeeded()}.
   *
   * @return Whether any log was unloaded
   */
  public boolean makeRoomFor(long bytes) {
    if (hasRoomFor(bytes) || cache.asMap().isEmpty()) return false;

    synchronized (evictionLock) {
      if (hasRoomFor(bytes)) return false;
      heap.collect();
      boolean evicted = false;
      while (!hasRoomFor(bytes) && evictLeastRecentlyUsed("making room for large file")) {
        evicted = true;
        heap.collect();
      }
      return evicted;
    }
  }

  private boolean hasRoomFor(long bytes) {
    return bytes <= heap.maxBytes() - heap.usedBytes();
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

    pinned.remove(path, log);
    closeIfLazy(log);

    if (cause != RemovalCause.REPLACED) {
      logger.info("Evicted log '{}' ({})", path, cause.name().toLowerCase());
      var callback = this.evictionCallback;
      if (callback != null) {
        try {
          callback.accept(path, log);
        } catch (Exception e) {
          logger.warn("Eviction callback failed for '{}': {}", path, e.getMessage());
        }
      }
    }
  }

  /** Returns true if JVM heap usage exceeds the pressure threshold. */
  private boolean isUnderHeapPressure() {
    double freeRatio = 1.0 - ((double) heap.usedBytes() / heap.maxBytes());
    return freeRatio < HEAP_PRESSURE_THRESHOLD;
  }

  /**
   * Evicts the least recently accessed entry from the cache.
   * Uses Caffeine's expireAfterAccess policy to find the oldest entry.
   */
  private boolean evictLeastRecentlyUsed(String reason) {
    var policy = cache.policy().expireAfterAccess();
    if (policy.isEmpty()) return false;

    synchronized (evictionLock) {
      // The first unpinned entry is among at most pins + 1 oldest candidates. Avoid copying
      // the whole cache in the ordinary (no pins) case, and remove only the chosen instance.
      for (var entry : policy.get().oldest(pinned.size() + 1).entrySet()) {
        if (pinned.get(entry.getKey()) == entry.getValue()) continue;
        if (cache.asMap().remove(entry.getKey(), entry.getValue())) {
          logger.info("Force-evicted log '{}' due to {}", entry.getKey(), reason);
          return true;
        }
      }
      return false;
    }
  }

  /** Retires an owned lazy or live index; in-flight uses keep their mappings until release. */
  private void closeIfLazy(LogData log) {
    if (log instanceof AutoCloseable owned) {
      try {
        owned.close();
      } catch (Exception e) {
        logger.debug("Error closing lazy log: {}", e.getMessage());
      }
    }
  }
}
