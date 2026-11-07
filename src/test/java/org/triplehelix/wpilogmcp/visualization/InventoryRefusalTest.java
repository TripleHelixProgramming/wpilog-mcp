/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.triplehelix.wpilogmcp.fixtures.RecordingFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.*;
import org.triplehelix.wpilogmcp.tools.VisualizationTools;

/** Refusals use the same transport/session/protocol rules as successful calls, even when all
 * four data workers are held by real HTTP and MCP requests. The peer never releases a worker
 * to make a control request succeed. */
class InventoryRefusalTest {
  @TempDir Path temp;
  @ParameterizedTest @CsvSource({"http,false,false", "http,true,false", "http,false,true", "http,true,true",
      "mcp,false,false", "mcp,true,false", "mcp,false,true", "mcp,true,true"})
  void healthSharesRawLimitsAndInventoryCapacity(String adapter, boolean busy, boolean oversized) throws Exception {
    try (var h = new Harness(busy)) {
      var body = new JsonObject(); body.add("source_ref", h.reference);
      String wire = adapter.equals("mcp") ? rpc("visualization_source_health", body, 1).toString() : body.toString();
      if (oversized) wire = " ".repeat(65537) + wire;
      var reply = h.send(adapter.equals("mcp") ? "/mcp" : "/visualizations/v1/source/health", wire, h.session);
      assertEquals(adapter.equals("mcp") ? 200 : busy || oversized ? 413 : 200, reply.statusCode(), reply.body());
      var parsed = JsonParser.parseString(reply.body()).getAsJsonObject();
      if (adapter.equals("mcp")) parsed = toolBody(parsed);
      if (busy || oversized) assertRefusal(parsed, !oversized);
      else assertEquals("inactive", (adapter.equals("mcp") ? parsed.getAsJsonObject("health") : parsed).get("acquisition_state").getAsString());
      assertEquals(200, h.send("/mcp", ping(2).toString(), h.session).statusCode());
    }
  }
  @ParameterizedTest @CsvSource({"spaces,false", "duplicate,false", "unicode,false", "batch,false",
      "spaces,true", "duplicate,true", "unicode,true", "batch,true"})
  void rawBytesSurviveJsonNormalization(String encoding, boolean busy) throws Exception {
    try (var h = new Harness(busy)) {
      var request = rpc("visualization_source_entries_list", h.id, 1);
      String wire = request.toString();
      if (encoding.equals("batch")) wire = "[" + wire + "," + ping(2) + "]";
      if (encoding.equals("spaces") || encoding.equals("batch")) wire = " ".repeat(70_000) + wire;
      else wire = "{\"ignored\":\"" + (encoding.equals("unicode") ? "é".repeat(35_000) : "x".repeat(70_000)) + "\",\"ignored\":\"\"," + wire.substring(1);
      assertTrue(wire.getBytes(StandardCharsets.UTF_8).length > 65536);
      var response = h.send("/mcp", wire, h.session); assertEquals(200, response.statusCode());
      var parsed = JsonParser.parseString(response.body());
      var first = encoding.equals("batch") ? parsed.getAsJsonArray().get(0).getAsJsonObject() : parsed.getAsJsonObject();
      assertRefusal(toolBody(first), false);
      if (encoding.equals("batch")) assertEquals(new JsonObject(), parsed.getAsJsonArray().get(1).getAsJsonObject().getAsJsonObject("result"));
    }
  }

