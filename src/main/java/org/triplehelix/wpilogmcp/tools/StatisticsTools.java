package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * Statistical analysis tools for WPILOG data.
 *
 * <p>Provides tools for computing statistics, comparing entries, detecting anomalies,
 * finding peaks, computing rates of change, and correlating time series data.
 *
 * <p>Tools included:
 * <ul>
 *   <li>{@code get_statistics} - Compute min, max, mean, median, std_dev for numeric entries</li>
 *   <li>{@code compare_entries} - Compare two numeric entries using RMSE and max difference</li>
 *   <li>{@code detect_anomalies} - Find outliers using the IQR method</li>
 *   <li>{@code find_peaks} - Find local maxima and minima in numeric data</li>
 *   <li>{@code rate_of_change} - Compute derivative of numeric data over time</li>
 *   <li>{@code time_correlate} - Compute Pearson correlation between two entries</li>
 * </ul>
 */
public final class StatisticsTools {

  private StatisticsTools() {}

  /**
   * Registers all statistics tools with the MCP server.
   *
   * @param server The MCP server to register tools with
   */
  public static void registerAll(ToolRegistry registry) {
    registry.registerTool(new GetStatisticsTool());
    registry.registerTool(new CompareEntriesTool());
    registry.registerTool(new DetectAnomaliesTool());
    registry.registerTool(new FindPeaksTool());
    registry.registerTool(new RateOfChangeTool());
    registry.registerTool(new TimeCorrelateTool());
  }

  /** Delegate to shared percentile implementation in ToolUtils. */
  private static double percentile(double[] sortedData, double p) {
    return ToolUtils.percentile(sortedData, p);
  }

  /**
   * Tool for computing descriptive statistics on numeric log entries.
   *
   * <p>Computes min, max, mean, median, and standard deviation for any numeric entry.
   * Supports optional time range filtering.
   */
  static class GetStatisticsTool extends LogRequiringTool {
    @Override
    public String name() { return "get_statistics"; }

    @Override
    public String description() {
      return "BUILT-IN statistics: Get min, max, mean, median, std_dev, percentiles for a numeric entry. "
          + "NEVER compute these manually—always use this tool! "
          + "Supports optional time range filtering (start_time, end_time). "
          + "Includes data quality metrics and sample size for confidence assessment."
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "The entry name", true)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var name = getRequiredString(arguments, "name");
      var start = getOptDouble(arguments, "start_time");
      var end = getOptDouble(arguments, "end_time");

      var values = requireEntry(log, name);
      var filtered = filterTimeRange(values, start, end);
      var numericFiltered = filtered.stream()
          .filter(tv -> tv.value() instanceof Number n && Double.isFinite(n.doubleValue()))
          .toList();
      var quality = DataQuality.fromValues(numericFiltered);
      var data = numericFiltered.stream()
          .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
          .toArray();

      if (data.length == 0) {
        throw new IllegalArgumentException("No numeric data in range");
      }

      var stats = java.util.Arrays.stream(data).summaryStatistics();
      java.util.Arrays.sort(data);
      double median = data.length % 2 == 1
          ? data[data.length / 2]
          : (data[data.length / 2 - 1] + data[data.length / 2]) / 2.0;

      double mean = stats.getAverage();
      // Use sample standard deviation (Bessel's correction: n-1) for more accurate estimates
      // from sample data. For n=1, return 0 to avoid division by zero.
      double sumSquaredDiff = java.util.Arrays.stream(data).map(v -> (v - mean) * (v - mean)).sum();
      double variance = data.length > 1 ? sumSquaredDiff / (data.length - 1) : 0.0;
      double stdDev = Math.sqrt(variance);

      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addFollowup("Use detect_anomalies to check for outliers that may skew these statistics")
          .addFollowup("Use time_correlate to check relationships with other entries");

      double q1 = percentile(data, 0.25);
      double q3 = percentile(data, 0.75);

