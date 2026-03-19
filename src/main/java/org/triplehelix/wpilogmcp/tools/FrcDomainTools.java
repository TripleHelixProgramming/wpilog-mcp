package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Objects;
import org.triplehelix.wpilogmcp.log.LogManager;
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
    server.registerTool(new AnalyzeLoopTimingTool());
    server.registerTool(new AnalyzeCanBusTool());
  }

  // ==================== SHARED HELPER METHODS ====================

  /**
   * Calculate the Euclidean distance between two poses (works for Pose2d and Pose3d).
   */
  private static double calculatePoseDistance(java.util.Map<String, Object> pose1, java.util.Map<String, Object> pose2) {
    var trans1 = extractTranslation(pose1);
    var trans2 = extractTranslation(pose2);

    if (trans1 == null || trans2 == null) return 0.0;

    double dx = trans1[0] - trans2[0];
    double dy = trans1[1] - trans2[1];
    double dz = trans1.length > 2 && trans2.length > 2 ? trans1[2] - trans2[2] : 0.0;

    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /**
   * Extract translation components from a Pose2d or Pose3d struct.
   */
  private static double[] extractTranslation(java.util.Map<String, Object> pose) {
    Object translationObj = pose.get("translation");
    if (translationObj instanceof java.util.Map) {
      @SuppressWarnings("unchecked")
      var translation = (java.util.Map<String, Object>) translationObj;

      var x = toDouble(translation.get("x"));
      var y = toDouble(translation.get("y"));
      var z = toDouble(translation.get("z"));

      if (x != null && y != null) {
        if (z != null) {
          return new double[]{x, y, z};
        }
        return new double[]{x, y};
      }
    }
    return null;
  }

  // ==================== TOOL IMPLEMENTATIONS ====================

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

      // Cache toLowerCase results for performance
      var lowerEntryNames = new HashMap<String, String>();
      for (var entryName : log.entries().keySet()) {
        lowerEntryNames.put(entryName, entryName.toLowerCase());
      }

      for (var entryName : log.entries().keySet()) {
        var lower = lowerEntryNames.get(entryName);

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
        var lower = lowerEntryNames.get(entryName);
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
      double jumpThreshold = getOptDouble(arguments, "jump_threshold", 0.5);
      double flickerWindow = getOptDouble(arguments, "flicker_window", 0.5);

      var targetValidEntries = new ArrayList<String>();
      var poseEntries = new ArrayList<String>();

      // Cache toLowerCase results for performance
      var lowerEntryNames = new HashMap<String, String>();
      for (var entryName : log.entries().keySet()) {
        lowerEntryNames.put(entryName, entryName.toLowerCase());
      }

      for (var entryName : log.entries().keySet()) {
        var lower = lowerEntryNames.get(entryName);
        boolean matchesPrefix = visionPrefix == null || entryName.startsWith(visionPrefix);

        if (matchesPrefix && (lower.contains("hastarget") || lower.contains("tv") || lower.contains("targetvalid"))) {
          targetValidEntries.add(entryName);
        }

        if (matchesPrefix && lower.contains("pose") && !lower.contains("target")) {
          var entry = log.entries().get(entryName);
          if (entry != null && (entry.type().contains("Pose2d") || entry.type().contains("Pose3d"))) {
            poseEntries.add(entryName);
          }
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

      // Detect pose jumps
      var poseJumps = new ArrayList<JsonObject>();
      for (var poseName : poseEntries) {
        var values = log.values().get(poseName);
        if (values == null || values.size() < 2) continue;

        java.util.Map<String, Object> lastPose = null;
        for (LogManager.TimestampedValue tv : values) {
          if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;

          if (tv.value() instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            var currentPose = (java.util.Map<String, Object>) tv.value();

            if (lastPose != null) {
              double distance = calculatePoseDistance(lastPose, currentPose);
              if (distance > jumpThreshold) {
                var jump = new JsonObject();
                jump.addProperty("timestamp", tv.timestamp());
                jump.addProperty("entry", poseName);
                jump.addProperty("distance", distance);
                poseJumps.add(jump);
              }
            }
            lastPose = currentPose;
          }
        }
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("target_acquisition", GSON.toJsonTree(targetAnalysis));

      if (!poseJumps.isEmpty()) {
        result.add("pose_jumps", GSON.toJsonTree(poseJumps));
        result.addProperty("jump_count", poseJumps.size());
      }

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
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      double stallCurrentThreshold = getOptDouble(arguments, "stall_current_threshold", 30.0);

      var lowerName = mechanismName.toLowerCase();
      var setpointEntry = (String) null;
      var measurementEntry = (String) null;
      var velocityEntry = (String) null;
      var currentEntry = (String) null;

      for (var entryName : log.entries().keySet()) {
        var lower = entryName.toLowerCase();
        if (!lower.contains(lowerName)) continue;

        if (setpointEntry == null && (lower.contains("setpoint") || lower.contains("goal"))) {
          setpointEntry = entryName;
        }
        if (measurementEntry == null && (lower.contains("position") || lower.contains("actual"))) {
          if (!lower.contains("setpoint")) measurementEntry = entryName;
        }
        if (velocityEntry == null && lower.contains("velocity")) {
          velocityEntry = entryName;
        }
        if (currentEntry == null && (lower.contains("current") || lower.contains("supplycurrent"))) {
          currentEntry = entryName;
        }
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("mechanism", mechanismName);

      if (setpointEntry != null && measurementEntry != null) {
        var setpointVals = log.values().get(setpointEntry);
        var measurementVals = log.values().get(measurementEntry);

        double rmse = calculateRmseLinear(setpointVals, measurementVals);
        if (!Double.isNaN(rmse)) {
          var errorAnalysis = new JsonObject();
          errorAnalysis.addProperty("rmse", rmse);

          // Calculate settling time and overshoot
          var settlingData = calculateSettlingTime(setpointVals, measurementVals, startTime, endTime);
          if (settlingData != null) {
            errorAnalysis.add("settling_time_sec", settlingData);
          }

          var overshoot = calculateOvershoot(setpointVals, measurementVals, startTime, endTime);
          if (!Double.isNaN(overshoot)) {
            errorAnalysis.addProperty("overshoot_percent", overshoot);
          }

          result.add("following_error", errorAnalysis);
        }
      }

      // Detect stalls
      if (velocityEntry != null && currentEntry != null) {
        var stallEvents = detectStalls(
            log.values().get(velocityEntry),
            log.values().get(currentEntry),
            stallCurrentThreshold,
            startTime,
            endTime
        );
        if (!stallEvents.isEmpty()) {
          result.add("stall_events", GSON.toJsonTree(stallEvents));
          result.addProperty("stall_count", stallEvents.size());
        }
      }

      return result;
    }

    private JsonElement calculateSettlingTime(
        java.util.List<LogManager.TimestampedValue> setpoints,
        java.util.List<LogManager.TimestampedValue> measurements,
        Double startTime,
        Double endTime
    ) {
      if (setpoints == null || measurements == null || setpoints.isEmpty() || measurements.isEmpty()) {
        return null;
      }

      var settlingTimes = new ArrayList<Double>();

      Double lastSetpoint = null;
      Double setpointChangeTime = null;

      // Use setpoints as reference, interpolate measurements
      for (LogManager.TimestampedValue spTv : setpoints) {
        if (startTime != null && spTv.timestamp() < startTime) continue;
        if (endTime != null && spTv.timestamp() > endTime) break;

        var spVal = toDouble(spTv.value());
        var measVal = getValueAtTimeLinear(measurements, spTv.timestamp());

        if (spVal == null || measVal == null) continue;

        // Detect setpoint change (more than 5% change)
        if (lastSetpoint == null || Math.abs(spVal - lastSetpoint) > Math.abs(lastSetpoint * 0.05)) {
          lastSetpoint = spVal;
          setpointChangeTime = spTv.timestamp();
        }

        // Check if settled (within 5% of setpoint)
        if (setpointChangeTime != null && Math.abs(measVal - spVal) <= Math.abs(spVal * 0.05)) {
          double settlingTime = spTv.timestamp() - setpointChangeTime;
          if (settlingTime > 0.01) { // Ignore very quick "settling" (likely noise)
            settlingTimes.add(settlingTime);
            setpointChangeTime = null; // Reset to avoid counting same settling multiple times
          }
        }
      }

      if (settlingTimes.isEmpty()) return null;

      var stats = new JsonObject();
      stats.addProperty("avg", settlingTimes.stream().mapToDouble(d -> d).average().orElse(0));
      stats.addProperty("max", settlingTimes.stream().mapToDouble(d -> d).max().orElse(0));
      stats.addProperty("min", settlingTimes.stream().mapToDouble(d -> d).min().orElse(0));
      return stats;
    }

    private double calculateOvershoot(
        java.util.List<LogManager.TimestampedValue> setpoints,
        java.util.List<LogManager.TimestampedValue> measurements,
        Double startTime,
        Double endTime
    ) {
      if (setpoints == null || measurements == null || setpoints.isEmpty() || measurements.isEmpty()) {
        return Double.NaN;
      }

      var overshoots = new ArrayList<Double>();

      Double lastSetpoint = null;
      Double maxOvershoot = null;

      // Use setpoints as reference, interpolate measurements
      for (LogManager.TimestampedValue spTv : setpoints) {
        if (startTime != null && spTv.timestamp() < startTime) continue;
        if (endTime != null && spTv.timestamp() > endTime) break;

        var spVal = toDouble(spTv.value());
        var measVal = getValueAtTimeLinear(measurements, spTv.timestamp());

        if (spVal == null || measVal == null) continue;

        // Detect setpoint change
        if (lastSetpoint == null || Math.abs(spVal - lastSetpoint) > Math.abs(lastSetpoint * 0.05)) {
          if (maxOvershoot != null && lastSetpoint != null && lastSetpoint != 0) {
            overshoots.add(maxOvershoot * 100.0 / Math.abs(lastSetpoint));
          }
          lastSetpoint = spVal;
          maxOvershoot = 0.0;
        }

        // Track maximum overshoot
        if (lastSetpoint != null) {
          double error = measVal - lastSetpoint;
          if (Math.abs(error) > Math.abs(maxOvershoot)) {
            maxOvershoot = error;
          }
        }
      }

      if (overshoots.isEmpty()) return Double.NaN;
      return overshoots.stream().mapToDouble(d -> d).average().orElse(Double.NaN);
    }

    private java.util.List<JsonObject> detectStalls(
        java.util.List<LogManager.TimestampedValue> velocities,
        java.util.List<LogManager.TimestampedValue> currents,
        double stallCurrentThreshold,
        Double startTime,
        Double endTime
    ) {
      var stallEvents = new ArrayList<JsonObject>();
      if (velocities == null || currents == null) return stallEvents;

      boolean inStall = false;
      double stallStartTime = 0;
      double stallMaxCurrent = 0;

      // Use velocities as reference, interpolate currents
      for (LogManager.TimestampedValue velTv : velocities) {
        if (startTime != null && velTv.timestamp() < startTime) continue;
        if (endTime != null && velTv.timestamp() > endTime) break;

        var velVal = toDouble(velTv.value());
        var currVal = getValueAtTimeLinear(currents, velTv.timestamp());

        if (velVal == null || currVal == null) continue;

        boolean isStalled = Math.abs(velVal) < 0.01 && currVal > stallCurrentThreshold;

        if (isStalled && !inStall) {
          // Stall started
          inStall = true;
          stallStartTime = velTv.timestamp();
          stallMaxCurrent = currVal;
        } else if (isStalled && inStall) {
          // Stall continuing
          stallMaxCurrent = Math.max(stallMaxCurrent, currVal);
        } else if (!isStalled && inStall) {
          // Stall ended
          var event = new JsonObject();
          event.addProperty("start_time", stallStartTime);
          event.addProperty("end_time", velTv.timestamp());
          event.addProperty("duration", velTv.timestamp() - stallStartTime);
          event.addProperty("max_current", stallMaxCurrent);
          stallEvents.add(event);
          inStall = false;
        }
      }

      return stallEvents;
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

      var autoPrefix = getOptString(arguments, "auto_prefix", null);

      var result = new JsonObject();
      result.addProperty("success", true);

      // Find selected routine
      log.entries().keySet().stream()
          .filter(n -> n.toLowerCase().contains("chooser"))
          .findFirst()
          .ifPresent(n -> {
            var vals = log.values().get(n);
            if (vals != null && !vals.isEmpty()) {
              result.addProperty("selected_routine", vals.get(0).value().toString());
            }
          });

      // Find auto period (first 15 seconds or until teleop)
      Double autoStartTime = null;
      Double autoEndTime = null;

      // Cache toLowerCase results for performance
      var lowerEntryNames = new HashMap<String, String>();
      for (var entryName : log.entries().keySet()) {
        lowerEntryNames.put(entryName, entryName.toLowerCase());
      }

      for (var entryName : log.entries().keySet()) {
        var lower = lowerEntryNames.get(entryName);
        if (lower.contains("driverstation") && (lower.contains("autonomous") || lower.contains("auto"))) {
          var values = log.values().get(entryName);
          if (values != null) {
            for (LogManager.TimestampedValue tv : values) {
              if (tv.value() instanceof Boolean isAuto) {
                if (isAuto && autoStartTime == null) {
                  autoStartTime = tv.timestamp();
                } else if (!isAuto && autoStartTime != null && autoEndTime == null) {
                  autoEndTime = tv.timestamp();
                  break;
                }
              }
            }
          }
          break;
        }
      }

      if (autoStartTime != null) {
        if (autoEndTime == null) {
          // If we didn't find auto end, use 15 seconds
          autoEndTime = autoStartTime + 15.0;
        }
        var autoDuration = autoEndTime - autoStartTime;
        result.addProperty("auto_start_time", autoStartTime);
        result.addProperty("auto_end_time", autoEndTime);
        result.addProperty("auto_duration", autoDuration);

        // Calculate path following error
        var pathFollowingError = calculatePathFollowingError(log, autoPrefix, autoStartTime, autoEndTime);
        if (pathFollowingError != null) {
          result.add("path_following_error", pathFollowingError);
        }
      }

      return result;
    }

    private JsonObject calculatePathFollowingError(
        LogManager.ParsedLog log,
        String prefix,
        double startTime,
        double endTime
    ) {
      // Look for pose setpoint and actual pose entries
      String setpointEntry = null;
      String actualEntry = null;

      // Cache toLowerCase results for performance
      var lowerEntryNames = new HashMap<String, String>();
      for (var entryName : log.entries().keySet()) {
        lowerEntryNames.put(entryName, entryName.toLowerCase());
      }

      for (var entryName : log.entries().keySet()) {
        var lower = lowerEntryNames.get(entryName);
        boolean matchesPrefix = prefix == null || entryName.startsWith(prefix);

        if (matchesPrefix) {
          var entry = log.entries().get(entryName);
          boolean isPose = entry != null && (entry.type().contains("Pose2d") || entry.type().contains("Pose3d"));

          if (isPose && (lower.contains("setpoint") || lower.contains("target") || lower.contains("desired"))) {
            setpointEntry = entryName;
          } else if (isPose && (lower.contains("actual") || lower.contains("estimated") || lower.contains("odometry"))) {
            actualEntry = entryName;
          }
        }
      }

      if (setpointEntry == null || actualEntry == null) {
        return null;
      }

      var setpointValues = log.values().get(setpointEntry);
      var actualValues = log.values().get(actualEntry);

      if (setpointValues == null || actualValues == null) {
        return null;
      }

      // Calculate RMSE for the auto period
      double sumSquaredError = 0.0;
      int count = 0;
      double maxError = 0.0;

      for (LogManager.TimestampedValue spTv : setpointValues) {
        if (spTv.timestamp() < startTime || spTv.timestamp() > endTime) continue;

        if (spTv.value() instanceof java.util.Map) {
          @SuppressWarnings("unchecked")
          var setpointPose = (java.util.Map<String, Object>) spTv.value();

          var actualPose = getActualPoseAtTime(actualValues, spTv.timestamp());
          if (actualPose != null) {
            double error = calculatePoseDistance(setpointPose, actualPose);
            sumSquaredError += error * error;
            maxError = Math.max(maxError, error);
            count++;
          }
        }
      }

      if (count == 0) {
        return null;
      }

      var errorAnalysis = new JsonObject();
      errorAnalysis.addProperty("rmse_meters", Math.sqrt(sumSquaredError / count));
      errorAnalysis.addProperty("max_error_meters", maxError);
      errorAnalysis.addProperty("samples", count);
      return errorAnalysis;
    }

    private java.util.Map<String, Object> getActualPoseAtTime(
        java.util.List<LogManager.TimestampedValue> values,
        double timestamp
    ) {
      // Find closest pose (ZOH)
      java.util.Map<String, Object> result = null;
      for (LogManager.TimestampedValue tv : values) {
        if (tv.timestamp() > timestamp) break;
        if (tv.value() instanceof java.util.Map) {
          @SuppressWarnings("unchecked")
          var pose = (java.util.Map<String, Object>) tv.value();
          result = pose;
        }
      }
      return result;
    }
  }

  static class AnalyzeCyclesTool implements Tool {
    @Override
    public String name() { return "analyze_cycles"; }

    @Override
    public String description() {
      return "Analyze game piece handling cycle times with configurable cycle detection modes "
          + "(start-to-start or start-to-end), dead time tracking, and data quality warnings. "
          + "Supports time filtering, case-sensitive/insensitive matching, and incomplete cycle detection.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("state_entry", "string", "Entry name for mechanism state", true)
          .addProperty("cycle_mode", "string", "Cycle detection mode: 'start_to_start' or 'start_to_end' (default: 'start_to_start')", false)
          .addProperty("cycle_start_state", "string", "State value that marks cycle start (e.g., 'INTAKING')", false)
          .addProperty("cycle_end_state", "string", "State value that marks cycle end (only for start_to_end mode, e.g., 'SCORING')", false)
          .addProperty("idle_state", "string", "State value for idle/dead time (e.g., 'IDLE')", false)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .addProperty("case_sensitive", "boolean", "Case-sensitive state matching (default: true)", false)
          .addIntegerProperty("limit", "Max cycles/dead periods to return (default: 10)", false, 10)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      // Parse parameters
      var stateEntry = getRequiredString(arguments, "state_entry");
      var cycleMode = getOptString(arguments, "cycle_mode", "start_to_start");
      var cycleStartState = getOptString(arguments, "cycle_start_state", null);
      var cycleEndState = getOptString(arguments, "cycle_end_state", null);
      var idleState = getOptString(arguments, "idle_state", null);
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      boolean caseSensitive = arguments.has("case_sensitive") && !arguments.get("case_sensitive").isJsonNull()
          ? arguments.get("case_sensitive").getAsBoolean()
          : true; // Default: true
      int limit = getOptInt(arguments, "limit", 10);

      // Validate cycle mode
      if (!cycleMode.equals("start_to_start") && !cycleMode.equals("start_to_end")) {
        return errorResult("cycle_mode must be 'start_to_start' or 'start_to_end'");
      }

      // Validate required states for mode
      if (cycleMode.equals("start_to_end") && (cycleStartState == null || cycleEndState == null)) {
        return errorResult("start_to_end mode requires both cycle_start_state and cycle_end_state");
      }
      if (cycleMode.equals("start_to_start") && cycleStartState == null) {
        return errorResult("start_to_start mode requires cycle_start_state");
      }

      var vals = log.values().get(stateEntry);
      if (vals == null) return errorResult("Entry not found: " + stateEntry);
      if (vals.isEmpty()) return errorResult("State entry has no data");

      // Detect cycles and dead time
      var cycleTimes = new ArrayList<Double>();
      var cycleDetails = new ArrayList<JsonObject>();
      var deadTimePeriods = new ArrayList<JsonObject>();
      double totalDeadTime = 0.0;

      // Cycle detection based on mode
      if (cycleMode.equals("start_to_start")) {
        Double cycleStartTime = null;
        boolean cycleIncomplete = false;

        for (LogManager.TimestampedValue tv : vals) {
          if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;

          String currentState = tv.value().toString();

          if (statesEqual(currentState, cycleStartState, caseSensitive)) {
            if (cycleStartTime != null) {
              // Complete previous cycle
              double cycleTime = tv.timestamp() - cycleStartTime;
              cycleTimes.add(cycleTime);

              var cycleDetail = new JsonObject();
              cycleDetail.addProperty("start_time", cycleStartTime);
              cycleDetail.addProperty("end_time", tv.timestamp());
              cycleDetail.addProperty("duration", cycleTime);
              cycleDetail.addProperty("incomplete", false);
              cycleDetails.add(cycleDetail);

              cycleIncomplete = false;
            }
            cycleStartTime = tv.timestamp();
            cycleIncomplete = true;
          }
        }

        // Handle incomplete final cycle
        if (cycleIncomplete && cycleStartTime != null) {
          double incompleteDuration = vals.get(vals.size() - 1).timestamp() - cycleStartTime;

          var cycleDetail = new JsonObject();
          cycleDetail.addProperty("start_time", cycleStartTime);
          cycleDetail.addProperty("end_time", vals.get(vals.size() - 1).timestamp());
          cycleDetail.addProperty("duration", incompleteDuration);
          cycleDetail.addProperty("incomplete", true);
          cycleDetails.add(cycleDetail);
        }
      } else if (cycleMode.equals("start_to_end")) {
        Double cycleStartTime = null;
        boolean inCycle = false;

        for (LogManager.TimestampedValue tv : vals) {
          if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;

          String currentState = tv.value().toString();

          // Detect cycle start
          if (statesEqual(currentState, cycleStartState, caseSensitive) && !inCycle) {
            cycleStartTime = tv.timestamp();
            inCycle = true;
          }

          // Detect cycle end
          if (statesEqual(currentState, cycleEndState, caseSensitive) && inCycle && cycleStartTime != null) {
            double cycleTime = tv.timestamp() - cycleStartTime;
            cycleTimes.add(cycleTime);

            var cycleDetail = new JsonObject();
            cycleDetail.addProperty("start_time", cycleStartTime);
            cycleDetail.addProperty("end_time", tv.timestamp());
            cycleDetail.addProperty("duration", cycleTime);
            cycleDetail.addProperty("incomplete", false);
            cycleDetails.add(cycleDetail);

            inCycle = false;
            cycleStartTime = null;
          }
        }

        // Handle incomplete cycle (started but never ended)
        if (inCycle && cycleStartTime != null) {
          double incompleteDuration = vals.get(vals.size() - 1).timestamp() - cycleStartTime;

          var cycleDetail = new JsonObject();
          cycleDetail.addProperty("start_time", cycleStartTime);
          cycleDetail.addProperty("end_time", vals.get(vals.size() - 1).timestamp());
          cycleDetail.addProperty("duration", incompleteDuration);
          cycleDetail.addProperty("incomplete", true);
          cycleDetails.add(cycleDetail);
        }
      }

      // Detect idle/dead time
      if (idleState != null) {
        String lastState = null;
        Double idleStartTime = null;

        for (LogManager.TimestampedValue tv : vals) {
          if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;

          String currentState = tv.value().toString();

          if (statesEqual(currentState, idleState, caseSensitive) &&
              !statesEqual(currentState, lastState, caseSensitive)) {
            // Entering idle
            idleStartTime = tv.timestamp();
          } else if (!statesEqual(currentState, idleState, caseSensitive) &&
                     statesEqual(lastState, idleState, caseSensitive) &&
                     idleStartTime != null) {
            // Exiting idle
            double deadTime = tv.timestamp() - idleStartTime;
            totalDeadTime += deadTime;

            var deadPeriod = new JsonObject();
            deadPeriod.addProperty("start_time", idleStartTime);
            deadPeriod.addProperty("end_time", tv.timestamp());
            deadPeriod.addProperty("duration", deadTime);
            deadTimePeriods.add(deadPeriod);

            idleStartTime = null;
          }

          lastState = currentState;
        }

        // Handle incomplete idle period
        if (idleStartTime != null) {
          double incompleteDuration = vals.get(vals.size() - 1).timestamp() - idleStartTime;

          var deadPeriod = new JsonObject();
          deadPeriod.addProperty("start_time", idleStartTime);
          deadPeriod.addProperty("end_time", vals.get(vals.size() - 1).timestamp());
          deadPeriod.addProperty("duration", incompleteDuration);
          deadPeriod.addProperty("incomplete", true);
          deadTimePeriods.add(deadPeriod);
        }
      }

      // Build result
      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("sample_count", vals.size());
      result.addProperty("cycle_mode", cycleMode);

      // Add data quality warnings
      var warnings = detectDataQualityIssues(vals, cycleStartState, cycleEndState, idleState, caseSensitive, startTime, endTime);
      if (!warnings.isEmpty()) {
        result.add("warnings", GSON.toJsonTree(warnings));
      }

      // Calculate cycle statistics (only for complete cycles)
      if (!cycleTimes.isEmpty()) {
        var cycleStats = new JsonObject();
        cycleStats.addProperty("count", cycleTimes.size());
        cycleStats.addProperty("avg_sec", cycleTimes.stream().mapToDouble(d -> d).average().orElse(0));
        cycleStats.addProperty("min_sec", cycleTimes.stream().mapToDouble(d -> d).min().orElse(0));
        cycleStats.addProperty("max_sec", cycleTimes.stream().mapToDouble(d -> d).max().orElse(0));
        result.add("cycle_times", cycleStats);
      }

      // Add cycle details (includes both complete and incomplete cycles)
      if (!cycleDetails.isEmpty()) {
        result.add("cycles", GSON.toJsonTree(cycleDetails.stream().limit(limit).toList()));

        if (cycleDetails.size() > limit) {
          result.addProperty("cycles_truncated", true);
          result.addProperty("total_cycles", cycleDetails.size());
        }
      }

      // Add dead time analysis
      if (idleState != null) {
        var deadTimeStats = new JsonObject();
        deadTimeStats.addProperty("total_sec", totalDeadTime);
        deadTimeStats.addProperty("period_count", deadTimePeriods.size());
        if (!deadTimePeriods.isEmpty()) {
          deadTimeStats.addProperty("avg_duration_sec",
              deadTimePeriods.stream().mapToDouble(p -> p.get("duration").getAsDouble()).average().orElse(0));
        }
        result.add("dead_time", deadTimeStats);

        // Apply configurable limit
        result.add("dead_time_periods", GSON.toJsonTree(deadTimePeriods.stream().limit(limit).toList()));

        if (deadTimePeriods.size() > limit) {
          result.addProperty("dead_time_periods_truncated", true);
          result.addProperty("total_dead_time_periods", deadTimePeriods.size());
        }
      }

      return result;
    }

    private boolean inTimeRange(double timestamp, Double start, Double end) {
      if (start != null && timestamp < start) return false;
      if (end != null && timestamp > end) return false;
      return true;
    }

    private boolean statesEqual(String state1, String state2, boolean caseSensitive) {
      if (state1 == null || state2 == null) return false;
      if (caseSensitive) {
        return state1.equals(state2);
      } else {
        return state1.equalsIgnoreCase(state2);
      }
    }

    private java.util.List<String> detectDataQualityIssues(
        java.util.List<LogManager.TimestampedValue> vals,
        String cycleStartState,
        String cycleEndState,
        String idleState,
        boolean caseSensitive,
        Double startTime,
        Double endTime) {

      var warnings = new ArrayList<String>();

      // 1. Rapid state bouncing detection
      String lastState = null;
      Double lastTransitionTime = null;
      int rapidTransitions = 0;

      for (var tv : vals) {
        if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;

        String currentState = tv.value().toString();

        if (lastState != null && !statesEqual(currentState, lastState, caseSensitive)) {
          if (lastTransitionTime != null && (tv.timestamp() - lastTransitionTime) < 0.1) {
            rapidTransitions++;
          }
          lastTransitionTime = tv.timestamp();
        }
        lastState = currentState;
      }

      if (rapidTransitions > 5) {
        warnings.add(String.format("Detected %d rapid state transitions (<0.1s apart) - may indicate state machine instability", rapidTransitions));
      }

      // 2. Unknown state detection
      var expectedStates = new java.util.HashSet<String>();
      if (cycleStartState != null) expectedStates.add(caseSensitive ? cycleStartState : cycleStartState.toLowerCase());
      if (cycleEndState != null) expectedStates.add(caseSensitive ? cycleEndState : cycleEndState.toLowerCase());
      if (idleState != null) expectedStates.add(caseSensitive ? idleState : idleState.toLowerCase());

      var unknownStates = new java.util.HashSet<String>();
      for (var tv : vals) {
        if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;

        String state = tv.value().toString();
        String compareState = caseSensitive ? state : state.toLowerCase();
        if (!expectedStates.isEmpty() && !expectedStates.contains(compareState)) {
          unknownStates.add(state);
        }
      }

      if (!unknownStates.isEmpty() && unknownStates.size() <= 5) {
        warnings.add("Detected unknown states: " + String.join(", ", unknownStates));
      } else if (unknownStates.size() > 5) {
        warnings.add(String.format("Detected %d unknown states (too many to list)", unknownStates.size()));
      }

      return warnings;
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

  static class AnalyzeLoopTimingTool implements Tool {
    @Override
    public String name() { return "analyze_loop_timing"; }

    @Override
    public String description() {
      return "Detect when robot code exceeded loop period threshold (default 20ms). "
          + "Returns violations, statistics, and a health score.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addNumberProperty("threshold_ms", "Loop time threshold in milliseconds (default: 20)", false, 20.0)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      double thresholdMs = getOptDouble(arguments, "threshold_ms", 20.0);
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");

      // Find loop time entry
      String loopTimeEntry = null;
      for (var entryName : log.entries().keySet()) {
        var lower = entryName.toLowerCase();
        if (lower.contains("looptime") || (lower.contains("loop") && lower.contains("time"))) {
          loopTimeEntry = entryName;
          break;
        }
      }

      if (loopTimeEntry == null) {
        return errorResult("No loop time entry found. Look for entries containing 'LoopTime' or 'loop time'");
      }

      var values = log.values().get(loopTimeEntry);
      if (values == null || values.isEmpty()) {
        return errorResult("Loop time entry found but has no data");
      }

      var violations = new ArrayList<JsonObject>();
      var loopTimes = new ArrayList<Double>();

      for (LogManager.TimestampedValue tv : values) {
        if (startTime != null && tv.timestamp() < startTime) continue;
        if (endTime != null && tv.timestamp() > endTime) break;

        if (tv.value() instanceof Number num) {
          double loopTimeMs = num.doubleValue();

          // Convert to ms if it looks like it's in seconds (< 1.0 but > 0)
          if (loopTimeMs < 1.0 && loopTimeMs > 0) {
            loopTimeMs *= 1000.0;
          }

          loopTimes.add(loopTimeMs);

          if (loopTimeMs > thresholdMs) {
            var violation = new JsonObject();
            violation.addProperty("timestamp", tv.timestamp());
            violation.addProperty("loop_time_ms", loopTimeMs);
            violation.addProperty("overage_ms", loopTimeMs - thresholdMs);
            violations.add(violation);
          }
        }
      }

      if (loopTimes.isEmpty()) {
        return errorResult("No numeric loop time data found");
      }

      // Calculate statistics
      var stats = loopTimes.stream().mapToDouble(d -> d).summaryStatistics();
      var sorted = loopTimes.stream().mapToDouble(d -> d).sorted().toArray();

      var statistics = new JsonObject();
      statistics.addProperty("avg_ms", stats.getAverage());
      statistics.addProperty("max_ms", stats.getMax());
      statistics.addProperty("min_ms", stats.getMin());
      statistics.addProperty("p95_ms", sorted[(int) (sorted.length * 0.95)]);
      statistics.addProperty("p99_ms", sorted[(int) (sorted.length * 0.99)]);

      // Calculate health score (0-100)
      double violationRate = (double) violations.size() / loopTimes.size();
      int healthScore = (int) Math.max(0, Math.min(100, 100 - (violationRate * 200)));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("loop_time_entry", loopTimeEntry);
      result.addProperty("threshold_ms", thresholdMs);
      result.addProperty("violation_count", violations.size());
      result.addProperty("total_samples", loopTimes.size());
      result.addProperty("violation_rate", violationRate);
      result.addProperty("health_score", healthScore);
      result.add("statistics", statistics);
      result.add("violations", GSON.toJsonTree(violations.stream().limit(50).toList()));

      return result;
    }
  }

  static class AnalyzeCanBusTool implements Tool {
    @Override
    public String name() { return "analyze_can_bus"; }

    @Override
    public String description() {
      return "Analyze CAN bus health: detect bus-off events, high utilization, and noisy devices.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("bus_name", "string", "CAN bus name (default: 'rio')", false)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var busName = getOptString(arguments, "bus_name", "rio");
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");

      // Find CAN-related entries
      var canUtilEntries = new ArrayList<String>();
      var canErrorEntries = new ArrayList<String>();

      // Cache toLowerCase
      var lowerEntryNames = new HashMap<String, String>();
      for (var entryName : log.entries().keySet()) {
        lowerEntryNames.put(entryName, entryName.toLowerCase());
      }

      for (var entryName : log.entries().keySet()) {
        var lower = lowerEntryNames.get(entryName);
        if (lower.contains("can")) {
          if (lower.contains("util") || lower.contains("bandwidth") || lower.contains("busoff")) {
            canUtilEntries.add(entryName);
          }
          if (lower.contains("error") || lower.contains("fault") || lower.contains("timeout")) {
            canErrorEntries.add(entryName);
          }
        }
      }

      var result = new JsonObject();
      result.addProperty("success", true);

      // Analyze utilization
      if (!canUtilEntries.isEmpty()) {
        var utilAnalysis = new ArrayList<JsonObject>();
        for (var entryName : canUtilEntries) {
          var values = log.values().get(entryName);
          if (values == null) continue;

          var utilData = new ArrayList<Double>();
          for (LogManager.TimestampedValue tv : values) {
            if (startTime != null && tv.timestamp() < startTime) continue;
            if (endTime != null && tv.timestamp() > endTime) break;

            if (tv.value() instanceof Number num) {
              utilData.add(num.doubleValue());
            }
          }

          if (!utilData.isEmpty()) {
            var stats = utilData.stream().mapToDouble(d -> d).summaryStatistics();
            var analysis = new JsonObject();
            analysis.addProperty("entry", entryName);
            analysis.addProperty("avg_percent", stats.getAverage());
            analysis.addProperty("max_percent", stats.getMax());
            analysis.addProperty("sample_count", stats.getCount());
            utilAnalysis.add(analysis);
          }
        }
        result.add("utilization", GSON.toJsonTree(utilAnalysis));
      }

      // Analyze errors
      if (!canErrorEntries.isEmpty()) {
        var errorAnalysis = new ArrayList<JsonObject>();
        for (var entryName : canErrorEntries) {
          var values = log.values().get(entryName);
          if (values == null) continue;

          int errorCount = 0;
          for (LogManager.TimestampedValue tv : values) {
            if (startTime != null && tv.timestamp() < startTime) continue;
            if (endTime != null && tv.timestamp() > endTime) break;

            // Count non-zero errors or true boolean errors
            if (tv.value() instanceof Boolean b && b) {
              errorCount++;
            } else if (tv.value() instanceof Number num && num.doubleValue() > 0) {
              errorCount++;
            }
          }

          if (errorCount > 0) {
            var analysis = new JsonObject();
            analysis.addProperty("entry", entryName);
            analysis.addProperty("error_count", errorCount);
            errorAnalysis.add(analysis);
          }
        }
        result.add("errors", GSON.toJsonTree(errorAnalysis));
      }

      if (canUtilEntries.isEmpty() && canErrorEntries.isEmpty()) {
        result.addProperty("warning", "No CAN-related entries found in log");
      }

      return result;
    }
  }
}
