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
}
