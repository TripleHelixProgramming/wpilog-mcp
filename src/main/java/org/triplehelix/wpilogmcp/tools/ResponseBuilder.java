/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Fluent API for building standardized MCP tool responses.
 *
 * <p>Provides consistent response format across all tools with:
 * <ul>
 *   <li>{@code success} field (true/false)</li>
 *   <li>{@code warnings} array (optional, omitted if empty)</li>
 *   <li>{@code _metadata} object (optional, for execution metadata)</li>
 *   <li>Data fields added directly to response object</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * return ResponseBuilder.success()
 *     .addProperty("count", 42)
 *     .addWarning("Data quality may be affected by sensor noise")
 *     .addMetadata("samples_used", 1000)
 *     .build();
 * }</pre>
 *
 * <p>Produces:
 * <pre>{@code
 * {
 *   "success": true,
 *   "warnings": ["Data quality may be affected by sensor noise"],
 *   "_metadata": {
 *     "samples_used": 1000
 *   },
 *   "count": 42
 * }
 * }</pre>
 *
 * @since 0.4.0
 */
public class ResponseBuilder {
  private static final Gson GSON = new Gson();

  private final JsonObject response;
  private final List<String> warnings;
  private JsonObject metadata;
  private ResultContract.Status status;

  private ResponseBuilder(boolean success) {
    this.response = new JsonObject();
    this.response.addProperty("success", success);
    this.warnings = new ArrayList<>();
    this.metadata = null;
  }

  /**
   * Creates a builder for a success response.
   *
   * @return A new ResponseBuilder with success=true
   */
  public static ResponseBuilder success() {
    return new ResponseBuilder(true);
  }

  /**
   * Creates a builder for an error response.
   *
   * @param message The error message
   * @return A new ResponseBuilder with success=false and error message
   */
  public static ResponseBuilder error(String message) {
    var builder = new ResponseBuilder(false);
    builder.response.addProperty("error", message);
    return builder;
  }

  /**
   * Creates a builder for a result that does not apply to this log (for example, autonomous
   * analysis on a log with no autonomous period). Not a success: the tool ran correctly, but there
   * was nothing for it to analyze, and {@code reason} says why.
   *
   * @param reason What made the tool inapplicable, in terms of the log's own data
   * @return A new ResponseBuilder with {@code status: not_applicable}
   * @since 0.9.0
   */
  public static ResponseBuilder notApplicable(String reason) {
    var builder = new ResponseBuilder(false);
    builder.status = ResultContract.Status.NOT_APPLICABLE;
    builder.response.addProperty("reason", reason);
    return builder;
  }

  /**
   * Creates a builder for a result where the tool found no entries of the kind it analyzes. Pair
   * it with {@link #lookedFor} (what was searched) and {@link #hint} (how to point the tool at the
   * right data), so the result cannot be mistaken for "no problem found".
   *
   * @param reason What was missing
   * @return A new ResponseBuilder with {@code status: no_match}
   * @since 0.9.0
   */
  public static ResponseBuilder noMatch(String reason) {
    var builder = new ResponseBuilder(false);
    builder.status = ResultContract.Status.NO_MATCH;
    builder.response.addProperty("reason", reason);
    return builder;
  }

  /**
   * Sets the result status explicitly (for example {@code PARTIAL} when some sections were
   * skipped). {@code success} is derived from it when the result is enforced.
   *
   * @param status The status
   * @return This builder for chaining
   * @since 0.9.0
   */
  public ResponseBuilder status(ResultContract.Status status) {
    this.status = status;
    return this;
  }

  /**
   * Records what the tool searched for (name patterns, types, schemas), for {@code no_match} and
   * {@code not_applicable} results.
   *
   * @param descriptions One description per rule searched
   * @return This builder for chaining
   * @since 0.9.0
   */
  public ResponseBuilder lookedFor(java.util.Collection<String> descriptions) {
    var array = new JsonArray();
    descriptions.forEach(array::add);
    response.add("looked_for", array);
    return this;
  }

  /**
   * Adds a hint telling the caller how to point the tool at the right data (usually a
   * parameter to pass).
   *
   * @param hint The hint
   * @return This builder for chaining
   * @since 0.9.0
   */
  public ResponseBuilder hint(String hint) {
    response.addProperty("hint", hint);
    return this;
  }

