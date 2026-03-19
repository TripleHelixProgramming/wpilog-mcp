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

  /** JSON serializer with null serialization (important for optional fields). */
  public static final Gson GSON = new GsonBuilder().serializeNulls().create();

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
   * Checks if a WPILOG type is numeric.
   *
   * @param type The type string
   * @return true if the type is double, float, or int64
   */
  public static boolean isNumericType(String type) {
    return "double".equals(type) || "float".equals(type) || "int64".equals(type);
  }

  /**
   * Gets the active log or returns an error result.
   *
   * @return The active log, or null if none is loaded
   */
  public static ParsedLog getActiveLogOrNull() {
    return LOG_MANAGER.getActiveLog();
  }

  /**
   * Checks if a log is loaded and returns an error result if not.
   *
   * @return Error result if no log is loaded, null otherwise
   */
  public static JsonObject checkLogLoaded() {
    if (LOG_MANAGER.getActiveLog() == null) {
      return errorResult("No log file is currently loaded. Use load_log first.");
    }
    return null;
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

    var result = (Object) null;
    for (var tv : values) {
      if (tv.timestamp() > targetTimestamp) {
        break;
      }
      result = tv.value();
    }
    return result;
  }

  /**
   * Gets value at a specific timestamp using linear interpolation.
   * Only works for numeric values; falls back to ZOH for non-numeric.
   *
   * @param values The timestamped values (must be sorted by timestamp)
   * @param targetTimestamp The timestamp to look up
   * @return The interpolated value at that timestamp
   */
  public static Double getValueAtTimeLinear(List<TimestampedValue> values, double targetTimestamp) {
    if (values == null || values.isEmpty()) {
      return null;
    }

    // Find the two surrounding values
    var before = (TimestampedValue) null;
    var after = (TimestampedValue) null;

    for (int i = 0; i < values.size(); i++) {
      var tv = values.get(i);
      if (tv.timestamp() <= targetTimestamp) {
        before = tv;
      } else {
        after = tv;
        break;
      }
    }

    // If we have an exact match or only before, return it
    if (before != null && (after == null || before.timestamp() == targetTimestamp)) {
      return toDouble(before.value());
    }

    // If we only have after, return it
    if (before == null && after != null) {
      return toDouble(after.value());
    }

    // If we have both, interpolate
    if (before != null && after != null) {
      var v1 = toDouble(before.value());
      var v2 = toDouble(after.value());
      if (v1 != null && v2 != null) {
        double t1 = before.timestamp();
        double t2 = after.timestamp();
        double fraction = (targetTimestamp - t1) / (t2 - t1);
        return v1 + (v2 - v1) * fraction;
      }
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
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    if (value instanceof Boolean) {
      return ((Boolean) value) ? 1.0 : 0.0;
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

  /**
   * Calculates RMSE between two aligned time series using ZOH (legacy method).
   *
   * @param series1 The first time series
   * @param series2 The second time series
   * @return The RMSE, or NaN if calculation not possible
   */
  public static double calculateRmseZoh(
      List<TimestampedValue> series1, List<TimestampedValue> series2) {
    if (series1 == null || series2 == null || series1.isEmpty() || series2.isEmpty()) {
      return Double.NaN;
    }

    var reference = series1.size() >= series2.size() ? series1 : series2;
    var other = series1.size() >= series2.size() ? series2 : series1;

    double sumSquaredError = 0.0;
    int count = 0;

    for (var tv : reference) {
      var refValue = toDouble(tv.value());
      var otherObj = getValueAtTimeZoh(other, tv.timestamp());
      var otherValue = toDouble(otherObj);

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
}
