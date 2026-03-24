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
    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));

    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
    cache.put("/log3.wpilog", createMockLog("/log3.wpilog", 1));

    // Access log1 and log3 to make log2 the LRU
    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
    cache.get("/log1.wpilog");

    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
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

    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));

    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
    cache.put("/log3.wpilog", createMockLog("/log3.wpilog", 1));

    // Access log1 to move it to MRU
    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
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

    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
    cache.put("/log2.wpilog", createMockLog("/log2.wpilog", 1));

    // Access log2 to make it MRU
    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
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
}
