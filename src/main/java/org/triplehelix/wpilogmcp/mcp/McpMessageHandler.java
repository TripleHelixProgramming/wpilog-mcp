/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transport-independent MCP message handler.
 *
 * <p>Routes JSON-RPC messages to the appropriate handler and returns responses. Contains no I/O
 * logic — shared by both stdio and HTTP transports.
 *
 * <p>A malformed message gets the JSON-RPC 2.0 answer for its shape, never an internal error: a
 * missing or non-string {@code method} is an invalid request (-32600, answered even without an
 * id, since it is not a valid notification either); non-object {@code params} or
 * {@code arguments}, or a missing or non-string tool {@code name}, are invalid params (-32602),
 * with the request's id. A well-formed request without an id is a notification and gets no
 * reply, whatever its method.
 */
public class McpMessageHandler {
  private static final Logger logger = LoggerFactory.getLogger(McpMessageHandler.class);

  static final String SERVER_NAME = "wpilog-mcp";
  static final String SERVER_VERSION = org.triplehelix.wpilogmcp.Version.VERSION;
  static final String PROTOCOL_VERSION = "2025-03-26";

  private final Gson gson;
  private final ToolRegistry toolRegistry;
  private final SessionManager sessionManager;

  public McpMessageHandler(ToolRegistry toolRegistry) {
    this(toolRegistry, null);
  }

  public McpMessageHandler(ToolRegistry toolRegistry, SessionManager sessionManager) {
    this.gson = new GsonBuilder().serializeNulls().create();
    this.toolRegistry = toolRegistry;
    this.sessionManager = sessionManager;
  }

  /**
   * Result of handling a JSON-RPC message.
   *
   * @param response The JSON-RPC response to send (null for notifications that need no response)
   * @param shouldShutdown Whether the server should shut down after sending this response
   * @param newSessionId If non-null, the transport should include this as the Mcp-Session-Id header
   */
  public record HandlerResult(JsonObject response, boolean shouldShutdown, String newSessionId) {
    public static HandlerResult of(JsonObject response) {
      return new HandlerResult(response, false, null);
    }

    public static HandlerResult withSession(JsonObject response, String sessionId) {
      return new HandlerResult(response, false, sessionId);
    }

    public static HandlerResult shutdown(JsonObject response) {
      return new HandlerResult(response, true, null);
    }

    public static HandlerResult noResponse() {
      return new HandlerResult(null, false, null);
    }
  }

  /**
   * Returns the message's method name, or null when it is missing or not a string. JSON-RPC 2.0
   * requires a string; a number, object, or null there is an invalid request, not a crash.
   */
  static String methodName(JsonObject message) {
    var method = message.get("method");
    return method != null && method.isJsonPrimitive() && method.getAsJsonPrimitive().isString()
        ? method.getAsString()
        : null;
  }

  /**
   * Handles a JSON-RPC message without session context (stdio mode).
   */
  public HandlerResult handleMessage(JsonObject message) {
    return handleMessage(message, null);
  }

