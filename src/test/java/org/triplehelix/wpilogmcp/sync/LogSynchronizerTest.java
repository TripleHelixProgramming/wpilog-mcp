package org.triplehelix.wpilogmcp.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
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
 * Tests for LogSynchronizer.
 */
class LogSynchronizerTest {

  private LogSynchronizer synchronizer;

  @BeforeEach
  void setUp() {
    synchronizer = new LogSynchronizer();
  }

  @Test
  void testSynchronizeWithMatchingSignals() {
    // Create wpilog and revlog with correlated signals
    // The revlog signal has timeOffset=-0.5, meaning revlog values at timestamp t
    // equal what wpilog has at timestamp t-0.5. This simulates revlog being 0.5s ahead,
    // so to convert revlog_time to wpilog_time: wpilog_time = revlog_time - 0.5
    double timeShift = 0.5;

    ParsedLog wpilog = createWpilogWithSignal("/drive/output", 0.0, 100);
    ParsedRevLog revlog = createRevlogWithSignal("appliedOutput", -timeShift, 100);

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    assertTrue(result.isSuccessful());
    // The offset should be approximately -0.5 seconds (revlog is ahead)
    // Allow for some error in the correlation
    double offsetSeconds = result.offsetSeconds();
    assertEquals(-timeShift, offsetSeconds, 0.1); // Within 100ms
  }

  @Test
  void testSynchronizeNoMatchingSignals() {
    // Create logs with no matching signal types and no systemTime data
    // → should fail since there's no coarse offset and no signal pairs
    ParsedLog wpilog = createMockWpilog(Map.of(
        "/sensors/gyro", createNumericValues(100, 0)
    ));

    ParsedRevLog revlog = createMockRevlog(Map.of(
        1, new RevLogDevice(1, "SPARK MAX")
    ), Map.of(
        "SparkMax_1/appliedOutput", createRevLogSignal("appliedOutput", "SparkMax_1", 100, 0)
    ));

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    // Without systemTime data and no matching signals, sync should fail
    assertEquals(SyncMethod.FAILED, result.method());
    assertFalse(result.isSuccessful());
  }

  @Test
  void testSynchronizeEmptyRevlog() {
    ParsedLog wpilog = createMockWpilog(Map.of(
        "/drive/output", createNumericValues(100, 0)
    ));

    ParsedRevLog revlog = createMockRevlog(Map.of(), Map.of());

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    // No signals in revlog means no pairs, no systemTime means no coarse offset → fail
    assertEquals(SyncMethod.FAILED, result.method());
  }

  @Test
  void testSynchronizeWithCanIdHints() {
    ParsedLog wpilog = createMockWpilog(Map.of(
        "/drive/frontLeft/output", createNumericValues(100, 0),
        "/drive/frontRight/output", createNumericValues(100, 0.1) // Different phase
    ));

    // Create revlog signal that matches frontLeft's phase
    ParsedRevLog revlog = createMockRevlog(Map.of(
        1, new RevLogDevice(1, "SPARK MAX")
    ), Map.of(
        "SparkMax_1/appliedOutput", createRevLogSignal("appliedOutput", "SparkMax_1", 100, 0)
    ));

    Map<Integer, String> hints = Map.of(1, "frontLeft");

    SyncResult result = synchronizer.synchronize(wpilog, revlog, hints);

    assertTrue(result.isSuccessful());
  }

  @Test
  void testSynchronizeReturnsSignalPairResults() {
    ParsedLog wpilog = createWpilogWithSignal("/drive/output", 0.0, 100);
    ParsedRevLog revlog = createRevlogWithSignal("appliedOutput", 0.0, 100);

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    // Should have at least one signal pair result
    assertFalse(result.signalPairs().isEmpty());

    SignalPairResult pairResult = result.signalPairs().get(0);
    assertNotNull(pairResult.wpilogEntry());
    assertNotNull(pairResult.revlogSignal());
    assertTrue(pairResult.samplesUsed() > 0);
  }

