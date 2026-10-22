/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

/**
 * The summary statistics every tool reports of finite numbers: the count, the extremes, the
 * mean, the sample standard deviation and, from sorted values, the median and percentiles. One
 * arithmetic serves {@code get_statistics}, {@code compare_matches}, {@code compare_sessions},
 * {@code compare_poses}, {@code align_entries} and {@code analyze_vision}, so two tools asked
 * about the same values give the same numbers.
 *
 * <p>Values are added one at a time, so a tool can summarize a stream it never holds, as
 * {@code compare_sessions} does across sessions. The mean is a compensated (Kahan) sum with its
 * final correction, computed as the JDK's own summary statistics compute it. The deviation, with
 * Bessel's correction, is the one-pass (Welford) form computed on each value
 * less the first, with its running mean and sum of squares compensated too. The plain one-pass
 * form loses digits when values sit far from zero relative to their spread: on synthetic
 * telemetry it was off by 1 part in 10^12 for an encoder count at 10^6 +- 10 and by 2 parts in
 * 10^10 for a timestamp ramp, where this form and two passes over the values were both exact.
 * Two summaries combine by the parallel rule. A single value has no standard deviation, and no
 * value has no statistic at all: those are null, never zero, as the project's rule on
 * uncomputable values requires.
 */
final class SummaryStatistics {
  private long count;
  private double sum;
  private double compensation;
  /** The plain sum, which recovers the correctly signed infinity when the compensated sum
   * spuriously becomes NaN after an overflow. */
  private double simpleSum;
  /** The first value; the deviation's sums see each value less it, the spread, not the offset. */
  private double shift;
  private double runningMean;
  private double meanCompensation;
  private double m2;
  private double m2Compensation;
  private double min = Double.POSITIVE_INFINITY;
  private double max = Double.NEGATIVE_INFINITY;

  /** The summary of {@code values}, added in their order. */
  static SummaryStatistics of(double[] values) {
    var summary = new SummaryStatistics();
    for (double value : values) summary.add(value);
    return summary;
  }

  /**
   * Adds a value. Callers drop non-finite values and count them themselves; one added anyway
   * carries through the sums as IEEE arithmetic does, as the JDK's own summaries do.
   */
  void add(double value) {
    if (count == 0) shift = value;
    count++;
    simpleSum += value;
    addCompensated(value);
    double shifted = value - shift;
    double delta = shifted - runningMean;
    double step = delta / count - meanCompensation;
    double mean = runningMean + step;
    meanCompensation = (mean - runningMean) - step;
    runningMean = mean;
    double square = delta * (shifted - runningMean) - m2Compensation;
    double total = m2 + square;
    m2Compensation = (total - m2) - square;
    m2 = total;
    min = Math.min(min, value);
    max = Math.max(max, value);
  }

  /** Adds every value another summary holds, as if this summary had seen them too. */
  void combine(SummaryStatistics other) {
    if (other.count == 0) return;
    if (count == 0) {
      copy(other);
      return;
    }
    long n = count + other.count;
    double mean = runningMean - meanCompensation;
    double delta = (other.shift - shift) + ((other.runningMean - other.meanCompensation) - mean);
    m2 = (m2 - m2Compensation) + (other.m2 - other.m2Compensation) + delta * delta * count * other.count / n;
    m2Compensation = 0;
    runningMean = mean + delta * other.count / n;
    meanCompensation = 0;
    count = n;
    simpleSum += other.simpleSum;
    addCompensated(other.sum);
    addCompensated(-other.compensation);
    min = Math.min(min, other.min);
    max = Math.max(max, other.max);
  }

  private void copy(SummaryStatistics other) {
    count = other.count;
    sum = other.sum;
    compensation = other.compensation;
    simpleSum = other.simpleSum;
    shift = other.shift;
    runningMean = other.runningMean;
    meanCompensation = other.meanCompensation;
    m2 = other.m2;
    m2Compensation = other.m2Compensation;
    min = other.min;
    max = other.max;
  }

  private void addCompensated(double value) {
    double corrected = value - compensation;
    double total = sum + corrected;
    compensation = (total - sum) - corrected;
    sum = total;
  }

  long count() {
    return count;
  }

  /** The mean; null with no value. */
  Double mean() {
    if (count == 0) return null;
    double total = sum - compensation;
    if (Double.isNaN(total) && Double.isInfinite(simpleSum)) total = simpleSum;
    return total / count;
  }

  /** The sample standard deviation, with n - 1; null for fewer than two values. */
  Double stdDev() {
    return count < 2 ? null : Math.sqrt((m2 - m2Compensation) / (count - 1));
  }

  Double min() {
    return count == 0 ? null : min;
  }

  Double max() {
    return count == 0 ? null : max;
  }

  /** The p-quantile of sorted values by the shared type-7 rule, p from 0 to 1. */
  static double percentile(double[] sorted, double p) {
    return ToolUtils.percentile(sorted, p);
  }

  /** The median of sorted values: halfway between the middle two of an even count. Halving each
   * before adding gives their average without overflowing near the largest double. */
  static double median(double[] sorted) {
    return percentile(sorted, 0.5);
  }

  /**
   * Circular statistics of angles: the mean direction, the resultant length R (1 when all are
   * equal, 0 when they spread evenly) and the circular standard deviation sqrt(-2 ln R). Angles
   * have no order, so these replace the extremes, mean and percentiles. They are kept as running
   * sums of sines and cosines, so they too stream and combine.
   */
  static final class Circular {
    private final double toRadians;
    private long count;
    private double sin;
    private double cos;

    /** @param period a full turn in the angles' unit: 2 pi for radians, 360 for degrees */
    Circular(double period) {
      toRadians = 2 * Math.PI / period;
    }

    /** Adds a finite angle; a non-finite one is ignored, as it has no direction. */
    void add(double angle) {
      if (!Double.isFinite(angle)) return;
      cos += Math.cos(angle * toRadians);
      sin += Math.sin(angle * toRadians);
      count++;
    }

    void combine(Circular other) {
      count += other.count;
      sin += other.sin;
      cos += other.cos;
    }

    long count() {
      return count;
    }

    /** R, from 0 to 1; null with no angle. */
    Double resultantLength() {
      return count == 0 ? null : Math.min(1.0, Math.hypot(cos / count, sin / count));
    }

    /** Whether the angles have a mean direction: R is not 0. */
    boolean directed() {
      var r = resultantLength();
      return r != null && r > 1e-12;
    }

    /** The mean direction in the angles' unit; null when the angles have none. */
    Double mean() {
      return directed() ? Math.atan2(sin / count, cos / count) / toRadians : null;
    }

    /** The circular standard deviation in the angles' unit; null when the angles have no mean
     * direction. */
    Double stdDev() {
      return directed() ? Math.sqrt(-2 * Math.log(resultantLength())) / toRadians : null;
    }
  }
}
