/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

/**
 * Which entry plays each role in a log — the robot's enabled state, battery voltage, loop time,
 * robot pose, module states, and so on — with the basis for the choice and the other candidates
 * (review section 5.3). Each role is resolved by the same code the tools that use it run, so the
 * mapping {@code resolve_signals} reports is the mapping the tools use; a tool's result records
 * the entries it used under {@code inputs.entries}. Choices are deterministic: candidates are
 * ranked, and ties go to the entry declared first, flagged {@code ambiguous}.
 *
 * @since 0.9.0
 */
final class SignalResolver {

  private SignalResolver() {}

  /** Roles tools consume. */
  enum Role {
    ROBOT_ENABLED("DriverStation enabled state", "get_match_phases, get_ds_timeline, every "
        + "scope"),
    AUTONOMOUS("DriverStation autonomous mode", "get_match_phases, analyze_auto"),
    BATTERY_VOLTAGE("Battery voltage", "power_analysis, get_ds_timeline, "
        + "predict_battery_health, generate_report"),
    BROWNOUT_FLAG("The roboRIO's brownout flag", "power_analysis, get_ds_timeline, "
        + "predict_battery_health, generate_report"),
    BROWNOUT_THRESHOLD("The roboRIO's brownout voltage setting", "power_analysis (brownout_"
        + "threshold), get_ds_timeline, predict_battery_health, generate_report"),
    LOOP_TIME_FULL("Robot loop time (whole cycle)", "analyze_loop_timing (entry)"),
    LOOP_TIME_USER("Robot loop time (user code)", "analyze_loop_timing"),
    ROBOT_POSE("Robot pose (odometry or estimator)", "analyze_vision (pose_entry)"),
    MODULE_STATES_MEASURED("Measured swerve module states", "analyze_swerve (measured_entry)"),
    MODULE_STATES_SETPOINT("Swerve module setpoints", "analyze_swerve (setpoint_entry)"),
    CHASSIS_SPEEDS_MEASURED("Measured chassis speeds", "find_condition, get_statistics (by "
        + "name)"),
    CHASSIS_SPEEDS_SETPOINT("Chassis speed setpoints", "compare_entries (by name)"),
    GYRO_YAW("Gyro yaw", "compare_entries, align_entries (by name)"),
    VISION_POSE_OBSERVATIONS("Vision pose observation streams", "analyze_vision"),
    VISION_TARGETS("Vision target streams and has-target entries", "analyze_vision"),
    CAN_BUS("CAN bus counters", "analyze_can_bus, can_health"),
    CONSOLE_TEXT("Console and message text (string entries)", "search_strings, "
        + "get_ds_timeline, can_health, generate_report"),
    ALERTS("Alerts (string[] entries)", "search_strings, get_ds_timeline");

    final String description;
    final String usedBy;

    Role(String description, String usedBy) {
      this.description = description;
      this.usedBy = usedBy;
    }

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    static Role fromWire(String wire) {
      for (var r : values()) {
        if (r.wire().equals(wire.toLowerCase(Locale.ROOT))) return r;
      }
      throw new IllegalArgumentException("Unknown role '" + wire + "'. Roles: "
          + String.join(", ", java.util.Arrays.stream(values()).map(Role::wire).toList()));
    }
  }

  /**
   * How a role was resolved.
   *
   * @param role The role
   * @param entries The entries chosen (usually one; several for per-camera or per-bus roles)
   * @param basis Why these entries
   * @param candidates The entries considered, best first
   * @param ambiguous Whether another candidate ranked as well as the choice
   * @param value A value rather than an entry (the brownout threshold), or null
   */
  record Resolution(Role role, List<String> entries, String basis, List<String> candidates,
      boolean ambiguous, Double value) {

    JsonObject toJson(int maxCandidates) {
      var o = new JsonObject();
      o.addProperty("description", role.description);
      if (entries.size() == 1) {
        o.addProperty("entry", entries.get(0));
      } else if (!entries.isEmpty()) {
        var array = new JsonArray();
        entries.forEach(array::add);
        o.add("entries", array);
      } else {
        o.add("entry", com.google.gson.JsonNull.INSTANCE);
      }
      if (value != null) o.addProperty("value", value);
      o.addProperty("basis", basis);
      if (ambiguous) o.addProperty("ambiguous", true);
      if (!candidates.isEmpty()) {
        var array = new JsonArray();
        candidates.stream().limit(maxCandidates).forEach(array::add);
        o.add("candidates", array);
        if (candidates.size() > maxCandidates) {
          o.addProperty("candidate_count", candidates.size());
        }
      }
      o.addProperty("used_by", role.usedBy);
      return o;
    }
  }

