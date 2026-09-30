/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Computes and holds data quality metrics for a time series.
 *
 * <p>Used by analytical tools to attach quality metadata to responses, enabling
 * LLMs to calibrate their confidence when interpreting results.
 *
 * <p>First the series' sampling is classified from its timing and values (review issue G1):
 * <ul>
 *   <li>{@link Sampling#PERIODIC}: most intervals within half of the median interval of it (a
 *       value logged every loop).</li>
 *   <li>{@link Sampling#CHANGE_ONLY}: irregular timing and no two consecutive values equal — logged
 *       only when the value changes, as AdvantageKit does, so a long interval means the value
 *       held.</li>
 *   <li>{@link Sampling#EVENT}: irregular timing otherwise (occasional events, too few samples to
 *       tell).</li>
 * </ul>
 *
 * <p>Quality score (0.0 to 1.0), each penalty listed in {@code reasons}:
 * <ul>
 *   <li>up to -0.3 for long intervals (over 5x the median), periodic and change-only series: as
 *       a fraction of the time span, 20% being the full penalty. For a change-only series they
 *       are holds, not missing data, but statistics weigh samples, not time</li>
 *   <li>up to -0.2 for timing jitter, periodic series only: median absolute deviation of the
 *       intervals relative to the median interval (robust to a few long gaps)</li>
 *   <li>up to -0.2 for NaN/Infinity values (scaled by ratio to total)</li>
 *   <li>-0.3 for fewer than 100 samples, -0.15 for fewer than 500</li>
 * </ul>
 *
 * @param sampleCount Number of samples
 * @param timeSpanSeconds First to last sample (summed over windows)
 * @param gapCount Intervals over 5x the median (periodic series)
 * @param maxGapMs Longest gap
 * @param totalGapMs Time in gaps
 * @param nanFiltered Non-finite values
 * @param effectiveSampleRateHz One over the median interval
 * @param qualityScore The score
 * @param sampling How the series was sampled
 * @param jitterRatio Median absolute deviation of the intervals over the median interval
 * @param reasons Why the score is below 1, one line per penalty
 * @since 0.5.0
 */
public record DataQuality(
    int sampleCount,
    double timeSpanSeconds,
    int gapCount,
    double maxGapMs,
    double totalGapMs,
    int nanFiltered,
    double effectiveSampleRateHz,
    double qualityScore,
    Sampling sampling,
    double jitterRatio,
    List<String> reasons) {

  /** How a series was sampled. */
  public enum Sampling {
    PERIODIC, CHANGE_ONLY, EVENT;

    public String wire() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  public DataQuality {
    Objects.requireNonNull(sampling);
    reasons = List.copyOf(reasons);
  }

  /** A periodic series' metrics without classification details (for tests and callers). */
  public DataQuality(int sampleCount, double timeSpanSeconds, int gapCount, double maxGapMs,
      double totalGapMs, int nanFiltered, double effectiveSampleRateHz, double qualityScore) {
    this(sampleCount, timeSpanSeconds, gapCount, maxGapMs, totalGapMs, nanFiltered,
        effectiveSampleRateHz, qualityScore, Sampling.PERIODIC, 0, List.of());
  }

  /** Fraction of the intervals within half a median interval of the median for "periodic". */
  static final double PERIODIC_FRACTION = 0.8;

  /**
   * Computes data quality metrics from a list of timestamped values.
   *
   * <p>A "gap" is defined as a timestamp interval exceeding 5x the median sample interval.
   * This adapts to any sample rate without hardcoded thresholds.
   *
   * @param values The timestamped values (must be sorted by timestamp)
   * @return The computed data quality metrics
   */
  public static DataQuality fromValues(List<TimestampedValue> values) {
    return fromSegments(values == null ? List.of() : List.of(values));
  }

  /**
   * Computes data quality over several time windows (a scope such as "enabled"): the time between
   * windows is not a data gap, so intervals are taken only within each window, and the time span
   * is the sum of the windows' spans.
   *
   * @param segments The values in each window, each sorted by timestamp
   * @return The computed data quality metrics
   * @since 0.9.0
   */
  public static DataQuality fromSegments(List<List<TimestampedValue>> segments) {
    int n = segments.stream().mapToInt(List::size).sum();
    if (n == 0) {
      return new DataQuality(0, 0, 0, 0, 0, 0, 0, 0, Sampling.EVENT, 0,
          List.of("no samples"));
    }

    double timeSpan = 0;
    var intervalList = new ArrayList<Double>();
    int nanCount = 0;
    int pairs = 0;
    int equalPairs = 0;
    for (var values : segments) {
      if (values.isEmpty()) continue;
      timeSpan += values.get(values.size() - 1).timestamp() - values.get(0).timestamp();
      for (int i = 0; i < values.size(); i++) {
        // Count NaN/Infinity
        if (values.get(i).value() instanceof Number num && !Double.isFinite(num.doubleValue())) {
          nanCount++;
        }
        // Values sharing a timestamp (elements of one record) carry no timing information
        if (i > 0 && values.get(i).timestamp() > values.get(i - 1).timestamp()) {
          intervalList.add(values.get(i).timestamp() - values.get(i - 1).timestamp());
          pairs++;
          if (Objects.equals(values.get(i).value(), values.get(i - 1).value())
              || (values.get(i).value() instanceof double[] a
                  && values.get(i - 1).value() instanceof double[] b && Arrays.equals(a, b))) {
            equalPairs++;
          }
        }
      }
    }

    var reasons = new ArrayList<String>();
    if (intervalList.isEmpty()) {
      reasons.add("one sample per window: no timing information");
      return new DataQuality(n, timeSpan, 0, 0, 0, nanCount, 0, 0.4, Sampling.EVENT, 0, reasons);
    }

    double[] intervals = intervalList.stream().mapToDouble(Double::doubleValue).toArray();
    double medianDt = median(intervals.clone());
    double sampleRate = medianDt > 0 ? 1.0 / medianDt : 0;

    // Sampling pattern: regular timing, or values that are only ever logged when they change
    int regular = 0;
    double[] deviations = new double[intervals.length];
    for (int i = 0; i < intervals.length; i++) {
      deviations[i] = Math.abs(intervals[i] - medianDt);
      if (deviations[i] <= 0.5 * medianDt) regular++;
    }
    double regularFraction = (double) regular / intervals.length;
    Sampling sampling;
    if (n >= 3 && regularFraction >= PERIODIC_FRACTION) {
      sampling = Sampling.PERIODIC;
    } else if (n >= 3 && equalPairs <= pairs * 0.01) {
      sampling = Sampling.CHANGE_ONLY;
    } else {
      sampling = Sampling.EVENT;
    }
    double jitterRatio = medianDt > 0 ? median(deviations) / medianDt : 0;

    // Gaps: intervals > 5x median. At 50 Hz (20 ms median) this flags gaps > 100 ms, tolerating
    // CAN congestion and network hiccups; only a periodic series is expected to have none.
    double gapThreshold = medianDt * 5.0;
    int gapCount = 0;
    double maxGap = 0;
    double totalGap = 0;
    for (double dt : intervals) {
      if (dt > gapThreshold) {
        gapCount++;
        maxGap = Math.max(maxGap, dt);
        totalGap += dt;
      }
    }

    double score = 1.0;
    // Long intervals weigh by the time they cover, not their number. For a change-only series
    // they are holds rather than missing data, but statistics weigh samples, not time, so a
    // statistic under-represents long holds either way. Events are irregular by nature.
    if (sampling != Sampling.EVENT) {
      double gapFraction = timeSpan > 0 ? totalGap / timeSpan : 0;
      double gapPenalty = 0.3 * Math.min(gapFraction / 0.2, 1.0);
      score -= gapPenalty;
      if (gapPenalty >= 0.005) {
        reasons.add(sampling == Sampling.PERIODIC
            ? String.format(java.util.Locale.ROOT, "%.1f%% of the time span is in %d %s "
                + "longer than 5x the median interval (longest %.0f ms)", 100 * gapFraction,
                gapCount, gapCount == 1 ? "gap" : "gaps", maxGap * 1000)
            : String.format(java.util.Locale.ROOT, "%.1f%% of the time span is in %d %s "
                + "longer than 5x the median with no new value (longest %.0f ms): the value "
                + "held or was not logged, and statistics weigh samples, not time",
                100 * gapFraction, gapCount, gapCount == 1 ? "interval" : "intervals",
                maxGap * 1000));
      }
    }
    if (sampling == Sampling.PERIODIC) {
      double jitterPenalty = 0.2 * Math.min(jitterRatio / 0.5, 1.0);
      score -= jitterPenalty;
      if (jitterPenalty >= 0.005) {
        reasons.add(String.format(java.util.Locale.ROOT, "irregular timing: intervals deviate "
            + "from the %.1f ms median by %.0f%% (median absolute deviation)", medianDt * 1000,
            100 * jitterRatio));
      }
    }
    if (nanCount > 0) {
      score -= 0.2 * Math.min((double) nanCount / n, 1.0);
      reasons.add(nanCount + " of " + n + " values are NaN or infinite");
    }
    // Sample size is about the values a statistic can use
    int finite = n - nanCount;
    if (finite == 0) {
      score = 0;
      reasons.add("no finite values");
    } else if (finite < 100) {
      score -= 0.3;
      reasons.add("only " + finite + " finite samples (fewer than 100)");
    } else if (finite < 500) {
      score -= 0.15;
      reasons.add("only " + finite + " finite samples (fewer than 500)");
    }
    score = Math.max(0.0, Math.min(1.0, score));

    return new DataQuality(n, timeSpan, gapCount, maxGap * 1000.0, totalGap * 1000.0, nanCount,
        sampleRate, score, sampling, jitterRatio, reasons);
  }

  private static double median(double[] values) {
    Arrays.sort(values);
    int mid = values.length / 2;
    return values.length % 2 == 0 ? (values[mid - 1] + values[mid]) / 2.0 : values[mid];
  }

  /**
   * Serializes the data quality metrics to a JSON object.
   *
   * @return A JsonObject with all quality fields
   */
  public JsonObject toJson() {
    var json = new JsonObject();
    json.addProperty("sample_count", sampleCount);
    json.addProperty("time_span_seconds", Math.round(timeSpanSeconds * 100.0) / 100.0);
    json.addProperty("sampling", sampling.wire());
    json.addProperty("gap_count", gapCount);
    if (gapCount > 0) {
      json.addProperty("max_gap_ms", Math.round(maxGapMs * 10.0) / 10.0);
    }
    if (nanFiltered > 0) {
      json.addProperty("nan_filtered", nanFiltered);
    }
    json.addProperty("effective_sample_rate_hz",
        Math.round(effectiveSampleRateHz * 10.0) / 10.0);
    json.addProperty("quality_score", Math.round(qualityScore * 100.0) / 100.0);
    if (!reasons.isEmpty()) {
      var array = new JsonArray();
      reasons.forEach(array::add);
      json.add("reasons", array);
    }
    return json;
  }

  /**
   * Returns a human-readable confidence level derived from the quality score.
   *
   * @return "high" (&gt;0.8), "medium" (0.5-0.8), "low" (0.2-0.5), or "insufficient" (&le;0.2)
   */
  public String confidenceLevel() {
    // Cap at "medium" if more than 10% of the time span is in long intervals, regardless of the
    // composite score. Duration-based, so one long gap is flagged even when the count is low;
    // event series are expected to have long intervals.
    boolean highGapRatio = sampling != Sampling.EVENT && timeSpanSeconds > 0
        && totalGapMs / 1000.0 / timeSpanSeconds > 0.10;
    if (qualityScore > 0.8 && !highGapRatio) return "high";
    if (qualityScore > 0.5) return "medium";
    if (qualityScore > 0.2) return "low";
    return "insufficient";
  }
}