  /**
   * Records an input entry the result was computed from, under {@code inputs.entries}.
   *
   * @param role What the entry was used as (e.g. "voltage", "enabled")
   * @param entry The entry name (null is ignored)
   * @return This builder for chaining
   * @since 0.9.0
   */
  public ResponseBuilder addInput(String role, String entry) {
    if (entry == null) return this;
    inputs().getAsJsonObject("entries").addProperty(role, entry);
    return this;
  }

  /**
   * Records a numeric signal under {@code inputs}: its entry under {@code inputs.entries} and,
   * when it reads a field, the field path under {@code inputs.fields}, both by role.
   *
   * @return This builder for chaining
   * @since 0.9.0
   */
  ResponseBuilder addInputSignal(String role, NumericSignal signal) {
    addInput(role, signal.entry());
    if (!signal.path().isRoot()) {
      var inputs = inputs();
      if (!inputs.has("fields")) inputs.add("fields", new JsonObject());
      inputs.getAsJsonObject("fields").addProperty(role, signal.path().toString());
    }
    return this;
  }

  /**
   * Records the time window the result covers, under {@code inputs.window}. Either bound may be
   * null (unbounded).
   *
   * @return This builder for chaining
   * @since 0.9.0
   */
  public ResponseBuilder addInputWindow(Double start, Double end) {
    var window = new JsonObject();
    if (start != null) window.addProperty("start", start);
    if (end != null) window.addProperty("end", end);
    inputs().add("window", window);
    return this;
  }

  /**
   * Records the time a result covers: {@code inputs.scope} for a named scope or explicit windows,
   * {@code inputs.window} for plain start_time/end_time bounds, nothing for the whole log.
   *
   * @return This builder for chaining
   * @since 0.9.0
   */
  public ResponseBuilder addInputScope(TimeScope scope) {
    if (!scope.isAll()) {
      inputs().add("scope", scope.toJson());
    } else if (scope.requestedStart() != null || scope.requestedEnd() != null) {
      addInputWindow(scope.requestedStart(), scope.requestedEnd());
    }
    return this;
  }

  private JsonObject inputs() {
    if (!response.has("inputs")) {
      var inputs = new JsonObject();
      inputs.add("entries", new JsonObject());
      response.add("inputs", inputs);
    }
    return response.getAsJsonObject("inputs");
  }

  /**
   * Records a section of the result that could not be produced, and why. A result with skipped
   * sections has status {@code partial} unless a status was set explicitly.
   *
   * @param section The section's key in a full result
   * @param reason Why it was not produced
   * @return This builder for chaining
   * @since 0.9.0
   */
  public ResponseBuilder addSkipped(String section, String reason) {
    if (!response.has("skipped")) response.add("skipped", new JsonArray());
    var entry = new JsonObject();
    entry.addProperty("section", section);
    entry.addProperty("reason", reason);
    response.getAsJsonArray("skipped").add(entry);
    return this;
  }

  /**
   * Adds a list that may have been cut short by a limit, recording {@code total},
   * {@code returned}, and {@code limit} under {@code limits.<key>}.
   *
   * @param key The list's key
   * @param items The items returned
   * @param total How many items exist in total
   * @param limit The limit applied
   * @return This builder for chaining
   * @since 0.9.0
   */
  public ResponseBuilder addLimitedList(String key, JsonArray items, long total, int limit) {
    ResultContract.addLimitedList(response, key, items, total, limit);
    return this;
  }

  /**
   * Adds a property to the response.
   *
   * <p>The property is added directly to the response object (not nested).
   *
   * @param key The property key
   * @param value The property value (String, Number, Boolean, or null)
   * @return This builder for chaining
   */
  public ResponseBuilder addProperty(String key, Object value) {
    if (value == null) {
      response.add(key, null);
    } else if (value instanceof String s) {
      response.addProperty(key, s);
    } else if (value instanceof Number n) {
      response.addProperty(key, n);
    } else if (value instanceof Boolean b) {
      response.addProperty(key, b);
    } else if (value instanceof Character c) {
      response.addProperty(key, c);
    } else {
      // For complex objects, serialize via GSON
      response.add(key, GSON.toJsonTree(value));
    }
    return this;
  }

