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
    return fromSegments(segments, false);
  }

  /** A provider declares sampling; changing values or adaptive periods must not imply change-only logging. */
  public static DataQuality fromSegments(org.triplehelix.wpilogmcp.log.LogData log, String entry,
      List<List<TimestampedValue>> segments) {
    var info = log.entries().get(entry);
    return fromSegments(segments, info != null && sampled(info.metadata()));
  }
  static boolean sampled(String metadata) {
    try {
      var value = com.google.gson.JsonParser.parseString(metadata).getAsJsonObject().get("sampled");
      return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    } catch (RuntimeException invalid) { return false; }
  }
  /**
   * As {@link #fromSegments(org.triplehelix.wpilogmcp.log.LogData, String, List)}, over samples by
   * position, so a column file's samples are scored without making a value of each; equal values
   * are told by their bits, which compare as the values' own {@code equals} does.
   */
  public static DataQuality fromSamples(org.triplehelix.wpilogmcp.log.LogData log, String entry,
      List<org.triplehelix.wpilogmcp.read.NumericSamples> segments) {
    var info = log.entries().get(entry);
    return fromSamples(segments, info != null && sampled(info.metadata()));
  }

  static DataQuality fromSamples(List<org.triplehelix.wpilogmcp.read.NumericSamples> segments, boolean sampled) {
    var accumulator = new Accumulator(sampled);
    for (var samples : segments) {
      accumulator.segment();
      for (int i = 0; i < samples.size(); i++) accumulator.add(samples.seconds(i), samples.number(i), samples.valueBits(i));
    }
    return accumulator.result();
  }

  static DataQuality fromSegments(List<List<TimestampedValue>> segments, boolean sampled) {
    var accumulator = new Accumulator(sampled);
    for (var values : segments) {
      accumulator.segment();
      for (var value : values) accumulator.add(value.timestamp(), value.value());
    }
    return accumulator.result();
  }

  /**
   * Scores samples seen one at a time, window by window, exactly as {@link #fromSegments} scores
   * lists. A caller that reads cursors instead of holding a history (compare_sessions) keeps only
   * the intervals the medians need and gets the score a tool holding the samples would get.
   */
  static final class Accumulator {
    private final boolean sampled;
    private int n;
    private int nanCount;
    private int pairs;
    private int equalPairs;
    private int count;
    private double timeSpan;
    private double first;
    private double last;
    private Object previous;
    private long previousBits;
    private boolean previousNumber;
    private boolean inSegment;
    private double[] intervals = new double[16];

    Accumulator(boolean sampled) {
      this.sampled = sampled;
    }

    /** Starts the next window: no interval spans the gap between two. */
    void segment() {
      close();
    }

    void add(double timestamp, Object value) {
      n++;
      // Count NaN/Infinity
      if (value instanceof Number num && !Double.isFinite(num.doubleValue())) nanCount++;
      if (!inSegment) {
        inSegment = true;
        first = timestamp;
      } else if (timestamp > last) {
        // Values sharing a timestamp (elements of one record) carry no timing information
        if (count == intervals.length) intervals = Arrays.copyOf(intervals, count * 2);
        intervals[count++] = timestamp - last;
        pairs++;
        if (Objects.equals(value, previous)
            || (value instanceof double[] a && previous instanceof double[] b && Arrays.equals(a, b))) {
          equalPairs++;
        }
      }
      last = timestamp;
      previous = value;
      previousNumber = false;
    }

    /** As {@link #add(double, Object)}, for a number whose equality its bits tell. */
    void add(double timestamp, double number, long bits) {
      n++;
      if (!Double.isFinite(number)) nanCount++;
      if (!inSegment) {
        inSegment = true;
        first = timestamp;
      } else if (timestamp > last) {
        if (count == intervals.length) intervals = Arrays.copyOf(intervals, count * 2);
        intervals[count++] = timestamp - last;
        pairs++;
        if (previousNumber && bits == previousBits) equalPairs++;
      }
      last = timestamp;
      previous = null;
      previousBits = bits;
      previousNumber = true;
    }

    private void close() {
      if (inSegment) timeSpan += last - first;
      inSegment = false;
      previous = null;
      previousNumber = false;
    }

    DataQuality result() {
      close();
      return score(n, timeSpan, Arrays.copyOf(intervals, count), nanCount, pairs, equalPairs, sampled);
    }
  }

  private static DataQuality score(int n, double timeSpan, double[] intervals, int nanCount,
      int pairs, int equalPairs, boolean sampled) {
    if (n == 0) {
      return new DataQuality(0, 0, 0, 0, 0, 0, 0, 0, sampled ? Sampling.PERIODIC : Sampling.EVENT, 0,
          List.of("no samples"));
    }

    var reasons = new ArrayList<String>();
    if (intervals.length == 0) {
      reasons.add("one sample per window: no timing information");
      return new DataQuality(n, timeSpan, 0, 0, 0, nanCount, 0, 0.4, sampled ? Sampling.PERIODIC : Sampling.EVENT, 0, reasons);
    }

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
    if (sampled || n >= 3 && regularFraction >= PERIODIC_FRACTION) {
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
