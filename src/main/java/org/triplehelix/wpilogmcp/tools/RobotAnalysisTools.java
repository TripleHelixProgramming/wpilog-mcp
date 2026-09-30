/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * Robot-specific analysis tools for WPILOG data.
 */
public final class RobotAnalysisTools {

  private RobotAnalysisTools() {}

  public static void registerAll(ToolRegistry registry) {
    registry.registerTool(new GetMatchPhasesTool());
    registry.registerTool(new AnalyzeSwerveTool());
    registry.registerTool(new PowerAnalysisTool());
    registry.registerTool(new CanHealthTool());
    registry.registerTool(new CompareMatchesTool());
    registry.registerTool(new GetCodeMetadataTool());
    registry.registerTool(new MoiRegressionTool());
  }

  static class GetMatchPhasesTool extends LogRequiringTool {
    @Override
    public String name() { return "get_match_phases"; }

    @Override
    public String description() {
      return "ALWAYS use this tool to find when the robot was enabled and in which mode—NEVER "
          + "manually parse timestamps! Returns segments: every interval of constant robot state "
          + "(enabled/disabled/unknown) with its mode (auto/teleop/test) while enabled and why it "
          + "ended (disabled, mode_change, log_end, ...). A log can hold any number of enabled "
          + "segments (practice sessions); DriverStation values logged only on change hold until "
          + "the next sample. When a segment pattern is an FMS match (FMS attached, or an "
          + "autonomous segment followed within seconds by teleop), matches lists its "
          + "autonomous/teleop/endgame phases and phases repeats the first one; endgame comes "
          + "from the season's timing (basis game_timing). Use segment or phase bounds as "
          + "start_time/end_time for other tools."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() { return new SchemaBuilder().build(); }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var timeline = MatchTimeline.of(log);
      var sources = timeline.sources();

      if (!timeline.hasEnabledData() && sources.autonomous() == null) {
        return ResponseBuilder.noMatch("No DriverStation state entries found in this log, so "
                + "enabled periods and match phases cannot be determined.")
            .lookedFor(List.of(
                "boolean entries named Enabled / Autonomous / Test / FMSAttached under "
                    + "/DriverStation/ (AdvantageKit) or DS: (WPILib DataLogManager)",
                "int64 entry FMSInfo/FMSControlData (NetworkTables control word)"))
            .hint("Search for the team's own enable flag with search_entries (pattern "
                + "'enabled') and pass its segments as start_time/end_time to other tools.")
            .addProperty("source", "none")
            .addProperty("log_duration", log.duration())
            .build();
      }

      var builder = success()
          .addProperty("source", "DriverStation")
          .addProperty("log_start", log.minTimestamp())
          .addProperty("log_end", log.maxTimestamp())
          .addProperty("log_duration", log.duration())
          .addInput("enabled", sources.enabled())
          .addInput("autonomous", sources.autonomous())
          .addInput("test", sources.test())
          .addInput("fms_attached", sources.fmsAttached())
          .addInput("control_word", sources.controlWord());
      var season = new JsonObject();
      season.addProperty("year", timeline.season().year());
      season.addProperty("basis", timeline.season().basis());
      builder.addData("season", season);
      builder.addData("segments", timeline.segmentsJson());

      var enabled = timeline.enabledSegments();
      double enabledTime = enabled.stream().mapToDouble(MatchTimeline.Segment::duration).sum();
      builder.addProperty("enabled_segment_count", enabled.size())
          .addProperty("enabled_time_sec", enabledTime);

      var notes = new ArrayList<String>();
      if (!sources.ignored().isEmpty()) {
        notes.add("Also found " + String.join(", ", sources.ignored())
            + "; used the AdvantageKit /DriverStation/ entries (then lowest entry id).");
      }
      if (sources.autonomous() == null && sources.controlWord() == null) {
        notes.add("No Autonomous entry: the mode of enabled segments is unknown.");
      } else if (timeline.hasAutonomousData() && !timeline.autonomousEverTrue()) {
        notes.add("Autonomous was never true: " + sources.autonomous() + " has "
            + timeline.autonomousSampleCount() + " sample(s), all false. DriverStation values "
            + "are logged on change and hold until the next sample, so every enabled segment "
            + "is teleop.");
      }

      var matches = timeline.matches();
      var matchesJson = new JsonArray();
      matches.forEach(m -> matchesJson.add(timeline.matchJson(m)));
      builder.addData("matches", matchesJson);

      var phases = new JsonObject();
      if (!matches.isEmpty()) {
        var m = matches.get(0);
        var mj = timeline.matchJson(m);
        if (mj.has("autonomous")) {
          var auto = mj.getAsJsonObject("autonomous").deepCopy();
          auto.addProperty("description", "Autonomous");
          phases.add("autonomous", auto);
        }
        var teleop = mj.getAsJsonObject("teleop").deepCopy();
        teleop.addProperty("description", "Teleop");
        phases.add("teleop", teleop);
        if (mj.has("endgame")) {
          var endgame = mj.getAsJsonObject("endgame").deepCopy();
          endgame.addProperty("description", "Endgame");
          phases.add("endgame", endgame);
        }
        double matchStart = m.auto() != null ? m.auto().start() : m.teleop().start();
        builder.addProperty("match_duration", m.teleop().end() - matchStart);
        if (m.auto() != null) builder.addProperty("auto_duration", m.auto().duration());
        builder.addProperty("teleop_duration", m.teleop().duration());
        if (!m.complete()) {
          builder.addWarning("The match's teleop segment does not end in a disable at about "
              + "the season's teleop length (" + timeline.game().map(g -> g.teleopDurationSec()
                  + " s").orElse("unknown") + "); the log may end mid-match. Report 'log ends at "
              + String.format("%.1f", log.maxTimestamp()) + " s', not 'match ended'.");
        }
        if (matches.size() > 1) {
          notes.add(matches.size() + " matches found; phases describes the first.");
        }
      } else if (enabled.size() == 1) {
        var only = enabled.get(0);
        var phase = MatchTimeline.phase(only.start(), only.end());
        phase.addProperty("description", only.mode() == MatchTimeline.Mode.UNKNOWN
            ? "Enabled (mode unknown)" : "Enabled (" + only.mode().name().toLowerCase() + ")");
        phases.add("enabled", phase);
      }
      builder.addData("phases", phases);

      if (enabled.isEmpty()) {
        builder.addWarning("The robot was never enabled in this log (pit or bench session); "
            + "there are no enabled segments or match phases.");
      } else if (matches.isEmpty()) {
        if (sources.autonomous() == null && sources.controlWord() == null) {
          builder.addWarning("Robot enable/disable detected but autonomous/teleop mode "
              + "transitions not found. Cannot distinguish match phases; use segments.");
        }
        notes.add("No FMS match pattern: FMS was never attached at an enable, and no enabled "
            + "autonomous segment was followed within a few seconds by teleop. Use segments "
            + "for time windows" + (enabled.size() > 1 ? " (phases is empty because there are "
                + enabled.size() + " enabled segments)." : "."));
      }
      var last = timeline.segments().isEmpty() ? null
          : timeline.segments().get(timeline.segments().size() - 1);
      if (last != null && last.state() == MatchTimeline.State.ENABLED
          && last.endReason() == MatchTimeline.EndReason.LOG_END) {
        builder.addWarning("The log ends while the robot is enabled (last segment end_reason "
            + "log_end at " + String.format("%.2f", last.end()) + " s): the session continued "
            + "past the end of the log, or the log was truncated.");
      }
      var first = timeline.segments().isEmpty() ? null : timeline.segments().get(0);
      if (first != null && first.state() == MatchTimeline.State.UNKNOWN) {
        notes.add("Robot state is unknown from " + String.format("%.2f", first.start()) + " to "
            + String.format("%.2f", first.end()) + " s, before the first DriverStation sample.");
      }
      if (!notes.isEmpty()) builder.addData("notes", GSON.toJsonTree(notes));
      return builder.build();
    }
  }

