package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonObject;
import java.util.List;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Computes and holds data quality metrics for a time series.
 *
 * <p>Used by analytical tools to attach quality metadata to responses, enabling
 * LLMs to calibrate their confidence when interpreting results.
 *
 * <p>Quality score formula (0.0 to 1.0):
 * <ul>
 *   <li>-0.3 for data gaps (scaled by gap count / 20)</li>
 *   <li>-0.2 for NaN/Infinity values (scaled by ratio to total)</li>
 *   <li>-0.3 for low sample count (&lt;100 = full penalty, &lt;500 = half)</li>
 *   <li>-0.2 for timing jitter (scaled by jitter / median dt)</li>
 * </ul>
 *
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
    double qualityScore) {

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
    if (values == null || values.isEmpty()) {
      return new DataQuality(0, 0, 0, 0, 0, 0, 0, 0);
    }

    int n = values.size();
    double firstTime = values.get(0).timestamp();
    double lastTime = values.get(n - 1).timestamp();
    double timeSpan = lastTime - firstTime;

    // Count NaN/Infinity
    int nanCount = 0;
    for (var tv : values) {
      if (tv.value() instanceof Number num) {
        double v = num.doubleValue();
        if (!Double.isFinite(v)) nanCount++;
      }
    }

    // Compute sample intervals for gap and jitter detection
    if (n < 2) {
      double score = n == 0 ? 0.0 : 0.4; // Single sample = low confidence
      return new DataQuality(n, timeSpan, 0, 0, 0, nanCount, 0, score);
    }

    double[] intervals = new double[n - 1];
    for (int i = 0; i < n - 1; i++) {
      intervals[i] = values.get(i + 1).timestamp() - values.get(i).timestamp();
    }

    // Median interval (for adaptive gap threshold)
    double[] sorted = intervals.clone();
    java.util.Arrays.sort(sorted);
    int mid = sorted.length / 2;
    double medianDt = (sorted.length % 2 == 0)
        ? (sorted[mid - 1] + sorted[mid]) / 2.0
        : sorted[mid];

    // Effective sample rate
    double sampleRate = medianDt > 0 ? 1.0 / medianDt : 0;

    // Gap detection: intervals > 5x median.
    // At 50Hz (20ms median), this flags gaps > 100ms, tolerating typical CAN bus
    // congestion and network hiccups while catching genuine data interruptions.
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

    // Jitter: sample standard deviation of intervals around median (Bessel's correction: n-1)
    // Using medianDt as center is more robust to outlier gaps than mean.
    double sumSq = 0;
    for (double dt : intervals) {
      double diff = dt - medianDt;
      sumSq += diff * diff;
    }
    double jitter = intervals.length > 1 ? Math.sqrt(sumSq / (intervals.length - 1)) : 0;

    // Quality score: composite 0.0–1.0 with four penalty components.
    // Weights sum to 1.0 so each component has a bounded maximum penalty.
    //
    //   Gaps (0.3):   20+ gaps = full penalty. Detects data interruptions.
    //   NaN  (0.2):   Ratio of non-finite values. Detects sensor dropouts.
    //   Samples (0.3): <100 = full penalty, <500 = half. Statistical confidence.
    //   Jitter (0.2):  Timing irregularity relative to median interval.
    //
    // These weights are empirical defaults calibrated for typical 50Hz FRC telemetry.
    // For signals at significantly different rates (e.g., 1kHz CAN data or 5Hz slow
    // sensors), the sample-count thresholds (100/500) may under- or over-penalize.
    // The score provides relative ranking — use for LLM confidence calibration,
    // not as an absolute data validity threshold.
    double score = 1.0;
    score -= 0.3 * Math.min(gapCount / 20.0, 1.0);
    score -= 0.2 * (n > 0 ? Math.min((double) nanCount / n, 1.0) : 0);
    score -= 0.3 * (n < 100 ? 1.0 : (n < 500 ? 0.5 : 0.0));
    score -= 0.2 * (medianDt > 0 ? Math.min(jitter / medianDt, 1.0) : 0);
    score = Math.max(0.0, Math.min(1.0, score));

    return new DataQuality(
        n,
        timeSpan,
        gapCount,
        maxGap * 1000.0, // convert to ms
        totalGap * 1000.0, // convert to ms
        nanCount,
        sampleRate,
        score);
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
    return json;
  }

  /**
   * Returns a human-readable confidence level derived from the quality score.
   *
   * @return "high" (&gt;0.8), "medium" (0.5-0.8), "low" (0.2-0.5), or "insufficient" (&le;0.2)
   */
  public String confidenceLevel() {
    // Cap at "medium" if gap duration exceeds 10% of total time, regardless of composite score.
    // Uses duration-based ratio (not count-based) so that one long gap is correctly flagged
    // even when the count-to-sample ratio is low.
    boolean highGapRatio = timeSpanSeconds > 0 && totalGapMs / 1000.0 / timeSpanSeconds > 0.10;
    if (qualityScore > 0.8 && !highGapRatio) return "high";
    if (qualityScore > 0.5) return "medium";
    if (qualityScore > 0.2) return "low";
    return "insufficient";
  }
}
