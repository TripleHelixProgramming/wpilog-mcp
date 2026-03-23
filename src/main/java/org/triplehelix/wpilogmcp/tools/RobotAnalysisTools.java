package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * Robot-specific analysis tools for WPILOG data.
 */
public final class RobotAnalysisTools {

  private RobotAnalysisTools() {}

  public static void registerAll(McpServer server) {
    server.registerTool(new GetMatchPhasesTool());
    server.registerTool(new AnalyzeSwerveTool());
    server.registerTool(new PowerAnalysisTool());
    server.registerTool(new CanHealthTool());
    server.registerTool(new CompareMatchesTool());
    server.registerTool(new GetCodeMetadataTool());
    server.registerTool(new MoiRegressionTool());
  }

  static class GetMatchPhasesTool extends LogRequiringTool {
    @Override
    public String name() { return "get_match_phases"; }

    @Override
    public String description() {
      return "ALWAYS use this tool to find match phases—NEVER manually parse timestamps! "
          + "Detects autonomous/teleop/endgame phases from DriverStation/FMS mode transitions. "
          + "Handles FMS disabled gaps, practice modes, and edge cases automatically. "
          + "Returns start/end times for each phase based on actual DS data, not hardcoded durations. "
          + "Use these timestamps to filter other analyses to specific match phases.";
    }

    @Override
    public JsonObject inputSchema() { return new SchemaBuilder().build(); }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("log_duration", log.duration());

      // Find DriverStation entries for mode detection
      String enabledEntry = null;
      String autoEntry = null;

      for (var entryName : log.entries().keySet()) {
        var lower = entryName.toLowerCase();
        if (lower.contains("driverstation") && lower.contains("enabled") && enabledEntry == null) {
          enabledEntry = entryName;
        }
        if (lower.contains("driverstation") && (lower.contains("autonomous") || lower.contains("auto"))
            && !lower.contains("command") && autoEntry == null) {
          autoEntry = entryName;
        }
      }

      var phases = new JsonObject();
      var warnings = new ArrayList<String>();

      if (enabledEntry == null && autoEntry == null) {
        warnings.add("No DriverStation mode entries found in log. "
            + "Cannot determine match phases. Look for entries containing 'DriverStation' "
            + "and 'Enabled' or 'Autonomous'.");
        result.add("warnings", GSON.toJsonTree(warnings));
        result.addProperty("source", "none");
        return result;
      }

      // Detect enable/disable transitions
      Double firstEnableTime = null;
      Double lastDisableTime = null;

      if (enabledEntry != null) {
        var enabledValues = log.values().get(enabledEntry);
        if (enabledValues != null) {
          for (var tv : enabledValues) {
            if (tv.value() instanceof Boolean enabled) {
              if (enabled && firstEnableTime == null) {
                firstEnableTime = tv.timestamp();
              }
              if (!enabled && firstEnableTime != null) {
                lastDisableTime = tv.timestamp();
              }
            }
          }
        }
      }

      // Detect autonomous/teleop transitions from DS mode entry.
      // IMPORTANT: The FMS sets the Autonomous flag BEFORE the robot is enabled
      // (e.g., during the pre-match countdown). The actual auto period only starts
      // when Autonomous=true AND Enabled=true simultaneously.
      // Between auto and teleop, FMS imposes a 1-3 second disabled delay.
      // We detect teleop start as the first Enabled=true AFTER Autonomous goes false,
      // rather than using the Autonomous→false timestamp directly.
      Double autoStart = null;
      Double autoEnd = null;
      Double teleopStart = null;
      Double teleopEnd = null;

      // Get enabled values for cross-referencing with autonomous state
      var enabledValuesForAutoCheck = enabledEntry != null ? log.values().get(enabledEntry) : null;

      if (autoEntry != null) {
        var autoValues = log.values().get(autoEntry);
        if (autoValues != null) {
          Boolean lastAutoState = null;
          for (var tv : autoValues) {
            if (tv.value() instanceof Boolean isAuto) {
              if (isAuto && (lastAutoState == null || !lastAutoState)) {
                // Autonomous flag went true — but only count as auto start
                // if the robot is also enabled (not just pre-match FMS setup)
                if (ToolUtils.isEnabledAt(enabledValuesForAutoCheck, tv.timestamp())) {
                  autoStart = tv.timestamp();
                } else if (autoStart == null) {
                  // Robot is in auto mode but not yet enabled — find the actual
                  // enable time while still in auto mode
                  if (enabledValuesForAutoCheck != null) {
                    for (var ev : enabledValuesForAutoCheck) {
                      if (ev.timestamp() > tv.timestamp() && ev.value() instanceof Boolean en && en) {
                        autoStart = ev.timestamp();
                        break;
                      }
                    }
                  }
                }
              }
              if (!isAuto && lastAutoState != null && lastAutoState) {
                autoEnd = tv.timestamp();
              }
              lastAutoState = isAuto;
            }
          }
        }
      }

