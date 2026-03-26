package org.triplehelix.wpilogmcp.sync;

/**
 * Result of cross-correlating a pair of signals from wpilog and revlog.
 *
 * <p>Each signal pair represents a potential correspondence between a wpilog entry
 * (e.g., "/drive/frontLeft/output") and a revlog signal (e.g., "SparkMax_1/appliedOutput").
 * The correlation score indicates how well the signals match when aligned.
 *
 * @param wpilogEntry The wpilog entry name that was correlated
 * @param revlogSignal The revlog signal key that was correlated
 * @param estimatedOffsetMicros The estimated time offset in microseconds
 *        (add to revlog timestamps to get FPGA time)
 * @param correlation The Pearson correlation coefficient at the best offset (-1 to 1)
 * @param samplesUsed The number of samples used in the correlation
 * @param warning Optional warning about the result quality (e.g., boundary peak), or null
 * @since 0.5.0
 */
public record SignalPairResult(
    String wpilogEntry,
    String revlogSignal,
    long estimatedOffsetMicros,
    double correlation,
    int samplesUsed,
    String warning) {

  /**
   * Backward-compatible constructor without warning.
   */
  public SignalPairResult(
      String wpilogEntry,
      String revlogSignal,
      long estimatedOffsetMicros,
      double correlation,
      int samplesUsed) {
    this(wpilogEntry, revlogSignal, estimatedOffsetMicros, correlation, samplesUsed, null);
  }

  /**
   * Gets the estimated offset in milliseconds.
   *
   * @return The offset in milliseconds
   */
  public double estimatedOffsetMillis() {
    return estimatedOffsetMicros / 1000.0;
  }

  /**
   * Gets the estimated offset in seconds.
   *
   * @return The offset in seconds
   */
  public double estimatedOffsetSeconds() {
    return estimatedOffsetMicros / 1_000_000.0;
  }

  /**
   * Checks if this result has a strong correlation (> 0.7).
   *
   * @return true if correlation is strong
   */
  public boolean isStrongCorrelation() {
    return correlation > 0.7;
  }

  /**
   * Checks if this result has a usable correlation (> 0.5).
   *
   * @return true if correlation is usable
   */
  public boolean isUsableCorrelation() {
    return correlation > 0.5;
  }

  /**
   * Creates a failed result for a signal pair that couldn't be correlated.
   *
   * @param wpilogEntry The wpilog entry
   * @param revlogSignal The revlog signal
   * @return A result with zero correlation
   */
  public static SignalPairResult failed(String wpilogEntry, String revlogSignal) {
    return new SignalPairResult(wpilogEntry, revlogSignal, 0, 0.0, 0);
  }
}
