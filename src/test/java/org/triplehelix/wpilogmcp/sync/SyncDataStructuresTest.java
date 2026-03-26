package org.triplehelix.wpilogmcp.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for synchronization data structures.
 */
class SyncDataStructuresTest {

  // ========== ConfidenceLevel Tests ==========

  @Test
  void testConfidenceLevelFromScoreHigh() {
    assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.fromScore(0.95));
    assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.fromScore(0.85));
  }

  @Test
  void testConfidenceLevelFromScoreMedium() {
    assertEquals(ConfidenceLevel.MEDIUM, ConfidenceLevel.fromScore(0.7));
    assertEquals(ConfidenceLevel.MEDIUM, ConfidenceLevel.fromScore(0.6));
  }

  @Test
  void testConfidenceLevelFromScoreLow() {
    assertEquals(ConfidenceLevel.LOW, ConfidenceLevel.fromScore(0.5));
    assertEquals(ConfidenceLevel.LOW, ConfidenceLevel.fromScore(0.3));
  }

  @Test
  void testConfidenceLevelFromScoreFailed() {
    assertEquals(ConfidenceLevel.FAILED, ConfidenceLevel.fromScore(0.2));
    assertEquals(ConfidenceLevel.FAILED, ConfidenceLevel.fromScore(0.0));
  }

  @Test
  void testConfidenceLevelProperties() {
    assertEquals("high", ConfidenceLevel.HIGH.getLabel());
    assertEquals(0.85, ConfidenceLevel.HIGH.getThreshold());
    assertNotNull(ConfidenceLevel.HIGH.getDescription());
    assertEquals("1-5", ConfidenceLevel.HIGH.getAccuracyMs());
  }

  @Test
  void testConfidenceLevelNumericValue() {
    assertEquals(1.0, ConfidenceLevel.HIGH.getNumericValue(), 0.001);
    assertEquals(0.67, ConfidenceLevel.MEDIUM.getNumericValue(), 0.001);
    assertEquals(0.33, ConfidenceLevel.LOW.getNumericValue(), 0.001);
    assertEquals(0.0, ConfidenceLevel.FAILED.getNumericValue(), 0.001);
  }

  // ========== SyncMethod Tests ==========

  @Test
  void testSyncMethodDescriptions() {
    assertNotNull(SyncMethod.CROSS_CORRELATION.getDescription());
    assertNotNull(SyncMethod.SYSTEM_TIME_ONLY.getDescription());
    assertNotNull(SyncMethod.USER_PROVIDED.getDescription());
    assertNotNull(SyncMethod.FAILED.getDescription());
  }

  // ========== SignalPairResult Tests ==========

  @Test
  void testSignalPairResultBasic() {
    SignalPairResult result = new SignalPairResult(
        "/drive/output",
        "SparkMax_1/appliedOutput",
        5000L, // 5ms offset
        0.92,
        1000
    );

    assertEquals("/drive/output", result.wpilogEntry());
    assertEquals("SparkMax_1/appliedOutput", result.revlogSignal());
    assertEquals(5000L, result.estimatedOffsetMicros());
    assertEquals(0.92, result.correlation(), 0.001);
    assertEquals(1000, result.samplesUsed());
  }

  @Test
  void testSignalPairResultOffsetConversions() {
    SignalPairResult result = new SignalPairResult(
        "entry", "signal", 5000L, 0.9, 100
    );

    assertEquals(5.0, result.estimatedOffsetMillis(), 0.001);
    assertEquals(0.005, result.estimatedOffsetSeconds(), 0.00001);
  }

  @Test
  void testSignalPairResultCorrelationChecks() {
    SignalPairResult strong = new SignalPairResult("e", "s", 0, 0.8, 100);
    assertTrue(strong.isStrongCorrelation());
    assertTrue(strong.isUsableCorrelation());

    SignalPairResult medium = new SignalPairResult("e", "s", 0, 0.6, 100);
    assertFalse(medium.isStrongCorrelation());
    assertTrue(medium.isUsableCorrelation());

    SignalPairResult weak = new SignalPairResult("e", "s", 0, 0.3, 100);
    assertFalse(weak.isStrongCorrelation());
    assertFalse(weak.isUsableCorrelation());
  }

  @Test
  void testSignalPairResultFailed() {
    SignalPairResult failed = SignalPairResult.failed("entry", "signal");

    assertEquals("entry", failed.wpilogEntry());
    assertEquals("signal", failed.revlogSignal());
    assertEquals(0, failed.estimatedOffsetMicros());
    assertEquals(0.0, failed.correlation());
    assertEquals(0, failed.samplesUsed());
  }

  @Test
  void testFiveArgConstructorWarningIsNull() {
    var result = new SignalPairResult("e", "s", 1000L, 0.9, 100);
    assertNull(result.warning(), "5-arg constructor should set warning to null");
  }

  @Test
  void testSixArgConstructorWarningIsSet() {
    var result = new SignalPairResult("e", "s", 1000L, 0.9, 100, "boundary peak");
    assertEquals("boundary peak", result.warning());
  }

  @Test
  void testSixArgConstructorWarningNull() {
    var result = new SignalPairResult("e", "s", 1000L, 0.9, 100, null);
    assertNull(result.warning());
  }

  @Test
  void testFiveArgEqualsCanonicalSixArg() {
    var fiveArg = new SignalPairResult("e", "s", 5000L, 0.85, 200);
    var sixArg = new SignalPairResult("e", "s", 5000L, 0.85, 200, null);
    assertEquals(fiveArg, sixArg, "5-arg constructor should produce same record as 6-arg with null warning");
    assertEquals(fiveArg.hashCode(), sixArg.hashCode());
  }

  @Test
  void testFailedWarningIsNull() {
    var failed = SignalPairResult.failed("entry", "signal");
    assertNull(failed.warning(), "failed() should produce a result with null warning");
  }

  // ========== SyncResult Tests ==========

  @Test
  void testSyncResultBasic() {
    List<SignalPairResult> pairs = List.of(
        new SignalPairResult("e1", "s1", 5000, 0.9, 100),
        new SignalPairResult("e2", "s2", 5100, 0.85, 100)
    );

    SyncResult result = new SyncResult(
        5000L,
        0.9,
        ConfidenceLevel.HIGH,
        pairs,
        SyncMethod.CROSS_CORRELATION,
        "Test explanation"
    );

    assertEquals(5000L, result.offsetMicros());
    assertEquals(0.9, result.confidence(), 0.001);
    assertEquals(ConfidenceLevel.HIGH, result.confidenceLevel());
    assertEquals(2, result.signalPairs().size());
    assertEquals(SyncMethod.CROSS_CORRELATION, result.method());
    assertEquals("Test explanation", result.explanation());
  }

  @Test
  void testSyncResultAutoConfidenceLevel() {
    SyncResult result = new SyncResult(
        5000L,
        0.9, // Should auto-assign HIGH
        List.of(),
        SyncMethod.CROSS_CORRELATION,
        "Test"
    );

    assertEquals(ConfidenceLevel.HIGH, result.confidenceLevel());
  }

  @Test
  void testSyncResultOffsetConversions() {
    SyncResult result = new SyncResult(5_000_000L, 0.9, List.of(),
        SyncMethod.CROSS_CORRELATION, "Test");

    assertEquals(5000.0, result.offsetMillis(), 0.001);
    assertEquals(5.0, result.offsetSeconds(), 0.001);
  }

  @Test
  void testSyncResultIsSuccessful() {
    SyncResult success = new SyncResult(0, 0.9, List.of(),
        SyncMethod.CROSS_CORRELATION, "");
    assertTrue(success.isSuccessful());

    SyncResult failed = SyncResult.failed("Test failure");
    assertFalse(failed.isSuccessful());
  }

  @Test
  void testSyncResultIsHighConfidence() {
    SyncResult high = new SyncResult(0, 0.9, ConfidenceLevel.HIGH,
        List.of(), SyncMethod.CROSS_CORRELATION, "");
    assertTrue(high.isHighConfidence());

    SyncResult medium = new SyncResult(0, 0.7, ConfidenceLevel.MEDIUM,
        List.of(), SyncMethod.CROSS_CORRELATION, "");
    assertFalse(medium.isHighConfidence());
  }

  @Test
  void testSyncResultStrongPairCount() {
    List<SignalPairResult> pairs = List.of(
        new SignalPairResult("e1", "s1", 0, 0.9, 100),  // Strong
        new SignalPairResult("e2", "s2", 0, 0.8, 100),  // Strong
        new SignalPairResult("e3", "s3", 0, 0.5, 100)   // Not strong
    );

    SyncResult result = new SyncResult(0, 0.8, pairs,
        SyncMethod.CROSS_CORRELATION, "");

    assertEquals(2, result.strongPairCount());
  }

  @Test
  void testSyncResultToFpgaTime() {
    SyncResult result = new SyncResult(1_000_000L, 0.9, List.of(),
        SyncMethod.CROSS_CORRELATION, ""); // 1 second offset

    assertEquals(11.0, result.toFpgaTime(10.0), 0.001);
    assertEquals(11_000_000L, result.toFpgaTimeMicros(10_000_000L));
  }

  @Test
  void testSyncResultFailed() {
    SyncResult failed = SyncResult.failed("Could not sync");

    assertFalse(failed.isSuccessful());
    assertEquals(SyncMethod.FAILED, failed.method());
    assertEquals(0, failed.offsetMicros());
    assertEquals(0.0, failed.confidence());
    assertEquals(ConfidenceLevel.FAILED, failed.confidenceLevel());
    assertTrue(failed.explanation().contains("Could not sync"));
  }

  @Test
  void testSyncResultFromUserOffset() {
    SyncResult result = SyncResult.fromUserOffset(10_000L);

    assertEquals(10_000L, result.offsetMicros());
    assertEquals(SyncMethod.USER_PROVIDED, result.method());
    assertEquals(ConfidenceLevel.MEDIUM, result.confidenceLevel());
    assertTrue(result.isSuccessful());
  }

  @Test
  void testSyncResultFromSystemTimeOnly() {
    SyncResult result = SyncResult.fromSystemTimeOnly(5000L, "Coarse estimate");

    assertEquals(5000L, result.offsetMicros());
    assertEquals(SyncMethod.SYSTEM_TIME_ONLY, result.method());
    assertEquals(ConfidenceLevel.LOW, result.confidenceLevel());
    assertTrue(result.isSuccessful());
  }

  @Test
  void testSyncResultEstimatedAccuracy() {
    SyncResult high = new SyncResult(0, 0.9, ConfidenceLevel.HIGH,
        List.of(), SyncMethod.CROSS_CORRELATION, "");
    assertEquals("1-5", high.estimatedAccuracyMs());

    SyncResult medium = new SyncResult(0, 0.7, ConfidenceLevel.MEDIUM,
        List.of(), SyncMethod.CROSS_CORRELATION, "");
    assertEquals("5-50", medium.estimatedAccuracyMs());
  }
}
