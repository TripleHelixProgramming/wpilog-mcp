package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.triplehelix.wpilogmcp.log.ParsedLog;

/**
 * Base class for tools that require an active log to be loaded.
 *
 * <p>This specialized base class automatically checks for an active log and provides
 * it to the {@link #executeWithLog(ParsedLog, JsonObject)} method. This eliminates
 * the repetitive pattern of checking for a loaded log in every tool.
 *
 * <p>Approximately 90% of tools require an active log, making this the most common
 * base class for tool implementations.
 *
 * <p>Example usage:
 * <pre>{@code
 * static class GetStatisticsTool extends LogRequiringTool {
 *     {@literal @}Override
 *     public String name() { return "get_statistics"; }
 *
 *     {@literal @}Override
 *     public String description() {
 *         return "Calculate statistics for a numeric entry";
 *     }
 *
 *     {@literal @}Override
 *     public JsonObject inputSchema() {
 *         return new SchemaBuilder()
 *             .addProperty("name", "string", "Entry name", true)
 *             .build();
 *     }
 *
 *     {@literal @}Override
 *     protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments)
 *             throws Exception {
 *         var name = getRequiredString(arguments, "name");
 *         var values = requireEntry(log, name);
 *         var data = extractNumericData(values);
 *
 *         // Calculate statistics...
 *         return success()
 *             .addProperty("count", data.length)
 *             .addProperty("mean", mean)
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * <p><strong>When NOT to use this class:</strong>
 * <ul>
 *   <li>Tools that don't require a log (e.g., {@code list_available_logs},
 *       {@code load_log}, {@code health_check})</li>
 *   <li>Tools that optionally use a log but don't require it</li>
 * </ul>
 * For these cases, extend {@link ToolBase} directly and call {@code getLogManager().getActiveLog()}
 * explicitly.
 *
 * @since 0.4.0
 */
public abstract class LogRequiringTool extends ToolBase {

  /**
   * Executes the tool with automatic log checking.
   *
   * <p>This method automatically calls {@link #requireActiveLog()} and passes the
   * result to {@link #executeWithLog(ParsedLog, JsonObject)}. If no log is loaded,
   * an error response is automatically returned.
   *
   * <p><strong>Do not override this method.</strong> Override {@link #executeWithLog(ParsedLog, JsonObject)}
   * instead.
   *
   * @param arguments The tool arguments from MCP
   * @return The tool result as JsonElement
   * @throws Exception if an error occurs
   */
  @Override
  protected final JsonElement executeInternal(JsonObject arguments) throws Exception {
    var log = requireActiveLog();
    return executeWithLog(log, arguments);
  }

  /**
   * Executes the tool with a guaranteed non-null active log.
   *
   * <p>Subclasses implement this method to provide tool-specific logic.
   * The log parameter is guaranteed to be non-null.
   *
   * <p>Throw {@link IllegalArgumentException} for parameter validation errors.
   * These will be automatically converted to error responses.
   *
   * @param log The active parsed log (guaranteed non-null)
   * @param arguments The tool arguments from MCP
   * @return The tool result as JsonElement
   * @throws Exception if an error occurs
   */
  protected abstract JsonElement executeWithLog(ParsedLog log, JsonObject arguments)
      throws Exception;
}
