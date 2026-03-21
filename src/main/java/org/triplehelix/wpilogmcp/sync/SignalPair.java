package org.triplehelix.wpilogmcp.sync;

import java.util.List;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * A candidate pair of signals from wpilog and revlog for cross-correlation.
 *
 * <p>Signal pairs are identified by matching signal types (e.g., both representing
 * motor output, velocity, or current) and optionally by device/entry name hints.
 *
 * @param wpilogEntry The wpilog entry name (e.g., "/drive/frontLeft/output")
 * @param revlogSignal The revlog signal key (e.g., "SparkMax_1/appliedOutput")
 * @param wpilogValues The timestamped values from wpilog
 * @param revlogValues The timestamped values from revlog
 * @param signalType The type of signal (for debugging/logging)
 * @param matchScore How well the names matched (0.0 to 1.0)
 * @since 0.5.0
 */
public record SignalPair(
    String wpilogEntry,
    String revlogSignal,
    List<TimestampedValue> wpilogValues,
    List<TimestampedValue> revlogValues,
    String signalType,
    double matchScore) {

  /**
   * Gets the number of samples available in the wpilog signal.
   *
   * @return The sample count
   */
  public int wpilogSampleCount() {
    return wpilogValues.size();
  }

  /**
   * Gets the number of samples available in the revlog signal.
   *
   * @return The sample count
   */
  public int revlogSampleCount() {
    return revlogValues.size();
  }

  /**
   * Gets the minimum sample count between both signals.
   *
   * @return The minimum of wpilog and revlog sample counts
   */
  public int minSampleCount() {
    return Math.min(wpilogValues.size(), revlogValues.size());
  }

  /**
   * Checks if this pair has enough samples for meaningful correlation.
   * Requires at least 10 samples in each signal.
   *
   * @return true if sufficient samples exist
   */
  public boolean hasSufficientSamples() {
    return wpilogValues.size() >= 10 && revlogValues.size() >= 10;
  }

  /**
   * Gets the time range overlap between the two signals in seconds.
   * Returns 0 if signals don't overlap or are empty.
   *
   * <p><b>Warning:</b> This method compares raw timestamps and is only meaningful
   * when both signals are in the same time domain. Before synchronization,
   * wpilog timestamps (FPGA time) and revlog timestamps (CLOCK_MONOTONIC)
   * are in different domains and this value is not meaningful.
   *
   * @return The overlap duration in seconds
   */
  public double getTimeOverlap() {
    if (wpilogValues.isEmpty() || revlogValues.isEmpty()) {
      return 0;
    }

    double wpiStart = wpilogValues.get(0).timestamp();
    double wpiEnd = wpilogValues.get(wpilogValues.size() - 1).timestamp();
    double revStart = revlogValues.get(0).timestamp();
    double revEnd = revlogValues.get(revlogValues.size() - 1).timestamp();

    double overlapStart = Math.max(wpiStart, revStart);
    double overlapEnd = Math.min(wpiEnd, revEnd);

    return Math.max(0, overlapEnd - overlapStart);
  }

  /**
   * Creates a simple signal pair without values (for testing/building).
   *
   * @param wpilogEntry The wpilog entry name
   * @param revlogSignal The revlog signal key
   * @param signalType The signal type
   * @return A SignalPair with empty value lists
   */
  public static SignalPair of(String wpilogEntry, String revlogSignal, String signalType) {
    return new SignalPair(wpilogEntry, revlogSignal, List.of(), List.of(), signalType, 1.0);
  }
}
