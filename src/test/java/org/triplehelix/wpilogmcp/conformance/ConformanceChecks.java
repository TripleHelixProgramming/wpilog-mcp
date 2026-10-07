/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The result checks every tool must pass on every fixture (the rules in
 * {@code doc/ROBUSTNESS_REVIEW.md} section 6, plus basic shape and crash checks).
 */
public final class ConformanceChecks {

  private ConformanceChecks() {}

  /** A check that can fail for a tool call. */
  public enum Check {
    /** The result is a JSON object with a boolean {@code success}; failures carry an error. */
    SHAPE,
    /** The tool threw an unexpected exception (reported as "Internal error: ..."). */
    INTERNAL_ERROR,
    /** A NaN or infinite number appears anywhere in the result. */
    NON_FINITE,
    /** A successful result carries no information at all and no warning (rule R4). */
    SILENT_EMPTY,
    /** The result differs when the log's entries iterate in a different order (rule B9). */
    NONDETERMINISTIC,
    /**
     * {@code status} is missing or unknown, disagrees with {@code success}, or a
     * {@code not_applicable}/{@code no_match} result has no {@code reason} (rule R4).
     */
    STATUS,
    /** A {@code limits} entry is inconsistent with its list (rule R7). */
    LIMITS,
    /** A list has exactly as many items as the requested limit but no {@code limits} entry. */
    UNREPORTED_TRUNCATION,
    /** A successful result from a log-reading tool does not say what it used (rule R3). */
    INPUTS,
    /** The call did not finish in time. */
    TIMEOUT,
    /** A successful result of an analytical tool carries no {@code data_quality} (rule R6). */
    QUALITY_MISSING,
    /** A result computed from a log that was not read to its end does not say so. */
    TRUNCATION_UNREPORTED;

    public String label() {
      return name().toLowerCase();
    }
  }

  /** Keys that describe a result rather than carry its content. */
  static final Set<String> BOOKKEEPING = Set.of("success", "status", "warnings", "_metadata",
      "data_quality", "server_analysis_directives", "_execution_time_ms", "inputs", "resolved",
      "skipped", "limits", "reason", "looked_for", "hint");

  static final Set<String> STATUSES = Set.of("ok", "partial", "not_applicable", "no_match", "error");

  /**
   * Keys that name what was analyzed or how, not what was found: a result whose only content is
   * such labels (and echoes of its arguments) found nothing.
   */
  static final Set<String> LABELS = Set.of("name", "entry", "log_path", "path", "signal_key",
      "condition", "conditions", "scope", "pattern", "mechanism_name", "expression",
      "brownout_threshold", "threshold", "method", "unit", "type", "can_bus", "season", "year",
      "event_code", "match_type", "match_number", "team_number", "window_sec", "smooth_window",
      "bus", "chosen_stem", "mechanism", "sampling", "log_directories", "export_directory", "tool",
      "query", "task", "combine", "interpolation", "time_source");

  /** Tools whose successful results are statistics over samples and so carry data_quality. */
  static final Set<String> QUALITY_TOOLS = Set.of("get_statistics", "detect_anomalies",
      "find_peaks", "rate_of_change", "time_correlate", "compare_entries", "find_condition",
      "compare_matches", "analyze_swerve", "power_analysis", "predict_battery_health",
      "profile_mechanism", "analyze_loop_timing", "analyze_can_bus", "compare_poses",
      "pose_corrections");

  /**
   * Runs the single-result checks. {@code limit} is the limit the call requested, or null;
   * {@code readsLog} is whether the tool reads a log (and so must report its inputs).
   */
  public static List<Check> check(JsonElement result, Integer limit, boolean readsLog) {
    return check(result, limit, readsLog, null, null);
  }

  /**
   * As above, with the call itself: {@code tool} selects the tool-specific rules (data_quality
   * on analytical tools) and {@code args} lets the silent-empty rule ignore echoed arguments.
   */
  public static List<Check> check(JsonElement result, Integer limit, boolean readsLog,
      String tool, JsonObject args) {
    var failed = check(result);
    if (result == null || !result.isJsonObject()) return failed;
    var obj = result.getAsJsonObject();
    if (!statusConsistent(obj)) failed.add(Check.STATUS);
    if (!limitsConsistent(obj)) failed.add(Check.LIMITS);
    if (limit != null && hasUnreportedTruncation(obj, limit)) {
      failed.add(Check.UNREPORTED_TRUNCATION);
    }
    if (readsLog && succeeded(obj) && !hasInputs(obj)) failed.add(Check.INPUTS);
    if (args != null && succeeded(obj) && !failed.contains(Check.SILENT_EMPTY)
        && isSilentEmpty(obj, args)) {
      failed.add(Check.SILENT_EMPTY);
    }
    // A partial result may have skipped its statistical section (power_analysis without a
    // voltage entry), so only a whole (ok) result must carry quality
    if (tool != null && QUALITY_TOOLS.contains(tool) && succeeded(obj)
        && "ok".equals(obj.get("status").getAsString()) && !hasQuality(obj, 3)) {
      failed.add(Check.QUALITY_MISSING);
    }
    // A timeout is an observation from a bounded wait, explicitly ok/changed:false in the
    // live-tool contract. This exception is tool-specific; empty analyses still fail R4.
    if ("wait_for_change".equals(tool) && new com.google.gson.JsonPrimitive("ok").equals(obj.get("status"))
        && new com.google.gson.JsonPrimitive(false).equals(obj.get("changed"))) failed.remove(Check.SILENT_EMPTY);
    return failed;
  }