  /**
   * Handles a JSON-RPC message with an optional session.
   *
   * @param message The parsed JSON-RPC message
   * @param session The session for this request (null for stdio mode)
   * @return The handler result containing the response and shutdown flag
   */
  public HandlerResult handleMessage(JsonObject message, McpSession session) {
    var id = message.get("id");
    var params = message.get("params");
    boolean isNotification = id == null || id.isJsonNull();

    var method = methodName(message);
    if (method == null) {
      // Not a valid request, so not a valid notification either: JSON-RPC 2.0 answers an invalid
      // request with its id when it has one, else with id null.
      var problem = message.has("method") ? "Method must be a string" : "Missing method";
      return HandlerResult.of(
          JsonRpc.createErrorResponse(id, JsonRpc.INVALID_REQUEST, problem));
    }

    logger.debug("Handling request: {}, id: {}", method, id);

    // Set session context for the duration of this request
    if (session != null) {
      SessionContext.set(session);
    }
    try {
      var result = switch (method) {
        // A notification cannot learn a session id, so none is created for it
        case "initialize" ->
            isNotification ? HandlerResult.noResponse() : handleInitialize(id, params);
        case "initialized" -> {
          logger.info("Client initialization complete");
          yield HandlerResult.noResponse();
        }
        case "shutdown" -> {
          logger.info("Received shutdown request");
          yield HandlerResult.shutdown(JsonRpc.createResponse(id, new JsonObject()));
        }
        case "tools/list" -> handleToolsList(id);
        case "tools/call" -> handleToolCall(id, params);
        case "ping" -> HandlerResult.of(JsonRpc.createResponse(id, new JsonObject()));
        case "prompts/list" -> {
          var res = new JsonObject();
          res.add("prompts", new JsonArray());
          yield HandlerResult.of(JsonRpc.createResponse(id, res));
        }
        case "resources/list" -> {
          var res = new JsonObject();
          res.add("resources", new JsonArray());
          yield HandlerResult.of(JsonRpc.createResponse(id, res));
        }
        case "completion/complete" -> {
          var res = new JsonObject();
          res.add("completion", new JsonObject());
          yield HandlerResult.of(JsonRpc.createResponse(id, res));
        }
        default -> {
          if (isNotification) {
            logger.debug("Ignoring unknown notification: {}", method);
            yield HandlerResult.noResponse();
          }
          logger.warn("Received unknown method: {}", method);
          var msg = "Unknown method: " + method;
          yield HandlerResult.of(
              JsonRpc.createErrorResponse(id, JsonRpc.METHOD_NOT_FOUND, msg));
        }
      };
      if (isNotification && result.response() != null) {
        // A request without an id is a notification; the server must not reply to it
        return new HandlerResult(null, result.shouldShutdown(), null);
      }
      return result;
    } catch (Exception e) {
      logger.error("Error handling request '{}': {}", method, e.getMessage(), e);
      if (isNotification) {
        return HandlerResult.noResponse();
      }
      return HandlerResult.of(
          JsonRpc.createErrorResponse(
              id, JsonRpc.INTERNAL_ERROR, "Internal error: " + e.getMessage()));
    } finally {
      if (session != null) {
        SessionContext.clear();
      }
    }
  }

  private HandlerResult handleInitialize(JsonElement id, JsonElement params) {
    var result = new JsonObject();
    result.addProperty("protocolVersion", PROTOCOL_VERSION);

    var capabilities = new JsonObject();
    capabilities.add("tools", new JsonObject());
    result.add("capabilities", capabilities);

    var serverInfo = new JsonObject();
    serverInfo.addProperty("name", SERVER_NAME);
    serverInfo.addProperty("version", SERVER_VERSION);
    result.add("serverInfo", serverInfo);

    // Optional guidance for the client's model; clients MAY add it to the system prompt.
    var instructions = toolRegistry.getServerInstructions();
    if (instructions != null && !instructions.isBlank()) {
      result.addProperty("instructions", instructions);
    }

    // Create a new session if session management is enabled
    if (sessionManager != null) {
      var session = sessionManager.createSession();
      return HandlerResult.withSession(JsonRpc.createResponse(id, result), session.getId());
    }

    return HandlerResult.of(JsonRpc.createResponse(id, result));
  }

  private HandlerResult handleToolsList(JsonElement id) {
    var result = new JsonObject();
    result.add("tools", toolRegistry.toJsonArray());
    return HandlerResult.of(JsonRpc.createResponse(id, result));
  }

  private HandlerResult handleToolCall(JsonElement id, JsonElement params) {
    if (params == null || params.isJsonNull()) {
      return invalidParams(id, "Missing params");
    }
    if (!params.isJsonObject()) {
      return invalidParams(id, "params must be an object");
    }

    var paramsObj = params.getAsJsonObject();
    var name = paramsObj.get("name");
    if (name == null || name.isJsonNull()) {
      return invalidParams(id, "Missing tool name");
    }
    if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
      return invalidParams(id, "Tool name must be a string");
    }
    var toolName = name.getAsString();