  static class AnalyzeSwerveTool extends LogRequiringTool {
    /** A setpoint older than this at a measured sample is not compared (setpoints stopped). */
    static final double MAX_SETPOINT_AGE_SEC = 0.1;
    /** Steer error is only meaningful while the module is commanded to move. */
    static final double MIN_STEER_SPEED_MPS = 0.05;

    @Override
    public String name() { return "analyze_swerve"; }

    @Override
    public String description() {
      return "Analyze swerve modules from SwerveModuleState entries: per module, mean and maximum "
          + "|speed| (magnitude; measured speeds are signed and negative about half the time), "
          + "and, when a setpoint entry exists, speed tracking error (| |measured| - |setpoint| |, "
          + "m/s, events above slip_threshold) and steer error (angle difference modulo 180 deg, "
          + "while the setpoint speed is above 0.05 m/s, events above sync_threshold_rad). "
          + "AdvantageKit's struct:SwerveModuleState[] arrays count as one module per index "
          + "(module[0..N-1]; the FL, FR, BL, BR labels are the AdvantageKit template's order, an "
          + "assumption); one entry per module also works. Setpoints are paired by index, "
          + "preferring an optimized setpoint entry. Odometry drift compares the robot pose "
          + "(a conventional name, or the only Pose2d outside vision paths, as resolve_signals "
          + "reports it) with a vision pose (the only scalar pose under a vision, camera, "
          + "PhotonVision, or Limelight path); other candidates are listed in skipped to "
          + "confirm, never guessed. "
          + "Sections that cannot be produced are listed in skipped "
          + "with the reason; use measured_entry/setpoint_entry/odometry_entry/vision_entry to "
          + "point the tool at the right data, and scope (e.g. 'enabled') to exclude disabled time. "
          + "Returns no_match when the log has no SwerveModuleState entries."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MECHANISM;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("module_prefix", "string",
              "Only consider module state entries under this prefix (e.g. '/RealOutputs/SwerveStates')", false)
          .addProperty("measured_entry", "string",
              "Measured module states: a SwerveModuleState[] entry, or one module's SwerveModuleState entry", false)
          .addProperty("setpoint_entry", "string",
              "Setpoint module states, paired with measured_entry by index", false)
          .addNumberProperty("slip_threshold", "Speed tracking error, in m/s, counted as an event (default: 0.5)", false, 0.5)
          .addNumberProperty("sync_threshold_rad", "Steer error, in radians, counted as an event (default: 0.1)", false, 0.1)
          .addProperty("odometry_entry", "string", "Explicit odometry pose entry (struct:Pose2d or Pose3d)", false)
          .addProperty("vision_entry", "string", "Explicit vision pose entry (struct:Pose2d or Pose3d)", false)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .build();
    }

    /** One module: where its measured and setpoint states come from. */
    record Module(String label, String measuredEntry, int measuredIndex, String setpointEntry,
        int setpointIndex) {}

    static final java.util.regex.Pattern SETPOINT_WORDS =
        java.util.regex.Pattern.compile("(?i)setpoint|desired|target|commanded|goal|reference");
    static final java.util.regex.Pattern MEASURED_WORDS =
        java.util.regex.Pattern.compile("(?i)measured|actual|real|current|state");

    static String leaf(String name) {
      return name.substring(name.lastIndexOf('/') + 1);
    }

