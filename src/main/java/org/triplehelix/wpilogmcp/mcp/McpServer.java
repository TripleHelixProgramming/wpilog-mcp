package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP (Model Context Protocol) server implementation using stdio transport.
 *
 * <p>This is a thin I/O adapter that reads JSON-RPC messages from stdin and writes responses to
 * stdout. All message routing and tool execution is delegated to {@link McpMessageHandler}.
 */
public class McpServer {
  private static final Logger logger = LoggerFactory.getLogger(McpServer.class);

  private final Gson gson;
  private final BufferedReader reader;
  private final PrintWriter writer;
  private final ToolRegistry toolRegistry;
  private final McpMessageHandler handler;

  private boolean running = false;

  public McpServer() {
    this(new ToolRegistry());
  }

  public McpServer(ToolRegistry toolRegistry) {
    this.gson = new GsonBuilder().serializeNulls().create();
    this.reader = new BufferedReader(new InputStreamReader(System.in));
    this.toolRegistry = toolRegistry;
    this.handler = new McpMessageHandler(toolRegistry);

    var mcpOut = System.out;
    System.setOut(System.err);
    this.writer = new PrintWriter(mcpOut, true);
    logger.debug("McpServer initialized, stdout redirected to stderr");
  }

  public void registerTool(Tool tool) {
    toolRegistry.registerTool(tool);
  }

  public ToolRegistry getToolRegistry() {
    return toolRegistry;
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
        var result = handler.handleMessage(message);

        if (result.shouldShutdown()) {
          running = false;
        }

        if (result.response() != null) {
          sendMessage(result.response());
        }
      } catch (Exception e) {
        logger.error("Failed to parse JSON-RPC message: {}", e.getMessage());
        sendMessage(JsonRpc.createErrorResponse(null, JsonRpc.PARSE_ERROR, "Parse error"));
      }
    }
    logger.info("MCP server stopped");
  }

  private void sendMessage(com.google.gson.JsonObject message) {
    writer.println(gson.toJson(message));
    writer.flush();
  }

  /**
   * Backward-compatible type alias for {@link ToolRegistry.Tool}.
   */
  public interface Tool extends ToolRegistry.Tool {}

  /**
   * Backward-compatible type alias for {@link ToolRegistry.SchemaBuilder}.
   */
  public static class SchemaBuilder extends ToolRegistry.SchemaBuilder {}
}