  @ParameterizedTest @CsvSource({"missing-mcp,false", "missing-http,false", "expired,false", "malformed-http,false", "mixed,false", "no-id,false", "null-id,false",
      "missing-mcp,true", "missing-http,true", "expired,true", "malformed-http,true", "mixed,true", "no-id,true", "null-id,true"})
  void refusalPreservesSessionAndJsonRpcSemantics(String scenario, boolean busy) throws Exception {
    try (var h = new Harness(busy)) {
      var list = rpc("visualization_source_entries_list", h.id, 1);
      switch (scenario) {
        case "missing-mcp" -> assertEquals(400, h.send("/mcp", list.toString(), null).statusCode());
        case "missing-http" -> assertEquals(400, h.send("/visualizations/v1/source/entries/list", h.id.toString(), null).statusCode());
        case "expired" -> assertEquals(404, h.send("/mcp", list.toString(), UUID.randomUUID().toString()).statusCode());
        case "malformed-http" -> assertEquals(400, h.send("/visualizations/v1/source/entries/list", "{", h.session).statusCode());
        case "no-id", "null-id" -> {
          list.addProperty("padding", "x".repeat(70_000));
          if (scenario.equals("no-id")) list.remove("id"); else list.add("id", JsonNull.INSTANCE);
          var response = h.send("/mcp", list.toString(), h.session);
          assertEquals(202, response.statusCode()); assertEquals("", response.body());
        }
        case "mixed" -> {
          var batch = new JsonArray(); batch.add(list); batch.add(ping(2));
          batch.add(rpc("visualization_source_inventory_close", h.id, 3));
          var invalid = new JsonObject(); invalid.addProperty("id", 4); batch.add(invalid);
          var notification = ping(5); notification.remove("id"); batch.add(notification);
          var nullId = ping(6); nullId.add("id", JsonNull.INSTANCE); batch.add(nullId);
          var response = h.send("/mcp", batch.toString(), h.session); assertEquals(200, response.statusCode());
          var replies = JsonParser.parseString(response.body()).getAsJsonArray(); assertEquals(4, replies.size(), response.body());
          for (int i = 0; i < 4; i++) assertEquals(i + 1, replies.get(i).getAsJsonObject().get("id").getAsInt());
          if (busy) assertRefusal(toolBody(replies.get(0).getAsJsonObject()), true);
          else assertEquals("ok", toolBody(replies.get(0).getAsJsonObject()).get("status").getAsString());
          assertEquals(new JsonObject(), replies.get(1).getAsJsonObject().getAsJsonObject("result"));
          assertTrue(toolBody(replies.get(2).getAsJsonObject()).getAsJsonObject("inventory").get("closed").getAsBoolean());
          assertEquals(-32600, replies.get(3).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
          h.unblock(); assertEquals(0, h.inventories.retainedHandles(), "The close in a mixed batch must run under pressure");
        }
        default -> fail(scenario);
      }
    }
  }

  @ParameterizedTest @CsvSource({"65536,false", "65537,true"})
  void rawRequestBoundaryCountsEveryByte(int length, boolean refused) throws Exception {
    try (var h = new Harness(false)) {
      String request = rpc("visualization_source_entries_list", h.id, 1).toString();
      request = " ".repeat(length - request.getBytes(StandardCharsets.UTF_8).length) + request;
      assertEquals(length, request.getBytes(StandardCharsets.UTF_8).length);
      var response = h.send("/mcp", request, h.session); assertEquals(200, response.statusCode());
      var body = toolBody(JsonParser.parseString(response.body()).getAsJsonObject());
      if (refused) assertRefusal(body, false); else assertEquals("ok", body.get("status").getAsString());
    }
  }

  @org.junit.jupiter.api.Test void inventoryBatchHasASeparateAggregateReplyCap() throws Exception {
    try (var h = new Harness(false)) {
      h.registry.registerTool(new ToolRegistry.Tool() {
        public String name() { return "wide_fixture"; }
        public String description() { return "Synthetic wide response"; }
        public JsonObject inputSchema() { return new JsonObject(); }
        public JsonElement execute(JsonObject arguments) { var out = new JsonObject(); out.addProperty("value", "x".repeat(4500000)); return out; }
      });
      var batch = new JsonArray(); batch.add(rpc("visualization_source_entries_list", h.id, 1));
      batch.add(rpc("wide_fixture", new JsonObject(), 2)); batch.add(rpc("wide_fixture", new JsonObject(), 3));
      var response = h.send("/mcp", batch.toString(), h.session);
      assertEquals(413, response.statusCode()); assertTrue(response.body().length() < 4096);
      assertRefusal(JsonParser.parseString(response.body()).getAsJsonObject(), false);
      assertTrue(response.body().contains("may have executed"), "A batch is not a transaction; refusal must not invite a blind retry");
      assertEquals(0, h.inventories.responseBytes());
      assertEquals(8 << 20, h.registry.visualizations().capabilities().getAsJsonObject("recording_inventory_limits").get("max_mcp_batch_reply_bytes").getAsInt());
    }
  }

  @ParameterizedTest @CsvSource({"0,false", "1,true"})
  void aggregateReplyBoundaryCountsUtf8EscapingAndArrayFraming(int extra, boolean refused) throws Exception {
    try (var h = new Harness(false)) {
      // Independent wire oracle: a scalar tool result has no timing field. Its JSON string is
      // embedded in the text block, so '<' becomes a double-escaped Unicode sequence.
      String prefix = "[{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"\\\"";
      String encoded = "é\\\\u003c".repeat(100);
      String suffix = "\\\"\"}]}}]";
      int filler = (8 << 20) + extra - (prefix + encoded + suffix).getBytes(StandardCharsets.UTF_8).length;
      String expected = prefix + encoded + "x".repeat(filler) + suffix;
      h.registry.registerTool(new ToolRegistry.Tool() {
        public String name() { return "boundary_fixture"; }
        public String description() { return "Synthetic encoded boundary"; }
        public JsonObject inputSchema() { return new JsonObject(); }
        public JsonElement execute(JsonObject arguments) { return new JsonPrimitive("é<".repeat(100) + "x".repeat(filler)); }
      });
      var batch = new JsonArray(); var notification = rpc("visualization_source_entries_list", h.id, 1);
      notification.remove("id"); batch.add(notification); batch.add(rpc("boundary_fixture", new JsonObject(), 2));
      var response = h.send("/mcp", batch.toString(), h.session);
      assertEquals(refused ? 413 : 200, response.statusCode());
      if (refused) assertRefusal(JsonParser.parseString(response.body()).getAsJsonObject(), false);
      else {
        assertEquals(8 << 20, response.body().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(expected, response.body());
      }
      assertEquals(0, h.inventories.responseBytes());
    }
  }

  static void assertRefusal(JsonObject body, boolean retryable) {
    assertEquals(new JsonPrimitive("RESOURCE_LIMIT"), body.get("code"), body.toString());
    assertEquals(new JsonPrimitive(retryable), body.get("retryable"), body.toString());
  }
  static JsonObject toolBody(JsonObject reply) {
    return JsonParser.parseString(reply.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
  }
  static JsonObject ping(int id) { var out = new JsonObject(); out.addProperty("jsonrpc", "2.0"); out.addProperty("id", id); out.addProperty("method", "ping"); return out; }
  static JsonObject rpc(String name, JsonObject arguments, int id) {
    var out = ping(id); out.addProperty("method", "tools/call"); var params = new JsonObject(); params.addProperty("name", name);
    var args = arguments.deepCopy(); args.addProperty("api_version", 1); params.add("arguments", args); out.add("params", params); return out;
  }
  final class Harness implements AutoCloseable {
    final LogManager logs = LogManager.getInstance(); final Set<Path> old = logs.getConfiguredDirectories();
    final HttpClient http = HttpClient.newHttpClient(); final CountDownLatch release = new CountDownLatch(1);
    final ArrayList<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
    final ToolRegistry registry; final HttpTransport server; final RecordingInventories inventories; final String session, base; final JsonObject id, reference;
    Harness(boolean busy) throws Exception {
      temp = temp.toRealPath(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
      var fixture = new RecordingFixture(temp.resolve("store"), 4);
      reference = fixture.reference;
      registry = new ToolRegistry(); registry.serverIdentity(ServerIdentity.ephemeral()); VisualizationTools.registerAll(registry);
      var workspace = registry.visualizations(); var block = new AtomicBoolean(); var entered = new CountDownLatch(4);
      inventories = new RecordingInventories(ref -> {
        if (block.get()) { entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new java.io.InterruptedIOException(); } }
        return workspace.sources.admitRecording(ref);
      }, RecordingInventories.Limits.defaults(), System::nanoTime);
      workspace.inventories.close(); var field = VisualizationWorkspace.class.getField("inventories"); field.setAccessible(true); field.set(workspace, inventories);
      server = new HttpTransport(registry, 0); server.start(); base = "http://127.0.0.1:" + server.getPort();
      session = send("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}", null).headers().firstValue("Mcp-Session-Id").orElseThrow();
      var open = new JsonObject(); open.add("source_ref", fixture.reference); open.addProperty("inventory_mode", "paged");
      var inventory = JsonParser.parseString(send("/visualizations/v1/source/describe", open.toString(), session).body()).getAsJsonObject();
      id = RecordingInventoriesTest.id(inventory);
      var page = JsonParser.parseString(send("/visualizations/v1/source/entries/list", id.toString(), session).body()).getAsJsonObject();
      var details = id.deepCopy(); var rows = new JsonArray(); rows.add(page.getAsJsonArray("entries").get(0).getAsJsonObject().get("row_id")); details.add("row_ids", rows);
      if (busy) {
        block.set(true);
        for (int i = 0; i < 4; i++) calls.add(http.sendAsync(request(i < 2 ? "/visualizations/v1/source/entries/get" : "/mcp",
            i < 2 ? details.toString() : rpc("visualization_source_entries_get", details, i + 10).toString(), session), HttpResponse.BodyHandlers.ofString()));
        assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(8 << 20, inventories.responseBytes());
      }
    }
    HttpRequest request(String path, String body, String owner) {
      var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString(body));
      if (owner != null) request.header("Mcp-Session-Id", owner); return request.build();
    }
    HttpResponse<String> send(String path, String body, String owner) throws Exception { return http.send(request(path, body, owner), HttpResponse.BodyHandlers.ofString()); }
    void unblock() throws Exception { release.countDown(); for (var call : calls) call.get(5, TimeUnit.SECONDS); }
    @Override public void close() throws Exception {
      try { unblock(); } finally { server.stop(); inventories.close(); logs.addAllowedDirectory(temp); logs.release(temp); logs.clearAllowedDirectories(); old.forEach(logs::addAllowedDirectory); }
    }
  }
}
