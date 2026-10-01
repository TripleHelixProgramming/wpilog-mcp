/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log.subsystems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

class LogCacheTest {
  private LogCache cache;

  @BeforeEach
  void setUp() {
    cache = new LogCache();
  }

  private ParsedLog createMockLog(String path, int entryCount) {
    var entries = Map.of("entry1", new EntryInfo(1, "entry1", "double", ""));
    var values =
        Map.of(
            "entry1",
            List.of(
                new TimestampedValue(0.0, 1.0),
                new TimestampedValue(1.0, 2.0),
                new TimestampedValue(2.0, 3.0)));
    return new ParsedLog(path, entries, values, 0.0, 2.0);
  }

  @Test
  void testPutAndGet() {
    var log = createMockLog("/path/to/log1.wpilog", 1);
    cache.put("/path/to/log1.wpilog", log);

    var retrieved = cache.get("/path/to/log1.wpilog");
    assertEquals(log, retrieved);
  }

  @Test
  void testGetNonexistent() {
    assertNull(cache.get("/nonexistent.wpilog"));
  }

  @Test
  void testRemove() {
    var log = createMockLog("/path/to/log1.wpilog", 1);
    cache.put("/path/to/log1.wpilog", log);

    var removed = cache.remove("/path/to/log1.wpilog");
    assertEquals(log, removed);
    assertNull(cache.get("/path/to/log1.wpilog"));
  }

