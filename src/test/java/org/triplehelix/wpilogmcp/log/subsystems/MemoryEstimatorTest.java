package org.triplehelix.wpilogmcp.log.subsystems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Comprehensive tests for MemoryEstimator heuristics.
 */
class MemoryEstimatorTest {
  private MemoryEstimator estimator;

  @BeforeEach
  void setUp() {
    estimator = new MemoryEstimator();
  }

  @Test
  void testEstimatePrimitiveDouble() {
    long memory = estimator.estimateValueMemory("double", 42.0);
    assertEquals(40, memory); // 32 base + 8 for double
  }

  @Test
  void testEstimatePrimitiveInt64() {
    long memory = estimator.estimateValueMemory("int64", 42L);
    assertEquals(40, memory); // 32 base + 8 for int64
  }

  @Test
  void testEstimatePrimitiveFloat() {
    long memory = estimator.estimateValueMemory("float", 42.0f);
    assertEquals(36, memory); // 32 base + 4 for float
  }

  @Test
  void testEstimatePrimitiveBoolean() {
    long memory = estimator.estimateValueMemory("boolean", true);
    assertEquals(33, memory); // 32 base + 1 for boolean
  }

  @Test
  void testEstimateShortString() {
    long memory = estimator.estimateValueMemory("string", "test");
    // 32 base + 40 string overhead + (4 chars * 2 bytes)
    assertEquals(80, memory);
  }

  @Test
  void testEstimateLongString() {
    String longString = "a".repeat(100);
    long memory = estimator.estimateValueMemory("string", longString);
    // 32 base + 40 string overhead + (100 chars * 2 bytes)
    assertEquals(272, memory);
  }

  @Test
  void testEstimateByteArray() {
    byte[] data = new byte[50];
    long memory = estimator.estimateValueMemory("byte[]", data);
    // 32 base + 40 array overhead + 50 bytes
    assertEquals(122, memory);
  }

  @Test
  void testEstimateStructMap() {
    Map<String, Object> struct = new HashMap<>();
    struct.put("x", 1.0);
    struct.put("y", 2.0);
    struct.put("z", 3.0);

    long memory = estimator.estimateValueMemory("struct:Pose3d", struct);
    // 32 base + 200 map overhead + (3 entries * 50)
    assertEquals(382, memory);
  }

  @Test
  void testEstimateUnknownTypeDefault() {
    long memory = estimator.estimateValueMemory("unknown_type", new Object());
    assertEquals(72, memory); // 32 base + 40 fallback
  }

  @Test
  void testEstimateEmptyLog() {
    ParsedLog log = new ParsedLog("/test.wpilog", Map.of(), Map.of(), 0.0, 10.0);
    long memory = estimator.estimateLogMemory(log);
    assertEquals(0, memory);
  }

  @Test
  void testEstimateLogWithSingleEntry() {
    var entries = Map.of(
        "/test/value",
        new EntryInfo(1, "/test/value", "double", ""));

    var values = Map.of(
        "/test/value",
        List.of(
            new TimestampedValue(0.0, 1.0),
            new TimestampedValue(1.0, 2.0),
            new TimestampedValue(2.0, 3.0)));

    ParsedLog log = new ParsedLog("/test.wpilog", entries, values, 0.0, 2.0);
    long memory = estimator.estimateLogMemory(log);

    // Entry overhead: 200 + ("/test/value".length() * 2) = 224
    // Per value: 40 bytes (32 base + 8 for double)
    // 3 values * 40 = 120
    // Total: 224 + 120 = 344
    // Note: Verify this is approximately correct (within reasonable range)
    assertTrue(memory > 300 && memory < 400, "Expected ~344 bytes, got: " + memory);
  }

  @Test
  void testEstimateLogWithMultipleEntries() {
    var entries = Map.of(
        "/entry1", new EntryInfo(1, "/entry1", "double", ""),
        "/entry2", new EntryInfo(2, "/entry2", "float", ""));

    var values = Map.of(
        "/entry1", List.of(new TimestampedValue(0.0, 1.0)),
        "/entry2", List.of(new TimestampedValue(0.0, 1.0f)));

    ParsedLog log = new ParsedLog("/test.wpilog", entries, values, 0.0, 1.0);
    long memory = estimator.estimateLogMemory(log);

    assertTrue(memory > 0);
    // Should account for both entries
    assertTrue(memory > 400); // At least 2 * (200 base + overhead)
  }

  @Test
  void testEstimateTotalMemoryOfMultipleLogs() {
    List<ParsedLog> logs = new ArrayList<>();

    for (int i = 0; i < 3; i++) {
      var entries = Map.of("/test", new EntryInfo(1, "/test", "double", ""));
      var values = Map.of("/test", List.of(new TimestampedValue(0.0, 1.0)));
      logs.add(new ParsedLog("/test" + i + ".wpilog", entries, values, 0.0, 1.0));
    }

    long totalMemory = estimator.estimateTotalMemory(logs);
    long singleLogMemory = estimator.estimateLogMemory(logs.get(0));

    assertEquals(singleLogMemory * 3, totalMemory);
  }

  @Test
  void testEstimateLogWithEmptyValuesList() {
    var entries = Map.of("/test", new EntryInfo(1, "/test", "double", ""));
    var values = Map.of("/test", List.<TimestampedValue>of());

    ParsedLog log = new ParsedLog("/test.wpilog", entries, values, 0.0, 1.0);
    long memory = estimator.estimateLogMemory(log);

    // Should only account for entry overhead, no value overhead
    assertEquals(0, memory);
  }

  @Test
  void testEstimateLogWithLargeStringValues() {
    String largeString = "x".repeat(1000);
    var entries = Map.of("/test", new EntryInfo(1, "/test", "string", ""));
    var values = Map.of(
        "/test",
        List.of(
            new TimestampedValue(0.0, largeString),
            new TimestampedValue(1.0, largeString)));

    ParsedLog log = new ParsedLog("/test.wpilog", entries, values, 0.0, 1.0);
    long memory = estimator.estimateLogMemory(log);

    // Each large string: 32 base + 40 overhead + (1000 * 2) = 2072
    // 2 values * 2072 = 4144
    // Entry overhead: ~206
    // Total: ~4350
    assertTrue(memory > 4000);
  }

  @Test
  void testEstimateValueMemoryWithNullString() {
    // Should handle null gracefully by using default
    long memory = estimator.estimateValueMemory("string", null);
    assertEquals(132, memory); // 32 base + 100 default
  }

  @Test
  void testEstimateValueMemoryWithNullByteArray() {
    long memory = estimator.estimateValueMemory("byte[]", null);
    assertEquals(132, memory); // 32 base + 100 default
  }

  @Test
  void testMemoryEstimationIsReasonable() {
    // Verify estimates are in reasonable ranges (not negative, not absurdly large)
    var entries = Map.of("/test", new EntryInfo(1, "/test", "double", ""));
    var values = new ArrayList<TimestampedValue>();
    for (int i = 0; i < 1000; i++) {
      values.add(new TimestampedValue(i, (double) i));
    }

    ParsedLog log = new ParsedLog("/test.wpilog", entries, Map.of("/test", values), 0.0, 1000.0);
    long memory = estimator.estimateLogMemory(log);

    assertTrue(memory > 0);
    assertTrue(memory < 1_000_000); // Should be less than 1MB for 1000 doubles
    assertTrue(memory > 10_000); // But more than 10KB
  }
}