  /** Every role, in declaration order. */
  static Map<Role, Resolution> resolveAll(LogData log) {
    var out = new LinkedHashMap<Role, Resolution>();
    for (var role : Role.values()) out.put(role, resolve(log, role));
    return out;
  }

  static Resolution resolve(LogData log, Role role) {
    return switch (role) {
      case ROBOT_ENABLED, AUTONOMOUS -> driverStation(log, role);
      case BATTERY_VOLTAGE -> battery(log);
      case BROWNOUT_FLAG -> {
        var flag = PowerFacts.flagEntry(log);
        yield new Resolution(role, flag.map(List::of).orElse(List.of()), flag.isPresent()
            ? "a boolean brownout flag (e.g. AdvantageKit /SystemStats/BrownedOut)"
            : "no boolean brownout flag entry in this log", flag.map(List::of).orElse(List.of()),
            false, null);
      }
      case BROWNOUT_THRESHOLD -> {
        var threshold = PowerFacts.threshold(log, null);
        yield new Resolution(role, threshold.entry() != null ? List.of(threshold.entry())
            : List.of(), threshold.basis(), List.of(), false, threshold.volts());
      }
      case LOOP_TIME_FULL, LOOP_TIME_USER -> loopTime(log, role);
      case ROBOT_POSE -> robotPose(log);
      case MODULE_STATES_MEASURED, MODULE_STATES_SETPOINT -> moduleStates(log, role);
      case CHASSIS_SPEEDS_MEASURED, CHASSIS_SPEEDS_SETPOINT -> chassisSpeeds(log, role);
      case GYRO_YAW -> gyro(log);
      case VISION_POSE_OBSERVATIONS -> {
        var streams = byId(log).stream()
            .filter(e -> FrcDomainTools.AnalyzeVisionTool.isObservationStream(log, e))
            .map(EntryInfo::name).toList();
        yield new Resolution(role, streams, streams.isEmpty()
            ? "no struct array whose records hold a timestamp and a pose"
            : "struct arrays whose records hold a timestamp and a pose, one per camera",
            streams, false, null);
      }
      case VISION_TARGETS -> {
        var targets = byId(log).stream().filter(e -> {
          var lower = e.name().toLowerCase(Locale.ROOT);
          return lower.contains("hastarget") || lower.endsWith("/tv") || lower.endsWith(".tv")
              || lower.contains("targetvalid")
              || FrcDomainTools.AnalyzeVisionTool.isTargetStream(log, e);
        }).map(EntryInfo::name).toList();
        yield new Resolution(role, targets, targets.isEmpty()
            ? "no struct with yaw and pitch fields and no has-target entry"
            : "structs with yaw and pitch fields, and has-target entries (tv, hasTarget)",
            targets, false, null);
      }
      case CAN_BUS -> {
        var buses = CanBusAnalysis.discoverBuses(log);
        var names = buses.stream().map(b -> b.prefix()).toList();
        yield new Resolution(role, names, buses.isEmpty()
            ? "no CAN bus counters (CANStatus fields or CANivore status) in this log"
            : "CAN counter entries by field name, one bus per parent path", names, false, null);
      }
      case CONSOLE_TEXT, ALERTS -> {
        var type = role == Role.CONSOLE_TEXT ? "string" : "string[]";
        var names = TextEvents.textEntries(log).stream().filter(e -> e.type().equals(type))
            .map(EntryInfo::name).toList();
        yield new Resolution(role, names, names.isEmpty() ? "no " + type + " entries"
            : "every " + type + " entry", names, false, null);
      }
    };
  }

  private static List<EntryInfo> byId(LogData log) {
    return log.entries().values().stream().sorted(Comparator.comparingInt(EntryInfo::id))
        .toList();
  }

  private static Resolution driverStation(LogData log, Role role) {
    var sources = MatchTimeline.of(log).sources();
    var entry = role == Role.ROBOT_ENABLED ? sources.enabled() : sources.autonomous();
    var candidates = new ArrayList<String>();
    if (entry != null) candidates.add(entry);
    if (sources.controlWord() != null) candidates.add(sources.controlWord());
    candidates.addAll(sources.ignored());
    String basis;
    if (entry != null) {
      basis = "DriverStation " + (role == Role.ROBOT_ENABLED ? "enabled" : "autonomous")
          + " entry (AdvantageKit /DriverStation/..., or WPILib DS:...)";
    } else if (sources.controlWord() != null) {
      entry = sources.controlWord();
      basis = "NetworkTables FMSControlData control word (bit 0 enabled, bit 1 autonomous)";
    } else {
      basis = "no DriverStation state entries in this log";
    }
    return new Resolution(role, entry != null ? List.of(entry) : List.of(), basis,
        candidates.stream().distinct().toList(), !sources.ignored().isEmpty(), null);
  }

