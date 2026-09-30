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
final class ConformanceChecks {

  private ConformanceChecks() {}

  /** A check that can fail for a tool call. */
  enum Check {
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
    /** The call did not finish in time. */
    TIMEOUT;

    String label() {
      return name().toLowerCase();
    }
  }

  /** Keys that describe a result rather than carry its content. */
  static final Set<String> BOOKKEEPING = Set.of("success", "status", "warnings", "_metadata",
      "data_quality", "server_analysis_directives", "_execution_time_ms", "inputs", "resolved",
      "skipped");

  /** Runs the single-result checks. */
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
      var error = obj.get("error");
      if (error == null || !error.isJsonPrimitive() || error.getAsString().isBlank()) {
        failed.add(Check.SHAPE);
      } else if (error.getAsString().startsWith("Internal error")) {
        failed.add(Check.INTERNAL_ERROR);
      }
    }
    if (containsNonFinite(obj)) failed.add(Check.NON_FINITE);
    if (success.getAsBoolean() && isSilentEmpty(obj)) failed.add(Check.SILENT_EMPTY);
    return failed;
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

  /** True when no content key carries information and there are no warnings. */
  static boolean isSilentEmpty(JsonObject obj) {
    var warnings = obj.get("warnings");
    if (warnings != null && warnings.isJsonArray() && !warnings.getAsJsonArray().isEmpty()) {
      return false;
    }
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
      return !p.isString() || !p.getAsString().isBlank();
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
