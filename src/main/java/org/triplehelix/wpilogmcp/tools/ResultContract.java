/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The result contract every tool result satisfies, enforced once in {@link ToolBase#execute} so
 * it holds for every tool regardless of how the tool built its JSON.
 *
 * <ul>
 *   <li>{@code status} is one of {@link Status}. {@code success} is {@code true} exactly when
 *       the status is {@code ok} or {@code partial}; a tool that could not apply
 *       ({@code not_applicable}) or found nothing to analyze ({@code no_match}) is not a success,
 *       and says why in {@code reason}.
 *   <li>No {@code NaN} or infinite number is ever emitted (they are not valid JSON). Each is
 *       replaced by {@code null}, the affected fields are named in a warning and in
 *       {@code _metadata.non_finite_fields}.
 *   <li>A list cut short by a limit is described in {@code limits}: for each list key,
 *       {@code total} (how many exist), {@code returned}, and the {@code limit} applied.
 * </ul>
 *
 * @since 0.9.0
 */
public final class ResultContract {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ResultContract.class);

  private ResultContract() {}

  /** Outcome of a tool call. */
  public enum Status {
    /** The tool applied and produced its full result. */
    OK,
    /** The tool applied but some sections could not be produced (see {@code skipped}). */
    PARTIAL,
    /** The tool does not apply to this log (e.g. no autonomous period, not a replay log). */
    NOT_APPLICABLE,
    /** The tool found no entries matching what it looks for. */
    NO_MATCH,
    /** The call failed (invalid arguments, missing entry, unreadable file). */
    ERROR;

    /** The wire form, e.g. {@code "not_applicable"}. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** Whether results with this status count as {@code success: true}. */
    public boolean isSuccess() {
      return this == OK || this == PARTIAL;
    }

    /** Parses a wire form; null or unknown values return {@code null}. */
    public static Status fromWire(String wire) {
      if (wire == null) return null;
      for (var s : values()) {
        if (s.wire().equals(wire)) return s;
      }
      return null;
    }
  }

  /**
   * Normalizes a tool result so it satisfies the contract. Returns a new object with
   * {@code success} and {@code status} first; other fields keep their order.
   *
   * @param result The raw tool result
   * @return The normalized result (non-objects are returned unchanged)
   */
  public static JsonElement enforce(JsonElement result) {
    if (result == null || !result.isJsonObject()) return result;
    var obj = result.getAsJsonObject();

    boolean success = obj.has("success") && obj.get("success").isJsonPrimitive()
        && obj.get("success").getAsJsonPrimitive().isBoolean() && obj.get("success").getAsBoolean();
    Status status = obj.has("status") && obj.get("status").isJsonPrimitive()
        ? Status.fromWire(obj.get("status").getAsString()) : null;
    if (status == null && obj.has("status")) {
      // "status" is reserved for the contract; a tool must not use it for its own values
      logger.warn("Tool result used the reserved 'status' key for '{}'; replaced", obj.get("status"));
    }
    if (status == null) {
      status = success ? Status.OK : Status.ERROR;
    }

    var out = new JsonObject();
    out.addProperty("success", status.isSuccess());
    out.addProperty("status", status.wire());
    for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
      if (e.getKey().equals("success") || e.getKey().equals("status")) continue;
      out.add(e.getKey(), e.getValue());
    }
    if ((status == Status.NOT_APPLICABLE || status == Status.NO_MATCH) && !out.has("reason")) {
      out.addProperty("reason", out.has("error") ? out.get("error").getAsString()
          : "The tool did not find the data it needs in this log.");
    }

    var nonFinite = new ArrayList<String>();
    sanitize(out, "", nonFinite);
    if (!nonFinite.isEmpty()) {
      var warnings = out.has("warnings") && out.get("warnings").isJsonArray()
          ? out.getAsJsonArray("warnings") : new JsonArray();
      warnings.add("Values that could not be computed (NaN or infinite) were replaced with null: "
          + String.join(", ", nonFinite.size() > 10 ? nonFinite.subList(0, 10) : nonFinite)
          + (nonFinite.size() > 10 ? " and " + (nonFinite.size() - 10) + " more" : "") + ".");
      out.add("warnings", warnings);
      var metadata = out.has("_metadata") && out.get("_metadata").isJsonObject()
          ? out.getAsJsonObject("_metadata") : new JsonObject();
      var fields = new JsonArray();
      nonFinite.forEach(fields::add);
      metadata.add("non_finite_fields", fields);
      out.add("_metadata", metadata);
    }
    return out;
  }

  /** Replaces non-finite numbers with null in place, recording their paths. */
  static void sanitize(JsonElement e, String path, List<String> found) {
    if (e == null || e.isJsonNull() || e.isJsonPrimitive()) return;
    if (e.isJsonArray()) {
      var array = e.getAsJsonArray();
      for (int i = 0; i < array.size(); i++) {
        var item = array.get(i);
        if (isNonFinite(item)) {
          array.set(i, JsonNull.INSTANCE);
          found.add(path + "[" + i + "]");
        } else {
          sanitize(item, path + "[" + i + "]", found);
        }
      }
      return;
    }
    var obj = e.getAsJsonObject();
    for (var entry : obj.entrySet()) {
      var childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
      if (isNonFinite(entry.getValue())) {
        entry.setValue(JsonNull.INSTANCE);
        found.add(childPath);
      } else {
        sanitize(entry.getValue(), childPath, found);
      }
    }
  }

  static boolean isNonFinite(JsonElement e) {
    if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
    double d = e.getAsDouble();
    return Double.isNaN(d) || Double.isInfinite(d);
  }

  /**
   * Adds a possibly truncated list to a hand-built result, and records it under {@code limits}.
   *
   * @param result The result object
   * @param key The list's key
   * @param items The items returned
   * @param total How many items exist in total (before the limit)
   * @param limit The limit applied
   */
  public static void addLimitedList(JsonObject result, String key, JsonArray items, long total,
      int limit) {
    result.add(key, items);
    var limits = result.has("limits") && result.get("limits").isJsonObject()
        ? result.getAsJsonObject("limits") : new JsonObject();
    var entry = new JsonObject();
    entry.addProperty("total", total);
    entry.addProperty("returned", items.size());
    entry.addProperty("limit", limit);
    limits.add(key, entry);
    result.add("limits", limits);
  }
}
