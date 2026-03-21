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
  private MemoryEstimator memoryEstimator;

  @BeforeEach
  void setUp() {
    memoryEstimator = new MemoryEstimator();
    cache = new LogCache(memoryEstimator);
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
    cache.setActiveLogPath("/log1.wpilog");

    cache.clear();

    assertEquals(0, cache.size());
    assertNull(cache.getActiveLogPath());
  }

  @Test
  void testCountBasedEviction() {
    cache.setMaxLoadedLogs(2);

    // Add 3 logs, should evict the first one
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));
    cache.evictIfNeeded(); // No eviction yet, only 2 logs

    assertEquals(2, cache.size());

    cache.put("/log3.wpilog", createMockLog("/log3.wpilog", 1));
    cache.evictIfNeeded(); // Should evict log1 (LRU)

    assertEquals(2, cache.size());
    assertNull(cache.get("/log1.wpilog"));
    assertNotNull(cache.get("/log2.wpilog"));
    assertNotNull(cache.get("/log3.wpilog"));
  }

  @Test
  void testActiveLogNotEvicted() {
    cache.setMaxLoadedLogs(2);

    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));
    cache.setActiveLogPath("/log1.wpilog"); // Make log1 active

    cache.put("/log3.wpilog", createMockLog("/log3.wpilog", 1));
    cache.evictIfNeeded(); // Should evict log2, not log1

    assertEquals(2, cache.size());
    assertNotNull(cache.get("/log1.wpilog")); // Active log kept
    assertNull(cache.get("/log2.wpilog")); // LRU non-active log evicted
    assertNotNull(cache.get("/log3.wpilog"));
  }

  @Test
  void testLRUOrder() {
    cache.setMaxLoadedLogs(2);

    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));

    // Access log1 to make it more recently used
    cache.get("/log1.wpilog");

    cache.put("/log3.wpilog", createMockLog("/log3.wpilog", 1));
    cache.evictIfNeeded(); // Should evict log2 (LRU), not log1

    assertEquals(2, cache.size());
    assertNotNull(cache.get("/log1.wpilog")); // Accessed more recently
    assertNull(cache.get("/log2.wpilog")); // LRU
    assertNotNull(cache.get("/log3.wpilog"));
  }

  @Test
  void testContainsKey() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));

    assertTrue(cache.containsKey("/log1.wpilog"));
    assertFalse(cache.containsKey("/nonexistent.wpilog"));
  }

  @Test
  void testGetStats() {
    cache.setMaxLoadedLogs(10);
    cache.setMaxMemoryMb(2048);
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.setActiveLogPath("/log1.wpilog");

    var stats = cache.getStats();

    assertEquals(1, stats.get("size"));
    assertEquals(10, stats.get("maxLogs"));
    assertEquals(2048, stats.get("maxMemoryMb"));
    assertEquals("/log1.wpilog", stats.get("activeLog"));
    assertTrue(stats.containsKey("estimatedMemoryMb"));
  }

  @Test
  void testSetMaxLoadedLogsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> cache.setMaxLoadedLogs(0));
    assertThrows(IllegalArgumentException.class, () -> cache.setMaxLoadedLogs(-1));
  }

  @Test
  void testSetMaxMemoryMbInvalid() {
    assertThrows(IllegalArgumentException.class, () -> cache.setMaxMemoryMb(0));
    assertThrows(IllegalArgumentException.class, () -> cache.setMaxMemoryMb(-1));
  }

  @Test
  void testSetActiveIfPresentWhenPresent() {
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));

    assertTrue(cache.setActiveIfPresent("/log1.wpilog"));
    assertEquals("/log1.wpilog", cache.getActiveLogPath());
  }

  @Test
  void testSetActiveIfPresentWhenAbsent() {
    assertFalse(cache.setActiveIfPresent("/nonexistent.wpilog"));
    assertNull(cache.getActiveLogPath());
  }

  @Test
  void testSetActiveIfPresentAtomicity() {
    // Verify that setActiveIfPresent doesn't set active path when log is absent
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.setActiveLogPath("/log1.wpilog");

    // Try to set active to a non-existent log
    assertFalse(cache.setActiveIfPresent("/log2.wpilog"));
    // Active path should remain unchanged
    assertEquals("/log1.wpilog", cache.getActiveLogPath());
  }

  @Test
  void testEvictionDoesNotCallSystemGc() {
    // Regression test: eviction should not call System.gc() while holding the write lock,
    // as this can cause Stop-The-World pauses that block all cache operations.
    cache.setMaxLoadedLogs(1);
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));

    // If System.gc() were called inside the write lock, this would still work
    // but we verify the eviction completes quickly (< 1 second).
    // A blocked System.gc() inside a lock would cause much longer delays under load.
    long start = System.nanoTime();
    cache.evictIfNeeded();
    long durationMs = (System.nanoTime() - start) / 1_000_000;

    assertEquals(1, cache.size());
    assertTrue(durationMs < 1000, "Eviction took too long (" + durationMs + "ms), "
        + "possible System.gc() blocking inside lock");
  }

  @Test
  void testConfigFieldsAreVolatile() throws Exception {
    // Regression test: maxLoadedLogs and maxMemoryMb should be volatile to ensure
    // visibility across threads when set during configuration.
    var field1 = LogCache.class.getDeclaredField("maxLoadedLogs");
    var field2 = LogCache.class.getDeclaredField("maxMemoryMb");

    assertTrue(java.lang.reflect.Modifier.isVolatile(field1.getModifiers()),
        "maxLoadedLogs should be volatile for thread safety");
    assertTrue(java.lang.reflect.Modifier.isVolatile(field2.getModifiers()),
        "maxMemoryMb should be volatile for thread safety");
  }

  @Test
  void testConcurrentGetDoesNotCorruptLRU() throws Exception {
    // Regression test: get() must use writeLock with access-ordered LinkedHashMap.
    // With readLock, concurrent get() calls would corrupt the LRU linked list.
    cache.setMaxLoadedLogs(3);
    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));
    cache.put("/log3.wpilog", createMockLog("/log3.wpilog", 1));

    // Concurrent reads should not cause ConcurrentModificationException
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
    // Cache should still be consistent
    assertEquals(3, cache.size());
  }

  @Test
  void evictsMultipleEntriesUntilWithinCountLimit() {
    cache.setMaxLoadedLogs(2);

    // Add 5 logs
    for (int i = 0; i < 5; i++) {
      cache.put("/log" + i + ".wpilog", createMockLog("/log" + i + ".wpilog", 1));
    }
    cache.setActiveLogPath("/log4.wpilog");

    assertEquals(5, cache.size());

    // evictIfNeeded should loop until cache is within limit (2)
    cache.evictIfNeeded();

    assertTrue(cache.size() <= 2,
        "Cache should have at most 2 entries after eviction, but has: " + cache.size());
    // Active log should never be evicted
    assertTrue(cache.containsKey("/log4.wpilog"), "Active log should not be evicted");
  }

  @Test
  void evictionCallbackIsInvokedOnEviction() {
    cache.setMaxLoadedLogs(1);

    var evictedPaths = new java.util.ArrayList<String>();
    cache.setEvictionCallback(evictedPaths::add);

    cache.put("/log1.wpilog", createMockLog("/log1.wpilog", 1));
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));
    cache.setActiveLogPath("/log2.wpilog");

    cache.evictIfNeeded();

    assertTrue(evictedPaths.contains("/log1.wpilog"),
        "Eviction callback should have been called with the evicted path");
    assertFalse(evictedPaths.contains("/log2.wpilog"),
        "Active log should not have been evicted");
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
    // No callback set — clear should still work
    assertDoesNotThrow(() -> cache.clear());
    assertEquals(0, cache.size());
  }
}
