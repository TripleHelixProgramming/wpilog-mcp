package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

  @Nested
  @DisplayName("Origin validation")
  class OriginValidationTest {
    private HttpTransport originTransport;
    private HttpClient originClient;
    private int originPort;

    private ToolRegistry createEchoRegistry() {
      var registry = new ToolRegistry();
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
      return registry;
    }

    @BeforeEach
    void setUp() throws IOException {
      // Create transport with an explicit allowlist containing "example.com"
      originTransport = new HttpTransport(
          createEchoRegistry(), 0, "127.0.0.1", Set.of("example.com"), null);
      originTransport.start();
      originPort = originTransport.getPort();
      originClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
      originTransport.stop();
    }

    private HttpResponse<String> postWithOrigin(String body, String sessionId, String origin)
        throws IOException, InterruptedException {
      var builder = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + originPort + "/mcp"))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json, text/event-stream")
          .POST(HttpRequest.BodyPublishers.ofString(body));
      if (sessionId != null) {
        builder.header("Mcp-Session-Id", sessionId);
      }
      if (origin != null) {
        builder.header("Origin", origin);
      }
      return originClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("forbidden origin returns 403")
    void forbiddenOriginReturns403() throws IOException, InterruptedException {
      var response = postWithOrigin(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
          null, "http://evil.com");
      assertEquals(403, response.statusCode());
    }

    @Test
    @DisplayName("localhost origin is always allowed")
    void localhostOriginAllowed() throws IOException, InterruptedException {
      var response = postWithOrigin(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
          null, "http://localhost:3000");
      assertEquals(200, response.statusCode());
    }

    @Test
    @DisplayName("127.0.0.1 origin is always allowed")
    void loopbackOriginAllowed() throws IOException, InterruptedException {
      var response = postWithOrigin(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
          null, "http://127.0.0.1:8080");
      assertEquals(200, response.statusCode());
    }

    @Test
    @DisplayName("IPv6 loopback origin is always allowed")
    void ipv6LoopbackOriginAllowed() throws IOException, InterruptedException {
      var response = postWithOrigin(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
          null, "http://[::1]:9090");
      assertEquals(200, response.statusCode());
    }

    @Test
    @DisplayName("allowlisted origin is accepted")
    void allowlistedOriginAccepted() throws IOException, InterruptedException {
      var response = postWithOrigin(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
          null, "https://example.com");
      assertEquals(200, response.statusCode());
    }

    @Test
    @DisplayName("malformed origin is rejected with 403")
    void malformedOriginRejected() throws IOException, InterruptedException {
      var response = postWithOrigin(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
          null, "not-a-valid-uri-://broken");
      assertEquals(403, response.statusCode());
    }

    @Test
    @DisplayName("no origin header is allowed (browser does not always send it)")
    void noOriginHeaderAllowed() throws IOException, InterruptedException {
      var response = postWithOrigin(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
          null, null);
      assertEquals(200, response.statusCode());
    }
  }

  @Nested
  @DisplayName("SSE endpoint (GET /mcp)")
  class SseEndpointTest {

    @Test
    @DisplayName("GET /mcp without session ID returns 400")
    void getWithoutSessionReturns400() throws IOException, InterruptedException {
      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + actualPort + "/mcp"))
          .header("Accept", "text/event-stream")
          .GET()
          .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(400, response.statusCode());
      var body = JsonParser.parseString(response.body()).getAsJsonObject();
      assertTrue(body.get("error").getAsString().contains("Missing"),
          "Error should mention missing session header");
    }

    @Test
    @DisplayName("GET /mcp with invalid session ID returns 404")
    void getWithInvalidSessionReturns404() throws IOException, InterruptedException {
      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + actualPort + "/mcp"))
          .header("Accept", "text/event-stream")
          .header("Mcp-Session-Id", "nonexistent-session-id")
          .GET()
          .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(404, response.statusCode());
      var body = JsonParser.parseString(response.body()).getAsJsonObject();
      assertTrue(body.get("error").getAsString().contains("Session not found"),
          "Error should mention session not found");
    }

    @Test
    @DisplayName("GET /mcp without text/event-stream Accept header returns 406")
    void getWithWrongAcceptReturns406() throws IOException, InterruptedException {
      // First create a valid session
      var initResponse = post(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
      var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();

      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + actualPort + "/mcp"))
          .header("Accept", "application/json")
          .header("Mcp-Session-Id", sessionId)
          .GET()
          .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(406, response.statusCode());
      var body = JsonParser.parseString(response.body()).getAsJsonObject();
      assertTrue(body.get("error").getAsString().contains("text/event-stream"),
          "Error should mention text/event-stream requirement");
    }
  }

  @Nested
  @DisplayName("CORS preflight (OPTIONS /mcp)")
  class CorsPreflightTest {

    @Test
    @DisplayName("OPTIONS request returns 204 with CORS headers")
    void optionsReturns204WithCorsHeaders() throws IOException, InterruptedException {
      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + actualPort + "/mcp"))
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
          .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(204, response.statusCode());
    }

    @Test
    @DisplayName("OPTIONS response includes Access-Control-Allow-Methods")
    void optionsIncludesAllowMethods() throws IOException, InterruptedException {
      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + actualPort + "/mcp"))
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
          .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      var allowMethods = response.headers().firstValue("Access-Control-Allow-Methods").orElse(null);
      assertNotNull(allowMethods, "Access-Control-Allow-Methods header should be present");
      assertTrue(allowMethods.contains("POST"), "Should allow POST");
      assertTrue(allowMethods.contains("GET"), "Should allow GET");
      assertTrue(allowMethods.contains("DELETE"), "Should allow DELETE");
      assertTrue(allowMethods.contains("OPTIONS"), "Should allow OPTIONS");
    }

    @Test
    @DisplayName("OPTIONS response includes Access-Control-Allow-Headers")
    void optionsIncludesAllowHeaders() throws IOException, InterruptedException {
      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + actualPort + "/mcp"))
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
          .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      var allowHeaders = response.headers().firstValue("Access-Control-Allow-Headers").orElse(null);
      assertNotNull(allowHeaders, "Access-Control-Allow-Headers header should be present");
      assertTrue(allowHeaders.contains("Content-Type"), "Should allow Content-Type");
      assertTrue(allowHeaders.contains("Accept"), "Should allow Accept");
      assertTrue(allowHeaders.contains("Mcp-Session-Id"), "Should allow Mcp-Session-Id");
    }

    @Test
    @DisplayName("OPTIONS with allowed origin includes Access-Control-Allow-Origin")
    void optionsWithAllowedOriginIncludesCorsOrigin() throws IOException, InterruptedException {
      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + actualPort + "/mcp"))
          .header("Origin", "http://localhost:3000")
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
          .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(204, response.statusCode());
      var allowOrigin = response.headers().firstValue("Access-Control-Allow-Origin").orElse(null);
      assertNotNull(allowOrigin, "Access-Control-Allow-Origin header should be present for allowed origin");
      assertEquals("http://localhost:3000", allowOrigin);
    }
  }

  @Nested
  @DisplayName("Custom mcpPath constructor")
  class CustomMcpPathTest {
    private HttpTransport customTransport;
    private HttpClient customClient;
    private int customPort;

    @BeforeEach
    void setUp() throws IOException {
      var registry = new ToolRegistry();
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

      customTransport = new HttpTransport(registry, 0, "127.0.0.1", null, "/custom/endpoint");
      customTransport.start();
      customPort = customTransport.getPort();
      customClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
      customTransport.stop();
    }

    @Test
    @DisplayName("request to custom mcpPath succeeds")
    void customPathSucceeds() throws IOException, InterruptedException {
      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + customPort + "/custom/endpoint"))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json, text/event-stream")
          .POST(HttpRequest.BodyPublishers.ofString(
              "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
          .build();
      var response = customClient.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      var body = JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals("2025-03-26",
          body.getAsJsonObject("result").get("protocolVersion").getAsString());
    }

    @Test
    @DisplayName("request to default /mcp path returns 404 when custom path configured")
    void defaultPathReturns404() throws IOException, InterruptedException {
      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + customPort + "/mcp"))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json, text/event-stream")
          .POST(HttpRequest.BodyPublishers.ofString(
              "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
          .build();
      var response = customClient.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(404, response.statusCode());
    }

    @Test
    @DisplayName("health endpoint still works with custom mcpPath")
    void healthEndpointStillWorks() throws IOException, InterruptedException {
      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + customPort + "/health"))
          .GET()
          .build();
      var response = customClient.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      var body = JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals("ok", body.get("status").getAsString());
    }
  }
}