    static boolean isSetpointName(String name) {
      return SETPOINT_WORDS.matcher(leaf(name)).find();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var prefix = getOptString(arguments, "module_prefix", null);
      var measuredArg = getOptString(arguments, "measured_entry", null);
      var setpointArg = getOptString(arguments, "setpoint_entry", null);
      double slipThreshold = getOptDouble(arguments, "slip_threshold", 0.5);
      double syncThresholdRad = getOptDouble(arguments, "sync_threshold_rad", 0.1);
      var odomArg = getOptString(arguments, "odometry_entry", null);
      var visionArg = getOptString(arguments, "vision_entry", null);
      var scope = TimeScope.resolve(log, null, getOptString(arguments, "scope", null),
          getOptDouble(arguments, "start_time"), getOptDouble(arguments, "end_time"));

      for (var explicit : java.util.Arrays.asList(measuredArg, setpointArg)) {
        if (explicit == null) continue;
        requireEntry(log, explicit); // throws with suggestions when missing
        var info = log.entries().get(explicit);
        if (!info.type().startsWith("struct:SwerveModuleState")) {
          throw new IllegalArgumentException("Entry " + explicit + " is " + info.type()
              + ", not struct:SwerveModuleState or struct:SwerveModuleState[]");
        }
      }

      var stateEntries = log.entries().values().stream()
          .filter(e -> e.type().equals("struct:SwerveModuleState")
              || e.type().equals("struct:SwerveModuleState[]"))
          .filter(e -> prefix == null || e.name().startsWith(prefix))
          .filter(e -> log.sampleCount(e.name()) > 0)
          .sorted(Comparator.comparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
          .toList();
      var modules = discoverModules(log, stateEntries, measuredArg, setpointArg);
      if (modules.isEmpty()) {
        var setpointOnly = stateEntries.stream().map(org.triplehelix.wpilogmcp.log.EntryInfo::name)
            .toList();
        var nm = ResponseBuilder.noMatch(setpointOnly.isEmpty()
                ? "No SwerveModuleState entries" + (prefix != null ? " under " + prefix : "")
                    + " in this log."
                : "Only setpoint module states found (" + String.join(", ", setpointOnly)
                    + "); no measured module states to analyze.")
            .lookedFor(List.of("struct:SwerveModuleState[] entries (one module per index)",
                "struct:SwerveModuleState entries (one per module, grouped by parent path)",
                "measured vs setpoint by leaf name: setpoint/desired/target/commanded/goal are "
                    + "setpoints; anything else is measured"))
            .hint("Pass measured_entry (and setpoint_entry) to choose the entries, or use "
                + "search_entries with type 'SwerveModuleState'.");
        return nm.build();
      }

      var builder = success();
      builder.addData("scope", scope.toJson());
      var first = modules.get(0);
      builder.addInput("measured", first.measuredEntry());
      if (first.setpointEntry() != null) builder.addInput("setpoint", first.setpointEntry());
      boolean arrayLayout = first.measuredIndex() >= 0;
      builder.addProperty("layout", arrayLayout ? "array" : "per_module");
      builder.addProperty("module_count", modules.size());
      if (arrayLayout && modules.size() == 4) {
        builder.addProperty("module_order_note", "Indices are the order the robot code logs its "
            + "modules. The AdvantageKit template logs front-left, front-right, back-left, "
            + "back-right; that labeling is an assumption, not recorded in the log.");
      }

      var modulesJson = new JsonArray();
      double worstSteer = 0;
      String worstSteerModule = null;
      long steerEvents = 0;
      long steerSamples = 0;
      boolean anySetpoint = false;
      List<TimestampedValue> qualityValues = null;
      for (var module : modules) {
        var measured = log.values().get(module.measuredEntry());
        var setpoints = module.setpointEntry() != null
            ? log.values().get(module.setpointEntry()) : null;
        var inScope = new ArrayList<TimestampedValue>();
        var speeds = new ArrayList<Double>();
        var speedErrors = new ArrayList<Double>();
        var steerErrors = new ArrayList<Double>();
        long speedEvents = 0;
        long moduleSteerEvents = 0;
        for (var tv : measured) {
          if (!scope.contains(tv.timestamp())) continue;
          var state = element(tv.value(), module.measuredIndex());
          var speed = StructFields.moduleSpeed(state);
          if (speed == null) continue;
          inScope.add(tv);
          speeds.add(Math.abs(speed));
          if (setpoints == null) continue;
          var sp = setpointAt(setpoints, tv.timestamp());
          if (sp == null) continue;
          var spState = element(sp.value(), module.setpointIndex());
          var spSpeed = StructFields.moduleSpeed(spState);
          if (spSpeed == null) continue;
          double speedError = Math.abs(Math.abs(speed) - Math.abs(spSpeed));
          speedErrors.add(speedError);
          if (speedError > slipThreshold) speedEvents++;
          var angle = StructFields.moduleAngle(state);
          var spAngle = StructFields.moduleAngle(spState);
          if (angle != null && spAngle != null && Math.abs(spSpeed) > MIN_STEER_SPEED_MPS) {
            double diff = Math.abs(Math.IEEEremainder(angle - spAngle, 2 * Math.PI));
            double steerError = Math.min(diff, Math.PI - diff); // optimization flips by 180 deg
            steerErrors.add(steerError);
            if (steerError > syncThresholdRad) moduleSteerEvents++;
            if (steerError > worstSteer) {
              worstSteer = steerError;
              worstSteerModule = module.label();
            }
          }
        }
        if (qualityValues == null) qualityValues = inScope;
        var m = new JsonObject();
        m.addProperty("module", module.label());
        if (module.measuredIndex() >= 0) {
          m.addProperty("index", module.measuredIndex());
          if (modules.size() == 4) {
            m.addProperty("assumed_position",
                List.of("front_left", "front_right", "back_left", "back_right")
                    .get(module.measuredIndex()));
          }
        }
        m.addProperty("measured_entry", module.measuredEntry());
        m.addProperty("samples", speeds.size());
        if (!speeds.isEmpty()) {
          m.addProperty("mean_abs_speed_mps",
              speeds.stream().mapToDouble(Double::doubleValue).average().orElse(0));
          m.addProperty("max_abs_speed_mps",
              speeds.stream().mapToDouble(Double::doubleValue).max().orElse(0));
        }
        if (module.setpointEntry() != null) {
          m.addProperty("setpoint_entry", module.setpointEntry());
          if (!speedErrors.isEmpty()) {
            anySetpoint = true;
            var tracking = errorStats(speedErrors, "mps");
            tracking.addProperty("events_over_threshold", speedEvents);
            m.add("speed_tracking_error", tracking);
          }
          if (!steerErrors.isEmpty()) {
            var steer = errorStats(steerErrors, "rad");
            steer.addProperty("events_over_threshold", moduleSteerEvents);
            steer.addProperty("max_deg", Math.toDegrees(steer.get("max_rad").getAsDouble()));
            m.add("steer_error", steer);
            steerEvents += moduleSteerEvents;
            steerSamples += steerErrors.size();
          }
        }
        modulesJson.add(m);
      }
      builder.addData("modules", modulesJson);

      if (first.setpointEntry() == null) {
        builder.addSkipped("speed_tracking_error", "No setpoint module states found to pair "
            + "with " + first.measuredEntry() + " (pass setpoint_entry).");
        builder.addSkipped("steer_error", "No setpoint module states.");
      } else if (!anySetpoint) {
        builder.addSkipped("speed_tracking_error", "No setpoint sample within "
            + MAX_SETPOINT_AGE_SEC + " s of a measured sample in scope (setpoints are often "
            + "logged only while enabled).");
      }
      if (steerSamples > 0) {
        var sync = new JsonObject();
        sync.addProperty("basis", "steer angle vs setpoint, modulo 180 deg, while the setpoint "
            + "speed exceeds " + MIN_STEER_SPEED_MPS + " m/s");
        sync.addProperty("samples_analyzed", steerSamples);
        sync.addProperty("desync_events", steerEvents);
        sync.addProperty("max_deviation_rad", worstSteer);
        sync.addProperty("max_deviation_deg", Math.toDegrees(worstSteer));
        if (worstSteerModule != null) sync.addProperty("worst_module", worstSteerModule);
        builder.addData("module_sync", sync);
      }

      var drift = analyzeOdometryDrift(log, odomArg, visionArg, scope, builder);
      if (drift != null) builder.addData("odometry_drift", drift);

      if (qualityValues != null && !qualityValues.isEmpty()) {
        var quality = DataQuality.fromSegments(scope.split(log.values().get(first.measuredEntry())));
        builder.addDataQuality(quality).addDirectives(AnalysisDirectives.fromQuality(quality)
            .addSingleMatchCaveat()
            .addFollowup("Use power_analysis to check if module issues correlate with brownouts"));
      } else {
        builder.addWarning("No measured module samples fall inside the scope "
            + scope.name() + ".");
      }
      return builder.build();
    }

    static JsonObject errorStats(List<Double> errors, String unit) {
      var sorted = errors.stream().mapToDouble(Double::doubleValue).sorted().toArray();
      var o = new JsonObject();
      o.addProperty("samples", sorted.length);
      o.addProperty("mean_" + unit, java.util.Arrays.stream(sorted).average().orElse(0));
      o.addProperty("p95_" + unit, percentile(sorted, 0.95));
      o.addProperty("max_" + unit, sorted[sorted.length - 1]);
      return o;
    }

    /** The {@code index}-th record of an array value, or the value itself for a single struct. */
    static Object element(Object value, int index) {
      if (index < 0) return value;
      var elements = StructFields.elements(value);
      return index < elements.size() ? elements.get(index) : null;
    }

    /** The setpoint in force at {@code t}, if it was logged at most MAX_SETPOINT_AGE_SEC ago. */
    static TimestampedValue setpointAt(List<TimestampedValue> setpoints, double t) {
      int lo = 0;
      int hi = setpoints.size() - 1;
      if (hi < 0 || setpoints.get(0).timestamp() > t) return null;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (setpoints.get(mid).timestamp() <= t) lo = mid; else hi = mid - 1;
      }
      var sp = setpoints.get(lo);
      return t - sp.timestamp() <= MAX_SETPOINT_AGE_SEC ? sp : null;
    }

