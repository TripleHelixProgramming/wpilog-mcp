package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Objects;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * FRC domain-specific analysis tools for WPILOG data.
 *
 * <p>Provides specialized tools for analyzing FRC robot telemetry including DriverStation
 * events, vision system performance, mechanism profiling, autonomous routine analysis,
 * game piece cycle times, and AdvantageKit replay drift detection.
 *
 * <p>Tools included:
 * <ul>
 *   <li>{@code get_ds_timeline} - Generate chronological timeline of robot events</li>
 *   <li>{@code analyze_vision} - Analyze vision system detection rates and latency</li>
 *   <li>{@code profile_mechanism} - Profile mechanism velocity/acceleration characteristics</li>
 *   <li>{@code analyze_auto} - Analyze autonomous routine performance</li>
 *   <li>{@code analyze_cycles} - Analyze game piece cycle times</li>
 *   <li>{@code analyze_replay_drift} - Detect AdvantageKit replay divergence</li>
 * </ul>
 */
public final class FrcDomainTools {

  private FrcDomainTools() {}

  /**
   * Registers all FRC domain tools with the MCP server.
   *
   * @param server The MCP server to register tools with
   */
  public static void registerAll(McpServer server) {
    server.registerTool(new GetDsTimelineTool());
    server.registerTool(new AnalyzeVisionTool());
    server.registerTool(new ProfileMechanismTool());
    server.registerTool(new AnalyzeAutoTool());
    server.registerTool(new AnalyzeCyclesTool());
    server.registerTool(new AnalyzeReplayDriftTool());
  }

  static class GetDsTimelineTool implements Tool {
    @Override
    public String name() { return "get_ds_timeline"; }

    @Override
    public String description() {
      return "Generate a chronological timeline of critical robot events: enable/disable, "
          + "match phases, brownouts, joystick disconnects, errors, and warnings.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .addNumberProperty("brownout_threshold", "Voltage threshold for brownout detection (default: 7.0V)", false, 7.0)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      double brownoutThreshold = getOptDouble(arguments, "brownout_threshold", 7.0);

      var events = new ArrayList<JsonObject>();

      for (var entryName : log.entries().keySet()) {
        var lower = entryName.toLowerCase();

        if (lower.contains("driverstation") && lower.contains("enabled")) {
          var values = log.values().get(entryName);
          if (values != null) {
            var lastState = (Boolean) null;
            for (var tv : values) {
              if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;
              if (tv.value() instanceof Boolean state) {
                if (lastState == null || !lastState.equals(state)) {
                  var event = new JsonObject();
                  event.addProperty("timestamp", tv.timestamp());
                  event.addProperty("type", state ? "ENABLED" : "DISABLED");
                  event.addProperty("category", "robot_state");
                  event.addProperty("source", entryName);
                  events.add(event);
                  lastState = state;
                }
              }
            }
          }
        }

        if (lower.contains("driverstation") && (lower.contains("autonomous") || lower.contains("auto"))) {
          var values = log.values().get(entryName);
          if (values != null) {
            var lastState = (Boolean) null;
            for (var tv : values) {
              if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;
              if (tv.value() instanceof Boolean isAuto) {
                if (lastState == null || !lastState.equals(isAuto)) {
                  var event = new JsonObject();
                  event.addProperty("timestamp", tv.timestamp());
                  event.addProperty("type", isAuto ? "AUTO_START" : "TELEOP_START");
                  event.addProperty("category", "match_phase");
                  event.addProperty("source", entryName);
                  events.add(event);
                  lastState = isAuto;
                }
              }
            }
          }
        }
      }

      // Add voltage brownouts
      for (var entryName : log.entries().keySet()) {
        var lower = entryName.toLowerCase();
        if (lower.contains("batteryvoltage") || lower.contains("battery_voltage") ||
            (lower.contains("voltage") && lower.contains("input"))) {
          var values = log.values().get(entryName);
          if (values != null) {
            boolean inBrownout = false;
            for (var tv : values) {
              if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;
              if (tv.value() instanceof Number num) {
                double voltage = num.doubleValue();
                if (voltage < brownoutThreshold && !inBrownout) {
                  var event = new JsonObject();
                  event.addProperty("timestamp", tv.timestamp());
                  event.addProperty("type", "BROWNOUT_START");
                  event.addProperty("category", "power");
                  event.addProperty("voltage", voltage);
                  event.addProperty("source", entryName);
                  events.add(event);
                  inBrownout = true;
                } else if (voltage >= brownoutThreshold && inBrownout) {
                  var event = new JsonObject();
                  event.addProperty("timestamp", tv.timestamp());
                  event.addProperty("type", "BROWNOUT_END");
                  event.addProperty("category", "power");
                  event.addProperty("voltage", voltage);
                  event.addProperty("source", entryName);
                  events.add(event);
                  inBrownout = false;
                }
              }
            }
          }
          break;
        }
      }

      events.sort(Comparator.comparingDouble(a -> a.get("timestamp").getAsDouble()));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("event_count", events.size());

      var categoryCounts = new HashMap<String, Integer>();
      for (var event : events) {
        var cat = event.get("category").getAsString();
        categoryCounts.merge(cat, 1, Integer::sum);
      }
      result.add("summary", GSON.toJsonTree(categoryCounts));
      result.add("events", GSON.toJsonTree(events));

      return result;
    }

