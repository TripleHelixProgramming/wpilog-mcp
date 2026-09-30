/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.triplehelix.wpilogmcp.game.GameKnowledgeBase;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;

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
 *   <li>{@code analyze_loop_timing} - Detect robot code loop timing violations</li>
 *   <li>{@code analyze_can_bus} - Analyze CAN bus utilization and error patterns</li>
 *   <li>{@code predict_battery_health} - Predict battery health from voltage/current data</li>
 * </ul>
 */
public final class FrcDomainTools {

  private FrcDomainTools() {}

  /**
   * Registers all FRC domain tools with the MCP server.
   *
   * @param server The MCP server to register tools with
   */
  public static void registerAll(ToolRegistry registry) {
    registry.registerTool(new GetDsTimelineTool());
    registry.registerTool(new AnalyzeVisionTool());
    registry.registerTool(new ProfileMechanismTool());
    registry.registerTool(new AnalyzeAutoTool());
    registry.registerTool(new AnalyzeCyclesTool());
    registry.registerTool(new AnalyzeReplayDriftTool());
    registry.registerTool(new AnalyzeLoopTimingTool());
    registry.registerTool(new AnalyzeCanBusTool());
    registry.registerTool(new PredictBatteryHealthTool());
    registry.registerTool(new GetGameInfoTool());
  }

  // ==================== SHARED HELPER METHODS ====================

  /** Delegate to shared percentile implementation in ToolUtils. */
  private static double interpolatedPercentile(double[] sortedData, double p) {
    return ToolUtils.percentile(sortedData, p);
  }

