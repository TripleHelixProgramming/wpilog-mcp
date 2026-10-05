/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The stdio bridge, driven as a stdio MCP client drives it: lines in, lines out, against the
 * real HTTP transport, and against a server of the test's own for what the transport does not
 * do (send events on the GET stream, lose a session).
 */
@DisplayName("StdioBridge")
class StdioBridgeTest {

  /**
   * A stdio client: writes lines to the bridge's input and reads the lines it writes, each
   * with a timeout, so a bridge that writes nothing fails the test rather than hanging it.
   */
  private static final class Client implements AutoCloseable {
    final PipedOutputStream toBridge = new PipedOutputStream();
    final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    final Future<Integer> exit;

    Client(URI endpoint) throws IOException {
      var bridgeIn = new PipedInputStream(toBridge, 1 << 16);
      var bridgeOut = new PipedOutputStream();
      var fromBridge = new PipedInputStream(bridgeOut, 1 << 16);
      var bridge = new StdioBridge(endpoint, bridgeIn, bridgeOut);
      pool.submit(() -> {
        try (var reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(fromBridge, StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            lines.add(line);
          }
        } catch (IOException e) {
          // the bridge closed its output
        }
        return null;
      });
      exit = pool.submit(() -> {
        try {
          return bridge.run();
        } finally {
          bridgeOut.close();
        }
      });
    }

    void send(String line) throws IOException {
      toBridge.write((line + "\n").getBytes(StandardCharsets.UTF_8));
      toBridge.flush();
    }

    JsonObject next() throws InterruptedException {
      var line = lines.poll(10, TimeUnit.SECONDS);
      assertNotNull(line, "the bridge wrote nothing within 10 s");
      return JsonParser.parseString(line).getAsJsonObject();
    }

    /** Whether nothing arrives within the wait: a notification must produce no line. */
    boolean silentFor(long millis) throws InterruptedException {
      return lines.poll(millis, TimeUnit.MILLISECONDS) == null;
    }

    int closeInputAndWait() throws Exception {
      toBridge.close();
      return exit.get(15, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      pool.shutdownNow();
    }
  }

  private static String request(int id, String method, String params) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":"
        + params + "}";
  }

  @Nested
  @DisplayName("against the HTTP transport")
  class AgainstTheTransport {
    private HttpTransport transport;
    private URI endpoint;

    @BeforeEach
    void start() throws IOException {
      var registry = new ToolRegistry();
      registry.registerTool(new ToolRegistry.Tool() {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echoes its argument"; }
        @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
        @Override public com.google.gson.JsonElement execute(JsonObject arguments) {
          var result = new JsonObject();
          result.add("echoed", arguments);
          return result;
        }
      });
      registry.setServerInstructions("bridge test");
      transport = new HttpTransport(registry, 0);
      transport.start();
      endpoint = URI.create("http://127.0.0.1:" + transport.getPort() + "/mcp");
    }

    @AfterEach
    void stop() {
      transport.stop();
    }

    @Test
    @DisplayName("initialize, a notification, a call, and an error, as over stdio")
    void relaysASession() throws Exception {
      try (var client = new Client(endpoint)) {
        client.send(request(1, "initialize", "{}"));
        var init = client.next();
        assertEquals(1, init.get("id").getAsInt());
        assertEquals("bridge test",
            init.getAsJsonObject("result").get("instructions").getAsString());
        assertEquals(1, transport.sessionCount(), "the bridge holds one session");

        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertTrue(client.silentFor(300), "a notification must produce no line");

        client.send(request(2, "tools/call", "{\"name\":\"echo\",\"arguments\":{\"x\":7}}"));
        var call = client.next();
        assertEquals(2, call.get("id").getAsInt());
        var text = call.getAsJsonObject("result").getAsJsonArray("content")
            .get(0).getAsJsonObject().get("text").getAsString();
        assertEquals(7, JsonParser.parseString(text).getAsJsonObject()
            .getAsJsonObject("echoed").get("x").getAsInt());

        client.send(request(3, "tools/call", "{\"name\":\"no_such_tool\",\"arguments\":{}}"));
        var error = client.next();
        assertEquals(3, error.get("id").getAsInt());
        assertTrue(error.has("error") || error.getAsJsonObject("result").get("isError")
            .getAsBoolean(), "an unknown tool is an error: " + error);

        client.send("this is not json");
        var parse = client.next();
        assertEquals(JsonRpc.PARSE_ERROR, parse.getAsJsonObject("error").get("code").getAsInt());

        // A client that initializes again gets a fresh session, and the old one is not leaked
        client.send(request(4, "initialize", "{}"));
        assertEquals(4, client.next().get("id").getAsInt());
        assertEquals(1, transport.sessionCount(), "the first session must be deleted");
        client.send(request(5, "tools/list", "{}"));
        assertTrue(client.next().has("result"), "the new session must work");

        assertEquals(0, client.closeInputAndWait(), "input closed: a clean exit");
        assertEquals(0, transport.sessionCount(), "the session must be deleted on exit");
      }
    }

    @Test
    @DisplayName("a request before initialize is answered with the server's error, not a hang")
    void requestBeforeInitialize() throws Exception {
      try (var client = new Client(endpoint)) {
        client.send(request(1, "tools/list", "{}"));
        var answer = client.next();
        assertEquals(1, answer.get("id").getAsInt());
        assertTrue(answer.has("error"), answer.toString());
        assertEquals(0, client.closeInputAndWait());
      }
    }
  }