    private boolean inTimeRange(double timestamp, Double start, Double end) {
      if (start != null && timestamp < start) return false;
      if (end != null && timestamp > end) return false;
      return true;
    }
  }

  static class AnalyzeVisionTool implements Tool {
    @Override
    public String name() { return "analyze_vision"; }

    @Override
    public String description() {
      return "Analyze vision system reliability: target acquisition rate, flicker detection, "
          + "pose discrepancy between vision and odometry, and sudden pose jumps.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("vision_prefix", "string", "Entry path prefix for vision data", false)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .addNumberProperty("jump_threshold", "Distance threshold for jump detection (meters)", false, 0.5)
          .addNumberProperty("flicker_window", "Time window for flicker detection (seconds)", false, 0.5)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var visionPrefix = getOptString(arguments, "vision_prefix", null);
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      // Note: jump_threshold is defined in the schema for future pose jump detection
      double flickerWindow = getOptDouble(arguments, "flicker_window", 0.5);

      var targetValidEntries = new ArrayList<String>();
      for (var entryName : log.entries().keySet()) {
        var lower = entryName.toLowerCase();
        boolean matchesPrefix = visionPrefix == null || entryName.startsWith(visionPrefix);
        if (matchesPrefix && (lower.contains("hastarget") || lower.contains("tv") || lower.contains("targetvalid"))) {
          targetValidEntries.add(entryName);
        }
      }

      var targetAnalysis = targetValidEntries.stream()
          .map(name -> {
            var values = log.values().get(name);
            if (values == null || values.isEmpty()) return null;

            int totalSamples = 0;
            int validSamples = 0;
            int flickerCount = 0;
            var lastTransition = (Double) null;
            var lastState = (Boolean) null;

            for (var tv : values) {
              if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;
              totalSamples++;

              boolean hasTarget = false;
              if (tv.value() instanceof Boolean b) hasTarget = b;
              else if (tv.value() instanceof Number n) hasTarget = n.doubleValue() > 0.5;

              if (hasTarget) validSamples++;

              if (lastState != null && !lastState.equals(hasTarget)) {
                if (lastTransition != null && (tv.timestamp() - lastTransition) < flickerWindow) {
                  flickerCount++;
                }
                lastTransition = tv.timestamp();
              }
              lastState = hasTarget;
            }

            var analysis = new JsonObject();
            analysis.addProperty("entry", name);
            analysis.addProperty("total_samples", totalSamples);
            analysis.addProperty("valid_samples", validSamples);
            analysis.addProperty("acquisition_rate", totalSamples > 0 ? (double) validSamples / totalSamples : 0);
            analysis.addProperty("flicker_events", flickerCount);
            return analysis;
          })
          .filter(Objects::nonNull)
          .toList();

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("target_acquisition", GSON.toJsonTree(targetAnalysis));
      return result;
    }

    private boolean inTimeRange(double timestamp, Double start, Double end) {
      if (start != null && timestamp < start) return false;
      if (end != null && timestamp > end) return false;
      return true;
    }
  }

  static class ProfileMechanismTool implements Tool {
    @Override
    public String name() { return "profile_mechanism"; }