      // Find teleop start: first Enabled=true after auto ends.
      // FMS imposes a 1-3s disabled gap between auto and teleop.
      // If the robot was continuously enabled (no FMS, practice mode),
      // teleop starts immediately at autoEnd.
      if (autoEnd != null && enabledEntry != null) {
        var enabledValues = log.values().get(enabledEntry);
        if (enabledValues != null) {
          // Check the robot's enabled state at autoEnd
          Boolean stateAtAutoEnd = null;
          Boolean lastState = null;
          for (var tv : enabledValues) {
            if (tv.value() instanceof Boolean enabled) {
              if (tv.timestamp() <= autoEnd) {
                stateAtAutoEnd = enabled;
              }
              // Look for a disable→enable transition after autoEnd
              // (the FMS disabled gap followed by teleop enable)
              if (tv.timestamp() >= autoEnd && enabled
                  && lastState != null && !lastState) {
                teleopStart = tv.timestamp();
                break;
              }
              lastState = enabled;
            }
          }

          // If robot stayed enabled through auto→teleop (no FMS gap),
          // teleop starts at autoEnd
          if (teleopStart == null && Boolean.TRUE.equals(stateAtAutoEnd)) {
            teleopStart = autoEnd;
          }
        }
      }

      // Fallback: if no Enabled entry, use autoEnd as teleop start
      if (teleopStart == null && autoEnd != null) {
        teleopStart = autoEnd;
      }

      // Build phases from observed transitions
      if (autoStart != null) {
        double aEnd = autoEnd != null ? autoEnd
            : (firstEnableTime != null && lastDisableTime != null ? lastDisableTime : log.maxTimestamp());
        phases.add("autonomous", createPhase(autoStart, aEnd, "Autonomous"));
      }

      if (teleopStart != null) {
        double tEnd = lastDisableTime != null ? lastDisableTime : log.maxTimestamp();
        if (tEnd > teleopStart) {
          phases.add("teleop", createPhase(teleopStart, tEnd, "Teleop"));
          teleopEnd = tEnd;
        }
      }

      // If we have autonomous data but no separate teleop marker,
      // and the robot was enabled before auto, note it
      if (autoStart == null && firstEnableTime != null) {
        double end = lastDisableTime != null ? lastDisableTime : log.maxTimestamp();
        phases.add("enabled", createPhase(firstEnableTime, end, "Enabled (mode unknown)"));
        warnings.add("Robot enable/disable detected but autonomous/teleop mode transitions "
            + "not found. Cannot distinguish match phases.");
      }

      result.add("phases", phases);
      result.addProperty("source", "DriverStation");

      // Add match duration if we can determine it
      if (firstEnableTime != null && lastDisableTime != null) {
        result.addProperty("match_duration", lastDisableTime - firstEnableTime);
      }
      if (autoStart != null && autoEnd != null) {
        result.addProperty("auto_duration", autoEnd - autoStart);
      }
      if (teleopStart != null && teleopEnd != null) {
        result.addProperty("teleop_duration", teleopEnd - teleopStart);
      }

      if (!warnings.isEmpty()) {
        result.add("warnings", GSON.toJsonTree(warnings));
      }
      return result;
    }