  @Nested
  @DisplayName("against a server of the test's own")
  class AgainstAFakeServer {
    private HttpServer server;
    private URI endpoint;
    private final List<String> deleted = new CopyOnWriteArrayList<>();
    /** Lines the server writes on the GET stream, once a stream is open. */
    private final CountDownLatch streamOpen = new CountDownLatch(1);
    private volatile java.io.OutputStream stream;
    private volatile boolean sessionGone;

    @BeforeEach
    void start() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/mcp", this::handle);
      server.setExecutor(Executors.newCachedThreadPool());
      server.start();
      endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    @AfterEach
    void stop() {
      server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
      switch (exchange.getRequestMethod()) {
        case "POST" -> {
          var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          var message = JsonParser.parseString(body).getAsJsonObject();
          if ("initialize".equals(message.get("method").getAsString())) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", "s-1");
            reply(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + message.get("id")
                + ",\"result\":{}}");
          } else if (sessionGone) {
            reply(exchange, 404, "{\"error\":\"Session not found or expired\"}");
          } else if (!message.has("id")) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
          } else {
            reply(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + message.get("id")
                + ",\"result\":{\"ok\":true}}");
          }
        }
        case "GET" -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          stream = exchange.getResponseBody();
          stream.write(":ping\n\n".getBytes(StandardCharsets.UTF_8));
          stream.flush();
          streamOpen.countDown();
          // Left open; the test writes events to it and the bridge closes its end
        }
        case "DELETE" -> {
          deleted.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        }
        default -> {
          exchange.sendResponseHeaders(405, -1);
          exchange.close();
        }
      }
    }

    private static void reply(HttpExchange exchange, int status, String body)
        throws IOException {
      var bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }

    @Test
    @DisplayName("the server's own messages on the GET stream are written as lines")
    void relaysServerMessages() throws Exception {
      try (var client = new Client(endpoint)) {
        client.send(request(1, "initialize", "{}"));
        assertEquals(1, client.next().get("id").getAsInt());
        assertTrue(streamOpen.await(10, TimeUnit.SECONDS), "the bridge did not open the stream");

        // A comment is not a message; an event's data is, even over several data lines
        stream.write((":keep-alive\n\n"
            + "event: message\n"
            + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n"
            + "\n").getBytes(StandardCharsets.UTF_8));
        stream.flush();
        var notice = client.next();
        assertEquals("notifications/tools/list_changed", notice.get("method").getAsString());

        stream.write(("data: {\"jsonrpc\":\"2.0\",\n"
            + "data: \"id\":9,\"method\":\"ping\"}\n\n").getBytes(StandardCharsets.UTF_8));
        stream.flush();
        var ping = client.next();
        assertEquals(9, ping.get("id").getAsInt());

        assertEquals(0, client.closeInputAndWait());
        assertEquals(List.of("s-1"), deleted, "the session is deleted when input closes");
      }
    }

    @Test
    @DisplayName("a session the server no longer knows ends the bridge with an error answer")
    void sessionGoneEndsTheBridge() throws Exception {
      try (var client = new Client(endpoint)) {
        client.send(request(1, "initialize", "{}"));
        client.next();
        sessionGone = true;
        client.send(request(2, "tools/list", "{}"));
        var answer = client.next();
        assertEquals(2, answer.get("id").getAsInt());
        assertEquals(StdioBridge.BRIDGE_ERROR, answer.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(answer.getAsJsonObject("error").get("message").getAsString()
            .contains("restarted"), answer.toString());
        assertEquals(1, client.closeInputAndWait(), "a lost session is a failed exit");
      }
    }
  }

  @Test
  @DisplayName("a server that cannot be reached answers the request with an error and ends the bridge")
  void unreachableServer() throws Exception {
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    try (var client = new Client(URI.create("http://127.0.0.1:" + port + "/mcp"))) {
      client.send(request(1, "initialize", "{}"));
      var answer = client.next();
      assertEquals(1, answer.get("id").getAsInt());
      assertEquals(StdioBridge.BRIDGE_ERROR, answer.getAsJsonObject("error").get("code").getAsInt());
      assertEquals(1, client.closeInputAndWait());
    }
  }

  @Test
  @DisplayName("a URL without a path means its /mcp endpoint")
  void endpointFor() {
    assertEquals(URI.create("http://pit:2363/mcp"), StdioBridge.endpointFor("http://pit:2363"));
    assertEquals(URI.create("http://pit:2363/mcp"), StdioBridge.endpointFor("http://pit:2363/"));
    assertEquals(URI.create("http://pit:2363/x"), StdioBridge.endpointFor(" http://pit:2363/x "));
  }

  @Test
  @DisplayName("the event parser takes an event's data and ignores comments and other fields")
  void sseData() {
    var data = StdioBridge.SseEvents.dataOf(
        ":ping\n\nevent: message\ndata: {\"a\":1}\n\ndata: x\ndata: y\n\nid: 3\ndata: last".lines());
    assertEquals(List.of("{\"a\":1}", "x\ny", "last"), data);
  }
}
