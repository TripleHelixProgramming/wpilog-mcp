/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs.Fixture;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

/**
 * Builds argument sets for running any tool against any fixture, from the tool's own input schema.
 *
 * <p>Every required parameter must have a rule here; an unknown one fails the conformance test so
 * a new tool cannot silently escape it. Entry-valued parameters are filled with entries picked by
 * kind (numeric, struct, struct array, boolean, string, numeric array): the one with the most
 * samples, ties broken by entry id, so choices are deterministic.
 */
final class ToolArguments {

  /** One way of calling a tool. */
  record Variant(String label, JsonObject args) {}

  /** Entry kinds a tool with a {@code name} parameter is exercised with. */
  enum Kind {
    NUMERIC(t -> t.equals("double") || t.equals("float") || t.equals("int64")),
    STRUCT(t -> t.startsWith("struct:") && !t.endsWith("[]")),
    STRUCT_ARRAY(t -> t.startsWith("struct:") && t.endsWith("[]")),
    BOOLEAN(t -> t.equals("boolean")),
    STRING(t -> t.equals("string")),
    NUMERIC_ARRAY(t -> t.equals("double[]") || t.equals("float[]") || t.equals("int64[]"));

    final Predicate<String> type;

    Kind(Predicate<String> type) {
      this.type = type;
    }

    String label() {
      return name().toLowerCase();
    }
  }

  private ToolArguments() {}

  static Optional<String> pick(LogData log, Kind kind, Predicate<String> nameFilter, int skip) {
    return log.entries().values().stream()
        .filter(e -> !e.name().startsWith("/.schema/"))
        .filter(e -> kind.type.test(e.type()))
        .filter(e -> nameFilter.test(e.name()))
        .filter(e -> log.sampleCount(e.name()) > 0)
        .sorted(Comparator.comparingInt((EntryInfo e) -> -log.sampleCount(e.name()))
            .thenComparingInt(EntryInfo::id))
        .skip(skip)
        .map(EntryInfo::name)
        .findFirst();
  }

  static Optional<String> pick(LogData log, Kind kind) {
    return pick(log, kind, n -> true, 0);
  }

  static Set<String> required(Tool tool) {
    var required = new HashSet<String>();
    var schema = tool.inputSchema();
    if (schema.has("required")) {
      schema.getAsJsonArray("required").forEach(e -> required.add(e.getAsString()));
    }
    return required;
  }

  static boolean takesPath(Tool tool) {
    var props = tool.inputSchema().getAsJsonObject("properties");
    return props != null && props.has("path");
  }

