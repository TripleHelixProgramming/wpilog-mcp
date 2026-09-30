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
      return "Analyze vision data three ways. observation_streams: struct arrays of pose "
          + "observations (for example the AdvantageKit vision template's "
          + "/Vision/Camera<N>/PoseObservations from PhotonVision or Limelight), found by content "
          + "(each record holds a timestamp and a pose), one stream per camera, with record and "
          + "observation counts, observation rate, tag-count and ambiguity distributions, latency "
          + "(log time minus the observation's own timestamp), and the residual between each "
          + "observation and the robot pose at the observation's timestamp (robot_pose_entry, "
          + "chosen or passed as pose_entry). target_acquisition: Limelight-style has-target "
          + "entries (tv, hasTarget, targetValid) with acquisition rate and flicker. pose_jumps: "
          + "steps larger than jump_threshold in scalar pose entries. vision_prefix limits the "
          + "vision entries only (case-insensitive); the robot pose may live elsewhere. Returns "
          + "no_match with what was searched when none of these exist."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("vision_prefix", "string",
              "Only vision entries under this prefix (case-insensitive), e.g. '/Vision'", false)
          .addProperty("pose_entry", "string",
              "Robot pose entry (struct:Pose2d or Pose3d) for residuals and jump detection; "
                  + "default: the scalar Pose2d with the most samples that is not a vision entry", false)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .addNumberProperty("jump_threshold", "Distance threshold for jump detection (meters)", false, 0.5)
          .addNumberProperty("flicker_window", "Time window for flicker detection (seconds)", false, 0.5)
          .build();
    }

    static boolean underPrefix(String name, String prefix) {
      return prefix == null || name.toLowerCase().startsWith(prefix.toLowerCase());
    }

    static boolean isScalarPose(org.triplehelix.wpilogmcp.log.EntryInfo e) {
      return e.type().equals("struct:Pose2d") || e.type().equals("struct:Pose3d");
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var visionPrefix = getOptString(arguments, "vision_prefix", null);
      var poseArg = getOptString(arguments, "pose_entry", null);
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      double jumpThreshold = getOptDouble(arguments, "jump_threshold", 0.5);
      double flickerWindow = getOptDouble(arguments, "flicker_window", 0.5);
      var entries = log.entries().values().stream()
          .sorted(Comparator.comparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id)).toList();

      // Robot pose: explicit, else the scalar Pose2d with the most samples outside vision entries
      String robotPose = poseArg;
      if (robotPose != null) {
        requireEntry(log, robotPose);
        if (!isScalarPose(log.entries().get(robotPose))) {
          throw new IllegalArgumentException("pose_entry " + robotPose + " is "
              + log.entries().get(robotPose).type() + ", not struct:Pose2d or struct:Pose3d");
        }
      } else {
        robotPose = entries.stream()
            .filter(e -> e.type().equals("struct:Pose2d"))
            .filter(e -> !e.name().toLowerCase().contains("vision"))
            .filter(e -> log.sampleCount(e.name()) >= 2)
            .max(Comparator.comparingInt((org.triplehelix.wpilogmcp.log.EntryInfo e) ->
                    log.sampleCount(e.name()))
                .thenComparingInt(e -> -e.id()))
            .map(org.triplehelix.wpilogmcp.log.EntryInfo::name).orElse(null);
      }

      // Vision entries (prefix applies only here)
      var targetEntries = new ArrayList<String>();
      var streams = new ArrayList<String>();
      for (var e : entries) {
        if (!underPrefix(e.name(), visionPrefix)) continue;
        var lower = e.name().toLowerCase();
        if (lower.contains("hastarget") || lower.endsWith("/tv") || lower.endsWith(".tv")
            || lower.contains("targetvalid")) {
          targetEntries.add(e.name());
        } else if (e.type().startsWith("struct:") && e.type().endsWith("[]")
            && isObservationStream(log.values().get(e.name()))) {
          streams.add(e.name());
        }
      }
      // Jumps: the robot pose plus scalar vision pose estimates
      var jumpEntries = new ArrayList<String>();
      if (robotPose != null) jumpEntries.add(robotPose);
      for (var e : entries) {
        if (!isScalarPose(e) || e.name().equals(robotPose) || log.sampleCount(e.name()) < 2) continue;
        var lower = e.name().toLowerCase();
        if (lower.contains("vision") && underPrefix(e.name(), visionPrefix)) jumpEntries.add(e.name());
      }

      if (targetEntries.isEmpty() && streams.isEmpty() && jumpEntries.isEmpty()) {
        return ResponseBuilder.noMatch("No vision data or pose entries found"
                + (visionPrefix != null ? " (vision entries under " + visionPrefix + ")" : "") + ".")
            .lookedFor(List.of(
                "struct arrays whose records hold a timestamp and a pose (pose observations)",
                "has-target entries: names containing hasTarget or targetValid, or ending in /tv",
                "scalar struct:Pose2d/Pose3d entries (robot pose; vision pose estimates)"))
            .hint("Use search_entries with pattern 'vision' or 'camera', then pass vision_prefix "
                + "or pose_entry.")
            .build();
      }

      var builder = success();
      if (robotPose != null) builder.addInput("robot_pose", robotPose);
      if (startTime != null || endTime != null) builder.addInputWindow(startTime, endTime);

      var targetAnalysis = new JsonArray();
      for (var name : targetEntries) {
        var a = targetAcquisition(log.values().get(name), startTime, endTime, flickerWindow);
        a.addProperty("entry", name);
        targetAnalysis.add(a);
      }
      builder.addData("target_acquisition", targetAnalysis);

      var streamsJson = new JsonArray();
      var robotPoseValues = robotPose != null ? log.values().get(robotPose) : null;
      for (var name : streams) {
        var o = observationStream(name, log.values().get(name), startTime, endTime,
            robotPose, robotPoseValues);
        streamsJson.add(o);
      }
      builder.addData("observation_streams", streamsJson);
      if (streams.isEmpty() && targetEntries.isEmpty()) {
        builder.addSkipped("observation_streams", "No pose observation streams or has-target "
            + "entries" + (visionPrefix != null ? " under " + visionPrefix : "")
            + "; only pose jumps were checked.");
      }

      var jumps = new ArrayList<JsonObject>();
      int unreadable = 0;
      for (var name : jumpEntries) {
        Object last = null;
        for (var tv : log.values().get(name)) {
          if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;
          if (last != null) {
            var distance = StructFields.planarDistance(last, tv.value());
            if (distance == null) {
              unreadable++;
            } else if (distance > jumpThreshold) {
              var jump = new JsonObject();
              jump.addProperty("timestamp", tv.timestamp());
              jump.addProperty("entry", name);
              jump.addProperty("distance", distance);
              jumps.add(jump);
            }
          }
          last = tv.value();
        }
      }
      var jumpList = new JsonArray();
      jumps.stream().limit(100).forEach(jumpList::add);
      builder.addLimitedList("pose_jumps", jumpList, jumps.size(), 100);
      builder.addProperty("jump_count", jumps.size());
      builder.addData("pose_entries_checked", GSON.toJsonTree(jumpEntries));
      if (unreadable > 0) builder.addProperty("unreadable_pose_samples", unreadable);

      List<TimestampedValue> qualitySource = !streams.isEmpty() ? log.values().get(streams.get(0))
          : !targetEntries.isEmpty() ? log.values().get(targetEntries.get(0))
          : !jumpEntries.isEmpty() ? log.values().get(jumpEntries.get(0)) : null;
      if (qualitySource != null) {
        var quality = DataQuality.fromValues(qualitySource);
        builder.addDataQuality(quality)
            .addDirectives(AnalysisDirectives.fromQuality(quality).addSingleMatchCaveat());
      }
      return builder.build();
    }

    /** A struct array whose non-empty records hold a timestamp and a readable pose. */
    static boolean isObservationStream(List<TimestampedValue> values) {
      if (values == null) return false;
      for (var tv : values) {
        var elements = StructFields.elements(tv.value());
        if (elements.isEmpty()) continue;
        var first = elements.get(0);
        return StructFields.number(first, "timestamp") != null
            && StructFields.number(first, "pose.translation.x", "pose_x") != null;
      }
      return false;
    }

    static JsonObject targetAcquisition(List<TimestampedValue> values, Double start, Double end,
        double flickerWindow) {
      int totalSamples = 0;
      int validSamples = 0;
      int flickerCount = 0;
      Double lastTransition = null;
      Boolean lastState = null;
      for (var tv : values) {
        if ((start != null && tv.timestamp() < start) || (end != null && tv.timestamp() > end)) {
          continue;
        }
        totalSamples++;
        boolean hasTarget = tv.value() instanceof Boolean b ? b
            : tv.value() instanceof Number n && n.doubleValue() > 0.5;
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
      analysis.addProperty("total_samples", totalSamples);
      analysis.addProperty("valid_samples", validSamples);
      analysis.addProperty("acquisition_rate", totalSamples > 0 ? (double) validSamples / totalSamples : 0);
      analysis.addProperty("flicker_events", flickerCount);
      return analysis;
    }

    static JsonObject observationStream(String name, List<TimestampedValue> values, Double start,
        Double end, String robotPose, List<TimestampedValue> robotPoseValues) {
      int records = 0;
      int withObservations = 0;
      int observations = 0;
      double first = Double.NaN;
      double last = Double.NaN;
      var tagCounts = new java.util.TreeMap<Integer, Integer>();
      var latencies = new ArrayList<Double>();
      var ambiguities = new ArrayList<Double>();
      var residuals = new ArrayList<Double>();
      for (var tv : values) {
        if ((start != null && tv.timestamp() < start) || (end != null && tv.timestamp() > end)) {
          continue;
        }
        records++;
        if (Double.isNaN(first)) first = tv.timestamp();
        last = tv.timestamp();
        var elements = StructFields.elements(tv.value());
        if (!elements.isEmpty()) withObservations++;
        for (var obs : elements) {
          observations++;
          var tags = StructFields.number(obs, "tagCount");
          if (tags != null) tagCounts.merge(tags.intValue(), 1, Integer::sum);
          var ambiguity = StructFields.number(obs, "ambiguity");
          if (ambiguity != null) ambiguities.add(ambiguity);
          var obsTime = StructFields.number(obs, "timestamp");
          if (obsTime == null) continue;
          latencies.add((tv.timestamp() - obsTime) * 1000.0);
          if (robotPoseValues != null) {
            var ox = StructFields.number(obs, "pose.translation.x", "pose_x");
            var oy = StructFields.number(obs, "pose.translation.y", "pose_y");
            var rx = interpolate(robotPoseValues, obsTime, true);
            var ry = interpolate(robotPoseValues, obsTime, false);
            if (ox != null && oy != null && rx != null && ry != null) {
              residuals.add(Math.hypot(ox - rx, oy - ry));
            }
          }
        }
      }
      var o = new JsonObject();
      o.addProperty("entry", name);
      var parent = name.substring(0, Math.max(0, name.lastIndexOf('/')));
      o.addProperty("camera", parent.substring(parent.lastIndexOf('/') + 1));
      o.addProperty("records", records);
      o.addProperty("records_with_observations", withObservations);
      o.addProperty("observation_count", observations);
      double span = last - first;
      if (span > 0) o.addProperty("observations_per_second", observations / span);
      if (!tagCounts.isEmpty()) {
        var t = new JsonObject();
        tagCounts.forEach((k, v) -> t.addProperty(String.valueOf(k), v));
        o.add("tag_count_distribution", t);
      }
      if (!ambiguities.isEmpty()) o.add("ambiguity", distribution(ambiguities, ""));
      if (!latencies.isEmpty()) {
        var l = distribution(latencies, "_ms");
        l.addProperty("basis", "log timestamp minus the observation's own timestamp");
        o.add("latency", l);
      }
      if (!residuals.isEmpty()) {
        var r = distribution(residuals, "_m");
        r.addProperty("robot_pose_entry", robotPose);
        r.addProperty("basis", "planar distance to the robot pose interpolated at the "
            + "observation's timestamp; the robot pose may itself include vision corrections");
        o.add("residual_vs_robot_pose", r);
      }
      return o;
    }

    static JsonObject distribution(List<Double> values, String unit) {
      var sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
      var o = new JsonObject();
      o.addProperty("n", sorted.length);
      o.addProperty("median" + unit, percentile(sorted, 0.5));
      o.addProperty("p95" + unit, percentile(sorted, 0.95));
      o.addProperty("max" + unit, sorted[sorted.length - 1]);
      return o;
    }

    /** Robot pose x (or y) linearly interpolated at t; null outside the logged range. */
    static Double interpolate(List<TimestampedValue> poses, double t, boolean x) {
      int n = poses.size();
      if (n == 0 || t < poses.get(0).timestamp() || t > poses.get(n - 1).timestamp()) return null;
      int lo = 0;
      int hi = n - 1;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (poses.get(mid).timestamp() <= t) lo = mid; else hi = mid - 1;
      }
      var a = poses.get(lo);
      var va = x ? StructFields.poseX(a.value()) : StructFields.poseY(a.value());
      if (lo == n - 1 || a.timestamp() == t) return va;
      var b = poses.get(lo + 1);
      var vb = x ? StructFields.poseX(b.value()) : StructFields.poseY(b.value());
      if (va == null || vb == null) return null;
      double f = (t - a.timestamp()) / (b.timestamp() - a.timestamp());
      return va + f * (vb - va);
    }
  }

  static class ProfileMechanismTool extends LogRequiringTool {
    /** Mechanism roles, resolved from entry names or passed explicitly. */
    enum Role {
      SETPOINT("setpoint_entry", "(setpoint|goal|target|reference|desired|commanded)"),
      MEASUREMENT("measurement_entry", "(position|actual|measured|angle|height|distance|rotations)"),
      VELOCITY("velocity_entry", "(velocity|speed|rpm|rps)"),
      CURRENT("current_entry", "(current|amps)"),
      TEMPERATURE("temperature_entry", "(temp|temperature|celsius)");

      final String param;
      final java.util.regex.Pattern pattern;

      Role(String param, String regex) {
        this.param = param;
        this.pattern = java.util.regex.Pattern.compile("(?i)" + regex);
      }

      String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
      }
    }

    static final java.util.regex.Pattern NOT_MEASUREMENT = java.util.regex.Pattern.compile(
        "(?i)(setpoint|goal|target|reference|desired|commanded|velocity|speed|current|amps|volt|"
            + "temp|celsius|applied|output)");

    @Override
    public String name() { return "profile_mechanism"; }

    @Override
    public String description() {
      return "Profile one closed-loop mechanism from its numeric entries: following error "
          + "(measurement minus the setpoint in force, as RMSE, bias, and maximum), step response "
          + "for each setpoint step (settling time into a 5% band of the step, percent overshoot "
          + "of the step), stall events (current above stall_current_threshold while |velocity| "
          + "is below stall_velocity_threshold), and motor temperature (maximum and final). "
          + "Entries are found among names containing mechanism_name (case-insensitive substring "
          + "anywhere in the name) by role — setpoint (setpoint/goal/target/reference), "
          + "measurement (position/angle/height/...), velocity, current, temperature — and "
          + "grouped by the stem before the role word, so /Drive/ModuleFrontLeft/DriveVelocity and "
          + "TurnVelocity are different stems; the first stem is used and other_stems lists the "
          + "rest. roles names every entry used; any role can be passed explicitly "
          + "(setpoint_entry, measurement_entry, velocity_entry, current_entry, "
          + "temperature_entry). Sections without their entries are listed in skipped. Returns "
          + "no_match when nothing matches."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MECHANISM;
    }

    @Override
    protected JsonObject toolSchema() {
      var b = new SchemaBuilder()
          .addProperty("mechanism_name", "string",
              "Text contained in the mechanism's entry names (case-insensitive), e.g. 'Elevator' or 'ModuleFrontLeft/Drive'", false)
          .addNumberProperty("start_time", "Start timestamp", false, null)
          .addNumberProperty("end_time", "End timestamp", false, null)
          .addNumberProperty("stall_current_threshold", "Current threshold for stall (default: 30A)", false, 30.0)
          .addNumberProperty("stall_velocity_threshold",
              "|velocity| below this counts as stopped, in the velocity entry's units (default: 0.01)", false, 0.01);
      for (var role : Role.values()) {
        b.addProperty(role.param, "string", "Explicit " + role.key() + " entry", false);
      }
      return b.build();
    }

    /** The name's leaf up to its role word ("DriveVelocityRadPerSec" -> "drive"). */
    static String stem(String name) {
      var leaf = name.substring(name.lastIndexOf('/') + 1);
      int cut = leaf.length();
      for (var role : Role.values()) {
        var m = role.pattern.matcher(leaf);
        if (m.find()) cut = Math.min(cut, m.start());
      }
      var vm = java.util.regex.Pattern.compile("(?i)(volt|applied|output)").matcher(leaf);
      if (vm.find()) cut = Math.min(cut, vm.start());
      return leaf.substring(0, cut).toLowerCase(java.util.Locale.ROOT);
    }

    static Role roleOf(String name) {
      var leaf = name.substring(name.lastIndexOf('/') + 1);
      if (Role.SETPOINT.pattern.matcher(leaf).find()) return Role.SETPOINT;
      if (Role.TEMPERATURE.pattern.matcher(leaf).find()) return Role.TEMPERATURE;
      if (RobotAnalysisTools.PowerAnalysisTool.isCurrentEntryName(name)) return Role.CURRENT;
      if (Role.VELOCITY.pattern.matcher(leaf).find()) return Role.VELOCITY;
      if (Role.MEASUREMENT.pattern.matcher(leaf).find() && !NOT_MEASUREMENT.matcher(leaf).find()) {
        return Role.MEASUREMENT;
      }
      return null;
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var mechanismName = getOptString(arguments, "mechanism_name", null);
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      double stallCurrentThreshold = getOptDouble(arguments, "stall_current_threshold", 30.0);
      double stallVelocityThreshold = getOptDouble(arguments, "stall_velocity_threshold", 0.01);

      var explicit = new java.util.EnumMap<Role, String>(Role.class);
      for (var role : Role.values()) {
        var name = getOptString(arguments, role.param, null);
        if (name == null) continue;
        requireEntry(log, name);
        if (!isNumericType(log.entries().get(name).type())) {
          throw new IllegalArgumentException(role.param + " " + name + " is "
              + log.entries().get(name).type() + "; profile_mechanism needs scalar numeric entries");
        }
        explicit.put(role, name);
      }
      if (mechanismName == null && explicit.isEmpty()) {
        throw new IllegalArgumentException("Pass mechanism_name, or the role entries "
            + "(setpoint_entry, measurement_entry, velocity_entry, current_entry, "
            + "temperature_entry)");
      }

      // Candidates by stem: stem -> role -> entry (lowest id first)
      var byStem = new LinkedHashMap<String, java.util.EnumMap<Role, String>>();
      if (mechanismName != null) {
        var lowerName = mechanismName.toLowerCase(java.util.Locale.ROOT);
        log.entries().values().stream()
            .filter(e -> isNumericType(e.type()))
            .filter(e -> e.name().toLowerCase(java.util.Locale.ROOT).contains(lowerName))
            .filter(e -> log.sampleCount(e.name()) > 0)
            .sorted(Comparator.comparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
            .forEach(e -> {
              var role = roleOf(e.name());
              if (role == null) return;
              byStem.computeIfAbsent(stem(e.name()), k -> new java.util.EnumMap<>(Role.class))
                  .putIfAbsent(role, e.name());
            });
      }
      String chosenStem = byStem.entrySet().stream()
          .max(Comparator.comparingInt((java.util.Map.Entry<String,
              java.util.EnumMap<Role, String>> en) -> en.getValue().size()))
          .map(java.util.Map.Entry::getKey).orElse(null);
      var roles = new java.util.EnumMap<Role, String>(Role.class);
      if (chosenStem != null) roles.putAll(byStem.get(chosenStem));
      roles.putAll(explicit);

      if (roles.isEmpty()) {
        return ResponseBuilder.noMatch("No numeric entries containing '" + mechanismName
                + "' with a recognizable role.")
            .lookedFor(List.of("scalar numeric entries whose name contains the mechanism name, "
                + "with a leaf naming a setpoint/goal/target, position/angle/height, "
                + "velocity/speed, current/amps, or temperature"))
            .hint("Use search_entries with the mechanism name, then pass the entries "
                + "explicitly (setpoint_entry, measurement_entry, ...).")
            .build();
      }

      var builder = success();
      if (mechanismName != null) builder.addProperty("mechanism", mechanismName);
      var rolesJson = new JsonObject();
      for (var role : Role.values()) {
        rolesJson.addProperty(role.key(), roles.get(role));
      }
      builder.addData("roles", rolesJson);
      if (chosenStem != null) builder.addProperty("stem", chosenStem);
      var otherStems = byStem.keySet().stream().filter(k -> !k.equals(chosenStem)).toList();
      if (!otherStems.isEmpty()) {
        builder.addData("other_stems", GSON.toJsonTree(otherStems));
        builder.addWarning("'" + mechanismName + "' matches several mechanisms by stem ("
            + (chosenStem.isEmpty() ? "(none)" : chosenStem) + " used; also "
            + String.join(", ", otherStems.stream().map(x -> x.isEmpty() ? "(none)" : x)
                .toList()) + "). Use a more specific mechanism_name or pass the entries.");
      }
      roles.forEach((role, entry) -> builder.addInput(role.key(), entry));
      if (startTime != null || endTime != null) builder.addInputWindow(startTime, endTime);

      var setpoint = roles.get(Role.SETPOINT);
      var measurement = roles.get(Role.MEASUREMENT);
      if (setpoint != null && measurement != null) {
        var fe = followingError(window(log.values().get(setpoint), startTime, endTime),
            window(log.values().get(measurement), startTime, endTime));
        if (fe != null) {
          fe.addProperty("setpoint_entry", setpoint);
          fe.addProperty("measurement_entry", measurement);
          builder.addData("following_error", fe);
        } else {
          builder.addSkipped("following_error", "No measurement samples with a setpoint in "
              + "force inside the window.");
        }
      } else {
        builder.addSkipped("following_error", "Needs both a setpoint and a measurement entry; "
            + "missing " + (setpoint == null ? "setpoint" : "measurement") + ".");
      }

      var velocity = roles.get(Role.VELOCITY);
      var current = roles.get(Role.CURRENT);
      if (velocity != null && current != null) {
        var stalls = detectStalls(window(log.values().get(velocity), startTime, endTime),
            log.values().get(current), stallCurrentThreshold, stallVelocityThreshold);
        var list = new JsonArray();
        stalls.stream().limit(50).forEach(list::add);
        builder.addLimitedList("stall_events", list, stalls.size(), 50);
        builder.addProperty("stall_count", stalls.size());
      } else {
        builder.addSkipped("stall_events", "Needs a velocity and a current entry; missing "
            + (velocity == null ? "velocity" : "current") + ".");
      }

      var temperature = roles.get(Role.TEMPERATURE);
      if (temperature != null) {
        var values = window(log.values().get(temperature), startTime, endTime);
        if (!values.isEmpty()) {
          var max = values.stream().max(Comparator.comparingDouble(
              tv -> ((Number) tv.value()).doubleValue())).orElseThrow();
          var t = new JsonObject();
          t.addProperty("entry", temperature);
          t.addProperty("max", ((Number) max.value()).doubleValue());
          t.addProperty("max_time_sec", max.timestamp());
          t.addProperty("first", ((Number) values.get(0).value()).doubleValue());
          t.addProperty("last", ((Number) values.get(values.size() - 1).value()).doubleValue());
          builder.addData("temperature", t);
        }
      } else {
        builder.addSkipped("temperature", "No temperature entry for this mechanism.");
      }

      var qualityEntry = measurement != null ? measurement : velocity;
      if (qualityEntry != null) {
        var quality = DataQuality.fromValues(window(log.values().get(qualityEntry), startTime,
            endTime));
        builder.addDataQuality(quality)
            .addDirectives(AnalysisDirectives.fromQuality(quality)
                .addSingleMatchCaveat()
                .addFollowup("Use moi_regression for mechanism inertia estimation"));
      }
      return builder.build();
    }

    /** Finite numeric samples inside [start, end]. */
    static List<TimestampedValue> window(List<TimestampedValue> values, Double start, Double end) {
      var out = new ArrayList<TimestampedValue>();
      for (var tv : values) {
        if ((start != null && tv.timestamp() < start) || (end != null && tv.timestamp() > end)) {
          continue;
        }
        if (tv.value() instanceof Number n && Double.isFinite(n.doubleValue())) out.add(tv);
      }
      return out;
    }

    /**
     * Error of each measurement against the setpoint in force (held until the next setpoint
     * sample), and the response to each setpoint step.
     */
    static JsonObject followingError(List<TimestampedValue> setpoints,
        List<TimestampedValue> measurements) {
      if (setpoints.isEmpty() || measurements.isEmpty()) return null;
      double sumSq = 0;
      double sum = 0;
      double maxAbs = 0;
      int n = 0;
      for (var m : measurements) {
        var sp = ToolUtils.getValueAtTimeZoh(setpoints, m.timestamp());
        if (!(sp instanceof Number s)) continue;
        double err = ((Number) m.value()).doubleValue() - s.doubleValue();
        sumSq += err * err;
        sum += err;
        maxAbs = Math.max(maxAbs, Math.abs(err));
        n++;
      }
      if (n == 0) return null;
      var o = new JsonObject();
      o.addProperty("rmse", Math.sqrt(sumSq / n));
      o.addProperty("mean_error", sum / n);
      o.addProperty("max_abs_error", maxAbs);
      o.addProperty("samples", n);

      // Steps: a setpoint change larger than 5% of the previous value (at least 0.01)
      var stepTimes = new ArrayList<double[]>(); // {time, from, to}
      double previous = ((Number) setpoints.get(0).value()).doubleValue();
      for (var sp : setpoints) {
        double v = ((Number) sp.value()).doubleValue();
        if (Math.abs(v - previous) > Math.max(Math.abs(previous) * 0.05, 0.01)) {
          stepTimes.add(new double[] {sp.timestamp(), previous, v});
        }
        previous = v;
      }
      var settling = new ArrayList<Double>();
      var overshoots = new ArrayList<Double>();
      var details = new JsonArray();
      for (int k = 0; k < stepTimes.size(); k++) {
        double t0 = stepTimes.get(k)[0];
        double from = stepTimes.get(k)[1];
        double to = stepTimes.get(k)[2];
        double tEnd = k + 1 < stepTimes.size() ? stepTimes.get(k + 1)[0] : Double.MAX_VALUE;
        double step = to - from;
        double band = Math.max(Math.abs(step) * 0.05, 1e-9);
        double worstOvershoot = 0;
        Double settledAt = null;
        for (var m : measurements) {
          double t = m.timestamp();
          if (t < t0 || t >= tEnd) continue;
          double v = ((Number) m.value()).doubleValue();
          worstOvershoot = Math.max(worstOvershoot, (v - to) * Math.signum(step));
          if (Math.abs(v - to) <= band) {
            if (settledAt == null) settledAt = t;
          } else {
            settledAt = null; // left the band: not settled yet
          }
        }
        double overshootPct = worstOvershoot / Math.abs(step) * 100.0;
        overshoots.add(overshootPct);
        if (settledAt != null) settling.add(settledAt - t0);
        if (details.size() < 20) {
          var d = new JsonObject();
          d.addProperty("time", t0);
          d.addProperty("from", from);
          d.addProperty("to", to);
          d.addProperty("overshoot_percent", overshootPct);
          if (settledAt != null) d.addProperty("settling_time_sec", settledAt - t0);
          else d.addProperty("settled", false);
          details.add(d);
        }
      }
      o.addProperty("steps", stepTimes.size());
      o.addProperty("settled_steps", settling.size());
      if (!settling.isEmpty()) {
        var st = new JsonObject();
        st.addProperty("avg", settling.stream().mapToDouble(d -> d).average().orElse(0));
        st.addProperty("max", settling.stream().mapToDouble(d -> d).max().orElse(0));
        st.addProperty("min", settling.stream().mapToDouble(d -> d).min().orElse(0));
        o.add("settling_time_sec", st);
      }
      if (!overshoots.isEmpty()) {
        o.addProperty("overshoot_percent", overshoots.stream().mapToDouble(d -> d).average()
            .orElse(0));
        o.addProperty("max_overshoot_percent", overshoots.stream().mapToDouble(d -> d).max()
            .orElse(0));
        o.add("step_details", details);
      }
      return o;
    }

    static List<JsonObject> detectStalls(List<TimestampedValue> velocities,
        List<TimestampedValue> currents, double currentThreshold, double velocityThreshold) {
      var stallEvents = new ArrayList<JsonObject>();
      boolean inStall = false;
      double stallStart = 0;
      double stallMaxCurrent = 0;
      double lastTime = 0;
      for (var velTv : velocities) {
        double vel = ((Number) velTv.value()).doubleValue();
        var cur = getValueAtTimeLinear(currents, velTv.timestamp());
        if (cur == null) continue;
        lastTime = velTv.timestamp();
        boolean stalled = Math.abs(vel) < velocityThreshold && cur > currentThreshold;
        if (stalled && !inStall) {
          inStall = true;
          stallStart = velTv.timestamp();
          stallMaxCurrent = cur;
        } else if (stalled) {
          stallMaxCurrent = Math.max(stallMaxCurrent, cur);
        } else if (inStall) {
          stallEvents.add(stall(stallStart, velTv.timestamp(), stallMaxCurrent, false));
          inStall = false;
        }
      }
      if (inStall) stallEvents.add(stall(stallStart, lastTime, stallMaxCurrent, true));
      return stallEvents;
    }

    static JsonObject stall(double start, double end, double maxCurrent, boolean open) {
      var event = new JsonObject();
      event.addProperty("start_time", start);
      event.addProperty("end_time", end);
      event.addProperty("duration", end - start);
      event.addProperty("max_current", maxCurrent);
      if (open) event.addProperty("open_at_end", true);
      return event;
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

        // Handle incomplete idle period (bounded by the window, like incomplete cycles)
        if (idleStartTime != null) {
          double lastTimestamp = vals.get(vals.size() - 1).timestamp();
          double boundedEnd = endTime != null ? Math.min(lastTimestamp, endTime) : lastTimestamp;
          double incompleteDuration = boundedEnd - idleStartTime;

          var deadPeriod = new JsonObject();
          deadPeriod.addProperty("start_time", idleStartTime);
          deadPeriod.addProperty("end_time", boundedEnd);
          deadPeriod.addProperty("duration", incompleteDuration);
          deadPeriod.addProperty("incomplete", true);
          deadTimePeriods.add(deadPeriod);
        }
      }

      // Build result
      var result = new JsonObject();
      result.addProperty("success", true);
      // Samples inside the window (the whole entry when no window is given)
      result.addProperty("sample_count",
          vals.stream().filter(tv -> inTimeRange(tv.timestamp(), startTime, endTime)).count());
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
      return "Loop timing: how often robot code exceeded the loop period (threshold_ms, default "
          + "20 ms) and the distribution of loop times (mean, median, p90, p95, p99, max with its "
          + "time), over a scope (e.g. 'enabled') or window. The entry is found in this order: "
          + "the entry argument; AdvantageKit's LoggedRobot/FullCycleMS (whole cycle, including "
          + "logging), reported with LoggedRobot/UserCodeMS alongside; names containing looptime, "
          + "loop_time, or cycletime; else loop periods derived from consecutive AdvantageKit "
          + "/Timestamp values. The unit comes from the argument, the name (...MS, ...Ms, _ms), "
          + "or the median (basis reported). A first sample more than 10x the median (the slow "
          + "boot cycle) is excluded and reported. health_score (0-100) is 100 minus the percent "
          + "of loops over the threshold. Returns no_match when the log has no loop timing, "
          + "with the count of WPILib loop-overrun console messages if any."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("entry", "string", "Loop time entry (default: discovered, see description)", false)
          .addNumberProperty("threshold_ms", "Loop time threshold in milliseconds (default: 20)", false, 20.0)
          .addProperty("unit", "string", "Unit of the entry's values: 'ms', 's', 'us', or 'auto' "
              + "(default: from the name, else the median)", false)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .build();
    }

    static int rank(String name) {
      var leaf = name.substring(name.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
      var lower = name.toLowerCase(java.util.Locale.ROOT);
      if (leaf.equals("fullcyclems")) return 0;
      if (leaf.equals("usercodems")) return 1;
      if (lower.contains("looptime") || lower.contains("loop_time") || lower.contains("cycletime")
          || lower.contains("cycle_time")) return 2;
      return Integer.MAX_VALUE;
    }

    /** Unit scale to milliseconds, and the basis for it. */
    record Unit(double toMs, String name, String basis) {}

    static Unit unitFor(String entry, String unitArg, double[] rawSorted) {
      if (unitArg != null && !unitArg.equalsIgnoreCase("auto")) {
        return switch (unitArg.toLowerCase(java.util.Locale.ROOT)) {
          case "ms" -> new Unit(1.0, "ms", "argument");
          case "s" -> new Unit(1000.0, "s", "argument");
          case "us" -> new Unit(0.001, "us", "argument");
          default -> throw new IllegalArgumentException("unit must be 'ms', 's', 'us', or 'auto'");
        };
      }
      if (entry != null) {
        var leaf = entry.substring(entry.lastIndexOf('/') + 1);
        if (leaf.endsWith("MS") || leaf.endsWith("Ms") || leaf.toLowerCase().endsWith("_ms")
            || leaf.toLowerCase().endsWith("millis")) {
          return new Unit(1.0, "ms", "name (" + leaf + ")");
        }
        if (leaf.endsWith("US") || leaf.endsWith("Us") || leaf.toLowerCase().endsWith("_us")
            || leaf.toLowerCase().endsWith("micros")) {
          return new Unit(0.001, "us", "name (" + leaf + ")");
        }
        if (leaf.toLowerCase().endsWith("sec") || leaf.toLowerCase().endsWith("seconds")
            || leaf.toLowerCase().endsWith("_s")) {
          return new Unit(1000.0, "s", "name (" + leaf + ")");
        }
      }
      double median = percentile(rawSorted, 0.5);
      if (median >= 0.001 && median < 1.0) {
        return new Unit(1000.0, "s", String.format("median %.4g: looks like seconds", median));
      }
      if (median > 500) {
        return new Unit(0.001, "us", String.format("median %.4g: looks like microseconds", median));
      }
      return new Unit(1.0, "ms", String.format("median %.4g: looks like milliseconds", median));
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      double thresholdMs = getOptDouble(arguments, "threshold_ms", 20.0);
      var entryArg = getOptString(arguments, "entry", null);
      var unitArg = getOptString(arguments, "unit", null);
      var scope = TimeScope.resolve(log, null, getOptString(arguments, "scope", null),
          getOptDouble(arguments, "start_time"), getOptDouble(arguments, "end_time"));

      String entry = entryArg;
      String secondary = null;
      if (entry != null) {
        requireEntry(log, entry);
        if (!isNumericType(log.entries().get(entry).type())) {
          throw new IllegalArgumentException("Entry " + entry + " is "
              + log.entries().get(entry).type() + ", not a scalar number");
        }
      } else {
        var ranked = log.entries().values().stream()
            .filter(e -> isNumericType(e.type()) && log.sampleCount(e.name()) > 0)
            .filter(e -> rank(e.name()) < Integer.MAX_VALUE)
            .sorted(Comparator.comparingInt((org.triplehelix.wpilogmcp.log.EntryInfo e) ->
                    rank(e.name()))
                .thenComparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
            .map(org.triplehelix.wpilogmcp.log.EntryInfo::name)
            .toList();
        if (!ranked.isEmpty()) {
          entry = ranked.get(0);
          if (rank(entry) == 0) {
            secondary = ranked.stream().filter(n -> rank(n) == 1).findFirst().orElse(null);
          }
        }
      }

      // Samples in scope, as {time, raw value}
      var raw = new ArrayList<double[]>();
      String basisOverride = null;
      if (entry != null) {
        for (var tv : log.values().get(entry)) {
          var v = toDouble(tv.value());
          if (v != null && Double.isFinite(v) && scope.contains(tv.timestamp())) {
            raw.add(new double[] {tv.timestamp(), v});
          }
        }
      } else {
        var timestamp = log.entries().get("/Timestamp");
        if (timestamp != null && isNumericType(timestamp.type())) {
          entry = "/Timestamp";
          basisOverride = "derived: differences between consecutive /Timestamp values "
              + "(AdvantageKit's per-cycle FPGA time, microseconds)";
          TimestampedValue previous = null;
          for (var tv : log.values().get("/Timestamp")) {
            if (previous != null && scope.contains(tv.timestamp())
                && tv.value() instanceof Number n && previous.value() instanceof Number p) {
              raw.add(new double[] {tv.timestamp(), (n.doubleValue() - p.doubleValue()) / 1000.0});
            }
            previous = tv;
          }
        }
      }
      if (entry == null) {
        long overrunMessages = 0;
        for (var e : log.entries().values()) {
          if (!"string".equals(e.type())) continue;
          for (var tv : log.values().get(e.name())) {
            if (tv.value() instanceof String text && text.toLowerCase().contains("overrun")) {
              overrunMessages++;
            }
          }
        }
        var nm = ResponseBuilder.noMatch("No loop time entry found.")
            .lookedFor(List.of("LoggedRobot/FullCycleMS and LoggedRobot/UserCodeMS (AdvantageKit)",
                "numeric entries whose names contain looptime, loop_time, or cycletime",
                "/Timestamp (AdvantageKit per-cycle time, to derive loop periods)"))
            .hint("Pass entry to name the robot's loop time entry"
                + (overrunMessages > 0 ? "; search_strings with pattern 'overrun' lists the "
                    + overrunMessages + " loop overrun console messages in this log" : "") + ".");
        if (overrunMessages > 0) nm.addProperty("overrun_messages", overrunMessages);
        return nm.build();
      }
      if (raw.isEmpty()) {
        return ResponseBuilder.noMatch("No samples of " + entry + " in scope '" + scope.name()
                + "'.")
            .addData("scope", scope.toJson())
            .build();
      }

      var rawSorted = raw.stream().mapToDouble(r -> r[1]).sorted().toArray();
      var unit = basisOverride != null ? new Unit(1.0, "ms", basisOverride)
          : unitFor(entry, unitArg, rawSorted);
      var ms = new ArrayList<double[]>(raw.size());
      raw.forEach(r -> ms.add(new double[] {r[0], r[1] * unit.toMs()}));

      // The first cycle after boot can take seconds; it is not a loop overrun
      JsonObject excludedBoot = null;
      var allOfEntry = basisOverride == null ? log.values().get(entry) : null;
      double medianMs = percentile(ms.stream().mapToDouble(r -> r[1]).sorted().toArray(), 0.5);
      if (allOfEntry != null && !allOfEntry.isEmpty()
          && ms.get(0)[0] == allOfEntry.get(0).timestamp() && ms.size() > 1
          && ms.get(0)[1] > 10 * medianMs) {
        excludedBoot = new JsonObject();
        excludedBoot.addProperty("timestamp", ms.get(0)[0]);
        excludedBoot.addProperty("loop_time_ms", ms.get(0)[1]);
        ms.remove(0);
      }

      var loopMs = ms.stream().mapToDouble(r -> r[1]).toArray();
      var sorted = loopMs.clone();
      java.util.Arrays.sort(sorted);
      var violations = new ArrayList<JsonObject>();
      double[] max = ms.get(0);
      for (var r : ms) {
        if (r[1] > max[1]) max = r;
        if (r[1] > thresholdMs) {
          var violation = new JsonObject();
          violation.addProperty("timestamp", r[0]);
          violation.addProperty("loop_time_ms", r[1]);
          violation.addProperty("overage_ms", r[1] - thresholdMs);
          violations.add(violation);
        }
      }
      var statistics = new JsonObject();
      statistics.addProperty("avg_ms", java.util.Arrays.stream(loopMs).average().orElse(0));
      statistics.addProperty("median_ms", percentile(sorted, 0.5));
      statistics.addProperty("p90_ms", percentile(sorted, 0.90));
      statistics.addProperty("p95_ms", percentile(sorted, 0.95));
      statistics.addProperty("p99_ms", percentile(sorted, 0.99));
      statistics.addProperty("max_ms", max[1]);
      statistics.addProperty("max_time_sec", max[0]);
      statistics.addProperty("min_ms", sorted[0]);

      double violationRate = (double) violations.size() / loopMs.length;
      int healthScore = (int) Math.max(0, Math.min(100, 100 - (violationRate * 100)));
      var list = new JsonArray();
      violations.stream().limit(50).forEach(list::add);

      var unitJson = new JsonObject();
      unitJson.addProperty("value", unit.name());
      unitJson.addProperty("basis", unit.basis());
      var builder = success()
          .addProperty("loop_time_entry", entry)
          .addData("unit", unitJson)
          .addData("scope", scope.toJson())
          .addProperty("threshold_ms", thresholdMs)
          .addProperty("violation_count", violations.size())
          .addProperty("total_samples", loopMs.length)
          .addProperty("violation_rate", violationRate)
          .addProperty("percent_over_threshold", violationRate * 100)
          .addProperty("health_score", healthScore)
          .addData("statistics", statistics)
          .addLimitedList("violations", list, violations.size(), 50)
          .addInput("loop_time", entry);
      if ("us".equals(unit.name()) && !"argument".equals(unit.basis())) {
        builder.addProperty("units_note", "Raw values were detected as microseconds and "
            + "converted to milliseconds");
      }
      if (excludedBoot != null) builder.addData("excluded_boot_cycle", excludedBoot);
      if (secondary != null) {
        var sec = new ArrayList<Double>();
        for (var tv : log.values().get(secondary)) {
          var v = toDouble(tv.value());
          if (v != null && Double.isFinite(v) && scope.contains(tv.timestamp())) sec.add(v);
        }
        if (!sec.isEmpty()) {
          var s = sec.stream().mapToDouble(Double::doubleValue).sorted().toArray();
          var o = new JsonObject();
          o.addProperty("entry", secondary);
          o.addProperty("basis", "robot code only; the full cycle adds logging and other overhead");
          o.addProperty("median_ms", percentile(s, 0.5));
          o.addProperty("p95_ms", percentile(s, 0.95));
          o.addProperty("percent_over_threshold",
              100.0 * java.util.Arrays.stream(s).filter(v -> v > thresholdMs).count() / s.length);
          builder.addData("user_code", o);
        }
      }
      var quality = DataQuality.fromValues(basisOverride == null ? log.values().get(entry)
          : log.values().get("/Timestamp"));
      builder.addDataQuality(quality).addDirectives(AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat()
          .addGuidance("Health score is a heuristic based on violation rate — consider context of violations"));
      return builder.build();
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
