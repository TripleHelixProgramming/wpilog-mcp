package org.triplehelix.wpilogmcp.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

import java.util.List;

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
  private static final java.util.regex.Pattern YEAR_PATTERN = java.util.regex.Pattern.compile("(20\\d{2})");

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
      + "Correlations below |0.7| are weak and may be coincidental.";

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
   * Estimates the FRC season year from a log's file path.
   * WPILib log filenames typically contain a date (e.g., "FRC_20260321_123456.wpilog").
   * Falls back to the current system clock year if no date can be extracted.
   *
   * @param log The parsed log
   * @return The estimated season year
   */
  public static int estimateSeasonYear(org.triplehelix.wpilogmcp.log.ParsedLog log) {
    if (log.path() != null) {
      var matcher = YEAR_PATTERN.matcher(log.path());
      if (matcher.find()) {
        int year = Integer.parseInt(matcher.group(1));
        if (year >= 2020 && year <= 2099) {
          return year;
        }
      }
    }
    return java.time.Year.now().getValue();
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
   * Checks if the robot is enabled at a given timestamp using Zero-Order Hold.
   *
   * <p>The FMS sets mode flags (e.g., Autonomous) before the robot is actually enabled.
   * Match phases should only start when the robot is both in the correct mode AND enabled.
   *
   * @param enabledValues The timestamped Enabled boolean values (may be null)
   * @param timestamp The timestamp to check
   * @return true if the robot is enabled at that timestamp, or true if no enabled data exists (permissive fallback)
   */
  public static boolean isEnabledAt(List<TimestampedValue> enabledValues, double timestamp) {
    if (enabledValues == null || enabledValues.isEmpty()) {
      return true; // No enabled data — fall back to permissive behavior
    }
    var value = getValueAtTimeZoh(enabledValues, timestamp);
    return Boolean.TRUE.equals(value);
  }

  /**
   * Finds a DriverStation entry name in the log matching the given keyword.
   *
   * @param log The parsed log
   * @param keyword The keyword to match (e.g., "enabled", "autonomous")
   * @return The entry name, or null if not found
   */
  public static String findDsEntry(ParsedLog log, String keyword) {
    for (var entryName : log.entries().keySet()) {
      var lower = entryName.toLowerCase();
      if (lower.contains("driverstation") && lower.contains(keyword)) {
        // Exclude "command" entries for auto detection
        if (keyword.contains("auto") && lower.contains("command")) continue;
        return entryName;
      }
    }
    return null;
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