  /**
   * Returns the argument variants for {@code tool} on {@code fixture} (or {@code null} fixture for
   * tools that take no log).
   *
   * @throws IllegalStateException if a required parameter has no rule
   */
  static List<Variant> variants(Tool tool, Fixture fixture, LogData log, List<Fixture> all,
      Path exportDir) {
    var required = required(tool);
    var base = new JsonObject();
    if (fixture != null) base.addProperty("path", fixture.path().toString());
    var variants = new ArrayList<Variant>();
    String toolName = tool.name();

    switch (toolName) {
      case "get_entry_info", "read_entry", "get_statistics", "detect_anomalies", "find_peaks",
          "rate_of_change", "find_condition", "export_csv" -> {
        for (var kind : Kind.values()) {
          var entry = pick(log, kind);
          if (entry.isEmpty()) continue;
          var args = base.deepCopy();
          args.addProperty("name", entry.get());
          if (toolName.equals("find_condition")) {
            args.addProperty("operator", "gt");
            args.addProperty("threshold", 0.0);
          }
          if (toolName.equals("export_csv")) {
            args.addProperty("output_path", exportDir
                .resolve(fixture.id() + "-" + kind.label() + ".csv").toString());
          }
          variants.add(new Variant(kind.label(), args));
        }
        if (variants.isEmpty()) {
          var args = base.deepCopy();
          args.addProperty("name", "/Missing/Entry");
          if (toolName.equals("find_condition")) {
            args.addProperty("operator", "gt");
            args.addProperty("threshold", 0.0);
          }
          if (toolName.equals("export_csv")) {
            args.addProperty("output_path", exportDir.resolve(fixture.id() + "-missing.csv")
                .toString());
          }
          variants.add(new Variant("missing", args));
        }
        return withLimitVariant(tool, variants);
      }
      case "align_entries" -> {
        var first = pick(log, Kind.NUMERIC, n -> true, 0);
        var second = pick(log, Kind.NUMERIC, n -> true, 1);
        var args = base.deepCopy();
        var names = new com.google.gson.JsonArray();
        names.add(first.orElse("/Missing/A"));
        names.add(second.orElse(first.orElse("/Missing/B")));
        args.add("names", names);
        args.addProperty("difference", true);
        variants.add(new Variant(first.isPresent() ? "numeric" : "missing", args));
        return withLimitVariant(tool, variants);
      }
      case "compare_entries", "time_correlate" -> {
        for (var kind : List.of(Kind.NUMERIC, Kind.STRUCT)) {
          var first = pick(log, kind, n -> true, 0);
          var second = pick(log, kind, n -> true, 1);
          if (first.isEmpty()) continue;
          var args = base.deepCopy();
          args.addProperty("name1", first.get());
          args.addProperty("name2", second.orElse(first.get()));
          variants.add(new Variant(kind.label(), args));
        }
        if (variants.isEmpty()) {
          var args = base.deepCopy();
          args.addProperty("name1", "/Missing/A");
          args.addProperty("name2", "/Missing/B");
          variants.add(new Variant("missing", args));
        }
        return variants;
      }
      case "compare_poses" -> {
        // reference_entry is required: the second pose entry, else the first against itself
        var poses = log.entries().values().stream()
            .filter(e -> e.type().equals("struct:Pose2d") || e.type().equals("struct:Pose3d"))
            .sorted(java.util.Comparator.comparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
            .map(org.triplehelix.wpilogmcp.log.EntryInfo::name).toList();
        var args = base.deepCopy();
        args.addProperty("reference_entry", poses.isEmpty() ? "/Missing/Pose"
            : poses.get(Math.min(1, poses.size() - 1)));
        variants.add(new Variant(poses.isEmpty() ? "missing" : "poses", args));
        var reference = args.deepCopy();
        reference.addProperty("frame", "reference");
        variants.add(new Variant("reference-frame", reference));
        return variants;
      }
      case "pose_corrections" -> {
        // The default threshold, and one low enough that fixtures produce corrections
        variants.add(new Variant("default", base.deepCopy()));
        var low = base.deepCopy();
        low.addProperty("threshold_m", 0.001);
        variants.add(new Variant("low-threshold", low));
        return withLimitVariant(tool, variants);
      }
      case "profile_mechanism" -> {
        // mechanism_name is optional in the schema but the tool needs it (or role entries), so
        // pass one: the elevator where there is one, else the drive
        var args = base.deepCopy();
        args.addProperty("mechanism_name",
            log.entries().keySet().stream().anyMatch(n -> n.contains("Elevator"))
                ? "Elevator" : "Drive");
        variants.add(new Variant("mechanism", args));
        return variants;
      }
      case "analyze_cycles" -> {
        // cycle_start_state is optional in the schema but needed in the default mode: use the
        // state entry's second distinct value (the first is usually the idle state)
        var stateEntry = pick(log, Kind.STRING, n -> n.endsWith("/Command"), 0)
            .or(() -> pick(log, Kind.STRING));
        var args = base.deepCopy();
        args.addProperty("state_entry", stateEntry.orElse("/Missing/Command"));
        stateEntry.flatMap(e -> log.values().get(e).stream()
                .map(v -> String.valueOf(v.value())).distinct().skip(1).findFirst())
            .ifPresent(state -> args.addProperty("cycle_start_state", state));
        variants.add(new Variant(stateEntry.isPresent() ? "state" : "missing", args));
        return withLimitVariant(tool, variants);
      }
      default -> {
        // one variant, below
      }
    }

    var args = base.deepCopy();
    fillRequired(tool, required, fixture, log, all, args);
    variants.add(new Variant("default", args));
    return withLimitVariant(tool, variants);
  }

  /** Adds a {@code limit: 2} copy of the first variant when the tool takes a limit. */
  static List<Variant> withLimitVariant(Tool tool, List<Variant> variants) {
    var props = tool.inputSchema().getAsJsonObject("properties");
    if (props != null && props.has("limit") && !variants.isEmpty()) {
      var limited = variants.get(0).args().deepCopy();
      limited.addProperty("limit", 2);
      variants.add(new Variant(variants.get(0).label() + "-limit2", limited));
    }
    return variants;
  }

  static void fillRequired(Tool tool, Set<String> required, Fixture fixture, LogData log,
      List<Fixture> all, JsonObject args) {
    String toolName = tool.name();
    for (var param : required) {
      if (param.equals("path")) continue;
      switch (param) {
        case "compare_path" -> {
          var other = all.stream()
              .filter(f -> !f.id().equals(fixture.id()) && f.id().startsWith("akit_"))
              .findFirst().orElse(fixture);
          args.addProperty(param, other.path().toString());
        }
        case "name" -> args.addProperty(param, pick(log, Kind.NUMERIC).orElse("/Missing/Entry"));
        case "velocity_entry" -> args.addProperty(param,
            pick(log, Kind.NUMERIC, n -> n.contains("Velocity"), 0)
                .or(() -> pick(log, Kind.NUMERIC)).orElse("/Missing/Velocity"));
        case "current_entry" -> args.addProperty(param,
            pick(log, Kind.NUMERIC, n -> n.contains("Current"), 0)
                .or(() -> pick(log, Kind.NUMERIC)).orElse("/Missing/Current"));
        case "kt" -> args.addProperty(param, 0.0192);
        case "gear_ratio" -> args.addProperty(param, 6.75);
        case "task" -> args.addProperty(param, "why did the robot brown out during teleop");
        case "signal_key" -> args.addProperty(param, "missing-signal");
        case "offset_ms" -> args.addProperty(param, 0.0);
        case "year" -> args.addProperty(param, 2026);
        case "event_code" -> args.addProperty(param, "2026test");
        case "match_type" -> args.addProperty(param, "qm");
        case "match_number" -> args.addProperty(param, 1);
        default -> throw new IllegalStateException(
            "No conformance argument rule for required parameter '" + param + "' of tool '"
                + toolName + "'. Add one to ToolArguments.");
      }
    }
  }
}