  @Test
  void testSynchronizeConfidenceLevels() {
    // Test with highly correlated signals
    ParsedLog wpilog = createWpilogWithSignal("/drive/output", 0.0, 200);
    ParsedRevLog revlog = createRevlogWithSignal("appliedOutput", 0.0, 200);

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    // With identical signals, should have high confidence
    assertTrue(result.confidence() > 0.5);
    assertNotEquals(ConfidenceLevel.FAILED, result.confidenceLevel());
  }

  @Test
  void testSynchronizeExplanationGenerated() {
    ParsedLog wpilog = createWpilogWithSignal("/drive/output", 0.0, 100);
    ParsedRevLog revlog = createRevlogWithSignal("appliedOutput", 0.0, 100);

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    assertNotNull(result.explanation());
    assertFalse(result.explanation().isEmpty());
  }

  @Test
  void testSynchronizeWithUserProvidedOffset() {
    // Test the static factory method
    SyncResult result = SyncResult.fromUserOffset(1_000_000L); // 1 second

    assertEquals(1_000_000L, result.offsetMicros());
    assertEquals(SyncMethod.USER_PROVIDED, result.method());
    assertTrue(result.isSuccessful());
  }

  // ========== Cross-correlation accuracy tests ==========

  @Test
  void testSynchronizeWithNonTrivialOffset() {
    // Test with signals in different time domains but within the ±60s search window.
    // Without systemTime entries for coarse alignment, the search window is centered
    // around the raw timestamp difference. Offsets within ±60s are discoverable.
    //
    // wpilog: FPGA time starting at 5.0s
    // revlog: different clock starting at 50.0s
    // True offset: fpga_time = revlog_time + offset → offset = 5.0 - 50.0 = -45.0s
    double wpiStartTime = 5.0;
    double revStartTime = 50.0;
    double trueOffsetSec = wpiStartTime - revStartTime; // -45.0

    ParsedLog wpilog = createWpilogWithSignalAtTime("/drive/output", wpiStartTime, 200);
    ParsedRevLog revlog = createRevlogWithSignalAtTime("appliedOutput", revStartTime, 200);

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    assertTrue(result.isSuccessful(), "Sync should succeed with matching signals");
    double offsetSeconds = result.offsetSeconds();
    assertEquals(trueOffsetSec, offsetSeconds, 0.05,
        "Offset should be approximately " + trueOffsetSec + "s, got " + offsetSeconds + "s");
  }

  @Test
  void testSynchronizeWithSmallOffset() {
    // 100ms offset
    double wpiStart = 0.0;
    double revStart = 0.1; // 100ms later in revlog time domain
    double trueOffset = wpiStart - revStart; // -0.1

    ParsedLog wpilog = createWpilogWithSignalAtTime("/drive/output", wpiStart, 200);
    ParsedRevLog revlog = createRevlogWithSignalAtTime("appliedOutput", revStart, 200);

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    assertTrue(result.isSuccessful());
    assertEquals(trueOffset, result.offsetSeconds(), 0.02,
        "Should detect 100ms offset within 20ms accuracy");
  }

  @Test
  void testSynchronizeWithLargeOffset() {
    // 30s offset - within the ±60s search window
    double wpiStart = 10.0;
    double revStart = 40.0;
    double trueOffset = wpiStart - revStart; // -30.0

    ParsedLog wpilog = createWpilogWithSignalAtTime("/drive/output", wpiStart, 300);
    ParsedRevLog revlog = createRevlogWithSignalAtTime("appliedOutput", revStart, 300);

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    assertTrue(result.isSuccessful());
    assertEquals(trueOffset, result.offsetSeconds(), 0.05,
        "Should detect 30s offset within 50ms accuracy");
  }

  @Test
  void testSynchronizeWithFlatSignalFails() {
    // Create a flat wpilog signal
    Map<String, EntryInfo> entries = new HashMap<>();
    entries.put("/drive/output", new EntryInfo(1, "/drive/output", "double", ""));

    List<TimestampedValue> flatValues = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      flatValues.add(new TimestampedValue(i * 0.02, 5.0)); // Constant value
    }
    Map<String, List<TimestampedValue>> values = new HashMap<>();
    values.put("/drive/output", flatValues);
    ParsedLog wpilog = new ParsedLog("/test.wpilog", entries, values, 0, 4);