      return success()
          .addProperty("name", name)
          .addProperty("count", stats.getCount())
          .addProperty("min", stats.getMin())
          .addProperty("max", stats.getMax())
          .addProperty("mean", mean)
          .addProperty("median", median)
          .addProperty("std_dev", stdDev)
          .addProperty("q1", q1)
          .addProperty("q3", q3)
          .addProperty("iqr", q3 - q1)
          .addProperty("p5", percentile(data, 0.05))
          .addProperty("p95", percentile(data, 0.95))
          .addDataQuality(quality)
          .addDirectives(directives)
          .build();
    }
  }

  static class CompareEntriesTool extends LogRequiringTool {
    @Override
    public String name() { return "compare_entries"; }

    @Override
    public String description() {
      return "Compare two numeric entries using RMSE and max difference."
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name1", "string", "First entry", true)
          .addProperty("name2", "string", "Second entry", true)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var n1 = getRequiredString(arguments, "name1");
      var n2 = getRequiredString(arguments, "name2");

      var v1 = log.values().get(n1);
      var v2 = log.values().get(n2);

      if (v1 == null || v2 == null) {
        throw new IllegalArgumentException("One or both entries not found");
      }

      double rmse = calculateRmseLinear(v1, v2);

      // Max diff calculation
      double maxDiff = 0.0;
      var reference = v1.size() >= v2.size() ? v1 : v2;
      var other = v1.size() >= v2.size() ? v2 : v1;
      for (var tv : reference) {
        var refValue = toDouble(tv.value());
        var otherValue = getValueAtTimeLinear(other, tv.timestamp());
        if (refValue != null && otherValue != null) {
          maxDiff = Math.max(maxDiff, Math.abs(refValue - otherValue));
        }
      }

      DataQuality q1 = DataQuality.fromValues(v1);
      DataQuality q2 = DataQuality.fromValues(v2);
      var quality = q1.qualityScore() <= q2.qualityScore() ? q1 : q2;
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addGuidance("RMSE is scale-dependent — compare to the entry's typical range for context")
          .addFollowup("Use get_statistics on each entry individually for baseline context");

      return success()
          .addProperty("rmse", rmse)
          .addProperty("max_difference", maxDiff)
          .addDataQuality(quality)
          .addDirectives(directives)
          .build();
    }
  }

  static class DetectAnomaliesTool extends LogRequiringTool {
    @Override
    public String name() { return "detect_anomalies"; }

    @Override
    public String description() {
      return "Detect anomalies (outliers) in numeric data using the IQR method. "
          + "Finds values that fall outside 1.5*IQR from Q1/Q3, or sudden spikes/drops."
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name", true)
          .addNumberProperty("iqr_multiplier", "IQR multiplier (default 1.5)", false, 1.5)
          .addNumberProperty("spike_threshold", "Spike percentage threshold", false, null)
          .addIntegerProperty("limit", "Max anomalies to return", false, 50)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var name = getRequiredString(arguments, "name");
      double iqrMult = getOptDouble(arguments, "iqr_multiplier", 1.5);
      int limit = getOptInt(arguments, "limit", 50);

      var values = requireEntry(log, name);

      var numeric = values.stream()
          .filter(tv -> tv.value() instanceof Number)
          .toList();
      if (numeric.size() < 4) {
        throw new IllegalArgumentException("Not enough data");
      }

      var sortedData = numeric.stream()
          .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
          .filter(Double::isFinite)
          .sorted()
          .toArray();

      if (sortedData.length < 4) {
        throw new IllegalArgumentException("Not enough finite data for IQR calculation");
      }

      double q1 = percentile(sortedData, 0.25);
      double q3 = percentile(sortedData, 0.75);
      double iqr = q3 - q1;
      double low = q1 - iqrMult * iqr;
      double high = q3 + iqrMult * iqr;

      long nonFiniteCount = numeric.stream()
          .filter(tv -> tv.value() instanceof Number && !Double.isFinite(((Number) tv.value()).doubleValue()))
          .count();

      var anomalies = new ArrayList<JsonObject>();
      for (var tv : numeric) {
        double v = ((Number) tv.value()).doubleValue();
        if (!Double.isFinite(v)) continue; // non-finite values counted separately
        if (v < low || v > high) {
          var obj = new JsonObject();
          obj.addProperty("timestamp_sec", tv.timestamp());
          obj.addProperty("value", v);
          obj.addProperty("type", v < low ? "below_lower_bound" : "above_upper_bound");
          anomalies.add(obj);
          if (anomalies.size() >= limit) break;
        }
      }

      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addFollowup("Use find_peaks if looking for signal extrema rather than statistical outliers");

      var builder = success()
          .addProperty("anomaly_count", anomalies.size())
          .addProperty("non_finite_count", nonFiniteCount)
          .addData("anomalies", GSON.toJsonTree(anomalies))
          .addDataQuality(quality)
          .addDirectives(directives);
      return builder.build();
    }
  }

  static class FindPeaksTool extends LogRequiringTool {
    @Override
    public String name() { return "find_peaks"; }

    @Override
    public String description() {
      return "Find local maxima and minima (peaks and valleys) in numeric data."
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name", true)
          .addProperty("type", "string", "Type: 'max', 'min', or 'both'", false)
          .addNumberProperty("min_height_diff", "Minimum height difference from neighbors to count as a peak. Filters out noise", false, null)
          .addIntegerProperty("limit", "Max peaks to return", false, 20)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var name = getRequiredString(arguments, "name");
      var peakType = getOptString(arguments, "type", "both");
      var minHeightDiff = getOptDouble(arguments, "min_height_diff");
      int limit = getOptInt(arguments, "limit", 20);

      var values = requireEntry(log, name);

      var data = values.stream()
          .filter(tv -> tv.value() instanceof Number n && Double.isFinite(n.doubleValue()))
          .map(tv -> new double[]{tv.timestamp(), ((Number) tv.value()).doubleValue()})
          .toList();

      if (data.size() < 3) {
        throw new IllegalArgumentException("Not enough data");
      }

      var maxima = new ArrayList<JsonObject>();
      var minima = new ArrayList<JsonObject>();

      for (int i = 1; i < data.size() - 1; i++) {
        double prev = data.get(i-1)[1], curr = data.get(i)[1], next = data.get(i+1)[1];
        boolean isMax = curr > prev && curr > next;
        boolean isMin = curr < prev && curr < next;

        if (isMax || isMin) {
          double heightDiff = Math.max(Math.abs(curr - prev), Math.abs(curr - next));
          if (minHeightDiff == null || heightDiff >= minHeightDiff) {
            var obj = new JsonObject();
            obj.addProperty("timestamp_sec", data.get(i)[0]);
            obj.addProperty("value", curr);
            obj.addProperty("height_diff", heightDiff);
            if (isMax) maxima.add(obj); else minima.add(obj);
          }
        }
      }

      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addFollowup("Use get_statistics to understand baseline before interpreting peaks");

      var builder = success();
      if (!"min".equals(peakType)) {
        builder.addData("maxima", GSON.toJsonTree(maxima.stream().limit(limit).toList()));
      }
      if (!"max".equals(peakType)) {
        builder.addData("minima", GSON.toJsonTree(minima.stream().limit(limit).toList()));
      }
      return builder.addDataQuality(quality).addDirectives(directives).build();
    }
  }

  static class RateOfChangeTool extends LogRequiringTool {
    @Override
    public String name() { return "rate_of_change"; }

    @Override
    public String description() {
      return "Compute rate of change (derivative) of numeric data over time."
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name", true)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addIntegerProperty("window_size", "Smoothing window (default 1)", false, 1)
          .addIntegerProperty("limit", "Max samples to return", false, 100)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var name = getRequiredString(arguments, "name");
      var start = getOptDouble(arguments, "start_time");
      var end = getOptDouble(arguments, "end_time");
      int window = getOptInt(arguments, "window_size", 1);
      int limit = getOptInt(arguments, "limit", 100);

      var values = requireEntry(log, name);

      var data = filterTimeRange(values, start, end).stream()
          .filter(tv -> tv.value() instanceof Number && Double.isFinite(((Number) tv.value()).doubleValue()))
          .map(tv -> new double[]{tv.timestamp(), ((Number) tv.value()).doubleValue()})
          .toList();

      if (data.size() < 2) {
        throw new IllegalArgumentException("Not enough data");
      }

      var samples = new ArrayList<JsonObject>();
      double sumRate = 0;
      int rateCount = 0;
      if (window == 1) {
        // Use central differences for interior points (more accurate, O(h^2) vs O(h))
        // Forward difference for first point, backward difference for last point
        for (int i = 0; i < data.size(); i++) {
          double rate;
          double timestamp;
          if (i == 0) {
            // Forward difference for first point
            double dt = data.get(1)[0] - data.get(0)[0];
            if (dt <= 0) continue;
            rate = (data.get(1)[1] - data.get(0)[1]) / dt;
            timestamp = data.get(0)[0];
          } else if (i == data.size() - 1) {
            // Backward difference for last point
            double dt = data.get(i)[0] - data.get(i - 1)[0];
            if (dt <= 0) continue;
            rate = (data.get(i)[1] - data.get(i - 1)[1]) / dt;
            timestamp = data.get(i)[0];
          } else {
            // Central difference for interior points
            double dt = data.get(i + 1)[0] - data.get(i - 1)[0];
            if (dt <= 0) continue;
            rate = (data.get(i + 1)[1] - data.get(i - 1)[1]) / dt;
            timestamp = data.get(i)[0];
          }
          if (!Double.isFinite(rate)) continue;
          sumRate += rate;
          rateCount++;
          if (samples.size() < limit) {
            var obj = new JsonObject();
            obj.addProperty("timestamp_sec", timestamp);
            obj.addProperty("rate", rate);
            samples.add(obj);
          }
        }
      } else {
        // Windowed forward difference (already smooths via averaging)
        for (int i = window; i < data.size(); i++) {
          double dt = data.get(i)[0] - data.get(i - window)[0];
          if (dt > 0) {
            double rate = (data.get(i)[1] - data.get(i - window)[1]) / dt;
            if (!Double.isFinite(rate)) continue;
            sumRate += rate;
            rateCount++;
            if (samples.size() < limit) {
              var obj = new JsonObject();
              obj.addProperty("timestamp_sec", data.get(i)[0]);
              obj.addProperty("rate", rate);
              samples.add(obj);
            }
          }
        }
      }

      var stats = new JsonObject();
      stats.addProperty("avg_rate", rateCount == 0 ? 0 : sumRate / rateCount);

      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addGuidance("Derivatives amplify noise — increase window_size for smoother results");

      return success()
          .addData("statistics", stats)
          .addData("samples", GSON.toJsonTree(samples))
          .addDataQuality(quality)
          .addDirectives(directives)
          .build();
    }
  }

  static class TimeCorrelateTool extends LogRequiringTool {
    @Override
    public String name() { return "time_correlate"; }

    @Override
    public String description() {
      return "BUILT-IN correlation: NEVER compute correlation manually—always use this tool! "
          + "Computes Pearson correlation coefficient with statistical significance (p-value). "
          + "Handles timestamp alignment automatically via linear interpolation. "
          + "Returns sample count for confidence assessment."
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL
          + " Correlation does not imply causation—consider confounding variables.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name1", "string", "First entry", true)
          .addProperty("name2", "string", "Second entry", true)
          .addNumberProperty("start_time", "Start time", false, null)
          .addNumberProperty("end_time", "End time", false, null)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var n1 = getRequiredString(arguments, "name1");
      var n2 = getRequiredString(arguments, "name2");
      var start = getOptDouble(arguments, "start_time");
      var end = getOptDouble(arguments, "end_time");

      var v1 = log.values().get(n1);
      var v2 = log.values().get(n2);
      if (v1 == null || v2 == null) {
        throw new IllegalArgumentException("Entries not found");
      }

      var d1 = filterTimeRange(v1, start, end).stream()
          .filter(tv -> tv.value() instanceof Number n && Double.isFinite(n.doubleValue()))
          .toList();
      var d2 = filterTimeRange(v2, start, end).stream()
          .filter(tv -> tv.value() instanceof Number n && Double.isFinite(n.doubleValue()))
          .toList();

      if (d1.isEmpty() || d2.isEmpty()) {
        throw new IllegalArgumentException("No numeric data");
      }

      // Estimate sample rates from timestamps to warn about aliasing risk
      double rate1 = d1.size() > 1
          ? (d1.size() - 1) / (d1.get(d1.size() - 1).timestamp() - d1.get(0).timestamp())
          : 0;
      double rate2 = d2.size() > 1
          ? (d2.size() - 1) / (d2.get(d2.size() - 1).timestamp() - d2.get(0).timestamp())
          : 0;

      var x = new ArrayList<Double>();
      var y = new ArrayList<Double>();
      for (var tv1 : d1) {
        var val2 = getValueAtTimeLinear(d2, tv1.timestamp());
        if (val2 != null) {
          x.add(((Number) tv1.value()).doubleValue());
          y.add(val2);
        }
      }

      if (x.size() < 2) {
        throw new IllegalArgumentException("Not enough overlapping data");
      }

      double meanX = x.stream().mapToDouble(v -> v).average().orElse(0);
      double meanY = y.stream().mapToDouble(v -> v).average().orElse(0);
      double num = 0, denX = 0, denY = 0;
      for (int i = 0; i < x.size(); i++) {
        double dx = x.get(i) - meanX, dy = y.get(i) - meanY;
        num += dx * dy; denX += dx * dx; denY += dy * dy;
      }

      var builder = success();

      if (x.size() < 30) {
        builder.addWarning("Correlation computed from only " + x.size()
            + " overlapping samples — insufficient for statistical significance. "
            + "Results may be misleading (with 2 points, correlation is always ±1.0).");
      }

      // Warn if sample rates differ significantly (>10x) - correlation may be
      // biased because the lower-rate signal is linearly interpolated, which
      // smooths high-frequency content and can inflate correlation.
      if (rate1 > 0 && rate2 > 0) {
        double rateRatio = Math.max(rate1, rate2) / Math.min(rate1, rate2);
        if (rateRatio > 10) {
          builder.addWarning(String.format(
              "Sample rate mismatch (%.1fHz vs %.1fHz, ratio %.0fx). "
              + "The lower-rate signal is linearly interpolated, which may bias correlation.",
              rate1, rate2, rateRatio));
        }
      }

      int sampleCount = x.size();
      builder.addProperty("sample_count", sampleCount);

      // Handle edge case: zero variance means correlation is undefined (NaN).
      // Use a relative threshold (variance = denX / n < 1e-15) to avoid
      // scale-dependent false positives with unnormalized sum-of-squares.
      double varianceThreshold = 1e-15 * x.size();
      if (denX < varianceThreshold || denY < varianceThreshold) {
        builder.addProperty("correlation", Double.NaN);
        builder.addProperty("p_value", 1.0);
        builder.addWarning("Correlation undefined: " +
            (denX < varianceThreshold && denY < varianceThreshold ? "both entries" : (denX < varianceThreshold ? "first entry" : "second entry")) +
            " has near-zero variance (all values are effectively identical)");
      } else {
        double corr = Math.max(-1.0, Math.min(1.0, num / Math.sqrt(denX * denY)));
        builder.addProperty("correlation", corr);
        double pValue = computePValue(corr, sampleCount);
        if (Double.isNaN(pValue)) {
          builder.addData("p_value", com.google.gson.JsonNull.INSTANCE);
          builder.addWarning(
              "P-value cannot be reliably computed for n < 15 (asymptotic approximation unreliable)");
        } else {
          builder.addProperty("p_value", pValue);
        }
      }

      var q1 = DataQuality.fromValues(v1);
      var q2 = DataQuality.fromValues(v2);
      var quality = q1.qualityScore() <= q2.qualityScore() ? q1 : q2;
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addGuidance("Correlation does not imply causation — consider confounding variables");

      return builder.addDataQuality(quality).addDirectives(directives).build();
    }
  }

  /**
   * Compute two-tailed p-value for Pearson correlation coefficient.
   *
   * <p>Uses the t-statistic t = r * sqrt((n-2) / (1 - r^2)) and approximates
   * the t-distribution CDF via the Abramowitz and Stegun formula 26.7.4
   * (complete Cornish-Fisher expansion with correction terms).
   *
   * @param r Pearson correlation coefficient
   * @param n Sample count
   * @return Two-tailed p-value
   */
  static double computePValue(double r, int n) {
    if (n <= 2) return 1.0;
    // For small samples, the Cornish-Fisher normal approximation is unreliable.
    // Return NaN to avoid understating p-values (overstating significance).
    if (n < 15) return Double.NaN;
    if (Math.abs(r) >= 1.0) return 0.0;
    double t = Math.abs(r) * Math.sqrt((n - 2.0) / (1.0 - r * r));
    double df = n - 2;
    // Abramowitz and Stegun formula 26.7.4 — Cornish-Fisher expansion with
    // higher-order correction terms for improved accuracy at moderate df.
    double a = df - 0.5;
    double b = 48.0 * a * a;
    double z2 = a * Math.log1p(t * t / df);
    double z = Math.sqrt(z2);
    // Full correction: first-order + second-order terms from A&S 26.7.5.
    // Coefficients (4, 33, 240, 855) are from Abramowitz & Stegun Table 26.7.4/26.7.5
    // and should be validated against the original reference if modifying this code.
    double z3 = z * z * z;
    z = z + (z3 + 3.0 * z) / b - (4.0 * z3 * z * z * z * z + 33.0 * z3 * z * z + 240.0 * z3 + 855.0 * z) / (10.0 * b * b);
    // Two-tailed p-value
    return 2.0 * (1.0 - normalCdf(z));
  }

  /**
   * Normal CDF approximation using Abramowitz and Stegun formula 26.2.17.
   * Full-precision polynomial coefficients for maximum accuracy (~7.5e-8).
   */
  private static double normalCdf(double z) {
    if (z < -8.0) return 0.0;
    if (z > 8.0) return 1.0;
    double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
    double d = 0.3989422804014327; // 1/sqrt(2*pi)
    double p = d * Math.exp(-z * z / 2.0) * t
        * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
    return z > 0 ? 1.0 - p : p;
  }
}
