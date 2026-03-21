package org.triplehelix.wpilogmcp.sync;

import java.util.List;

/**
 * Result of synchronizing a revlog with a wpilog file.
 *
 * <p>Contains the calculated time offset, confidence metrics, and details about
 * how the synchronization was performed. The offset can be applied to revlog
 * timestamps to convert them to FPGA time.
 *
 * <p><b>Usage:</b>
 * <pre>
 * long fpgaTimeMicros = revlogTimestampMicros + syncResult.offsetMicros();
 * </pre>
 *
 * <p>For long recordings (&gt;15 minutes), clock drift between the FPGA clock and
 * the monotonic clock may cause timestamps to diverge. The {@link #driftRateNanosPerSec()}
 * field estimates this linear skew so callers can compensate.
 *
 * @param offsetMicros The time offset in microseconds to add to revlog timestamps
 *        to get FPGA time (at the reference time)
 * @param confidence The confidence score (0.0 to 1.0) indicating sync reliability
 * @param confidenceLevel The categorical confidence level (high/medium/low/failed)
 * @param signalPairs Results from individual signal pair correlations
 * @param method The synchronization method that was used
 * @param explanation Human-readable explanation of the synchronization result
 * @param driftRateNanosPerSec Estimated clock drift rate in nanoseconds per second.
 *        Zero if drift could not be estimated or recording is too short.
 * @param referenceTimeSec The revlog timestamp (in seconds) at which offsetMicros
 *        is most accurate. For drift-compensated results, offset accuracy
 *        degrades with distance from this point.
 * @since 0.5.0
 */
public record SyncResult(
    long offsetMicros,
    double confidence,
    ConfidenceLevel confidenceLevel,
    List<SignalPairResult> signalPairs,
    SyncMethod method,
    String explanation,
    double driftRateNanosPerSec,
    double referenceTimeSec) {

  /**
   * Creates a SyncResult with automatic confidence level determination.
   */
  public SyncResult(
      long offsetMicros,
      double confidence,
      List<SignalPairResult> signalPairs,
      SyncMethod method,
      String explanation) {
    this(offsetMicros, confidence, ConfidenceLevel.fromScore(confidence),
        signalPairs, method, explanation, 0.0, 0.0);
  }

  /**
   * Creates a SyncResult with explicit confidence level (no drift).
   */
  public SyncResult(
      long offsetMicros,
      double confidence,
      ConfidenceLevel confidenceLevel,
      List<SignalPairResult> signalPairs,
      SyncMethod method,
      String explanation) {
    this(offsetMicros, confidence, confidenceLevel,
        signalPairs, method, explanation, 0.0, 0.0);
  }

  /**
   * Gets the offset in milliseconds.
   *
   * @return The offset in milliseconds
   */
  public double offsetMillis() {
    return offsetMicros / 1000.0;
  }

  /**
   * Gets the offset in seconds.
   *
   * @return The offset in seconds
   */
  public double offsetSeconds() {
    return offsetMicros / 1_000_000.0;
  }

  /**
   * Checks if synchronization was successful.
   *
   * @return true if sync succeeded (method is not FAILED)
   */
  public boolean isSuccessful() {
    return method != SyncMethod.FAILED;
  }

  /**
   * Checks if the sync has high confidence.
   *
   * @return true if confidence level is HIGH
   */
  public boolean isHighConfidence() {
    return confidenceLevel == ConfidenceLevel.HIGH;
  }

  /**
   * Gets the number of signal pairs that had strong correlation.
   *
   * @return Count of pairs with correlation > 0.7
   */
  public int strongPairCount() {
    return (int) signalPairs.stream()
        .filter(SignalPairResult::isStrongCorrelation)
        .count();
  }

  /**
   * Gets the estimated timing accuracy in milliseconds.
   *
   * @return The accuracy range string (e.g., "1-5", "5-50")
   */
  public String estimatedAccuracyMs() {
    return confidenceLevel.getAccuracyMs();
  }

  /**
   * Converts a revlog timestamp (in seconds) to FPGA time, compensating for
   * clock drift if a drift rate was estimated.
   *
   * @param revlogTimestampSeconds The revlog timestamp in seconds
   * @return The corresponding FPGA timestamp in seconds
   */
  public double toFpgaTime(double revlogTimestampSeconds) {
    double baseOffset = offsetSeconds();
    if (driftRateNanosPerSec != 0.0 && referenceTimeSec != 0.0) {
      double dt = revlogTimestampSeconds - referenceTimeSec;
      double driftCorrection = dt * driftRateNanosPerSec / 1_000_000_000.0;
      return revlogTimestampSeconds + baseOffset + driftCorrection;
    }
    return revlogTimestampSeconds + baseOffset;
  }

  /**
   * Converts a revlog timestamp (in microseconds) to FPGA time, compensating for
   * clock drift if a drift rate was estimated.
   *
   * @param revlogTimestampMicros The revlog timestamp in microseconds
   * @return The corresponding FPGA timestamp in microseconds
   */
  public long toFpgaTimeMicros(long revlogTimestampMicros) {
    if (driftRateNanosPerSec != 0.0 && referenceTimeSec != 0.0) {
      double dtSec = (revlogTimestampMicros / 1_000_000.0) - referenceTimeSec;
      long driftMicros = Math.round(dtSec * driftRateNanosPerSec / 1_000.0);
      return revlogTimestampMicros + offsetMicros + driftMicros;
    }
    return revlogTimestampMicros + offsetMicros;
  }

  /**
   * Creates a failed sync result.
   *
   * @param explanation The reason for failure
   * @return A failed SyncResult
   */
  public static SyncResult failed(String explanation) {
    return new SyncResult(
        0, 0.0, ConfidenceLevel.FAILED, List.of(), SyncMethod.FAILED,
        explanation, 0.0, 0.0);
  }

  /**
   * Creates a sync result from a user-provided offset.
   *
   * @param offsetMicros The user-provided offset in microseconds
   * @return A SyncResult with USER_PROVIDED method
   */
  public static SyncResult fromUserOffset(long offsetMicros) {
    return new SyncResult(
        offsetMicros, 0.5, ConfidenceLevel.MEDIUM, List.of(), SyncMethod.USER_PROVIDED,
        "Using user-provided offset of " + (offsetMicros / 1000.0) + "ms", 0.0, 0.0);
  }

  /**
   * Creates a sync result from coarse system time alignment only.
   *
   * @param offsetMicros The estimated offset in microseconds
   * @param explanation Explanation of how the offset was determined
   * @return A SyncResult with SYSTEM_TIME_ONLY method and low confidence
   */
  public static SyncResult fromSystemTimeOnly(long offsetMicros, String explanation) {
    return new SyncResult(
        offsetMicros, 0.2, ConfidenceLevel.LOW, List.of(), SyncMethod.SYSTEM_TIME_ONLY,
        explanation, 0.0, 0.0);
  }
}