    ParsedRevLog revlog = createRevlogWithSignal("appliedOutput", 0.0, 200);

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    // Flat signal can't be correlated, should degrade gracefully
    assertNotNull(result);
  }

  @Test
  void testCoarseOffsetFailureReturnsFailed() {
    // When there's no systemTime and no signal pairs, should get FAILED, not offset=0
    ParsedLog wpilog = createMockWpilog(Map.of(
        "/sensors/custom", createNumericValues(50, 0)
    ));

    ParsedRevLog revlog = createMockRevlog(Map.of(), Map.of());

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    assertEquals(SyncMethod.FAILED, result.method(),
        "Should fail when no coarse offset and no signal pairs");
    assertFalse(result.isSuccessful());
  }

  // ========== Edge case tests for sync math ==========

  @Test
  void testSynchronizeWithSingleSampleSignalFails() {
    // Single sample in each signal - not enough for correlation
    Map<String, EntryInfo> entries = new HashMap<>();
    entries.put("/drive/output", new EntryInfo(1, "/drive/output", "double", ""));

    List<TimestampedValue> singleVal = List.of(new TimestampedValue(0.0, 1.0));
    Map<String, List<TimestampedValue>> values = new HashMap<>();
    values.put("/drive/output", singleVal);
    ParsedLog wpilog = new ParsedLog("/test.wpilog", entries, values, 0, 0);

    Map<Integer, RevLogDevice> devices = new HashMap<>();
    devices.put(1, new RevLogDevice(1, "SPARK MAX"));
    Map<String, RevLogSignal> signals = new HashMap<>();
    signals.put("SparkMax_1/appliedOutput",
        new RevLogSignal("appliedOutput", "SparkMax_1", singleVal, ""));
    ParsedRevLog revlog = new ParsedRevLog("/test.revlog", "20260320_143052",
        devices, signals, 0, 0, 1);

    SyncResult result = synchronizer.synchronize(wpilog, revlog);
    assertNotNull(result);
    // Should degrade gracefully - either fail or return low confidence
  }

  @Test
  void testSynchronizeWithIdenticalTimestampsHandledGracefully() {
    // All values at the same timestamp (zero-length signal)
    Map<String, EntryInfo> entries = new HashMap<>();
    entries.put("/drive/output", new EntryInfo(1, "/drive/output", "double", ""));

    List<TimestampedValue> sameTime = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      sameTime.add(new TimestampedValue(5.0, Math.sin(i * 0.1)));
    }
    Map<String, List<TimestampedValue>> values = new HashMap<>();
    values.put("/drive/output", sameTime);
    ParsedLog wpilog = new ParsedLog("/test.wpilog", entries, values, 5, 5);

    Map<Integer, RevLogDevice> devices = new HashMap<>();
    devices.put(1, new RevLogDevice(1, "SPARK MAX"));
    List<TimestampedValue> revSameTime = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      revSameTime.add(new TimestampedValue(10.0, Math.sin(i * 0.1)));
    }
    Map<String, RevLogSignal> signals = new HashMap<>();
    signals.put("SparkMax_1/appliedOutput",
        new RevLogSignal("appliedOutput", "SparkMax_1", revSameTime, ""));
    ParsedRevLog revlog = new ParsedRevLog("/test.revlog", "20260320_143052",
        devices, signals, 10, 10, 50);

    SyncResult result = synchronizer.synchronize(wpilog, revlog);
    // Should not throw, should handle zero-duration signals
    assertNotNull(result);
  }

  @Test
  void testSynchronizeWithLongDisabledPeriod() {
    // Simulates robot disabled for 2 minutes, then active for 2 minutes
    // The first 120s is flat, followed by chirp data
    int disabledSamples = 6000; // 120s at 50Hz
    int activeSamples = 6000;   // 120s at 50Hz
    int totalSamples = disabledSamples + activeSamples;

    double wpiStart = 0.0;
    double revStart = 3.0; // 3s offset
    double trueOffset = wpiStart - revStart;

    Map<String, EntryInfo> entries = new HashMap<>();
    entries.put("/drive/output", new EntryInfo(1, "/drive/output", "double", ""));

    List<TimestampedValue> wpiVals = new ArrayList<>();
    for (int i = 0; i < totalSamples; i++) {
      double t = wpiStart + i * 0.02;
      double value = i < disabledSamples ? 0.0 : chirpValue((i - disabledSamples) * 0.02);
      wpiVals.add(new TimestampedValue(t, value));
    }

    Map<String, List<TimestampedValue>> values = new HashMap<>();
    values.put("/drive/output", wpiVals);
    ParsedLog wpilog = new ParsedLog("/test.wpilog", entries, values,
        wpiStart, wpiStart + totalSamples * 0.02);

    List<TimestampedValue> revVals = new ArrayList<>();
    for (int i = 0; i < totalSamples; i++) {
      double t = revStart + i * 0.02;
      double value = i < disabledSamples ? 0.0 : chirpValue((i - disabledSamples) * 0.02);
      revVals.add(new TimestampedValue(t, value));
    }

    Map<Integer, RevLogDevice> devices = new HashMap<>();
    devices.put(1, new RevLogDevice(1, "SPARK MAX"));
    Map<String, RevLogSignal> signals = new HashMap<>();
    signals.put("SparkMax_1/appliedOutput",
        new RevLogSignal("appliedOutput", "SparkMax_1", revVals, ""));
    ParsedRevLog revlog = new ParsedRevLog("/test.revlog", "20260320_143052",
        devices, signals, revStart, revStart + totalSamples * 0.02, totalSamples);

    SyncResult result = synchronizer.synchronize(wpilog, revlog);

    assertTrue(result.isSuccessful(),
        "Should succeed even with long disabled period (high-variance window search)");
    assertEquals(trueOffset, result.offsetSeconds(), 0.1,
        "Should find correct offset from the active portion");
  }

  @Test
  void testSyncResultDriftCompensation() {
    // Test that drift-compensated offset varies with time
    SyncResult result = new SyncResult(
        1_000_000L, // 1s offset
        0.9,
        ConfidenceLevel.HIGH,
        List.of(),
        SyncMethod.CROSS_CORRELATION,
        "test",
        100.0,  // 100 ns/s drift
        500.0   // reference at 500s
    );

    // At reference time, offset should be exactly 1.0s
    double atRef = result.toFpgaTime(500.0);
    assertEquals(501.0, atRef, 0.0001);

    // 100s after reference, drift adds 100*100 = 10,000 ns = 0.01ms = 0.00001s
    double after100s = result.toFpgaTime(600.0);
    double expected = 600.0 + 1.0 + (100.0 * 100.0 / 1_000_000_000.0);
    assertEquals(expected, after100s, 0.0001);

    // 100s before reference, drift subtracts the same amount
    double before100s = result.toFpgaTime(400.0);
    double expectedBefore = 400.0 + 1.0 + (-100.0 * 100.0 / 1_000_000_000.0);
    assertEquals(expectedBefore, before100s, 0.0001);
  }

  @Test
  void testSyncResultNoDriftWhenZero() {
    SyncResult result = new SyncResult(
        1_000_000L, 0.9, ConfidenceLevel.HIGH, List.of(),
        SyncMethod.CROSS_CORRELATION, "test", 0.0, 0.0);

    // With zero drift, toFpgaTime should just add the constant offset
    assertEquals(501.0, result.toFpgaTime(500.0), 0.0001);
    assertEquals(601.0, result.toFpgaTime(600.0), 0.0001);
  }

  @Test
  void testUserProvidedOffset() {
    SyncResult result = SyncResult.fromUserOffset(500_000L); // 500ms

    assertEquals(SyncMethod.USER_PROVIDED, result.method());
    assertTrue(result.isSuccessful());
    assertEquals(500.0, result.offsetMillis(), 0.001);
    assertEquals(0.0, result.driftRateNanosPerSec());
  }

  @Test
  void testCustomParametersConstructor() {
    // Verify that custom parameters are accepted and produce valid results
    LogSynchronizer customSync = new LogSynchronizer(
        3000,   // smaller search window
        50.0,   // lower sample rate
        30000,  // fewer max samples
        1e-5,   // higher flat threshold
        0.6,    // higher min correlation
        0.8     // higher quality threshold
    );

    ParsedLog wpilog = createWpilogWithSignal("/drive/output", 0.0, 200);
    ParsedRevLog revlog = createRevlogWithSignal("appliedOutput", 0.0, 200);

    SyncResult result = customSync.synchronize(wpilog, revlog);
    assertNotNull(result);
    assertTrue(result.isSuccessful());
  }

  @Test
  void testTimezoneParameterAccepted() {
    // Verify that a custom timezone can be provided without error
    LogSynchronizer utcSync = new LogSynchronizer(
        LogSynchronizer.DEFAULT_SEARCH_WINDOW_SAMPLES,
        LogSynchronizer.DEFAULT_SAMPLE_RATE_HZ,
        LogSynchronizer.DEFAULT_MAX_RESAMPLE_SAMPLES,
        LogSynchronizer.DEFAULT_FLAT_SIGNAL_THRESHOLD,
        LogSynchronizer.DEFAULT_MIN_USEFUL_CORRELATION,
        LogSynchronizer.DEFAULT_HIGH_CORRELATION_THRESHOLD,
        ZoneId.of("UTC")
    );

    ParsedLog wpilog = createWpilogWithSignal("/drive/output", 0.0, 100);
    ParsedRevLog revlog = createRevlogWithSignal("appliedOutput", 0.0, 100);

    // Should not throw and should produce a valid result
    SyncResult result = utcSync.synchronize(wpilog, revlog);
    assertNotNull(result);
    assertTrue(result.isSuccessful());
  }

  @Test
  void testDefaultConstructorUsesDefaults() {
    // Verify default constructor produces same results as explicit defaults
    LogSynchronizer defaultSync = new LogSynchronizer();
    LogSynchronizer explicitSync = new LogSynchronizer(
        LogSynchronizer.DEFAULT_SEARCH_WINDOW_SAMPLES,
        LogSynchronizer.DEFAULT_SAMPLE_RATE_HZ,
        LogSynchronizer.DEFAULT_MAX_RESAMPLE_SAMPLES,
        LogSynchronizer.DEFAULT_FLAT_SIGNAL_THRESHOLD,
        LogSynchronizer.DEFAULT_MIN_USEFUL_CORRELATION,
        LogSynchronizer.DEFAULT_HIGH_CORRELATION_THRESHOLD
    );

    ParsedLog wpilog = createWpilogWithSignal("/drive/output", 0.0, 100);
    ParsedRevLog revlog = createRevlogWithSignal("appliedOutput", 0.0, 100);

    SyncResult r1 = defaultSync.synchronize(wpilog, revlog);
    SyncResult r2 = explicitSync.synchronize(wpilog, revlog);

    assertEquals(r1.offsetMicros(), r2.offsetMicros());
    assertEquals(r1.confidence(), r2.confidence(), 0.001);
  }

  // ========== Helper Methods ==========

  private ParsedLog createWpilogWithSignal(String entryName, double timeOffset, int samples) {
    Map<String, EntryInfo> entries = new HashMap<>();
    entries.put(entryName, new EntryInfo(1, entryName, "double", ""));

    Map<String, List<TimestampedValue>> values = new HashMap<>();
    values.put(entryName, createSineWave(samples, timeOffset));

    return new ParsedLog("/test.wpilog", entries, values, 0, samples * 0.02);
  }

  /**
   * Creates a wpilog with a chirp signal starting at a specific FPGA time.
   * Values are computed from the sample index, so corresponding samples in
   * wpilog and revlog have identical values regardless of their timestamp domain.
   */
  private ParsedLog createWpilogWithSignalAtTime(String entryName, double startTime, int samples) {
    Map<String, EntryInfo> entries = new HashMap<>();
    entries.put(entryName, new EntryInfo(1, entryName, "double", ""));

    List<TimestampedValue> vals = new ArrayList<>();
    for (int i = 0; i < samples; i++) {
      double t = startTime + i * 0.02;
      double value = chirpValue(i * 0.02);
      vals.add(new TimestampedValue(t, value));
    }

    Map<String, List<TimestampedValue>> values = new HashMap<>();
    values.put(entryName, vals);
    return new ParsedLog("/test.wpilog", entries, values, startTime, startTime + samples * 0.02);
  }

  /**
   * Creates a revlog with a chirp signal starting at a specific CLOCK_MONOTONIC time.
   * Values are computed from the sample index (same as wpilog), so sample i in the
   * revlog has the same value as sample i in the wpilog. The cross-correlator must
   * find the lag that accounts for the timestamp domain difference.
   */
  private ParsedRevLog createRevlogWithSignalAtTime(String signalName, double startTime, int samples) {
    Map<Integer, RevLogDevice> devices = new HashMap<>();
    devices.put(1, new RevLogDevice(1, "SPARK MAX"));

    List<TimestampedValue> vals = new ArrayList<>();
    for (int i = 0; i < samples; i++) {
      double t = startTime + i * 0.02;
      double value = chirpValue(i * 0.02);
      vals.add(new TimestampedValue(t, value));
    }

    Map<String, RevLogSignal> signals = new HashMap<>();
    String deviceKey = "SparkMax_1";
    signals.put(deviceKey + "/" + signalName,
        new RevLogSignal(signalName, deviceKey, vals, ""));

    return new ParsedRevLog("/test.revlog", "20260320_143052", devices, signals,
        startTime, startTime + samples * 0.02, samples);
  }

  /**
   * Generates a chirp signal value. The chirp has increasing frequency to create
   * a unique pattern that can only match at one time offset.
   */
  private double chirpValue(double t) {
    return Math.sin(t * 2 * Math.PI * (1 + t * 0.5)) + 0.5 * t;
  }

  private ParsedRevLog createRevlogWithSignal(String signalName, double timeOffset, int samples) {
    Map<Integer, RevLogDevice> devices = new HashMap<>();
    devices.put(1, new RevLogDevice(1, "SPARK MAX"));

    Map<String, RevLogSignal> signals = new HashMap<>();
    String deviceKey = "SparkMax_1";
    signals.put(deviceKey + "/" + signalName,
        new RevLogSignal(signalName, deviceKey, createSineWave(samples, timeOffset), ""));

    return new ParsedRevLog("/test.revlog", "20260320_143052", devices, signals,
        0, samples * 0.02, samples);
  }

  private List<TimestampedValue> createSineWave(int samples, double timeOffset) {
    List<TimestampedValue> values = new ArrayList<>();
    for (int i = 0; i < samples; i++) {
      double time = i * 0.02 + timeOffset;
      // Use a chirp signal (increasing frequency) to avoid periodicity issues
      // This creates a unique pattern that can only be matched at the correct offset
      double value = Math.sin(time * 2 * Math.PI * (1 + time * 0.5)) + 0.5 * time;
      values.add(new TimestampedValue(i * 0.02, value)); // Timestamps start at 0
    }
    return values;
  }

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

  private List<TimestampedValue> createNumericValues(int count, double phase) {
    List<TimestampedValue> values = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      values.add(new TimestampedValue(i * 0.02, Math.sin(i * 0.1 + phase)));
    }
    return values;
  }

  private RevLogSignal createRevLogSignal(String name, String deviceKey, int sampleCount, double phase) {
    List<TimestampedValue> values = new ArrayList<>();
    for (int i = 0; i < sampleCount; i++) {
      values.add(new TimestampedValue(i * 0.02, Math.sin(i * 0.1 + phase)));
    }
    return new RevLogSignal(name, deviceKey, values, "");
  }
}
