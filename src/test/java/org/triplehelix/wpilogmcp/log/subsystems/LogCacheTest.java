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
}
