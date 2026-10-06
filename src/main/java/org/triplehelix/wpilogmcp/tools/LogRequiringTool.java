/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.SessionContext;

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
 *       {@link LogManager#acquire(String)}, retaining its mapping
 *       until the result and its input annotations are complete</li>
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

  /** Creates a tool using the singleton dependencies. */
  protected LogRequiringTool() {
    super();
  }

  /**
   * Creates a tool with injected dependencies, as {@link ToolBase#ToolBase(ToolDependencies)}
   * does: a test can give it a log manager of its own.
   *
   * @param deps The dependency container
   * @since 0.9.1
   */
  protected LogRequiringTool(ToolDependencies deps) {
    super(deps);
  }

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
    try (var use = logManager.acquire(path)) {
      var loaded = use.log();
      // The file as the log was read from it, taken now so that an eviction during the call does
      // not lose it; the call's result is trusted only if the file is still that file afterwards
      var before = use.snapshot();
      var log = new AccessTrackingLogData(loaded);
      JsonElement result;
      try {
        result = executeWithLog(log, arguments);
      } catch (InternalError e) {
        // A read of the memory-mapped file faulted: the file was truncated or rewritten in place
        // under the mapping. The attributes may or may not show it, so the fault itself counts
        return changedDuringCall(logManager.faultDuringCall(path, loaded, before, e.getMessage()));
      }
      var change = logManager.changeDuringCall(path, loaded, before);
      if (change != null) return changedDuringCall(change);
      if (result != null && result.isJsonObject()) {
        var object = result.getAsJsonObject();
        // Never compute silently from partly undecodable entries: say which ones and why
        log.annotate(object);
        // Nor from a log that was not read to its end
        ToolUtils.noteTruncation(object, log);
        // Every successful result says what it was computed from (rule R3)
        if (!object.has("success") || object.get("success").getAsBoolean()) {
          log.recordInputs(object);
        }
        if (object.has("inputs") && object.get("inputs").isJsonObject()) {
          log.recordSessionRange(object.getAsJsonObject("inputs"));
        }
        // A session that used this log before its file changed is told once that it was reloaded
        var reload = logManager.reloadNoticeFor(sessionKey(), path);
        if (reload != null) noteReload(object, reload);
      }
      return result;
    }
  }

  /**
   * The error for a result read while the file changed: it may hold old data, or mix old and
   * new bytes, so it is discarded; the log is already unloaded, and the next call loads the file
   * as it is now.
   */
  private static JsonElement changedDuringCall(String change) {
    return ToolUtils.errorResult("The log file changed on disk while this call was reading it ("
        + change + "). The result was discarded, because it may mix old and new data; the "
        + "server has reloaded the log, so call again.");
  }

  /**
   * The session this call belongs to: the HTTP session, or the one stdio client, which is one
   * session for the life of the process.
   */
  private static String sessionKey() {
    var session = SessionContext.current();
    return session == null ? "stdio" : session.getId();
  }

  /**
   * Says in a result, once per session, that its log was reloaded because the file changed:
   * {@code _metadata.log_reloaded} with when and what changed, and a warning, since results the
   * session holds from earlier calls came from the file as it was.
   */
  static void noteReload(JsonObject result, LogManager.Reload reload) {
    var metadata = result.has("_metadata") && result.get("_metadata").isJsonObject()
        ? result.getAsJsonObject("_metadata") : new JsonObject();
    var note = new JsonObject();
    note.addProperty("at", reload.at().toString());
    note.addProperty("change", reload.change());
    metadata.add("log_reloaded", note);
    result.add("_metadata", metadata);
    var warnings = result.has("warnings") && result.get("warnings").isJsonArray()
        ? result.getAsJsonArray("warnings") : new JsonArray();
    warnings.add("This log was reloaded from disk because its file changed (" + reload.change()
        + "). Results from earlier calls in this session came from the file as it was; repeat "
        + "any you rely on.");
    result.add("warnings", warnings);
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
