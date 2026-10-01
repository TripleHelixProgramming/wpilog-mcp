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
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

/**
 * Which entry plays each role in a log — the robot's enabled state, battery voltage, loop time,
 * robot pose, module states, and so on — with the basis for the choice and the other candidates
 * (review section 5.3). Tools resolve their inputs here, so the mapping {@code resolve_signals}
 * reports is the mapping the tools use; a tool's result records the entries it used under
 * {@code inputs.entries}.
 *
 * <p>The server does not guess what an entry means (doc/IDEAS.md section 6.8). An entry is chosen
 * only when it is passed explicitly, follows a well-known logging convention (AdvantageKit,
 * WPILib, CTRE, PathPlanner names; see each role's rules below), or is the only entry of the
 * role's type. Entries that match a role by name alone are {@link Tier#HEURISTIC}: they are
 * listed as candidates for the caller to confirm and pass explicitly, and never used silently.
 * Choices are deterministic: ties go to the entry declared first and are flagged
 * {@code ambiguous}.
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
    TEST_MODE("DriverStation test mode", "get_match_phases (scope 'test')"),
    FMS_ATTACHED("Whether the FMS was attached", "get_match_phases (match detection)"),
    BATTERY_VOLTAGE("Battery voltage", "power_analysis, get_ds_timeline, "
        + "predict_battery_health, generate_report (voltage_entry)"),
    TOTAL_CURRENT("Total robot current", "predict_battery_health (total_current_entry)"),
    BROWNOUT_FLAG("The roboRIO's brownout flag", "power_analysis, get_ds_timeline, "
        + "predict_battery_health, generate_report"),
    BROWNOUT_THRESHOLD("The roboRIO's brownout voltage setting", "power_analysis (brownout_"
        + "threshold), get_ds_timeline, predict_battery_health, generate_report"),
    LOOP_TIME_FULL("Robot loop time (whole cycle)", "analyze_loop_timing (entry)"),
    LOOP_TIME_USER("Robot loop time (user code)", "analyze_loop_timing"),
    ROBOT_POSE("Robot pose (odometry or estimator)", "analyze_vision (pose_entry), "
        + "compare_poses (pose_entry), pose_corrections (pose_entry), analyze_swerve "
        + "(odometry_entry)"),
    VISION_POSE("Vision pose estimate (a scalar pose under a vision path)",
        "analyze_swerve (vision_entry)"),
    AUTO_CHOOSER("The selected autonomous routine", "analyze_auto (chooser_entry)"),
    PATH_SETPOINT("Path-following setpoint pose", "analyze_auto (path_setpoint_entry)"),
    PATH_ACTUAL("Path-following actual pose", "analyze_auto (path_actual_entry)"),
    MODULE_STATES_MEASURED("Measured swerve module states", "analyze_swerve (measured_entry)"),
    MODULE_STATES_SETPOINT("Swerve module setpoints", "analyze_swerve (setpoint_entry)"),
    CHASSIS_SPEEDS_MEASURED("Measured chassis speeds", "pose_corrections "
        + "(chassis_speeds_entry); find_condition, get_statistics (by name)"),
    CHASSIS_SPEEDS_SETPOINT("Chassis speed setpoints", "compare_entries (by name)"),
    GYRO_YAW("Gyro yaw", "no tool resolves it; compare_entries and align_entries take it by "
        + "name"),
    VISION_POSE_OBSERVATIONS("Vision pose observation streams", "analyze_vision"),
    VISION_TARGETS("Vision target streams and has-target entries", "analyze_vision"),
    CAN_BUS("CAN bus counters", "analyze_can_bus, can_health"),
    CONSOLE_TEXT("Console and message text (string entries)", "search_strings, "
        + "get_ds_timeline, can_health, generate_report"),
    ALERTS("Alerts (string[] entries)", "search_strings, get_ds_timeline, can_health, "
        + "generate_report");

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

  /** How a role's entry was chosen, strongest first. */
  enum Tier {
    /** Passed by the caller. */
    EXPLICIT,
    /** A well-known logging convention (AdvantageKit, WPILib, a vendor library). */
    CONVENTION,
    /** Found by type or schema (the only entry of its kind, or a struct's fields). */
    TYPE,
    /** Matched by name alone: listed as candidates for the caller to confirm, never chosen. */
    HEURISTIC,
    /** Nothing in the log fits the role. */
    NONE;

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * How a role was resolved.
   *
   * @param role The role
   * @param entries The entries chosen (usually one; several for per-camera or per-bus roles);
   *     empty for {@link Tier#HEURISTIC} and {@link Tier#NONE}
   * @param basis Why these entries (or why none)
   * @param candidates The entries considered, best first
   * @param ambiguous Whether another candidate ranked as well as the choice
   * @param value A value rather than an entry (the brownout threshold), or null
   * @param tier How the choice was made
   */
  record Resolution(Role role, List<String> entries, String basis, List<String> candidates,
      boolean ambiguous, Double value, Tier tier) {

    /** The chosen entry, if any. */
    java.util.Optional<String> chosen() {
      return entries.isEmpty() ? java.util.Optional.empty()
          : java.util.Optional.of(entries.get(0));
    }

    /** Only candidates that match by name: the caller must confirm one and pass it. */
    boolean needsConfirmation() {
      return tier == Tier.HEURISTIC;
    }

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
      o.addProperty("match", tier.wire());
      o.addProperty("basis", basis);
      if (ambiguous) o.addProperty("ambiguous", true);
      if (needsConfirmation()) o.addProperty("needs_confirmation", true);
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

  // ==================== all roles ====================

  /** Every role, in declaration order. */
  static Map<Role, Resolution> resolveAll(LogData log) {
    var out = new LinkedHashMap<Role, Resolution>();
    for (var role : Role.values()) out.put(role, resolve(log, role));
    return out;
  }

  static Resolution resolve(LogData log, Role role) {
    return switch (role) {
      case ROBOT_ENABLED, AUTONOMOUS, TEST_MODE, FMS_ATTACHED -> driverStation(log, role);
      case BATTERY_VOLTAGE -> batteryVoltage(log, null, null);
      case TOTAL_CURRENT -> totalCurrent(log, null);
      case BROWNOUT_FLAG -> {
        var flag = PowerFacts.flagEntry(log);
        yield new Resolution(role, flag.map(List::of).orElse(List.of()), flag.isPresent()
            ? "a boolean named BrownedOut or IsBrownedOut (AdvantageKit /SystemStats/BrownedOut)"
            : "no boolean BrownedOut entry in this log", flag.map(List::of).orElse(List.of()),
            false, null, flag.isPresent() ? Tier.CONVENTION : Tier.NONE);
      }
      case BROWNOUT_THRESHOLD -> {
        var threshold = PowerFacts.threshold(log, null);
        yield new Resolution(role, threshold.entry() != null ? List.of(threshold.entry())
            : List.of(), threshold.basis(), List.of(), false, threshold.volts(),
            threshold.entry() != null ? Tier.CONVENTION : Tier.NONE);
      }
      case LOOP_TIME_FULL, LOOP_TIME_USER -> loopTime(log, role, null);
      case ROBOT_POSE -> robotPose(log, null);
      case VISION_POSE -> visionPose(log, null);
      case AUTO_CHOOSER -> autoChooser(log, null);
      case PATH_SETPOINT, PATH_ACTUAL -> pathPose(log, role, null, null);
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
            streams, false, null, streams.isEmpty() ? Tier.NONE : Tier.TYPE);
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
            : "structs with yaw and pitch fields, and has-target entries (Limelight tv, "
                + "PhotonVision hasTarget)", targets, false, null,
            targets.isEmpty() ? Tier.NONE : Tier.CONVENTION);
      }
      case CAN_BUS -> {
        var buses = CanBusAnalysis.discoverBuses(log);
        var names = buses.stream().map(b -> b.prefix()).toList();
        yield new Resolution(role, names, buses.isEmpty()
            ? "no CAN bus counters (CANStatus fields or CANivore status) in this log"
            : "CAN counter entries by field name, one bus per parent path", names, false, null,
            buses.isEmpty() ? Tier.NONE : Tier.CONVENTION);
      }
      case CONSOLE_TEXT, ALERTS -> {
        var type = role == Role.CONSOLE_TEXT ? "string" : "string[]";
        var names = TextEvents.textEntries(log).stream().filter(e -> e.type().equals(type))
            .map(EntryInfo::name).toList();
        yield new Resolution(role, names, names.isEmpty() ? "no " + type + " entries"
            : "every " + type + " entry", names, false, null,
            names.isEmpty() ? Tier.NONE : Tier.TYPE);
      }
    };
  }

  // ==================== shared helpers ====================

  private static List<EntryInfo> byId(LogData log) {
    return log.entries().values().stream().sorted(Comparator.comparingInt(EntryInfo::id))
        .toList();
  }

  /** The last path segment, lower case, letters and digits only ("Battery Voltage" -> ...). */
  static String leaf(String name) {
    return normalize(name.substring(name.lastIndexOf('/') + 1));
  }

  /** The second-to-last path segment, normalized like {@link #leaf}, or "". */
  static String parent(String name) {
    int cut = name.lastIndexOf('/');
    if (cut <= 0) return "";
    var head = name.substring(0, cut);
    return normalize(head.substring(head.lastIndexOf('/') + 1));
  }

  private static String normalize(String segment) {
    return segment.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  /**
   * The explicit entry a caller passed, checked to exist and to be a type the role reads.
   *
   * @throws IllegalArgumentException if it does not exist or has the wrong type
   */
  static Resolution explicit(LogData log, Role role, String name, String param,
      Predicate<String> typeOk, String typeDescription) {
    var info = log.entries().get(name);
    if (info == null) {
      throw new IllegalArgumentException(param + " " + name + " is not in this log. "
          + "Use search_entries or resolve_signals to find the entry.");
    }
    if (!typeOk.test(info.type())) {
      throw new IllegalArgumentException(param + " " + name + " is " + info.type() + "; "
          + role.description.toLowerCase(Locale.ROOT) + " must be " + typeDescription + ".");
    }
    return new Resolution(role, List.of(name), "passed as " + param, List.of(name), false, null,
        Tier.EXPLICIT);
  }

  /** Candidates that match by name only: listed, never chosen. */
  static Resolution heuristic(Role role, List<String> candidates, String how) {
    return new Resolution(role, List.of(), "no entry follows a known convention for this role; "
        + how + " (by name only, not chosen)", candidates, false, null, Tier.HEURISTIC);
  }

  /**
   * Why a tool could not use a role, for a {@code skipped} section or a {@code no_match}
   * reason: the candidates to confirm, or what was searched, and the parameter to pass.
   */
  static String unresolvedReason(Resolution r, String param) {
    var what = r.role().description.toLowerCase(Locale.ROOT).replaceFirst("^the ", "");
    if (r.needsConfirmation()) {
      return "No entry follows a known convention for the " + what + ". Entries that match by "
          + "name only: " + String.join(", ", r.candidates().stream().limit(5).toList())
          + (r.candidates().size() > 5 ? ", ..." : "") + ". Confirm which one (if any) is the "
          + what + " (get_entry_info, read_entry, or ask the user) and pass it as " + param
          + "; the server does not guess.";
    }
    return "No " + what + " entry found (" + r.basis() + "); if the log has one under another "
        + "name, pass it as " + param + ".";
  }

  /** A ranked choice: ambiguous when another candidate ranks the same as the choice. */
  private static Resolution ranked(Role role, String chosen, List<String> candidates,
      ToIntFunction<String> rank, String basis, String noneBasis, Tier tier) {
    if (chosen == null) {
      return new Resolution(role, List.of(), noneBasis, candidates, false, null, Tier.NONE);
    }
    boolean ambiguous = candidates.stream().filter(c -> !c.equals(chosen))
        .anyMatch(c -> rank.applyAsInt(c) == rank.applyAsInt(chosen));
    return new Resolution(role, List.of(chosen), basis, candidates, ambiguous, null, tier);
  }

  private static boolean hasFiniteSample(LogData log, String name) {
    var values = log.values().get(name);
    return values != null && values.stream()
        .anyMatch(tv -> tv.value() instanceof Number n && Double.isFinite(n.doubleValue()));
  }

  // ==================== DriverStation ====================

  /** DriverStation roles: AdvantageKit /DriverStation/..., WPILib DS:..., or FMSControlData. */
  private static Resolution driverStation(LogData log, Role role) {
    var sources = MatchTimeline.of(log).sources();
    var entry = switch (role) {
      case ROBOT_ENABLED -> sources.enabled();
      case AUTONOMOUS -> sources.autonomous();
      case TEST_MODE -> sources.test();
      default -> sources.fmsAttached();
    };
    var word = switch (role) {
      case ROBOT_ENABLED -> "enabled";
      case AUTONOMOUS -> "autonomous";
      case TEST_MODE -> "test";
      default -> "FMS-attached";
    };
    var candidates = new ArrayList<String>();
    if (entry != null) candidates.add(entry);
    if (sources.controlWord() != null) candidates.add(sources.controlWord());
    candidates.addAll(sources.ignored());
    String basis;
    if (entry != null) {
      basis = "DriverStation " + word + " entry (AdvantageKit /DriverStation/..., or WPILib "
          + "DS:...)";
    } else if (sources.controlWord() != null) {
      entry = sources.controlWord();
      basis = "NetworkTables FMSControlData control word (bit 0 enabled, bit 1 autonomous, "
          + "bit 2 test, bit 4 FMS attached)";
    } else {
      basis = "no DriverStation state entries in this log";
    }
    return new Resolution(role, entry != null ? List.of(entry) : List.of(), basis,
        candidates.stream().distinct().toList(), !sources.ignored().isEmpty(), null,
        entry != null ? Tier.CONVENTION : Tier.NONE);
  }

  // ==================== power ====================

  private static final Pattern POWER_DISTRIBUTION =
      Pattern.compile("powerdistribution\\d*|pdh|pdp|battery");

  /**
   * Convention rank of a battery-voltage entry, or -1: 0 for a leaf named BatteryVoltage
   * (AdvantageKit /SystemStats/BatteryVoltage, RobotController), 1 for Voltage under a power
   * distribution or battery parent (AdvantageKit /PowerDistribution/Voltage, WPILib's dashboard
   * PowerDistribution[1]/Voltage, PDH/Voltage, Battery/Voltage).
   */
  static int batteryConventionRank(String name) {
    var leaf = leaf(name);
    if (leaf.equals("batteryvoltage")) return 0;
    if (leaf.equals("voltage") && POWER_DISTRIBUTION.matcher(parent(name)).matches()) return 1;
    return -1;
  }

  /**
   * The battery voltage: an explicit entry, else a conventional one with a finite sample (ties to
   * the entry declared first), else the numeric voltage entries that match by name only (never
   * rails, regulator, or motor output voltages) as candidates to confirm.
   *
   * @param prefix Only entries under this prefix, or null
   * @param explicit The caller's voltage_entry, or null
   */
  static Resolution batteryVoltage(LogData log, String prefix, String explicit) {
    if (explicit != null) {
      return explicit(log, Role.BATTERY_VOLTAGE, explicit, "voltage_entry",
          ToolUtils::isNumericType, "a scalar number");
    }
    var numeric = byId(log).stream()
        .filter(e -> prefix == null || e.name().startsWith(prefix))
        .filter(e -> ToolUtils.isNumericType(e.type()) && log.sampleCount(e.name()) > 0)
        .toList();
    var conventionalNames = numeric.stream()
        .filter(e -> batteryConventionRank(e.name()) >= 0)
        .sorted(Comparator.comparingInt((EntryInfo e) -> batteryConventionRank(e.name()))
            .thenComparingInt(EntryInfo::id))
        .map(EntryInfo::name).toList();
    var conventional = conventionalNames.stream().filter(n -> hasFiniteSample(log, n)).toList();
    if (!conventional.isEmpty()) {
      return ranked(Role.BATTERY_VOLTAGE, conventional.get(0), conventional,
          SignalResolver::batteryConventionRank, "a battery voltage by convention "
              + "(BatteryVoltage; Voltage under PowerDistribution, PDH, PDP, or Battery)",
          null, Tier.CONVENTION);
    }
    var byName = numeric.stream()
        .filter(e -> e.name().toLowerCase(Locale.ROOT).contains("voltage"))
        .filter(e -> batteryConventionRank(e.name()) < 0) // conventional, but no finite sample
        .filter(e -> ToolUtils.voltageEntryRank(e.name().toLowerCase(Locale.ROOT)) < 4)
        .sorted(Comparator.comparingInt((EntryInfo e) ->
            ToolUtils.voltageEntryRank(e.name().toLowerCase(Locale.ROOT)))
            .thenComparingInt(EntryInfo::id))
        .map(EntryInfo::name).toList();
    if (!byName.isEmpty()) {
      return heuristic(Role.BATTERY_VOLTAGE, byName, "numeric entries named voltage (not "
          + "rails, regulators, or motor outputs)");
    }
    return new Resolution(Role.BATTERY_VOLTAGE, List.of(), conventionalNames.isEmpty()
        ? "no BatteryVoltage, or Voltage under PowerDistribution/PDH/PDP/Battery"
            + (prefix != null ? " under " + prefix : "")
        : "battery voltage entries with no finite samples: "
            + String.join(", ", conventionalNames), conventionalNames, false, null, Tier.NONE);
  }

  /**
   * The total robot current: an explicit entry, else a leaf named TotalCurrent (AdvantageKit and
   * WPILib power distribution), else names with total or battery current as candidates.
   */
  static Resolution totalCurrent(LogData log, String explicit) {
    if (explicit != null) {
      return explicit(log, Role.TOTAL_CURRENT, explicit, "total_current_entry",
          ToolUtils::isNumericType, "a scalar number");
    }
    var numeric = byId(log).stream()
        .filter(e -> ToolUtils.isNumericType(e.type()) && log.sampleCount(e.name()) > 0)
        .toList();
    var conventional = numeric.stream().filter(e -> leaf(e.name()).equals("totalcurrent"))
        .map(EntryInfo::name).toList();
    if (!conventional.isEmpty()) {
      return ranked(Role.TOTAL_CURRENT, conventional.get(0), conventional, n -> 0,
          "TotalCurrent (AdvantageKit /PowerDistribution/TotalCurrent, WPILib power "
              + "distribution)", null, Tier.CONVENTION);
    }
    // Not AdvantageKit's /SystemStats/BatteryCurrent: the roboRIO's own input current
    var byName = numeric.stream().filter(e -> {
      var lower = e.name().toLowerCase(Locale.ROOT);
      return lower.contains("totalcurrent") || lower.contains("total_current");
    }).map(EntryInfo::name).toList();
    if (!byName.isEmpty()) {
      return heuristic(Role.TOTAL_CURRENT, byName, "numeric entries named total current");
    }
    return new Resolution(Role.TOTAL_CURRENT, List.of(), "no TotalCurrent entry", List.of(),
        false, null, Tier.NONE);
  }

  // ==================== loop time ====================

  /**
   * Loop time. Full cycle: AdvantageKit LoggedRobot/FullCycleMS, else periods derived from
   * AdvantageKit's /Timestamp; user code: LoggedRobot/UserCodeMS. Names containing looptime or
   * cycletime are candidates only.
   */
  static Resolution loopTime(LogData log, Role role, String explicit) {
    if (explicit != null) {
      return explicit(log, role, explicit, "entry", ToolUtils::isNumericType, "a scalar number");
    }
    var numeric = byId(log).stream()
        .filter(e -> ToolUtils.isNumericType(e.type()) && log.sampleCount(e.name()) > 0)
        .toList();
    String want = role == Role.LOOP_TIME_FULL ? "fullcyclems" : "usercodems";
    var conventional = numeric.stream().filter(e -> leaf(e.name()).equals(want))
        .map(EntryInfo::name).toList();
    if (!conventional.isEmpty()) {
      return ranked(role, conventional.get(0), conventional, n -> 0,
          role == Role.LOOP_TIME_FULL ? "AdvantageKit LoggedRobot/FullCycleMS"
              : "AdvantageKit LoggedRobot/UserCodeMS", null, Tier.CONVENTION);
    }
    var byName = numeric.stream().filter(e -> {
      var lower = e.name().toLowerCase(Locale.ROOT);
      return lower.contains("looptime") || lower.contains("loop_time")
          || lower.contains("cycletime") || lower.contains("cycle_time");
    }).map(EntryInfo::name).toList();
    if (role == Role.LOOP_TIME_FULL) {
      var timestamp = log.entries().get("/Timestamp");
      if (timestamp != null && ToolUtils.isNumericType(timestamp.type())) {
        return new Resolution(role, List.of("/Timestamp"), "no FullCycleMS; periods derived "
            + "from AdvantageKit's per-cycle /Timestamp", byName, false, null, Tier.CONVENTION);
      }
    }
    if (!byName.isEmpty()) {
      return heuristic(role, byName, "numeric entries named looptime or cycletime");
    }
    return new Resolution(role, List.of(), role == Role.LOOP_TIME_FULL
        ? "no FullCycleMS or /Timestamp entry" : "no UserCodeMS entry", List.of(), false, null,
        Tier.NONE);
  }

  // ==================== poses ====================

  private static final Pattern SETPOINT = Pattern.compile(
      "(?i)(setpoint|desired|target|commanded|goal)");

  /**
   * Robot pose names by convention, best first: CTRE's swerve template (DriveState/Pose),
   * AdvantageKit's swerve template (Odometry/Robot), a drive subsystem's Pose, an estimator's
   * EstimatedPose or RobotPose, then PathPlanner's copy (PathPlanner/currentPose, logged only
   * while following a path).
   */
  private static final List<Pattern> POSE_CONVENTIONS = List.of(
      Pattern.compile("(?i)(^|/)DriveState/Pose$"),
      Pattern.compile("(?i)(^|/)Odometry/Robot$"),
      Pattern.compile("(?i)(^|/)Drive/Pose$"),
      Pattern.compile("(?i)(^|/)(Estimated|Robot)Pose$"),
      Pattern.compile("(?i)(^|/)PathPlanner/currentPose$"));

  static int poseConventionRank(String name) {
    for (int i = 0; i < POSE_CONVENTIONS.size(); i++) {
      if (POSE_CONVENTIONS.get(i).matcher(name).find()) return i;
    }
    return -1;
  }

  private static boolean isScalarPose(String type) {
    return type.equals("struct:Pose2d") || type.equals("struct:Pose3d");
  }

  /**
   * The robot pose: an explicit entry; else a conventional name; else the only scalar Pose2d
   * (at least two samples, not under a vision path, not named like a setpoint); else those
   * Pose2d entries as candidates to confirm. The same choice analyze_vision makes.
   */
  static Resolution robotPose(LogData log, String explicit) {
    if (explicit != null) {
      return explicit(log, Role.ROBOT_POSE, explicit, "pose_entry",
          SignalResolver::isScalarPose, "struct:Pose2d or struct:Pose3d");
    }
    var candidates = byId(log).stream()
        .filter(e -> e.type().equals("struct:Pose2d"))
        .filter(e -> !FrcDomainTools.AnalyzeVisionTool.VISION_PATH.matcher(e.name()).find())
        .filter(e -> !SETPOINT.matcher(e.name().substring(e.name().lastIndexOf('/') + 1)).find())
        .filter(e -> log.sampleCount(e.name()) >= 2)
        .sorted(Comparator.comparingInt((EntryInfo e) -> -log.sampleCount(e.name()))
            .thenComparingInt(EntryInfo::id))
        .map(EntryInfo::name).toList();
    var conventional = candidates.stream().filter(n -> poseConventionRank(n) >= 0)
        .sorted(Comparator.comparingInt(SignalResolver::poseConventionRank)
            .thenComparingInt(n -> log.entries().get(n).id()))
        .toList();
    if (!conventional.isEmpty()) {
      return ranked(Role.ROBOT_POSE, conventional.get(0), candidates,
          n -> poseConventionRank(n) < 0 ? Integer.MAX_VALUE : poseConventionRank(n),
          "a robot pose by convention (DriveState/Pose, Odometry/Robot, Drive/Pose, "
              + "EstimatedPose, RobotPose, PathPlanner/currentPose)", null, Tier.CONVENTION);
    }
    if (candidates.size() == 1) {
      return new Resolution(Role.ROBOT_POSE, candidates, "the only struct:Pose2d with at least "
          + "two samples outside vision entries", candidates, false, null, Tier.TYPE);
    }
    if (!candidates.isEmpty()) {
      return heuristic(Role.ROBOT_POSE, candidates, "several struct:Pose2d entries, most "
          + "samples first");
    }
    return new Resolution(Role.ROBOT_POSE, List.of(), "no struct:Pose2d entry with at least two "
        + "samples outside vision entries", List.of(), false, null, Tier.NONE);
  }

  /** The legacy form: the robot pose with no explicit entry. */
  static Resolution robotPose(LogData log) {
    return robotPose(log, null);
  }

  /**
   * A vision pose estimate (for drift against the robot pose): an explicit entry; else the only
   * scalar Pose2d/Pose3d with at least two samples under a vision, camera, PhotonVision, or
   * Limelight path; else those entries as candidates to confirm.
   */
  static Resolution visionPose(LogData log, String explicit) {
    if (explicit != null) {
      return explicit(log, Role.VISION_POSE, explicit, "vision_entry",
          SignalResolver::isScalarPose, "struct:Pose2d or struct:Pose3d");
    }
    var candidates = byId(log).stream()
        .filter(e -> isScalarPose(e.type()))
        .filter(e -> FrcDomainTools.AnalyzeVisionTool.VISION_PATH.matcher(e.name()).find())
        .filter(e -> log.sampleCount(e.name()) >= 2)
        .sorted(Comparator.comparingInt((EntryInfo e) -> -log.sampleCount(e.name()))
            .thenComparingInt(EntryInfo::id))
        .map(EntryInfo::name).toList();
    if (candidates.size() == 1) {
      return new Resolution(Role.VISION_POSE, candidates, "the only scalar struct:Pose2d or "
          + "struct:Pose3d with at least two samples under a vision, camera, PhotonVision, or "
          + "Limelight path", candidates, false, null, Tier.TYPE);
    }
    if (!candidates.isEmpty()) {
      return heuristic(Role.VISION_POSE, candidates, "several scalar poses under vision paths, "
          + "most samples first");
    }
    return new Resolution(Role.VISION_POSE, List.of(), "no scalar struct:Pose2d or struct:Pose3d "
        + "with at least two samples under a vision, camera, PhotonVision, or Limelight path",
        List.of(), false, null, Tier.NONE);
  }

  /**
   * Path-following poses. Setpoint: PathPlanner/targetPose or AdvantageKit's
   * Odometry/TrajectorySetpoint by convention; other poses named like a setpoint are candidates.
   * Actual: PathPlanner/currentPose, else the robot pose.
   *
   * @param prefix Only entries under this prefix (analyze_auto's auto_prefix), or null
   */
  static Resolution pathPose(LogData log, Role role, String prefix, String explicit) {
    var param = role == Role.PATH_SETPOINT ? "path_setpoint_entry" : "path_actual_entry";
    if (explicit != null) {
      return explicit(log, role, explicit, param, SignalResolver::isScalarPose,
          "struct:Pose2d or struct:Pose3d");
    }
    var poses = byId(log).stream()
        .filter(e -> prefix == null || e.name().startsWith(prefix))
        .filter(e -> isScalarPose(e.type()) && log.sampleCount(e.name()) >= 2)
        .map(EntryInfo::name).toList();
    if (role == Role.PATH_SETPOINT) {
      var conventional = poses.stream().filter(n -> n.matches("(?i)(.*/)?PathPlanner/targetPose")
          || n.matches("(?i)(.*/)?Odometry/TrajectorySetpoint")).toList();
      if (!conventional.isEmpty()) {
        return ranked(role, conventional.get(0), conventional, n -> 0, "PathPlanner/targetPose "
            + "or AdvantageKit Odometry/TrajectorySetpoint", null, Tier.CONVENTION);
      }
      var byName = poses.stream()
          .filter(n -> SETPOINT.matcher(n.substring(n.lastIndexOf('/') + 1)).find()).toList();
      return byName.isEmpty()
          ? new Resolution(role, List.of(), "no PathPlanner/targetPose or "
              + "Odometry/TrajectorySetpoint" + (prefix != null ? " under " + prefix : ""),
              List.of(), false, null, Tier.NONE)
          : heuristic(role, byName, "poses named like a setpoint (setpoint, target, desired)");
    }
    var planner = poses.stream().filter(n -> n.matches("(?i)(.*/)?PathPlanner/currentPose"))
        .toList();
    if (!planner.isEmpty()) {
      return ranked(role, planner.get(0), planner, n -> 0, "PathPlanner/currentPose", null,
          Tier.CONVENTION);
    }
    var robot = robotPose(log, null);
    if (prefix != null && robot.chosen().isPresent()
        && !robot.chosen().get().startsWith(prefix)) {
      robot = new Resolution(Role.ROBOT_POSE, List.of(), "the robot pose is not under "
          + prefix, robot.candidates(), false, null, Tier.NONE);
    }
    return new Resolution(role, robot.entries(), robot.chosen().isPresent()
        ? "the robot pose: " + robot.basis() : robot.basis(), robot.candidates(),
        robot.ambiguous(), null, robot.tier());
  }

  // ==================== autonomous chooser ====================

  /** AdvantageKit's logged dashboard inputs: /NetworkInputs (2025+) or /DashboardInputs. */
  private static final Pattern AKIT_DASHBOARD_INPUT = Pattern.compile(
      "(^|/)(NetworkInputs|DashboardInputs)/SmartDashboard/[^/]+$");

  /**
   * The selected autonomous routine: a chooser whose key contains "auto" — a WPILib
   * SendableChooser's {@code active} entry (a sibling {@code .type} of "String Chooser", or
   * {@code options}) or an AdvantageKit dashboard input ({@code /NetworkInputs/SmartDashboard/
   * <key>}, as LoggedDashboardChooser logs it) — when there is exactly one. Other choosers, and
   * strings named like a selected auto mode, are candidates.
   */
  static Resolution autoChooser(LogData log, String explicit) {
    if (explicit != null) {
      return explicit(log, Role.AUTO_CHOOSER, explicit, "chooser_entry", "string"::equals,
          "a string");
    }
    var strings = byId(log).stream()
        .filter(e -> "string".equals(e.type()) && log.sampleCount(e.name()) > 0).toList();
    var choosers = new ArrayList<String>();
    for (var e : strings) {
      var name = e.name();
      boolean sendable = leaf(name).equals("active")
          && isSendableChooser(log, name.substring(0, name.lastIndexOf('/')));
      if (sendable || AKIT_DASHBOARD_INPUT.matcher(name).find()) choosers.add(name);
    }
    // The chooser's key: the path segment before /active, or the dashboard input's leaf
    java.util.function.Function<String, String> key = n -> leaf(n).equals("active")
        ? parent(n) : leaf(n);
    var autoChoosers = choosers.stream().filter(n -> key.apply(n).contains("auto")).toList();
    if (autoChoosers.size() == 1) {
      return new Resolution(Role.AUTO_CHOOSER, autoChoosers, "the only chooser whose key "
          + "contains 'auto' (a SendableChooser's active entry, or AdvantageKit's "
          + "/NetworkInputs/SmartDashboard/<key>)", choosers, false, null, Tier.CONVENTION);
    }
    var byName = new ArrayList<String>(autoChoosers.isEmpty() ? choosers : autoChoosers);
    strings.stream()
        .filter(e -> FrcDomainTools.AnalyzeAutoTool.chooserRank(
            e.name().toLowerCase(Locale.ROOT)) < Integer.MAX_VALUE)
        .map(EntryInfo::name)
        .filter(n -> !byName.contains(n))
        .forEach(byName::add);
    if (!byName.isEmpty()) {
      return heuristic(Role.AUTO_CHOOSER, byName, autoChoosers.size() > 1
          ? "several choosers whose key contains 'auto'"
          : "choosers and strings named like a selected auto routine");
    }
    return new Resolution(Role.AUTO_CHOOSER, List.of(), "no chooser (a SendableChooser's "
        + "active entry, or AdvantageKit /NetworkInputs/SmartDashboard/<key>) and no string "
        + "named like a selected auto routine", List.of(), false, null, Tier.NONE);
  }

  /** A WPILib SendableChooser's topics under {@code prefix}: .type "String Chooser" or options. */
  private static boolean isSendableChooser(LogData log, String prefix) {
    var type = log.entries().get(prefix + "/.type");
    if (type != null && "string".equals(type.type())) {
      var values = log.values().get(type.name());
      if (values != null && values.stream()
          .anyMatch(tv -> "String Chooser".equals(tv.value()))) {
        return true;
      }
    }
    return log.entries().containsKey(prefix + "/options");
  }

  // ==================== swerve, chassis, gyro ====================

  private static Resolution moduleStates(LogData log, Role role) {
    var stateEntries = byId(log).stream()
        .filter(e -> e.type().equals("struct:SwerveModuleState")
            || e.type().equals("struct:SwerveModuleState[]"))
        .filter(e -> log.sampleCount(e.name()) > 0).toList();
    var modules = RobotAnalysisTools.AnalyzeSwerveTool.discoverModules(log, stateEntries, null,
        null);
    var chosen = modules.stream()
        .map(m -> role == Role.MODULE_STATES_MEASURED ? m.measuredEntry() : m.setpointEntry())
        .filter(Objects::nonNull).distinct().toList();
    return new Resolution(role, chosen, chosen.isEmpty()
        ? (stateEntries.isEmpty() ? "no SwerveModuleState entries"
            : "no " + (role == Role.MODULE_STATES_MEASURED ? "measured" : "setpoint")
                + " module states among the SwerveModuleState entries")
        : "SwerveModuleState[] (one module per index) or per-module entries; setpoints by "
            + "leaf name (setpoint, desired, target, commanded, goal), optimized setpoints "
            + "preferred", stateEntries.stream().map(EntryInfo::name).toList(), false, null,
        chosen.isEmpty() ? Tier.NONE : Tier.TYPE);
  }

  private static Resolution chassisSpeeds(LogData log, Role role) {
    boolean wantSetpoint = role == Role.CHASSIS_SPEEDS_SETPOINT;
    var word = wantSetpoint ? "setpoint" : "measured";
    var candidates = byId(log).stream()
        .filter(e -> e.type().equals("struct:ChassisSpeeds"))
        .filter(e -> log.sampleCount(e.name()) > 0)
        .filter(e -> SETPOINT.matcher(e.name().substring(e.name().lastIndexOf('/') + 1)).find()
            == wantSetpoint)
        .sorted(Comparator.comparingInt((EntryInfo e) ->
            e.name().toLowerCase(Locale.ROOT).contains(word) ? 0 : 1).thenComparingInt(EntryInfo::id))
        .map(EntryInfo::name).toList();
    if (candidates.isEmpty()) {
      return new Resolution(role, List.of(), "no struct:ChassisSpeeds entry "
          + (wantSetpoint ? "named like a setpoint" : "not named like a setpoint"), candidates,
          false, null, Tier.NONE);
    }
    boolean named = candidates.get(0).toLowerCase(Locale.ROOT).contains(word);
    if (!named && candidates.size() > 1) {
      return heuristic(role, candidates, "several struct:ChassisSpeeds entries");
    }
    return ranked(role, candidates.get(0), candidates,
        n -> n.toLowerCase(Locale.ROOT).contains(word) ? 0 : 1,
        "struct:ChassisSpeeds, " + (named ? "named '" + word + "'" : "the only one "
            + (wantSetpoint ? "named like a setpoint" : "not named like a setpoint")),
        null, named ? Tier.CONVENTION : Tier.TYPE);
  }

  private static final Pattern GYRO_PATH = Pattern.compile(
      "(?i)(gyro|pigeon|navx|canandgyro|imu|ahrs)");

  private static Resolution gyro(LogData log) {
    ToIntFunction<EntryInfo> rank = e -> {
      var leafName = e.name().substring(e.name().lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
      boolean gyroPath = GYRO_PATH.matcher(e.name()).find();
      boolean rotation = e.type().equals("struct:Rotation2d");
      if (gyroPath && rotation && leafName.contains("yaw")) return 0;
      if (gyroPath && leafName.contains("yaw")) return 1;
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
    if (names.isEmpty()) {
      return new Resolution(Role.GYRO_YAW, List.of(), "no Rotation2d or numeric yaw entry under "
          + "a gyro-like path (gyro, pigeon, navx, canandgyro, imu)", names, false, null,
          Tier.NONE);
    }
    if (rank.applyAsInt(candidates.get(0)) == 2) {
      return heuristic(Role.GYRO_YAW, names, "Rotation2d entries under a gyro-like path, none "
          + "named yaw");
    }
    boolean ambiguous = candidates.size() > 1
        && rank.applyAsInt(candidates.get(0)) == rank.applyAsInt(candidates.get(1));
    var basis = "a yaw entry (Rotation2d first) under a gyro-like path (gyro, pigeon, navx, "
        + "canandgyro, imu)"
        + (StructSchemas.structName(log.entries().get(names.get(0)).type()) != null
            ? " (address the angle as " + names.get(0) + ".value)" : "");
    return new Resolution(Role.GYRO_YAW, List.of(names.get(0)), basis, names, ambiguous, null,
        Tier.CONVENTION);
  }
}
