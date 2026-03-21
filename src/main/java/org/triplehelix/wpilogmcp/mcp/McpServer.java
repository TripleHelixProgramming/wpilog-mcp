package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP (Model Context Protocol) server implementation using stdio transport.
 */
public class McpServer {
  private static final Logger logger = LoggerFactory.getLogger(McpServer.class);

  private static final String SERVER_NAME = "wpilog-mcp";
  private static final String SERVER_VERSION = org.triplehelix.wpilogmcp.Version.VERSION;
  private static final String PROTOCOL_VERSION = "2024-11-05";

  private final Gson gson;
  private final BufferedReader reader;
  private final PrintWriter writer;
  private final Map<String, Tool> tools = new ConcurrentHashMap<>();

  private boolean running = false;
  public McpServer() {
    this.gson = new GsonBuilder().serializeNulls().create();
    this.reader = new BufferedReader(new InputStreamReader(System.in));

    var mcpOut = System.out;
    System.setOut(System.err);
    this.writer = new PrintWriter(mcpOut, true);
    logger.debug("McpServer initialized, stdout redirected to stderr");
  }

  public void registerTool(Tool tool) {
    logger.debug("Registering tool: {}", tool.name());
    tools.put(tool.name(), tool);
  }

  public void run() throws IOException {
    logger.info("MCP server listening on stdin");
    running = true;

    while (running) {
      var line = reader.readLine();
      if (line == null) {
        logger.info("Client disconnected (EOF on stdin)");
        break;
      }

      if (line.trim().isEmpty()) continue;

      try {
        var message = JsonParser.parseString(line).getAsJsonObject();
        handleMessage(message);
      } catch (Exception e) {
        logger.error("Failed to parse JSON-RPC message: {}", e.getMessage());
        // JSON-RPC 2.0 §4.2: parse errors must return id:null response
        sendMessage(JsonRpc.createErrorResponse(null, JsonRpc.PARSE_ERROR, "Parse error"));
      }
    }
    logger.info("MCP server stopped");
  }

  private void handleMessage(JsonObject message) {
    var method = message.has("method") ? message.get("method").getAsString() : null;
    var id = message.get("id");
    var params = message.get("params");

    if (method == null) {
      sendError(id, JsonRpc.INVALID_REQUEST, "Missing method");
      return;
    }

    logger.debug("Handling request: {}, id: {}", method, id);

    try {
      switch (method) {
        case "initialize" -> handleInitialize(id, params);
        case "initialized" -> {
          logger.info("Client initialization complete");
        }
        case "shutdown" -> {
          logger.info("Received shutdown request");
          running = false;
          sendResult(id, new JsonObject());
        }
        case "tools/list" -> handleToolsList(id);
        case "tools/call" -> handleToolCall(id, params);
        case "ping" -> sendResult(id, new JsonObject());
        case "prompts/list" -> {
          var res = new JsonObject();
          res.add("prompts", new JsonArray());
          sendResult(id, res);
        }
        case "resources/list" -> {
          var res = new JsonObject();
          res.add("resources", new JsonArray());
          sendResult(id, res);
        }
        case "completion/complete" -> {
          var res = new JsonObject();
          res.add("completion", new JsonObject());
          sendResult(id, res);
        }
        default -> {
          logger.warn("Received unknown method: {}", method);
          sendError(id, JsonRpc.METHOD_NOT_FOUND, "Unknown method: " + method);
        }
      }
    } catch (Exception e) {
      logger.error("Error handling request '{}': {}", method, e.getMessage(), e);
      sendError(id, JsonRpc.INTERNAL_ERROR, "Internal error: " + e.getMessage());
    }
  }

  private void handleInitialize(JsonElement id, JsonElement params) {
    var result = new JsonObject();
    result.addProperty("protocolVersion", PROTOCOL_VERSION);

    var capabilities = new JsonObject();
    capabilities.add("tools", new JsonObject());
    result.add("capabilities", capabilities);

    var serverInfo = new JsonObject();
    serverInfo.addProperty("name", SERVER_NAME);
    serverInfo.addProperty("version", SERVER_VERSION);
    result.add("serverInfo", serverInfo);

    sendResult(id, result);
  }

  private void handleToolsList(JsonElement id) {
    var toolsArray = tools.values().stream()
        .map(tool -> {
          var toolObj = new JsonObject();
          toolObj.addProperty("name", tool.name());
          toolObj.addProperty("description", tool.description());
          toolObj.add("inputSchema", tool.inputSchema());
          return toolObj;
        })
        .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

    var result = new JsonObject();
    result.add("tools", toolsArray);
    sendResult(id, result);
  }

