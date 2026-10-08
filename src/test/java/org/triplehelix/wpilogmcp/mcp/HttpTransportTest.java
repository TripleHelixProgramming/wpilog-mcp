/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HttpTransportTest {
  private static final int TEST_PORT = 0; // Will find a free port
  private ToolRegistry registry;
  private HttpTransport transport;
  private HttpClient client;
  private int actualPort;

  @BeforeEach
  void setUp() throws IOException {
    registry = new ToolRegistry();
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
    registry.setServerInstructions("HTTP test instructions");

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
    assertEquals("HTTP test instructions",
        body.getAsJsonObject("result").get("instructions").getAsString());
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

  @Nested
  @DisplayName("Shutdown")
  class ShutdownTest {

    private String initialize() throws IOException, InterruptedException {
      var initResponse = post(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
      return initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    @Test
    @DisplayName("stop() returns promptly with nothing in flight")
    void stopIsPromptWhenIdle() {
      long start = System.nanoTime();
      transport.stop();
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      assertTrue(elapsedMs < 1000, "stop() took " + elapsedMs + " ms with nothing in flight");
    }

    @Test
    @DisplayName("stop() returns promptly while an SSE stream is open")
    void stopIsPromptWithOpenSseStream() throws Exception {
      var sessionId = initialize();

      // A raw socket, so the stream is known to be open (first ping received) when stop() runs
      try (var socket = new Socket("127.0.0.1", actualPort)) {
        socket.setSoTimeout(5000);
        var request = "GET /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n"
            + "Mcp-Session-Id: " + sessionId + "\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        var in = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line;
        boolean pinged = false;
        while ((line = in.readLine()) != null) {
          if (line.contains(":ping")) {
            pinged = true;
            break;
          }
        }
        assertTrue(pinged, "The SSE stream should have sent its first ping");

        long start = System.nanoTime();
        transport.stop();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 1000,
            "stop() took " + elapsedMs + " ms with an SSE stream open");

        // The server has ended the stream: the socket reaches end of stream (or is reset)
        try {
          while (in.readLine() != null) {
            // drain the chunk terminator
          }
        } catch (IOException ignored) {
          // A reset also means the server closed the connection
        }
      }
    }

    @Test
    @DisplayName("stop() lets a request in flight finish")
    void stopDrainsRequestInFlight() throws Exception {
      var started = new CountDownLatch(1); var finish = new CountDownLatch(1);
      registry.registerTool(new ToolRegistry.Tool() {
        @Override public String name() { return "slow"; }
        @Override public String description() { return "Takes a while"; }
        @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
        @Override public com.google.gson.JsonElement execute(JsonObject arguments) {
          started.countDown();
          try {
            assertTrue(finish.await(5, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          var result = new JsonObject();
          result.addProperty("done", true);
          return result;
        }
      });
      var sessionId = initialize();

      var request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + actualPort + "/mcp"))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json, text/event-stream")
          .header("Mcp-Session-Id", sessionId)
          .POST(HttpRequest.BodyPublishers.ofString(
              "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                  + "\"params\":{\"name\":\"slow\",\"arguments\":{}}}"))
          .build();
      var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
      assertTrue(started.await(5, TimeUnit.SECONDS), "The tool call should be in flight");

      var stopping = java.util.concurrent.CompletableFuture.runAsync(transport::stop);
      org.triplehelix.wpilogmcp.harness.HarnessHttp.await("shutdown admitted", 5, transport::stopping);
      assertFalse(stopping.isDone(), "The request is still held");
      finish.countDown(); stopping.get(5, TimeUnit.SECONDS);

      var response = pending.get(5, TimeUnit.SECONDS);
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("done"),
          "The request in flight should have completed: " + response.body());
    }
  }

  @Nested
  @DisplayName("Malformed JSON-RPC over HTTP")
  class MalformedMessagesTest {

    private String initialize() throws IOException, InterruptedException {
      var initResponse = post(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
      return initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    @Test
    @DisplayName("non-string method is a JSON-RPC invalid request, not HTTP 500")
    void nonStringMethod() throws Exception {
      var sessionId = initialize();
      var response = post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":5}", sessionId);

      assertEquals(200, response.statusCode());
      var body = JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals(JsonRpc.INVALID_REQUEST, body.getAsJsonObject("error").get("code").getAsInt());
      assertEquals(7, body.get("id").getAsInt());
    }

    @Test
    @DisplayName("batch with a non-string method is answered element by element")
    void batchWithNonStringMethod() throws Exception {
      var sessionId = initialize();
      var response = post("[{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":{\"x\":1}},"
          + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}]", sessionId);

      assertEquals(200, response.statusCode());
      var body = JsonParser.parseString(response.body()).getAsJsonArray();
      assertEquals(2, body.size());
      assertEquals(JsonRpc.INVALID_REQUEST,
          body.get(0).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
      assertEquals(2, body.get(0).getAsJsonObject().get("id").getAsInt());
      assertTrue(body.get(1).getAsJsonObject().has("result"));
    }

    @Test
    @DisplayName("non-object arguments are invalid params with the id preserved")
    void nonObjectArguments() throws Exception {
      var sessionId = initialize();
      var response = post("{\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\"method\":\"tools/call\","
          + "\"params\":{\"name\":\"echo\",\"arguments\":\"nope\"}}", sessionId);

      assertEquals(200, response.statusCode());
      var body = JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals(JsonRpc.INVALID_PARAMS, body.getAsJsonObject("error").get("code").getAsInt());
      assertEquals("call-1", body.get("id").getAsString());
    }

    @Test
    @DisplayName("a request without an id is a notification: 202 and no body")
    void notificationGetsNoBody() throws Exception {
      var sessionId = initialize();
      var response = post("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}", sessionId);

      assertEquals(202, response.statusCode());
      assertTrue(response.body().isEmpty(), "A notification must not be answered");
    }
  }

  // ==================== /health, /stop, and the idle exit ====================

  @Nested
  @DisplayName("the version in /health, POST /stop, and the idle exit")
  class ControlTests {
    private final java.util.List<HttpTransport> started = new java.util.ArrayList<>();

    private HttpTransport startWith(java.util.function.Consumer<HttpTransport> configure)
        throws IOException {
      var t = new HttpTransport(registry, 0);
      configure.accept(t);
      t.start();
      started.add(t);
      return t;
    }

    @AfterEach
    void stopAll() {
      started.forEach(HttpTransport::stop);
    }

    private HttpResponse<String> request(HttpTransport t, String method, String path,
        String token) throws IOException, InterruptedException {
      var builder = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + t.getPort() + path))
          .method(method, HttpRequest.BodyPublishers.noBody());
      if (token != null) builder.header(HttpTransport.STOP_TOKEN_HEADER, token);
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postTo(HttpTransport t, String body, String sessionId)
        throws IOException, InterruptedException {
      var builder = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + t.getPort() + "/mcp"))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json, text/event-stream")
          .POST(HttpRequest.BodyPublishers.ofString(body));
      if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String initialize(HttpTransport t) throws IOException, InterruptedException {
      var response = postTo(t, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
      assertEquals(200, response.statusCode());
      return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    @Test
    @DisplayName("/health reports the version and the process ID, so a start can tell its own")
    void healthReportsVersionAndPid() throws Exception {
      var response = request(transport, "GET", "/health", null);
      assertEquals(200, response.statusCode());
      var health = JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals("ok", health.get("status").getAsString());
      assertEquals(0, health.get("sessions").getAsInt());
      assertEquals(org.triplehelix.wpilogmcp.Version.VERSION, health.get("version").getAsString());
      assertEquals(ProcessHandle.current().pid(), health.get("pid").getAsLong());
    }

    @Test
    @DisplayName("/stop is refused when it is not enabled, and the server goes on serving")
    void stopNotEnabled() throws Exception {
      var response = request(transport, "POST", "/stop", "anything");
      assertEquals(403, response.statusCode());
      assertTrue(response.body().contains("not enabled"), response.body());
      assertEquals(200, request(transport, "GET", "/health", null).statusCode());
    }

    @Test
    @DisplayName("/stop needs the token, and only POST")
    void stopNeedsTheToken() throws Exception {
      var stopped = new CountDownLatch(1);
      var t = startWith(x -> x.setStop("secret", stopped::countDown));
      assertEquals(403, request(t, "POST", "/stop", null).statusCode(), "no token");
      assertEquals(403, request(t, "POST", "/stop", "wrong").statusCode(), "wrong token");
      assertEquals(405, request(t, "GET", "/stop", "secret").statusCode(), "not a POST");
      assertEquals(1, stopped.getCount(), "nothing above may stop the server");
      assertEquals(200, request(t, "GET", "/health", null).statusCode());

      var response = request(t, "POST", "/stop", "secret");
      assertEquals(200, response.statusCode());
      assertEquals("stopping",
          JsonParser.parseString(response.body()).getAsJsonObject().get("status").getAsString());
      assertTrue(stopped.await(5, TimeUnit.SECONDS), "the stop action did not run");
    }

    @Test
    @DisplayName("a stop lets the call in flight finish before the server stops")
    void stopLetsCallsFinish() throws Exception {
      var slow = new ToolRegistry();
      var toolDone = new java.util.concurrent.atomic.AtomicLong();
      var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
      slow.registerTool(new ToolRegistry.Tool() {
        @Override public String name() { return "slow"; }
        @Override public String description() { return "Sleeps"; }
        @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
        @Override public com.google.gson.JsonElement execute(JsonObject arguments) {
          try {
            entered.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          toolDone.set(System.nanoTime());
          var result = new JsonObject();
          result.addProperty("slept", true);
          return result;
        }
      });
      var t = new HttpTransport(slow, 0);
      var stopReturned = new java.util.concurrent.atomic.AtomicLong();
      var stopDone = new CountDownLatch(1);
      t.setStop("secret", () -> {
        t.stop();
        stopReturned.set(System.nanoTime());
        stopDone.countDown();
      });
      t.start();
      started.add(t);
      var session = initialize(t);

      var call = java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> postTo(t,
          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"slow\",\"arguments\":{}}}",
          session));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(200, request(t, "POST", "/stop", "secret").statusCode());
      org.triplehelix.wpilogmcp.harness.HarnessHttp.await("shutdown admitted", 5, t::stopping);
      assertEquals(0, toolDone.get()); finish.countDown();

      var response = call.get(10, TimeUnit.SECONDS);
      assertEquals(200, response.statusCode(), "the call in flight must be answered");
      assertTrue(response.body().contains("slept"), response.body());
      assertTrue(stopDone.await(10, TimeUnit.SECONDS));
      assertTrue(stopReturned.get() >= toolDone.get(), "the server stopped before the call ended");
    }

    @Test
    @DisplayName("only a loopback connection may stop the server")
    void onlyLoopbackMayStop() throws Exception {
      assertTrue(HttpTransport.isLoopback(
          new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 1)));
      assertTrue(HttpTransport.isLoopback(new java.net.InetSocketAddress("127.0.0.1", 1)));
      assertFalse(HttpTransport.isLoopback(
          new java.net.InetSocketAddress(java.net.InetAddress.getByAddress(
              new byte[] {10, 23, 63, 2}), 1)));
      assertFalse(HttpTransport.isLoopback(null));
    }

    @Test
    @DisplayName("with no session and no request, the server exits after the idle time")
    void idleExitFires() throws Exception {
      var exited = new CountDownLatch(1);
      var time = new java.util.concurrent.atomic.AtomicLong();
      var t = startWith(x -> { x.idleClock(time::get); x.setIdleExit(java.time.Duration.ofMillis(300), exited::countDown); });
      time.set(300_000_000); t.exitIfIdle();
      assertEquals(0, exited.getCount());
    }

    @Test
    @DisplayName("a session keeps the server up; its end starts the idle clock")
    void sessionHoldsTheServer() throws Exception {
      var exited = new CountDownLatch(1);
      var time = new java.util.concurrent.atomic.AtomicLong();
      var t = startWith(x -> { x.idleClock(time::get); x.setIdleExit(java.time.Duration.ofMillis(300), exited::countDown); });
      var session = initialize(t);
      time.set(1_200_000_000); t.exitIfIdle();
      assertEquals(1, exited.getCount(), "the server exited while a session was open");
      assertEquals(1, t.sessionCount());

      var delete = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + t.getPort() + "/mcp"))
          .header("Mcp-Session-Id", session)
          .method("DELETE", HttpRequest.BodyPublishers.noBody())
          .build();
      assertEquals(200, client.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode());
      time.addAndGet(300_000_000); t.exitIfIdle();
      assertEquals(0, exited.getCount(), "the idle exit did not run after the session");
    }

    @Test
    @DisplayName("MCP requests reset the idle clock; health checks do not")
    void requestsResetTheClock() throws Exception {
      var exited = new CountDownLatch(1);
      var time = new java.util.concurrent.atomic.AtomicLong();
      var t = startWith(x -> { x.idleClock(time::get); x.setIdleExit(java.time.Duration.ofMillis(500), exited::countDown); });
      for (int i = 0; i < 15; i++) {
        time.addAndGet(100_000_000);
        postTo(t, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null);
        t.exitIfIdle();
      }
      assertEquals(1, exited.getCount(), "the server exited while it was being used");
      for (int i = 0; i < 5; i++) {
        time.addAndGet(100_000_000); request(t, "GET", "/health", null); t.exitIfIdle();
      }
      assertEquals(0, exited.getCount(), "health checks kept the server up");
    }
  }
}
