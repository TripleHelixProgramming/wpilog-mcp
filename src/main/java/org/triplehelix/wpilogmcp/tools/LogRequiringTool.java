package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.triplehelix.wpilogmcp.log.LogData;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.getRequiredString;

/**
 * Base class for tools that operate on a specific log file.
 *
 * <p>Each tool call specifies which log to operate on via a required {@code path} parameter.
 * The server auto-loads the log on first reference and caches it for subsequent calls.
 * There is no need for clients to explicitly load or unload logs.
 *
 * <p>This class automatically:
 * <ul>
 *   <li>Injects a required {@code path} parameter into the tool schema</li>
 *   <li>Extracts the path from arguments and auto-loads the log via
 *       {@link org.triplehelix.wpilogmcp.log.LogManager#getOrLoad(String)}</li>
 *   <li>Passes the loaded log to {@link #executeWithLog(LogData, JsonObject)}</li>
 * </ul>
 *
 * <p>Subclasses define their tool-specific parameters by overriding {@link #toolSchema()},
 * and implement their logic in {@link #executeWithLog(LogData, JsonObject)}.
 *
 * <p>Example usage:
 * <pre>{@code
 * static class GetStatisticsTool extends LogRequiringTool {
 *     {@literal @}Override
 *     public String name() { return "get_statistics"; }
 *
 *     {@literal @}Override
 *     public String description() { return "Calculate statistics"; }
 *
 *     {@literal @}Override
 *     protected JsonObject toolSchema() {
 *         return new SchemaBuilder()
 *             .addProperty("name", "string", "Entry name", true)
 *             .build();
 *     }
 *
 *     {@literal @}Override
 *     protected JsonElement executeWithLog(LogData log, JsonObject arguments)
 *             throws Exception {
 *         var name = getRequiredString(arguments, "name");
 *         var values = requireEntry(log, name);
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * @since 0.4.0
 */
public abstract class LogRequiringTool extends ToolBase {

  /**
   * Returns the tool-specific input schema (without the {@code path} parameter).
   *
   * <p>The {@code path} parameter is automatically injected by {@link #inputSchema()}.
   * Subclasses should only define their own parameters here.
   *
   * @return The tool-specific JSON Schema object
   */
  protected abstract JsonObject toolSchema();

  /**
   * Returns the complete input schema with the {@code path} parameter injected.
   *
   * <p>Calls {@link #toolSchema()} to get tool-specific parameters, then adds
   * a required {@code path} string property. Subclasses must not override this
   * method — override {@link #toolSchema()} instead.
   */
  @Override
  public final JsonObject inputSchema() {
    var base = toolSchema();

    // Deep-copy to avoid modifying the original schema object
    var schema = base.deepCopy();

    // Inject "path" property
    var properties = schema.getAsJsonObject("properties");
    if (properties == null) {
      properties = new JsonObject();
      schema.add("properties", properties);
    }
    var pathProp = new JsonObject();
    pathProp.addProperty("type", "string");
    pathProp.addProperty("description",
        "Path to the log file (from list_available_logs)");
    properties.add("path", pathProp);

    // Add to required array
    var required = schema.has("required")
        ? schema.getAsJsonArray("required")
        : new JsonArray();
    required.add("path");
    schema.add("required", required);

    return schema;
  }

  /**
   * Extracts the {@code path} argument, auto-loads the log, and delegates
   * to {@link #executeWithLog(LogData, JsonObject)}.
   *
   * <p>Subclasses must not override this method.
   */
  @Override
  protected final JsonElement executeInternal(JsonObject arguments) throws Exception {
    var path = getRequiredString(arguments, "path");
    var log = logManager.getOrLoad(path);
    return executeWithLog(log, arguments);
  }

  /**
   * Executes the tool with the loaded log.
   *
   * <p>Subclasses implement this method to provide tool-specific logic.
   * The log parameter is guaranteed to be non-null.
   *
   * @param log The parsed log (never null)
   * @param arguments The tool arguments from MCP (includes {@code path})
   * @return The tool result as JsonElement
   * @throws Exception if an error occurs
   */
  protected abstract JsonElement executeWithLog(LogData log, JsonObject arguments)
      throws Exception;
}
