/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.RecordingFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.tools.VisualizationTools;
import org.triplehelix.wpilogmcp.visualization.ServerIdentity;

class RecordingInventoryEndpointTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance();
  final HttpClient http = HttpClient.newHttpClient();
  Set<Path> original;
  HttpTransport server;
  String session;
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); original = logs.getConfiguredDirectories();
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    var registry = new ToolRegistry(); registry.serverIdentity(ServerIdentity.open(temp.resolve("state")));
    VisualizationTools.registerAll(registry);
    server = new HttpTransport(registry, 0); server.start(); session = connect();
  }
  @AfterEach void stop() throws Exception {
    if (server != null) server.stop(); logs.addAllowedDirectory(temp); logs.release(temp);
    logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
  }
  String base() { return "http://127.0.0.1:" + server.getPort(); }
  String connect() throws Exception {
    var reply = send("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"inventory-fixture\",\"version\":\"1\"}}}", null);
    assertEquals(200, reply.statusCode(), reply.body()); return reply.headers().firstValue("Mcp-Session-Id").orElseThrow();
  }
  HttpResponse<String> send(String path, String body, String owner) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    if (owner != null) request.header("Mcp-Session-Id", owner);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
  JsonObject call(String path, JsonObject body) throws Exception {
    var reply = send("/visualizations/v1" + path, body.toString(), session);
    assertEquals(200, reply.statusCode(), reply.body()); assertTrue(reply.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1 << 20);
    return JsonParser.parseString(reply.body()).getAsJsonObject();
  }
  JsonObject open(RecordingFixture fixture) throws Exception {
    var body = new JsonObject(); body.add("source_ref", fixture.reference); body.addProperty("inventory_mode", "paged");
    return call("/source/describe", body);
  }
  static JsonObject id(JsonObject inventory) { var body = new JsonObject(); body.add("inventory_id", inventory.get("inventory_id")); return body; }
  JsonObject tool(String name, JsonObject arguments) throws Exception {
    var request = new JsonObject(); request.addProperty("jsonrpc", "2.0"); request.addProperty("id", 1); request.addProperty("method", "tools/call");
    var params = new JsonObject(); params.addProperty("name", "visualization_" + name); arguments = arguments.deepCopy(); arguments.addProperty("api_version", 1);
    params.add("arguments", arguments); request.add("params", params);
    var reply = send("/mcp", request.toString(), session); assertEquals(200, reply.statusCode(), reply.body());
    assertTrue(reply.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1 << 20, "MCP envelope counts toward the reply cap");
    return JsonParser.parseString(JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("result")
        .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
  }

  @ParameterizedTest @ValueSource(ints = {5000, 10000, 20000})
  void detachedPagesEnumerateBothMembersExactly(int names) throws Exception {
    var inventory = open(new RecordingFixture(temp.resolve("store"), names));
    assertEquals(new JsonPrimitive("paged"), inventory.get("inventory_mode"), "Paged mode must be explicitly negotiated, not silently ignored");
    assertFalse(inventory.has("entries")); assertEquals(names, inventory.get("total_entry_names").getAsInt());
    assertTrue(logs.release(temp).released(), "Publishing an inventory must release every mapped prefix");
    var request = id(inventory); request.addProperty("limit", 137);
    int next = 0;
    do {
      var page = call("/source/entries/list", request);
      assertEquals(page, call("/source/entries/list", request), "Retry must not advance the cursor");
      assertEquals(names, page.get("total_matching").getAsInt());
      assertEquals(0, page.get("unavailable_entry_names").getAsInt());
      for (var element : page.getAsJsonArray("entries")) {
        var row = element.getAsJsonObject(); assertEquals(RecordingFixture.name(next), row.get("name").getAsString());
        assertEquals(next < names / 2 ? "3" : next % 2 == 0 ? "1" : "2", row.get("sample_count").getAsString());
        assertFalse(row.get("conflict").getAsBoolean()); assertFalse(row.has("metadata")); next++;
      }
      assertEquals(next == names, page.get("complete").getAsBoolean());
      if (page.get("next_cursor").isJsonNull()) break;
      request = id(inventory); request.add("cursor", page.get("next_cursor"));
    } while (true);
    assertEquals(names, next);
    call("/source/inventory/close", id(inventory));
    assertEquals(410, send("/visualizations/v1/source/entries/list", id(inventory).toString(), session).statusCode());
  }

  @Test void modeVocabularyIsClosedAndLegacyReplyIsUnchanged() throws Exception {
    var fixture = new RecordingFixture(temp.resolve("store"), 4);
    var body = new JsonObject(); body.add("source_ref", fixture.reference);
    var legacy = call("/source/describe", body);
    body.addProperty("inventory_mode", "full"); assertEquals(legacy, call("/source/describe", body));
    body.addProperty("inventory_mode", "guess");
    assertEquals(400, send("/visualizations/v1/source/describe", body.toString(), session).statusCode());
  }

  @Test void httpAndMcpSharePagesDetailsAndTheResultContract() throws Exception {
    var fixture = new RecordingFixture(temp.resolve("store"), 8);
    var request = new JsonObject(); request.add("source_ref", fixture.reference); request.addProperty("inventory_mode", "paged");
    var inventory = tool("source_inspect", request).getAsJsonObject("source").getAsJsonObject("inventory");
    request = id(inventory); request.addProperty("limit", 3);
    var page = call("/source/entries/list", request); var mcp = tool("source_entries_list", request);
    assertEquals(page, mcp.get("page")); assertEquals("ok", mcp.get("status").getAsString());
    var limits = mcp.getAsJsonObject("limits").getAsJsonObject("page.entries");
    assertEquals(8, limits.get("total").getAsInt()); assertEquals(3, limits.get("returned").getAsInt()); assertEquals(3, limits.get("limit").getAsInt());
    var details = id(inventory); var ids = new JsonArray(); ids.add(page.getAsJsonArray("entries").get(0).getAsJsonObject().get("row_id")); details.add("row_ids", ids);
    var httpDetails = call("/source/entries/get", details); var mcpDetails = tool("source_entries_get", details).getAsJsonObject("details");
    assertNotEquals(httpDetails.remove("detail_revision"), mcpDetails.remove("detail_revision")); assertEquals(httpDetails, mcpDetails);
    assertEquals("fresh_admission", httpDetails.get("count_basis").getAsString());
    assertEquals(2, httpDetails.getAsJsonArray("descriptors").size());
    for (var member : httpDetails.getAsJsonArray("descriptors")) assertEquals(1, member.getAsJsonObject().getAsJsonObject("snapshot").getAsJsonObject("entries").size());
    request = id(inventory); request.addProperty("query", "not-present");
    mcp = tool("source_entries_list", request); assertEquals("no_match", mcp.get("status").getAsString());
    assertTrue(mcp.has("looked_for")); assertTrue(mcp.has("hint")); assertEquals(8, mcp.getAsJsonObject("page").get("total_entry_names").getAsInt());
    assertEquals(call("/source/inventory/close", id(inventory)), tool("source_inventory_close", id(inventory)).get("inventory"));
    assertEquals("INVENTORY_EXPIRED", tool("source_entries_list", id(inventory)).get("code").getAsString());
  }

  @Test void cursorsBindInventoryFilterLimitAndSession() throws Exception {
    var fixture = new RecordingFixture(temp.resolve("store"), 5); var first = open(fixture); var second = open(fixture);
    var request = id(first); request.addProperty("limit", 2); var page = call("/source/entries/list", request);
    request = id(first); request.add("cursor", page.get("next_cursor")); var continuation = call("/source/entries/list", request);
    assertEquals(continuation, call("/source/entries/list", request));
    var changed = request.deepCopy(); changed.addProperty("limit", 3);
    assertEquals(400, send("/visualizations/v1/source/entries/list", changed.toString(), session).statusCode());
    changed = request.deepCopy(); changed.add("inventory_id", second.get("inventory_id"));
    assertEquals(400, send("/visualizations/v1/source/entries/list", changed.toString(), session).statusCode());
    changed = request.deepCopy(); String token = request.get("cursor").getAsString();
    changed.addProperty("cursor", token.substring(0, token.length() - 1) + (token.endsWith("x") ? "y" : "x"));
    assertEquals(400, send("/visualizations/v1/source/entries/list", changed.toString(), session).statusCode());
    String other = connect();
    assertEquals(403, send("/visualizations/v1/source/entries/list", request.toString(), other).statusCode());
    assertEquals(403, send("/visualizations/v1/source/inventory/close", id(first).toString(), other).statusCode());
    assertEquals(continuation, call("/source/entries/list", request));
  }

  @Test void revocationStopsReplayButNeverPreventsClose() throws Exception {
    var inventory = open(new RecordingFixture(temp.resolve("store"), 5)); var request = id(inventory);
    call("/source/entries/list", request);
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(Files.createDirectory(temp.resolve("elsewhere")));
    assertEquals(403, send("/visualizations/v1/source/entries/list", request.toString(), session).statusCode());
    assertEquals(410, send("/visualizations/v1/source/entries/list", request.toString(), session).statusCode());
    assertTrue(call("/source/inventory/close", request).get("closed").getAsBoolean());
  }

  @Test void handlesAreSharedFairlyAndSessionDeletionReleasesThem() throws Exception {
    var fixture = new RecordingFixture(temp.resolve("store"), 2); var owners = new ArrayList<String>();
    for (int i = 0; i < 4; i++) {
      owners.add(session); open(fixture); open(fixture);
      var request = new JsonObject(); request.add("source_ref", fixture.reference); request.addProperty("inventory_mode", "paged");
      var refusal = send("/visualizations/v1/source/describe", request.toString(), session);
      assertEquals(413, refusal.statusCode()); var body = JsonParser.parseString(refusal.body()).getAsJsonObject();
      assertTrue(body.get("retryable").getAsBoolean()); assertEquals(1000, body.get("retry_after_ms").getAsInt());
      session = connect();
    }
    var request = new JsonObject(); request.add("source_ref", fixture.reference); request.addProperty("inventory_mode", "paged");
    assertEquals(413, send("/visualizations/v1/source/describe", request.toString(), session).statusCode());
    var reply = http.send(HttpRequest.newBuilder(URI.create(base() + "/mcp")).header("Mcp-Session-Id", owners.get(0)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, reply.statusCode()); open(fixture); open(fixture);
  }

  @Test void oversizedRequestsAndInvalidFiltersHaveTypedRefusals() throws Exception {
    var inventory = open(new RecordingFixture(temp.resolve("store"), 2));
    var request = id(inventory); request.addProperty("query", "x".repeat(65536));
    var reply = send("/visualizations/v1/source/entries/list", request.toString(), session);
    assertEquals(413, reply.statusCode()); assertFalse(JsonParser.parseString(reply.body()).getAsJsonObject().get("retryable").getAsBoolean());
    assertEquals("RESOURCE_LIMIT", tool("source_entries_list", request).get("code").getAsString());
    for (String invalid : List.of("{\"query\":null}", "{\"limit\":0}", "{\"limit\":257}", "{\"limit\":1.5}", "{\"types\":[1]}", "{\"unavailable\":\"true\"}", "{\"unavailable\":true,\"query\":\"x\"}")) {
      request = JsonParser.parseString(invalid).getAsJsonObject(); request.add("inventory_id", inventory.get("inventory_id"));
      assertEquals(400, send("/visualizations/v1/source/entries/list", request.toString(), session).statusCode(), invalid);
    }
    request = id(inventory); request.addProperty("query", "😀".repeat(257));
    assertEquals(400, send("/visualizations/v1/source/entries/list", request.toString(), session).statusCode());
    request = id(inventory); request.addProperty("query", "İ".repeat(256));
    assertEquals(0, call("/source/entries/list", request).get("total_matching").getAsInt());
  }

  @Test void byteLimitedPagesFitBothEnvelopesAndKeepEveryLongEscapedName() throws Exception {
    var fixture = new RecordingFixture(temp.resolve("store"), 0); var expected = new ArrayList<String>();
    for (int i = 0; i < 80; i++) expected.add(RecordingFixture.name(i) + "\"\\😀".repeat(1000));
    fixture.rewrite(0, writer -> { for (String name : expected) writer.start(name, "double", "", 0); });
    var inventory = open(fixture); var request = id(inventory); var found = new ArrayList<String>(); int pages = 0;
    do {
      var page = call("/source/entries/list", request); assertEquals(page, tool("source_entries_list", request).get("page"));
      for (var row : page.getAsJsonArray("entries")) found.add(row.getAsJsonObject().get("name").getAsString());
      pages++; if (page.get("next_cursor").isJsonNull()) break;
      request = id(inventory); request.add("cursor", page.get("next_cursor"));
    } while (true);
    assertTrue(pages > 1, "The byte ceiling must shorten a page even below 256 names"); assertEquals(expected, found);
  }

  @Test void filtersLeaveRoomForTheirContinuationAndMcpEnvelope() throws Exception {
    var inventory = open(new RecordingFixture(temp.resolve("store"), 2)); var request = id(inventory);
    var types = new JsonArray(); types.add("int64");
    for (int i = 0; i < 15; i++) types.add(i + "x".repeat(3000));
    request.add("types", types); request.addProperty("limit", 1);
    var page = call("/source/entries/list", request); var next = id(inventory); next.add("cursor", page.get("next_cursor"));
    assertEquals(call("/source/entries/list", next), tool("source_entries_list", next).get("page"));
    types.set(0, new JsonPrimitive("x".repeat(3000)));
    var refusal = send("/visualizations/v1/source/entries/list", request.toString(), session);
    assertEquals(400, refusal.statusCode()); assertTrue(refusal.body().contains("continuation"));
  }
}
