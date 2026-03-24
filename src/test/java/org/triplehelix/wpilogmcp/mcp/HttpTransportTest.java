package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpTransportTest {
  private static final int TEST_PORT = 0; // Will find a free port
  private HttpTransport transport;
  private HttpClient client;
  private int actualPort;

  @BeforeEach
  void setUp() throws IOException {
    var registry = new ToolRegistry();
    // Register a simple test tool
    registry.registerTool(new ToolRegistry.Tool() {
      @Override public String name() { return "echo"; }
      @Override public String description() { return "Echo tool"; }
      @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
      @Override public com.google.gson.JsonElement execute(JsonObject arguments) {
        var result = new JsonObject();
        result.addProperty("echoed", true);
        return result;
      }
    });

    // Use port 0 to let the OS assign a free port
    transport = new HttpTransport(registry, 0);
    transport.start();
    // Get the actual port from the server
    actualPort = transport.getPort();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void tearDown() {
    transport.stop();
  }

  @Test
  @DisplayName("POST initialize creates session")
  void initializeCreatesSession() throws IOException, InterruptedException {
    var response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);

    assertEquals(200, response.statusCode());
    var sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
    assertNotNull(sessionId, "Response should include Mcp-Session-Id header");

    var body = JsonParser.parseString(response.body()).getAsJsonObject();
    assertEquals("2025-03-26",
        body.getAsJsonObject("result").get("protocolVersion").getAsString());
  }

  @Test
  @DisplayName("POST without session returns 400")
  void postWithoutSession() throws IOException, InterruptedException {
    var response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", null);
    assertEquals(400, response.statusCode());
  }

  @Test
  @DisplayName("POST with invalid session returns 404")
  void postWithInvalidSession() throws IOException, InterruptedException {
    var response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", "bad-session");
    assertEquals(404, response.statusCode());
  }

  @Test
  @DisplayName("full lifecycle: initialize, tools/list, tools/call, delete")
  void fullLifecycle() throws IOException, InterruptedException {
    // Initialize
    var initResponse = post(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
    assertEquals(200, initResponse.statusCode());
    var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();

    // List tools
    var listResponse = post(
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", sessionId);
    assertEquals(200, listResponse.statusCode());
    var listBody = JsonParser.parseString(listResponse.body()).getAsJsonObject();
    var tools = listBody.getAsJsonObject("result").getAsJsonArray("tools");
    assertEquals(1, tools.size());
    assertEquals("echo", tools.get(0).getAsJsonObject().get("name").getAsString());

    // Call tool
    var callResponse = post(
        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"echo\",\"arguments\":{}}}", sessionId);
    assertEquals(200, callResponse.statusCode());

    // Delete session
    var deleteResponse = delete(sessionId);
    assertEquals(200, deleteResponse.statusCode());

    // Subsequent request should get 404
    var afterDelete = post(
        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}", sessionId);
    assertEquals(404, afterDelete.statusCode());
  }

  @Test
  @DisplayName("batch request returns batch response")
  void batchRequest() throws IOException, InterruptedException {
    // Initialize first
    var initResponse = post(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
    var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();

    // Send batch
    var batch = "[{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"},"
        + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}]";
    var response = post(batch, sessionId);
    assertEquals(200, response.statusCode());

    var body = JsonParser.parseString(response.body()).getAsJsonArray();
    assertEquals(2, body.size());
  }

  @Test
  @DisplayName("GET /health returns 200 with status ok")
  void healthEndpointReturnsOk() throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + actualPort + "/health"))
        .GET()
        .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    var body = JsonParser.parseString(response.body()).getAsJsonObject();
    assertEquals("ok", body.get("status").getAsString());
    assertTrue(body.has("sessions"), "Response should include sessions count");
  }

  @Test
  @DisplayName("POST /health returns 405")
  void healthEndpointRejectsPost() throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + actualPort + "/health"))
        .POST(HttpRequest.BodyPublishers.ofString("{}"))
        .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(405, response.statusCode());
  }

  @Test
  @DisplayName("DELETE without session returns 400")
  void deleteWithoutSession() throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + actualPort + "/mcp"))
        .method("DELETE", HttpRequest.BodyPublishers.noBody())
        .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(400, response.statusCode());
  }

  private HttpResponse<String> post(String body, String sessionId)
      throws IOException, InterruptedException {
    var builder = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + actualPort + "/mcp"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (sessionId != null) {
      builder.header("Mcp-Session-Id", sessionId);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> delete(String sessionId)
      throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + actualPort + "/mcp"))
        .header("Mcp-Session-Id", sessionId)
        .method("DELETE", HttpRequest.BodyPublishers.noBody())
        .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
