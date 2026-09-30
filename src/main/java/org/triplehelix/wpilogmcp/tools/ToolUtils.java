/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

import java.util.List;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Shared utilities for all WPILOG tools.
 *
 * <p>This class provides common functionality used across all tool implementations:
 * <ul>
 *   <li>JSON serialization via {@link #GSON}</li>
 *   <li>Shared {@link LogManager} instance</li>
 *   <li>Helper methods for creating responses</li>
 *   <li>Signal alignment utilities (ZOH and linear interpolation)</li>
 * </ul>
 */
public final class ToolUtils {

  /** Pre-compiled pattern for extracting 4-digit year from log file paths. */

  /** JSON serializer with null serialization (important for optional fields). */
  public static final Gson GSON = new GsonBuilder().serializeNulls().create();

  // ==================== LLM INTERPRETATION GUIDANCE (§6.1) ====================
  // Appended to tool descriptions to nudge LLMs toward calibrated reasoning.

  /** Universal guidance appended to all analytical tools. */
  public static final String GUIDANCE_UNIVERSAL =
      "\n\nINTERPRETATION GUIDANCE: Results are raw data, not conclusions. "
      + "Express findings as possibilities, not certainties. "
      + "Single-match data cannot establish patterns—recommend cross-match comparison. "
      + "Consider alternative explanations before attributing causation.";

  /** Additional guidance for statistical tools. */
  public static final String GUIDANCE_STATISTICAL =
      " Sample sizes below 100 have high uncertainty. "
      + "Correlations below |0.7| are moderate to weak — interpret cautiously, "
      + "especially with small samples. Consider whether a physical relationship is expected.";

  /** Additional guidance for power/battery tools. */
  public static final String GUIDANCE_POWER =
      " Voltage drops may indicate power issues, aggressive driving, worn battery, "
      + "or loose connections. Single brownout events are not necessarily concerning—"
      + "look for patterns across matches.";

  /** Additional guidance for mechanism/regression tools. */
  public static final String GUIDANCE_MECHANISM =
      " Regression estimates depend on data quality and model assumptions. "
      + "Physical parameters outside typical ranges (negative inertia, negative damping) "
      + "indicate model or data issues, not actual physics.";

  /** Additional guidance for FRC match analysis tools. */
  public static final String GUIDANCE_MATCH_ANALYSIS =
      " Match conditions vary (battery age, field surface, alliance partners). "
      + "A single match is one sample—do not generalize without cross-match data.";

  /** Shared LogManager instance for all tools. */
  private static final LogManager LOG_MANAGER = LogManager.getInstance();

  private ToolUtils() {
    // Utility class - no instantiation
  }

  /**
   * Gets the shared LogManager instance.
   *
   * @return The LogManager singleton
   */
  public static LogManager getLogManager() {
    return LOG_MANAGER;
  }

  /**
   * Creates an error result JSON object.
   *
   * @param message The error message
   * @return A JSON object with success=false and the error message
   */
  public static JsonObject errorResult(String message) {
    var result = new JsonObject();
    result.addProperty("success", false);
    result.addProperty("error", message);
    return result;
  }

  /**
   * Creates a success result JSON object.
   *
   * @return A JSON object with success=true
   */
  public static JsonObject successResult() {
    var result = new JsonObject();
    result.addProperty("success", true);
    return result;
  }

  /**
   * Creates a ResponseBuilder for building standardized success responses.
   *
   * <p>This is the preferred way to create responses in new tools. Use the fluent API
   * to add properties, warnings, and metadata.
   *
   * <p>Example:
   * <pre>{@code
   * return successResponse()
   *     .addProperty("count", 42)
   *     .addWarning("Data quality may be affected")
   *     .addMetadata("samples_used", 1000)
   *     .build();
   * }</pre>
   *
   * @return A new ResponseBuilder for success responses
   * @since 0.4.0
   */
  public static ResponseBuilder successResponse() {
    return ResponseBuilder.success();
  }

  /**
   * Creates a ResponseBuilder for building standardized error responses.
   *
   * <p>This is the preferred way to create error responses in new tools.
   *
   * <p>Example:
   * <pre>{@code
   * return errorResponse("Entry not found")
   *     .addProperty("attempted_name", name)
   *     .build();
   * }</pre>
   *
   * @param message The error message
   * @return A new ResponseBuilder for error responses
   * @since 0.4.0
   */
  public static ResponseBuilder errorResponse(String message) {
    return ResponseBuilder.error(message);
  }

  /**
   * Checks if a WPILOG type is numeric.
   *
   * @param type The type string
   * @return true if the type is double, float, or int64
   */
  public static boolean isNumericType(String type) {
    return "double".equals(type) || "float".equals(type) || "int64".equals(type);
  }


  // ==================== MATCH PHASE DETECTION UTILITIES ====================

  /**
   * Returns true if a lower-cased entry name is a DriverStation state entry.
   *
   * <p>Two naming conventions are recognized: AdvantageKit-style names containing
   * {@code driverstation} (e.g. {@code /DriverStation/Enabled}) and WPILib DataLogManager names
   * with the {@code DS:} prefix (e.g. {@code DS:enabled}, {@code DS:autonomous}).
   *
   * @param lowerName The entry name, already lower-cased
   * @return true if the entry belongs to the DriverStation state family
   */
  public static boolean isDsEntry(String lowerName) {
    return lowerName.contains("driverstation") || lowerName.startsWith("ds:");
  }

  // ==================== POWER ENTRY SELECTION ====================

  /** Name fragments that mark a voltage entry as a rail, regulator, or motor output, not the battery. */
  private static final List<String> NON_BATTERY_VOLTAGE_HINTS =
      List.of("rail", "3v3", "5v", "6v", "brownoutvoltage", "setpoint", "applied", "output", "motor");

  /**
   * Ranks a lower-cased voltage entry name by how likely it is to carry the battery voltage:
   * 0 = battery voltage, 1 = another battery entry, 2 = input/bus voltage, 3 = any other voltage,
   * 4 = rail, regulator, or motor output.
   *
   * @param lowerName The entry name, already lower-cased
   * @return The rank (lower is better)
   */
  public static int voltageEntryRank(String lowerName) {
    if (lowerName.contains("batteryvoltage") || lowerName.contains("battery_voltage")) return 0;
    if (lowerName.contains("battery")) return 1;
    if (lowerName.contains("inputvoltage") || lowerName.contains("input_voltage")
        || lowerName.contains("busvoltage") || lowerName.contains("bus_voltage")) return 2;
    // Rail/regulator/motor hints are judged on the last two path segments only, so an
    // AdvantageKit "/RealOutputs/PDH/Voltage" is not demoted by the "output" in "RealOutputs".
    int cut = lowerName.lastIndexOf('/');
    if (cut > 0) cut = lowerName.lastIndexOf('/', cut - 1);
    var tail = cut >= 0 ? lowerName.substring(cut + 1) : lowerName;
    if (NON_BATTERY_VOLTAGE_HINTS.stream().anyMatch(tail::contains)) return 4;
    return 3;
  }

  /**
   * Returns true if the list contains at least one finite numeric sample.
   *
   * @param values The timestamped values (may be null)
   * @return true if any value is a finite Number
   */
  public static boolean hasFiniteNumericSample(List<TimestampedValue> values) {
    if (values == null) return false;
    for (var tv : values) {
      if (tv.value() instanceof Number n && Double.isFinite(n.doubleValue())) return true;
    }
    return false;
  }

  // ==================== CONSOLE TEXT CLASSIFICATION ====================

  /** Result of classifying a text sample: {@code "ERROR"} or {@code "WARNING"}, and the line that matched. */
  public record ClassifiedText(String type, String message) {}

  /** Maximum characters of a message line returned to the model; longer lines get "...". */
  public static final int MESSAGE_LINE_LIMIT = 200;

  /**
   * Classifies a (possibly multi-line) string sample. A sample is an ERROR if any line contains
   * "error", "exception", or "fault" ("default" does not count), otherwise a WARNING if any line
   * contains "warning", "overrun", or "watchdog"; errors dominate regardless of line order. The
   * returned message is the first matching line of the winning kind, stripped but not truncated
   * (callers truncate for display with {@link #truncate}). Shared by {@code get_ds_timeline}
   * (counts and summary) and {@code search_strings} (level filter) so the two always agree.
   *
   * @param message The sample text
   * @return The classification, or null if neither
   */
  public static ClassifiedText classifyText(String message) {
    String firstWarning = null;
    for (var line : message.split("\\R")) {
      var lower = line.toLowerCase(java.util.Locale.ROOT);
      if (lower.contains("error") || lower.contains("exception")
          || lower.replace("default", "").contains("fault")) {
        return new ClassifiedText("ERROR", line.strip());
      }
      if (firstWarning == null && (lower.contains("warning") || lower.contains("overrun")
          || lower.contains("watchdog"))) {
        firstWarning = line.strip();
      }
    }
    return firstWarning == null ? null : new ClassifiedText("WARNING", firstWarning);
  }

  /**
   * Truncates text for display, appending "..." when cut.
   *
   * @param text The text
   * @param maxChars Maximum characters to keep
   * @return The text, or its first {@code maxChars} characters followed by "..."
   */
  public static String truncate(String text, int maxChars) {
    return text.length() > maxChars ? text.substring(0, maxChars) + "..." : text;
  }

  /**
   * Normalizes a message for grouping: runs of digits (with optional decimal part) become
   * {@code #} and whitespace collapses, so "Loop time of 0.023s overrun" and "... 0.031s ..."
   * are the same message.
   *
   * @param message The message text
   * @return The normalized pattern
   */
  public static String normalizeMessage(String message) {
    return message.replaceAll("\\d+(?:\\.\\d+)?", "#").replaceAll("\\s+", " ").strip();
  }

  // ==================== PERCENTILE UTILITY ====================

  /**
   * NIST Type 7 percentile with linear interpolation (same as R default and numpy method='linear').
   *
   * @param sortedData Array of sorted numeric values
   * @param p Percentile (0.0 to 1.0, e.g., 0.25 for Q1, 0.75 for Q3)
   * @return The interpolated percentile value
   */
  public static double percentile(double[] sortedData, double p) {
    if (p < 0.0 || p > 1.0) {
      throw new IllegalArgumentException("Percentile must be in [0.0, 1.0], got " + p);
    }
    if (sortedData.length == 0) return 0.0;
    if (sortedData.length == 1) return sortedData[0];
    double index = p * (sortedData.length - 1);
    int lower = (int) Math.floor(index);
    int upper = Math.min((int) Math.ceil(index), sortedData.length - 1);
    if (lower == upper) return sortedData[lower];
    double weight = index - lower;
    return sortedData[lower] * (1 - weight) + sortedData[upper] * weight;
  }

  // ==================== SIGNAL ALIGNMENT UTILITIES ====================

  /**
   * Gets value at a specific timestamp using Zero-Order Hold (ZOH).
   * Returns the most recent value at or before the target timestamp.
   *
   * @param values The timestamped values (must be sorted by timestamp)
   * @param targetTimestamp The timestamp to look up
   * @return The value at that timestamp, or null if no value exists at or before
   */
  public static Object getValueAtTimeZoh(List<TimestampedValue> values, double targetTimestamp) {
    if (values == null || values.isEmpty()) {
      return null;
    }

    // Binary search for the last value at or before targetTimestamp (O(log n)).
    int lo = 0, hi = values.size() - 1;
    if (values.get(0).timestamp() > targetTimestamp) {
      return null; // All values are after the target
    }
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1; // upper-mid to find last <= target
      if (values.get(mid).timestamp() <= targetTimestamp) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    return values.get(lo).value();
  }

  /**
   * Gets value at a specific timestamp using linear interpolation.
   * Only works for numeric values. Returns null if the target timestamp is
   * outside the time range of the series (no extrapolation).
   *
   * <p>Uses binary search for O(log n) lookup instead of O(n) linear scan.
   *
   * @param values The timestamped values (must be sorted by timestamp)
   * @param targetTimestamp The timestamp to look up
   * @return The interpolated value at that timestamp, or null if out of bounds
   */
  public static Double getValueAtTimeLinear(List<TimestampedValue> values, double targetTimestamp) {
    if (values == null || values.isEmpty()) {
      return null;
    }

    // Reject timestamps outside the series range (no extrapolation)
    double firstTime = values.get(0).timestamp();
    double lastTime = values.get(values.size() - 1).timestamp();
    if (targetTimestamp < firstTime || targetTimestamp > lastTime) {
      return null;
    }

    // Binary search for the insertion point
    int lo = 0, hi = values.size() - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1; // upper-mid to find last <= target
      if (values.get(mid).timestamp() <= targetTimestamp) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }

    var before = values.get(lo);

    // Exact match or at the last timestamp
    if (before.timestamp() == targetTimestamp || lo == values.size() - 1) {
      return toDouble(before.value());
    }

    // Interpolate between before and after
    var after = values.get(lo + 1);
    var v1 = toDouble(before.value());
    var v2 = toDouble(after.value());
    if (v1 != null && v2 != null) {
      double t1 = before.timestamp();
      double t2 = after.timestamp();
      if (t2 == t1) return v1; // Guard against zero dt
      double fraction = (targetTimestamp - t1) / (t2 - t1);
      return v1 + (v2 - v1) * fraction;
    }

    return null;
  }

  /**
   * Converts a value to Double if possible.
   *
   * @param value The value to convert
   * @return The Double value, or null if not convertible
   */
  public static Double toDouble(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value instanceof Boolean b) {
      return b ? 1.0 : 0.0;
    }
    return null;
  }

  /**
   * Calculates RMSE between two aligned time series using linear interpolation.
   *
   * @param series1 The first time series (timestamped values)
   * @param series2 The second time series (timestamped values)
   * @return The RMSE, or NaN if calculation not possible
   */
  public static double calculateRmseLinear(
      List<TimestampedValue> series1, List<TimestampedValue> series2) {
    if (series1 == null || series2 == null || series1.isEmpty() || series2.isEmpty()) {
      return Double.NaN;
    }

    // Use the timestamps from the denser series
    var reference = series1.size() >= series2.size() ? series1 : series2;
    var other = series1.size() >= series2.size() ? series2 : series1;

    double sumSquaredError = 0.0;
    int count = 0;

    for (var tv : reference) {
      var refValue = toDouble(tv.value());
      var otherValue = getValueAtTimeLinear(other, tv.timestamp());

      if (refValue != null && otherValue != null) {
        double error = refValue - otherValue;
        sumSquaredError += error * error;
        count++;
      }
    }

    if (count == 0) {
      return Double.NaN;
    }

    return Math.sqrt(sumSquaredError / count);
  }

  // ==================== ARGUMENT EXTRACTION UTILITIES ====================

  /**
   * Gets an optional Double parameter from JSON arguments.
   *
   * @param args The JSON arguments object
   * @param key The parameter key
   * @return The Double value, or null if not present or null
   */
  public static Double getOptDouble(com.google.gson.JsonObject args, String key) {
    return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsDouble() : null;
  }

  /**
   * Gets an optional Double parameter from JSON arguments with a default value.
   *
   * @param args The JSON arguments object
   * @param key The parameter key
   * @param defaultValue The default value if not present
   * @return The Double value, or the default if not present or null
   */
  public static double getOptDouble(com.google.gson.JsonObject args, String key, double defaultValue) {
    return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsDouble() : defaultValue;
  }

  /**
   * Gets an optional Integer parameter from JSON arguments with a default value.
   *
   * @param args The JSON arguments object
   * @param key The parameter key
   * @param defaultValue The default value if not present
   * @return The int value, or the default if not present or null
   */
  public static int getOptInt(com.google.gson.JsonObject args, String key, int defaultValue) {
    return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsInt() : defaultValue;
  }

  /**
   * Gets an optional boolean parameter; a missing or null value means false.
   *
   * @param args The tool arguments
   * @param key The parameter name
   * @return The value, or false if absent
   */
  public static boolean getOptBoolean(com.google.gson.JsonObject args, String key) {
    return args.has(key) && !args.get(key).isJsonNull() && args.get(key).getAsBoolean();
  }

  /**
   * Gets an optional String parameter from JSON arguments with a default value.
   *
   * @param args The JSON arguments object
   * @param key The parameter key
   * @param defaultValue The default value if not present
   * @return The String value, or the default if not present or null
   */
  public static String getOptString(com.google.gson.JsonObject args, String key, String defaultValue) {
    return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : defaultValue;
  }

  /**
   * Gets a required String parameter from JSON arguments.
   *
   * @param args The JSON arguments object
   * @param key The parameter key
   * @return The String value
   * @throws IllegalArgumentException if the parameter is missing or null
   */
  public static String getRequiredString(com.google.gson.JsonObject args, String key) {
    if (!args.has(key) || args.get(key).isJsonNull()) {
      throw new IllegalArgumentException("Missing required parameter: " + key);
    }
    return args.get(key).getAsString();
  }

  /**
   * Validates that a numeric parameter is within a specified range.
   *
   * @param value The value to validate
   * @param min The minimum allowed value (inclusive)
   * @param max The maximum allowed value (inclusive)
   * @param paramName The parameter name for error messages
   * @return The validated value
   * @throws IllegalArgumentException if the value is out of range
   */
  public static int validateRange(int value, int min, int max, String paramName) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          String.format("%s must be between %d and %d, got %d", paramName, min, max, value));
    }
    return value;
  }

  /**
   * Validates that a numeric parameter is positive.
   *
   * @param value The value to validate
   * @param paramName The parameter name for error messages
   * @return The validated value
   * @throws IllegalArgumentException if the value is not positive
   */
  public static int validatePositive(int value, String paramName) {
    if (value <= 0) {
      throw new IllegalArgumentException(paramName + " must be positive, got " + value);
    }
    return value;
  }

  /**
   * Validates that a numeric parameter is non-negative.
   *
   * @param value The value to validate
   * @param paramName The parameter name for error messages
   * @return The validated value
   * @throws IllegalArgumentException if the value is negative
   */
  public static int validateNonNegative(int value, String paramName) {
    if (value < 0) {
      throw new IllegalArgumentException(paramName + " must be non-negative, got " + value);
    }
    return value;
  }

  // ==================== DATA QUALITY HELPERS ====================

  /**
   * Appends data quality and analysis directives to a raw JsonObject response.
   *
   * <p>Use this for tools that build responses manually (not via ResponseBuilder).
   * Merges with any existing warnings array.
   *
   * @param result The response JsonObject to augment
   * @param quality The data quality metrics
   * @param directives The analysis directives
   */
  public static void appendQualityToResult(
      com.google.gson.JsonObject result,
      DataQuality quality,
      AnalysisDirectives directives) {
    result.add("data_quality", quality.toJson());
    result.add("server_analysis_directives", directives.toJson());

    if (quality.qualityScore() < 0.5) {
      String warning = "Low data quality (score: "
          + String.format("%.2f", quality.qualityScore())
          + "). Results should be treated as preliminary.";

      // Merge with existing warnings
      com.google.gson.JsonArray warnings;
      if (result.has("warnings") && result.get("warnings").isJsonArray()) {
        warnings = result.getAsJsonArray("warnings");
      } else {
        warnings = new com.google.gson.JsonArray();
      }
      warnings.add(warning);
      result.add("warnings", warnings);
    }
  }
}
