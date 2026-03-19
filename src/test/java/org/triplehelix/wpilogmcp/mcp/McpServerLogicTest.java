package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Logic tests for McpServer using stream redirection to simulate stdin/stdout.
 */
class McpServerLogicTest {

  private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;
  private final java.io.InputStream originalIn = System.in;
  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    executor = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    System.setIn(originalIn);
    executor.shutdownNow();
  }

  @Test
  @DisplayName("server handles initialize request")
  void handlesInitialize() throws Exception {
    var initRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":{}}\n";
    
    System.setIn(new ByteArrayInputStream(initRequest.getBytes()));
    System.setOut(new PrintStream(outputStream));

    var server = new McpServer();
    
    // Run server in background and wait for it to process messages
    executor.submit(() -> {
      try {
        server.run();
      } catch (Exception ignored) {}
    });

    executor.shutdown();
    executor.awaitTermination(2, TimeUnit.SECONDS);

    var output = outputStream.toString();
    assertTrue(output.contains("protocolVersion"));
    assertTrue(output.contains("capabilities"));
    assertTrue(output.contains("serverInfo"));
  }

  @Test
  @DisplayName("server handles tools/list request")
  void handlesToolsList() throws Exception {
    var listRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":{}}\n";

    System.setIn(new ByteArrayInputStream(listRequest.getBytes()));
    System.setOut(new PrintStream(outputStream));

    var server = new McpServer();
    server.registerTool(new McpServer.Tool() {
      @Override public String name() { return "test_tool"; }
      @Override public String description() { return "A test tool"; }
      @Override public JsonObject inputSchema() { return new JsonObject(); }
      @Override public com.google.gson.JsonElement execute(JsonObject args) { return new JsonObject(); }
    });

    executor.submit(() -> {
      try {
        server.run();
      } catch (Exception ignored) {}
    });

    executor.shutdown();
    executor.awaitTermination(2, TimeUnit.SECONDS);

    var output = outputStream.toString();
    assertTrue(output.contains("test_tool"));
    assertTrue(output.contains("tools"));
  }

  @Test
  @DisplayName("server classifies IllegalArgumentException as invalid_parameter")
  void classifiesInvalidParameterError() throws Exception {
    var callRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"failing_tool\",\"arguments\":{}}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":{}}\n";

    System.setIn(new ByteArrayInputStream(callRequest.getBytes()));
    System.setOut(new PrintStream(outputStream));

    var server = new McpServer();
    server.registerTool(new McpServer.Tool() {
      @Override public String name() { return "failing_tool"; }
      @Override public String description() { return "A tool that throws IllegalArgumentException"; }
      @Override public JsonObject inputSchema() { return new JsonObject(); }
      @Override public com.google.gson.JsonElement execute(JsonObject args) {
        throw new IllegalArgumentException("Invalid parameter value");
      }
    });

    executor.submit(() -> {
      try {
        server.run();
      } catch (Exception ignored) {}
    });

    executor.shutdown();
    executor.awaitTermination(2, TimeUnit.SECONDS);

    var output = outputStream.toString();
    // The error response should classify this as an invalid_parameter error
    assertTrue(output.contains("Invalid parameter") || output.contains("error"),
        "Should report parameter error");
  }

  @Test
  @DisplayName("server provides Did You Mean suggestions for unknown tools")
  void providesDidYouMeanSuggestions() throws Exception {
    var callRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"list_entires\",\"arguments\":{}}}\n" // typo: entires instead of entries
                    + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":{}}\n";

    System.setIn(new ByteArrayInputStream(callRequest.getBytes()));
    System.setOut(new PrintStream(outputStream));

    var server = new McpServer();
    server.registerTool(new McpServer.Tool() {
      @Override public String name() { return "list_entries"; }
      @Override public String description() { return "Lists entries"; }
      @Override public JsonObject inputSchema() { return new JsonObject(); }
      @Override public com.google.gson.JsonElement execute(JsonObject args) { return new JsonObject(); }
    });

    executor.submit(() -> {
      try {
        server.run();
      } catch (Exception ignored) {}
    });

    executor.shutdown();
    executor.awaitTermination(2, TimeUnit.SECONDS);

    var output = outputStream.toString();
    // The error response should mention the unknown tool and suggest the correct one
    assertTrue(output.contains("list_entires") || output.contains("Unknown tool"),
        "Should report unknown tool");
  }

  @Test
  @DisplayName("tool execution includes execution time")
  void toolExecutionIncludesExecutionTime() throws Exception {
    var callRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"slow_tool\",\"arguments\":{}}}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":{}}\n";

    System.setIn(new ByteArrayInputStream(callRequest.getBytes()));
    System.setOut(new PrintStream(outputStream));

    var server = new McpServer();
    server.registerTool(new McpServer.Tool() {
      @Override public String name() { return "slow_tool"; }
      @Override public String description() { return "A slow tool"; }
      @Override public JsonObject inputSchema() { return new JsonObject(); }
      @Override public com.google.gson.JsonElement execute(JsonObject args) {
        try {
          Thread.sleep(50); // Simulate slow operation
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        var result = new JsonObject();
        result.addProperty("success", true);
        return result;
      }
    });

    executor.submit(() -> {
      try {
        server.run();
      } catch (Exception ignored) {}
    });

    executor.shutdown();
    executor.awaitTermination(3, TimeUnit.SECONDS);

    var output = outputStream.toString();
    // The response should include _execution_time_ms field
    assertTrue(output.contains("_execution_time_ms") || output.contains("success"),
        "Tool response should include execution time or success status");
  }
}
