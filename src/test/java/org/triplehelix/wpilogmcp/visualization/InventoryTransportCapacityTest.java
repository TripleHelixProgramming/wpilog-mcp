/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.RecordingFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.*;
import org.triplehelix.wpilogmcp.tools.VisualizationTools;

/** Real sockets with blocked admissions prove that four inventory readers do not occupy the
 * transport's control workers. Timing is only a deadlock bound, not a performance claim. */
class InventoryTransportCapacityTest {
  @TempDir Path temp;
  final HttpClient http = HttpClient.newHttpClient();
  HttpRequest request(String base, String route, String body, String session) {
    var builder = HttpRequest.newBuilder(URI.create(base + route)).timeout(Duration.ofSeconds(3));
    if (session != null) builder.header("Mcp-Session-Id", session);
    return body == null ? builder.GET().build() : builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"control", "authority", "stalled-refusal", "oversized-http", "oversized-mcp", "batch"})
  void saturatedInventoryWorkersLeaveHealthPingAndCloseAvailable(String scenario) throws Exception {
    var logs = LogManager.getInstance(); var old = logs.getConfiguredDirectories(); temp = temp.toRealPath();
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp); var fixture = new RecordingFixture(temp.resolve("store"), 4);
    var registry = new ToolRegistry(); registry.serverIdentity(ServerIdentity.ephemeral()); VisualizationTools.registerAll(registry);
    var workspace = registry.visualizations(); var block = new AtomicBoolean(); var entered = new CountDownLatch(4); var release = new CountDownLatch(1);
    var inventory = new RecordingInventories(ref -> {
      if (block.get()) { entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new java.io.InterruptedIOException(); } }
      return workspace.sources.admitRecording(ref);
    }, RecordingInventories.Limits.defaults(), System::nanoTime);
    workspace.inventories.close(); var field = VisualizationWorkspace.class.getField("inventories"); field.setAccessible(true); field.set(workspace, inventory);
    var server = new HttpTransport(registry, 0);
    var limits = HttpTransport.class.getDeclaredMethod("requestLimits", int.class, int.class, Duration.class);
    limits.setAccessible(true); limits.invoke(server, 4 << 20, 2, Duration.ofSeconds(3));
    var timeout = HttpTransport.class.getDeclaredMethod("streamTimeout", Duration.class);
    timeout.setAccessible(true); timeout.invoke(server, Duration.ofMillis(250));
    try {
      server.start(); String base = "http://127.0.0.1:" + server.getPort();
      var initialized = http.send(request(base, "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"capacity\",\"version\":\"1\"}}}", null), HttpResponse.BodyHandlers.ofString());
      String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var opening = new JsonObject(); opening.add("source_ref", fixture.reference); opening.addProperty("inventory_mode", "paged");
      var opened = http.send(request(base, "/visualizations/v1/source/describe", opening.toString(), session), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, opened.statusCode(), opened.body()); var handle = JsonParser.parseString(opened.body()).getAsJsonObject();
      var id = RecordingInventoriesTest.id(handle);
      var page = http.send(request(base, "/visualizations/v1/source/entries/list", id.toString(), session), HttpResponse.BodyHandlers.ofString());
      var details = id.deepCopy(); var ids = new JsonArray(); ids.add(JsonParser.parseString(page.body()).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().get("row_id")); details.add("row_ids", ids);
      var calls = new ArrayList<CompletableFuture<HttpResponse<String>>>(); block.set(true);
      for (int i = 0; i < 4; i++) {
        // Half the readers come through MCP so a separate HTTP-only pool would not suffice.
        String route = i < 2 ? "/visualizations/v1/source/entries/get" : "/mcp";
        var body = details.deepCopy(); body.addProperty("api_version", 1);
        if (i >= 2) body = rpc(body);
        calls.add(http.sendAsync(request(base, route, body.toString(), session), HttpResponse.BodyHandlers.ofString()));
      }
      assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(8 << 20, inventory.responseBytes());
      var refused = http.send(request(base, "/visualizations/v1/source/entries/list", id.toString(), session), HttpResponse.BodyHandlers.ofString());
      assertEquals(413, refused.statusCode()); assertTrue(JsonParser.parseString(refused.body()).getAsJsonObject().get("retryable").getAsBoolean());
      if (scenario.equals("authority")) {
        var foreign = HttpRequest.newBuilder(request(base, "/visualizations/v1/source/entries/list", id.toString(), session), (name, value) -> true)
            .header("Origin", "https://untrusted.invalid").build();
        assertEquals(403, http.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode());
      }
      if (scenario.startsWith("oversized-")) {
        boolean mcp = scenario.equals("oversized-mcp");
        var body = mcp ? rpc(details) : id.deepCopy(); body.addProperty(mcp ? "id" : "padding", "x".repeat(80 << 10));
        var result = http.send(request(base, mcp ? "/mcp" : "/visualizations/v1/source/entries/list", body.toString(), session), HttpResponse.BodyHandlers.ofString());
        assertEquals(mcp ? 200 : 413, result.statusCode());
        var envelope = JsonParser.parseString(result.body()).getAsJsonObject();
        var refusal = mcp ? JsonParser.parseString(envelope.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject() : envelope;
        assertFalse(refusal.get("retryable").getAsBoolean(), "An oversized request cannot succeed unchanged");
        assertTrue(result.body().length() < 4096, "Refusals must not echo an oversized request id");
        if (mcp) assertTrue(envelope.get("id").isJsonNull());
      }
      if (scenario.equals("batch")) {
        var batch = new JsonArray(); batch.add(rpc(details)); var second = rpc(details); second.addProperty("id", 2); batch.add(second);
        var result = http.send(request(base, "/mcp", batch.toString(), session), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode()); var replies = JsonParser.parseString(result.body()).getAsJsonArray(); assertEquals(2, replies.size());
        for (int i = 0; i < 2; i++) {
          var reply = replies.get(i).getAsJsonObject(); assertEquals(i + 1, reply.get("id").getAsInt());
          assertTrue(reply.getAsJsonObject("result").get("isError").getAsBoolean());
          var refusal = JsonParser.parseString(reply.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
          assertEquals("RESOURCE_LIMIT", refusal.get("code").getAsString()); assertTrue(refusal.get("retryable").getAsBoolean());
        }
      }
      if (scenario.equals("stalled-refusal")) {
        try (var socket = new java.net.Socket("127.0.0.1", server.getPort())) {
          socket.setSoTimeout(2000);
          var header = "POST /visualizations/v1/source/entries/list HTTP/1.1\r\nHost: 127.0.0.1\r\nMcp-Session-Id: " + session + "\r\nContent-Length: 1024\r\n\r\n";
          socket.getOutputStream().write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
          assertEquals(-1, socket.getInputStream().read(), "A refusal drain must have its own IO deadline");
        }
      }
      assertEquals(200, http.send(request(base, "/health", null, null), HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(200, http.send(request(base, "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", session), HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(200, http.send(request(base, "/visualizations/v1/source/inventory/close", id.toString(), session), HttpResponse.BodyHandlers.ofString()).statusCode());
      release.countDown();
      for (int i = 0; i < 4; i++) {
        var result = calls.get(i).get(3, TimeUnit.SECONDS); assertEquals(i < 2 ? 410 : 200, result.statusCode(), result.body()); assertTrue(result.body().contains("INVENTORY_EXPIRED"));
      }
      RecordingInventoriesTest.await(() -> inventory.responseBytes() == 0 && inventory.retainedBytes() == 0);
      assertTrue(logs.release(temp).released());
    } finally {
      release.countDown(); server.stop(); inventory.close(); logs.addAllowedDirectory(temp); logs.release(temp);
      logs.clearAllowedDirectories(); old.forEach(logs::addAllowedDirectory);
    }
  }
  private static JsonObject rpc(JsonObject arguments) {
    var request = new JsonObject(); request.addProperty("jsonrpc", "2.0"); request.addProperty("id", 1); request.addProperty("method", "tools/call");
    var params = new JsonObject(); params.addProperty("name", "visualization_source_entries_get"); params.add("arguments", arguments); request.add("params", params); return request;
  }
}