  private static Resolution battery(LogData log) {
    var chosen = ToolUtils.selectVoltageEntry(log, null);
    var candidates = byId(log).stream()
        .filter(e -> e.name().toLowerCase(Locale.ROOT).contains("voltage")
            && ToolUtils.isNumericType(e.type()))
        .sorted(Comparator.comparingInt((EntryInfo e) ->
            ToolUtils.voltageEntryRank(e.name().toLowerCase(Locale.ROOT)))
            .thenComparingInt(EntryInfo::id))
        .map(EntryInfo::name).toList();
    return ranked(Role.BATTERY_VOLTAGE, chosen.orElse(null), candidates,
        n -> ToolUtils.voltageEntryRank(n.toLowerCase(Locale.ROOT)),
        "numeric entries named voltage, ranked battery > input/bus > other > rails and motor "
            + "outputs, the first with a finite sample", "no numeric entry named voltage with "
            + "a finite sample");
  }

  private static Resolution loopTime(LogData log, Role role) {
    var ranked = byId(log).stream()
        .filter(e -> ToolUtils.isNumericType(e.type()) && log.sampleCount(e.name()) > 0)
        .filter(e -> FrcDomainTools.AnalyzeLoopTimingTool.rank(e.name()) < Integer.MAX_VALUE)
        .sorted(Comparator.comparingInt((EntryInfo e) ->
            FrcDomainTools.AnalyzeLoopTimingTool.rank(e.name())).thenComparingInt(EntryInfo::id))
        .map(EntryInfo::name).toList();
    int want = role == Role.LOOP_TIME_FULL ? 0 : 1;
    String chosen = ranked.stream()
        .filter(n -> FrcDomainTools.AnalyzeLoopTimingTool.rank(n) == want).findFirst()
        .orElse(null);
    if (chosen == null && role == Role.LOOP_TIME_FULL && !ranked.isEmpty()) chosen = ranked.get(0);
    String basis;
    if (chosen != null) {
      basis = role == Role.LOOP_TIME_FULL
          ? "LoggedRobot/FullCycleMS, else UserCodeMS, else names with looptime or cycletime"
          : "LoggedRobot/UserCodeMS";
    } else if (role == Role.LOOP_TIME_FULL && log.entries().containsKey("/Timestamp")) {
      chosen = "/Timestamp";
      basis = "no loop time entry; periods derived from /Timestamp";
    } else {
      basis = "no loop time entry";
    }
    return new Resolution(role, chosen != null ? List.of(chosen) : List.of(), basis, ranked,
        false, null);
  }

  /**
   * The robot pose: the scalar Pose2d with the most samples that is not a vision entry (so a
   * one-sample trajectory or a vision estimate is not chosen); ties to the entry declared first.
   * The same choice analyze_vision makes.
   */
  static Resolution robotPose(LogData log) {
    var candidates = byId(log).stream()
        .filter(e -> e.type().equals("struct:Pose2d"))
        .filter(e -> !e.name().toLowerCase(Locale.ROOT).contains("vision"))
        .filter(e -> log.sampleCount(e.name()) >= 2)
        .sorted(Comparator.comparingInt((EntryInfo e) -> -log.sampleCount(e.name()))
            .thenComparingInt(EntryInfo::id))
        .map(EntryInfo::name).toList();
    boolean ambiguous = candidates.size() > 1
        && log.sampleCount(candidates.get(0)) == log.sampleCount(candidates.get(1));
    return new Resolution(Role.ROBOT_POSE, candidates.isEmpty() ? List.of()
        : List.of(candidates.get(0)), candidates.isEmpty()
        ? "no struct:Pose2d entry with at least two samples outside vision entries"
        : "the struct:Pose2d with the most samples, not under a vision path", candidates,
        ambiguous, null);
  }

  private static Resolution moduleStates(LogData log, Role role) {
    var stateEntries = byId(log).stream()
        .filter(e -> e.type().equals("struct:SwerveModuleState")
            || e.type().equals("struct:SwerveModuleState[]"))
        .filter(e -> log.sampleCount(e.name()) > 0).toList();
    var modules = RobotAnalysisTools.AnalyzeSwerveTool.discoverModules(log, stateEntries, null,
        null);
    var chosen = modules.stream()
        .map(m -> role == Role.MODULE_STATES_MEASURED ? m.measuredEntry() : m.setpointEntry())
        .filter(java.util.Objects::nonNull).distinct().toList();
    return new Resolution(role, chosen, chosen.isEmpty()
        ? (stateEntries.isEmpty() ? "no SwerveModuleState entries"
            : "no " + (role == Role.MODULE_STATES_MEASURED ? "measured" : "setpoint")
                + " module states among the SwerveModuleState entries")
        : "SwerveModuleState[] (one module per index) or per-module entries; setpoints by "
            + "leaf name (setpoint, desired, target, commanded, goal), optimized setpoints "
            + "preferred", stateEntries.stream().map(EntryInfo::name).toList(), false, null);
  }

