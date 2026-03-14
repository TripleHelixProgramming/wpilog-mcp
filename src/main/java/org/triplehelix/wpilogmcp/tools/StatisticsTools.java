package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

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
  public static void registerAll(McpServer server) {
    server.registerTool(new GetStatisticsTool());
    server.registerTool(new CompareEntriesTool());
    server.registerTool(new DetectAnomaliesTool());
    server.registerTool(new FindPeaksTool());
    server.registerTool(new RateOfChangeTool());
    server.registerTool(new TimeCorrelateTool());
  }

  /**
   * Tool for computing descriptive statistics on numeric log entries.
   *
   * <p>Computes min, max, mean, median, and standard deviation for any numeric entry.
   * Supports optional time range filtering.
   */
  static class GetStatisticsTool implements Tool {
    @Override
    public String name() { return "get_statistics"; }

    @Override
    public String description() { return "Get statistics (min, max, mean, median, std_dev) for a numeric entry."; }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "The entry name", true)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var name = getRequiredString(arguments, "name");
      var start = getOptDouble(arguments, "start_time");
      var end = getOptDouble(arguments, "end_time");

      var values = log.values().get(name);
      if (values == null) return errorResult("Entry not found: " + name);

      var data = values.stream()
          .filter(tv -> (start == null || tv.timestamp() >= start) && (end == null || tv.timestamp() <= end))
          .filter(tv -> tv.value() instanceof Number)
          .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
          .toArray();

      if (data.length == 0) return errorResult("No numeric data in range");

      var stats = java.util.Arrays.stream(data).summaryStatistics();
      java.util.Arrays.sort(data);
      double median = data.length % 2 == 1 ? data[data.length / 2] : (data[data.length / 2 - 1] + data[data.length / 2]) / 2.0;
      
      double mean = stats.getAverage();
      double stdDev = Math.sqrt(java.util.Arrays.stream(data).map(v -> Math.pow(v - mean, 2)).average().orElse(0.0));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("name", name);
      result.addProperty("count", stats.getCount());
      result.addProperty("min", stats.getMin());
      result.addProperty("max", stats.getMax());
      result.addProperty("mean", mean);
      result.addProperty("median", median);
      result.addProperty("std_dev", stdDev);
      return result;
    }
  }

  static class CompareEntriesTool implements Tool {
    @Override
    public String name() { return "compare_entries"; }

    @Override
    public String description() { return "Compare two numeric entries."; }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("name1", "string", "First entry", true)
          .addProperty("name2", "string", "Second entry", true)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var n1 = getRequiredString(arguments, "name1");
      var n2 = getRequiredString(arguments, "name2");

      var v1 = log.values().get(n1);
      var v2 = log.values().get(n2);

      if (v1 == null || v2 == null) return errorResult("One or both entries not found");

      double rmse = calculateRmseLinear(v1, v2);
      
      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("rmse", rmse);

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
      result.addProperty("max_difference", maxDiff);

      return result;
    }
  }

  static class DetectAnomaliesTool implements Tool {
    @Override
    public String name() { return "detect_anomalies"; }

    @Override
    public String description() {
      return "Detect anomalies (outliers) in numeric data using the IQR method. "
          + "Finds values that fall outside 1.5*IQR from Q1/Q3, or sudden spikes/drops.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name", true)
          .addNumberProperty("iqr_multiplier", "IQR multiplier (default 1.5)", false, 1.5)
          .addNumberProperty("spike_threshold", "Spike percentage threshold", false, null)
          .addIntegerProperty("limit", "Max anomalies to return", false, 50)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var name = getRequiredString(arguments, "name");
      double iqrMult = getOptDouble(arguments, "iqr_multiplier", 1.5);
      int limit = getOptInt(arguments, "limit", 50);

      var values = log.values().get(name);
      if (values == null) return errorResult("Entry not found");

      var numeric = values.stream()
          .filter(tv -> tv.value() instanceof Number)
          .toList();
      if (numeric.size() < 4) return errorResult("Not enough data");

      var sortedData = numeric.stream()
          .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
          .sorted()
          .toArray();

      double q1 = sortedData[sortedData.length / 4];
      double q3 = sortedData[3 * sortedData.length / 4];
      double iqr = q3 - q1;
      double low = q1 - iqrMult * iqr;
      double high = q3 + iqrMult * iqr;

      var anomalies = new ArrayList<JsonObject>();
      for (var tv : numeric) {
        double v = ((Number) tv.value()).doubleValue();
        if (v < low || v > high) {
          var obj = new JsonObject();
          obj.addProperty("timestamp_sec", tv.timestamp());
          obj.addProperty("value", v);
          obj.addProperty("type", v < low ? "below_lower_bound" : "above_upper_bound");
          anomalies.add(obj);
          if (anomalies.size() >= limit) break;
        }
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("anomaly_count", anomalies.size());
      result.add("anomalies", GSON.toJsonTree(anomalies));
      return result;
    }
  }

  static class FindPeaksTool implements Tool {
    @Override
    public String name() { return "find_peaks"; }

    @Override
    public String description() {
      return "Find local maxima and minima (peaks and valleys) in numeric data.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name", true)
          .addProperty("type", "string", "Type: 'max', 'min', or 'both'", false)
          .addNumberProperty("prominence", "Minimum peak prominence", false, null)
          .addIntegerProperty("limit", "Max peaks to return", false, 20)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var name = getRequiredString(arguments, "name");
      var peakType = getOptString(arguments, "type", "both");
      var prominence = getOptDouble(arguments, "prominence");
      int limit = getOptInt(arguments, "limit", 20);

      var values = log.values().get(name);
      if (values == null) return errorResult("Entry not found");

      var data = values.stream()
          .filter(tv -> tv.value() instanceof Number)
          .map(tv -> new double[]{tv.timestamp(), ((Number) tv.value()).doubleValue()})
          .toList();

      if (data.size() < 3) return errorResult("Not enough data");

      var maxima = new ArrayList<JsonObject>();
      var minima = new ArrayList<JsonObject>();

      for (int i = 1; i < data.size() - 1; i++) {
        double prev = data.get(i-1)[1], curr = data.get(i)[1], next = data.get(i+1)[1];
        boolean isMax = curr > prev && curr > next;
        boolean isMin = curr < prev && curr < next;

        if (isMax || isMin) {
          double prom = Math.max(Math.abs(curr - prev), Math.abs(curr - next));
          if (prominence == null || prom >= prominence) {
            var obj = new JsonObject();
            obj.addProperty("timestamp_sec", data.get(i)[0]);
            obj.addProperty("value", curr);
            obj.addProperty("prominence", prom);
            if (isMax) maxima.add(obj); else minima.add(obj);
          }
        }
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      if (!"min".equals(peakType)) result.add("maxima", GSON.toJsonTree(maxima.stream().limit(limit).toList()));
      if (!"max".equals(peakType)) result.add("minima", GSON.toJsonTree(minima.stream().limit(limit).toList()));
      return result;
    }
  }

  static class RateOfChangeTool implements Tool {
    @Override
    public String name() { return "rate_of_change"; }

    @Override
    public String description() {
      return "Compute rate of change (derivative) of numeric data over time.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name", true)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addIntegerProperty("window_size", "Smoothing window (default 1)", false, 1)
          .addIntegerProperty("limit", "Max samples to return", false, 100)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var name = getRequiredString(arguments, "name");
      var start = getOptDouble(arguments, "start_time");
      var end = getOptDouble(arguments, "end_time");
      int window = getOptInt(arguments, "window_size", 1);
      int limit = getOptInt(arguments, "limit", 100);

      var values = log.values().get(name);
      if (values == null) return errorResult("Entry not found");

      var data = values.stream()
          .filter(tv -> tv.value() instanceof Number)
          .filter(tv -> (start == null || tv.timestamp() >= start) && (end == null || tv.timestamp() <= end))
          .map(tv -> new double[]{tv.timestamp(), ((Number) tv.value()).doubleValue()})
          .toList();

      if (data.size() < 2) return errorResult("Not enough data");

      var samples = new ArrayList<JsonObject>();
      double sumRate = 0;
      for (int i = window; i < data.size(); i++) {
        double dt = data.get(i)[0] - data.get(i-window)[0];
        if (dt > 0) {
          double rate = (data.get(i)[1] - data.get(i-window)[1]) / dt;
          sumRate += rate;
          if (samples.size() < limit) {
            var obj = new JsonObject();
            obj.addProperty("timestamp_sec", data.get(i)[0]);
            obj.addProperty("rate", rate);
            samples.add(obj);
          }
        }
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      var stats = new JsonObject();
      stats.addProperty("avg_rate", samples.isEmpty() ? 0 : sumRate / (data.size() - window));
      result.add("statistics", stats);
      result.add("samples", GSON.toJsonTree(samples));
      return result;
    }
  }

  static class TimeCorrelateTool implements Tool {
    @Override
    public String name() { return "time_correlate"; }

    @Override
    public String description() {
      return "Compute Pearson correlation coefficient between two numeric entries.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("name1", "string", "First entry", true)
          .addProperty("name2", "string", "Second entry", true)
          .addNumberProperty("start_time", "Start time", false, null)
          .addNumberProperty("end_time", "End time", false, null)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var n1 = getRequiredString(arguments, "name1");
      var n2 = getRequiredString(arguments, "name2");
      var start = getOptDouble(arguments, "start_time");
      var end = getOptDouble(arguments, "end_time");

      var v1 = log.values().get(n1);
      var v2 = log.values().get(n2);
      if (v1 == null || v2 == null) return errorResult("Entries not found");

      var d1 = v1.stream().filter(tv -> tv.value() instanceof Number && (start == null || tv.timestamp() >= start) && (end == null || tv.timestamp() <= end)).toList();
      var d2 = v2.stream().filter(tv -> tv.value() instanceof Number && (start == null || tv.timestamp() >= start) && (end == null || tv.timestamp() <= end)).toList();

      if (d1.isEmpty() || d2.isEmpty()) return errorResult("No numeric data");

      var x = new ArrayList<Double>();
      var y = new ArrayList<Double>();
      for (var tv1 : d1) {
        var val2 = getValueAtTimeLinear(v2, tv1.timestamp());
        if (val2 != null) {
          x.add(((Number) tv1.value()).doubleValue());
          y.add(val2);
        }
      }

      if (x.size() < 2) return errorResult("Not enough overlapping data");

      double meanX = x.stream().mapToDouble(v -> v).average().orElse(0);
      double meanY = y.stream().mapToDouble(v -> v).average().orElse(0);
      double num = 0, denX = 0, denY = 0;
      for (int i = 0; i < x.size(); i++) {
        double dx = x.get(i) - meanX, dy = y.get(i) - meanY;
        num += dx * dy; denX += dx * dx; denY += dy * dy;
      }
      double corr = (denX == 0 || denY == 0) ? 0 : num / Math.sqrt(denX * denY);

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("correlation", corr);
      return result;
    }
  }
}
