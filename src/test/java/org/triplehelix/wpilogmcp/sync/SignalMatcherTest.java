package org.triplehelix.wpilogmcp.sync;

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
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogDevice;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;

/**
 * Tests for SignalMatcher.
 */
class SignalMatcherTest {

  private SignalMatcher matcher;

  @BeforeEach
  void setUp() {
    matcher = new SignalMatcher();
  }

  @Test
  void testFindPairsWithMatchingSignals() {
    // Create a mock wpilog with motor output entries
    ParsedLog wpilog = createMockWpilog(Map.of(
        "/drive/frontLeft/output", createNumericValues(100),
        "/drive/frontRight/output", createNumericValues(100),
        "/drive/frontLeft/velocity", createNumericValues(100)
    ));

    // Create a mock revlog with matching signals
    ParsedRevLog revlog = createMockRevlog(Map.of(
        1, new RevLogDevice(1, "SPARK MAX")
    ), Map.of(
        "SparkMax_1/appliedOutput", createRevLogSignal("appliedOutput", "SparkMax_1", 100),
        "SparkMax_1/velocity", createRevLogSignal("velocity", "SparkMax_1", 100)
    ));

    List<SignalPair> pairs = matcher.findPairs(wpilog, revlog, Map.of());

    assertFalse(pairs.isEmpty());
    // Should find matches for output and velocity
    assertTrue(pairs.stream().anyMatch(p -> p.signalType().equals("appliedOutput")));
    assertTrue(pairs.stream().anyMatch(p -> p.signalType().equals("velocity")));
  }

  @Test
  void testFindPairsWithCanIdHints() {
    ParsedLog wpilog = createMockWpilog(Map.of(
        "/drive/frontLeft/output", createNumericValues(100),
        "/drive/frontRight/output", createNumericValues(100)
    ));

    ParsedRevLog revlog = createMockRevlog(Map.of(
        1, new RevLogDevice(1, "SPARK MAX")
    ), Map.of(
        "SparkMax_1/appliedOutput", createRevLogSignal("appliedOutput", "SparkMax_1", 100)
    ));

    // Provide hint that CAN ID 1 corresponds to frontLeft
    Map<Integer, String> hints = Map.of(1, "frontLeft");

    List<SignalPair> pairs = matcher.findPairs(wpilog, revlog, hints);

    assertFalse(pairs.isEmpty());
    // The frontLeft entry should have higher score due to hint
    SignalPair bestMatch = pairs.get(0);
    assertTrue(bestMatch.wpilogEntry().contains("frontLeft"));
  }

  @Test
  void testFindPairsNoMatchingEntries() {
    // Wpilog with non-motor entries
    ParsedLog wpilog = createMockWpilog(Map.of(
        "/sensors/gyro/angle", createNumericValues(100),
        "/sensors/lidar/distance", createNumericValues(100)
    ));

    ParsedRevLog revlog = createMockRevlog(Map.of(
        1, new RevLogDevice(1, "SPARK MAX")
    ), Map.of(
        "SparkMax_1/appliedOutput", createRevLogSignal("appliedOutput", "SparkMax_1", 100)
    ));

    List<SignalPair> pairs = matcher.findPairs(wpilog, revlog, Map.of());

    // May find some matches with low scores, or empty
    // The key is it shouldn't crash
    assertNotNull(pairs);
  }

  @Test
  void testFindPairsInsufficientSamples() {
    // Create entries with very few samples
    ParsedLog wpilog = createMockWpilog(Map.of(
        "/drive/output", createNumericValues(5) // Only 5 samples
    ));

    ParsedRevLog revlog = createMockRevlog(Map.of(
        1, new RevLogDevice(1, "SPARK MAX")
    ), Map.of(
        "SparkMax_1/appliedOutput", createRevLogSignal("appliedOutput", "SparkMax_1", 5)
    ));

    List<SignalPair> pairs = matcher.findPairs(wpilog, revlog, Map.of());

    // Should filter out pairs with insufficient samples
    assertTrue(pairs.isEmpty());
  }

