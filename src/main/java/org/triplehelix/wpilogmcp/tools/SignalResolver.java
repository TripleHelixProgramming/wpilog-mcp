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
import java.util.Optional;
import java.util.Arrays;
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
 * WPILib, CTRE, PathPlanner, YAGSL, Limelight, PhotonVision names; see each role's rules below),
 * or is the only entry of the role's type. Entries that match a role by name alone are
 * {@link Tier#HEURISTIC}: they are listed as candidates for the caller to confirm and pass
 * explicitly, and never used silently. A word in a name is not evidence: what an entry holds is
 * decided by the robot code that logs it, which is where a caller confirms a candidate.
 * Choices are deterministic: ties go to the entry declared first and are flagged
 * {@code ambiguous}.
 *
 * @since 0.9.0
 */
public final class SignalResolver {

  private SignalResolver() {}

  /** Metadata names are shared with the directory scanner; suggestive leaf names do not count. */
  public enum MetadataRole {
    SERIAL("string", "/systemstats/serialnumber"),
    COMMENTS("string", "/systemstats/comments"),
    EVENT("string", "/driverstation/eventname", "/fmsinfo/eventname"),
    MATCH_TYPE("int64", "/driverstation/matchtype", "/fmsinfo/matchtype"),
    MATCH_NUMBER("int64", "/driverstation/matchnumber", "/fmsinfo/matchnumber"),
    TEAM("int64", "/systemstats/teamnumber");

    private final String type;
    private final List<String> names;

    MetadataRole(String type, String... names) {
      this.type = type;
      this.names = List.of(names);
    }

    public static Optional<MetadataRole> of(String name, String type) {
      var lower = name.toLowerCase(Locale.ROOT);
      return Arrays.stream(values())
          .filter(r -> r.type.equals(type) && r.names.stream().anyMatch(lower::endsWith))
          .findFirst();
    }
  }

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
          + String.join(", ", Arrays.stream(values()).map(Role::wire).toList()));
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
    Optional<String> chosen() {
      return entries.isEmpty() ? Optional.empty()
          : Optional.of(entries.get(0));
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
      case MODULE_STATES_MEASURED -> moduleStates(log, null, null, null).measured();
      case MODULE_STATES_SETPOINT -> moduleStates(log, null, null, null).setpoint();
      case CHASSIS_SPEEDS_MEASURED, CHASSIS_SPEEDS_SETPOINT -> chassisSpeeds(log, role);
      case GYRO_YAW -> gyro(log);
      case VISION_POSE_OBSERVATIONS -> {
        var vision = visionEntries(log, null, null);
        var streams = vision.analyzed(VisionKind.OBSERVATION_STREAM);
        var candidates = new ArrayList<String>(streams);
        candidates.addAll(vision.candidates(VisionKind.OBSERVATION_STREAM));
        if (!streams.isEmpty()) {
          yield new Resolution(role, streams, "struct:PoseObservation[] entries (the AdvantageKit "
              + "vision template's record: a timestamp and a pose), one per camera", candidates,
              false, null, Tier.CONVENTION);
        }
        yield candidates.isEmpty()
            ? new Resolution(role, List.of(), "no struct:PoseObservation[] entry, and no other "
                + "struct array whose records hold a timestamp and a pose", List.of(), false,
                null, Tier.NONE)
            : undecided(role, candidates, "struct arrays whose records hold a timestamp and a "
                + "pose, which a planned trajectory's samples do as well as a camera's "
                + "observations");
      }
      case VISION_TARGETS -> {
        var vision = visionEntries(log, null, null);
        var targets = new ArrayList<String>(vision.analyzed(VisionKind.TARGET_STREAM));
        targets.addAll(vision.analyzed(VisionKind.HAS_TARGET));
        var candidates = new ArrayList<String>(targets);
        candidates.addAll(vision.candidates(VisionKind.TARGET_STREAM));
        candidates.addAll(vision.candidates(VisionKind.HAS_TARGET));
        if (!targets.isEmpty()) {
          yield new Resolution(role, targets, "struct:TargetObservation entries with yaw and "
              + "pitch fields (the AdvantageKit vision template's record), and has-target "
              + "entries by convention (" + HAS_TARGET_CONVENTIONS + ")", candidates, false, null,
              Tier.CONVENTION);
        }
        yield candidates.isEmpty()
            ? new Resolution(role, List.of(), "no struct:TargetObservation entry and no "
                + "has-target entry (" + HAS_TARGET_CONVENTIONS + ")", List.of(), false, null,
                Tier.NONE)
            : heuristic(role, candidates, "structs with yaw and pitch fields under another "
                + "name, and flags named like a has-target entry");
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

  /** Candidates of the role's type that no convention tells apart: listed, never chosen. */
  static Resolution undecided(Role role, List<String> candidates, String how) {
    return new Resolution(role, List.of(), "no entry follows a known convention for this role; "
        + how + " (not chosen)", candidates, false, null, Tier.HEURISTIC);
  }

  /** How a caller confirms what an entry holds: the code that logs it, then its values. */
  static final String HOW_TO_CONFIRM = "the robot's source code, where the entry is logged, "
      + "shows what it holds and in which units; get_entry_info and read_entry show its type and "
      + "values; or ask the user";

  /**
   * Why a tool could not use a role, for a {@code skipped} section or a {@code no_match}
   * reason: the candidates to confirm, or what was searched, and the parameter to pass.
   */
  static String unresolvedReason(Resolution r, String param) {
    var what = r.role().description.toLowerCase(Locale.ROOT).replaceFirst("^the ", "");
    if (r.needsConfirmation()) {
      return "No entry follows a known convention for the " + what + ". Candidates, not used: "
          + String.join(", ", r.candidates().stream().limit(5).toList())
          + (r.candidates().size() > 5 ? ", ..." : "") + ". Confirm which one (if any) is the "
          + what + " (" + HOW_TO_CONFIRM + ") and pass it as " + param
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
    return robotPose(log, explicit, "pose_entry");
  }

  /** The robot pose, with errors about an explicit entry naming the tool's parameter. */
  static Resolution robotPose(LogData log, String explicit, String param) {
    if (explicit != null) {
      return explicit(log, Role.ROBOT_POSE, explicit, param,
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

  /**
   * A published naming of swerve module states: the measured entry, and the setpoint entries
   * logged beside it in the same table, best first.
   */
  record ModuleStateConvention(String source, String measured, List<String> setpoints) {

    /** "SwerveStates/Measured with SwerveStates/SetpointsOptimized or ... (source)". */
    String describe() {
      return measured + " with " + String.join(" or ", setpoints) + " (" + source + ")";
    }

    /** The table the measured entry {@code name} is in ("/RealOutputs/"), or null. */
    String tableOf(String name) {
      var m = Pattern.compile("(?i)^(.*[/:])?" + Pattern.quote(measured) + "$").matcher(name);
      return m.matches() ? (m.group(1) == null ? "" : m.group(1)) : null;
    }
  }

  /**
   * The module-state names the swerve libraries and templates publish. A team's own names (an
   * "Actual" beside a "Commanded", two target arrays beside the measured one) are not here:
   * what such an entry holds is decided by that team's code.
   */
  static final List<ModuleStateConvention> MODULE_STATE_CONVENTIONS = List.of(
      new ModuleStateConvention("AdvantageKit swerve template", "SwerveStates/Measured",
          List.of("SwerveStates/SetpointsOptimized", "SwerveStates/Setpoints")),
      new ModuleStateConvention("CTRE swerve telemetry", "DriveState/ModuleStates",
          List.of("DriveState/ModuleTargets")),
      new ModuleStateConvention("YAGSL telemetry", "swerve/advantagescope/currentStates",
          List.of("swerve/advantagescope/desiredStates")));

  private static Optional<ModuleStateConvention> moduleConvention(String name) {
    return MODULE_STATE_CONVENTIONS.stream().filter(c -> c.tableOf(name) != null).findFirst();
  }

  private static boolean isModuleStates(String type) {
    return type.equals("struct:SwerveModuleState") || type.equals("struct:SwerveModuleState[]");
  }

  /** The measured and setpoint module states analyze_swerve reads. */
  record ModuleStates(Resolution measured, Resolution setpoint) {}

  /**
   * Swerve module states. Measured: an explicit entry; else a conventional name (ties to the
   * entry declared first, flagged ambiguous); else the only SwerveModuleState entry, when it is
   * not named like a setpoint; else the entries as candidates. Setpoint: an explicit entry; else
   * the convention's setpoint beside the measured entry, in the same table; else the other
   * entries as candidates. No entry is taken for a setpoint, or for the measured states, on the
   * strength of a word in its name.
   *
   * @param prefix Only entries under this prefix (analyze_swerve's module_prefix), or null
   * @param measuredExplicit The caller's measured_entry, or null
   * @param setpointExplicit The caller's setpoint_entry, or null
   */
  static ModuleStates moduleStates(LogData log, String prefix, String measuredExplicit,
      String setpointExplicit) {
    var names = byId(log).stream()
        .filter(e -> isModuleStates(e.type()))
        .filter(e -> prefix == null || e.name().startsWith(prefix))
        .filter(e -> log.sampleCount(e.name()) > 0)
        .map(EntryInfo::name).toList();
    var under = prefix != null ? " under " + prefix : "";

    Resolution measured;
    if (measuredExplicit != null) {
      measured = explicit(log, Role.MODULE_STATES_MEASURED, measuredExplicit, "measured_entry",
          SignalResolver::isModuleStates, "struct:SwerveModuleState or struct:SwerveModuleState[]");
    } else {
      var conventional = names.stream().filter(n -> moduleConvention(n).isPresent()).toList();
      if (!conventional.isEmpty()) {
        var chosen = conventional.get(0);
        measured = new Resolution(Role.MODULE_STATES_MEASURED, List.of(chosen),
            moduleConvention(chosen).orElseThrow().measured() + " ("
                + moduleConvention(chosen).orElseThrow().source() + ")", names,
            conventional.size() > 1, null, Tier.CONVENTION);
      } else if (names.size() == 1
          && !RobotAnalysisTools.AnalyzeSwerveTool.isSetpointName(names.get(0))) {
        measured = new Resolution(Role.MODULE_STATES_MEASURED, names, "the only SwerveModuleState "
            + "entry" + under + ", not named like a setpoint; the log does not say whether it "
            + "holds measured or commanded states", names, false, null, Tier.TYPE);
      } else if (!names.isEmpty()) {
        measured = undecided(Role.MODULE_STATES_MEASURED, names, names.size() == 1
            ? "the only SwerveModuleState entry" + under + " is named like a setpoint"
            : "several SwerveModuleState entries" + under + ", none under a published name");
      } else {
        measured = new Resolution(Role.MODULE_STATES_MEASURED, List.of(),
            "no SwerveModuleState entries" + under, List.of(), false, null, Tier.NONE);
      }
    }

    Resolution setpoint;
    var chosenMeasured = measured.chosen().orElse(null);
    var others = names.stream().filter(n -> !n.equals(chosenMeasured)).toList();
    if (setpointExplicit != null) {
      setpoint = explicit(log, Role.MODULE_STATES_SETPOINT, setpointExplicit, "setpoint_entry",
          SignalResolver::isModuleStates, "struct:SwerveModuleState or struct:SwerveModuleState[]");
    } else if (chosenMeasured == null) {
      setpoint = names.isEmpty()
          ? new Resolution(Role.MODULE_STATES_SETPOINT, List.of(), "no SwerveModuleState entries"
              + under, List.of(), false, null, Tier.NONE)
          : undecided(Role.MODULE_STATES_SETPOINT, names, "the measured module states are not "
              + "resolved, so no setpoint is paired with them");
    } else {
      // The convention's setpoints beside the measured entry: same table, same shape
      var convention = moduleConvention(chosenMeasured);
      var measuredType = log.entries().get(chosenMeasured).type();
      String paired = null;
      if (convention.isPresent()) {
        var table = convention.get().tableOf(chosenMeasured);
        for (var suffix : convention.get().setpoints()) {
          var match = byId(log).stream()
              .filter(e -> e.name().equalsIgnoreCase(table + suffix))
              .filter(e -> e.type().equals(measuredType) && log.sampleCount(e.name()) > 0)
              .map(EntryInfo::name).findFirst();
          if (match.isPresent()) {
            paired = match.get();
            break;
          }
        }
      }
      if (paired != null) {
        setpoint = new Resolution(Role.MODULE_STATES_SETPOINT, List.of(paired),
            paired.substring(convention.get().tableOf(chosenMeasured).length()) + " beside the "
                + "measured entry (" + convention.get().source() + ")", others, false, null,
            Tier.CONVENTION);
      } else if (!others.isEmpty()) {
        setpoint = undecided(Role.MODULE_STATES_SETPOINT, others, "the other SwerveModuleState "
            + "entries; none is the measured entry's setpoint by a published name");
      } else {
        setpoint = new Resolution(Role.MODULE_STATES_SETPOINT, List.of(), "no SwerveModuleState "
            + "entry besides the measured one", List.of(), false, null, Tier.NONE);
      }
    }
    return new ModuleStates(measured, setpoint);
  }

  // ==================== vision entries ====================

  private static final Pattern LIMELIGHT_TV = Pattern.compile("(?i)(^|[/:])limelight[^/]*/tv$");
  private static final Pattern PHOTON_HAS_TARGET =
      Pattern.compile("(?i)(^|[/:])photonvision/[^/]+/hasTarget$");
  static final String HAS_TARGET_CONVENTIONS = "Limelight's <table>/tv, PhotonVision's "
      + "photonvision/<camera>/hasTarget";
  /** The pose arrays the AdvantageKit vision template records. */
  private static final Pattern TEMPLATE_POSE_SET = Pattern.compile(
      "(?i)(^|/)Vision/(Summary|Camera\\d+)/(TagPoses|RobotPoses|RobotPosesAccepted|"
          + "RobotPosesRejected)$");
  static final String POSE_SET_CONVENTION = "Vision/Summary/ and Vision/Camera<N>/ TagPoses, "
      + "RobotPoses, RobotPosesAccepted, RobotPosesRejected";

  private static boolean isFlagType(String type) {
    return type.equals("boolean") || ToolUtils.isNumericType(type);
  }

  /** The kinds of vision data analyze_vision reads. */
  enum VisionKind {
    OBSERVATION_STREAM("observation_streams"),
    TARGET_STREAM("target_streams"),
    POSE_SET("pose_sets"),
    HAS_TARGET("has_target"),
    POSE_ESTIMATE("pose_estimates");

    final String key;

    VisionKind(String key) {
      this.key = key;
    }
  }

  /**
   * The entries analyze_vision analyzes, by kind, and the entries that only look like vision
   * data, which it lists and does not read.
   */
  record VisionEntries(Map<VisionKind, List<String>> analyzed,
      Map<VisionKind, List<String>> candidates) {

    List<String> analyzed(VisionKind kind) {
      return analyzed.getOrDefault(kind, List.of());
    }

    List<String> candidates(VisionKind kind) {
      return candidates.getOrDefault(kind, List.of());
    }
  }

  /**
   * Vision entries. Analyzed: what the AdvantageKit vision template and the vision libraries
   * publish under their own names ({@code struct:PoseObservation[]} streams,
   * {@code struct:TargetObservation} with yaw and pitch, the template's pose arrays, Limelight's
   * {@code tv}, PhotonVision's {@code hasTarget}), the only scalar pose under a vision path, and
   * the entries the caller passes, each by its shape. Candidates: entries that have the content
   * or the name of vision data without being those. Real logs show why content is not enough:
   * a planned trajectory is a struct array of timestamps and poses, and a gyro's struct has yaw
   * and pitch. Candidates are found from their type and schema, never by decoding them.
   *
   * @param prefix Only entries under this prefix, case-insensitive (vision_prefix), or null;
   *     entries passed explicitly are not limited by it
   * @param explicit The caller's vision_entries, or null
   * @throws IllegalArgumentException if an explicit entry is missing or has a shape
   *     analyze_vision does not read
   */
  static VisionEntries visionEntries(LogData log, String prefix, List<String> explicit) {
    var analyzed = new java.util.EnumMap<VisionKind, List<String>>(VisionKind.class);
    var candidates = new java.util.EnumMap<VisionKind, List<String>>(VisionKind.class);
    for (var kind : VisionKind.values()) {
      analyzed.put(kind, new ArrayList<>());
      candidates.put(kind, new ArrayList<>());
    }
    var passed = explicit == null ? List.<String>of() : explicit;
    for (var name : passed) {
      var info = log.entries().get(name);
      if (info == null) {
        throw new IllegalArgumentException("vision_entries " + name + " is not in this log. "
            + "Use search_entries or resolve_signals to find the entry.");
      }
      var kind = shapeOf(log, info);
      if (kind == null) {
        throw new IllegalArgumentException("vision_entries " + name + " is " + info.type()
            + ": analyze_vision reads has-target flags (boolean or number), pose arrays "
            + "(struct:Pose2d[], struct:Pose3d[]), scalar poses, struct arrays whose records "
            + "hold a timestamp and a pose, and structs with yaw and pitch fields.");
      }
      if (!analyzed.get(kind).contains(name)) analyzed.get(kind).add(name);
    }

    var scalarVisionPoses = new ArrayList<String>();
    for (var e : byId(log)) {
      var name = e.name();
      if (passed.contains(name)) continue;
      if (!FrcDomainTools.AnalyzeVisionTool.underPrefix(name, prefix)) continue;
      var type = e.type();
      boolean visionPath = FrcDomainTools.AnalyzeVisionTool.VISION_PATH.matcher(name).find();
      if (isFlagType(type)) {
        if (LIMELIGHT_TV.matcher(name).find() || PHOTON_HAS_TARGET.matcher(name).find()) {
          analyzed.get(VisionKind.HAS_TARGET).add(name);
        } else {
          var lower = name.toLowerCase(Locale.ROOT);
          if (lower.contains("hastarget") || lower.contains("targetvalid")
              || lower.endsWith("/tv") || lower.endsWith(".tv")) {
            candidates.get(VisionKind.HAS_TARGET).add(name);
          }
        }
      } else if (type.equals("struct:Pose3d[]") || type.equals("struct:Pose2d[]")) {
        if (TEMPLATE_POSE_SET.matcher(name).find()) {
          analyzed.get(VisionKind.POSE_SET).add(name);
        } else if (visionPath) {
          candidates.get(VisionKind.POSE_SET).add(name);
        }
      } else if (isScalarPose(type)) {
        if (visionPath && log.sampleCount(name) >= 2) scalarVisionPoses.add(name);
      } else if (type.startsWith("struct:")) {
        var struct = StructSchemas.structName(type);
        if (type.endsWith("[]") && "PoseObservation".equals(struct)
            && FrcDomainTools.AnalyzeVisionTool.isObservationStream(log, e)) {
          analyzed.get(VisionKind.OBSERVATION_STREAM).add(name);
        } else if ("TargetObservation".equals(struct)
            && FrcDomainTools.AnalyzeVisionTool.isTargetStream(log, e)) {
          analyzed.get(VisionKind.TARGET_STREAM).add(name);
        } else {
          // Another struct with the same fields: judged from its schema, never decoded
          var schema = log.structSchemas().info(struct);
          if (schema.isEmpty()) continue;
          if (type.endsWith("[]")
              && FrcDomainTools.AnalyzeVisionTool.isObservationStream(log, e)) {
            candidates.get(VisionKind.OBSERVATION_STREAM).add(name);
          } else if (FrcDomainTools.AnalyzeVisionTool.isTargetStream(log, e)) {
            candidates.get(VisionKind.TARGET_STREAM).add(name);
          }
        }
      }
    }
    // A vision pose estimate to check for jumps: the only scalar pose under a vision path
    // (the vision_pose role); several are candidates
    if (scalarVisionPoses.size() == 1
        && analyzed.get(VisionKind.POSE_ESTIMATE).isEmpty()) {
      analyzed.get(VisionKind.POSE_ESTIMATE).addAll(scalarVisionPoses);
    } else {
      candidates.get(VisionKind.POSE_ESTIMATE).addAll(scalarVisionPoses);
    }
    return new VisionEntries(analyzed, candidates);
  }

  /** The kind an entry passed explicitly is analyzed as, by its shape, or null. */
  private static VisionKind shapeOf(LogData log, EntryInfo e) {
    var type = e.type();
    if (isFlagType(type)) return VisionKind.HAS_TARGET;
    if (type.equals("struct:Pose3d[]") || type.equals("struct:Pose2d[]")) {
      return VisionKind.POSE_SET;
    }
    if (isScalarPose(type)) return VisionKind.POSE_ESTIMATE;
    if (!type.startsWith("struct:")) return null;
    if (type.endsWith("[]") && FrcDomainTools.AnalyzeVisionTool.isObservationStream(log, e)) {
      return VisionKind.OBSERVATION_STREAM;
    }
    return FrcDomainTools.AnalyzeVisionTool.isTargetStream(log, e) ? VisionKind.TARGET_STREAM
        : null;
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