    private JsonObject createPhase(double s, double e, String desc) {
      var obj = new JsonObject();
      obj.addProperty("start", s);
      obj.addProperty("end", e);
      obj.addProperty("duration", Math.max(0, e - s));
      obj.addProperty("description", desc);
      return obj;
    }
  }

  static class AnalyzeSwerveTool extends LogRequiringTool {
    @Override
    public String name() { return "analyze_swerve"; }

    @Override
    public String description() {
      return "Analyze swerve drive module performance: per-module speed statistics from SwerveModuleState entries. "
          + "Returns 'no swerve modules detected' if log does not contain swerve module state entries."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MECHANISM;
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("module_prefix", "string", "Entry path prefix (e.g., '/Drive/Module')", false)
          .addNumberProperty("slip_threshold", "Speed difference threshold for slip detection in m/s (default: 0.5)", false, 0.5)
          .addNumberProperty("sync_threshold_rad", "Angle threshold for sync deviation in radians (default: 0.1)", false, 0.1)
          .addProperty("odometry_entry", "string", "Explicit odometry pose entry name", false)
          .addProperty("vision_entry", "string", "Explicit vision pose entry name", false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {
      var prefix = getOptString(arguments, "module_prefix", null);
      double slipThreshold = getOptDouble(arguments, "slip_threshold", 0.5);
      double syncThresholdRad = getOptDouble(arguments, "sync_threshold_rad", 0.1);
      var odomEntryName = getOptString(arguments, "odometry_entry", null);
      var visionEntryName = getOptString(arguments, "vision_entry", null);

      var categorizedEntries = log.entries().entrySet().stream()
          .filter(e -> prefix == null || e.getKey().startsWith(prefix))
          .collect(Collectors.groupingBy(e -> {
            var type = e.getValue().type();
            if (type.contains("SwerveModuleState")) return "module_states";
            if (type.contains("SwerveModulePosition")) return "module_positions";
            if (type.contains("ChassisSpeeds")) return "chassis_speeds";
            return "other";
          }, Collectors.mapping(Map.Entry::getKey, Collectors.toList())));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("swerve_entries", GSON.toJsonTree(categorizedEntries));

      var states = categorizedEntries.get("module_states");
      var warnings = new ArrayList<String>();

      // 1. Per-module speed statistics
      if (states != null) {
        var analysis = new JsonArray();
        for (var name : states) {
          var moduleResult = analyzeModule(name, log.values().get(name));
          if (moduleResult != null) {
            analysis.add(moduleResult);
          } else {
            warnings.add("Could not analyze module '" + name + "': no valid speed data found");
          }
        }
        result.add("module_analysis", analysis);
      }

      // 2. Wheel slip detection — find setpoint/measured pairs
      if (states != null) {
        var slipAnalysis = analyzeWheelSlip(log, states, slipThreshold);
        if (slipAnalysis != null) {
          result.add("wheel_slip", slipAnalysis);
        }
      }

      // 3. Module sync analysis — compare angles across modules
      if (states != null && states.size() >= 2) {
        var syncAnalysis = analyzeModuleSync(log, states, syncThresholdRad);
        if (syncAnalysis != null) {
          result.add("module_sync", syncAnalysis);
        }
      }

      // 4. Odometry drift analysis
      var driftAnalysis = analyzeOdometryDrift(log, odomEntryName, visionEntryName);
      if (driftAnalysis != null) {
        result.add("odometry_drift", driftAnalysis);
      }

      // Data quality from first module state entry
      if (states != null && !states.isEmpty()) {
        var qVals = log.values().get(states.get(0));
        if (qVals != null) {
          var quality = DataQuality.fromValues(qVals);
          var directives = AnalysisDirectives.fromQuality(quality)
              .addSingleMatchCaveat()
              .addFollowup("Use power_analysis to check if module issues correlate with brownouts");
          appendQualityToResult(result, quality, directives);
        }
      }

      if (!warnings.isEmpty()) {
        result.add("warnings", GSON.toJsonTree(warnings));
      }

      return result;
    }

    private JsonObject analyzeModule(String name, List<TimestampedValue> values) {
      if (values == null || values.isEmpty()) return null;

      var speeds = extractSpeeds(values);
      if (speeds.length == 0) return null;

      var stats = java.util.Arrays.stream(speeds).summaryStatistics();
      var obj = new JsonObject();
      obj.addProperty("entry", name);
      obj.addProperty("max_speed_mps", stats.getMax());
      obj.addProperty("avg_speed_mps", stats.getAverage());
      obj.addProperty("sample_count", stats.getCount());
      return obj;
    }

    /** Detects wheel slip by comparing setpoint and measured module state entries. */
    private JsonObject analyzeWheelSlip(ParsedLog log, List<String> stateEntries, double threshold) {
      // Find setpoint/measured pairs by naming convention
      var pairs = new ArrayList<String[]>(); // [setpoint, measured]
      for (var entry : stateEntries) {
        var lower = entry.toLowerCase();
        if (lower.contains("setpoint") || lower.contains("desired") || lower.contains("target")) {
          // Look for matching measured entry
          String base = entry.replaceAll("(?i)(setpoint|desired|target)", "");
          for (var other : stateEntries) {
            var otherLower = other.toLowerCase();
            if ((otherLower.contains("measured") || otherLower.contains("actual") || otherLower.contains("state"))
                && other.replaceAll("(?i)(measured|actual|state)", "").equalsIgnoreCase(base)) {
              pairs.add(new String[]{entry, other});
            }
          }
          // Also try: same prefix, Setpoint vs Measured suffix
          for (var other : stateEntries) {
            if (!other.equals(entry) && sharePrefix(entry, other)) {
              pairs.add(new String[]{entry, other});
            }
          }
        }
      }

      if (pairs.isEmpty()) return null;

      var slipResult = new JsonObject();
      var moduleSlips = new JsonArray();

      for (var pair : pairs) {
        var setpointVals = log.values().get(pair[0]);
        var measuredVals = log.values().get(pair[1]);
        if (setpointVals == null || measuredVals == null) continue;

        double[] setpointSpeeds = extractSpeeds(setpointVals);
        double[] measuredSpeeds = extractSpeeds(measuredVals);

        int len = Math.min(setpointSpeeds.length, measuredSpeeds.length);
        if (len == 0) continue;

        double maxSlip = 0;
        double sumSlip = 0;
        int slipEvents = 0;

        for (int i = 0; i < len; i++) {
          double slip = Math.abs(setpointSpeeds[i] - measuredSpeeds[i]);
          maxSlip = Math.max(maxSlip, slip);
          sumSlip += slip;
          if (slip > threshold) slipEvents++;
        }

        var moduleSlip = new JsonObject();
        moduleSlip.addProperty("setpoint_entry", pair[0]);
        moduleSlip.addProperty("measured_entry", pair[1]);
        moduleSlip.addProperty("max_slip_mps", maxSlip);
        moduleSlip.addProperty("avg_slip_mps", sumSlip / len);
        moduleSlip.addProperty("slip_events", slipEvents);
        moduleSlip.addProperty("slip_event_rate", (double) slipEvents / len);
        moduleSlips.add(moduleSlip);
      }

      if (moduleSlips.size() == 0) return null;
      slipResult.add("modules", moduleSlips);
      slipResult.addProperty("pair_count", moduleSlips.size());
      return slipResult;
    }

    /** Analyzes steering angle synchronization across modules. */
    private JsonObject analyzeModuleSync(ParsedLog log, List<String> stateEntries, double thresholdRad) {
      // Collect measured entries (exclude setpoints)
      var measuredEntries = stateEntries.stream()
          .filter(e -> {
            var lower = e.toLowerCase();
            return !lower.contains("setpoint") && !lower.contains("desired") && !lower.contains("target");
          })
          .toList();

      if (measuredEntries.size() < 2) return null;

      // Extract angle arrays for each module
      var moduleAngles = new ArrayList<double[]>();
      var moduleNames = new ArrayList<String>();
      int minLen = Integer.MAX_VALUE;

      for (var entry : measuredEntries) {
        var values = log.values().get(entry);
        if (values == null) continue;
        double[] angles = extractAngles(values);
        if (angles.length == 0) continue;
        moduleAngles.add(angles);
        moduleNames.add(entry);
        minLen = Math.min(minLen, angles.length);
      }

      if (moduleAngles.size() < 2 || minLen == 0) return null;

      // At each timestamp, compute max deviation from mean angle
      int desyncEvents = 0;
      double maxDeviation = 0;
      String worstModule = "";

      for (int i = 0; i < minLen; i++) {
        double sum = 0;
        for (var angles : moduleAngles) sum += angles[i];
        double mean = sum / moduleAngles.size();

        for (int m = 0; m < moduleAngles.size(); m++) {
          double dev = Math.abs(moduleAngles.get(m)[i] - mean);
          if (dev > maxDeviation) {
            maxDeviation = dev;
            worstModule = moduleNames.get(m);
          }
          if (dev > thresholdRad) desyncEvents++;
        }
      }

      var syncResult = new JsonObject();
      syncResult.addProperty("module_count", moduleAngles.size());
      syncResult.addProperty("samples_analyzed", minLen);
      syncResult.addProperty("desync_events", desyncEvents);
      syncResult.addProperty("max_deviation_rad", maxDeviation);
      syncResult.addProperty("max_deviation_deg", Math.toDegrees(maxDeviation));
      if (!worstModule.isEmpty()) {
        syncResult.addProperty("worst_module", worstModule);
      }
      return syncResult;
    }

    /** Analyzes odometry drift by comparing odometry pose to vision pose. */
    @SuppressWarnings("unchecked")
    private JsonObject analyzeOdometryDrift(ParsedLog log, String odomName, String visionName) {
      // Discover entries if not specified
      if (odomName == null) {
        odomName = log.entries().keySet().stream()
            .filter(n -> {
              var lower = n.toLowerCase();
              var type = log.entries().get(n).type();
              return (lower.contains("odometry") || lower.contains("estimatedpose"))
                  && (type.contains("Pose2d") || type.contains("Pose3d"));
            })
            .findFirst().orElse(null);
      }
      if (visionName == null) {
        visionName = log.entries().keySet().stream()
            .filter(n -> {
              var lower = n.toLowerCase();
              var type = log.entries().get(n).type();
              return lower.contains("vision") && lower.contains("pose")
                  && (type.contains("Pose2d") || type.contains("Pose3d"));
            })
            .findFirst().orElse(null);
      }

      if (odomName == null || visionName == null) return null;

      var odomVals = log.values().get(odomName);
      var visionVals = log.values().get(visionName);
      if (odomVals == null || visionVals == null || odomVals.size() < 2 || visionVals.size() < 2) {
        return null;
      }

      // Compare poses at vision timestamps (lower rate)
      double totalDrift = 0;
      int comparisons = 0;
      double maxDrift = 0;

      for (var vTv : visionVals) {
        if (!(vTv.value() instanceof Map)) continue;
        var visionPose = (Map<String, Object>) vTv.value();

        // Find nearest odometry pose (ZOH)
        Map<String, Object> odomPose = null;
        for (var oTv : odomVals) {
          if (oTv.timestamp() > vTv.timestamp()) break;
          if (oTv.value() instanceof Map) {
            odomPose = (Map<String, Object>) oTv.value();
          }
        }

        if (odomPose == null) continue;

        double dist = poseDistance(odomPose, visionPose);
        totalDrift += dist;
        maxDrift = Math.max(maxDrift, dist);
        comparisons++;
      }

      if (comparisons < 2) return null;

      double timeSpan = visionVals.get(visionVals.size() - 1).timestamp() - visionVals.get(0).timestamp();
      double driftRate = timeSpan > 0 ? maxDrift / timeSpan : 0;

      var driftResult = new JsonObject();
      driftResult.addProperty("odometry_entry", odomName);
      driftResult.addProperty("vision_entry", visionName);
      driftResult.addProperty("avg_error_m", totalDrift / comparisons);
      driftResult.addProperty("max_error_m", maxDrift);
      driftResult.addProperty("drift_rate_m_per_sec", driftRate);
      driftResult.addProperty("comparisons", comparisons);
      return driftResult;
    }

    // ==================== Helpers ====================

    private double[] extractSpeeds(List<TimestampedValue> values) {
      return values.stream()
          .flatMap(tv -> {
            if (tv.value() instanceof Map) {
              return java.util.stream.Stream.of((Map<?, ?>) tv.value());
            } else if (tv.value() instanceof List) {
              return ((List<?>) tv.value()).stream()
                  .filter(v -> v instanceof Map).map(v -> (Map<?, ?>) v);
            }
            return java.util.stream.Stream.empty();
          })
          .map(m -> m.get("speed_mps"))
          .filter(v -> v instanceof Number)
          .mapToDouble(v -> ((Number) v).doubleValue())
          .toArray();
    }

    private double[] extractAngles(List<TimestampedValue> values) {
      return values.stream()
          .flatMap(tv -> {
            if (tv.value() instanceof Map) {
              return java.util.stream.Stream.of((Map<?, ?>) tv.value());
            } else if (tv.value() instanceof List) {
              return ((List<?>) tv.value()).stream()
                  .filter(v -> v instanceof Map).map(v -> (Map<?, ?>) v);
            }
            return java.util.stream.Stream.empty();
          })
          .map(m -> {
            var angle = m.get("angle_rad");
            if (angle instanceof Number) return ((Number) angle).doubleValue();
            // Try nested Rotation2d
            if (m.get("angle") instanceof Map) {
              var rot = ((Map<?, ?>) m.get("angle")).get("value");
              if (rot instanceof Number) return ((Number) rot).doubleValue();
            }
            return null;
          })
          .filter(Objects::nonNull)
          .mapToDouble(v -> (double) v)
          .toArray();
    }

    /** Distance between two Pose2d/3d maps. */
    @SuppressWarnings("unchecked")
    private double poseDistance(Map<String, Object> p1, Map<String, Object> p2) {
      var t1 = (Map<String, Object>) p1.get("translation");
      var t2 = (Map<String, Object>) p2.get("translation");
      if (t1 == null || t2 == null) return 0;
      double dx = ((Number) t1.getOrDefault("x", 0.0)).doubleValue()
                 - ((Number) t2.getOrDefault("x", 0.0)).doubleValue();
      double dy = ((Number) t1.getOrDefault("y", 0.0)).doubleValue()
                 - ((Number) t2.getOrDefault("y", 0.0)).doubleValue();
      return Math.sqrt(dx * dx + dy * dy);
    }

    /** Checks if two entry names share a common prefix (before Setpoint/Measured suffix). */
    private boolean sharePrefix(String a, String b) {
      int lastSlashA = a.lastIndexOf('/');
      int lastSlashB = b.lastIndexOf('/');
      if (lastSlashA < 0 || lastSlashB < 0) return false;
      return a.substring(0, lastSlashA).equals(b.substring(0, lastSlashB));
    }
  }

  static class PowerAnalysisTool extends LogRequiringTool {
    @Override
    public String name() { return "power_analysis"; }

    @Override
    public String description() {
      return "Analyze battery and current distribution data. Finds peak currents per channel and brownout risk. "
          + "Default threshold is 6.8V (roboRIO 1). Set to 6.3V for roboRIO 2. "
          + "Returns 'no battery data found' if log does not contain battery voltage or current entries."
          + GUIDANCE_UNIVERSAL + GUIDANCE_POWER;
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("power_prefix", "string", "Entry path prefix (e.g., '/PDP')", false)
          .addNumberProperty("brownout_threshold", "Voltage threshold (default 6.8V for roboRIO 1, use 6.3V for roboRIO 2)", false, 6.8)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {
      var prefix = getOptString(arguments, "power_prefix", null);
      double threshold = getOptDouble(arguments, "brownout_threshold", 6.8);

      var voltageEntry = log.entries().keySet().stream()
          .filter(n -> (prefix == null || n.startsWith(prefix)) && (n.toLowerCase().contains("voltage")))
          .findFirst();

      var result = new JsonObject();
      result.addProperty("success", true);

      voltageEntry.ifPresent(name -> {
        var values = log.values().get(name);
        if (values != null && !values.isEmpty()) {
          var stats = values.stream()
              .filter(tv -> tv.value() instanceof Number)
              .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
              .summaryStatistics();
          
          var vObj = new JsonObject();
          vObj.addProperty("entry", name);
          vObj.addProperty("min_voltage", stats.getMin());
          vObj.addProperty("max_voltage", stats.getMax());
          vObj.addProperty("avg_voltage", stats.getAverage());
          
          long brownouts = values.stream()
              .filter(tv -> tv.value() instanceof Number && ((Number) tv.value()).doubleValue() < threshold)
              .count();
          vObj.addProperty("samples_below_threshold", brownouts);
          vObj.addProperty("brownout_risk", brownouts > 0 ? "HIGH" : (stats.getMin() < threshold + 1 ? "MODERATE" : "LOW"));
          result.add("voltage_analysis", vObj);
        }
      });

      // Add data quality from voltage entry if available
      if (voltageEntry.isPresent()) {
        var vals = log.values().get(voltageEntry.get());
        if (vals != null && !vals.isEmpty()) {
          var quality = DataQuality.fromValues(vals);
          var directives = AnalysisDirectives.fromQuality(quality)
              .addSingleMatchCaveat()
              .addFollowup("Use predict_battery_health for comprehensive battery assessment");
          appendQualityToResult(result, quality, directives);
        }
      }

      return result;
    }
  }

  static class CanHealthTool extends LogRequiringTool {
    @Override
    public String name() { return "can_health"; }

    @Override
    public String description() {
      return "Analyze CAN bus health by looking for timeout errors and communication issues. "
          + "Returns 'no CAN data found' if log does not contain CAN bus utilization or error entries."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    public JsonObject inputSchema() { return new SchemaBuilder().build(); }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {

      var errorCounts = log.entries().entrySet().stream()
          .filter(e -> "string".equals(e.getValue().type()))
          .collect(Collectors.toMap(Map.Entry::getKey, e -> {
            var values = log.values().get(e.getKey());
            return values == null ? 0L : values.stream()
                .filter(tv -> tv.value() instanceof String s && isCanError(s))
                .count();
          }))
          .entrySet().stream()
          .filter(e -> e.getValue() > 0)
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("error_counts_by_entry", GSON.toJsonTree(errorCounts));
      
      long total = errorCounts.values().stream().mapToLong(Long::longValue).sum();
      result.addProperty("total_can_errors", total);
      result.addProperty("health_assessment", total == 0 ? "GOOD" : (total < 50 ? "CONCERNING" : "POOR"));
      
      return result;
    }

    private boolean isCanError(String s) {
      var lower = s.toLowerCase();
      return lower.contains("can") && (lower.contains("timeout") || lower.contains("error") || lower.contains("fault"));
    }
  }

  static class CompareMatchesTool extends ToolBase {
    @Override
    public String name() { return "compare_matches"; }

    @Override
    public String description() {
      return "Compare statistics for an entry across multiple loaded log files."
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().addProperty("name", "string", "Entry name", true).build();
    }

    @Override
    protected JsonElement executeInternal(JsonObject arguments) throws Exception {
      var loadedPaths = logManager.getLoadedLogPaths();
      if (loadedPaths.size() < 2) {
        throw new IllegalArgumentException(
            "Need at least 2 logs loaded to compare. Currently loaded: " + loadedPaths.size());
      }

      var name = getRequiredString(arguments, "name");

      // Access logs directly by path instead of mutating the active log.
      // This avoids a race condition where concurrent tool invocations could see
      // an unexpected active log.
      var allEntries = logManager.getAllLoadedLogs();

      var comparisons = new JsonArray();
      for (var entry : allEntries.entrySet()) {
        var path = entry.getKey();
        var log = entry.getValue();
        var stats = new JsonObject();
        stats.addProperty("log_filename", Path.of(path).getFileName().toString());

        var vals = log.values().get(name);
        if (vals != null) {
          var s = vals.stream()
              .filter(tv -> tv.value() instanceof Number)
              .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
              .summaryStatistics();
          if (s.getCount() > 0) {
            var sObj = new JsonObject();
            sObj.addProperty("min", s.getMin());
            sObj.addProperty("max", s.getMax());
            sObj.addProperty("mean", s.getAverage());
            stats.add("statistics", sObj);
          }
        }
        comparisons.add(stats);
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("comparisons", comparisons);
      return result;
    }
  }

  /**
   * OLS regression tool for estimating moment of inertia (J) and viscous damping (B) from logged
   * motor current and mechanism velocity data.
   *
   * <p>Physics model: {@code G * motor_count * kt * I = J * α + B * ω}
   *
   * <p>Key inputs:
   * <ul>
   *   <li>velocity_entry — angular (rad/s) or linear (m/s) velocity from the log
   *   <li>current_entry — motor current in amps (always non-negative for TalonFX/Spark)
   *   <li>applied_volts_entry — optional, used to recover torque sign when current is unsigned
   * </ul>
   *
   * <p>The tool performs nearest-neighbour interpolation to align current to velocity timestamps,
   * applies optional moving-average smoothing, computes the numerical velocity derivative, then
   * solves the 2×2 normal equations analytically.
   */
  static class MoiRegressionTool extends LogRequiringTool {

    @Override
    public String name() { return "moi_regression"; }

    @Override
    public String description() {
      return "Estimate moment of inertia J (kg·m²) and viscous damping B (Nm·s/rad) for a "
          + "DC-motor-driven mechanism using OLS regression on logged velocity and current. "
          + "Model: G * motor_count * kt * I = J * α + B * ω. "
          + "Supports angular (rad/s) or linear (m/s, via wheel_radius) velocity entries. "
          + "Provide applied_volts_entry when current is always non-negative (TalonFX/SparkMax) "
          + "so torque direction is recovered from voltage sign."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MECHANISM;
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("velocity_entry", "string",
              "Entry path for mechanism velocity (rad/s, or m/s if wheel_radius is given)", true)
          .addProperty("current_entry", "string",
              "Entry path for motor current (A)", true)
          .addNumberProperty("kt", "Motor torque constant per motor (Nm/A). "
              + "Kraken X60=0.01940, NEO Vortex=0.01706, NEO 550=0.0108", true, null)
          .addNumberProperty("gear_ratio", "Overall gear ratio from motor shaft to output shaft (G)",
              true, null)
          .addIntegerProperty("motor_count",
              "Number of motors driving the mechanism in parallel (default 1)", false, 1)
          .addNumberProperty("wheel_radius",
              "Wheel radius (m). Provide when velocity is logged as linear (m/s) to convert to angular",
              false, null)
          .addProperty("applied_volts_entry", "string",
              "Optional: entry for applied voltage. When current is always non-negative "
              + "(TalonFX/SparkMax), voltage sign is used to determine torque direction.", false)
          .addNumberProperty("start_time", "Analysis window start (seconds)", false, null)
          .addNumberProperty("end_time", "Analysis window end (seconds)", false, null)
          .addNumberProperty("alpha_threshold",
              "Min |α| (rad/s²) for a sample to be included in OLS. Filters near-steady-state "
              + "points. Default 1.0", false, 1.0)
          .addIntegerProperty("smooth_window",
              "Moving-average half-width (samples) applied to velocity before differentiating. "
              + "Default 2", false, 2)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject args) throws Exception {
      // ── Required params ──────────────────────────────────────────────────────
      var velEntry  = getRequiredString(args, "velocity_entry");
      var currEntry = getRequiredString(args, "current_entry");
      double kt     = args.get("kt").getAsDouble();
      double G      = args.get("gear_ratio").getAsDouble();

      // ── Optional params ──────────────────────────────────────────────────────
      int    motorCount  = getOptInt(args,    "motor_count",     1);
      Double wheelRadius = getOptDouble(args, "wheel_radius");
      String voltsEntry  = getOptString(args, "applied_volts_entry", null);
      Double startTime   = getOptDouble(args, "start_time");
      Double endTime     = getOptDouble(args, "end_time");
      double alphaThr    = getOptDouble(args, "alpha_threshold", 1.0);
      int    smoothW     = getOptInt(args,    "smooth_window",   2);

      double torqueScale = G * motorCount * kt;

      // ── Fetch entries ─────────────────────────────────────────────────────────
      var velValues  = log.values().get(velEntry);
      if (velValues == null || velValues.isEmpty())
        return errorResult("Velocity entry not found or empty: " + velEntry);

      var currValues = log.values().get(currEntry);
      if (currValues == null || currValues.isEmpty())
        return errorResult("Current entry not found or empty: " + currEntry);

      List<TimestampedValue> voltsValues = null;
      if (voltsEntry != null) {
        voltsValues = log.values().get(voltsEntry);
        if (voltsValues == null || voltsValues.isEmpty())
          return errorResult("Applied volts entry not found or empty: " + voltsEntry);
      }

      // ── Filter to time window and build arrays ────────────────────────────────
      final double tStart = startTime != null ? startTime : Double.NEGATIVE_INFINITY;
      final double tEnd   = endTime   != null ? endTime   : Double.POSITIVE_INFINITY;

      var velFiltered = velValues.stream()
          .filter(tv -> tv.timestamp() >= tStart && tv.timestamp() <= tEnd
                        && tv.value() instanceof Number)
          .collect(Collectors.toList());

      if (velFiltered.size() < 10)
        return errorResult("Too few velocity samples in window: " + velFiltered.size() + " (need ≥10)");

      int n = velFiltered.size();
      double[] ts    = new double[n];
      double[] omega = new double[n];
      double radiusInv = (wheelRadius != null && wheelRadius > 0) ? 1.0 / wheelRadius : 1.0;
      for (int i = 0; i < n; i++) {
        ts[i]    = velFiltered.get(i).timestamp();
        omega[i] = ((Number) velFiltered.get(i).value()).doubleValue() * radiusInv;
      }

      // ── Smooth velocity and differentiate ─────────────────────────────────────
      double[] omegaS = movingAverage(omega, smoothW);
      double[] alpha  = gradient(omegaS, ts);

      // ── Interpolate current to velocity timestamps ────────────────────────────
      // Null means the current signal has no data at this timestamp (e.g., the
      // current log starts later than the velocity log). We mark these with NaN
      // and skip them in the OLS loop rather than inserting 0.0, which would
      // silently corrupt the regression fit.
      double[] curr = new double[n];
      for (int i = 0; i < n; i++) {
        Double v = getValueAtTimeLinear(currValues, ts[i]);
        curr[i] = v != null ? v : Double.NaN;
      }

      // ── Torque direction sign ─────────────────────────────────────────────────
      // If applied_volts_entry given: sign(volts); otherwise assume current is already signed.
      double[] tauSign = new double[n];
      if (voltsValues != null) {
        for (int i = 0; i < n; i++) {
          Double v = getValueAtTimeLinear(voltsValues, ts[i]);
          tauSign[i] = v != null ? Math.signum(v) : Double.NaN;
        }
      } else {
        java.util.Arrays.fill(tauSign, 1.0);
      }

      // ── OLS normal equations (2×2 system: J, B) ──────────────────────────────
      double sumA2 = 0, sumAW = 0, sumW2 = 0, sumTA = 0, sumTW = 0;
      int nUsed = 0, filtByThr = 0, filtBySign = 0;

      for (int i = 0; i < n; i++) {
        if (!Double.isFinite(alpha[i]) || !Double.isFinite(omegaS[i])
            || !Double.isFinite(curr[i]) || !Double.isFinite(tauSign[i]))
          continue;
        if (Math.abs(alpha[i]) < alphaThr) { filtByThr++;  continue; }
        if (voltsValues != null && Math.abs(tauSign[i]) < 0.5) { filtBySign++; continue; }

        double tau = torqueScale * tauSign[i] * Math.abs(curr[i]);
        sumA2 += alpha[i] * alpha[i];
        sumAW += alpha[i] * omegaS[i];
        sumW2 += omegaS[i] * omegaS[i];
        sumTA += tau * alpha[i];
        sumTW += tau * omegaS[i];
        nUsed++;
      }

      if (nUsed < 5) {
        var err = errorResult("Insufficient samples after filtering: " + nUsed
            + ". Try lowering alpha_threshold or widening the time window.");
        err.addProperty("samples_total", n);
        err.addProperty("filtered_by_alpha_threshold", filtByThr);
        if (filtBySign > 0) err.addProperty("filtered_by_zero_volts", filtBySign);
        return err;
      }

      double det = sumA2 * sumW2 - sumAW * sumAW;
      double detScale = Math.max(sumA2 * sumW2, 1e-20);
      if (Math.abs(det) < 1e-10 * detScale)
        return errorResult("Singular OLS matrix: α and ω are nearly collinear. "
            + "Try a different time window or increase alpha_threshold.");

      double J = (sumTA * sumW2 - sumTW * sumAW) / det;
      double B = (sumTW * sumA2 - sumTA * sumAW) / det;

      // ── R² (uncentered) ──────────────────────────────────────────────────────
      // The physics model τ = Jα + Bω has no intercept term, so we use the
      // uncentered R² = 1 - SS_res / SS_y² where SS_y² = Σyᵢ².
      // Standard centered R² (using mean of Y) is mathematically invalid for
      // regression through the origin and can produce misleading or negative values.
      double ssY2 = 0, ssRes = 0;
      for (int i = 0; i < n; i++) {
        if (!Double.isFinite(alpha[i]) || !Double.isFinite(omegaS[i])
            || !Double.isFinite(curr[i]) || !Double.isFinite(tauSign[i]))
          continue;
        if (Math.abs(alpha[i]) < alphaThr) continue;
        if (voltsValues != null && Math.abs(tauSign[i]) < 0.5) continue;
        double y    = torqueScale * tauSign[i] * Math.abs(curr[i]);
        double yHat = J * alpha[i] + B * omegaS[i];
        ssY2  += y * y;
        ssRes += (y - yHat) * (y - yHat);
      }
      double r2 = ssY2 > 1e-20 ? 1.0 - ssRes / ssY2 : Double.NaN;

      // ── Build result ──────────────────────────────────────────────────────────
      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("J_kg_m2", J);
      result.addProperty("B_Nm_s_per_rad", B);
      result.addProperty("r_squared", r2);
      result.addProperty("n_samples_used", nUsed);
      result.addProperty("n_samples_total", n);
      result.addProperty("filtered_by_alpha_threshold", filtByThr);
      if (filtBySign > 0) result.addProperty("filtered_by_zero_volts", filtBySign);

      var warnings = new JsonArray();
      if (J < 0)
        warnings.add("J is negative — physically invalid. If current is unsigned, add applied_volts_entry.");
      if (!Double.isNaN(r2) && r2 < 0.2)
        warnings.add(String.format("R²=%.3f is low. Narrow the window to a clean acceleration transient, "
            + "raise alpha_threshold, or increase smooth_window.", r2));
      if (nUsed < 20)
        warnings.add("Only " + nUsed + " samples used. Consider widening the window or lowering alpha_threshold.");
      if (warnings.size() > 0) result.add("warnings", warnings);

      var ctx = new JsonObject();
      ctx.addProperty("torque_scale_Nm_per_A", torqueScale);
      if (wheelRadius != null) ctx.addProperty("wheel_radius_m", wheelRadius);
      if (voltsEntry != null)  ctx.addProperty("applied_volts_used", true);
      if (startTime  != null)  ctx.addProperty("start_time", startTime);
      if (endTime    != null)  ctx.addProperty("end_time",   endTime);
      result.add("parameters_used", ctx);

      // Data quality from velocity entry
      var quality = DataQuality.fromValues(velFiltered);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addGuidance("Regression estimates depend on data quality and model assumptions");
      appendQualityToResult(result, quality, directives);

      return result;
    }

    /** Symmetric moving average, half-width {@code w}. Edge points use a smaller window. */
    private double[] movingAverage(double[] v, int w) {
      int n = v.length;
      double[] out = new double[n];
      for (int i = 0; i < n; i++) {
        int lo = Math.max(0, i - w);
        int hi = Math.min(n - 1, i + w);
        double sum = 0;
        for (int j = lo; j <= hi; j++) sum += v[j];
        out[i] = sum / (hi - lo + 1);
      }
      return out;
    }

    /**
     * Numerical gradient using central differences (numpy.gradient semantics).
     * Guards against zero dt (duplicate timestamps) by returning NaN for those samples,
     * which are then filtered out by the isFinite check in the OLS loop.
     */
    private double[] gradient(double[] v, double[] t) {
      int n = v.length;
      double[] g = new double[n];
      if (n < 2) return g;
      double dt0 = t[1] - t[0];
      g[0] = dt0 > 1e-9 ? (v[1] - v[0]) / dt0 : Double.NaN;
      double dtN = t[n - 1] - t[n - 2];
      g[n - 1] = dtN > 1e-9 ? (v[n - 1] - v[n - 2]) / dtN : Double.NaN;
      for (int i = 1; i < n - 1; i++) {
        double dt = t[i + 1] - t[i - 1];
        g[i] = dt > 1e-9 ? (v[i + 1] - v[i - 1]) / dt : Double.NaN;
      }
      return g;
    }
  }

  static class GetCodeMetadataTool extends LogRequiringTool {
    @Override
    public String name() { return "get_code_metadata"; }

    @Override
    public String description() {
      return "Extract code metadata including Git SHA, branch, and build date. "
          + "Returns 'no code metadata found' if log does not contain metadata entries.";
    }

    @Override
    public JsonObject inputSchema() { return new SchemaBuilder().build(); }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {
      var keys = List.of("GitSHA", "GitBranch", "GitDirty", "BuildDate", "ProjectName", "Version");
      var found = log.entries().keySet().stream()
          .filter(name -> keys.stream().anyMatch(name::contains))
          .collect(Collectors.toMap(
              name -> keys.stream().filter(name::contains).findFirst().get(),
              name -> log.values().get(name).get(0).value(),
              (v1, v2) -> v1));

      return success()
          .addData("metadata", GSON.toJsonTree(found))
          .build();
    }
  }
}
