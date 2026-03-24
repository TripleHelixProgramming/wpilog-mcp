package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.tba.TbaClient;
import org.triplehelix.wpilogmcp.tba.TbaConfig;

import java.util.List;
import java.util.stream.Collectors;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * Abstract base class for WPILOG MCP tools.
 *
 * <p>Provides common patterns and utilities to reduce boilerplate code across tool implementations:
 * <ul>
 *   <li>Template method pattern for consistent error handling</li>
 *   <li>Log acquisition with null checking and clear error messages</li>
 *   <li>Entry retrieval with "did you mean?" suggestions</li>
 *   <li>Time range filtering</li>
 *   <li>Numeric data extraction</li>
 *   <li>Response building with {@link ResponseBuilder}</li>
 *   <li>Dependency injection support (optional)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * static class MyTool extends ToolBase {
 *     {@literal @}Override
 *     public String name() { return "my_tool"; }
 *
 *     {@literal @}Override
 *     protected JsonElement executeInternal(JsonObject arguments) throws Exception {
 *         var path = getRequiredString(arguments, "path");
 *         var log = logManager.getOrLoad(path);
 *         var name = getRequiredString(arguments, "name");
 *         var values = requireEntry(log, name);
 *
 *         return success()
 *             .addProperty("count", values.size())
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * @since 0.4.0
 */
public abstract class ToolBase implements McpServer.Tool {

  // ===== DEPENDENCY INJECTION =====

  /** The injected LogManager instance (or from singleton if using no-arg constructor). */
  protected final LogManager logManager;

  /** The injected TbaClient instance (or from singleton if using no-arg constructor). */
  protected final TbaClient tbaClient;

  /** The injected TbaConfig instance (or from singleton if using no-arg constructor). */
  protected final TbaConfig tbaConfig;

  /** The injected LogDirectory instance (or from singleton if using no-arg constructor). */
  protected final LogDirectory logDirectory;

  /**
   * Creates a tool with injected dependencies.
   *
   * <p>This constructor enables dependency injection for testing with mocks or
   * alternative configurations.
   *
   * <p>Example with mocks:
   * <pre>{@code
   * var mockLogManager = mock(LogManager.class);
   * var deps = new ToolDependencies(mockLogManager, null, null, null);
   * var tool = new MyTool(deps);
   * }</pre>
   *
   * @param deps The dependency container
   * @since 0.4.0
   */
  protected ToolBase(ToolDependencies deps) {
    this.logManager = deps.logManager();
    this.tbaClient = deps.tbaClient();
    this.tbaConfig = deps.tbaConfig();
    this.logDirectory = deps.logDirectory();
  }

  /**
   * Creates a tool using singleton dependencies (backwards compatible).
   *
   * <p>This no-arg constructor maintains backwards compatibility with existing
   * tool implementations. It uses {@link ToolDependencies#fromSingletons()} to
   * populate dependencies.
   *
   * <p><strong>Note:</strong> New tools should prefer the dependency injection
   * constructor for better testability.
   */
  protected ToolBase() {
    this(ToolDependencies.fromSingletons());
  }

  /**
   * Gets the LogManager instance.
   *
   * <p>For backwards compatibility with existing code that calls getLogManager().
   * New code should access the {@link #logManager} field directly.
   *
   * @return The LogManager instance
   */
  protected LogManager getLogManager() {
    return logManager;
  }

  /**
   * Gets the TbaClient instance.
   *
   * <p>For backwards compatibility with existing code that calls getTbaClient().
   * New code should access the {@link #tbaClient} field directly.
   *
   * @return The TbaClient instance
   */
  protected TbaClient getTbaClient() {
    return tbaClient;
  }

  // ===== TEMPLATE METHOD PATTERN =====

  /**
   * Executes the tool with automatic error handling.
   *
   * <p>This method wraps {@link #executeInternal(JsonObject)} with exception handling.
   * {@link IllegalArgumentException} exceptions are automatically converted to error responses.
   *
   * <p>Subclasses should override {@link #executeInternal(JsonObject)} instead of this method.
   *
   * @param arguments The tool arguments from MCP
   * @return The tool result as JsonElement
   * @throws Exception if an unexpected error occurs
   */
  @Override
  public final JsonElement execute(JsonObject arguments) throws Exception {
    try {
      return executeInternal(arguments);
    } catch (IllegalArgumentException e) {
      // Parameter validation errors - return user-friendly error
      return errorResult(e.getMessage());
    } catch (Exception e) {
      // Unexpected errors - return error response instead of propagating
      // raw exceptions to the MCP layer
      var msg = e.getMessage();
      return errorResult("Internal error: " + (msg != null ? msg : e.getClass().getSimpleName()));
    }
  }

  /**
   * Subclasses implement this method to provide tool-specific logic.
   *
   * <p>Throw {@link IllegalArgumentException} for parameter validation errors.
   * These will be automatically converted to error responses.
   *
   * @param arguments The tool arguments from MCP
   * @return The tool result as JsonElement
   * @throws Exception if an error occurs
   */
  protected abstract JsonElement executeInternal(JsonObject arguments) throws Exception;

  // ===== LOG ACQUISITION HELPERS =====

  /**
   * Gets entry values or throws with helpful error message including suggestions.
   *
   * <p>If the entry is not found, searches for similar entry names (case-insensitive
   * contains match) and includes them in the error message as suggestions.
   *
   * @param log The parsed log
   * @param name The entry name to retrieve
   * @return The list of timestamped values for the entry
   * @throws IllegalArgumentException if entry not found
   */
  protected List<TimestampedValue> requireEntry(LogData log, String name)
      throws IllegalArgumentException {
    var values = log.values().get(name);
    if (values == null) {
      // Provide suggestions for similar entry names
      var suggestions = log.entries().keySet().stream()
          .filter(n -> n.toLowerCase().contains(name.toLowerCase()))
          .limit(5)
          .collect(Collectors.toList());

      var msg = "Entry not found: " + name;
      if (!suggestions.isEmpty()) {
        msg += ". Did you mean: " + String.join(", ", suggestions) + "?";
      }
      throw new IllegalArgumentException(msg);
    }
    return values;
  }

  // ===== TIME RANGE FILTERING HELPERS =====

  /**
   * Filters timestamped values by an optional time range.
   *
   * <p>If startTime is null, includes all values from the beginning.
   * If endTime is null, includes all values to the end.
   *
   * @param values The timestamped values to filter
   * @param startTime Optional start timestamp (inclusive), or null for no start limit
   * @param endTime Optional end timestamp (inclusive), or null for no end limit
   * @return The filtered list of timestamped values
   */
  protected List<TimestampedValue> filterTimeRange(
      List<TimestampedValue> values,
      Double startTime,
      Double endTime) {
    return values.stream()
        .filter(tv -> inTimeRange(tv.timestamp(), startTime, endTime))
        .collect(Collectors.toList());
  }

  /**
   * Checks if a timestamp is within an optional time range.
   *
   * <p>Returns true if:
   * <ul>
   *   <li>startTime is null OR timestamp &gt;= startTime</li>
   *   <li>AND endTime is null OR timestamp &lt;= endTime</li>
   * </ul>
   *
   * @param timestamp The timestamp to check
   * @param startTime Optional start time (inclusive), or null for no start limit
   * @param endTime Optional end time (inclusive), or null for no end limit
   * @return true if timestamp is in range
   */
  protected boolean inTimeRange(double timestamp, Double startTime, Double endTime) {
    if (startTime != null && timestamp < startTime) return false;
    if (endTime != null && timestamp > endTime) return false;
    return true;
  }

  // ===== DATA EXTRACTION HELPERS =====

  /**
   * Extracts numeric values from timestamped data with optional time filtering.
   *
   * <p>Filters by time range and includes only finite numeric values (NaN and Infinity
   * are excluded to prevent silent corruption of statistical calculations).
   *
   * @param values The timestamped values
   * @param startTime Optional start time (inclusive), or null
   * @param endTime Optional end time (inclusive), or null
   * @return Array of finite numeric values
   */
  protected double[] extractNumericData(
      List<TimestampedValue> values,
      Double startTime,
      Double endTime) {
    return values.stream()
        .filter(tv -> inTimeRange(tv.timestamp(), startTime, endTime))
        .filter(tv -> tv.value() instanceof Number)
        .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
        .filter(Double::isFinite)
        .toArray();
  }

  /**
   * Extracts numeric values from all timestamped data (no time filtering).
   *
   * @param values The timestamped values
   * @return Array of numeric values
   */
  protected double[] extractNumericData(List<TimestampedValue> values) {
    return extractNumericData(values, null, null);
  }

  // ===== ENTRY SEARCH HELPERS =====

  /**
   * Finds the first entry whose name contains the pattern (case-insensitive).
   *
   * <p>This is useful for finding entries with common prefixes or patterns
   * without requiring exact matches.
   *
   * @param log The parsed log
   * @param pattern The pattern to search for (case-insensitive)
   * @return The first matching entry name, or null if no match found
   */
  protected String findEntryByPattern(LogData log, String pattern) {
    var lowerPattern = pattern.toLowerCase();
    return log.entries().keySet().stream()
        .filter(name -> name.toLowerCase().contains(lowerPattern))
        .findFirst()
        .orElse(null);
  }

  /**
   * Finds all entries whose names contain the pattern (case-insensitive).
   *
   * @param log The parsed log
   * @param pattern The pattern to search for (case-insensitive)
   * @return List of matching entry names (may be empty)
   */
  protected List<String> findEntriesByPattern(LogData log, String pattern) {
    var lowerPattern = pattern.toLowerCase();
    return log.entries().keySet().stream()
        .filter(name -> name.toLowerCase().contains(lowerPattern))
        .collect(Collectors.toList());
  }

  // ===== RESPONSE BUILDING HELPERS =====

  /**
   * Creates a ResponseBuilder for building standardized success responses.
   *
   * <p>Convenience method equivalent to {@link ResponseBuilder#success()}.
   *
   * @return A new ResponseBuilder for success responses
   */
  protected ResponseBuilder success() {
    return ResponseBuilder.success();
  }

  /**
   * Creates a ResponseBuilder for building standardized error responses.
   *
   * <p>Convenience method equivalent to {@link ResponseBuilder#error(String)}.
   *
   * @param message The error message
   * @return A new ResponseBuilder for error responses
   */
  protected ResponseBuilder error(String message) {
    return ResponseBuilder.error(message);
  }
}