    /**
     * Modules from array entries (one module per index) when a measured array exists, else from
     * per-module entries grouped by parent path. Measured entries rank "measured" names first;
     * setpoints rank "optimized" names first; ties by entry id.
     */
    static List<Module> discoverModules(LogData log,
        List<org.triplehelix.wpilogmcp.log.EntryInfo> entries, String measuredArg,
        String setpointArg) {
      var arrays = entries.stream().filter(e -> e.type().endsWith("[]")).toList();
      var singles = entries.stream().filter(e -> !e.type().endsWith("[]")).toList();
      String measured = measuredArg;
      if (measured == null) {
        measured = arrays.stream().filter(e -> !isSetpointName(e.name()))
            .min(Comparator.comparingInt((org.triplehelix.wpilogmcp.log.EntryInfo e) ->
                leaf(e.name()).toLowerCase().contains("measured") ? 0 : 1)
                .thenComparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
            .map(org.triplehelix.wpilogmcp.log.EntryInfo::name).orElse(null);
      }
      var modules = new ArrayList<Module>();
      if (measured != null && log.entries().get(measured).type().endsWith("[]")) {
        String setpoint = setpointArg;
        if (setpoint == null) {
          setpoint = arrays.stream().filter(e -> isSetpointName(e.name()))
              .min(Comparator.comparingInt((org.triplehelix.wpilogmcp.log.EntryInfo e) ->
                  leaf(e.name()).toLowerCase().contains("optimized") ? 0 : 1)
                  .thenComparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
              .map(org.triplehelix.wpilogmcp.log.EntryInfo::name).orElse(null);
        }
        int count = 0;
        for (var tv : log.values().get(measured)) {
          count = Math.max(count, StructFields.elements(tv.value()).size());
        }
        for (int i = 0; i < count; i++) {
          modules.add(new Module("module[" + i + "]", measured, i, setpoint, i));
        }
        return modules;
      }
      if (measured != null) {
        // Explicit single-module entry
        modules.add(new Module(parentName(measured), measured, -1, setpointArg, -1));
        return modules;
      }
      // Per-module entries: group by parent path
      var groups = new LinkedHashMap<String, List<org.triplehelix.wpilogmcp.log.EntryInfo>>();
      for (var e : singles) {
        var parent = e.name().substring(0, Math.max(0, e.name().lastIndexOf('/')));
        groups.computeIfAbsent(parent, k -> new ArrayList<>()).add(e);
      }
      for (var group : groups.values()) {
        var m = group.stream().filter(e -> !isSetpointName(e.name()))
            .map(org.triplehelix.wpilogmcp.log.EntryInfo::name).findFirst().orElse(null);
        if (m == null) continue;
        var sp = group.stream().filter(e -> isSetpointName(e.name()))
            .map(org.triplehelix.wpilogmcp.log.EntryInfo::name).findFirst().orElse(null);
        modules.add(new Module(parentName(m), m, -1, sp, -1));
      }
      return modules;
    }

    static String parentName(String entry) {
      var parent = entry.substring(0, Math.max(0, entry.lastIndexOf('/')));
      return parent.isEmpty() ? entry : parent.substring(parent.lastIndexOf('/') + 1);
    }

    /**
     * Odometry drift: distance between the robot pose and a vision pose at the vision
     * timestamps. Both come from the signal resolver (the robot_pose and vision_pose roles): an
     * explicit entry, a conventional name, or the only candidate; entries that match by name
     * alone are listed in skipped to confirm, never used (the server does not guess).
     */
    private JsonObject analyzeOdometryDrift(LogData log, String odomArg, String visionArg,
        TimeScope scope, ResponseBuilder builder) {
      SignalResolver.Resolution odomRole;
      SignalResolver.Resolution visionRole;
      try {
        odomRole = SignalResolver.robotPose(log, odomArg);
        visionRole = SignalResolver.visionPose(log, visionArg);
      } catch (IllegalArgumentException e) {
        builder.addSkipped("odometry_drift", e.getMessage());
        return null;
      }
      if (odomRole.chosen().isEmpty() || visionRole.chosen().isEmpty()) {
        builder.addSkipped("odometry_drift", "Needs a scalar odometry pose and a scalar vision "
            + "pose (struct:Pose2d or struct:Pose3d, at least 2 samples). Odometry: "
            + odomRole.chosen().orElseGet(() ->
                SignalResolver.unresolvedReason(odomRole, "odometry_entry"))
            + " Vision: " + visionRole.chosen().orElseGet(() ->
                SignalResolver.unresolvedReason(visionRole, "vision_entry")));
        return null;
      }
      String odomName = odomRole.chosen().get();
      String visionName = visionRole.chosen().get();
      var odomVals = log.values().get(odomName);
      var visionVals = log.values().get(visionName);
      double total = 0;
      double max = 0;
      int comparisons = 0;
      int unreadable = 0;
      double firstT = Double.NaN;
      double lastT = Double.NaN;
      for (var vTv : visionVals) {
        if (!scope.contains(vTv.timestamp())) continue;
        var odom = ToolUtils.getValueAtTimeZoh(odomVals, vTv.timestamp());
        if (odom == null) continue;
        var dist = StructFields.planarDistance(odom, vTv.value());
        if (dist == null) {
          unreadable++;
          continue;
        }
        total += dist;
        max = Math.max(max, dist);
        comparisons++;
        if (Double.isNaN(firstT)) firstT = vTv.timestamp();
        lastT = vTv.timestamp();
      }
      if (comparisons < 2) {
        builder.addSkipped("odometry_drift", "Fewer than 2 comparable samples of " + visionName
            + " and " + odomName + " in scope" + (unreadable > 0 ? " (" + unreadable
                + " unreadable)" : "") + ".");
        return null;
      }
      var drift = new JsonObject();
      drift.addProperty("odometry_entry", odomName);
      drift.addProperty("odometry_basis", odomRole.basis());
      drift.addProperty("vision_entry", visionName);
      drift.addProperty("vision_basis", visionRole.basis());
      drift.addProperty("avg_error_m", total / comparisons);
      drift.addProperty("max_error_m", max);
      double span = lastT - firstT;
      drift.addProperty("max_error_per_total_time", span > 0 ? max / span : 0);
      drift.addProperty("comparisons", comparisons);
      if (unreadable > 0) drift.addProperty("unreadable_samples", unreadable);
      return drift;
    }
  }

  static class PowerAnalysisTool extends LogRequiringTool {
    /** Sub-path fragments after "Current/" that indicate a non-amperage quantity. */
    private static final List<String> NON_CURRENT_HINTS =
        List.of("angle", "position", "pose", "velocity", "speed", "setpoint", "target", "limit",
            "mode", "state", "command", "time", "height", "distance", "gear", "level");

    @Override
    public String name() { return "power_analysis"; }

    @Override
    public String description() {
      return "Analyze battery and current distribution data over a scope (default: enabled "
          + "time when the log records it, so idle and boot time do not dilute averages). "
          + "Reports battery voltage statistics (min with its time, max, avg, samples below the "
          + "brownout threshold, threshold crossings with 0.2 V hysteresis and the seconds spent "
          + "below; the threshold comes from the log's BrownoutVoltage entry when logged, else "
          + "6.8V for roboRIO 1, with the basis stated), brownout_risk with its basis (HIGH only "
          + "from the roboRIO's logged brownout flag, or from crossings when no flag is logged; "
          + "MODERATE within 1 V; LOW otherwise), the roboRIO's own brownouts in scope when its "
          + "flag is logged (rio_brownouts: start and duration of each), and, for every amperage "
          + "entry, the peak current by magnitude with its timestamp, signed min/max, average, "
          + "and sample count in scope, sorted by peak. Amperage entries are "
          + "named ...Current, ...CurrentAmps, ...Amps, ...Current/<sub>, or WPILib "
          + "PowerDistribution[<id>]/Chan<N>; names like CurrentAngle or CurrentLimit are excluded. "
          + "Per-channel arrays such as /PowerDistribution/ChannelCurrent are expanded per channel index. "
          + "Warns when no voltage or current entries are found. The battery voltage entry is "
          + "BatteryVoltage (e.g. /SystemStats/BatteryVoltage) or Voltage under "
          + "PowerDistribution, PDH, PDP, or Battery; the server does not guess among other "
          + "voltage entries: it lists them in the skipped reason, and voltage_entry names the "
          + "one to use."
          + GUIDANCE_UNIVERSAL + GUIDANCE_POWER;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("power_prefix", "string", "Entry path prefix (e.g., '/PDP')", false)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION + " Default: 'enabled' when the log records enabled state, else 'all'.", false)
          .addProperty("voltage_entry", "string", "Battery voltage entry to use (default: BatteryVoltage, or Voltage under PowerDistribution/PDH/PDP/Battery; other voltage entries are never guessed: when the log has only those, they are listed to confirm and pass here)", false)
          .addNumberProperty("brownout_threshold", "Voltage threshold (default: the log's BrownoutVoltage entry when logged, else 6.8V for roboRIO 1; roboRIO 2 is 6.3V)", false, null)
          .addIntegerProperty("channel_limit", "Maximum number of current entries/channels to return, sorted by peak current (default: 30, minimum: 1)", false, 30)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var prefix = getOptString(arguments, "power_prefix", null);
      var brownoutThreshold = PowerFacts.threshold(log, getOptDouble(arguments, "brownout_threshold"));
      double threshold = brownoutThreshold.volts();
      int channelLimit = Math.max(1, getOptInt(arguments, "channel_limit", 30));

      var result = new JsonObject();
      result.addProperty("success", true);
      var warnings = new ArrayList<String>();

      // Voltage: the battery_voltage role, shared with get_ds_timeline, predict_battery_health,
      // and generate_report; a conventional entry has at least one finite sample.
      var battery = SignalResolver.batteryVoltage(log, prefix,
          getOptString(arguments, "voltage_entry", null));
      var voltageEntry = battery.chosen();
      var timeline = MatchTimeline.of(log);
      var scopeArg = getOptString(arguments, "scope", null);
      if (scopeArg == null) scopeArg = timeline.hasEnabledData() ? "enabled" : "all";
      var scope = TimeScope.resolve(log, timeline, scopeArg, null, null);
      result.add("scope", scope.toJson());

      // The roboRIO's own brownouts in scope, when its flag is logged
      var flag = PowerFacts.flagEntry(log);
      var flagged = flag.map(f -> PowerFacts.brownouts(log, f, null, null).stream()
          .filter(b -> scope.contains(b.start())).toList()).orElse(List.of());
      flag.ifPresent(f -> result.add("rio_brownouts", PowerFacts.brownoutsJson(f, flagged)));

      var voltageFacts = voltageEntry.flatMap(name ->
          PowerFacts.voltage(log.values().get(name), scope, threshold));
      voltageFacts.ifPresent(v -> {
        var vObj = new JsonObject();
        vObj.addProperty("entry", voltageEntry.get());
        v.addTo(vObj);
        brownoutThreshold.addTo(vObj);
        PowerFacts.risk(v, brownoutThreshold, flag.orElse(null), flagged).addTo(vObj);
        result.add("voltage_analysis", vObj);
      });

      // Currents: every amperage entry, arrays expanded per channel index, sorted by peak
      var channelResult = channelAnalysis(log, prefix, scope);
      var channels = channelResult.channels();
      String firstScalarCurrentEntry = channelResult.firstScalarEntry();
      result.addProperty("current_entries_analyzed", channels.size());
      if (!channels.isEmpty()) {
        var shown = channels.size() > channelLimit ? channels.subList(0, channelLimit) : channels;
        ResultContract.addLimitedList(result, "channel_analysis",
            GSON.toJsonTree(shown).getAsJsonArray(), channels.size(), channelLimit);
        if (channels.size() > channelLimit) {
          warnings.add("Showing the top " + channelLimit + " of " + channels.size()
              + " current entries/channels by peak current; raise channel_limit to see more.");
        }
      }

      if (voltageEntry.isEmpty() && channels.isEmpty() && flag.isEmpty()) {
        return ResponseBuilder.noMatch("No battery voltage, current, or brownout flag entries "
                + "found" + (prefix != null ? " under " + prefix : "") + ".")
            .lookedFor(List.of("a battery voltage entry: BatteryVoltage, or Voltage under "
                    + "PowerDistribution, PDH, PDP, or Battery",
                "amperage entries named ...Current, ...CurrentAmps, ...Amps, ...Current/<sub>, or "
                    + "PowerDistribution[<id>]/Chan<N>, and per-channel current arrays",
                "a boolean brownout flag such as /SystemStats/BrownedOut"))
            .hint(battery.needsConfirmation()
                ? SignalResolver.unresolvedReason(battery, "voltage_entry")
                : "Use search_entries with pattern 'voltage' or 'current'.")
            .build();
      }
      if (voltageFacts.isEmpty()) {
        var reason = voltageEntry.isEmpty()
            ? SignalResolver.unresolvedReason(battery, "voltage_entry")
            : "No finite samples of " + voltageEntry.get() + " in scope '" + scope.name() + "'.";
        result.add("skipped", skippedEntry("voltage_analysis", reason));
        warnings.add(reason);
      }
      if (channels.isEmpty()) {
        var skipped = result.has("skipped") ? result.getAsJsonArray("skipped") : new JsonArray();
        skipped.addAll(skippedEntry("channel_analysis", "no amperage entries"));
        result.add("skipped", skipped);
        warnings.add("No current entries found. Amperage entries are named ...Current, ...Amps, "
            + "...Current/<sub>, or PowerDistribution[<id>]/Chan<N>; pass power_prefix to narrow, or "
            + "use read_entry on a specific entry.");
      }
      if (!warnings.isEmpty()) {
        result.add("warnings", GSON.toJsonTree(warnings));
      }

      if (result.has("skipped")) result.addProperty("status", "partial");

      // The entries used, by role (the same roles resolve_signals reports)
      var inputs = new JsonObject();
      var inputEntries = new JsonObject();
      voltageEntry.ifPresent(v -> inputEntries.addProperty("voltage", v));
      flag.ifPresent(f -> inputEntries.addProperty("brownout_flag", f));
      inputs.add("entries", inputEntries);
      result.add("inputs", inputs);

      // Data quality from the voltage entry, or the first scalar current entry when there is none.
      var qualitySource = voltageEntry.orElse(firstScalarCurrentEntry);
      if (qualitySource != null) {
        var vals = log.values().get(qualitySource);
        if (vals != null && !vals.isEmpty()) {
          var quality = DataQuality.fromSegments(scope.split(vals));
          var directives = AnalysisDirectives.fromQuality(quality)
              .addSingleMatchCaveat()
              .addFollowup("Use predict_battery_health for comprehensive battery assessment");
          appendQualityToResult(result, quality, directives);
        }
      }

      return result;
    }

    static JsonArray skippedEntry(String section, String reason) {
      var array = new JsonArray();
      var o = new JsonObject();
      o.addProperty("section", section);
      o.addProperty("reason", reason);
      array.add(o);
      return array;
    }

    /**
     * Decides whether an entry name denotes an electrical current (amps) rather than something
     * that merely contains the word "current" or ends in "amps" ("Current Angle Degrees",
     * "CurrentLimit", "OdometryTimestamps", "SlewRamps").
     *
     * <p>Accepted: a unit suffix at a token boundary ({@code CurrentAmps}, {@code StatorAmps},
     * {@code stator_amps}, {@code STATOR_AMPS}); names whose text after the last "current" is
     * empty or a unit/plural/draw suffix ({@code OutputCurrent}, {@code Current_A},
     * {@code CurrentDraw}, {@code Currents}, {@code Current(A)}); a sub-path after "Current/"
     * that is not a non-amperage quantity ({@code Current/Stator} yes, {@code Current/Setpoint}
     * no); and WPILib PowerDistribution sendable channels ({@code PowerDistribution[1]/Chan3}).
     * Anything containing "voltage" is rejected.
     */
    static boolean isCurrentEntryName(String entryName) {
      var original = entryName.replace(" ", "");
      var lower = original.toLowerCase();
      if (lower.contains("voltage")) return false;
      if (lower.contains("powerdistribution") && lower.matches(".*/chan\\d+$")) return true;
      if (original.matches(".*(?:^|[^A-Za-z]|[a-z])(?:Amps|Amperes)$")
          || original.matches(".*(?:^|[^A-Za-z])(?:amps|AMPS|amperes|AMPERES)$")) {
        return true;
      }
      int idx = lower.lastIndexOf("current");
      if (idx < 0) return false;
      var rest = lower.substring(idx + "current".length());
      if (rest.matches("[_\\-]?(?:a|amps?|amperage|draw|s|\\(a\\))?")) return true;
      if (rest.startsWith("/")) {
        return NON_CURRENT_HINTS.stream().noneMatch(rest::contains);
      }
      return false;
    }

    /** Widens any numeric array sample to double[]; returns null for non-array values. */
    static double[] toDoubleArray(Object value) {
      if (value instanceof double[] d) return d;
      if (value instanceof float[] f) {
        var out = new double[f.length];
        for (int i = 0; i < f.length; i++) out[i] = f[i];
        return out;
      }
      if (value instanceof long[] l) {
        var out = new double[l.length];
        for (int i = 0; i < l.length; i++) out[i] = l[i];
        return out;
      }
      return null;
    }

    /** Per-channel current results sorted by peak magnitude, and the first scalar entry. */
    record Channels(List<JsonObject> channels, String firstScalarEntry) {}

    /**
     * Current in every amperage entry (declaration order; arrays expanded per channel index)
     * within the scope, sorted by peak magnitude: the channel_analysis of power_analysis, and the
     * peak_currents of generate_report.
     *
     * @param prefix Only entries under this prefix, or null
     */
    static Channels channelAnalysis(LogData log, String prefix, TimeScope scope) {
      var currentEntries = log.entries().entrySet().stream()
          .filter(e -> prefix == null || e.getKey().startsWith(prefix))
          .filter(e -> isCurrentEntryName(e.getKey()))
          .sorted(Comparator.comparingInt(e -> e.getValue().id()))
          .map(Map.Entry::getKey)
          .toList();
      var channels = new ArrayList<JsonObject>();
      String firstScalarCurrentEntry = null;
      for (var entryName : currentEntries) {
        var values = log.values().get(entryName);
        if (values == null || values.isEmpty()) continue;
        var sample = values.stream().map(TimestampedValue::value)
            .filter(v -> v instanceof Number || toDoubleArray(v) != null).findFirst().orElse(null);
        if (sample instanceof Number) {
          var acc = new CurrentAccumulator();
          for (var tv : values) {
            if (!scope.contains(tv.timestamp())) continue;
            if (tv.value() instanceof Number n) acc.add(n.doubleValue(), tv.timestamp());
          }
          if (acc.count > 0) {
            if (firstScalarCurrentEntry == null) firstScalarCurrentEntry = entryName;
            channels.add(acc.toJson(entryName, null, null));
          }
        } else if (sample != null) {
          var accumulators = new ArrayList<CurrentAccumulator>();
          for (var tv : values) {
            if (!scope.contains(tv.timestamp())) continue;
            var arr = toDoubleArray(tv.value());
            if (arr == null) continue;
            while (accumulators.size() < arr.length) accumulators.add(new CurrentAccumulator());
            for (int i = 0; i < arr.length; i++) accumulators.get(i).add(arr[i], tv.timestamp());
          }
          for (int i = 0; i < accumulators.size(); i++) {
            var acc = accumulators.get(i);
            if (acc.count > 0) channels.add(acc.toJson(entryName + "[" + i + "]", entryName, i));
          }
        }
      }
      channels.sort(Comparator.comparingDouble(
          (JsonObject c) -> Math.abs(c.get("peak_current_A").getAsDouble())).reversed());
      return new Channels(channels, firstScalarCurrentEntry);
    }

    /**
     * Running accumulator for one current signal; ignores non-finite samples. Tracks the signed
     * extremes and the peak by magnitude, because logged currents may be signed (direction or
     * regenerative braking) and a -150 A stall is still a 150 A event.
     */
    private static final class CurrentAccumulator {
      double max = Double.NEGATIVE_INFINITY;
      double min = Double.POSITIVE_INFINITY;
      double peak = 0;
      double peakTime = Double.NaN;
      double sum = 0;
      long count = 0;

      void add(double amps, double timestamp) {
        if (!Double.isFinite(amps)) return;
        if (amps > max) max = amps;
        if (amps < min) min = amps;
        if (count == 0 || Math.abs(amps) > Math.abs(peak)) {
          peak = amps;
          peakTime = timestamp;
        }
        sum += amps;
        count++;
      }

      JsonObject toJson(String entry, String sourceEntry, Integer channel) {
        var obj = new JsonObject();
        obj.addProperty("entry", entry);
        if (sourceEntry != null) obj.addProperty("source_entry", sourceEntry);
        if (channel != null) obj.addProperty("channel", channel);
        obj.addProperty("peak_current_A", peak);
        obj.addProperty("peak_current_time_sec", peakTime);
        obj.addProperty("max_current_A", max);
        obj.addProperty("min_current_A", min);
        obj.addProperty("avg_current_A", sum / count);
        obj.addProperty("sample_count", count);
        return obj;
      }
    }
  }