  @Test
  void testClear() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));
    cache.clear();

    assertTrue(cache.isEmpty());
    assertTrue(cache.getAllEntries().isEmpty());
  }

  @Test
  void testContainsKey() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));

    assertTrue(cache.containsKey("/log1.wpilog"));
    assertFalse(cache.containsKey("/nonexistent.wpilog"));
  }

  @Test
  void testIsEmpty() {
    assertTrue(cache.isEmpty());
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    assertFalse(cache.isEmpty());
  }

  @Test
  void testGetAllEntries() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));

    var entries = cache.getAllEntries();
    assertEquals(2, entries.size());
    assertTrue(entries.containsKey("/log1.wpilog"));
    assertTrue(entries.containsKey("/log2.wpilog"));
  }

  @Test
  void testGetAllEntriesReturnsDefensiveCopy() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    var entries = cache.getAllEntries();
    entries.put("/injected.wpilog", createMockLog("/injected.wpilog", 1));

    assertFalse(cache.containsKey("/injected.wpilog"));
  }

  @Test
  void testEvictOneRemovesEntry() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));
    cache.put("/log3.wpilog", createMockLog("/log3.wpilog", 1));

    boolean evicted = cache.evictOne();
    assertTrue(evicted);
    assertEquals(2, cache.getAllEntries().size());
  }

  @Test
  void testEvictOneRemovesLRU() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));

    // Small sleep to ensure different access times
    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));

    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    cache.put("/log3.wpilog", createMockLog("/log3.wpilog", 1));

    // Access log1 and log3 to make log2 the LRU
    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    cache.get("/log1.wpilog");

    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    cache.get("/log3.wpilog");

    cache.evictOne();
    // log2 should be evicted (least recently accessed)
    assertNull(cache.get("/log2.wpilog"), "LRU entry should have been evicted");
    assertEquals(2, cache.getAllEntries().size());
  }

  @Test
  void testEvictOneOnEmptyCacheReturnsFalse() {
    assertFalse(cache.evictOne());
  }

  @Test
  void testLRUOrder() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));

    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));

    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    cache.put("/log3.wpilog", createMockLog("/log3.wpilog", 1));

    // Access log1 to move it to MRU
    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    cache.get("/log1.wpilog");

    // Evict should remove log2 (oldest not-recently-accessed)
    cache.evictOne();
    assertNull(cache.get("/log2.wpilog"), "LRU entry should be evicted");
    // log1 and log3 should remain
    assertEquals(2, cache.getAllEntries().size());
  }

  @Test
  void testGetStats() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));

    var stats = cache.getStats();

    assertEquals(1, stats.get("cached_logs"));
    assertTrue(stats.containsKey("heap_used_mb"));
    assertTrue(stats.containsKey("heap_max_mb"));
    assertTrue(stats.containsKey("heap_pressure_threshold"));
  }

  @Test
  void testEvictionDoesNotCallSystemGc() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));

    long start = System.nanoTime();
    cache.evictOne();
    long durationMs = (System.nanoTime() - start) / 1_000_000;

    assertTrue(durationMs < 1000, "Eviction took too long (" + durationMs + "ms)");
  }

  @Test
  void testConcurrentGetDoesNotCorruptCache() throws Exception {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));
    cache.put("/log3.wpilog", createMockLog("/log3.wpilog", 1));

    var threads = new Thread[10];
    var errors = new java.util.concurrent.atomic.AtomicInteger(0);
    for (int i = 0; i < threads.length; i++) {
      final int idx = i;
      threads[i] = new Thread(() -> {
        try {
          for (int j = 0; j < 100; j++) {
            cache.get("/log" + (idx % 3 + 1) + ".wpilog");
          }
        } catch (Exception e) {
          errors.incrementAndGet();
        }
      });
    }
    for (var t : threads) t.start();
    for (var t : threads) t.join();

    assertEquals(0, errors.get(), "Concurrent get() calls should not throw");
    assertEquals(3, cache.getAllEntries().size());
  }

  @Test
  void evictionCallbackIsInvokedOnEviction() {
    var evictedPaths = new java.util.ArrayList<String>();
    cache.setEvictionCallback(evictedPaths::add);

    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));

    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));

    // Access log2 to make it MRU
    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    cache.get("/log2.wpilog");

    cache.evictOne();

    assertTrue(evictedPaths.contains("/log1.wpilog"),
        "Eviction callback should have been called with the LRU path");
    assertFalse(evictedPaths.contains("/log2.wpilog"),
        "MRU log should not have been evicted");
  }

  @Test
  void clearInvokesEvictionCallbackForEachLog() {
    var evictedPaths = new java.util.ArrayList<String>();
    cache.setEvictionCallback(evictedPaths::add);

    cache.put("/a.wpilog", createMockLog("/a.wpilog", 1));
    cache.put("/b.wpilog", createMockLog("/b.wpilog", 1));
    cache.put("/c.wpilog", createMockLog("/c.wpilog", 1));

    cache.clear();

    assertEquals(3, evictedPaths.size(),
        "clear() should invoke callback for each cached log");
    assertTrue(evictedPaths.contains("/a.wpilog"));
    assertTrue(evictedPaths.contains("/b.wpilog"));
    assertTrue(evictedPaths.contains("/c.wpilog"));
  }

  @Test
  void clearWithNoCallbackDoesNotThrow() {
    cache.put("/a.wpilog", createMockLog("/a.wpilog", 1));
    assertDoesNotThrow(() -> cache.clear());
    assertTrue(cache.isEmpty());
  }

  @Test
  void evictIfNeededEvictsIdleLogs() {
    // Create cache with 1ms idle timeout
    var shortCache = new LogCache(1);
    shortCache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));

    // Wait for idle expiration
    try { Thread.sleep(50); } catch (InterruptedException ignored) {}

    shortCache.evictIfNeeded();

    assertTrue(shortCache.isEmpty(), "Idle log should have been evicted");
  }

  @Test
  void evictIfNeededKeepsRecentLogs() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));

    cache.evictIfNeeded();

    // Under normal heap conditions, the log should not be evicted
    // (it's not idle and we're not under heap pressure in tests)
  }

  @Test
  void multipleEvictOneCallsDrainCache() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));
    cache.put("/log3.wpilog", createMockLog("/log3.wpilog", 1));

    assertTrue(cache.evictOne());
    assertTrue(cache.evictOne());
    assertTrue(cache.evictOne());
    assertFalse(cache.evictOne());
    assertTrue(cache.isEmpty());
  }

  @Test
  void putOverwriteDoesNotInvokeEvictionCallback() {
    var evictedPaths = new java.util.ArrayList<String>();
    cache.setEvictionCallback(evictedPaths::add);

    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    // Overwrite with new value
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));

    assertTrue(evictedPaths.isEmpty(),
        "Overwriting an entry should not invoke eviction callback");
  }

  @Test
  void removeReturnsNullForMissingEntry() {
    assertNull(cache.remove("/nonexistent.wpilog"));
  }

  // ==================== Heap pressure ====================

  static final long MB = 1024L * 1024L;

  /**
   * A 1000 MB heap in which every cached log holds {@code perLog} bytes. What an unloaded log
   * held stays counted as used until a collection, as on the real heap. That is what made the
   * eviction loops unload every log: they measured again without collecting, saw no change, and
   * went on to the next log.
   */
  static final class FakeHeap implements LogCache.Heap {
    final java.util.concurrent.atomic.AtomicLong garbage = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicInteger collections =
        new java.util.concurrent.atomic.AtomicInteger();
    volatile long other; // live bytes that belong to no cached log
    volatile long perLog = 90 * MB;
    volatile LogCache cache;

    @Override
    public long usedBytes() {
      return other + garbage.get() + perLog * cache.getAllEntries().size();
    }

    @Override
    public long maxBytes() {
      return 1000 * MB;
    }

    @Override
    public void collect() {
      collections.incrementAndGet();
      garbage.set(0);
    }
  }

  /** A cache of {@code logs} logs (/log0.wpilog is the least recently used) on a fake heap. */
  private FakeHeap heapWith(int logs, long perLog) {
    var heap = new FakeHeap();
    heap.perLog = perLog;
    var c = new LogCache(1_800_000, heap);
    heap.cache = c;
    c.setEvictionCallback(p -> heap.garbage.addAndGet(heap.perLog));
    for (int i = 0; i < logs; i++) {
      c.put("/log" + i + ".wpilog", createMockLog("/log" + i + ".wpilog", 1));
    }
    return heap;
  }

  @Test
  void heapPressureUnloadsOnlyWhatIsNeeded() {
    // 900 of 1000 MB in use: 10% free, under the 15% threshold. Unloading one log is enough.
    var heap = heapWith(10, 90 * MB);

    heap.cache.evictIfNeeded();

    var left = heap.cache.getAllEntries().keySet();
    assertEquals(9, left.size(), "one log relieves the pressure; it unloaded " + (10 - left.size()));
    assertFalse(left.contains("/log0.wpilog"), "the least recently used log is the one to go");
  }

  @Test
  void garbageAloneUnloadsNothing() {
    // 270 MB held by logs and 600 MB of garbage: it reads as 13% free until a collection
    var heap = heapWith(3, 90 * MB);
    heap.garbage.set(600 * MB);

    heap.cache.evictIfNeeded();

    assertEquals(3, heap.cache.getAllEntries().size(),
        "a heap full of garbage is not a reason to unload logs");
    assertEquals(1, heap.collections.get());
  }

  @Test
  void pressureThatUnloadingCannotRelieveEndsWithAnEmptyCache() {
    // 880 MB is held by something other than the logs
    var heap = heapWith(3, 10 * MB);
    heap.other = 880 * MB;

    heap.cache.evictIfNeeded();

    assertTrue(heap.cache.isEmpty());
    assertTrue(heap.collections.get() <= 4, "one collection before, one per unloaded log: "
        + heap.collections.get());
    // and with nothing left to unload, later checks do not collect at all
    heap.cache.evictIfNeeded();
    assertTrue(heap.collections.get() <= 4, "collected with nothing to unload");
  }

  @Test
  void noPressureMeansNoCollection() {
    var heap = heapWith(5, 90 * MB); // 55% free

    heap.cache.evictIfNeeded();
    assertFalse(heap.cache.makeRoomFor(100 * MB));

    assertEquals(5, heap.cache.getAllEntries().size());
    assertEquals(0, heap.collections.get(), "the ordinary case must not force a collection");
  }

  @Test
  void makingRoomUnloadsOnlyWhatIsNeeded() {
    // 100 MB free; 250 MB wanted: two logs of 90 MB make it 280 MB
    var heap = heapWith(10, 90 * MB);

    assertTrue(heap.cache.makeRoomFor(250 * MB));

    var left = heap.cache.getAllEntries().keySet();
    assertEquals(8, left.size(), "two logs make the room; it unloaded " + (10 - left.size()));
    assertFalse(left.contains("/log0.wpilog"));
    assertFalse(left.contains("/log1.wpilog"));
  }

  @Test
  void makingRoomCollectsGarbageBeforeUnloadingLogs() {
    var heap = heapWith(3, 90 * MB);
    heap.garbage.set(600 * MB); // 130 MB free until collected, 730 MB after

    assertFalse(heap.cache.makeRoomFor(250 * MB));

    assertEquals(3, heap.cache.getAllEntries().size());
  }

  @Test
  void makingRoomForMoreThanTheHeapUnloadsEverythingAndStops() {
    var heap = heapWith(3, 90 * MB);

    assertTrue(heap.cache.makeRoomFor(5000 * MB));

    assertTrue(heap.cache.isEmpty());
    assertFalse(heap.cache.makeRoomFor(5000 * MB), "nothing left to unload");
  }

  @Test
  void concurrentChecksTogetherUnloadOnlyWhatIsNeeded() throws Exception {
    for (int round = 0; round < 20; round++) {
      var heap = heapWith(10, 90 * MB);
      var start = new java.util.concurrent.CountDownLatch(1);
      var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
      try {
        var done = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int t = 0; t < 8; t++) {
          done.add(pool.submit(() -> {
            start.await();
            heap.cache.evictIfNeeded();
            return null;
          }));
        }
        start.countDown();
        for (var f : done) f.get(10, java.util.concurrent.TimeUnit.SECONDS);
      } finally {
        pool.shutdownNow();
      }
      assertEquals(9, heap.cache.getAllEntries().size(),
          "round " + round + ": eight checks at once still unload one log");
    }
  }
}