  /**
   * Whether a result computed from a truncated log says so: {@code _metadata.log_truncation},
   * or a {@code log_truncation} on one of its parts where a tool reads several logs
   * (compare_matches). An error result, which computed nothing from the log, need not.
   */
  public static boolean reportsTruncation(JsonObject obj) {
    var status = obj.get("status");
    if (status != null && status.isJsonPrimitive() && "error".equals(status.getAsString())) {
      return true;
    }
    var metadata = obj.get("_metadata");
    if (metadata != null && metadata.isJsonObject()
        && metadata.getAsJsonObject().has("log_truncation")) {
      return true;
    }
    return hasKey(obj, "log_truncation", 3);
  }

  private static boolean hasKey(JsonObject obj, String key, int depth) {
    if (obj.has(key)) return true;
    if (depth == 0) return false;
    for (var entry : obj.entrySet()) {
      var v = entry.getValue();
      if (v.isJsonObject() && hasKey(v.getAsJsonObject(), key, depth - 1)) return true;
      if (v.isJsonArray()) {
        for (var item : v.getAsJsonArray()) {
          if (item.isJsonObject() && hasKey(item.getAsJsonObject(), key, depth - 1)) return true;
        }
      }
    }
    return false;
  }

  /**
   * A {@code data_quality} object with a sample count, at the top level or within {@code depth}
   * levels (compare_matches carries one per compared log).
   */
  static boolean hasQuality(JsonObject obj, int depth) {
    var quality = obj.get("data_quality");
    if (quality != null && quality.isJsonObject()
        && quality.getAsJsonObject().has("sample_count")) {
      return true;
    }
    if (depth == 0) return false;
    for (var entry : obj.entrySet()) {
      var v = entry.getValue();
      if (v.isJsonObject() && hasQuality(v.getAsJsonObject(), depth - 1)) return true;
      if (v.isJsonArray()) {
        for (var item : v.getAsJsonArray()) {
          if (item.isJsonObject() && hasQuality(item.getAsJsonObject(), depth - 1)) return true;
        }
      }
    }
    return false;
  }

  /**
   * As {@link #isSilentEmpty(JsonObject)}, but labels ({@link #LABELS}) and values that merely
   * echo an argument do not count as information: {@code {"entry": "/x", "scope": "enabled"}}
   * says what was asked, not what was found.
   */
  static boolean isSilentEmpty(JsonObject obj, JsonObject args) {
    var echoes = new java.util.HashSet<JsonElement>();
    for (var a : args.entrySet()) {
      if (a.getValue().isJsonPrimitive()) echoes.add(a.getValue());
    }
    for (var entry : obj.entrySet()) {
      if (BOOKKEEPING.contains(entry.getKey()) || LABELS.contains(entry.getKey())) continue;
      var v = entry.getValue();
      if (v.isJsonPrimitive() && echoes.contains(v)) continue;
      if (informative(v)) return false;
    }
    return true;
  }

  static boolean succeeded(JsonObject obj) {
    var success = obj.get("success");
    return success != null && success.isJsonPrimitive() && success.getAsBoolean();
  }

  /** An {@code inputs} object with at least one field. */
  static boolean hasInputs(JsonObject obj) {
    var inputs = obj.get("inputs");
    return inputs != null && inputs.isJsonObject() && !inputs.getAsJsonObject().isEmpty();
  }

  static boolean statusConsistent(JsonObject obj) {
    var status = obj.get("status");
    if (status == null || !status.isJsonPrimitive() || !STATUSES.contains(status.getAsString())) {
      return false;
    }
    var s = status.getAsString();
    boolean success = obj.get("success").getAsBoolean();
    if (success != (s.equals("ok") || s.equals("partial"))) return false;
    if (s.equals("not_applicable") || s.equals("no_match")) {
      var reason = obj.get("reason");
      return reason != null && reason.isJsonPrimitive() && !reason.getAsString().isBlank();
    }
    return true;
  }

