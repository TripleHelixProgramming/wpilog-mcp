/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP (Model Context Protocol) server implementation using stdio transport.
 *
 * <p>This is a thin I/O adapter that reads JSON-RPC messages from stdin and writes responses to
 * stdout. All message routing and tool execution is delegated to {@link McpMessageHandler}.
 *
 * <p>MCP stdio is UTF-8 in both directions, whatever the platform charset (which on JDK 17 for
 * Windows is not UTF-8): entry names and text values with non-ASCII characters survive the round
 * trip.
 */
public class McpServer {
  private static final Logger logger = LoggerFactory.getLogger(McpServer.class);

  private final Gson gson;
  private final BufferedReader reader;
  private final PrintWriter writer;
  private final ToolRegistry toolRegistry;
  private final McpMessageHandler handler;

  private volatile boolean running = false;

  public McpServer() {
    this(new ToolRegistry());
  }

  public McpServer(ToolRegistry toolRegistry) {
    this.gson = new GsonBuilder().serializeNulls().create();
    this.reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    this.toolRegistry = toolRegistry;
    this.handler = new McpMessageHandler(toolRegistry);

    var mcpOut = System.out;
    System.setOut(System.err);
    this.writer = new PrintWriter(new OutputStreamWriter(mcpOut, StandardCharsets.UTF_8), true);
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
    var notifications = new Thread(() -> {
      String last = null;
      while (running) try {
        var control = toolRegistry.visualizations().notifications;
        for (var event : control.after("stdio", last, 1000)) {
          sendMessage(JsonParser.parseString(event.json()).getAsJsonObject()); last = event.id();
        }
        if (!control.negotiated("stdio")) Thread.sleep(250);
      } catch (InterruptedException e) { return; }
      catch (IOException e) { logger.warn("Visualization notifications unavailable: {}", e.getMessage()); return; }
    }, "visualization-stdio-events");
    notifications.setDaemon(true); notifications.start();

    try {
    while (running) {
      var line = reader.readLine();
      if (line == null) {
        logger.info("Client disconnected (EOF on stdin)");
        break;
      }

      if (line.trim().isEmpty()) continue;

      try (var responses = org.triplehelix.wpilogmcp.visualization.InventoryResponses.open()) {
        var message = JsonParser.parseString(line).getAsJsonObject();
        var result = handler.handleMessage(message, null, CallerContext.Authority.STDIO_LOCAL,
            new InventoryRequests.Admission(line.getBytes(StandardCharsets.UTF_8).length, true));

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
    } finally { running = false; notifications.interrupt(); toolRegistry.closeVisualizations(); }
    logger.info("MCP server stopped");
  }

  private synchronized void sendMessage(com.google.gson.JsonObject message) {
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
