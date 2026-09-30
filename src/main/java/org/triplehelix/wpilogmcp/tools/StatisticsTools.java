/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
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
      return "BUILT-IN statistics: Get min, max, mean, median, std_dev, percentiles for a numeric entry "
          + "or field. NEVER compute these manually—always use this tool! "
          + "Supports optional time range filtering (start_time, end_time). "
          + "Includes data quality metrics and sample size for confidence assessment."
          + NumericSignal.PATH_HELP + " A [*] path pools every element (count is values, "
          + "records_in_window is records). For an angle, min/max/mean/percentiles are of the "
          + "unwrapped angle within the window (so max - min is how far it turned) and angle "
          + "gives the circular mean and standard deviation and the number of wraps."
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "The entry name, optionally with a field path "
              + "(e.g. /RealOutputs/Drive/Pose.translation.x)", true)
          .addProperty("field", "string", NumericSignal.FIELD_PARAM, false)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var signal = signal(log, arguments, "name", "field", null);
      var name = signal.label();
      var start = getOptDouble(arguments, "start_time");
      var end = getOptDouble(arguments, "end_time");

      var values = signal.values();
      var filtered = filterTimeRange(values, start, end);
      var numericFiltered = filtered.stream()
          .filter(tv -> toDouble(tv.value()) != null && Double.isFinite(toDouble(tv.value())))
          .toList();
      // Angles: statistics of the continuous angle within the window (a [*] pool has no order)
      boolean unwrap = signal.isAngle() && !signal.multiValued();
      var measured = unwrap ? signal.unwrap(numericFiltered) : numericFiltered;
      var quality = DataQuality.fromValues(measured);
      var data = measured.stream()
          .mapToDouble(tv -> toDouble(tv.value()))
          .toArray();

      if (data.length == 0) {
        throw new IllegalArgumentException("No numeric data in range: no finite samples of " + name
            + (start != null || end != null ? " between " + (start != null ? start : "start")
                + " and " + (end != null ? end : "end") + " s" : "") + " (" + values.size()
            + " value(s) in the log, " + filtered.size() + " in the window"
            + (signal.recordsWithoutValue() > 0 ? "; " + signal.recordsWithoutValue() + " of "
                + signal.recordCount() + " records hold no value at " + signal.path() : "")
            + ")");
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

      var builder = success()
          .addProperty("name", name);
      if (!signal.path().isRoot()) builder.addProperty("field", signal.path().toString());
      if (signal.multiValued()) {
        builder.addProperty("records_in_window", measured.stream()
            .mapToDouble(tv -> tv.timestamp()).distinct().count());
      }
      if (signal.recordsWithoutValue() > 0) {
        builder.addProperty("records_without_value", signal.recordsWithoutValue());
      }
      if (signal.isAngle()) {
        var angle = NumericSignal.circularStatistics(
            numericFiltered.stream().mapToDouble(tv -> toDouble(tv.value())).toArray(),
            signal.angle());
        angle.addProperty("unit", signal.angle().wire());
        angle.addProperty("unwrapped", unwrap);
        if (unwrap) angle.addProperty("wraps", signal.wrapCount(numericFiltered));
        builder.addData("angle", angle);
      }
      return builder
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
          .addInputSignal("entry", signal)
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
      return "Compare two numeric entries or fields: RMSE and maximum absolute difference, "
          + "evaluated at the denser signal's timestamps with the other linearly interpolated (no "
          + "extrapolation), plus the number of compared samples. Two angles (e.g. a pose "
          + "heading and a gyro's Rotation2d) are compared by their shortest angular difference, "
          + "in the first one's unit." + NumericSignal.PATH_HELP
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name1", "string", "First entry (optionally with a field path)", true)
          .addProperty("name2", "string", "Second entry (optionally with a field path)", true)
          .addProperty("field1", "string", NumericSignal.FIELD_PARAM + ", for name1", false)
          .addProperty("field2", "string", NumericSignal.FIELD_PARAM + ", for name2", false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var s1 = signal(log, arguments, "name1", "field1", name());
      var s2 = signal(log, arguments, "name2", "field2", name());
      var n1 = s1.label();
      var n2 = s2.label();
      boolean angles = s1.isAngle() && s2.isAngle();
      // Angles: continuous series (for interpolation) in the first signal's unit
      var v1 = angles ? s1.unwrap(s1.values()) : s1.values();
      var v2 = angles ? convertAngles(s2.unwrap(s2.values()), s2.angle(), s1.angle())
          : s2.values();

      // Evaluate at the denser entry's timestamps, interpolating the other (no extrapolation)
      var reference = v1.size() >= v2.size() ? v1 : v2;
      var other = v1.size() >= v2.size() ? v2 : v1;
      double sumSq = 0.0;
      double maxDiff = 0.0;
      int compared = 0;
      for (var tv : reference) {
        var refValue = toDouble(tv.value());
        var otherValue = getValueAtTimeLinear(other, tv.timestamp());
        if (refValue == null || otherValue == null
            || !Double.isFinite(refValue) || !Double.isFinite(otherValue)) {
          continue;
        }
        double diff = angles ? NumericSignal.wrapToHalfTurn(refValue - otherValue,
            s1.angle().period) : refValue - otherValue;
        sumSq += diff * diff;
        maxDiff = Math.max(maxDiff, Math.abs(diff));
        compared++;
      }
      if (compared == 0) {
        throw new IllegalArgumentException("No overlapping samples: " + n1 + " spans "
            + span(v1) + " and " + n2 + " spans " + span(v2) + " (no extrapolation)");
      }
      double rmse = Math.sqrt(sumSq / compared);

      DataQuality q1 = DataQuality.fromValues(v1);
      DataQuality q2 = DataQuality.fromValues(v2);
      var quality = q1.qualityScore() <= q2.qualityScore() ? q1 : q2;
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addGuidance("RMSE is scale-dependent — compare to the entry's typical range for context")
          .addFollowup("Use get_statistics on each entry individually for baseline context");

      var builder = success()
          .addProperty("rmse", rmse)
          .addProperty("max_difference", maxDiff)
          .addProperty("samples_compared", compared)
          .addProperty("reference_entry", reference == v1 ? n1 : n2);
      if (angles) {
        builder.addProperty("angle_unit", s1.angle().wire())
            .addProperty("difference", "shortest angular difference");
      } else if (s1.isAngle() || s2.isAngle()) {
        builder.addWarning((s1.isAngle() ? n1 : n2) + " is an angle and "
            + (s1.isAngle() ? n2 : n1) + " is not; they were compared as plain numbers.");
      }
      return builder
          .addInputSignal("entry1", s1)
          .addInputSignal("entry2", s2)
          .addDataQuality(quality)
          .addDirectives(directives)
          .build();
    }

    /** Angle values converted from one unit to another. */
    static List<org.triplehelix.wpilogmcp.log.TimestampedValue> convertAngles(
        List<org.triplehelix.wpilogmcp.log.TimestampedValue> values, NumericSignal.AngleUnit from,
        NumericSignal.AngleUnit to) {
      if (from == to) return values;
      double factor = to.period / from.period;
      return values.stream().map(tv -> new org.triplehelix.wpilogmcp.log.TimestampedValue(
          tv.timestamp(), ((Number) tv.value()).doubleValue() * factor)).toList();
    }

    static String span(List<org.triplehelix.wpilogmcp.log.TimestampedValue> values) {
      if (values.isEmpty()) return "no samples";
      return String.format("%.3f-%.3f s", values.get(0).timestamp(),
          values.get(values.size() - 1).timestamp());
    }
  }

  /**
   * The numeric signal named by a tool's arguments: {@code nameKey} (an entry, or an entry with
   * a field path appended) and the optional {@code fieldKey}.
   *
   * @param singleValuedTool The tool's name when it needs one value per sample (no {@code [*]}),
   *     or null
   */
  static NumericSignal signal(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments,
      String nameKey, String fieldKey, String singleValuedTool) {
    var name = getRequiredString(arguments, nameKey);
    var field = getOptString(arguments, fieldKey, null);
    var signal = NumericSignal.resolve(log, name, field);
    return singleValuedTool == null ? signal : signal.requireSingleValued(singleValuedTool);
  }

  static class DetectAnomaliesTool extends LogRequiringTool {
    @Override
    public String name() { return "detect_anomalies"; }

    @Override
    public String description() {
      return "Detect anomalies in a numeric entry within an optional time window: outliers outside "
          + "iqr_multiplier x IQR beyond Q1/Q3 (Tukey fences), and, when spike_threshold is given, "
          + "spikes: sample-to-sample jumps larger than spike_threshold (in the entry's units). "
          + "anomaly_count is the true total; the list is sorted by time (default) or severity "
          + "(distance beyond the fence, or jump size) and cut at limit, with limits.anomalies "
          + "giving total and returned. Boot transients and disabled periods count unless the "
          + "window excludes them: take windows from get_match_phases." + NumericSignal.PATH_HELP
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name (optionally with a field path)", true)
          .addProperty("field", "string", NumericSignal.FIELD_PARAM, false)
          .addNumberProperty("iqr_multiplier", "IQR multiplier (default 1.5)", false, 1.5)
          .addNumberProperty("spike_threshold",
              "Flag sample-to-sample jumps larger than this, in the entry's units (off by default)", false, null)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addProperty("sort", "string", "'time' (default) or 'severity'", false)
          .addIntegerProperty("limit", "Max anomalies to return", false, 50)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var signal = signal(log, arguments, "name", "field", name());
      var name = signal.label();
      double iqrMult = getOptDouble(arguments, "iqr_multiplier", 1.5);
      var spikeThreshold = getOptDouble(arguments, "spike_threshold");
      var start = getOptDouble(arguments, "start_time");
      var end = getOptDouble(arguments, "end_time");
      var sort = getOptString(arguments, "sort", "time");
      if (!sort.equals("time") && !sort.equals("severity")) {
        throw new IllegalArgumentException("sort must be 'time' or 'severity'");
      }
      int limit = getOptInt(arguments, "limit", 50);
      validatePositive(limit, "limit");
      if (spikeThreshold != null && !(spikeThreshold > 0)) {
        throw new IllegalArgumentException("spike_threshold must be positive");
      }

      var inWindow = signal.unwrap(filterTimeRange(signal.values(), start, end));
      long nonFiniteCount = inWindow.stream()
          .filter(tv -> toDouble(tv.value()) != null && !Double.isFinite(toDouble(tv.value())))
          .count();
      var finite = inWindow.stream()
          .filter(tv -> toDouble(tv.value()) != null && Double.isFinite(toDouble(tv.value())))
          .toList();
      if (finite.size() < 4) {
        throw new IllegalArgumentException("Not enough data for IQR calculation: "
            + finite.size() + " finite sample(s) in the window (need 4)");
      }
      var sortedData = finite.stream().mapToDouble(tv -> toDouble(tv.value())).sorted().toArray();
      double q1 = percentile(sortedData, 0.25);
      double q3 = percentile(sortedData, 0.75);
      double iqr = q3 - q1;
      double low = q1 - iqrMult * iqr;
      double high = q3 + iqrMult * iqr;

      var anomalies = new ArrayList<JsonObject>();
      long outliers = 0;
      long spikes = 0;
      Double previous = null;
      for (var tv : finite) {
        double v = toDouble(tv.value());
        if (v < low || v > high) {
          var obj = new JsonObject();
          obj.addProperty("timestamp_sec", tv.timestamp());
          obj.addProperty("value", v);
          obj.addProperty("type", v < low ? "below_lower_bound" : "above_upper_bound");
          obj.addProperty("severity", v < low ? low - v : v - high);
          anomalies.add(obj);
          outliers++;
        }
        if (spikeThreshold != null && previous != null && Math.abs(v - previous) > spikeThreshold) {
          var obj = new JsonObject();
          obj.addProperty("timestamp_sec", tv.timestamp());
          obj.addProperty("value", v);
          obj.addProperty("type", v > previous ? "spike_up" : "spike_down");
          obj.addProperty("jump", v - previous);
          obj.addProperty("severity", Math.abs(v - previous));
          anomalies.add(obj);
          spikes++;
        }
        previous = v;
      }
      if (sort.equals("severity")) {
        anomalies.sort(java.util.Comparator.comparingDouble(
            (JsonObject a) -> -a.get("severity").getAsDouble()));
      }
      var list = new com.google.gson.JsonArray();
      anomalies.stream().limit(limit).forEach(list::add);

      var bounds = new JsonObject();
      bounds.addProperty("q1", q1);
      bounds.addProperty("q3", q3);
      bounds.addProperty("iqr", iqr);
      bounds.addProperty("lower", low);
      bounds.addProperty("upper", high);

      var quality = DataQuality.fromValues(inWindow);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addFollowup("Use find_peaks if looking for signal extrema rather than statistical outliers");

      var builder = success()
          .addProperty("anomaly_count", anomalies.size())
          .addProperty("outlier_count", outliers)
          .addProperty("non_finite_count", nonFiniteCount)
          .addData("bounds", bounds)
          .addProperty("samples_analyzed", finite.size())
          .addProperty("sort", sort)
          .addLimitedList("anomalies", list, anomalies.size(), limit);
      if (spikeThreshold != null) builder.addProperty("spike_count", spikes);
      if (signal.isAngle()) builder.addProperty("angle_unit", signal.angle().wire());
      if (start != null || end != null) builder.addInputWindow(start, end);
      return builder.addProperty("name", name).addInputSignal("entry", signal)
          .addDataQuality(quality).addDirectives(directives).build();
    }
  }

  static class FindPeaksTool extends LogRequiringTool {
    @Override
    public String name() { return "find_peaks"; }

    @Override
    public String description() {
      return "Find local maxima and minima (peaks and valleys) in numeric data, in time order. "
          + "maxima_count and minima_count are the true totals; each list is cut at limit "
          + "(limits gives total and returned). A flat signal has none." + NumericSignal.PATH_HELP
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name (optionally with a field path)", true)
          .addProperty("field", "string", NumericSignal.FIELD_PARAM, false)
          .addProperty("type", "string", "Type: 'max', 'min', or 'both'", false)
          .addNumberProperty("min_height_diff", "Minimum height difference from neighbors to count as a peak. Filters out noise", false, null)
          .addIntegerProperty("limit", "Max peaks to return", false, 20)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var signal = signal(log, arguments, "name", "field", name());
      var name = signal.label();
      var peakType = getOptString(arguments, "type", "both");
      var minHeightDiff = getOptDouble(arguments, "min_height_diff");
      int limit = getOptInt(arguments, "limit", 20);

      var values = signal.unwrap(signal.values());

      var data = values.stream()
          .filter(tv -> toDouble(tv.value()) != null && Double.isFinite(toDouble(tv.value())))
          .map(tv -> new double[]{tv.timestamp(), toDouble(tv.value())})
          .toList();

      if (data.size() < 3) {
        throw new IllegalArgumentException("Not enough data: " + data.size()
            + " finite sample(s), need 3");
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

      var builder = success().addProperty("name", name).addInputSignal("entry", signal);
      if (signal.isAngle()) builder.addProperty("angle_unit", signal.angle().wire());
      if (!"min".equals(peakType)) {
        var list = new com.google.gson.JsonArray();
        maxima.stream().limit(limit).forEach(list::add);
        builder.addProperty("maxima_count", maxima.size())
            .addLimitedList("maxima", list, maxima.size(), limit);
      }
      if (!"max".equals(peakType)) {
        var list = new com.google.gson.JsonArray();
        minima.stream().limit(limit).forEach(list::add);
        builder.addProperty("minima_count", minima.size())
            .addLimitedList("minima", list, minima.size(), limit);
      }
      return builder.addDataQuality(quality).addDirectives(directives).build();
    }
  }

  static class RateOfChangeTool extends LogRequiringTool {
    @Override
    public String name() { return "rate_of_change"; }

    @Override
    public String description() {
      return "Compute rate of change (derivative) of numeric data over time, in the signal's "
          + "units per second." + NumericSignal.PATH_HELP
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name (optionally with a field path)", true)
          .addProperty("field", "string", NumericSignal.FIELD_PARAM, false)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addIntegerProperty("window_size", "Smoothing window (default 1)", false, 1)
          .addIntegerProperty("limit", "Max samples to return", false, 100)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var signal = signal(log, arguments, "name", "field", name());
      var start = getOptDouble(arguments, "start_time");
      var end = getOptDouble(arguments, "end_time");
      int window = getOptInt(arguments, "window_size", 1);
      int limit = getOptInt(arguments, "limit", 100);

      var values = signal.values();

      var data = signal.unwrap(filterTimeRange(values, start, end)).stream()
          .filter(tv -> toDouble(tv.value()) != null && Double.isFinite(toDouble(tv.value())))
          .map(tv -> new double[]{tv.timestamp(), toDouble(tv.value())})
          .toList();

      if (data.size() < 2) {
        throw new IllegalArgumentException("Not enough data: " + data.size()
            + " finite sample(s) in the window, need 2");
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
      stats.addProperty("rate_count", rateCount);

      var quality = DataQuality.fromValues(filterTimeRange(values, start, end));
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addGuidance("Derivatives amplify noise — increase window_size for smoother results");

      var sampleList = new com.google.gson.JsonArray();
      samples.forEach(sampleList::add);
      var builder = success()
          .addProperty("name", signal.label())
          .addData("statistics", stats)
          .addLimitedList("samples", sampleList, rateCount, limit)
          .addInputSignal("entry", signal);
      if (signal.isAngle()) builder.addProperty("angle_unit", signal.angle().wire());
      if (start != null || end != null) builder.addInputWindow(start, end);
      return builder.addDataQuality(quality).addDirectives(directives).build();
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
          + "Returns sample count for confidence assessment." + NumericSignal.PATH_HELP
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL
          + " Correlation does not imply causation—consider confounding variables.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name1", "string", "First entry (optionally with a field path)", true)
          .addProperty("name2", "string", "Second entry (optionally with a field path)", true)
          .addProperty("field1", "string", NumericSignal.FIELD_PARAM + ", for name1", false)
          .addProperty("field2", "string", NumericSignal.FIELD_PARAM + ", for name2", false)
          .addNumberProperty("start_time", "Start time", false, null)
          .addNumberProperty("end_time", "End time", false, null)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var s1 = signal(log, arguments, "name1", "field1", name());
      var s2 = signal(log, arguments, "name2", "field2", name());
      var n1 = s1.label();
      var n2 = s2.label();
      var start = getOptDouble(arguments, "start_time");
      var end = getOptDouble(arguments, "end_time");

      var v1 = s1.values();
      var v2 = s2.values();

      var d1 = s1.unwrap(filterTimeRange(v1, start, end)).stream()
          .filter(tv -> toDouble(tv.value()) != null && Double.isFinite(toDouble(tv.value())))
          .map(tv -> new org.triplehelix.wpilogmcp.log.TimestampedValue(tv.timestamp(),
              toDouble(tv.value())))
          .toList();
      var d2 = s2.unwrap(filterTimeRange(v2, start, end)).stream()
          .filter(tv -> toDouble(tv.value()) != null && Double.isFinite(toDouble(tv.value())))
          .map(tv -> new org.triplehelix.wpilogmcp.log.TimestampedValue(tv.timestamp(),
              toDouble(tv.value())))
          .toList();

      if (d1.isEmpty() || d2.isEmpty()) {
        throw new IllegalArgumentException("No finite samples in the window for "
            + (d1.isEmpty() ? n1 : n2));
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
      builder.addProperty("sample_count", sampleCount)
          .addInputSignal("entry1", s1)
          .addInputSignal("entry2", s2);

      // Handle edge case: zero variance means correlation is undefined (NaN).
      // Use a relative threshold (variance = denX / n < 1e-15) to avoid
      // scale-dependent false positives with unnormalized sum-of-squares.
      double varianceThreshold = 1e-15 * x.size();
      if (denX < varianceThreshold || denY < varianceThreshold) {
        // Undefined, not zero: null (NaN is not valid JSON), with the reason in a warning
        builder.addData("correlation", com.google.gson.JsonNull.INSTANCE);
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