  static boolean limitsConsistent(JsonObject obj) {
    var limits = obj.get("limits");
    if (limits == null) return true;
    if (!limits.isJsonObject()) return false;
    for (var entry : limits.getAsJsonObject().entrySet()) {
      if (!entry.getValue().isJsonObject()) return false;
      var l = entry.getValue().getAsJsonObject();
      var list = obj.get(entry.getKey());
      if (list == null || !list.isJsonArray() || !l.has("total") || !l.has("returned")) {
        return false;
      }
      long total = l.get("total").getAsLong();
      int returned = l.get("returned").getAsInt();
      if (returned != list.getAsJsonArray().size() || total < returned) return false;
    }
    return true;
  }

  /** A top-level list of exactly {@code limit} items, with no {@code limits} entry for it. */
  static boolean hasUnreportedTruncation(JsonObject obj, int limit) {
    var limits = obj.has("limits") && obj.get("limits").isJsonObject()
        ? obj.getAsJsonObject("limits") : new JsonObject();
    for (var entry : obj.entrySet()) {
      if (BOOKKEEPING.contains(entry.getKey()) || entry.getKey().equals("limits")) continue;
      var v = entry.getValue();
      if (v.isJsonArray() && v.getAsJsonArray().size() == limit && !limits.has(entry.getKey())) {
        return true;
      }
    }
    return false;
  }

  /** Runs the checks that apply to every result regardless of the call. */
  static List<Check> check(JsonElement result) {
    var failed = new ArrayList<Check>();
    if (result == null || !result.isJsonObject()) {
      failed.add(Check.SHAPE);
      return failed;
    }
    var obj = result.getAsJsonObject();
    var success = obj.get("success");
    if (success == null || !success.isJsonPrimitive() || !success.getAsJsonPrimitive().isBoolean()) {
      failed.add(Check.SHAPE);
      return failed;
    }
    if (!success.getAsBoolean()) {
      // A failed call explains itself: "error" for a failure, "reason" for a tool that did not
      // apply or found nothing to analyze
      var status = obj.get("status");
      boolean explained = status != null && status.isJsonPrimitive()
          && (status.getAsString().equals("not_applicable")
              || status.getAsString().equals("no_match"));
      var message = obj.get(explained ? "reason" : "error");
      if (message == null || !message.isJsonPrimitive() || message.getAsString().isBlank()) {
        failed.add(Check.SHAPE);
      } else if (message.getAsString().startsWith("Internal error")) {
        failed.add(Check.INTERNAL_ERROR);
      }
    }
    if (containsNonFinite(obj) || reportsNonFinite(obj)) failed.add(Check.NON_FINITE);
    if (success.getAsBoolean() && isSilentEmpty(obj)) failed.add(Check.SILENT_EMPTY);
    return failed;
  }

  /** The result contract replaced NaN/Infinity with null and listed the fields. */
  static boolean reportsNonFinite(JsonObject obj) {
    var metadata = obj.get("_metadata");
    return metadata != null && metadata.isJsonObject()
        && metadata.getAsJsonObject().has("non_finite_fields");
  }

  static boolean containsNonFinite(JsonElement e) {
    if (e == null || e.isJsonNull()) return false;
    if (e.isJsonPrimitive()) {
      var p = e.getAsJsonPrimitive();
      if (!p.isNumber()) return false;
      double d = p.getAsDouble();
      return Double.isNaN(d) || Double.isInfinite(d);
    }
    if (e.isJsonArray()) {
      for (var item : e.getAsJsonArray()) if (containsNonFinite(item)) return true;
      return false;
    }
    for (var entry : e.getAsJsonObject().entrySet()) {
      if (containsNonFinite(entry.getValue())) return true;
    }
    return false;
  }

  /**
   * True when a successful result's content keys carry no information: nothing but nulls, zeros,
   * {@code false}, blank strings, and empty lists and objects — "found nothing" dressed as a
   * success. A tool that found nothing to analyze must say so with {@code status: no_match} or
   * {@code not_applicable}, not with an empty success (with or without a warning).
   */
  static boolean isSilentEmpty(JsonObject obj) {
    for (var entry : obj.entrySet()) {
      if (BOOKKEEPING.contains(entry.getKey())) continue;
      if (informative(entry.getValue())) return false;
    }
    return true;
  }

  static boolean informative(JsonElement e) {
    if (e == null || e.isJsonNull()) return false;
    if (e.isJsonPrimitive()) {
      var p = e.getAsJsonPrimitive();
      if (p.isBoolean()) return p.getAsBoolean();
      if (p.isNumber()) return p.getAsDouble() != 0.0;
      return !p.getAsString().isBlank();
    }
    if (e.isJsonArray()) {
      JsonArray a = e.getAsJsonArray();
      return !a.isEmpty();
    }
    for (var entry : e.getAsJsonObject().entrySet()) {
      if (informative(entry.getValue())) return true;
    }
    return false;
  }

  /** Removes fields that legitimately differ between two runs of the same call. */
  static JsonElement normalize(JsonElement e) {
    if (e == null || !e.isJsonObject()) return e;
    var copy = e.getAsJsonObject().deepCopy();
    copy.remove("_execution_time_ms");
    return copy;
  }
}