  static class CanHealthTool extends LogRequiringTool {
    @Override
    public String name() { return "can_health"; }

    @Override
    public String description() {
      return "CAN bus health overview from two sources: console and message text (string "
          + "entries) with CAN timeout/error/fault lines, each classified by the robot's enabled "
          + "state at that moment from the DriverStation timeline, and the structured bus "
          + "counters that analyze_can_bus reads (TEC/REC error counters, bus-off and TX-full "
          + "counts). health_assessment: POOR if a bus-off count rose while enabled or 50+ CAN "
          + "text errors occurred while enabled; CONCERNING if any CAN text error occurred while "
          + "enabled or TEC/REC reached 128 (error-passive) while enabled; otherwise GOOD. "
          + "assessment_basis says which fact decided it. Errors while disabled are normal (for "
          + "example devices booting) and do not count; errors before the first DriverStation "
          + "sample are reported separately. See analyze_can_bus for per-bus detail."
          + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() { return new SchemaBuilder().build(); }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var timeline = MatchTimeline.of(log);
      boolean hasEnabledData = timeline.hasEnabledData();

      long totalEnabled = 0;
      long totalDisabled = 0;
      long totalUnknown = 0;
      var errorCounts = new JsonObject();
      var firstEnabled = new ArrayList<JsonObject>();

      // Text from string lines, alerts (once per appearance), and json strings
      for (var entry : TextEvents.textEntries(log)) {
        long enabled = 0;
        long disabled = 0;
        long unknown = 0;
        for (var event : TextEvents.of(log, entry)) {
          for (var line : event.text().split("\\R")) {
            if (!CanBusAnalysis.isCanErrorLine(line)) continue;
            switch (timeline.stateAt(event.timestamp())) {
              case ENABLED -> {
                enabled++;
                if (firstEnabled.size() < 5) {
                  var example = new JsonObject();
                  example.addProperty("timestamp_sec", event.timestamp());
                  example.addProperty("entry", entry.name());
                  example.addProperty("line", ToolUtils.truncate(line.strip(),
                      ToolUtils.MESSAGE_LINE_LIMIT));
                  firstEnabled.add(example);
                }
              }
              case DISABLED -> disabled++;
              case UNKNOWN -> unknown++;
            }
          }
        }
        long entryTotal = enabled + disabled + unknown;
        if (entryTotal > 0) {
          var entryObj = new JsonObject();
          entryObj.addProperty("total", entryTotal);
          entryObj.addProperty("while_enabled", enabled);
          if (hasEnabledData) entryObj.addProperty("while_disabled", disabled);
          if (unknown > 0) entryObj.addProperty("state_unknown", unknown);
          errorCounts.add(entry.name(), entryObj);
          totalEnabled += enabled;
          totalDisabled += disabled;
          totalUnknown += unknown;
        }
      }

      // Structured counters, shared with analyze_can_bus
      var buses = CanBusAnalysis.discoverBuses(log);
      var busSummary = new JsonArray();
      double busOffEnabled = 0;
      double maxEnabledErrorCounter = 0;
      String maxEnabledErrorWhere = null;
      for (var bus : buses) {
        var full = CanBusAnalysis.analyze(log, bus, timeline, null, null);
        var row = new JsonObject();
        row.addProperty("bus", bus.name());
        for (var key : List.of("tec", "rec")) {
          if (!full.has(key)) continue;
          var level = full.getAsJsonObject(key);
          if (!level.has("max")) continue;
          row.addProperty(key + "_max", level.get("max").getAsDouble());
          row.addProperty(key + "_max_time_sec", level.get("max_time_sec").getAsDouble());
          if (level.has("while_enabled")) {
            var e = level.getAsJsonObject("while_enabled");
            double max = e.get("max").getAsDouble();
            row.addProperty(key + "_max_while_enabled", max);
            if (max > maxEnabledErrorCounter) {
              maxEnabledErrorCounter = max;
              maxEnabledErrorWhere = bus.name() + " " + key.toUpperCase() + " reached "
                  + (long) max + " at " + String.format("%.2f", e.get("max_time_sec")
                      .getAsDouble()) + " s";
            }
          }
        }
        if (full.has("bus_off") && full.getAsJsonObject("bus_off").has("increase")) {
          var busOff = full.getAsJsonObject("bus_off");
          row.addProperty("bus_off_increase", busOff.get("increase").getAsDouble());
          double enabled = busOff.get("increase_while_enabled").getAsDouble();
          row.addProperty("bus_off_increase_while_enabled", enabled);
          busOffEnabled += enabled;
        }
        busSummary.add(row);
      }

      String health;
      String basis;
      if (busOffEnabled > 0) {
        health = "POOR";
        basis = "a bus-off count rose by " + (long) busOffEnabled + " while enabled";
      } else if (totalEnabled >= 50) {
        health = "POOR";
        basis = totalEnabled + " CAN error lines while enabled";
      } else if (totalEnabled > 0) {
        health = "CONCERNING";
        basis = totalEnabled + " CAN error line(s) while enabled";
      } else if (maxEnabledErrorCounter >= CanBusAnalysis.ERROR_PASSIVE) {
        health = "CONCERNING";
        basis = maxEnabledErrorWhere + " while enabled (error-passive at 128)";
      } else if (!hasEnabledData && totalUnknown > 0) {
        health = "UNKNOWN";
        basis = totalUnknown + " CAN error line(s), but the log has no DriverStation state to "
            + "tell whether the robot was enabled";
      } else {
        health = "GOOD";
        basis = "no CAN error lines or error-counter excursions while enabled"
            + (buses.isEmpty() ? " (no bus counters logged)" : "");
      }

      var builder = success()
          .addData("error_counts_by_entry", errorCounts)
          .addProperty("total_can_errors", totalEnabled + totalDisabled + totalUnknown)
          .addProperty("errors_while_enabled", totalEnabled)
          .addProperty("health_assessment", health)
          .addProperty("assessment_basis", basis)
          .addData("bus_counters", busSummary)
          .addInput("enabled", timeline.sources().enabled());
      if (hasEnabledData) builder.addProperty("errors_while_disabled", totalDisabled);
      if (totalUnknown > 0) builder.addProperty("errors_state_unknown", totalUnknown);
      if (!firstEnabled.isEmpty()) {
        builder.addData("first_errors_while_enabled", GSON.toJsonTree(firstEnabled));
      }
      if (!hasEnabledData) {
        builder.addWarning("No DriverStation enabled entry: CAN errors cannot be split by "
            + "robot state (reported as errors_state_unknown).");
      }
      if (totalDisabled > 0) {
        builder.addWarning("CAN errors while disabled (" + totalDisabled + ") are normal "
            + "and excluded from health assessment.");
      }
      return builder.build();
    }
  }

