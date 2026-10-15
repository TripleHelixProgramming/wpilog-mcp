/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.visualization.VisualizationWorkspace;

/** SSE can observe an existing workspace, but may neither create nor outlive its owner. */
class VisualizationSseLifecycleTest {
  final HttpClient client = HttpClient.newHttpClient();
  String session(HttpTransport server) throws Exception {
    var reply = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/mcp"))
        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
        .build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, reply.statusCode()); return reply.headers().firstValue("Mcp-Session-Id").orElseThrow();
  }
  Socket stream(HttpTransport server, String session) throws Exception {
    var socket = new Socket("127.0.0.1", server.getPort()); socket.setSoTimeout(3000);
    socket.getOutputStream().write(("GET /mcp HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\nMcp-Session-Id: " + session + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    socket.getOutputStream().flush(); return socket;
  }
  void ping(Socket socket) throws Exception {
    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    String line; boolean ping = false;
    while ((line = input.readLine()) != null) if (line.equals(":ping")) { ping = true; break; }
    assertTrue(ping, "optional visualization failure must leave ordinary SSE heartbeat available");
  }
  @Test void unavailableWorkspaceStillAllowsHeartbeat() throws Exception {
    var registry = new ToolRegistry() {
      @Override public VisualizationWorkspace visualizations() throws IOException { throw new IOException("fixture unavailable state"); }
    };
    var server = new HttpTransport(registry, 0); server.start();
    try (var socket = stream(server, session(server))) { ping(socket); }
    finally { server.stop(); }
  }
  @Test void streamUsesExistingOwnerWithoutCallingTheLazyFactory() throws Exception {
    var forbid = new AtomicBoolean();
    var registry = new ToolRegistry() {
      @Override public VisualizationWorkspace visualizations() throws IOException {
        if (forbid.get()) throw new IOException("SSE called the lazy workspace factory");
        return super.visualizations();
      }
    };
    var server = new HttpTransport(registry, 0); server.start();
    try {
      var session = session(server); forbid.set(true);
      try (var socket = stream(server, session)) { ping(socket); }
    } finally { server.stop(); }
  }
  @Test void workspaceClosesAfterSseWorkersTerminate() throws Exception {
    var transport = new AtomicReference<HttpTransport>(); var closedTooSoon = new AtomicBoolean();
    var registry = new ToolRegistry() {
      @Override public void closeVisualizations() {
        try {
          var field = HttpTransport.class.getDeclaredField("sseExecutor"); field.setAccessible(true);
          closedTooSoon.set(!((ExecutorService) field.get(transport.get())).isTerminated());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        super.closeVisualizations();
      }
    };
    var server = new HttpTransport(registry, 0); transport.set(server); server.start();
    try (var socket = stream(server, session(server))) { ping(socket); server.stop(); }
    finally { server.stop(); }
    assertFalse(closedTooSoon.get(), "SSE disposal must precede workspace disposal");
  }
  @Test void admittedToolFinishesOnItsOriginalWorkspaceBeforeClose() throws Exception {
    var registry = new ToolRegistry(); var server = new HttpTransport(registry, 0); server.start();
    var owner = registry.visualizations(); var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
    registry.registerTool(new ToolRegistry.Tool() {
      public String name() { return "held_visualization"; }
      public String description() { return "Fixture"; }
      public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
      public JsonObject execute(JsonObject arguments) {
        entered.countDown();
        try {
          assertTrue(finish.await(5, TimeUnit.SECONDS)); var result = new JsonObject();
          result.addProperty("same_owner", owner == registry.visualizations()); return result;
        } catch (Exception e) { throw new AssertionError(e); }
      }
    });
    try {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/mcp"))
          .header("Mcp-Session-Id", session(server)).POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"held_visualization\",\"arguments\":{}}}" )).build();
      var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
      assertTrue(entered.await(5, TimeUnit.SECONDS)); var stopper = new AtomicReference<Thread>();
      var stopped = CompletableFuture.runAsync(() -> { stopper.set(Thread.currentThread()); server.stop(); });
      org.triplehelix.wpilogmcp.harness.HarnessHttp.await("shutdown drain reached", 5,
          () -> stopper.get() != null && java.util.Arrays.stream(stopper.get().getStackTrace()).anyMatch(f -> f.getMethodName().equals("awaitInFlightRequests")));
      finish.countDown(); var response = pending.get(5, TimeUnit.SECONDS); stopped.get(5, TimeUnit.SECONDS);
      var rpc = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
      var text = rpc.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
      assertTrue(com.google.gson.JsonParser.parseString(text).getAsJsonObject().get("same_owner").getAsBoolean(), text);
    } finally { finish.countDown(); server.stop(); }
  }
}