    var tool = toolRegistry.getTool(toolName);
    if (tool == null) {
      var suggestions =
          toolRegistry.getToolNames().stream()
              .filter(candidate -> levenshteinDistance(candidate, toolName) <= 3)
              .limit(3)
              .toList();
      String msg = "Unknown tool: " + toolName;
      if (!suggestions.isEmpty()) {
        msg += ". Did you mean: " + String.join(", ", suggestions) + "?";
      }
      return HandlerResult.of(
          JsonRpc.createErrorResponse(id, JsonRpc.METHOD_NOT_FOUND, msg));
    }

    // Arguments are optional; absent or null means none
    var argumentsElement = paramsObj.get("arguments");
    JsonObject arguments;
    if (argumentsElement == null || argumentsElement.isJsonNull()) {
      arguments = new JsonObject();
    } else if (argumentsElement.isJsonObject()) {
      arguments = argumentsElement.getAsJsonObject();
    } else {
      return invalidParams(id, "arguments must be an object");
    }

    logger.info("Executing tool '{}' with {} argument(s)", toolName, arguments.size());
    long startTime = System.currentTimeMillis();
    try {
      var toolResult = tool.execute(arguments);
      long executionTimeMs = System.currentTimeMillis() - startTime;

      logger.info("Tool '{}' executed successfully in {} ms", toolName, executionTimeMs);

      if (toolResult.isJsonObject()) {
        toolResult.getAsJsonObject().addProperty("_execution_time_ms", executionTimeMs);
      }

      return HandlerResult.of(JsonRpc.createResponse(id, wrapToolResult(toolResult, false)));
    } catch (IllegalArgumentException e) {
      logger.warn("Tool '{}' failed with invalid parameter: {}", toolName, e.getMessage());
      return HandlerResult.of(
          JsonRpc.createResponse(
              id,
              wrapToolError(
                  e.getMessage() != null ? e.getMessage() : "Invalid argument",
                  "invalid_parameter")));
    } catch (java.io.IOException e) {
      logger.error("Tool '{}' failed with IO error: {}", toolName, e.getMessage());
      return HandlerResult.of(
          JsonRpc.createResponse(
              id,
              wrapToolError(
                  e.getMessage() != null ? e.getMessage() : "IO error", "io_error")));
    } catch (OutOfMemoryError e) {
      logger.error("Tool '{}' failed with out of memory error", toolName);
      return HandlerResult.of(
          JsonRpc.createResponse(id, wrapToolError("Out of memory", "memory_error")));
    } catch (Exception e) {
      logger.error("Tool '{}' failed with internal error: {}", toolName, e.getMessage(), e);
      return HandlerResult.of(
          JsonRpc.createResponse(
              id,
              wrapToolError(
                  e.getMessage() != null ? e.getMessage() : "Unknown error", "internal_error")));
    }
  }

  private static HandlerResult invalidParams(JsonElement id, String message) {
    return HandlerResult.of(JsonRpc.createErrorResponse(id, JsonRpc.INVALID_PARAMS, message));
  }

  private JsonObject wrapToolResult(JsonElement toolResult, boolean isError) {
    var result = new JsonObject();
    var content = new JsonArray();
    var textContent = new JsonObject();
    textContent.addProperty("type", "text");
    textContent.addProperty("text", gson.toJson(toolResult));
    content.add(textContent);
    result.add("content", content);
    if (isError) {
      result.addProperty("isError", true);
    }
    return result;
  }

  private JsonObject wrapToolError(String errorMessage, String errorType) {
    var errorObj = new JsonObject();
    errorObj.addProperty("error", errorMessage);
    errorObj.addProperty("error_type", errorType);
    return wrapToolResult(errorObj, true);
  }

  /** Calculate Levenshtein distance between two strings for spell-checking suggestions. */
  static int levenshteinDistance(String s1, String s2) {
    int len1 = s1.length();
    int len2 = s2.length();

    int[][] dp = new int[len1 + 1][len2 + 1];

    for (int i = 0; i <= len1; i++) {
      dp[i][0] = i;
    }
    for (int j = 0; j <= len2; j++) {
      dp[0][j] = j;
    }

    for (int i = 1; i <= len1; i++) {
      for (int j = 1; j <= len2; j++) {
        int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
        dp[i][j] =
            Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
      }
    }

    return dp[len1][len2];
  }
}