  // Extends ToolBase directly because this tool requires TWO log paths, not one
  static class CompareMatchesTool extends ToolBase {
    /** A maximum or minimum this early in a log is flagged as a likely boot transient. */
    static final double BOOT_SECONDS = 5.0;

    @Override
    public String name() { return "compare_matches"; }

    @Override
    public String description() {
      return "Compare one numeric signal across two logs: per log, count, min, max (with when), "
          + "mean, std_dev, median, p5, p25, p75, p95, and data_quality; and the differences "
          + "(second minus first) of mean, median, and p95. scope ('enabled', 'teleop', "
          + "'segment:<i>', ...) is resolved in each log's own timeline, so the same phase is "
          + "compared; start_time/end_time apply to each log's own clock. The name may carry a "
          + "field path (/RealOutputs/Drive/Pose.translation.x, ChannelCurrent[3]). A maximum or "
          + "minimum in the first 5 s of a log is flagged as a likely boot transient: compare "
          + "scope 'enabled' instead. Samples within a log are autocorrelated, so no "
          + "significance test is made; two logs are two samples."
          + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("path", "string", "Path to the first log file", true)
          .addProperty("compare_path", "string", "Path to the second log file", true)
          .addProperty("name", "string", "Entry name to compare, optionally with a field path",
              true)
          .addProperty("field", "string", NumericSignal.FIELD_PARAM, false)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION
              + " Resolved in each log's own timeline.", false)
          .addNumberProperty("start_time", "Start timestamp (s), on each log's clock", false, null)
          .addNumberProperty("end_time", "End timestamp (s), on each log's clock", false, null)
          .build();
    }

