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
    } else if (value instanceof String) {
      response.addProperty(key, (String) value);
    } else if (value instanceof Number) {
      response.addProperty(key, (Number) value);
    } else if (value instanceof Boolean) {
      response.addProperty(key, (Boolean) value);
    } else if (value instanceof Character) {
      response.addProperty(key, (Character) value);
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
    if (value instanceof String) {
      metadata.addProperty(key, (String) value);
    } else if (value instanceof Number) {
      metadata.addProperty(key, (Number) value);
    } else if (value instanceof Boolean) {
      metadata.addProperty(key, (Boolean) value);
    } else if (value instanceof Character) {
      metadata.addProperty(key, (Character) value);
    } else if (value instanceof JsonElement) {
      metadata.add(key, (JsonElement) value);
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
      addWarning("Low data quality (score: " + String.format("%.2f", quality.qualityScore())
          + "). Results should be treated as preliminary.");
    }
    return this;
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