  private static final java.util.regex.Pattern SETPOINT = java.util.regex.Pattern.compile(
      "(?i)(setpoint|desired|target|commanded|goal)");

  private static Resolution chassisSpeeds(LogData log, Role role) {
    boolean wantSetpoint = role == Role.CHASSIS_SPEEDS_SETPOINT;
    var candidates = byId(log).stream()
        .filter(e -> e.type().equals("struct:ChassisSpeeds"))
        .filter(e -> log.sampleCount(e.name()) > 0)
        .filter(e -> SETPOINT.matcher(e.name().substring(e.name().lastIndexOf('/') + 1)).find()
            == wantSetpoint)
        .sorted(Comparator.comparingInt((EntryInfo e) ->
            e.name().toLowerCase(Locale.ROOT).contains(wantSetpoint ? "setpoint" : "measured")
                ? 0 : 1).thenComparingInt(EntryInfo::id))
        .map(EntryInfo::name).toList();
    return ranked(role, candidates.isEmpty() ? null : candidates.get(0), candidates,
        n -> n.toLowerCase(Locale.ROOT).contains(wantSetpoint ? "setpoint" : "measured") ? 0 : 1,
        "struct:ChassisSpeeds entries, " + (wantSetpoint ? "named like a setpoint"
            : "not named like a setpoint") + "; 'Measured'/'Setpoints' in the name first",
        "no struct:ChassisSpeeds entry " + (wantSetpoint ? "named like a setpoint"
            : "not named like a setpoint"));
  }

  private static final java.util.regex.Pattern GYRO_PATH = java.util.regex.Pattern.compile(
      "(?i)(gyro|pigeon|navx|canandgyro|imu|ahrs)");

  private static Resolution gyro(LogData log) {
    ToIntFunction<EntryInfo> rank = e -> {
      var leaf = e.name().substring(e.name().lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
      boolean gyroPath = GYRO_PATH.matcher(e.name()).find();
      boolean rotation = e.type().equals("struct:Rotation2d");
      if (gyroPath && rotation && leaf.contains("yaw")) return 0;
      if (gyroPath && leaf.contains("yaw")) return 1;
      if (gyroPath && rotation) return 2;
      return 3;
    };
    var candidates = byId(log).stream()
        .filter(e -> e.type().equals("struct:Rotation2d") || ToolUtils.isNumericType(e.type()))
        .filter(e -> log.sampleCount(e.name()) > 0)
        .filter(e -> rank.applyAsInt(e) < 3)
        .sorted(Comparator.comparingInt(rank).thenComparingInt(EntryInfo::id))
        .toList();
    var names = candidates.stream().map(EntryInfo::name).toList();
    boolean ambiguous = candidates.size() > 1
        && rank.applyAsInt(candidates.get(0)) == rank.applyAsInt(candidates.get(1));
    var chosen = names.isEmpty() ? List.<String>of() : List.of(names.get(0));
    var basis = names.isEmpty() ? "no Rotation2d or numeric yaw entry under a gyro-like path "
        + "(gyro, pigeon, navx, canandgyro, imu)"
        : "a Rotation2d named like yaw under a gyro-like path first, then a numeric yaw"
            + (StructSchemas.structName(log.entries().get(names.get(0)).type()) != null
                ? " (address the angle as " + names.get(0) + ".value)" : "");
    return new Resolution(Role.GYRO_YAW, chosen, basis, names, ambiguous, null);
  }

  /** A ranked choice: ambiguous when the runner-up ranks the same as the choice. */
  private static Resolution ranked(Role role, String chosen, List<String> candidates,
      ToIntFunction<String> rank, String basis, String noneBasis) {
    if (chosen == null) {
      return new Resolution(role, List.of(), noneBasis, candidates, false, null);
    }
    boolean ambiguous = candidates.stream().filter(c -> !c.equals(chosen))
        .anyMatch(c -> rank.applyAsInt(c) == rank.applyAsInt(chosen));
    return new Resolution(role, List.of(chosen), basis, candidates, ambiguous, null);
  }
}