  private void handleToolCall(JsonElement id, JsonElement params) {
    if (params == null || !params.isJsonObject()) {
      sendError(id, JsonRpc.INVALID_PARAMS, "Missing params");
      return;
    }

    var paramsObj = params.getAsJsonObject();
    var toolName = paramsObj.has("name") ? paramsObj.get("name").getAsString() : null;

    if (toolName == null) {
      sendError(id, JsonRpc.INVALID_PARAMS, "Missing tool name");
      return;
    }

    var tool = tools.get(toolName);
    if (tool == null) {
      var suggestions = tools.keySet().stream()
          .filter(name -> levenshteinDistance(name, toolName) <= 3)
          .limit(3)
          .toList();
      String msg = "Unknown tool: " + toolName;
      if (!suggestions.isEmpty()) {
        msg += ". Did you mean: " + String.join(", ", suggestions) + "?";
      }
      sendError(id, JsonRpc.METHOD_NOT_FOUND, msg);
      return;
    }

    var arguments = paramsObj.has("arguments") ? paramsObj.getAsJsonObject("arguments") : new JsonObject();

    logger.info("Executing tool '{}' with {} argument(s)", toolName, arguments.size());
    long startTime = System.currentTimeMillis();
    try {
      var toolResult = tool.execute(arguments);
      long executionTimeMs = System.currentTimeMillis() - startTime;

      logger.info("Tool '{}' executed successfully in {} ms", toolName, executionTimeMs);

      // Add execution time to result if it's a JsonObject
      if (toolResult.isJsonObject()) {
        toolResult.getAsJsonObject().addProperty("_execution_time_ms", executionTimeMs);
      }

      var result = new JsonObject();
      var content = new JsonArray();
      var textContent = new JsonObject();
      textContent.addProperty("type", "text");
      textContent.addProperty("text", gson.toJson(toolResult));
      content.add(textContent);
      result.add("content", content);
      sendResult(id, result);
    } catch (IllegalArgumentException e) {
      // Client error: bad parameters
      logger.warn("Tool '{}' failed with invalid parameter: {}", toolName, e.getMessage());
      var result = new JsonObject();
      var content = new JsonArray();
      var textContent = new JsonObject();
      textContent.addProperty("type", "text");
      var errorObj = new JsonObject();
      errorObj.addProperty("error", e.getMessage() != null ? e.getMessage() : "Invalid argument");
      errorObj.addProperty("error_type", "invalid_parameter");
      textContent.addProperty("text", gson.toJson(errorObj));
      content.add(textContent);
      result.add("content", content);
      result.addProperty("isError", true);
      sendResult(id, result);
    } catch (java.io.IOException e) {
      // IO error: file/network issues
      logger.error("Tool '{}' failed with IO error: {}", toolName, e.getMessage());
      var result = new JsonObject();
      var content = new JsonArray();
      var textContent = new JsonObject();
      textContent.addProperty("type", "text");
      var errorObj = new JsonObject();
      errorObj.addProperty("error", e.getMessage() != null ? e.getMessage() : "IO error");
      errorObj.addProperty("error_type", "io_error");
      textContent.addProperty("text", gson.toJson(errorObj));
      content.add(textContent);
      result.add("content", content);
      result.addProperty("isError", true);
      sendResult(id, result);
    } catch (OutOfMemoryError e) {
      // Memory error
      logger.error("Tool '{}' failed with out of memory error", toolName);
      var result = new JsonObject();
      var content = new JsonArray();
      var textContent = new JsonObject();
      textContent.addProperty("type", "text");
      var errorObj = new JsonObject();
      errorObj.addProperty("error", "Out of memory");
      errorObj.addProperty("error_type", "memory_error");
      textContent.addProperty("text", gson.toJson(errorObj));
      content.add(textContent);
      result.add("content", content);
      result.addProperty("isError", true);
      sendResult(id, result);
    } catch (Exception e) {
      // Generic internal error
      logger.error("Tool '{}' failed with internal error: {}", toolName, e.getMessage(), e);
      var result = new JsonObject();
      var content = new JsonArray();
      var textContent = new JsonObject();
      textContent.addProperty("type", "text");
      var errorObj = new JsonObject();
      errorObj.addProperty("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
      errorObj.addProperty("error_type", "internal_error");
      textContent.addProperty("text", gson.toJson(errorObj));
      content.add(textContent);
      result.add("content", content);
      result.addProperty("isError", true);
      sendResult(id, result);
    }
  }

  private void sendResult(JsonElement id, JsonElement result) {
    sendMessage(JsonRpc.createResponse(id, result));
  }

  private void sendError(JsonElement id, int code, String message) {
    if (id == null || id.isJsonNull()) return;
    sendMessage(JsonRpc.createErrorResponse(id, code, message));
  }

  private void sendMessage(JsonObject message) {
    writer.println(gson.toJson(message));
    writer.flush();
  }

  /**
   * Calculate Levenshtein distance between two strings for spell-checking suggestions.
   */
  private int levenshteinDistance(String s1, String s2) {
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
        dp[i][j] = Math.min(
            Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
            dp[i - 1][j - 1] + cost
        );
      }
    }

    return dp[len1][len2];
  }

  public interface Tool {
    String name();
    String description();
    JsonObject inputSchema();
    JsonElement execute(JsonObject arguments) throws Exception;
  }

  public static class SchemaBuilder {
    private final JsonObject schema = new JsonObject();
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    public SchemaBuilder() {
      schema.addProperty("type", "object");
      schema.add("properties", properties);
    }

    public SchemaBuilder addProperty(String name, String type, String description, boolean isRequired) {
      var prop = new JsonObject();
      prop.addProperty("type", type);
      prop.addProperty("description", description);
      properties.add(name, prop);
      if (isRequired) required.add(name);
      return this;
    }

    public SchemaBuilder addIntegerProperty(String name, String description, boolean isRequired, Integer defaultValue) {
      var prop = new JsonObject();
      prop.addProperty("type", "integer");
      prop.addProperty("description", description);
      if (defaultValue != null) prop.addProperty("default", defaultValue);
      properties.add(name, prop);
      if (isRequired) required.add(name);
      return this;
    }

    public SchemaBuilder addNumberProperty(String name, String description, boolean isRequired, Double defaultValue) {
      var prop = new JsonObject();
      prop.addProperty("type", "number");
      prop.addProperty("description", description);
      if (defaultValue != null) prop.addProperty("default", defaultValue);
      properties.add(name, prop);
      if (isRequired) required.add(name);
      return this;
    }

    public JsonObject build() {
      if (required.size() > 0) schema.add("required", required);
      return schema;
    }
  }
}
