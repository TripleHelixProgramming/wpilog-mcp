/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
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
    registry.registerTool(new AlignEntriesTool());
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
          + "or field. NEVER compute these manually—always use this tool! std_dev is the sample "
          + "deviation (n-1), null for a single value. "
          + "Supports optional time range filtering (start_time, end_time). "
          + "No finite samples in the selected scope returns no_match with count 0 and a hint. "
          + "Includes data quality metrics and sample size for confidence assessment."
          + NumericSignal.PATH_HELP + " A [*] path pools every element (count is values, "
          + "records_in_window is records). For an angle, min/max/mean/percentiles are of the "
          + "unwrapped angle within the window (so max - min is how far it turned) and angle "
          + "gives the circular mean and standard deviation, and the number of wraps when the "
          + "signal is single-valued (a [*] pool has no order to unwrap)."
          + SCOPE_HELP + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "The entry name, optionally with a field path "
              + "(e.g. /RealOutputs/Drive/Pose.translation.x)", true)
          .addProperty("field", "string", NumericSignal.FIELD_PARAM, false)
          .addProperty("angle", "string", NumericSignal.ANGLE_PARAM, false)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(),
              false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var signal = signal(log, arguments, "name", "field", null);
      var name = signal.label();
      var scope = TimeScope.fromArguments(log, null, arguments);

      var samples = signal.samples();
      var segments = scope.split(samples);
      // Angles: statistics of the continuous angle within each window (a [*] pool has no order)
      boolean unwrap = signal.isAngle() && !signal.multiValued();
      var rawWindows = SampleWindows.finiteWindows(signal, segments, false);
      var measuredWindows = unwrap ? SampleWindows.finiteWindows(signal, segments, true) : rawWindows;
      // Quality is scored on every sample in scope, before non-finite values are dropped, so
      // NaN is counted and the timing classification is of the series as logged
      var quality = DataQuality.fromSamples(log, signal.entry(), segments);
      var data = SampleWindows.numbers(measuredWindows);

      if (data.length == 0) {
        // An empty or all-nonfinite window is data absence, not an invalid selector. In a live
        // view the next completed prefix can contain samples without any configuration change.
        return ResponseBuilder.noMatch("No numeric data in range: no finite samples of " + name
            + scopeText(scope) + " (" + samples.size() + " value(s) in the log, "
            + SampleWindows.count(segments) + " in scope"
            + (signal.recordsWithoutValue() > 0 ? "; " + signal.recordsWithoutValue() + " of "
                + signal.recordCount() + " records hold no value at " + signal.path() : "")
            + ")")
            .lookedFor(List.of(name)).hint("No finite samples in this scope; widen the interval or wait for new samples")
            .addProperty("name", name).addProperty("count", 0)
            .addInputSignal("entry", signal).addInputScope(scope).addDataQuality(quality).build();
      }

      // In time order, then sorted for the median and percentiles
      var summary = SummaryStatistics.of(data);
      java.util.Arrays.sort(data);

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
        builder.addProperty("records_in_window",
            java.util.stream.DoubleStream.of(SampleWindows.seconds(measuredWindows)).distinct().count());
      }
      if (signal.recordsWithoutValue() > 0) {
        builder.addProperty("records_without_value", signal.recordsWithoutValue());
      }
      if (signal.isAngle()) {
        var angle = NumericSignal.circularStatistics(SampleWindows.numbers(rawWindows), signal.angle());
        angle.addProperty("unit", signal.angle().wire());
        angle.addProperty("unwrapped", unwrap);
        if (unwrap) {
          angle.addProperty("wraps", rawWindows.stream().mapToInt(signal::wrapCount).sum());
        }
        builder.addData("angle", angle);
      }
      return builder
          .addProperty("count", summary.count())
          .addProperty("min", summary.min())
          .addProperty("max", summary.max())
          .addProperty("mean", summary.mean())
          .addProperty("median", SummaryStatistics.median(data))
          .addProperty("std_dev", summary.stdDev())
          .addProperty("q1", q1)
          .addProperty("q3", q3)
          .addProperty("iqr", q3 - q1)
          .addProperty("p5", percentile(data, 0.05))
          .addProperty("p95", percentile(data, 0.95))
          .addInputSignal("entry", signal)
          .addInputScope(scope)
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
          + "in the first one's unit. With a scope or window, only the reference signal's "
          + "samples inside it are compared." + NumericSignal.PATH_HELP + SCOPE_HELP
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name1", "string", "First entry (optionally with a field path)", true)
          .addProperty("name2", "string", "Second entry (optionally with a field path)", true)
          .addProperty("field1", "string", NumericSignal.FIELD_PARAM + ", for name1", false)
          .addProperty("field2", "string", NumericSignal.FIELD_PARAM + ", for name2", false)
          .addProperty("angle", "string", NumericSignal.ANGLE_PARAM + " (both signals)", false)
          .addNumberProperty("max_lag_sec", LAG_SCHEMA_MAX, false, null)
          .addNumberProperty("lag_step_sec", "Lag search step (default: the first signal's "
              + "median sample interval)", false, null)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(),
              false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var scope = TimeScope.fromArguments(log, null, arguments);
      var s1 = signal(log, arguments, "name1", "field1", name());
      var s2 = signal(log, arguments, "name2", "field2", name());
      var n1 = s1.label();
      var n2 = s2.label();
      boolean angles = s1.isAngle() && s2.isAngle();
      // Angles: continuous series (for interpolation) in the first signal's unit
      var v1 = angles ? s1.unwrap(s1.samples()) : s1.samples();
      var v2 = angles ? convertAngles(s2.unwrap(s2.samples()), s2.angle(), s1.angle())
          : s2.samples();

      // Evaluate at the denser entry's timestamps, interpolating the other (no extrapolation)
      var reference = v1.size() >= v2.size() ? v1 : v2;
      var other = v1.size() >= v2.size() ? v2 : v1;
      double sumSq = 0.0;
      double maxDiff = 0.0;
      int compared = 0;
      for (var window : scope.split(reference)) {
        for (int i = 0; i < window.size(); i++) {
          double refValue = window.number(i);
          var otherValue = SampleWindows.linearAt(other, window.seconds(i));
          if (otherValue == null || !Double.isFinite(refValue) || !Double.isFinite(otherValue)) continue;
          double diff = angles ? NumericSignal.wrapToHalfTurn(refValue - otherValue,
              s1.angle().period) : refValue - otherValue;
          sumSq += diff * diff;
          maxDiff = Math.max(maxDiff, Math.abs(diff));
          compared++;
        }
      }
      if (compared == 0) {
        throw new IllegalArgumentException("No overlapping samples: " + n1 + " spans "
            + span(v1) + " and " + n2 + " spans " + span(v2) + " (no extrapolation)"
            + scopeText(scope));
      }
      double rmse = Math.sqrt(sumSq / compared);
      JsonObject lagSearch = null;
      if (arguments.has("max_lag_sec") && !arguments.get("max_lag_sec").isJsonNull()) {
        var reference1 = SampleWindows.concat(scope.split(SampleWindows.finite(v1)));
        var lags = lagGrid(arguments, reference1);
        double bestRmse = Double.POSITIVE_INFINITY;
        double bestLag = Double.NaN;
        int bestN = 0;
        Double zeroRmse = null;
        for (double lag : lags) {
          double ss = 0;
          int n = 0;
          for (int i = 0; i < reference1.size(); i++) {
            var y = SampleWindows.linearAt(v2, reference1.seconds(i) + lag);
            if (y == null || !Double.isFinite(y)) continue;
            double x = reference1.number(i);
            double d = angles ? NumericSignal.wrapToHalfTurn(x - y, s1.angle().period) : x - y;
            ss += d * d;
            n++;
          }
          if (n == 0) continue;
          double r = Math.sqrt(ss / n);
          if (Math.abs(lag) < 1e-12) zeroRmse = r;
          if (r < bestRmse) {
            bestRmse = r;
            bestLag = lag;
            bestN = n;
          }
        }
        lagSearch = new JsonObject();
        lagSearch.addProperty("lags_evaluated", lags.length);
        lagSearch.addProperty("lag_step_sec", lags.length > 1 ? lags[1] - lags[0] : 0);
        if (!Double.isNaN(bestLag)) {
          lagSearch.addProperty("best_lag_sec", bestLag);
          lagSearch.addProperty("rmse_at_best_lag", bestRmse);
          lagSearch.addProperty("samples_at_best_lag", bestN);
          if (zeroRmse != null) lagSearch.addProperty("rmse_at_zero_lag", zeroRmse);
        }
        lagSearch.addProperty("note", "The first signal's samples are the reference here "
            + "(unlike the zero-lag rmse, which uses the denser signal); positive lag: the "
            + "second signal follows the first.");
      }

      DataQuality q1 = DataQuality.fromSamples(log, s1.entry(), scope.split(v1));
      DataQuality q2 = DataQuality.fromSamples(log, s2.entry(), scope.split(v2));
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
      if (lagSearch != null) builder.addData("lag_search", lagSearch);
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
          .addInputScope(scope)
          .addDataQuality(quality)
          .addDirectives(directives)
          .build();
    }

    /** Angle samples converted from one unit to another. */
    static org.triplehelix.wpilogmcp.read.NumericSamples convertAngles(org.triplehelix.wpilogmcp.read.NumericSamples values,
        NumericSignal.AngleUnit from, NumericSignal.AngleUnit to) {
      return from == to ? values : SampleWindows.scaled(values, to.period / from.period);
    }

    static String span(org.triplehelix.wpilogmcp.read.NumericSamples values) {
      if (values.size() == 0) return "no samples";
      return String.format("%.3f-%.3f s", values.seconds(0), values.seconds(values.size() - 1));
    }
  }

  /** How the numeric tools take their time scope, for descriptions. */
  static final String SCOPE_HELP = " Time: start_time/end_time, scope ('enabled', 'disabled', "
      + "'auto', 'teleop', 'segment:<i>'; from get_match_phases), and windows (e.g. the intervals "
      + "find_condition returns) combine; differences, peaks, and unwrapping stay within each "
      + "window, and data_quality does not count the time between windows as a gap.";

  /** Lags evaluated by a lag search, at most. */
  static final int MAX_LAGS = 401;

  /**
   * The lags a search evaluates: -max..+max in steps of {@code lag_step_sec}, or of the reference
   * signal's median sample interval; widened so at most {@value #MAX_LAGS} are evaluated.
   */
  /** The lags searched: the reference's median interval as the step, unless one is given. */
  static double[] lagGrid(JsonObject arguments, org.triplehelix.wpilogmcp.read.NumericSamples reference) {
    double maxLag = getOptDouble(arguments, "max_lag_sec");
    if (!(maxLag > 0) || !Double.isFinite(maxLag)) {
      throw new IllegalArgumentException("max_lag_sec must be a positive number of seconds");
    }
    var stepArg = getOptDouble(arguments, "lag_step_sec");
    double step;
    if (stepArg != null) {
      if (!(stepArg > 0) || !Double.isFinite(stepArg)) throw new IllegalArgumentException("lag_step_sec must be finite and positive");
      step = stepArg;
    } else {
      var dts = new double[Math.max(0, reference.size() - 1)];
      int positive = 0;
      for (int i = 1; i < reference.size(); i++) {
        double dt = reference.seconds(i) - reference.seconds(i - 1);
        if (dt > 0) dts[positive++] = dt;
      }
      java.util.Arrays.sort(dts, 0, positive);
      step = positive == 0 ? 0.02 : dts[positive / 2];
    }
    double requestedCount = Math.floor(maxLag / step + 1e-9);
    int count;
    if (requestedCount > (MAX_LAGS - 1) / 2) {
      count = (MAX_LAGS - 1) / 2;
      step = maxLag / count;
    } else count = (int) requestedCount;
    var lags = new double[2 * count + 1];
    for (int k = -count; k <= count; k++) lags[k + count] = k * step;
    return lags;
  }

  static final String LAG_SCHEMA_MAX = "Also search for the time shift that best aligns the two "
      + "signals, from -max_lag_sec to +max_lag_sec (a positive lag means the second signal "
      + "follows the first)";

  static final String CORRELATION_LAG_SCHEMA_MAX = "Also search for the time shift at which the "
      + "two signals correlate most strongly, positively or negatively, from -max_lag_sec to "
      + "+max_lag_sec (a positive lag means the second signal follows the first)";

  /** " in scope enabled (4 windows, 1052.3 s)" or " between 10 and 20 s", for messages. */
  static String scopeText(TimeScope scope) {
    if (!scope.isAll()) return " in " + scope.describe();
    var start = scope.requestedStart();
    var end = scope.requestedEnd();
    if (start == null && end == null) return "";
    return " between " + (start != null ? start : "start") + " and " + (end != null ? end : "end")
        + " s";
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
    var signal = NumericSignal.resolve(log, name, field).withAngleArgument(arguments);
    return singleValuedTool == null ? signal : signal.requireSingleValued(singleValuedTool);
  }

  static class DetectAnomaliesTool extends LogRequiringTool {
    @Override
    public String name() { return "detect_anomalies"; }

    @Override
    public String description() {
      return "Detect anomalies in a numeric entry within an optional time window: outliers outside "
          + "iqr_multiplier x IQR beyond Q1/Q3 (Tukey fences), and, when spike_threshold is given, "
          + "spikes: sample-to-sample jumps larger than spike_threshold (in the entry's units), "
          + "with spike_interval_sec, the time between consecutive spikes (median, p95; the "
          + "cadence of steps such as vision corrections). "
          + "anomaly_count is the true total; the list is sorted by time (default) or severity "
          + "(distance beyond the fence, or jump size) and cut at limit, with limits.anomalies "
          + "giving total and returned. Boot transients and disabled periods count unless the "
          + "window excludes them: pass scope 'enabled' or windows from get_match_phases."
          + NumericSignal.PATH_HELP + SCOPE_HELP + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name (optionally with a field path)", true)
          .addProperty("field", "string", NumericSignal.FIELD_PARAM, false)
          .addProperty("angle", "string", NumericSignal.ANGLE_PARAM, false)
          .addNumberProperty("iqr_multiplier", "IQR multiplier (default 1.5)", false, 1.5)
          .addNumberProperty("spike_threshold",
              "Flag sample-to-sample jumps larger than this, in the entry's units (off by default)", false, null)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(),
              false)
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
      var scope = TimeScope.fromArguments(log, null, arguments);
      var sort = getOptString(arguments, "sort", "time");
      if (!sort.equals("time") && !sort.equals("severity")) {
        throw new IllegalArgumentException("sort must be 'time' or 'severity'");
      }
      int limit = getOptInt(arguments, "limit", 50);
      validatePositive(limit, "limit");
      if (spikeThreshold != null && !(spikeThreshold > 0)) {
        throw new IllegalArgumentException("spike_threshold must be positive");
      }

      var segments = scope.split(signal.samples());
      long nonFiniteCount = 0;
      for (var segment : segments) {
        for (int i = 0; i < segment.size(); i++) if (!Double.isFinite(segment.number(i))) nonFiniteCount++;
      }
      var finiteWindows = SampleWindows.finiteWindows(signal, segments, true);
      int finiteCount = SampleWindows.count(finiteWindows);
      if (finiteCount < 4) {
        throw new IllegalArgumentException("Not enough data for IQR calculation: "
            + finiteCount + " finite sample(s)" + scopeText(scope) + " (need 4)");
      }
      var sortedData = SampleWindows.numbers(finiteWindows);
      java.util.Arrays.sort(sortedData);
      double q1 = percentile(sortedData, 0.25);
      double q3 = percentile(sortedData, 0.75);
      double iqr = q3 - q1;
      double low = q1 - iqrMult * iqr;
      double high = q3 + iqrMult * iqr;

      var anomalies = new ArrayList<JsonObject>();
      long outliers = 0;
      long spikes = 0;
      var spikeIntervals = new ArrayList<Double>(); // between consecutive spikes in one window
      for (var window : finiteWindows) {
        Double previous = null; // spikes are jumps within one window
        Double lastSpike = null;
        for (int i = 0; i < window.size(); i++) {
          double v = window.number(i);
          double time = window.seconds(i);
          if (v < low || v > high) {
            var obj = new JsonObject();
            obj.addProperty("timestamp_sec", time);
            obj.addProperty("value", v);
            obj.addProperty("type", v < low ? "below_lower_bound" : "above_upper_bound");
            obj.addProperty("severity", v < low ? low - v : v - high);
            anomalies.add(obj);
            outliers++;
          }
          if (spikeThreshold != null && previous != null && Math.abs(v - previous) > spikeThreshold) {
            var obj = new JsonObject();
            obj.addProperty("timestamp_sec", time);
            obj.addProperty("value", v);
            obj.addProperty("type", v > previous ? "spike_up" : "spike_down");
            obj.addProperty("jump", v - previous);
            obj.addProperty("severity", Math.abs(v - previous));
            anomalies.add(obj);
            spikes++;
            if (lastSpike != null) spikeIntervals.add(time - lastSpike);
            lastSpike = time;
          }
          previous = v;
        }
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

      var quality = DataQuality.fromSamples(log, signal.entry(), segments);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addFollowup("Use find_peaks if looking for signal extrema rather than statistical outliers");

      var builder = success()
          .addProperty("anomaly_count", anomalies.size())
          .addProperty("outlier_count", outliers)
          .addProperty("non_finite_count", nonFiniteCount)
          .addData("bounds", bounds)
          .addProperty("samples_analyzed", finiteCount)
          .addProperty("sort", sort)
          .addLimitedList("anomalies", list, anomalies.size(), limit);
      if (spikeThreshold != null) {
        builder.addProperty("spike_count", spikes);
        if (!spikeIntervals.isEmpty()) {
          // The cadence of steps (e.g. vision corrections arriving every ~100 ms)
          var sorted = spikeIntervals.stream().mapToDouble(Double::doubleValue).sorted().toArray();
          var intervals = new JsonObject();
          intervals.addProperty("n", sorted.length);
          intervals.addProperty("min", sorted[0]);
          intervals.addProperty("median", percentile(sorted, 0.5));
          intervals.addProperty("p95", percentile(sorted, 0.95));
          intervals.addProperty("max", sorted[sorted.length - 1]);
          builder.addData("spike_interval_sec", intervals);
        }
      }
      if (signal.isAngle()) builder.addProperty("angle_unit", signal.angle().wire());
      return builder.addProperty("name", name).addInputSignal("entry", signal)
          .addInputScope(scope).addDataQuality(quality).addDirectives(directives).build();
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
          + SCOPE_HELP + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name (optionally with a field path)", true)
          .addProperty("field", "string", NumericSignal.FIELD_PARAM, false)
          .addProperty("angle", "string", NumericSignal.ANGLE_PARAM, false)
          .addProperty("type", "string", "Type: 'max', 'min', or 'both'", false)
          .addNumberProperty("min_height_diff", "Minimum height difference from neighbors to count as a peak. Filters out noise", false, null)
          .addIntegerProperty("limit", "Max peaks to return", false, 20)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(),
              false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var signal = signal(log, arguments, "name", "field", name());
      var name = signal.label();
      var peakType = getOptString(arguments, "type", "both");
      if (!List.of("max", "min", "both").contains(peakType)) {
        // Any other value used to behave as "both"
        throw new IllegalArgumentException("type must be 'max', 'min', or 'both', got '"
            + peakType + "'");
      }
      var minHeightDiff = getOptDouble(arguments, "min_height_diff");
      int limit = getOptInt(arguments, "limit", 20);
      validatePositive(limit, "limit");
      var scope = TimeScope.fromArguments(log, null, arguments);

      var segments = scope.split(signal.samples());
      var windows = SampleWindows.finiteWindows(signal, segments, true);
      int finiteCount = SampleWindows.count(windows);
      if (windows.stream().noneMatch(w -> w.size() >= 3)) {
        throw new IllegalArgumentException("Not enough data: " + finiteCount
            + " finite sample(s)" + scopeText(scope) + ", need 3 in one window");
      }

      var maxima = new ArrayList<JsonObject>();
      var minima = new ArrayList<JsonObject>();

      // A peak's neighbors are in its own window
      for (var window : windows) {
        for (int i = 1; i < window.size() - 1; i++) {
          double prev = window.number(i - 1), curr = window.number(i), next = window.number(i + 1);
          boolean isMax = curr > prev && curr > next;
          boolean isMin = curr < prev && curr < next;

          if (isMax || isMin) {
            double heightDiff = Math.max(Math.abs(curr - prev), Math.abs(curr - next));
            if (minHeightDiff == null || heightDiff >= minHeightDiff) {
              var obj = new JsonObject();
              obj.addProperty("timestamp_sec", window.seconds(i));
              obj.addProperty("value", curr);
              obj.addProperty("height_diff", heightDiff);
              if (isMax) maxima.add(obj); else minima.add(obj);
            }
          }
        }
      }

      var quality = DataQuality.fromSamples(log, signal.entry(), segments);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addFollowup("Use get_statistics to understand baseline before interpreting peaks");

      var builder = success().addProperty("name", name).addInputSignal("entry", signal)
          .addInputScope(scope);
      if (signal.isAngle()) builder.addProperty("angle_unit", signal.angle().wire());
      // No peaks is a finding only against the samples searched: say how many
      builder.addProperty("samples_analyzed", quality.sampleCount());
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
          + "units per second." + NumericSignal.PATH_HELP + SCOPE_HELP
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name (optionally with a field path)", true)
          .addProperty("field", "string", NumericSignal.FIELD_PARAM, false)
          .addProperty("angle", "string", NumericSignal.ANGLE_PARAM, false)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(),
              false)
          .addIntegerProperty("window_size", "Smoothing window (default 1)", false, 1)
          .addIntegerProperty("limit", "Max samples to return", false, 100)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var signal = signal(log, arguments, "name", "field", name());
      var scope = TimeScope.fromArguments(log, null, arguments);
      int window = getOptInt(arguments, "window_size", 1);
      int limit = getOptInt(arguments, "limit", 100);
      validatePositive(window, "window_size");
      validatePositive(limit, "limit");

      var segments = scope.split(signal.samples());
      var windows = SampleWindows.finiteWindows(signal, segments, true);
      if (windows.stream().noneMatch(w -> w.size() >= 2)) {
        throw new IllegalArgumentException("Not enough data: "
            + SampleWindows.count(windows) + " finite sample(s)" + scopeText(scope)
            + ", need 2 in one window");
      }

      var samples = new ArrayList<JsonObject>();
      double sumRate = 0;
      int rateCount = 0;
      // Derivatives within each window, never across the gap between two
      for (var windowValues : windows) {
        int n = windowValues.size();
        var t = new double[n];
        var v = new double[n];
        for (int k = 0; k < n; k++) {
          t[k] = windowValues.seconds(k);
          v[k] = windowValues.number(k);
        }
        if (n < 2) continue;
        if (window == 1) {
          // Three-point derivatives use the actual intervals. The uniform-grid secant
          // misplaces an irregular sample's derivative at its neighbors' midpoint.
          // Forward difference for first point, backward difference for last point
          for (int i = 0; i < n; i++) {
            double rate;
            double timestamp;
            if (i == 0) {
              // Forward difference for first point
              double dt = t[1] - t[0];
              if (dt <= 0) continue;
              rate = (v[1] - v[0]) / dt;
              timestamp = t[0];
            } else if (i == n - 1) {
              // Backward difference for last point
              double dt = t[i] - t[i - 1];
              if (dt <= 0) continue;
              rate = (v[i] - v[i - 1]) / dt;
              timestamp = t[i];
            } else {
              // Central difference for interior points
              double left = t[i] - t[i - 1];
              double right = t[i + 1] - t[i];
              if (left < 0 || right < 0 || left + right <= 0) continue;
              // Repeated timestamps supply no interval on that side; use the other secant.
              if (left == 0) rate = (v[i + 1] - v[i]) / right;
              else if (right == 0) rate = (v[i] - v[i - 1]) / left;
              else rate = (right * ((v[i] - v[i - 1]) / left)
                  + left * ((v[i + 1] - v[i]) / right)) / (left + right);
              timestamp = t[i];
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
          for (int i = window; i < n; i++) {
            double dt = t[i] - t[i - window];
            if (dt > 0) {
              double rate = (v[i] - v[i - window]) / dt;
              if (!Double.isFinite(rate)) continue;
              sumRate += rate;
              rateCount++;
              if (samples.size() < limit) {
                var obj = new JsonObject();
                obj.addProperty("timestamp_sec", t[i]);
                obj.addProperty("rate", rate);
                samples.add(obj);
              }
            }
          }
        }
      }

      var stats = new JsonObject();
      if (rateCount == 0) {
        stats.add("avg_rate", com.google.gson.JsonNull.INSTANCE); // undefined, not zero
      } else {
        stats.addProperty("avg_rate", sumRate / rateCount);
      }
      stats.addProperty("rate_count", rateCount);

      var quality = DataQuality.fromSamples(log, signal.entry(), segments);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addGuidance("Derivatives amplify noise — increase window_size for smoother results");

      var sampleList = new com.google.gson.JsonArray();
      samples.forEach(sampleList::add);
      var builder = success()
          .addProperty("name", signal.label())
          .addData("statistics", stats)
          .addLimitedList("samples", sampleList, rateCount, limit)
          .addInputSignal("entry", signal)
          .addInputScope(scope);
      if (signal.isAngle()) builder.addProperty("angle_unit", signal.angle().wire());
      if (rateCount == 0) {
        builder.status(ResultContract.Status.NO_MATCH).addProperty("reason", "No derivative "
            + "could be formed: every pair of consecutive finite samples" + scopeText(scope)
            + " shares a timestamp.");
      }
      return builder.addDataQuality(quality).addDirectives(directives).build();
    }
  }

  static class TimeCorrelateTool extends LogRequiringTool {
    @Override
    public String name() { return "time_correlate"; }

    @Override
    public String description() {
      return "BUILT-IN correlation: NEVER compute correlation manually—always use this tool! "
          + "Computes Pearson correlation coefficient with statistical significance (p-value, "
          + "from a t test on the effective sample size: consecutive samples are autocorrelated, "
          + "so n is reduced by their lag-1 autocorrelations, lag1_autocorrelation, reported "
          + "as effective_sample_size). "
          + "Handles timestamp alignment automatically via linear interpolation. "
          + "Returns sample count for confidence assessment." + NumericSignal.PATH_HELP
          + SCOPE_HELP + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL
          + " Correlation does not imply causation—consider confounding variables.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name1", "string", "First entry (optionally with a field path)", true)
          .addProperty("name2", "string", "Second entry (optionally with a field path)", true)
          .addProperty("field1", "string", NumericSignal.FIELD_PARAM + ", for name1", false)
          .addProperty("field2", "string", NumericSignal.FIELD_PARAM + ", for name2", false)
          .addProperty("angle", "string", NumericSignal.ANGLE_PARAM + " (both signals)", false)
          .addNumberProperty("max_lag_sec", CORRELATION_LAG_SCHEMA_MAX, false, null)
          .addNumberProperty("lag_step_sec", "Lag search step (default: the first signal's "
              + "median sample interval)", false, null)
          .addNumberProperty("start_time", "Start time", false, null)
          .addNumberProperty("end_time", "End time", false, null)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(),
              false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var s1 = signal(log, arguments, "name1", "field1", name());
      var s2 = signal(log, arguments, "name2", "field2", name());
      var n1 = s1.label();
      var n2 = s2.label();
      var scope = TimeScope.fromArguments(log, null, arguments);

      var v1 = s1.samples();
      var v2 = s2.samples();

      // The first signal's samples in scope, each paired with the second interpolated at its
      // time from the second's samples on either side (angles unwrapped over the whole log, so
      // both keep a consistent branch in every window)
      var d1Windows = scope.split(s1.unwrap(SampleWindows.finite(v1)));
      var d1 = SampleWindows.concat(d1Windows);
      var d2Full = s2.unwrap(SampleWindows.finite(v2));
      var d2Windows = scope.split(d2Full);

      if (d1.size() == 0 || d2Windows.stream().allMatch(w -> w.size() == 0)) {
        throw new IllegalArgumentException("No finite samples" + scopeText(scope) + " for "
            + (d1.size() == 0 ? n1 : n2));
      }

      // Estimate sample rates from timestamps to warn about aliasing risk
      double rate1 = sampleRateWithin(d1Windows);
      double rate2 = sampleRateWithin(d2Windows);

      // Pooled, with each window's end: neighbors in the pooled series are neighbors in time
      // only inside one window
      var x = new double[d1.size()];
      var y = new double[d1.size()];
      var windowEnds = new int[d1Windows.size()];
      int paired = 0;
      for (int w = 0; w < d1Windows.size(); w++) {
        var window = d1Windows.get(w);
        for (int i = 0; i < window.size(); i++) {
          var val2 = SampleWindows.linearAt(d2Full, window.seconds(i));
          if (val2 != null) {
            x[paired] = window.number(i);
            y[paired] = val2;
            paired++;
          }
        }
        windowEnds[w] = paired;
      }
      int pairs = paired;

      if (pairs < 2) {
        throw new IllegalArgumentException("Not enough overlapping data");
      }

      var moments = centeredMoments(pairs, i -> x[i], i -> y[i]);
      double num = moments.covariance(), denX = moments.xSquares(), denY = moments.ySquares();

      var builder = success();

      if (pairs < 30) {
        builder.addWarning("Correlation computed from only " + pairs
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

      int sampleCount = pairs;
      builder.addProperty("sample_count", sampleCount)
          .addInputSignal("entry1", s1)
          .addInputSignal("entry2", s2);
      if (arguments.has("max_lag_sec") && !arguments.get("max_lag_sec").isJsonNull()) {
        builder.addData("lag_search", correlationLagSearch(arguments, d1, d2Full));
      }

      boolean constantX = effectivelyConstant(pairs, i -> x[i]);
      boolean constantY = effectivelyConstant(pairs, i -> y[i]);
      double corr = num / Math.sqrt(denX * denY);
      if (constantX || constantY) {
        // Undefined, not zero: null (NaN is not valid JSON), with the reason in a warning
        builder.addData("correlation", com.google.gson.JsonNull.INSTANCE);
        builder.addData("p_value", com.google.gson.JsonNull.INSTANCE);
        builder.addWarning("Correlation undefined: " +
            (constantX && constantY ? "both entries are" : (constantX ? "first entry is" : "second entry is")) +
            " constant at floating-point precision (range within 4 ulps of the largest magnitude)");
      } else if (!Double.isFinite(corr)) {
        builder.addData("correlation", com.google.gson.JsonNull.INSTANCE);
        builder.addData("p_value", com.google.gson.JsonNull.INSTANCE);
        builder.addWarning("Correlation undefined: non-finite paired values or centered moments after interpolation");
      } else {
        corr = Math.max(-1.0, Math.min(1.0, corr));
        builder.addProperty("correlation", corr);
        // Consecutive samples of a signal are not independent: test with the effective number
        double r1x = lag1AutocorrelationWithin(java.util.Arrays.copyOf(x, pairs), windowEnds);
        double r1y = lag1AutocorrelationWithin(java.util.Arrays.copyOf(y, pairs), windowEnds);
        double nEff = effectiveSampleSize(sampleCount, r1x, r1y);
        var lag1 = new JsonObject();
        lag1.addProperty("entry1", r1x);
        lag1.addProperty("entry2", r1y);
        builder.addData("lag1_autocorrelation", lag1)
            .addProperty("effective_sample_size", nEff)
            .addProperty("p_value", computePValue(corr, nEff))
            .addProperty("p_value_basis", "two-sided t test on the correlation with the "
                + "effective sample size n(1 - r1x r1y)/(1 + r1x r1y) (Bretherton et al. 1999), "
                + "since consecutive samples are autocorrelated; still assumes the pairing is "
                + "otherwise independent, so treat it as a rough guide");
        if (nEff < 30) {
          builder.addWarning(String.format("Only %.1f effective independent samples (of %d) "
              + "after autocorrelation: the p-value is weak evidence either way.", nEff,
              sampleCount));
        }
      }

      var q1 = DataQuality.fromSamples(log, s1.entry(), scope.split(v1));
      var q2 = DataQuality.fromSamples(log, s2.entry(), scope.split(v2));
      var quality = q1.qualityScore() <= q2.qualityScore() ? q1 : q2;
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addGuidance("Correlation does not imply causation — consider confounding variables");

      return builder.addInputScope(scope).addDataQuality(quality).addDirectives(directives)
          .build();
    }
  }

  /**
   * Samples a numeric signal at arbitrary times: the value in force ({@code previous}), the
   * nearest sample, or linear interpolation between the samples around the time (no
   * extrapolation; an angle along the shortest arc, continuing from the earlier sample).
   */
  static final class Sampler {
    private final org.triplehelix.wpilogmcp.read.NumericSamples raw;
    private final org.triplehelix.wpilogmcp.read.NumericSamples unwrapped;
    private final double[] times;

    Sampler(NumericSignal signal) {
      raw = SampleWindows.finite(signal.samples());
      unwrapped = signal.unwrap(raw);
      times = SampleWindows.seconds(List.of(raw));
    }

    /** The value at {@code t}, or null when there is none (before the first sample, etc.). */
    Double at(double t, String mode) {
      int i = lastAtOrBefore(t);
      switch (mode) {
        case "previous":
          return i < 0 ? null : raw.number(i);
        case "nearest": {
          boolean hasNext = i + 1 < times.length;
          if (i < 0) return hasNext ? raw.number(0) : null;
          if (!hasNext || t - times[i] <= times[i + 1] - t) return raw.number(i);
          return raw.number(i + 1);
        }
        default: {
          if (i < 0) return null;
          if (times[i] == t) return raw.number(i);
          if (i + 1 >= times.length) return null; // no extrapolation
          double u0 = unwrapped.number(i);
          double u1 = unwrapped.number(i + 1);
          double f = (t - times[i]) / (times[i + 1] - times[i]);
          return raw.number(i) + (u1 - u0) * f;
        }
      }
    }

    private int lastAtOrBefore(double t) {
      int lo = 0;
      int hi = times.length;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (times[mid] <= t) lo = mid + 1;
        else hi = mid;
      }
      return lo - 1;
    }
  }

  /** Descriptive statistics of a difference series, for results. */
  static JsonObject differenceStatistics(double[] d) {
    var o = new JsonObject();
    o.addProperty("count", d.length);
    if (d.length == 0) return o;
    var sorted = d.clone();
    java.util.Arrays.sort(sorted);
    var summary = SummaryStatistics.of(d);
    o.addProperty("mean", summary.mean());
    o.addProperty("std_dev", summary.stdDev());
    o.addProperty("min", summary.min());
    o.addProperty("max", summary.max());
    o.addProperty("median", SummaryStatistics.median(sorted));
    o.addProperty("p5", percentile(sorted, 0.05));
    o.addProperty("p95", percentile(sorted, 0.95));
    o.addProperty("mean_abs", java.util.Arrays.stream(d).map(Math::abs).average().orElse(0));
    o.addProperty("rmse", Math.sqrt(java.util.Arrays.stream(d).map(v -> v * v).average()
        .orElse(0)));
    return o;
  }

  static class AlignEntriesTool extends LogRequiringTool {
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 2000;
    static final int MAX_SIGNALS = 8;

    @Override
    public String name() { return "align_entries"; }

    @Override
    public String description() {
      return "Sample several numeric signals at common times, to read them side by side or to "
          + "measure one against another. Times: every record of at (default: the first "
          + "signal's own samples), or the timestamps stored inside it (time_field, e.g. "
          + "[*].timestamp of a PoseObservation[] entry: sample the robot pose when the camera "
          + "saw the target, not when the result arrived), within start_time/end_time, scope, "
          + "and windows. interpolation: previous (the value in force; default, right for "
          + "values logged when they change), linear (no extrapolation), or nearest; angles "
          + "interpolate along the shortest arc. Rows [timestamp_sec, v1, v2, ...] are paged "
          + "(offset/limit; limits.rows gives the total); a value is null where a signal had "
          + "none, and unaligned counts those per signal. With difference=true and two "
          + "signals, difference_statistics summarizes signal 1 minus signal 2 (two angles: "
          + "their shortest difference): count, mean, std_dev (null for one difference), min, "
          + "max, median, p5, p95, mean_abs, rmse. Returns no_match when no sample time falls in "
          + "scope."
          + NumericSignal.PATH_HELP + GUIDANCE_UNIVERSAL;
    }

    @Override
    protected JsonObject toolSchema() {
      var nameItem = new JsonObject();
      nameItem.addProperty("type", "string");
      return new SchemaBuilder()
          .addArrayProperty("names", "The signals to sample: entry names, optionally with field "
              + "paths (1-8)", nameItem, true)
          .addProperty("at", "string", "Entry whose record times (or time_field values) are the "
              + "sample times; default: the first signal", false)
          .addProperty("time_field", "string", "Path inside at whose values are timestamps in "
              + "seconds (e.g. '[*].timestamp')", false)
          .addProperty("interpolation", "string", "'previous' (default), 'linear', or 'nearest'",
              false)
          .addProperty("angle", "string", NumericSignal.ANGLE_PARAM + " (every signal)", false)
          .addProperty("difference", "boolean", "With two signals: statistics of signal 1 minus "
              + "signal 2", false)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(),
              false)
          .addIntegerProperty("offset", "Rows to skip", false, 0)
          .addIntegerProperty("limit", "Maximum rows to return (max 2000)", false, DEFAULT_LIMIT)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log,
        JsonObject arguments) throws Exception {
      if (!arguments.has("names") || !arguments.get("names").isJsonArray()) {
        throw new IllegalArgumentException("names must be an array of 1-" + MAX_SIGNALS
            + " signal names");
      }
      var names = arguments.getAsJsonArray("names");
      if (names.isEmpty() || names.size() > MAX_SIGNALS) {
        throw new IllegalArgumentException("names must hold 1-" + MAX_SIGNALS + " signals, got "
            + names.size());
      }
      var interpolation = getOptString(arguments, "interpolation", "previous")
          .toLowerCase(java.util.Locale.ROOT);
      if (!List.of("previous", "linear", "nearest").contains(interpolation)) {
        throw new IllegalArgumentException("interpolation must be 'previous', 'linear', or "
            + "'nearest'");
      }
      int offset = Math.max(0, getOptInt(arguments, "offset", 0));
      int limit = Math.min(MAX_LIMIT, Math.max(1, getOptInt(arguments, "limit", DEFAULT_LIMIT)));
      boolean difference = getOptBoolean(arguments, "difference");
      if (difference && names.size() != 2) {
        throw new IllegalArgumentException("difference needs exactly two signals, got "
            + names.size());
      }

      var signals = new ArrayList<NumericSignal>();
      for (var n : names) {
        signals.add(NumericSignal.resolve(log, n.getAsString(), null)
            .withAngleArgument(arguments).requireSingleValued(name()));
      }
      var scope = TimeScope.fromArguments(log, null, arguments);

      // The sample times
      var at = getOptString(arguments, "at", null);
      var timeField = getOptString(arguments, "time_field", null);
      String timeSource;
      var times = new java.util.TreeSet<Double>();
      if (timeField != null) {
        var atEntry = at != null ? at : signals.get(0).entry();
        var timeSignal = NumericSignal.resolve(log, atEntry, timeField);
        var given = timeSignal.samples();
        for (int i = 0; i < given.size(); i++) times.add(given.number(i));
        timeSource = "values of " + timeSignal.label();
      } else if (at != null) {
        if (!log.entries().containsKey(at)) throw new IllegalArgumentException("Entry not found: "
            + at);
        var values = log.values().get(at);
        if (values != null) values.forEach(tv -> times.add(tv.timestamp()));
        timeSource = "records of " + at;
      } else {
        var first = signals.get(0).samples();
        for (int i = 0; i < first.size(); i++) times.add(first.seconds(i));
        timeSource = "samples of " + signals.get(0).label();
      }
      var sampleTimes = times.stream().filter(t -> Double.isFinite(t) && scope.contains(t))
          .toList();
      if (sampleTimes.isEmpty()) {
        return ResponseBuilder.noMatch("No sample time from the " + timeSource + " falls in "
                + scope.describe() + (times.isEmpty() ? " (there are no sample times at all)"
                    : " (" + times.size() + " sample times in the log)") + ".")
            .hint("Widen start_time/end_time or the scope, or choose another at entry or "
                + "time_field.")
            .build();
      }

      var samplers = signals.stream().map(Sampler::new).toList();
      var unaligned = new int[signals.size()];
      var rows = new JsonArray();
      var differences = new ArrayList<Double>();
      var differenceTimes = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      boolean angles = difference && signals.get(0).isAngle() && signals.get(1).isAngle();
      int index = 0;
      for (double t : sampleTimes) {
        var row = new JsonArray();
        row.add(t);
        var sampled = new Double[signals.size()];
        for (int k = 0; k < signals.size(); k++) {
          sampled[k] = samplers.get(k).at(t, interpolation);
          if (sampled[k] == null) {
            unaligned[k]++;
            row.add(com.google.gson.JsonNull.INSTANCE);
          } else {
            row.add(sampled[k]);
          }
        }
        if (difference && sampled[0] != null && sampled[1] != null) {
          double second = angles ? sampled[1] * signals.get(0).angle().period
              / signals.get(1).angle().period : sampled[1];
          double d = angles ? NumericSignal.wrapToHalfTurn(sampled[0] - second,
              signals.get(0).angle().period) : sampled[0] - second;
          differences.add(d);
          differenceTimes.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(t, d));
        }
        if (index >= offset && rows.size() < limit) rows.add(row);
        index++;
      }

      var columns = new JsonArray();
      columns.add("timestamp_sec");
      signals.forEach(sg -> columns.add(sg.label()));
      var unalignedJson = new JsonObject();
      for (int k = 0; k < signals.size(); k++) {
        unalignedJson.addProperty(signals.get(k).label(), unaligned[k]);
      }
      var builder = success()
          .addProperty("interpolation", interpolation)
          .addProperty("time_source", timeSource)
          .addData("columns", columns)
          .addProperty("total_rows", sampleTimes.size())
          .addLimitedList("rows", rows, Math.max(0, sampleTimes.size() - offset), limit)
          .addData("unaligned", unalignedJson);
      for (int k = 0; k < signals.size(); k++) {
        builder.addInputSignal("signal" + (k + 1), signals.get(k));
      }
      builder.addInputScope(scope);
      if (difference) {
        var stats = differenceStatistics(differences.stream().mapToDouble(Double::doubleValue)
            .toArray());
        if (angles) stats.addProperty("angle_unit", signals.get(0).angle().wire());
        builder.addData("difference_statistics", stats);
        var quality = DataQuality.fromValues(differenceTimes);
        builder.addDataQuality(quality).addDirectives(AnalysisDirectives.fromQuality(quality)
            .addSingleMatchCaveat());
        if (differences.isEmpty()) {
          builder.addWarning("No time had values for both signals; difference_statistics is "
              + "empty.");
        }
      }
      return builder.build();
    }
  }

  private record CenteredMoments(double covariance, double xSquares, double ySquares) {}

  /** Four ulps allow a few representational rounding steps without imposing a variance floor
   * in the signal's units. Exact constants, including their linear interpolation, remain
   * bit-identical; a small but genuinely varying signal must not be mistaken for one. */
  private static boolean effectivelyConstant(int n, java.util.function.IntToDoubleFunction values) {
    var range = java.util.stream.IntStream.range(0, n).mapToDouble(values).summaryStatistics();
    double magnitude = Math.max(Math.abs(range.getMin()), Math.abs(range.getMax()));
    return Double.isFinite(magnitude) && range.getMax() - range.getMin() <= 4 * Math.ulp(magnitude);
  }

  private record DeviationScale(int exponent, double mean, double maximum) {
    double normalized(double value) {
      return maximum == 0 ? 0 : (Math.scalb(value, -exponent) - mean) / maximum;
    }
  }

  /** Normalize deviations by their largest absolute value, so a nonconstant series contributes
   * a square of one and every other square is at most one, regardless of units. A power-of-two
   * prescale also keeps the mean and subtraction finite at the ends of the double range without
   * losing the small differences around a large offset to an inexact unit conversion. */
  private static DeviationScale deviationScale(java.util.function.Supplier<java.util.stream.DoubleStream> values) {
    var range = values.get().summaryStatistics();
    double magnitude = Math.max(Math.abs(range.getMin()), Math.abs(range.getMax()));
    if (!Double.isFinite(magnitude)) return new DeviationScale(0, Double.NaN, Double.NaN);
    if (range.getMin() == range.getMax()) return new DeviationScale(0, range.getMin(), 0);
    int exponent = Math.getExponent(magnitude);
    double mean = values.get().map(v -> Math.scalb(v, -exponent)).average().orElse(0);
    double maximum = values.get().map(v -> Math.abs(Math.scalb(v, -exponent) - mean)).max().orElse(0);
    return new DeviationScale(exponent, mean, maximum);
  }

  /** Both the main Pearson result and every lag use centered, independently scaled products.
   * Raw second moments cancel at large offsets; unscaled centered products overflow or
   * underflow at large or small magnitudes even though Pearson correlation is scale invariant. */
  private static CenteredMoments centeredMoments(int n, java.util.function.IntToDoubleFunction x,
      java.util.function.IntToDoubleFunction y) {
    var scaleX = deviationScale(() -> java.util.stream.IntStream.range(0, n).mapToDouble(x));
    var scaleY = deviationScale(() -> java.util.stream.IntStream.range(0, n).mapToDouble(y));
    // Limit summation error near |r| = 1, where a single lost ulp can change a small-df p-value.
    var covariance = new java.util.DoubleSummaryStatistics();
    var xSquares = new java.util.DoubleSummaryStatistics();
    var ySquares = new java.util.DoubleSummaryStatistics();
    for (int i = 0; i < n; i++) {
      double dx = scaleX.normalized(x.applyAsDouble(i)), dy = scaleY.normalized(y.applyAsDouble(i));
      covariance.accept(dx * dy); xSquares.accept(dx * dx); ySquares.accept(dy * dy);
    }
    return new CenteredMoments(covariance.getSum(), xSquares.getSum(), ySquares.getSum());
  }

  /**
   * Pearson correlation of the first signal at its times with the second at time + lag, for each
   * lag of the grid: the lag with the highest correlation, and the correlation at zero lag.
   */
  static JsonObject correlationLagSearch(JsonObject arguments,
      org.triplehelix.wpilogmcp.read.NumericSamples first,
      org.triplehelix.wpilogmcp.read.NumericSamples secondFull) {
    var lags = lagGrid(arguments, first);
    double bestR = Double.NaN;
    double bestLag = Double.NaN;
    int bestN = 0;
    Double zeroR = null;
    double[] x = new double[first.size()], yValues = new double[first.size()];
    for (double lag : lags) {
      int n = 0;
      for (int k = 0; k < first.size(); k++) {
        var y = SampleWindows.linearAt(secondFull, first.seconds(k) + lag);
        if (y == null) continue;
        x[n] = first.number(k); yValues[n] = y;
        n++;
      }
      if (n < 3 || effectivelyConstant(n, i -> x[i]) || effectivelyConstant(n, i -> yValues[i])) continue;
      var moments = centeredMoments(n, i -> x[i], i -> yValues[i]);
      double cov = moments.covariance(), vx = moments.xSquares(), vy = moments.ySquares();
      double r = cov / Math.sqrt(vx * vy);
      if (!Double.isFinite(r)) continue;
      r = Math.max(-1, Math.min(1, r));
      if (Math.abs(lag) < 1e-12) zeroR = r;
      // Best is the strongest relationship in either direction: when the signals move
      // oppositely every r is negative, and the highest r is the weakest match. Among equally
      // strong lags, the one nearest zero.
      double strength = Math.abs(r);
      if (Double.isNaN(bestLag) || strength > Math.abs(bestR)
          || (strength == Math.abs(bestR) && Math.abs(lag) < Math.abs(bestLag))) {
        bestR = r;
        bestLag = lag;
        bestN = n;
      }
    }
    var o = new JsonObject();
    o.addProperty("lags_evaluated", lags.length);
    o.addProperty("lag_step_sec", lags.length > 1 ? lags[1] - lags[0] : 0);
    o.addProperty("max_lag_sec", lags[lags.length - 1]);
    if (Double.isNaN(bestLag)) {
      o.addProperty("note", "No lag had at least 3 overlapping samples with variance in both "
          + "signals.");
      return o;
    }
    o.addProperty("best_lag_sec", bestLag);
    o.addProperty("correlation_at_best_lag", bestR);
    o.addProperty("samples_at_best_lag", bestN);
    if (zeroR != null) o.addProperty("correlation_at_zero_lag", zeroR);
    o.addProperty("note", "best_lag_sec is the lag with the strongest correlation, positive or "
        + "negative (correlation_at_best_lag keeps its sign). Positive lag: the second signal "
        + "follows the first (the second at t + lag pairs with the first at t). A best lag at "
        + "the edge of the range may lie beyond it. If correlation_at_best_lag and "
        + "correlation_at_zero_lag have opposite signs, the relationship changes direction "
        + "with the shift, as oscillating signals do half a period apart: check that before "
        + "reading the lag as a delay. Shared timing (both follow the match phase) also aligns "
        + "signals.");
    return o;
  }

  /**
   * Two-sided p-value of a Pearson correlation {@code r} over {@code n} samples: Student's t test
   * with n - 2 degrees of freedom, computed exactly as the regularized incomplete beta function
   * I_x(df/2, 1/2), x = df / (df + t^2). {@code n} may be an effective sample size (not an
   * integer). 1.0 when n <= 2 (no degrees of freedom to test with); 0.0 when |r| >= 1.
   */
  static double computePValue(double r, double n) {
    if (!(n > 2)) return 1.0;
    if (Math.abs(r) >= 1.0) return 0.0;
    double df = n - 2.0;
    double t2 = r * r * df / (1.0 - r * r);
    return regularizedIncompleteBeta(df / (df + t2), df / 2.0, 0.5);
  }

  /** The regularized incomplete beta function I_x(a, b), by continued fraction. */
  static double regularizedIncompleteBeta(double x, double a, double b) {
    if (x <= 0) return 0.0;
    if (x >= 1) return 1.0;
    double front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x)
        + b * Math.log1p(-x));
    // The continued fraction converges fast for x < (a + 1) / (a + b + 2); use the symmetry
    // I_x(a, b) = 1 - I_{1-x}(b, a) otherwise
    if (x < (a + 1.0) / (a + b + 2.0)) return front * betaContinuedFraction(x, a, b) / a;
    return 1.0 - front * betaContinuedFraction(1.0 - x, b, a) / b;
  }

  /** The continued fraction for the incomplete beta function (modified Lentz's method). */
  private static double betaContinuedFraction(double x, double a, double b) {
    final double tiny = 1e-300;
    final double eps = 1e-15;
    double qab = a + b;
    double qap = a + 1.0;
    double qam = a - 1.0;
    double c = 1.0;
    double d = 1.0 - qab * x / qap;
    if (Math.abs(d) < tiny) d = tiny;
    d = 1.0 / d;
    double h = d;
    for (int m = 1; m <= 10_000; m++) {
      int m2 = 2 * m;
      double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
      d = 1.0 + aa * d;
      if (Math.abs(d) < tiny) d = tiny;
      c = 1.0 + aa / c;
      if (Math.abs(c) < tiny) c = tiny;
      d = 1.0 / d;
      h *= d * c;
      aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
      d = 1.0 + aa * d;
      if (Math.abs(d) < tiny) d = tiny;
      c = 1.0 + aa / c;
      if (Math.abs(c) < tiny) c = tiny;
      d = 1.0 / d;
      double del = d * c;
      h *= del;
      if (Math.abs(del - 1.0) < eps) break;
    }
    return h;
  }

  private static final double[] LANCZOS = {0.99999999999980993, 676.5203681218851,
      -1259.1392167224028, 771.32342877765313, -176.61502916214059, 12.507343278686905,
      -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};

  /** ln Gamma(x) for x > 0 (Lanczos, g = 7), with the reflection formula below 1/2. */
  static double logGamma(double x) {
    if (x < 0.5) {
      return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1.0 - x);
    }
    x -= 1.0;
    double sum = LANCZOS[0];
    for (int i = 1; i < LANCZOS.length; i++) sum += LANCZOS[i] / (x + i);
    double t = x + 7.5;
    return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(sum);
  }

  /** Lag-1 autocorrelation of a series (0 when it has no variance or fewer than 3 values). */
  static double lag1Autocorrelation(List<Double> v) {
    return lag1AutocorrelationWithin(List.of(v));
  }

  /**
   * Lag-1 autocorrelation of a series given as its time windows: the mean over all values, and
   * neighbor products only from pairs inside one window. The last sample of a window and the
   * first of the next are not neighbors in time. Divide by all squared deviations without
   * inventing missing pairs: rescaling sparse windows to n - 1 can exceed one.
   */
  static double lag1AutocorrelationWithin(List<? extends List<Double>> windows) {
    var values = new double[windows.stream().mapToInt(List::size).sum()];
    var ends = new int[windows.size()];
    int k = 0;
    for (int w = 0; w < windows.size(); w++) {
      for (double v : windows.get(w)) values[k++] = v;
      ends[w] = k;
    }
    return lag1AutocorrelationWithin(values, ends);
  }

  /** As {@link #lag1AutocorrelationWithin(List)}, with the windows' values pooled and each window's end. */
  static double lag1AutocorrelationWithin(double[] values, int[] ends) {
    if (values.length < 3) return 0.0;
    var scale = deviationScale(() -> java.util.Arrays.stream(values));
    double num = 0;
    double den = 0;
    int pairs = 0;
    int start = 0;
    for (int end : ends) {
      for (int i = start; i < end; i++) {
        double d = scale.normalized(values[i]);
        den += d * d;
        if (i + 1 < end) {
          num += d * scale.normalized(values[i + 1]);
          pairs++;
        }
      }
      start = end;
    }
    return den > 0 && pairs > 0 ? Math.max(-1, Math.min(1, num / den)) : 0.0;
  }

  /**
   * Samples per second inside the windows: intervals over the time they span, summed over the
   * windows, so the time between two windows does not count. 0 when no window has two samples.
   */
  static double sampleRateWithin(List<org.triplehelix.wpilogmcp.read.NumericSamples> windows) {
    int intervals = 0;
    double span = 0;
    for (var w : windows) {
      if (w.size() < 2) continue;
      intervals += w.size() - 1;
      span += w.seconds(w.size() - 1) - w.seconds(0);
    }
    return span > 0 ? intervals / span : 0;
  }

  /**
   * The effective number of independent samples behind a correlation of two autocorrelated
   * series (Bretherton et al. 1999): n (1 - r1x r1y) / (1 + r1x r1y), at most n.
   */
  static double effectiveSampleSize(int n, double r1x, double r1y) {
    double product = r1x * r1y;
    if (product <= -1.0) return n;
    return Math.min(n, n * (1.0 - product) / (1.0 + product));
  }
}