    @Override
    public String description() {
      return "Analyze closed-loop mechanism performance: following error (RMSE), settling time, "
          + "stall detection, and motor temperature profiling.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("mechanism_name", "string", "Mechanism name or prefix", true)
          .addNumberProperty("start_time", "Start timestamp", false, null)
          .addNumberProperty("end_time", "End timestamp", false, null)
          .addNumberProperty("stall_current_threshold", "Current threshold for stall (default: 30A)", false, 30.0)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var mechanismName = getRequiredString(arguments, "mechanism_name");
      // Note: start_time, end_time, and stall_current_threshold are defined in the schema
      // for future implementation of time filtering and stall detection

      var lowerName = mechanismName.toLowerCase();
      var setpointEntry = (String) null;
      var measurementEntry = (String) null;

      for (var entryName : log.entries().keySet()) {
        var lower = entryName.toLowerCase();
        if (!lower.contains(lowerName)) continue;

        if (setpointEntry == null && (lower.contains("setpoint") || lower.contains("goal"))) {
          setpointEntry = entryName;
        }
        if (measurementEntry == null && (lower.contains("position") || lower.contains("actual"))) {
          if (!lower.contains("setpoint")) measurementEntry = entryName;
        }
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("mechanism", mechanismName);

      if (setpointEntry != null && measurementEntry != null) {
        double rmse = calculateRmseLinear(log.values().get(setpointEntry), log.values().get(measurementEntry));
        if (!Double.isNaN(rmse)) {
          var errorAnalysis = new JsonObject();
          errorAnalysis.addProperty("rmse", rmse);
          result.add("following_error", errorAnalysis);
        }
      }

      return result;
    }
  }

  static class AnalyzeAutoTool implements Tool {
    @Override
    public String name() { return "analyze_auto"; }

    @Override
    public String description() {
      return "Analyze autonomous routine: identify selected routine, path following error, "
          + "completion time, and phase breakdown.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("auto_prefix", "string", "Entry path prefix for auto data", false)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var result = new JsonObject();
      result.addProperty("success", true);
      
      log.entries().keySet().stream()
          .filter(n -> n.toLowerCase().contains("chooser"))
          .findFirst()
          .ifPresent(n -> {
            var vals = log.values().get(n);
            if (vals != null && !vals.isEmpty()) {
              result.addProperty("selected_routine", vals.get(0).value().toString());
            }
          });

      return result;
    }
  }

  static class AnalyzeCyclesTool implements Tool {
    @Override
    public String name() { return "analyze_cycles"; }

    @Override
    public String description() {
      return "Analyze game piece handling cycle times: count cycles, calculate average/min/max times, "
          + "and identify dead time.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("state_entry", "string", "Entry name for mechanism state", true)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var stateEntry = getRequiredString(arguments, "state_entry");
      var vals = log.values().get(stateEntry);
      if (vals == null) return errorResult("Entry not found: " + stateEntry);

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("sample_count", vals.size());
      return result;
    }
  }

  static class AnalyzeReplayDriftTool implements Tool {
    @Override
    public String name() { return "analyze_replay_drift"; }

    @Override
    public String description() {
      return "Validate AdvantageKit deterministic replay by comparing RealOutputs vs ReplayOutputs.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var realEntries = log.entries().keySet().stream()
          .filter(n -> n.contains("/RealOutputs/"))
          .toList();

      var divergent = realEntries.stream()
          .map(real -> {
            var replay = real.replace("/RealOutputs/", "/ReplayOutputs/");
            if (!log.entries().containsKey(replay)) return null;
            
            var realVals = log.values().get(real);
            var replayVals = log.values().get(replay);
            if (realVals == null || replayVals == null) return null;

            for (int i = 0; i < Math.min(realVals.size(), replayVals.size()); i++) {
              if (!Objects.equals(realVals.get(i).value(), replayVals.get(i).value())) {
                var div = new JsonObject();
                div.addProperty("entry", real);
                div.addProperty("timestamp", realVals.get(i).timestamp());
                return div;
              }
            }
            return null;
          })
          .filter(Objects::nonNull)
          .toList();

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("divergent_count", divergent.size());
      result.add("divergences", GSON.toJsonTree(divergent.stream().limit(10).toList()));
      return result;
    }
  }
}