  @Test
  void testFindPairsSkipsNonNumericEntries() {
    Map<String, EntryInfo> entries = new HashMap<>();
    entries.put("/drive/output", new EntryInfo(1, "/drive/output", "double", ""));
    entries.put("/drive/status", new EntryInfo(2, "/drive/status", "string", ""));

    Map<String, List<TimestampedValue>> values = new HashMap<>();
    values.put("/drive/output", createNumericValues(100));
    values.put("/drive/status", createNumericValues(100));

    ParsedLog wpilog = new ParsedLog("/test.wpilog", entries, values, 0, 10);

    ParsedRevLog revlog = createMockRevlog(Map.of(
        1, new RevLogDevice(1, "SPARK MAX")
    ), Map.of(
        "SparkMax_1/appliedOutput", createRevLogSignal("appliedOutput", "SparkMax_1", 100)
    ));

    List<SignalPair> pairs = matcher.findPairs(wpilog, revlog, Map.of());

    // Should only match the numeric entry, not the string one
    for (SignalPair pair : pairs) {
      assertNotEquals("/drive/status", pair.wpilogEntry());
    }
  }

  @Test
  void testPrioritizePairs() {
    List<SignalPair> pairs = List.of(
        SignalPair.of("/a", "s", "position"),
        SignalPair.of("/b", "s", "appliedOutput"),
        SignalPair.of("/c", "s", "velocity"),
        SignalPair.of("/d", "s", "temperature")
    );

    List<SignalPair> prioritized = matcher.prioritizePairs(pairs);

    // appliedOutput should be first, then velocity
    assertEquals("appliedOutput", prioritized.get(0).signalType());
    assertEquals("velocity", prioritized.get(1).signalType());
  }

  @Test
  void testFindPairsEmptyRevlog() {
    ParsedLog wpilog = createMockWpilog(Map.of(
        "/drive/output", createNumericValues(100)
    ));

    ParsedRevLog revlog = createMockRevlog(Map.of(), Map.of());

    List<SignalPair> pairs = matcher.findPairs(wpilog, revlog, Map.of());

    assertTrue(pairs.isEmpty());
  }

  @Test
  void testFindPairsEmptyWpilog() {
    ParsedLog wpilog = createMockWpilog(Map.of());

    ParsedRevLog revlog = createMockRevlog(Map.of(
        1, new RevLogDevice(1, "SPARK MAX")
    ), Map.of(
        "SparkMax_1/appliedOutput", createRevLogSignal("appliedOutput", "SparkMax_1", 100)
    ));

    List<SignalPair> pairs = matcher.findPairs(wpilog, revlog, Map.of());

    assertTrue(pairs.isEmpty());
  }

  // ========== Helper Methods ==========

  private ParsedLog createMockWpilog(Map<String, List<TimestampedValue>> valueMap) {
    Map<String, EntryInfo> entries = new HashMap<>();
    Map<String, List<TimestampedValue>> values = new HashMap<>();

    int id = 1;
    for (var entry : valueMap.entrySet()) {
      entries.put(entry.getKey(), new EntryInfo(id++, entry.getKey(), "double", ""));
      values.put(entry.getKey(), entry.getValue());
    }

    return new ParsedLog("/test.wpilog", entries, values, 0, 10);
  }

  private ParsedRevLog createMockRevlog(
      Map<Integer, RevLogDevice> devices,
      Map<String, RevLogSignal> signals) {
    return new ParsedRevLog(
        "/test.revlog",
        "20260320_143052",
        devices,
        signals,
        0,
        10,
        1000
    );
  }

  private List<TimestampedValue> createNumericValues(int count) {
    List<TimestampedValue> values = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      values.add(new TimestampedValue(i * 0.02, Math.sin(i * 0.1)));
    }
    return values;
  }

  private RevLogSignal createRevLogSignal(String name, String deviceKey, int sampleCount) {
    List<TimestampedValue> values = new ArrayList<>();
    for (int i = 0; i < sampleCount; i++) {
      values.add(new TimestampedValue(i * 0.02, Math.sin(i * 0.1)));
    }
    return new RevLogSignal(name, deviceKey, values, "");
  }
}