  /**
   * Calculate the Euclidean distance between two poses (works for Pose2d and Pose3d).
   *
   * @return The distance, or NaN when either pose's translation cannot be read (callers count
   *     such samples as unreadable; they must never be treated as "no movement")
   */
  static double calculatePoseDistance(java.util.Map<String, Object> pose1, java.util.Map<String, Object> pose2) {
    var trans1 = extractTranslation(pose1);
    var trans2 = extractTranslation(pose2);

    if (trans1 == null || trans2 == null) return Double.NaN;

    double dx = trans1[0] - trans2[0];
    double dy = trans1[1] - trans2[1];
    double dz = trans1.length > 2 && trans2.length > 2 ? trans1[2] - trans2[2] : 0.0;

    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /**
   * Extract translation components from a Pose2d or Pose3d struct.
   */
  private static double[] extractTranslation(java.util.Map<String, Object> pose) {
    // Try nested layout first (e.g., from protobuf or WPILib network tables)
    Object translationObj = pose.get("translation");
    if (translationObj instanceof java.util.Map<?, ?> rawTranslation) {
      @SuppressWarnings("unchecked")
      var translation = (java.util.Map<String, Object>) rawTranslation;

      var x = toDouble(translation.get("x"));
      var y = toDouble(translation.get("y"));
      var z = toDouble(translation.get("z"));

      if (x != null && y != null) {
        return z != null ? new double[]{x, y, z} : new double[]{x, y};
      }
    }
    // Fall back to flat layout (from struct decoder: Pose2dDecoder/Pose3dDecoder)
    var x = toDouble(pose.get("x"));
    var y = toDouble(pose.get("y"));
    var z = toDouble(pose.get("z"));
    if (x != null && y != null) {
      return z != null ? new double[]{x, y, z} : new double[]{x, y};
    }
    return null;
  }

  // ==================== TOOL IMPLEMENTATIONS ====================

  static class GetDsTimelineTool extends LogRequiringTool {
    @Override
    public String name() { return "get_ds_timeline"; }

    @Override
    public String description() {
      return "Generate a chronological timeline of critical robot events: enable/disable, "
          + "match phases, battery-voltage threshold brownouts (BROWNOUT_START/END, basis "
          + "voltage_threshold), roboRIO brownout flag transitions when a flag such as "
          + "/SystemStats/BrownedOut is logged (RIO_BROWNOUT_START/END, basis rio_flag), and "
          + "for errors/warnings found in string entries, exact counts (text_event_counts, per "
          + "source) and text_event_summary: each distinct message (numbers normalized to #) with "
          + "its count, first/last time, sources, and how many distinct raw texts it covers. "
          + "Individual messages are deliberately not listed here; use search_strings (level, "
          + "regex, time window, offset/limit paging) for the complete list. "
          + "rio_brownout_flag_logged says whether the roboRIO's own brownout state is available "
          + "in this log; brownout_voltage_entry names the voltage entry scanned for threshold "
          + "crossings, and a warning says when there is none."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .addNumberProperty("brownout_threshold", "Voltage threshold for BROWNOUT_START/END crossings (default: the log's BrownoutVoltage entry when logged, else 6.8V for roboRIO 1; roboRIO 2 is 6.3V)", false, null)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {

      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      var threshold = PowerFacts.threshold(log, getOptDouble(arguments, "brownout_threshold"));
      double brownoutThreshold = threshold.volts();

      var events = new ArrayList<JsonObject>();

      // Enable/disable and mode transitions, from the same timeline get_match_phases uses: one
      // DriverStation entry per role (AdvantageKit first), values held until the next sample.
      var timeline = MatchTimeline.of(log);
      for (var event : timeline.events(startTime, endTime)) {
        events.add(event.toJson());
      }
      var dsSources = timeline.sources();

      // Add voltage-threshold brownouts on the battery voltage entry. Uses the same selection as
      // power_analysis so the two tools agree on which signal was analyzed; the entry is reported
      // as brownout_voltage_entry, and a warning says when there is none.
      var voltageEntry = ToolUtils.selectVoltageEntry(log, null);
      if (voltageEntry.isPresent()) {
        var entryName = voltageEntry.get();
        var values = log.values().get(entryName);
        boolean inBrownout = false;
        for (var tv : values) {
          if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;
          if (tv.value() instanceof Number num && Double.isFinite(num.doubleValue())) {
            double voltage = num.doubleValue();
            // Hysteresis: enter brownout below threshold, exit only above threshold + 0.2V.
            // Prevents noisy voltage (e.g., loose connectors) from inflating event counts.
            double hysteresis = 0.2;
            if (voltage < brownoutThreshold && !inBrownout) {
              var event = new JsonObject();
              event.addProperty("timestamp", tv.timestamp());
              event.addProperty("type", "BROWNOUT_START");
              event.addProperty("category", "power");
              event.addProperty("basis", "voltage_threshold");
              event.addProperty("voltage", voltage);
              event.addProperty("source", entryName);
              events.add(event);
              inBrownout = true;
            } else if (voltage >= brownoutThreshold + hysteresis && inBrownout) {
              var event = new JsonObject();
              event.addProperty("timestamp", tv.timestamp());
              event.addProperty("type", "BROWNOUT_END");
              event.addProperty("category", "power");
              event.addProperty("basis", "voltage_threshold");
              event.addProperty("voltage", voltage);
              event.addProperty("source", entryName);
              events.add(event);
              inBrownout = false;
            }
          }
        }
      }

      // Add roboRIO brownout flag transitions (e.g. AdvantageKit /SystemStats/BrownedOut).
      // Unlike the voltage-threshold events above, these reflect the roboRIO's own brownout
      // state: the flag is set only when the RIO actually cut outputs.
      String rioFlagEntry = PowerFacts.flagEntry(log).orElse(null);
      if (rioFlagEntry != null) {
        var values = log.values().get(rioFlagEntry);
        Boolean lastState = null;
        for (var tv : values) {
          if (!(tv.value() instanceof Boolean state)) continue;
          if (lastState != null && lastState.equals(state)) continue;
          boolean transition = state || lastState != null; // initial false is not an event
          if (transition && inTimeRange(tv.timestamp(), startTime, endTime)) {
            var event = new JsonObject();
            event.addProperty("timestamp", tv.timestamp());
            event.addProperty("type", state ? "RIO_BROWNOUT_START" : "RIO_BROWNOUT_END");
            event.addProperty("category", "power");
            event.addProperty("basis", "rio_flag");
            event.addProperty("source", rioFlagEntry);
            events.add(event);
          }
          lastState = state;
        }
      }

      // Error/warning text: counts and a distinct-message summary only. Individual messages are
      // not placed on the timeline, because any cap or priority over a chatty console is a
      // judgment the model cannot see; search_strings lists them completely, with paging.
      final int maxTextGroups = 200;
      var textGroups = new LinkedHashMap<String, TextGroup>();
      var countsBySource = new LinkedHashMap<String, int[]>(); // [error, warning]
      int errorSamples = 0;
      int warningSamples = 0;
      for (var entry : log.entries().entrySet()) {
        if (!"string".equals(entry.getValue().type())) continue;
        var values = log.values().get(entry.getKey());
        if (values == null) continue;
        for (var tv : values) {
          if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;
          if (!(tv.value() instanceof String message) || message.isBlank()) continue;
          var classified = ToolUtils.classifyText(message);
          if (classified == null) continue;
          boolean isError = "ERROR".equals(classified.type());
          if (isError) errorSamples++; else warningSamples++;
          countsBySource.computeIfAbsent(entry.getKey(), k -> new int[2])[isError ? 0 : 1]++;
          // The same error usually recurs with varying numbers (loop times, device ids, line
          // numbers), so group on a normalized pattern and count how many raw texts it covers.
          var pattern = ToolUtils.normalizeMessage(classified.message());
          textGroups.computeIfAbsent(classified.type() + "|" + pattern,
                  k -> new TextGroup(classified.type(), pattern, classified.message()))
              .add(tv.timestamp(), entry.getKey(), classified.message());
        }
      }

      events.sort(Comparator.comparingDouble(a -> a.get("timestamp").getAsDouble()));

      var categoryCounts = new HashMap<String, Integer>();
      for (var event : events) {
        var cat = event.get("category").getAsString();
        categoryCounts.merge(cat, 1, Integer::sum);
      }

      var builder = success()
          .addProperty("event_count", events.size())
          .addProperty("brownout_threshold", threshold.volts())
          .addProperty("brownout_threshold_basis", threshold.basis())
          .addProperty("rio_brownout_flag_logged", rioFlagEntry != null)
          .addData("summary", GSON.toJsonTree(categoryCounts))
          .addData("events", GSON.toJsonTree(events));
      if (rioFlagEntry != null) {
        builder.addProperty("rio_brownout_flag_entry", rioFlagEntry);
      }
      var textCounts = new JsonObject();
      textCounts.addProperty("error", errorSamples);
      textCounts.addProperty("warning", warningSamples);
      textCounts.addProperty("total", errorSamples + warningSamples);
      var bySource = new JsonObject();
      countsBySource.forEach((source, c) -> {
        var o = new JsonObject();
        o.addProperty("error", c[0]);
        o.addProperty("warning", c[1]);
        bySource.add(source, o);
      });
      textCounts.add("by_source", bySource);
      builder.addData("text_event_counts", textCounts);
      if (!textGroups.isEmpty()) {
        var groups = new ArrayList<>(textGroups.values());
        groups.sort(Comparator.comparingInt((TextGroup g) -> -g.count)
            .thenComparingDouble(g -> g.firstTimestamp));
        var summary = new JsonArray();
        for (var group : groups.subList(0, Math.min(groups.size(), maxTextGroups))) {
          summary.add(group.toJson());
        }
        builder.addProperty("text_event_groups_total", groups.size());
        builder.addData("text_event_summary", summary);
        if (groups.size() > maxTextGroups) {
          builder.addWarning("text_event_summary shows the " + maxTextGroups + " most frequent of "
              + groups.size() + " distinct messages; use search_strings for the rest.");
        }
      }
      voltageEntry.ifPresentOrElse(
          name -> builder.addProperty("brownout_voltage_entry", name),
          () -> builder.addWarning("No battery voltage entry found; BROWNOUT_START/END events "
              + "cannot be detected in this log."));

      builder.addInput("enabled", dsSources.enabled())
          .addInput("autonomous", dsSources.autonomous())
          .addInput("control_word", dsSources.controlWord())
          .addInput("voltage", voltageEntry.orElse(null))
          .addInput("rio_brownout_flag", rioFlagEntry);
      if (!dsSources.ignored().isEmpty()) {
        builder.addWarning("Also found " + String.join(", ", dsSources.ignored())
            + "; enable and mode events come from " + dsSources.enabled() + " only.");
      }
      if (!timeline.hasEnabledData()) {
        builder.addWarning("No DriverStation enabled entry found; there are no ENABLED/DISABLED "
            + "or match phase events in this timeline.");
      }

      // Add data quality from enabled values if available
      var enabledValuesForTimeline = dsSources.enabled() == null ? null
          : log.values().get(dsSources.enabled());
      if (enabledValuesForTimeline != null && !enabledValuesForTimeline.isEmpty()) {
        var quality = DataQuality.fromValues(enabledValuesForTimeline);
        builder.addDataQuality(quality)
            .addDirectives(AnalysisDirectives.fromQuality(quality).addSingleMatchCaveat());
      }

      return builder.build();
    }

    /** One distinct error/warning message (after normalization) with its occurrence statistics. */
    private static final class TextGroup {
      final String type;
      final String pattern;
      final String example;
      int count = 0;
      double firstTimestamp = Double.NaN;
      double lastTimestamp = Double.NaN;
      final Set<String> sources = new LinkedHashSet<>();
      final Set<String> variants = new HashSet<>();
      static final int MAX_VARIANTS = 10_000;
      boolean variantsCapped = false;

      TextGroup(String type, String pattern, String example) {
        this.type = type;
        this.pattern = pattern;
        this.example = example;
      }

      void add(double timestamp, String source, String rawMessage) {
        if (count == 0 || timestamp < firstTimestamp) firstTimestamp = timestamp;
        if (count == 0 || timestamp > lastTimestamp) lastTimestamp = timestamp;
        count++;
        sources.add(source);
        if (variants.size() < MAX_VARIANTS) {
          variants.add(rawMessage);
        } else if (!variants.contains(rawMessage)) {
          variantsCapped = true;
        }
      }

      JsonObject toJson() {
        var obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("message", ToolUtils.truncate(pattern, ToolUtils.MESSAGE_LINE_LIMIT));
        if (!pattern.equals(example)) {
          obj.addProperty("example", ToolUtils.truncate(example, ToolUtils.MESSAGE_LINE_LIMIT));
        }
        obj.addProperty("count", count);
        obj.addProperty("variants", variants.size());
        if (variantsCapped) obj.addProperty("variants_capped", true);
        obj.addProperty("first_timestamp", firstTimestamp);
        obj.addProperty("last_timestamp", lastTimestamp);
        obj.add("sources", GSON.toJsonTree(sources));
        return obj;
      }
    }

  }

  static class AnalyzeVisionTool extends LogRequiringTool {
    @Override
    public String name() { return "analyze_vision"; }

    @Override
    public String description() {
      return "Analyze vision system reliability: target acquisition rate, flicker detection, "
          + "pose discrepancy between vision and odometry, and sudden pose jumps."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("vision_prefix", "string", "Entry path prefix for vision data", false)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .addNumberProperty("jump_threshold", "Distance threshold for jump detection (meters)", false, 0.5)
          .addNumberProperty("flicker_window", "Time window for flicker detection (seconds)", false, 0.5)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {

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

        if (matchesPrefix && (lower.contains("hastarget") || lower.endsWith("/tv") || lower.endsWith(".tv") || lower.contains("targetvalid"))) {
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
        for (TimestampedValue tv : values) {
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

      var builder = success()
          .addData("target_acquisition", GSON.toJsonTree(targetAnalysis));

      if (!poseJumps.isEmpty()) {
        builder.addData("pose_jumps", GSON.toJsonTree(poseJumps));
        builder.addProperty("jump_count", poseJumps.size());
      }

      // Data quality from first target entry, or first pose entry as fallback
      List<TimestampedValue> qualitySource = null;
      if (!targetValidEntries.isEmpty()) {
        qualitySource = log.values().get(targetValidEntries.get(0));
      } else if (!poseEntries.isEmpty()) {
        qualitySource = log.values().get(poseEntries.get(0));
      }
      if (qualitySource != null) {
        var quality = DataQuality.fromValues(qualitySource);
        builder.addDataQuality(quality)
            .addDirectives(AnalysisDirectives.fromQuality(quality).addSingleMatchCaveat());
      }

      return builder.build();
    }
  }

  static class ProfileMechanismTool extends LogRequiringTool {
    @Override
    public String name() { return "profile_mechanism"; }

    @Override
    public String description() {
      return "Analyze closed-loop mechanism performance: following error (RMSE), settling time, "
          + "stall detection, and motor temperature profiling."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MECHANISM;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("mechanism_name", "string", "Mechanism name or prefix", true)
          .addNumberProperty("start_time", "Start timestamp", false, null)
          .addNumberProperty("end_time", "End timestamp", false, null)
          .addNumberProperty("stall_current_threshold", "Current threshold for stall (default: 30A)", false, 30.0)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {

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

      var builder = success()
          .addProperty("mechanism", mechanismName);

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

          builder.addData("following_error", errorAnalysis);
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
          builder.addData("stall_events", GSON.toJsonTree(stallEvents));
          builder.addProperty("stall_count", stallEvents.size());
        }
      }

      // Data quality from measurement entry if available
      var qualityEntry = measurementEntry != null ? measurementEntry
          : (velocityEntry != null ? velocityEntry : null);
      if (qualityEntry != null) {
        var qVals = log.values().get(qualityEntry);
        if (qVals != null) {
          var quality = DataQuality.fromValues(qVals);
          builder.addDataQuality(quality)
              .addDirectives(AnalysisDirectives.fromQuality(quality)
                  .addSingleMatchCaveat()
                  .addFollowup("Use moi_regression for mechanism inertia estimation"));
        }
      }

      return builder.build();
    }

    private JsonElement calculateSettlingTime(
        java.util.List<TimestampedValue> setpoints,
        java.util.List<TimestampedValue> measurements,
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
      for (TimestampedValue spTv : setpoints) {
        if (startTime != null && spTv.timestamp() < startTime) continue;
        if (endTime != null && spTv.timestamp() > endTime) break;

        var spVal = toDouble(spTv.value());
        var measVal = getValueAtTimeLinear(measurements, spTv.timestamp());

        if (spVal == null || measVal == null) continue;

        // Detect setpoint change (more than 5% change, with absolute minimum threshold)
        if (lastSetpoint == null || Math.abs(spVal - lastSetpoint) > Math.max(Math.abs(lastSetpoint * 0.05), 0.01)) {
          lastSetpoint = spVal;
          setpointChangeTime = spTv.timestamp();
        }

        // Check if settled (within 5% of setpoint, with absolute minimum threshold)
        if (setpointChangeTime != null && Math.abs(measVal - spVal) <= Math.max(Math.abs(spVal * 0.05), 0.01)) {
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
        java.util.List<TimestampedValue> setpoints,
        java.util.List<TimestampedValue> measurements,
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
      for (TimestampedValue spTv : setpoints) {
        if (startTime != null && spTv.timestamp() < startTime) continue;
        if (endTime != null && spTv.timestamp() > endTime) break;

        var spVal = toDouble(spTv.value());
        var measVal = getValueAtTimeLinear(measurements, spTv.timestamp());

        if (spVal == null || measVal == null) continue;

        // Detect setpoint change
        if (lastSetpoint == null || Math.abs(spVal - lastSetpoint) > Math.max(Math.abs(lastSetpoint * 0.05), 0.01)) {
          if (maxOvershoot != null && lastSetpoint != null && Math.abs(lastSetpoint) > 0.001) {
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
        java.util.List<TimestampedValue> velocities,
        java.util.List<TimestampedValue> currents,
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
      for (TimestampedValue velTv : velocities) {
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

  static class AnalyzeAutoTool extends LogRequiringTool {
    @Override
    public String name() { return "analyze_auto"; }

    @Override
    public String description() {
      return "Analyze autonomous periods: every enabled autonomous segment (from the same "
          + "DriverStation timeline as get_match_phases) with its start, end, duration, and "
          + "end_reason; the selected routine at each start (from a string chooser entry such as "
          + ".../Auto Chooser/active or an entry naming the selected auto mode); and path "
          + "following error (RMSE and max, meters) when a setpoint pose and an actual pose "
          + "entry can be identified. Returns status not_applicable with the reason when the log "
          + "has no autonomous period (for example a practice session where Autonomous was "
          + "never true)."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("auto_prefix", "string",
              "Entry name prefix to search for the path setpoint and actual pose entries", false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var autoPrefix = getOptString(arguments, "auto_prefix", null);
      var timeline = MatchTimeline.of(log);
      var sources = timeline.sources();
      var chooser = findChooserEntry(log);

      if (!timeline.hasEnabledData() && sources.autonomous() == null) {
        return ResponseBuilder.noMatch("No DriverStation state entries found, so autonomous "
                + "periods cannot be identified.")
            .lookedFor(List.of("boolean Enabled and Autonomous entries under /DriverStation/ "
                + "or DS:", "int64 FMSInfo/FMSControlData"))
            .hint("Use get_match_phases to see what the server can determine about this log.")
            .build();
      }

      var periods = timeline.enabledSegments().stream()
          .filter(seg -> seg.mode() == MatchTimeline.Mode.AUTO).toList();
      if (periods.isEmpty()) {
        String reason;
        if (sources.autonomous() == null && sources.controlWord() == null) {
          reason = "The log has no Autonomous entry, so autonomous periods cannot be identified.";
        } else if (!timeline.autonomousEverTrue()) {
          reason = "No autonomous period: " + sources.autonomous() + " has "
              + timeline.autonomousSampleCount() + " sample(s), all false (DriverStation values "
              + "are logged on change and hold until the next sample).";
        } else {
          reason = "Autonomous mode was set, but the robot was never enabled while in it.";
        }
        var na = ResponseBuilder.notApplicable(reason)
            .addInput("enabled", sources.enabled())
            .addInput("autonomous", sources.autonomous());
        chooser.ifPresent(c -> na.addInput("selected_routine", c));
        return na.build();
      }

      var game = timeline.game();
      var builder = success()
          .addInput("enabled", sources.enabled())
          .addInput("autonomous", sources.autonomous());
      chooser.ifPresent(c -> builder.addInput("selected_routine", c));

      var pathEntries = findPathEntries(log, autoPrefix);
      var periodsJson = new JsonArray();
      for (var period : periods) {
        var p = new JsonObject();
        p.addProperty("start", period.start());
        p.addProperty("end", period.end());
        p.addProperty("duration", period.duration());
        p.addProperty("end_reason", period.endReason().name().toLowerCase());
        chooser.ifPresent(c -> {
          var value = ToolUtils.getValueAtTimeZoh(log.values().get(c), period.start());
          if (value instanceof String routine) p.addProperty("selected_routine", routine);
        });
        if (pathEntries != null) {
          var error = calculatePathFollowingError(log, pathEntries[0], pathEntries[1],
              period.start(), period.end());
          if (error != null) p.add("path_following_error", error);
        }
        periodsJson.add(p);
      }
      builder.addData("auto_periods", periodsJson);

      // Compatibility fields describe the first autonomous period
      var first = periodsJson.get(0).getAsJsonObject();
      builder.addProperty("auto_start_time", first.get("start").getAsDouble())
          .addProperty("auto_end_time", first.get("end").getAsDouble())
          .addProperty("auto_duration", first.get("duration").getAsDouble());
      if (first.has("selected_routine")) {
        builder.addProperty("selected_routine", first.get("selected_routine").getAsString());
      }
      if (first.has("path_following_error")) {
        builder.addData("path_following_error", first.get("path_following_error"));
      }
      game.ifPresent(g -> builder.addProperty("expected_auto_sec", g.autoDurationSec()));

      if (chooser.isEmpty()) {
        builder.addSkipped("selected_routine", "No string entry naming the selected autonomous "
            + "routine (looked for names ending in /active under a chooser, or containing "
            + "'auto' with 'selected', 'mode', 'routine', or 'choice', or containing 'chooser').");
      }
      if (pathEntries == null) {
        builder.addSkipped("path_following_error", "Could not identify both a setpoint pose "
            + "(Pose2d/Pose3d named with setpoint, target, or desired) and an actual pose "
            + "(named with actual, estimated, odometry, or pose)"
            + (autoPrefix != null ? " under " + autoPrefix : "") + ".");
      } else {
        builder.addInput("path_setpoint", pathEntries[0]).addInput("path_actual", pathEntries[1]);
        if (objectsWithout(periodsJson, "path_following_error") == periodsJson.size()) {
          builder.addSkipped("path_following_error", "No setpoint samples with a readable "
              + "actual pose fell inside an autonomous period.");
        }
      }
      if (periods.size() > 1) {
        builder.addWarning(periods.size() + " autonomous periods found; the top-level auto_* "
            + "fields describe the first, auto_periods lists all.");
      }
      return builder.build();
    }

    private static int objectsWithout(JsonArray array, String key) {
      int n = 0;
      for (var e : array) if (!e.getAsJsonObject().has(key)) n++;
      return n;
    }

    /**
     * The string entry holding the selected autonomous routine: a chooser's {@code /active}
     * entry first, then names with "auto" and "selected"/"mode"/"routine"/"choice", then any name
     * containing "chooser"; ties by entry id.
     */
    static java.util.Optional<String> findChooserEntry(LogData log) {
      return log.entries().values().stream()
          .filter(e -> "string".equals(e.type()))
          .filter(e -> chooserRank(e.name().toLowerCase()) < Integer.MAX_VALUE)
          .filter(e -> log.sampleCount(e.name()) > 0)
          .sorted(Comparator.comparingInt((org.triplehelix.wpilogmcp.log.EntryInfo e) ->
                  chooserRank(e.name().toLowerCase()))
              .thenComparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
          .map(org.triplehelix.wpilogmcp.log.EntryInfo::name)
          .findFirst();
    }

    static int chooserRank(String lower) {
      var leaf = lower.substring(lower.lastIndexOf('/') + 1);
      if (leaf.startsWith(".") || leaf.equals("default") || leaf.equals("options")) {
        return Integer.MAX_VALUE;
      }
      boolean chooser = lower.contains("chooser");
      boolean auto = lower.contains("auto");
      if (leaf.equals("active") && (chooser || auto)) return 0;
      if (auto && (lower.contains("selected") || lower.contains("routine")
          || lower.contains("choice") || lower.contains("mode"))) return 1;
      if (chooser) return 2;
      return Integer.MAX_VALUE;
    }

    /** {setpoint, actual} pose entries for path following, or null; ranked, ties by entry id. */
    static String[] findPathEntries(LogData log, String prefix) {
      String setpoint = null;
      String actual = null;
      int setpointId = Integer.MAX_VALUE;
      int actualId = Integer.MAX_VALUE;
      for (var entry : log.entries().values()) {
        var name = entry.name();
        if (prefix != null && !name.startsWith(prefix)) continue;
        var type = entry.type();
        boolean scalarPose = (type.equals("struct:Pose2d") || type.equals("struct:Pose3d"));
        if (!scalarPose || log.sampleCount(name) < 2) continue;
        var lower = name.toLowerCase();
        if (lower.contains("setpoint") || lower.contains("target") || lower.contains("desired")) {
          if (entry.id() < setpointId) {
            setpoint = name;
            setpointId = entry.id();
          }
        } else if (lower.contains("actual") || lower.contains("estimated")
            || lower.contains("odometry") || lower.endsWith("/pose")) {
          if (entry.id() < actualId) {
            actual = name;
            actualId = entry.id();
          }
        }
      }
      return setpoint != null && actual != null ? new String[] {setpoint, actual} : null;
    }

    private JsonObject calculatePathFollowingError(LogData log, String setpointEntry,
        String actualEntry, double startTime, double endTime) {
      var setpointValues = log.values().get(setpointEntry);
      var actualValues = log.values().get(actualEntry);
      if (setpointValues == null || actualValues == null) return null;

      double sumSquaredError = 0.0;
      int count = 0;
      int unreadable = 0;
      double maxError = 0.0;
      for (TimestampedValue spTv : setpointValues) {
        if (spTv.timestamp() < startTime || spTv.timestamp() > endTime) continue;
        if (!(spTv.value() instanceof java.util.Map)) {
          unreadable++;
          continue;
        }
        @SuppressWarnings("unchecked")
        var setpointPose = (java.util.Map<String, Object>) spTv.value();
        var actualPose = getActualPoseAtTime(actualValues, spTv.timestamp());
        if (actualPose == null) continue;
        double error = calculatePoseDistance(setpointPose, actualPose);
        if (!Double.isFinite(error)) {
          unreadable++;
          continue;
        }
        sumSquaredError += error * error;
        maxError = Math.max(maxError, error);
        count++;
      }
      if (count == 0) return null;

      var errorAnalysis = new JsonObject();
      errorAnalysis.addProperty("rmse_meters", Math.sqrt(sumSquaredError / count));
      errorAnalysis.addProperty("max_error_meters", maxError);
      errorAnalysis.addProperty("samples", count);
      if (unreadable > 0) errorAnalysis.addProperty("unreadable_samples", unreadable);
      return errorAnalysis;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> getActualPoseAtTime(
        java.util.List<TimestampedValue> values,
        double timestamp
    ) {
      // Find closest pose (ZOH) using O(log n) binary search
      var raw = ToolUtils.getValueAtTimeZoh(values, timestamp);
      if (raw instanceof java.util.Map) {
        return (java.util.Map<String, Object>) raw;
      }
      return null;
    }
  }

  static class AnalyzeCyclesTool extends LogRequiringTool {
    @Override
    public String name() { return "analyze_cycles"; }

    @Override
    public String description() {
      return "Analyze game piece handling cycle times with configurable cycle detection modes "
          + "(start-to-start or start-to-end), dead time tracking, and data quality warnings. "
          + "Supports time filtering, case-sensitive/insensitive matching, and incomplete cycle detection."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() {
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
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {// Parse parameters
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

        for (TimestampedValue tv : vals) {
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
          double lastTimestamp = vals.get(vals.size() - 1).timestamp();
          double boundedEnd = endTime != null ? Math.min(lastTimestamp, endTime) : lastTimestamp;
          double incompleteDuration = boundedEnd - cycleStartTime;

          var cycleDetail = new JsonObject();
          cycleDetail.addProperty("start_time", cycleStartTime);
          cycleDetail.addProperty("end_time", boundedEnd);
          cycleDetail.addProperty("duration", incompleteDuration);
          cycleDetail.addProperty("incomplete", true);
          cycleDetails.add(cycleDetail);
        }
      } else if (cycleMode.equals("start_to_end")) {
        Double cycleStartTime = null;
        boolean inCycle = false;

        for (TimestampedValue tv : vals) {
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
          double lastTimestamp = vals.get(vals.size() - 1).timestamp();
          double boundedEnd = endTime != null ? Math.min(lastTimestamp, endTime) : lastTimestamp;
          double incompleteDuration = boundedEnd - cycleStartTime;

          var cycleDetail = new JsonObject();
          cycleDetail.addProperty("start_time", cycleStartTime);
          cycleDetail.addProperty("end_time", boundedEnd);
          cycleDetail.addProperty("duration", incompleteDuration);
          cycleDetail.addProperty("incomplete", true);
          cycleDetails.add(cycleDetail);
        }
      }

      // Detect idle/dead time
      if (idleState != null) {
        String lastState = null;
        Double idleStartTime = null;

        for (TimestampedValue tv : vals) {
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

      // Warn about incomplete cycles
      long incompleteCount = cycleDetails.stream()
          .filter(c -> c.has("incomplete") && c.get("incomplete").getAsBoolean()).count();
      if (incompleteCount > 0) {
        warnings.add(incompleteCount + " cycle(s) incomplete (log ended mid-cycle). "
            + "Exclude from statistical analysis.");
      }

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

      // Add data quality and analysis directives
      var quality = DataQuality.fromValues(vals);
      var directives = AnalysisDirectives.fromQuality(quality).addSingleMatchCaveat();
      result.add("data_quality", quality.toJson());
      result.add("server_analysis_directives", directives.toJson());

      return result;
    }private boolean statesEqual(String state1, String state2, boolean caseSensitive) {
      if (state1 == null || state2 == null) return false;
      if (caseSensitive) {
        return state1.equals(state2);
      } else {
        return state1.equalsIgnoreCase(state2);
      }
    }

    private java.util.List<String> detectDataQualityIssues(
        java.util.List<TimestampedValue> vals,
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

  static class AnalyzeReplayDriftTool extends LogRequiringTool {
    static final double TIME_TOLERANCE_SEC = 0.001;
    static final double DEFAULT_RELATIVE_TOLERANCE = 1e-9;
    static final double ABSOLUTE_TOLERANCE = 1e-12;

    @Override
    public String name() { return "analyze_replay_drift"; }

    @Override
    public String description() {
      return "Validate AdvantageKit deterministic replay: in a replay output log (the _sim log "
          + "AdvantageScope writes), compare every /RealOutputs/X entry with /ReplayOutputs/X "
          + "sample by sample (timestamps matched within 1 ms). Numbers are equal within "
          + "relative_tolerance (default 1e-9); arrays and structs are compared element by "
          + "element. Returns pairs_compared, the entries present on only one side, and for each "
          + "divergent entry the first divergence time, divergent/compared sample counts, the "
          + "largest numeric difference, and the values at the first divergence. Returns "
          + "not_applicable on a log without /ReplayOutputs/ entries (a real-robot log). Small "
          + "drift may come from non-deterministic inputs; look for large or systematic "
          + "divergence."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addNumberProperty("relative_tolerance",
              "Numbers within this fraction of their magnitude are equal (default 1e-9)", false,
              DEFAULT_RELATIVE_TOLERANCE)
          .addIntegerProperty("limit", "Maximum divergent entries to list (default 20)", false, 20)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      double relTol = getOptDouble(arguments, "relative_tolerance", DEFAULT_RELATIVE_TOLERANCE);
      if (!(relTol >= 0) || !Double.isFinite(relTol)) {
        throw new IllegalArgumentException("relative_tolerance must be a finite number >= 0");
      }
      int limit = getOptInt(arguments, "limit", 20);
      validatePositive(limit, "limit");

      var names = log.entries().values().stream()
          .sorted(Comparator.comparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
          .map(org.triplehelix.wpilogmcp.log.EntryInfo::name).toList();
      var real = names.stream().filter(n -> n.startsWith("/RealOutputs/")).toList();
      var replay = names.stream().filter(n -> n.startsWith("/ReplayOutputs/")).toList();
      if (replay.isEmpty()) {
        return ResponseBuilder.notApplicable("This log has no /ReplayOutputs/ entries, so it is "
                + "not an AdvantageKit replay output log (" + real.size() + " /RealOutputs/ "
                + "entries, nothing to compare them with).")
            .hint("Run AdvantageKit replay on this log (AdvantageScope writes a _sim log) and "
                + "call this tool on the _sim log.")
            .build();
      }
      var replaySet = new java.util.HashSet<>(replay);
      var realSet = new java.util.HashSet<>(real);
      var pairs = real.stream()
          .filter(n -> replaySet.contains(n.replaceFirst("^/RealOutputs/", "/ReplayOutputs/")))
          .toList();
      var realOnly = real.stream()
          .filter(n -> !replaySet.contains(n.replaceFirst("^/RealOutputs/", "/ReplayOutputs/")))
          .toList();
      var replayOnly = replay.stream()
          .filter(n -> !realSet.contains(n.replaceFirst("^/ReplayOutputs/", "/RealOutputs/")))
          .toList();
      if (pairs.isEmpty()) {
        return ResponseBuilder.noMatch("No /RealOutputs/X entry has a /ReplayOutputs/X "
                + "counterpart (" + real.size() + " real, " + replay.size() + " replay entries).")
            .lookedFor(List.of("entry pairs /RealOutputs/<name> and /ReplayOutputs/<name>"))
            .build();
      }

      var divergent = new ArrayList<JsonObject>();
      long comparedSamples = 0;
      long unmatchedSamples = 0;
      for (var realName : pairs) {
        var replayName = realName.replaceFirst("^/RealOutputs/", "/ReplayOutputs/");
        var realVals = log.values().get(realName);
        var replayVals = log.values().get(replayName);
        int j = 0;
        int compared = 0;
        int diverged = 0;
        double maxDiff = 0;
        JsonObject firstDivergence = null;
        for (var realTv : realVals) {
          while (j < replayVals.size()
              && replayVals.get(j).timestamp() < realTv.timestamp() - TIME_TOLERANCE_SEC) {
            j++;
          }
          if (j >= replayVals.size()
              || Math.abs(replayVals.get(j).timestamp() - realTv.timestamp()) > TIME_TOLERANCE_SEC) {
            unmatchedSamples++;
            continue;
          }
          var replayTv = replayVals.get(j);
          compared++;
          double[] diff = {0};
          if (!valuesEqual(realTv.value(), replayTv.value(), relTol, diff)) {
            diverged++;
            maxDiff = Math.max(maxDiff, diff[0]);
            if (firstDivergence == null) {
              firstDivergence = new JsonObject();
              firstDivergence.addProperty("timestamp", realTv.timestamp());
              firstDivergence.addProperty("real", preview(realTv.value()));
              firstDivergence.addProperty("replay", preview(replayTv.value()));
            }
          }
        }
        comparedSamples += compared;
        if (diverged > 0) {
          var d = new JsonObject();
          d.addProperty("entry", realName);
          d.addProperty("type", log.entries().get(realName).type());
          d.addProperty("first_divergence_time", firstDivergence.get("timestamp").getAsDouble());
          d.addProperty("divergent_samples", diverged);
          d.addProperty("compared_samples", compared);
          if (maxDiff > 0) d.addProperty("max_abs_difference", maxDiff);
          d.add("first_divergence", firstDivergence);
          divergent.add(d);
        }
      }
      divergent.sort(Comparator.comparingDouble(d -> d.get("first_divergence_time").getAsDouble()));

      var list = new JsonArray();
      divergent.stream().limit(limit).forEach(list::add);
      var builder = success()
          .addProperty("pairs_compared", pairs.size())
          .addProperty("samples_compared", comparedSamples)
          .addProperty("divergent_count", divergent.size())
          .addProperty("relative_tolerance", relTol)
          .addLimitedList("divergences", list, divergent.size(), limit);
      if (unmatchedSamples > 0) builder.addProperty("samples_without_counterpart", unmatchedSamples);
      builder.addData("real_only_entries", GSON.toJsonTree(realOnly.stream().limit(50).toList()))
          .addProperty("real_only_count", realOnly.size())
          .addData("replay_only_entries", GSON.toJsonTree(replayOnly.stream().limit(50).toList()))
          .addProperty("replay_only_count", replayOnly.size());
      if (!realOnly.isEmpty()) {
        builder.addWarning(realOnly.size() + " /RealOutputs/ entries have no replay counterpart "
            + "and were not compared.");
      }
      return builder.build();
    }

    /** Deep equality with a relative numeric tolerance; records the largest numeric difference. */
    static boolean valuesEqual(Object a, Object b, double relTol, double[] maxDiff) {
      if (a instanceof Number x && b instanceof Number y) {
        double dx = x.doubleValue();
        double dy = y.doubleValue();
        if (Double.isNaN(dx) || Double.isNaN(dy)) return Double.isNaN(dx) && Double.isNaN(dy);
        if (dx == dy) return true; // includes equal infinities
        double diff = Math.abs(dx - dy);
        if (Double.isFinite(diff)) maxDiff[0] = Math.max(maxDiff[0], diff);
        return diff <= Math.max(ABSOLUTE_TOLERANCE, relTol * Math.max(Math.abs(dx), Math.abs(dy)));
      }
      if (a instanceof java.util.Map<?, ?> ma && b instanceof java.util.Map<?, ?> mb) {
        if (!ma.keySet().equals(mb.keySet())) return false;
        boolean equal = true;
        for (var key : ma.keySet()) equal &= valuesEqual(ma.get(key), mb.get(key), relTol, maxDiff);
        return equal;
      }
      if (a instanceof List<?> la && b instanceof List<?> lb) {
        if (la.size() != lb.size()) return false;
        boolean equal = true;
        for (int i = 0; i < la.size(); i++) equal &= valuesEqual(la.get(i), lb.get(i), relTol, maxDiff);
        return equal;
      }
      if (a != null && b != null && a.getClass().isArray() && b.getClass().isArray()) {
        int n = java.lang.reflect.Array.getLength(a);
        if (n != java.lang.reflect.Array.getLength(b)) return false;
        boolean equal = true;
        for (int i = 0; i < n; i++) {
          equal &= valuesEqual(java.lang.reflect.Array.get(a, i), java.lang.reflect.Array.get(b, i),
              relTol, maxDiff);
        }
        return equal;
      }
      return Objects.equals(a, b);
    }

    static String preview(Object value) {
      String text;
      if (value != null && value.getClass().isArray()) {
        int n = java.lang.reflect.Array.getLength(value);
        var sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
          if (i > 0) sb.append(", ");
          sb.append(java.lang.reflect.Array.get(value, i));
        }
        text = sb.append(']').toString();
      } else {
        text = String.valueOf(value);
      }
      return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
  }

  static class AnalyzeLoopTimingTool extends LogRequiringTool {
    @Override
    public String name() { return "analyze_loop_timing"; }

    @Override
    public String description() {
      return "Detect when robot code exceeded loop period threshold (default 20ms). "
          + "Returns violations, statistics, and a health score. "
          + "Auto-detects units (ms vs s) via median heuristic; assumes standard FRC loop rates."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addNumberProperty("threshold_ms", "Loop time threshold in milliseconds (default: 20)", false, 20.0)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .addProperty("unit", "string", "Unit of loop time values: 'ms', 's', or 'auto' (default: 'auto'). "
              + "Auto-detect uses median value: if median < 1.0, assumes seconds and converts to ms.", false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {double thresholdMs = getOptDouble(arguments, "threshold_ms", 20.0);
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      var unit = getOptString(arguments, "unit", "auto");

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

      // Determine conversion factor based on unit parameter
      // For "auto", collect raw values first, then detect unit from median
      boolean needsAutoDetect = "auto".equalsIgnoreCase(unit);
      double conversionFactor = 1.0; // default: assume ms
      if ("s".equalsIgnoreCase(unit)) {
        conversionFactor = 1000.0;
      }

      // First pass: collect raw values with timestamps for auto-detection
      record RawSample(double timestamp, double value) {}
      var rawSamples = new ArrayList<RawSample>();
      for (TimestampedValue tv : values) {
        if (startTime != null && tv.timestamp() < startTime) continue;
        if (endTime != null && tv.timestamp() > endTime) break;
        if (tv.value() instanceof Number num) {
          rawSamples.add(new RawSample(tv.timestamp(), num.doubleValue()));
        }
      }

      if (rawSamples.isEmpty()) {
        return errorResult("No numeric loop time data found");
      }

      boolean detectedMicroseconds = false;
      if (needsAutoDetect) {
        // Use median to determine unit (robust to outliers)
        var sortedRaw = rawSamples.stream().mapToDouble(RawSample::value).sorted().toArray();
        double median = sortedRaw.length % 2 == 1
            ? sortedRaw[sortedRaw.length / 2]
            : (sortedRaw[sortedRaw.length / 2 - 1] + sortedRaw[sortedRaw.length / 2]) / 2.0;
        // Values in 0.001–1.0 range look like seconds (typical: 0.02 for 20ms loop)
        // Below 0.001 could be fractional ms or corrupt data — leave as-is
        if (median >= 0.001 && median < 1.0) {
          conversionFactor = 1000.0; // Values look like seconds, convert to ms
        } else if (median > 500) {
          // Values > 500 look like microseconds (typical: 20000 for 20ms loop)
          conversionFactor = 1.0 / 1000.0; // Convert microseconds to ms
          detectedMicroseconds = true;
        }
      }

      for (var sample : rawSamples) {
        double loopTimeMs = sample.value() * conversionFactor;
        loopTimes.add(loopTimeMs);

        if (loopTimeMs > thresholdMs) {
          var violation = new JsonObject();
          violation.addProperty("timestamp", sample.timestamp());
          violation.addProperty("loop_time_ms", loopTimeMs);
          violation.addProperty("overage_ms", loopTimeMs - thresholdMs);
          violations.add(violation);
        }
      }

      // Calculate statistics
      var stats = loopTimes.stream().mapToDouble(d -> d).summaryStatistics();
      var sorted = loopTimes.stream().mapToDouble(d -> d).sorted().toArray();

      var statistics = new JsonObject();
      statistics.addProperty("avg_ms", stats.getAverage());
      statistics.addProperty("max_ms", stats.getMax());
      statistics.addProperty("min_ms", stats.getMin());
      statistics.addProperty("p95_ms", interpolatedPercentile(sorted, 0.95));
      statistics.addProperty("p99_ms", interpolatedPercentile(sorted, 0.99));

      // Calculate health score (0-100)
      double violationRate = (double) violations.size() / loopTimes.size();
      // Linear mapping: 0% violations = 100, 100% violations = 0
      int healthScore = (int) Math.max(0, Math.min(100, 100 - (violationRate * 100)));

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
      if (detectedMicroseconds) {
        result.addProperty("units_note", "Raw values were detected as microseconds and converted to milliseconds");
      }

      // Add data quality and analysis directives
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addGuidance("Health score is a heuristic based on violation rate — consider context of violations");
      result.add("data_quality", quality.toJson());
      result.add("server_analysis_directives", directives.toJson());

      return result;
    }
  }

  static class AnalyzeCanBusTool extends LogRequiringTool {
    @Override
    public String name() { return "analyze_can_bus"; }

    @Override
    public String description() {
      return "Analyze CAN bus health from the counters the log records, per bus: utilization "
          + "(percent; 0-1 fractions are detected and converted), transmit/receive error "
          + "counters TEC and REC (maximum, when, excursions above the error-passive threshold "
          + "of 128, time spent at or above it; bus-off is TEC above 255), and bus-off and "
          + "TX-full count increases, each overall and while enabled. Buses are found by the "
          + "standard field names (WPILib CANStatus as AdvantageKit logs it under "
          + "/SystemStats/CANBus, named 'rio'; CTRE CANivore status such as "
          + "<prefix>/CANHD/{Utilization,TEC,REC,BusOffCount,TxFullCount}, named by the last "
          + "path segment); bus_name selects one. Other numeric/boolean entries named with CAN "
          + "and error/fault/timeout are reported under errors by how much they increased. "
          + "Returns no_match when the log has no CAN counters. See also can_health (console "
          + "messages plus these counters)."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("bus_name", "string",
              "Bus to analyze: 'rio', a CANivore name such as 'CANHD', or a path prefix "
                  + "(default: every bus found)", false)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .build();
    }

    /**
     * Other entries named with CAN (as a word, or CANbus/CANivore/CANcoder, never Canandgyro or
     * scan) and error/fault/timeout, e.g. {@code /CAN/TimeoutCount} or {@code /CANBus/Error/Tx}.
     */
    static boolean isOtherCanErrorEntry(String name) {
      var lower = name.toLowerCase(java.util.Locale.ROOT).replace("default", "");
      boolean canToken = lower.matches(".*(^|[^a-z])can([^a-z]|bus|ivore|coder|$).*");
      boolean failure = lower.matches(".*(error|fault|timeout).*");
      return canToken && failure;
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var busName = getOptString(arguments, "bus_name", null);
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      var timeline = MatchTimeline.of(log);

      var allBuses = CanBusAnalysis.discoverBuses(log);
      var buses = busName == null ? allBuses
          : allBuses.stream().filter(b -> b.matches(busName)).toList();
      var busEntries = new java.util.HashSet<String>();
      allBuses.forEach(b -> busEntries.addAll(b.entries().values()));
      var otherErrorEntries = log.entries().values().stream()
          .sorted(Comparator.comparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
          .filter(e -> CanBusAnalysis.numeric(e.type()) || "boolean".equals(e.type()))
          .filter(e -> !busEntries.contains(e.name()))
          .filter(e -> isOtherCanErrorEntry(e.name()))
          .map(org.triplehelix.wpilogmcp.log.EntryInfo::name)
          .toList();

      var lookedFor = List.of(
          "numeric entries named Utilization, BusUtilization, BusOffCount, OffCount, "
              + "TxFullCount, REC, ReceiveErrorCount, TEC, or TransmitErrorCount under a path "
              + "containing 'can' (e.g. /SystemStats/CANBus/..., .../CANBus/CANHD/...)",
          "numeric or boolean entries named with CAN and error, fault, or timeout");
      if (busName != null && buses.isEmpty()) {
        var available = new JsonArray();
        allBuses.forEach(b -> available.add(b.name()));
        return ResponseBuilder.noMatch("No CAN bus named '" + busName + "' in this log.")
            .lookedFor(lookedFor)
            .addData("available_buses", available)
            .hint(allBuses.isEmpty() ? "This log records no CAN bus counters."
                : "Pass one of available_buses as bus_name, or omit bus_name for all.")
            .build();
      }
      if (buses.isEmpty() && otherErrorEntries.isEmpty()) {
        return ResponseBuilder.noMatch("This log records no CAN bus counters or CAN error "
                + "entries.")
            .lookedFor(lookedFor)
            .hint("CAN problems may still appear as console messages: use can_health or "
                + "search_strings with pattern 'CAN'.")
            .build();
      }

      var builder = success();
      builder.addData("buses", CanBusAnalysis.busesJson(log, buses, timeline, startTime,
          endTime));
      if (startTime != null || endTime != null) builder.addInputWindow(startTime, endTime);
      builder.addInput("enabled", timeline.sources().enabled());

      // Compatibility: one utilization row per bus, in percent
      var utilization = new JsonArray();
      for (var bus : buses) {
        var name = bus.entries().get(CanBusAnalysis.Field.UTILIZATION);
        if (name == null) continue;
        var u = CanBusAnalysis.utilization(
            CanBusAnalysis.window(log.values().get(name), startTime, endTime), timeline);
        if (!u.has("mean_percent")) continue;
        var row = new JsonObject();
        row.addProperty("entry", name);
        row.addProperty("bus", bus.name());
        row.addProperty("avg_percent", u.get("mean_percent").getAsDouble());
        row.addProperty("max_percent", u.get("max_percent").getAsDouble());
        row.addProperty("sample_count", u.get("samples").getAsInt());
        row.addProperty("unit_detected", u.get("unit_detected").getAsString());
        utilization.add(row);
      }
      builder.addData("utilization", utilization);

      // Other CAN error entries: how much each increased, and how much of that while enabled
      var errors = new JsonArray();
      double enabledErrorTotal = 0;
      for (var name : otherErrorEntries) {
        var values = log.values().get(name);
        double increase = 0;
        double increaseEnabled = 0;
        double increaseUnknown = 0;
        Double previous = null;
        for (var tv : values) {
          if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;
          Double v = tv.value() instanceof Boolean b ? (b ? 1.0 : 0.0)
              : tv.value() instanceof Number n && Double.isFinite(n.doubleValue())
                  ? n.doubleValue() : null;
          if (v == null) continue;
          // A boolean counts each false->true; a number counts each increase
          double delta = previous == null ? (v > 0 ? v : 0) : v - previous;
          previous = v;
          if (delta <= 0) continue;
          increase += delta;
          switch (timeline.stateAt(tv.timestamp())) {
            case ENABLED -> increaseEnabled += delta;
            case UNKNOWN -> increaseUnknown += delta;
            default -> { }
          }
        }
        var row = new JsonObject();
        row.addProperty("entry", name);
        row.addProperty("type", log.entries().get(name).type());
        row.addProperty("error_count", increase);
        row.addProperty("errors_while_enabled", increaseEnabled);
        if (timeline.hasEnabledData()) {
          row.addProperty("errors_while_disabled", increase - increaseEnabled - increaseUnknown);
        }
        if (increaseUnknown > 0) row.addProperty("errors_state_unknown", increaseUnknown);
        errors.add(row);
        enabledErrorTotal += increaseEnabled;
      }
      for (var bus : buses) {
        for (var f : List.of(CanBusAnalysis.Field.BUS_OFF, CanBusAnalysis.Field.TX_FULL)) {
          var name = bus.entries().get(f);
          if (name == null) continue;
          var c = CanBusAnalysis.counter(
              CanBusAnalysis.window(log.values().get(name), startTime, endTime), timeline);
          if (c.has("increase_while_enabled")) {
            enabledErrorTotal += c.get("increase_while_enabled").getAsDouble();
          }
        }
      }
      builder.addData("errors", errors);
      builder.addProperty("enabled_error_total", enabledErrorTotal);
      if (!timeline.hasEnabledData()) {
        builder.addWarning("No DriverStation enabled entry: while_enabled figures are absent and "
            + "errors cannot be split by robot state.");
      }
      return builder.build();
    }
  }

  static class PredictBatteryHealthTool extends LogRequiringTool {
    @Override
    public String name() {
      return "predict_battery_health";
    }

    @Override
    public String description() {
      return "Battery and power-delivery evidence with a heuristic health score (0-100) and risk "
          + "level (MINIMAL/LOW/MODERATE/HIGH/CRITICAL). Facts first: voltage statistics over the "
          + "scope (default: enabled time when the log records it); brownouts from the roboRIO's "
          + "logged flag (e.g. /SystemStats/BrownedOut, with start and duration) or, when no flag "
          + "is logged, threshold crossings (basis stated); the brownout threshold from the log's "
          + "BrownoutVoltage entry when logged, else 6.8 V (roboRIO 1, stated); dips below "
          + "warning_threshold; and, when a total-current entry exists, the load line: battery "
          + "voltage regressed on total current, giving the effective source resistance (battery "
          + "internal resistance plus wiring and connectors) and open-circuit voltage. "
          + "observations state what the evidence is consistent with and what would distinguish "
          + "the causes; one log cannot tell a weak battery from high current draw or a bad "
          + "connection, so no replacement advice is given."
          + GUIDANCE_UNIVERSAL + GUIDANCE_POWER;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION
              + " Default: 'enabled' when the log records enabled state, else 'all'.", false)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .addNumberProperty("nominal_voltage", "Expected full battery voltage (default: 12.6V)", false, 12.6)
          .addNumberProperty("brownout_threshold", "Brownout threshold in volts (default: the log's "
              + "BrownoutVoltage entry when logged, else 6.8 V for roboRIO 1; roboRIO 2 is 6.3 V)", false, null)
          .addNumberProperty("warning_threshold", "Voltage below which a dip is reported (default: 9.0V)", false, 9.0)
          .build();
    }

    /** A scalar total-current entry (total, battery, or input current), lowest entry id. */
    static java.util.Optional<String> findTotalCurrentEntry(LogData log) {
      return log.entries().values().stream()
          .filter(e -> isNumericType(e.type()))
          .filter(e -> {
            var lower = e.name().toLowerCase(java.util.Locale.ROOT);
            return lower.contains("totalcurrent") || lower.contains("total_current")
                || lower.contains("batterycurrent") || lower.contains("battery_current");
          })
          .filter(e -> log.sampleCount(e.name()) > 0)
          .min(Comparator.comparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
          .map(org.triplehelix.wpilogmcp.log.EntryInfo::name);
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      double nominalVoltage = getOptDouble(arguments, "nominal_voltage", 12.6);
      double warningThreshold = getOptDouble(arguments, "warning_threshold", 9.0);
      var threshold = PowerFacts.threshold(log, getOptDouble(arguments, "brownout_threshold"));
      var timeline = MatchTimeline.of(log);
      var scopeArg = getOptString(arguments, "scope", null);
      if (scopeArg == null) scopeArg = timeline.hasEnabledData() ? "enabled" : "all";
      var scope = TimeScope.resolve(log, timeline, scopeArg, startTime, endTime);

      var voltageEntry = ToolUtils.selectVoltageEntry(log, null);
      if (voltageEntry.isEmpty()) {
        return ResponseBuilder.noMatch("No battery voltage entry with finite samples found.")
            .lookedFor(List.of("scalar numeric entries named with 'voltage', ranked battery > "
                + "input/bus > other > rails and regulators (the same choice power_analysis "
                + "and get_ds_timeline make)"))
            .hint("Use search_entries with pattern 'voltage' to find how this robot logs it.")
            .build();
      }
      var voltageValues = log.values().get(voltageEntry.get()).stream()
          .filter(tv -> scope.contains(tv.timestamp()))
          .filter(tv -> tv.value() instanceof Number n && Double.isFinite(n.doubleValue()))
          .toList();
      if (voltageValues.isEmpty()) {
        return ResponseBuilder.noMatch("No samples of " + voltageEntry.get() + " fall inside "
                + "the scope '" + scope.name() + "'.")
            .addData("scope", scope.toJson())
            .build();
      }
      var currentEntry = findTotalCurrentEntry(log);

      double minVoltage = Double.MAX_VALUE;
      double maxVoltage = -Double.MAX_VALUE;
      double sum = 0;
      double minTime = 0;
      for (var tv : voltageValues) {
        double v = ((Number) tv.value()).doubleValue();
        sum += v;
        if (v < minVoltage) {
          minVoltage = v;
          minTime = tv.timestamp();
        }
        maxVoltage = Math.max(maxVoltage, v);
      }
      double avgVoltage = sum / voltageValues.size();
      double voltageSag = nominalVoltage - minVoltage;

      // Brownouts: the roboRIO's own flag when logged, else threshold crossings
      var flag = PowerFacts.flagEntry(log);
      List<PowerFacts.Brownout> rioBrownouts = flag.map(f -> PowerFacts.brownouts(log, f, null,
          null).stream().filter(b -> scope.contains(b.start())).toList()).orElse(List.of());
      var crossings = detectVoltageEvents(voltageValues, threshold.volts());
      var dips = detectVoltageEvents(voltageValues, warningThreshold);
      int brownoutCount = flag.isPresent() ? rioBrownouts.size() : crossings.size();

      var loadLine = currentEntry.map(c -> loadLine(log, voltageValues, c)).orElse(null);
      var recoveryAnalysis = analyzeVoltageRecovery(voltageValues);

      int healthScore = calculateHealthScore(avgVoltage, nominalVoltage, minVoltage,
          brownoutCount, dips.size(), recoveryAnalysis);
      String riskLevel = brownoutCount > 0 ? "CRITICAL"
          : minVoltage < warningThreshold || healthScore < 30 ? "HIGH"
          : healthScore < 60 ? "MODERATE" : healthScore < 80 ? "LOW" : "MINIMAL";

      var response = success();
      response.addData("scope", scope.toJson());
      response.addInput("voltage", voltageEntry.get());
      currentEntry.ifPresent(c -> response.addInput("total_current", c));
      flag.ifPresent(f -> response.addInput("rio_brownout_flag", f));
      response.addProperty("health_score", healthScore);
      response.addProperty("health_score_basis", "heuristic: 100, minus 20 per brownout, 5 per "
          + "dip below warning_threshold, and penalties for a low average (below 88% of "
          + "nominal), a minimum below 10 V, and slow recovery; compare batteries across logs "
          + "rather than reading the number alone");
      response.addProperty("risk_level", riskLevel);

      var voltageStats = new JsonObject();
      voltageStats.addProperty("min_volts", minVoltage);
      voltageStats.addProperty("min_time_sec", minTime);
      voltageStats.addProperty("max_volts", maxVoltage);
      voltageStats.addProperty("avg_volts", avgVoltage);
      voltageStats.addProperty("voltage_sag", voltageSag);
      voltageStats.addProperty("samples", voltageValues.size());
      response.addData("voltage_stats", voltageStats);

      var thresholdJson = new JsonObject();
      threshold.addTo(thresholdJson);
      thresholdJson.entrySet().forEach(e -> response.addData(e.getKey(), e.getValue()));
      response.addProperty("brownout_events", brownoutCount);
      response.addProperty("brownout_basis", flag.isPresent()
          ? "rio_flag: intervals where " + flag.get() + " was true (the roboRIO disabled outputs)"
          : "voltage_threshold: crossings below " + threshold.volts() + " V (no roboRIO brownout "
              + "flag is logged, so whether outputs were disabled cannot be determined)");
      if (flag.isPresent()) {
        response.addData("rio_brownouts", PowerFacts.brownoutsJson(flag.get(), rioBrownouts));
      }
      response.addProperty("threshold_crossings", crossings.size());
      if (!crossings.isEmpty()) {
        response.addData("brownout_details", GSON.toJsonTree(crossings.stream().limit(10).toList()));
      }
      response.addProperty("warning_events", dips.size());
      if (recoveryAnalysis != null) response.addData("recovery_analysis", recoveryAnalysis);
      if (loadLine != null) {
        response.addData("load_line", loadLine);
      } else {
        response.addSkipped("load_line", currentEntry.isEmpty()
            ? "No total-current entry (TotalCurrent, BatteryCurrent) to regress voltage on."
            : "Too few samples or too little current variation in scope to fit voltage against "
                + currentEntry.get() + ".");
      }

      var observations = observations(brownoutCount, flag.isPresent(), rioBrownouts, crossings,
          minVoltage, minTime, avgVoltage, warningThreshold, voltageSag, loadLine, scope);
      response.addData("observations", GSON.toJsonTree(observations));
      // Compatibility: "recommendations" carries the same evidence-based statements
      response.addData("recommendations", GSON.toJsonTree(observations));

      var quality = DataQuality.fromValues(voltageValues);
      response.addDataQuality(quality).addDirectives(AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addGuidance("The health score is a heuristic; battery age, charge, connector "
              + "condition, and current draw all move it. Compare the same battery across logs."));
      return response.build();
    }

    static List<String> observations(int brownoutCount, boolean flagLogged,
        List<PowerFacts.Brownout> rioBrownouts, List<JsonObject> crossings, double minVoltage,
        double minTime, double avgVoltage, double warningThreshold, double voltageSag,
        JsonObject loadLine, TimeScope scope) {
      var out = new ArrayList<String>();
      if (flagLogged && brownoutCount > 0) {
        var parts = new ArrayList<String>();
        for (var b : rioBrownouts.stream().limit(5).toList()) {
          parts.add(String.format("%.2f s for %.3f s", b.start(), b.duration()));
        }
        out.add(brownoutCount + " roboRIO brownout(s) (" + String.join("; ", parts)
            + (brownoutCount > 5 ? "; ..." : "") + "). Candidate causes: high current draw at "
            + "those moments (check power_analysis channel peaks in the same windows), a "
            + "weak or undercharged battery, or high-resistance connections. One log cannot "
            + "distinguish them: compare this battery across logs and inspect connectors.");
      } else if (!flagLogged && brownoutCount > 0) {
        out.add(brownoutCount + " crossing(s) below the brownout threshold (first at "
            + String.format("%.2f", crossings.get(0).get("start_time").getAsDouble()) + " s). "
            + "The log has no roboRIO brownout flag, so whether outputs were disabled cannot be "
            + "determined. Same candidate causes: high current draw, a weak battery, or "
            + "connections.");
      }
      if (minVoltage < warningThreshold) {
        out.add(String.format("Minimum voltage %.2f V at %.2f s, below the %.1f V warning "
            + "threshold.", minVoltage, minTime, warningThreshold));
      }
      if (avgVoltage < 11.5) {
        out.add(String.format("Average voltage over the scope (%s) was %.2f V. This is "
            + "consistent with a partly discharged battery or sustained high load; the charge "
            + "at the start of the log and other logs with this battery would tell them apart.",
            scope.name(), avgVoltage));
      }
      if (loadLine != null) {
        double r = loadLine.get("resistance_ohm").getAsDouble();
        out.add(String.format("Load line: voltage falls %.1f mV per amp of total current (effective "
            + "source resistance %.4f ohm, r^2 %.2f, n %d). This combines battery internal "
            + "resistance, wiring, and connectors; compare it across batteries and logs.",
            r * 1000, r, loadLine.get("r_squared").getAsDouble(),
            loadLine.get("samples").getAsInt()));
      }
      if (out.isEmpty()) {
        out.add(String.format("No brownouts and no dips below %.1f V in the scope; voltage sag "
            + "%.2f V below nominal.", warningThreshold, voltageSag));
      }
      return out;
    }

    /**
     * Least-squares fit of battery voltage against total current (the current held at each
     * voltage sample): V = V0 - R * I. Needs 30 aligned samples and a 10 A current range.
     */
    static JsonObject loadLine(LogData log, List<TimestampedValue> voltage, String currentEntry) {
      var current = log.values().get(currentEntry);
      if (current == null || current.isEmpty()) return null;
      var xs = new ArrayList<Double>();
      var ys = new ArrayList<Double>();
      for (var tv : voltage) {
        var i = ToolUtils.getValueAtTimeZoh(current, tv.timestamp());
        if (!(i instanceof Number n) || !Double.isFinite(n.doubleValue())) continue;
        xs.add(n.doubleValue());
        ys.add(((Number) tv.value()).doubleValue());
      }
      int n = xs.size();
      if (n < 30) return null;
      double minI = xs.stream().mapToDouble(Double::doubleValue).min().orElse(0);
      double maxI = xs.stream().mapToDouble(Double::doubleValue).max().orElse(0);
      if (maxI - minI < 10) return null;
      double mx = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
      double my = ys.stream().mapToDouble(Double::doubleValue).average().orElse(0);
      double sxx = 0, sxy = 0, syy = 0;
      for (int k = 0; k < n; k++) {
        double dx = xs.get(k) - mx;
        double dy = ys.get(k) - my;
        sxx += dx * dx;
        sxy += dx * dy;
        syy += dy * dy;
      }
      if (sxx <= 0 || syy <= 0) return null;
      double slope = sxy / sxx;
      var o = new JsonObject();
      o.addProperty("current_entry", currentEntry);
      o.addProperty("resistance_ohm", -slope);
      o.addProperty("open_circuit_voltage", my - slope * mx);
      o.addProperty("r_squared", (sxy * sxy) / (sxx * syy));
      o.addProperty("samples", n);
      o.addProperty("current_range_a", maxI - minI);
      return o;
    }

    private java.util.List<JsonObject> detectVoltageEvents(
        java.util.List<TimestampedValue> voltageValues,
        double threshold) {
      var events = new ArrayList<JsonObject>();
      boolean inEvent = false;
      double eventStartTime = 0;
      double eventMinVoltage = Double.MAX_VALUE;

      for (var tv : voltageValues) {
        var voltage = toDouble(tv.value());
        if (voltage == null) continue;

        // Hysteresis: enter below threshold, exit only above threshold + 0.2V
        double hysteresis = 0.2;
        if (voltage < threshold && !inEvent) {
          inEvent = true;
          eventStartTime = tv.timestamp();
          eventMinVoltage = voltage;
        } else if (voltage < threshold && inEvent) {
          eventMinVoltage = Math.min(eventMinVoltage, voltage);
        } else if (voltage >= threshold + hysteresis && inEvent) {
          var event = new JsonObject();
          event.addProperty("start_time", eventStartTime);
          event.addProperty("end_time", tv.timestamp());
          event.addProperty("duration", tv.timestamp() - eventStartTime);
          event.addProperty("min_voltage", eventMinVoltage);
          events.add(event);
          inEvent = false;
        }
      }

      // Emit open-ended event if voltage was still below threshold at end of the data
      if (inEvent && !voltageValues.isEmpty()) {
        double lastTime = voltageValues.get(voltageValues.size() - 1).timestamp();
        var event = new JsonObject();
        event.addProperty("start_time", eventStartTime);
        event.addProperty("end_time", lastTime);
        event.addProperty("duration", lastTime - eventStartTime);
        event.addProperty("min_voltage", eventMinVoltage);
        event.addProperty("open_at_end", true);
        events.add(event);
      }
      return events;
    }

    /** How long the voltage takes to recover 90% of a drop of more than 0.5 V (up to 2 s). */
    private JsonObject analyzeVoltageRecovery(java.util.List<TimestampedValue> voltageValues) {
      var recoveryTimes = new ArrayList<Double>();
      for (int i = 1; i < voltageValues.size() - 1; i++) {
        var voltageBefore = toDouble(voltageValues.get(i - 1).value());
        var voltageAtLoad = toDouble(voltageValues.get(i).value());
        if (voltageBefore == null || voltageAtLoad == null) continue;
        double voltageDrop = voltageBefore - voltageAtLoad;
        if (voltageDrop > 0.5) {
          double dropTime = voltageValues.get(i).timestamp();
          double recoveryTarget = voltageAtLoad + (voltageDrop * 0.9);
          for (int j = i + 1; j < voltageValues.size(); j++) {
            double elapsed = voltageValues.get(j).timestamp() - dropTime;
            if (elapsed > 2.0) break;
            var recoveredVoltage = toDouble(voltageValues.get(j).value());
            if (recoveredVoltage != null && recoveredVoltage >= recoveryTarget) {
              recoveryTimes.add(elapsed);
              break;
            }
          }
        }
      }
      if (recoveryTimes.isEmpty()) return null;
      var analysis = new JsonObject();
      analysis.addProperty("avg_recovery_sec",
          recoveryTimes.stream().mapToDouble(d -> d).average().orElse(0));
      analysis.addProperty("max_recovery_sec",
          recoveryTimes.stream().mapToDouble(d -> d).max().orElse(0));
      analysis.addProperty("sample_count", recoveryTimes.size());
      return analysis;
    }

    /**
     * Heuristic battery health score (0-100), kept by design (see CODE_REVIEW_REJECTION.md):
     * start at 100; average below 88% of nominal: −(deficit × 150); each brownout: −20; each dip
     * below the warning threshold that is not a brownout: −5; slow recovery (> 0.5 s average):
     * −(excess × 20); minimum below 10 V: −(deficit × 10).
     */
    private int calculateHealthScore(double avgVoltage, double nominalVoltage, double minVoltage,
        int brownoutEvents, int sagEvents, JsonObject recoveryAnalysis) {
      int score = 100;
      double voltageRatio = nominalVoltage > 0 ? avgVoltage / nominalVoltage : 1.0;
      if (voltageRatio < 0.88) score -= (int) ((0.88 - voltageRatio) * 150);
      score -= brownoutEvents * 20;
      score -= Math.max(0, sagEvents - brownoutEvents) * 5;
      if (recoveryAnalysis != null) {
        double avgRecovery = recoveryAnalysis.get("avg_recovery_sec").getAsDouble();
        if (avgRecovery > 0.5) score -= (int) ((avgRecovery - 0.5) * 20);
      }
      if (minVoltage < 10.0) score -= (int) ((10.0 - minVoltage) * 10);
      return Math.max(0, Math.min(100, score));
    }
  }

  /**
   * Provides year-specific FRC game information for contextual log analysis.
   */
  static class GetGameInfoTool extends ToolBase {
    @Override
    public String name() {
      return "get_game_info";
    }

    @Override
    public String description() {
      return "Get year-specific FRC game information (match timing, scoring values, field geometry, "
          + "game pieces, and analysis hints). Use this to understand the context of a log file: "
          + "what the match phases are, what scoring actions look like, and what mechanisms to expect. "
          + "Defaults to the current season if no year is specified.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addIntegerProperty("season", "FRC season year (e.g., 2026). Defaults to current year.", false, null)
          .build();
    }

    @Override
    protected JsonElement executeInternal(JsonObject arguments) throws Exception {
      var kb = org.triplehelix.wpilogmcp.game.GameKnowledgeBase.getInstance();

      int season = arguments.has("season") && !arguments.get("season").isJsonNull()
          ? arguments.get("season").getAsInt()
          : java.time.Year.now().getValue();

      var game = kb.getGame(season);
      if (game == null) {
        var result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", "No game data available for season " + season);
        var available = kb.availableSeasons();
        if (available.length > 0) {
          var arr = new JsonArray();
          for (int s : available) arr.add(s);
          result.add("available_seasons", arr);
        }
        return result;
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("season", game.season());
      result.addProperty("game_name", game.gameName());
      result.add("match_timing", game.raw().getAsJsonObject("match_timing"));
      result.add("scoring", game.scoring());
      result.add("field_geometry", game.raw().getAsJsonObject("field_geometry"));
      result.add("game_pieces", game.gamePieces());
      if (game.analysisHints() != null) {
        result.add("analysis_hints", game.analysisHints());
      }
      if (game.raw().has("typical_mechanisms")) {
        result.add("typical_mechanisms", game.raw().getAsJsonArray("typical_mechanisms"));
      }
      if (game.raw().has("hub_mechanics")) {
        result.add("hub_mechanics", game.raw().getAsJsonObject("hub_mechanics"));
      }
      return result;
    }
  }
}