  /**
   * Adds a JsonElement property to the response.
   *
   * <p>Use this for complex nested structures (JsonObjects, JsonArrays).
   *
   * @param key The property key
   * @param value The JsonElement value
   * @return This builder for chaining
   */
  public ResponseBuilder addData(String key, JsonElement value) {
    response.add(key, value);
    return this;
  }

  /**
   * Adds a warning message to the warnings array.
   *
   * <p>Warnings are non-fatal issues that the user should be aware of,
   * such as data quality concerns, incomplete analysis, or edge cases.
   *
   * @param message The warning message
   * @return This builder for chaining
   */
  public ResponseBuilder addWarning(String message) {
    warnings.add(message);
    return this;
  }

  /**
   * Adds metadata about the tool execution.
   *
   * <p>Metadata is stored in a {@code _metadata} object and typically includes
   * information about the analysis like sample counts, data quality scores,
   * or algorithm parameters used.
   *
   * <p>Note: Execution time is automatically added by McpServer as
   * {@code _execution_time_ms}.
   *
   * @param key The metadata key
   * @param value The metadata value
   * @return This builder for chaining
   */
  public ResponseBuilder addMetadata(String key, Object value) {
    if (metadata == null) {
      metadata = new JsonObject();
    }
    if (value instanceof String s) {
      metadata.addProperty(key, s);
    } else if (value instanceof Number n) {
      metadata.addProperty(key, n);
    } else if (value instanceof Boolean b) {
      metadata.addProperty(key, b);
    } else if (value instanceof Character c) {
      metadata.addProperty(key, c);
    } else if (value instanceof JsonElement je) {
      metadata.add(key, je);
    } else {
      metadata.add(key, GSON.toJsonTree(value));
    }
    return this;
  }

  /**
   * Builds and returns the final JsonObject response.
   *
   * <p>The warnings array is only included if non-empty.
   * The metadata object is only included if any metadata was added.
   *
   * @return The complete response as a JsonObject
   */
  /**
   * Adds data quality metrics to the response.
   *
   * <p>Automatically adds a warning if the quality score is below 0.5.
   *
   * @param quality The data quality metrics
   * @return This builder for chaining
   * @since 0.5.0
   */
  public ResponseBuilder addDataQuality(DataQuality quality) {
    response.add("data_quality", quality.toJson());
    if (quality.qualityScore() < 0.5) {
      addWarning(lowQualityWarning(quality));
    }
    return this;
  }

  /**
   * The warning attached below a quality score of 0.5. It bounds statistics only: a logged
   * flag, a threshold crossing, or an error line in the same result is an observation and is
   * not made preliminary by sparse or irregular sampling.
   */
  static String lowQualityWarning(DataQuality quality) {
    return "Low data quality (score: " + String.format("%.2f", quality.qualityScore())
        + "): statistics in this result should be treated as preliminary; directly observed "
        + "events (a logged flag, a threshold crossing, an error line) are not affected.";
  }

  /**
   * Adds LLM analysis directives to the response.
   *
   * <p>These directives guide LLMs toward calibrated interpretation of results.
   *
   * @param directives The analysis directives
   * @return This builder for chaining
   * @since 0.5.0
   */
  public ResponseBuilder addDirectives(AnalysisDirectives directives) {
    response.add("server_analysis_directives", directives.toJson());
    return this;
  }

  public JsonObject build() {
    // Status: explicit, else partial when sections were skipped from a successful result
    var effective = status;
    if (effective == null && response.get("success").getAsBoolean() && response.has("skipped")) {
      effective = ResultContract.Status.PARTIAL;
    }
    if (effective != null) {
      response.addProperty("success", effective.isSuccess());
      response.addProperty("status", effective.wire());
    }

    // Add warnings array if any warnings were added
    if (!warnings.isEmpty()) {
      var warningsArray = new JsonArray();
      for (var warning : warnings) {
        warningsArray.add(warning);
      }
      response.add("warnings", warningsArray);
    }

    // Add metadata object if any metadata was added
    if (metadata != null && metadata.size() > 0) {
      response.add("_metadata", metadata);
    }

    return response;
  }

  /**
   * Convenience method to build and return as JsonElement.
   *
   * @return The complete response as a JsonElement
   */
  public JsonElement buildElement() {
    return build();
  }
}