    @Override
    protected JsonElement executeInternal(JsonObject arguments) throws Exception {
      var path1 = getRequiredString(arguments, "path");
      var path2 = getRequiredString(arguments, "compare_path");
      var name = getRequiredString(arguments, "name");

      if (path1.equals(path2)) {
        throw new IllegalArgumentException("path and compare_path must be different log files");
      }

      var logs = new java.util.LinkedHashMap<String, LogData>();
      logs.put(path1, new AccessTrackingLogData(logManager.getOrLoad(path1)));
      logs.put(path2, new AccessTrackingLogData(logManager.getOrLoad(path2)));

      var comparisons = new JsonArray();
      var warnings = new ArrayList<String>();
      var found = new ArrayList<JsonObject>();
      DataQuality worst = null;
      for (var entry : logs.entrySet()) {
        var logPath = entry.getKey();
        var log = entry.getValue();
        var filename = Path.of(logPath).getFileName().toString();
        var stats = new JsonObject();
        stats.addProperty("log_path", logPath);
        stats.addProperty("log_filename", filename);
        NumericSignal signal;
        try {
          signal = StatisticsTools.signal(log, arguments, "name", "field", null);
        } catch (IllegalArgumentException e) {
          // present but not a number here (e.g. an array without an index), or not logged
          boolean exists = log.entries().containsKey(name)
              || NumericSignal.longestEntryPrefix(log, name) != null;
          stats.addProperty("entry_found", exists);
          stats.addProperty("reason", e.getMessage());
          warnings.add(filename + ": " + e.getMessage());
          comparisons.add(stats);
          continue;
        }
        stats.addProperty("entry_found", true);
        stats.addProperty("signal", signal.label());
        TimeScope scope;
        try {
          scope = TimeScope.fromArguments(log, null, arguments);
        } catch (IllegalArgumentException e) {
          // this log cannot be scoped (e.g. no DriverStation data): report it, compare the rest
          stats.addProperty("reason", e.getMessage());
          warnings.add(filename + ": " + e.getMessage());
          comparisons.add(stats);
          continue;
        }
        if (!scope.isAll()) stats.add("scope", scope.toJson());
        var windows = StatisticsTools.finiteWindows(signal, scope,
            signal.isAngle() && !signal.multiValued());
        var values = StatisticsTools.flatten(windows);
        stats.addProperty("sample_count", values.size());
        if (values.isEmpty()) {
          warnings.add(filename + ": no finite values of " + signal.label()
              + StatisticsTools.scopeText(scope) + ".");
          comparisons.add(stats);
          continue;
        }
        var data = values.stream().mapToDouble(tv -> ((Number) tv.value()).doubleValue())
            .toArray();
        var sorted = data.clone();
        java.util.Arrays.sort(sorted);
        int maxIndex = 0;
        int minIndex = 0;
        for (int i = 1; i < data.length; i++) {
          if (data[i] > data[maxIndex]) maxIndex = i;
          if (data[i] < data[minIndex]) minIndex = i;
        }
        double mean = java.util.Arrays.stream(data).average().orElse(0);
        double ss = java.util.Arrays.stream(data).map(v -> (v - mean) * (v - mean)).sum();
        var sObj = new JsonObject();
        sObj.addProperty("min", sorted[0]);
        sObj.addProperty("min_at_sec", values.get(minIndex).timestamp());
        sObj.addProperty("max", sorted[sorted.length - 1]);
        sObj.addProperty("max_at_sec", values.get(maxIndex).timestamp());
        sObj.addProperty("mean", mean);
        sObj.addProperty("std_dev", data.length > 1 ? Math.sqrt(ss / (data.length - 1)) : 0.0);
        sObj.addProperty("median", percentile(sorted, 0.5));
        sObj.addProperty("p5", percentile(sorted, 0.05));
        sObj.addProperty("p25", percentile(sorted, 0.25));
        sObj.addProperty("p75", percentile(sorted, 0.75));
        sObj.addProperty("p95", percentile(sorted, 0.95));
        if (signal.isAngle()) sObj.addProperty("angle_unit", signal.angle().wire());
        stats.add("statistics", sObj);
        for (var extreme : List.of("max", "min")) {
          double at = sObj.get(extreme + "_at_sec").getAsDouble();
          if (at - log.minTimestamp() < BOOT_SECONDS) {
            stats.addProperty(extreme + "_likely_boot_transient", true);
            warnings.add(filename + ": the " + extreme + " of " + signal.label() + " is at "
                + String.format("%.2f", at) + " s, within " + (int) BOOT_SECONDS + " s of the "
                + "start of the log (likely a boot transient); compare scope 'enabled' instead.");
          }
        }
        var quality = DataQuality.fromSegments(scope.split(signal.values()));
        stats.add("data_quality", quality.toJson());
        if (worst == null || quality.qualityScore() < worst.qualityScore()) worst = quality;
        found.add(sObj);
        comparisons.add(stats);
      }

      var result = new JsonObject();
      result.addProperty("success", !found.isEmpty());
      if (found.isEmpty()) {
        result.addProperty("status", "no_match");
        result.addProperty("reason", "Neither log has finite values of " + name + ".");
      } else if (found.size() < logs.size()) {
        result.addProperty("status", "partial");
        var skipped = new JsonArray();
        var item = new JsonObject();
        item.addProperty("section", "differences");
        item.addProperty("reason", "Only one log has values to compare.");
        skipped.add(item);
        result.add("skipped", skipped);
      }
      result.addProperty("entry", name);
      var inputs = new JsonObject();
      var inputLogs = new JsonArray();
      logs.keySet().forEach(inputLogs::add);
      inputs.add("logs", inputLogs);
      inputs.addProperty("entry", name);
      result.add("inputs", inputs);
      result.addProperty("logs_compared", logs.size());
      result.add("comparisons", comparisons);
      if (found.size() == 2) {
        var differences = new JsonObject();
        for (var key : List.of("mean", "median", "p95")) {
          differences.addProperty(key, found.get(1).get(key).getAsDouble()
              - found.get(0).get(key).getAsDouble());
        }
        differences.addProperty("note", "Second log minus first. Two logs are two samples: a "
            + "difference may reflect battery, field, opponents, or code changes "
            + "(get_code_metadata), not only the robot.");
        result.add("differences", differences);
      }
      if (!warnings.isEmpty()) {
        result.add("warnings", GSON.toJsonTree(warnings));
      }
      if (worst != null) {
        var directives = AnalysisDirectives.fromQuality(worst)
            .addGuidance("Cross-match comparisons require consistent logging configurations for "
                + "valid comparison");
        result.add("server_analysis_directives", directives.toJson());
      }
      for (var log : logs.values()) ((AccessTrackingLogData) log).annotate(result);
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
   * <p>The tool linearly interpolates current (and applied volts) to the velocity timestamps,
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
    protected JsonObject toolSchema() {
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
    protected JsonElement executeWithLog(LogData log, JsonObject args) throws Exception {
      // ── Required params ──────────────────────────────────────────────────────
      var velEntry  = getRequiredString(args, "velocity_entry");
      var currEntry = getRequiredString(args, "current_entry");
      var ktArg = getOptDouble(args, "kt");
      var gearArg = getOptDouble(args, "gear_ratio");
      if (ktArg == null || gearArg == null) {
        throw new IllegalArgumentException("Missing required parameter: "
            + (ktArg == null ? "kt (motor torque constant, Nm/A)" : "gear_ratio"));
      }
      double kt     = ktArg;
      double G      = gearArg;
      if (!(kt > 0) || !Double.isFinite(kt)) {
        throw new IllegalArgumentException("kt must be a positive number (Nm/A), got " + kt);
      }
      if (!(G > 0) || !Double.isFinite(G)) {
        throw new IllegalArgumentException("gear_ratio must be a positive number, got " + G);
      }

      // ── Optional params ──────────────────────────────────────────────────────
      int    motorCount  = getOptInt(args,    "motor_count",     1);
      validatePositive(motorCount, "motor_count");
      Double wheelRadius = getOptDouble(args, "wheel_radius");
      if (wheelRadius != null && !(wheelRadius > 0)) {
        throw new IllegalArgumentException("wheel_radius must be positive (meters), got "
            + wheelRadius);
      }
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
          .toList();

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

        double tau = voltsValues != null
            ? torqueScale * tauSign[i] * Math.abs(curr[i])
            : torqueScale * curr[i];
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
      if (Math.abs(det) < Math.max(1e-10 * detScale, 1e-15))
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
      int residualCount = 0;
      for (int i = 0; i < n; i++) {
        if (!Double.isFinite(alpha[i]) || !Double.isFinite(omegaS[i])
            || !Double.isFinite(curr[i]) || !Double.isFinite(tauSign[i]))
          continue;
        if (Math.abs(alpha[i]) < alphaThr) continue;
        if (voltsValues != null && Math.abs(tauSign[i]) < 0.5) continue;
        double y    = voltsValues != null
            ? torqueScale * tauSign[i] * Math.abs(curr[i])
            : torqueScale * curr[i];
        double yHat = J * alpha[i] + B * omegaS[i];
        ssY2  += y * y;
        double residual = y - yHat;
        ssRes += residual * residual;
        residualCount++;
      }
      double r2 = ssY2 > 1e-20 ? 1.0 - ssRes / ssY2 : Double.NaN;
      double rmse = residualCount > 0 ? Math.sqrt(ssRes / residualCount) : Double.NaN;

      // ── Build result ──────────────────────────────────────────────────────────
      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("J_kg_m2", J);
      result.addProperty("B_Nm_s_per_rad", B);
      // Undefined fit quality (all-zero torque) is null with a warning, never NaN
      if (Double.isFinite(r2)) result.addProperty("r_squared", r2);
      else result.add("r_squared", com.google.gson.JsonNull.INSTANCE);
      if (Double.isFinite(rmse)) result.addProperty("rmse_nm", rmse);
      else result.add("rmse_nm", com.google.gson.JsonNull.INSTANCE);
      result.addProperty("n_samples_used", nUsed);
      result.addProperty("n_samples_total", n);
      result.addProperty("filtered_by_alpha_threshold", filtByThr);
      if (filtBySign > 0) result.addProperty("filtered_by_zero_volts", filtBySign);

      var warnings = new JsonArray();
      if (J < 0)
        warnings.add("J is negative — physically invalid. If current is unsigned, add applied_volts_entry.");
      if (!Double.isFinite(r2))
        warnings.add("R² is undefined: the modeled torque is zero for every sample used "
            + "(check the current entry and kt).");
      if (!Double.isNaN(r2) && r2 < 0.2)
        warnings.add(String.format("R²=%.3f is low. Narrow the window to a clean acceleration transient, "
            + "raise alpha_threshold, or increase smooth_window.", r2));
      if (nUsed < 20)
        warnings.add("Only " + nUsed + " samples used. Consider widening the window or lowering alpha_threshold.");
      if (Math.abs(J) > 1000 || Math.abs(B) > 100)
        warnings.add("Extreme values detected — results may be unreliable due to near-singular data");
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
    /** Metadata keys by leaf name, as AdvantageKit's BuildConstants records them. */
    static final List<String> KEYS =
        List.of("GitSHA", "GitBranch", "GitDirty", "GitDate", "BuildDate", "ProjectName", "Version");

    @Override
    public String name() { return "get_code_metadata"; }

    @Override
    public String description() {
      return "Extract code metadata (Git SHA, branch, dirty flag, Git date, build date, project "
          + "name, version) from string entries with those leaf names, e.g. AdvantageKit's "
          + "/RealMetadata/GitSHA ('Version' only under a path containing 'metadata'). sources "
          + "names the entry each value came from (lowest entry id when several exist; a warning "
          + "says when they disagree). Returns no_match when the log has no metadata entries.";
    }

    @Override
    protected JsonObject toolSchema() { return new SchemaBuilder().build(); }

    static String key(String entryName) {
      var leaf = entryName.substring(entryName.lastIndexOf('/') + 1);
      for (var key : KEYS) {
        if (!leaf.equalsIgnoreCase(key)) continue;
        if (key.equals("Version") && !entryName.toLowerCase().contains("metadata")) return null;
        return key;
      }
      return null;
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var metadata = new JsonObject();
      var sources = new JsonObject();
      var warnings = new ArrayList<String>();
      var entries = log.entries().values().stream()
          .filter(e -> "string".equals(e.type()) && key(e.name()) != null)
          .sorted(Comparator.comparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
          .toList();
      for (var e : entries) {
        var key = key(e.name());
        var vals = log.values().get(e.name());
        var value = vals != null && !vals.isEmpty() ? String.valueOf(vals.get(0).value()) : "unknown";
        if (!metadata.has(key)) {
          metadata.addProperty(key, value);
          sources.addProperty(key, e.name());
        } else if (!metadata.get(key).getAsString().equals(value)) {
          warnings.add(key + " differs: " + sources.get(key).getAsString() + " = "
              + metadata.get(key).getAsString() + ", " + e.name() + " = " + value);
        }
      }
      if (metadata.size() == 0) {
        return ResponseBuilder.noMatch("No code metadata entries found.")
            .lookedFor(List.of("string entries with leaf names " + String.join(", ", KEYS)
                + " (e.g. /RealMetadata/GitSHA)"))
            .hint("Teams that do not log build metadata can add AdvantageKit's "
                + "Logger.recordMetadata calls from the generated BuildConstants.")
            .build();
      }
      var builder = success().addData("metadata", metadata).addData("sources", sources);
      warnings.forEach(builder::addWarning);
      return builder.build();
    }
  }
}
