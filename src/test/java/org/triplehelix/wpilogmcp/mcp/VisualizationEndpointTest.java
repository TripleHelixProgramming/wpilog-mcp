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
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.visualization.*;

/** Real HTTP and generated records: the editor's service boundary is not a mock backend. */
class VisualizationEndpointTest {
  @TempDir Path temp;
  HttpTransport server;
  ToolRegistry registry;
  String session;
  Set<Path> original;
  final HttpClient http = HttpClient.newHttpClient();
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath();
    var logs = LogManager.getInstance(); original = logs.getConfiguredDirectories();
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    registry = new ToolRegistry(); registry.serverIdentity(ServerIdentity.open(temp.resolve("state")));
    launch();
  }
  void launch() throws Exception {
    server = new HttpTransport(registry, 0); server.start(); session = connect();
  }
  String connect() throws Exception {
    var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/mcp"))
        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}"))
        .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
  }
  @AfterEach void stop() {
    if (server != null) server.stop();
    var logs = LogManager.getInstance(); logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
  }
  String base() { return "http://127.0.0.1:" + server.getPort(); }
  HttpResponse<String> raw(String route, String body, String owner, String origin) throws Exception {
    return rawVersion(1, route, body, owner, origin);
  }
  HttpResponse<String> rawVersion(int version, String route, String body, String owner, String origin) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base() + "/visualizations/v" + version + route))
        .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
        .method(body == null ? "GET" : "POST", body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (owner != null) builder.header("Mcp-Session-Id", owner);
    if (origin != null) builder.header("Origin", origin);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
  JsonObject call(String route, JsonObject body) throws Exception {
    var reply = raw(route, body == null ? null : body.toString(), session, null);
    assertEquals(200, reply.statusCode(), reply.body()); return JsonParser.parseString(reply.body()).getAsJsonObject();
  }
  static JsonObject obj(String key, String value) { var o = new JsonObject(); o.addProperty(key, value); return o; }
  Path fixture(String name, double scale) throws Exception {
    var file = temp.resolve(name + ".wpilog");
    try (var writer = new WpilogWriter(file, "")) {
      int id = writer.start("/value", "double", "", 0);
      // File order differs from clock order; the last equal timestamp wins for cursor values.
      writer.append(id, 2_000_000, WpilogWriter.encodeDouble(4 * scale));
      writer.append(id, 1_000_000, WpilogWriter.encodeDouble(2 * scale));
      writer.append(id, 2_000_000, WpilogWriter.encodeDouble(6 * scale));
    }
    return file;
  }
  JsonObject source(Path path) throws Exception { return call("/source", obj("path", path.toString())).getAsJsonObject("source_ref"); }
  JsonObject create(JsonObject source) throws Exception {
    var body = obj("request_id", UUID.randomUUID().toString()); body.add("source_ref", source);
    return call("/views", body);
  }
  String route(JsonObject view) { return "/views/" + view.get("view_id").getAsString(); }
  /** The workspace's public ID for a session's window; ownership itself stays with the session. */
  String windowId(String owner) throws Exception { return registry.visualizations().views.windowIds().forSession(owner); }

  @Test void remoteApi2SharesSourceReadsAndLibraryEditsButCannotUseLocalAdministration() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var path = fixture("remote", 3);
    var caps = proxyVisualization(2, "/capabilities", null);
    assertEquals(200, caps.statusCode(), caps.body());
    assertTrue(JsonParser.parseString(caps.body()).getAsJsonObject().get("remote_visualization").getAsBoolean());
    var window = proxyVisualization(2, "/window", "{\"decoded_buffer_budget_bytes\":67108864}");
    assertEquals(200, window.statusCode(), window.body());
    assertEquals(67108864, JsonParser.parseString(window.body()).getAsJsonObject().get("decoded_buffer_budget_bytes").getAsLong());
    assertEquals(400, proxyVisualization(2, "/window", "{\"decoded_buffer_budget_bytes\":67108864,\"client_id\":\"other\"}").statusCode());
    assertEquals(403, proxyVisualization(1, "/capabilities", null).statusCode());
    assertEquals(403, proxyVisualization(2, "/library/migrate", "{}").statusCode());
    assertEquals(403, proxyVisualization(2, "/stores", null).statusCode());
    var identified = proxyVisualization(2, "/source", obj("path", path.toString()).toString());
    assertEquals(200, identified.statusCode(), identified.body());
    var ref = JsonParser.parseString(identified.body()).getAsJsonObject().getAsJsonObject("source_ref");
    assertEquals(2, ref.get("schema_version").getAsInt());
    var args = object("source_ref", ref); args.addProperty("entry", "/value");
    var stats = proxyVisualization(2, "/source/statistics", args.toString());
    assertEquals(200, stats.statusCode(), stats.body());
    assertEquals(12, JsonParser.parseString(stats.body()).getAsJsonObject().get("mean").getAsDouble());
    args.addProperty("api_version", 2);
    var message = new JsonObject(); message.addProperty("jsonrpc", "2.0"); message.addProperty("id", 20); message.addProperty("method", "tools/call");
    var params = obj("name", "visualization_source_statistics"); params.add("arguments", args); message.add("params", params);
    var mcp = http.send(HttpRequest.newBuilder(URI.create(base() + "/mcp"))
        .header("Mcp-Session-Id", session).header("Forwarded", "for=192.0.2.1").header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(message.toString())).build(), HttpResponse.BodyHandlers.ofString());
    var text = JsonParser.parseString(mcp.body()).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    var result = JsonParser.parseString(text).getAsJsonObject(); assertEquals("ok", result.get("status").getAsString(), text);
    assertEquals(12, result.getAsJsonObject("statistics").get("mean").getAsDouble());
    var create = object("source_ref", ref); create.addProperty("request_id", UUID.randomUUID().toString());
    var created = proxyVisualization(2, "/views", create.toString()); assertEquals(200, created.statusCode(), created.body());
    var view = JsonParser.parseString(created.body()).getAsJsonObject();
    assertEquals(200, proxyVisualization(2, route(view) + "/attach", "{}").statusCode());
    var save = obj("request_id", UUID.randomUUID().toString()); save.add("revision", view.get("revision")); save.addProperty("title", "Remote layout");
    var saved = proxyVisualization(2, route(view) + "/save", save.toString()); assertEquals(200, saved.statusCode(), saved.body());
    assertTrue(JsonParser.parseString(saved.body()).getAsJsonObject().getAsJsonObject("saved_view_ref").has("library_id"));
    for (String admin : List.of("/directories", "/tba-key", "/pit-credential")) {
      var denied = http.send(HttpRequest.newBuilder(URI.create(base() + admin)).header("Forwarded", "for=192.0.2.1")
          .header("Mcp-Session-Id", session).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(403, denied.statusCode(), admin);
    }
    assertEquals(403, rawVersion(2, "/capabilities", null, session, "https://denied.invalid").statusCode());
  }

  private HttpResponse<String> proxyVisualization(int version, String route, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(base() + "/visualizations/v" + version + route))
        .header("Mcp-Session-Id", session).header("Forwarded", "for=192.0.2.1").header("Content-Type", "application/json")
        .method(body == null ? "GET" : "POST", body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .build(), HttpResponse.BodyHandlers.ofString());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"reduction", "histogram", "scatter"})
  void remoteApi2ChartsUseQualifiedContent(String chart) throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var identified = proxyVisualization(2, "/source", obj("path", fixture("remote-chart", 3).toString()).toString());
    assertEquals(200, identified.statusCode(), identified.body());
    var ref = JsonParser.parseString(identified.body()).getAsJsonObject().getAsJsonObject("source_ref");
    var args = object("source_ref", ref);
    if (chart.equals("scatter")) { args.addProperty("x_entry", "/value"); args.addProperty("y_entry", "/value"); }
    else args.addProperty("entry", "/value");
    var reply = proxyVisualization(2, "/source/" + chart, args.toString());
    assertEquals(200, reply.statusCode(), reply.body());
    var result = JsonParser.parseString(reply.body()).getAsJsonObject();
    assertEquals("ok", result.get("status").getAsString(), result.toString());
    assertEquals(ref, result.getAsJsonObject("inputs").get("source_ref"));
    args.addProperty("api_version", 2);
    assertEquals(result, tool("visualization_source_" + chart, args).get(chart));
  }

  @Test void dataSourceSelectionRefusalRetainsChoicesAndRemedy() throws Exception {
    var path = fixture("unknown-data-scope", 3);
    var choices = LogManager.getInstance().sourceScopes().snapshot();
    var uri = URI.create(base() + "/data/entries?names=/value&format=csv&path="
        + java.net.URLEncoder.encode(path.toString(), java.nio.charset.StandardCharsets.UTF_8)
        + "&scope_id=" + UUID.randomUUID());
    var reply = http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(409, reply.statusCode(), reply.body());
    var body = JsonParser.parseString(reply.body()).getAsJsonObject();
    assertEquals("SOURCE_UNAVAILABLE", body.get("code").getAsString());
    assertEquals(choices.size(), body.getAsJsonArray("source_scopes").size());
    assertEquals(choices.get(0).id(), body.getAsJsonArray("source_scopes").get(0).getAsJsonObject().get("scope_id").getAsString());
    assertTrue(body.get("hint").getAsString().contains("list_source_scopes"));
  }

  @Test void dataSourceMissingFileStillReturnsNotFound() throws Exception {
    var uri = URI.create(base() + "/data/entries?names=/value&path="
        + java.net.URLEncoder.encode(temp.resolve("missing.wpilog").toString(), java.nio.charset.StandardCharsets.UTF_8));
    var reply = http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, reply.statusCode(), reply.body());
  }

  @Test void dataStreamKeepsTheSelectedServerScopeAndSourceEvenForCachedReplies() throws Exception {
    var path = fixture("remote-data", 3); var other = fixture("other-data", 7);
    var identified = proxyVisualization(2, "/source", obj("path", path.toString()).toString());
    var ref = JsonParser.parseString(identified.body()).getAsJsonObject().getAsJsonObject("source_ref");
    String query = "/data/entries?names=/value&format=csv&source_ref=" + java.net.URLEncoder.encode(ref.toString(), java.nio.charset.StandardCharsets.UTF_8);
    java.util.function.Function<Path, URI> url = file -> URI.create(base() + query + "&path=" + java.net.URLEncoder.encode(file.toString(), java.nio.charset.StandardCharsets.UTF_8));
    var reply = http.send(HttpRequest.newBuilder(url.apply(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, reply.statusCode(), reply.body()); assertTrue(reply.body().contains("12.0"), reply.body());
    assertEquals(409, http.send(HttpRequest.newBuilder(url.apply(other)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(409, http.send(HttpRequest.newBuilder(URI.create(url.apply(path) + "&server_instance_id=" + UUID.randomUUID())).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(url.apply(path) + "&scope_id=" + UUID.randomUUID())).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    java.nio.file.Files.writeString(path, "changed synthetic bytes");
    var stale = http.send(HttpRequest.newBuilder(url.apply(path)).header("If-None-Match", reply.headers().firstValue("ETag").orElseThrow()).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(409, stale.statusCode(), stale.body());
  }

  @Test void serverLibrarySharesHttpMcpPersistenceWithoutAnyLogStoreDestination() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var body = obj("request_id", UUID.randomUUID().toString()); body.addProperty("name", "Library parity"); body.addProperty("description", "Synthetic"); body.add("configuration", VisualizationContracts.blank());
    var reply = rawVersion(2, "/templates/create", body.toString(), session, null); assertEquals(200, reply.statusCode(), reply.body());
    var created = JsonParser.parseString(reply.body()).getAsJsonObject(); var get = object("template_ref", created.get("template_ref")); get.addProperty("api_version", 2);
    assertEquals(created, tool("visualization_template_get", get).get("definition"));
    get.addProperty("api_version", 1);
    assertEquals(400, rawVersion(1, "/templates/get", get.toString(), session, null).statusCode());
    assertEquals("error", tool("visualization_template_get", get).get("status").getAsString());
    get.addProperty("api_version", 2);
    body.addProperty("unexpected", true); assertEquals(400, rawVersion(2, "/templates/create", body.toString(), session, null).statusCode()); body.remove("unexpected");
    body.addProperty("store_id", UUID.randomUUID().toString()); assertEquals(400, rawVersion(2, "/templates/create", body.toString(), session, null).statusCode());
    var view = create(source(fixture("library", 1))); call(route(view) + "/attach", new JsonObject());
    var save = obj("request_id", UUID.randomUUID().toString()); save.add("revision", view.get("revision")); save.addProperty("title", "No store");
    var savedReply = rawVersion(2, route(view) + "/save", save.toString(), session, null); assertEquals(200, savedReply.statusCode(), savedReply.body());
    var saved = JsonParser.parseString(savedReply.body()).getAsJsonObject(); assertTrue(saved.getAsJsonObject("saved_view_ref").has("library_id"));
    assertEquals(400, rawVersion(1, route(view), null, session, null).statusCode());
    assertEquals(saved, JsonParser.parseString(rawVersion(2, route(view) + "/save", save.toString(), session, null).body()));
    var open = object("saved_view_ref", saved.get("saved_view_ref")); open.addProperty("api_version", 2); open.addProperty("request_id", UUID.randomUUID().toString());
    var reopened = tool("visualization_saved_view_open", open); assertEquals("ok", reopened.get("status").getAsString());
    assertEquals(saved.get("configuration"), reopened.getAsJsonObject("view").get("configuration"));
    var list = obj("kind", "view"); list.addProperty("api_version", 2); list.addProperty("limit", 1);
    var listed = tool("visualization_library_list", list); assertEquals(1, listed.getAsJsonObject("catalog").get("total_matching").getAsInt());
    assertEquals(1, listed.getAsJsonObject("limits").getAsJsonObject("catalog.items").get("total").getAsInt());
    list.addProperty("query", "missing"); assertEquals("no_match", tool("visualization_library_list", list).get("status").getAsString());
    assertEquals(403, rawVersion(2, "/library/list", list.toString(), session, "https://foreign.invalid").statusCode());
    assertEquals(400, rawVersion(2, "/library/list", "{\"kind\":\"view\",\"kind\":\"template\"}", session, null).statusCode());
    LogManager.getInstance().clearAllowedDirectories(); LogManager.getInstance().addAllowedDirectory(temp.resolve("state"));
    var refused = tool("visualization_saved_view_open", open); assertEquals("error", refused.get("status").getAsString());
    assertFalse(Files.exists(temp.resolve("store.json")));
  }

  @Test void libraryTemplateLimitsCountTemplatesSeparatelyFromUnavailableRows() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var directory = Files.createDirectories(temp.resolve("state/library/objects"));
    var damaged = directory.resolve("template-" + UUID.randomUUID() + ".json");
    Files.writeString(damaged, "broken synthetic record");
    var args = obj("query", ""); args.addProperty("api_version", 2);
    var reply = tool("visualization_template_list", args); var catalog = reply.getAsJsonObject("catalog");
    var limits = reply.getAsJsonObject("limits").getAsJsonObject("catalog.templates");
    assertEquals(catalog.getAsJsonArray("templates").size(), limits.get("returned").getAsInt());
    assertEquals(catalog.getAsJsonArray("templates").size(), limits.get("total").getAsInt());
    assertEquals(1, catalog.getAsJsonArray("unreadable").size()); assertEquals("partial", reply.get("status").getAsString());
    assertEquals("broken synthetic record", Files.readString(damaged));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "http, 1, false, false", "mcp, 1, false, false",
      "http, 1, true, false", "mcp, 1, true, false",
      "http, 2, false, false", "mcp, 2, false, false",
      "http, 2, false, true", "mcp, 2, false, true"})
  void nestedDirectorySourceSelectionKeepsLegacyAbsolutePaths(String adapter, int version,
      boolean relative, boolean selected) throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    // Keep the fixture on the working directory's volume so its relative spelling works on Windows.
    var outer = Files.createTempDirectory(Path.of("build"), "nested-source-").toRealPath();
    var inner = Files.createDirectory(outer.resolve("2026"));
    var logs = LogManager.getInstance();
    try {
      var path = Files.move(fixture("nested", 3), inner.resolve("source.wpilog"));
      logs.clearAllowedDirectories(); logs.addAllowedDirectory(outer); logs.addAllowedDirectory(inner);
      String scopeId = logs.sourceScopes().snapshot().stream().filter(s -> s.root().equals(inner))
          .findFirst().orElseThrow().id();
      var request = obj("path", (relative ? Path.of("").toRealPath().relativize(path) : path).toString());
      if (selected) request.addProperty("scope_id", scopeId);
      boolean accepted = version == 1 ? !relative : selected;
      JsonObject result;
      if (adapter.equals("http")) {
        var reply = rawVersion(version, "/source", request.toString(), session, null);
        assertEquals(accepted ? 200 : 409, reply.statusCode(), reply.body());
        result = JsonParser.parseString(reply.body()).getAsJsonObject();
      } else {
        request.addProperty("api_version", version);
        result = tool("visualization_source_inspect", request);
        assertEquals(accepted ? "ok" : "error", result.get("status").getAsString(), result.toString());
        if (accepted) result = result.getAsJsonObject("source");
      }
      if (accepted) {
        var reference = result.getAsJsonObject("source_ref");
        if (version == 1) assertFalse(reference.has("schema_version"));
        else assertEquals(scopeId, reference.get("scope_id").getAsString());
      } else {
        assertEquals("SOURCE_SELECTION_REQUIRED", result.get("code").getAsString(), result.toString());
        assertEquals(2, result.getAsJsonArray("source_scopes").size());
        if (version == 1) {
          String hint = result.get("hint").getAsString();
          assertTrue(hint.contains("absolute path"), hint);
          assertTrue(hint.contains("list_available_logs"), hint);
          assertTrue(hint.contains("api_version 2 with scope_id"), hint);
        }
      }
    } finally {
      logs.release(outer);
      try (var files = Files.walk(outer)) {
        for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
      }
    }
  }

  @Test void qualifiedSourcesHaveHttpMcpParityWithoutRelaxingV1OrRemoteAdmission() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var first = Files.createDirectory(temp.resolve("first"));
    var second = Files.createDirectory(temp.resolve("second"));
    var path = Files.move(fixture("first", 1), first.resolve("same.wpilog"));
    Files.move(fixture("second", 10), second.resolve("same.wpilog"));
    var logs = LogManager.getInstance(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(first); logs.addAllowedDirectory(second);
    var choices = rawVersion(2, "/source/scopes", "{}", session, null);
    assertEquals(200, choices.statusCode(), choices.body());
    var scopes = JsonParser.parseString(choices.body()).getAsJsonObject().getAsJsonArray("source_scopes");
    assertEquals(2, scopes.size());
    for (String invalid : List.of("{\"offset\":0.5}", "{\"limit\":\"2\"}", "{\"unknown\":1}"))
      assertEquals(400, rawVersion(2, "/source/scopes", invalid, session, null).statusCode());
    String scope = java.util.stream.StreamSupport.stream(scopes.spliterator(), false).map(JsonElement::getAsJsonObject)
        .filter(row -> row.get("path").getAsString().equals(first.toString())).findFirst().orElseThrow().get("scope_id").getAsString();
    var identify = obj("path", path.toString()); identify.addProperty("scope_id", scope);
    var identified = rawVersion(2, "/source", identify.toString(), session, null);
    assertEquals(200, identified.statusCode(), identified.body());
    var ref = JsonParser.parseString(identified.body()).getAsJsonObject().getAsJsonObject("source_ref");
    assertEquals(2, ref.get("schema_version").getAsInt()); assertEquals(scope, ref.get("scope_id").getAsString());
    identify.addProperty("api_version", 2);
    var inspection = tool("visualization_source_inspect", identify);
    assertEquals(2, inspection.get("api_version").getAsInt());
    assertEquals(ref, inspection.getAsJsonObject("source").get("source_ref"));
    var args = object("source_ref", ref); args.addProperty("api_version", 2); args.addProperty("entry", "/value");
    var response = rawVersion(2, "/source/statistics", args.toString(), session, null);
    assertEquals(200, response.statusCode(), response.body());
    var stats = JsonParser.parseString(response.body()).getAsJsonObject();
    assertEquals(4.0, stats.get("mean").getAsDouble()); assertEquals(3, stats.get("count").getAsInt());
    assertEquals(stats, tool("visualization_source_statistics", args).get("statistics"));
    assertEquals(400, rawVersion(1, "/source/statistics", args.toString(), session, null).statusCode());
    args.addProperty("api_version", 1);
    assertEquals("error", tool("visualization_source_statistics", args).get("status").getAsString());
    args.addProperty("api_version", 2);
    assertEquals(403, rawVersion(2, "/source/statistics", args.toString(), session, "https://denied.invalid").statusCode());
    var create = object("source_ref", ref); create.addProperty("request_id", UUID.randomUUID().toString());
    var created = rawVersion(2, "/views", create.toString(), session, null);
    assertEquals(200, created.statusCode(), created.body());
    var view = JsonParser.parseString(created.body()).getAsJsonObject();
    assertEquals(400, rawVersion(1, route(view), null, session, null).statusCode());
    var get = obj("view_id", view.get("view_id").getAsString()); get.addProperty("api_version", 1);
    assertEquals("error", tool("visualization_view_get", get).get("status").getAsString());
    var update = get.deepCopy(); update.addProperty("expected_revision", 1);
    update.addProperty("request_id", UUID.randomUUID().toString()); update.addProperty("configuration_json", VisualizationContracts.blank().toString());
    assertEquals("error", tool("visualization_view_update", update).get("status").getAsString());
    get.addProperty("api_version", 2);
    assertEquals(1, tool("visualization_view_get", get).getAsJsonObject("view").get("revision").getAsInt(), "Refused v1 edit made no mutation");
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(second);
    assertEquals(409, rawVersion(2, "/source/statistics", args.toString(), session, null).statusCode());
    var refused = tool("visualization_source_statistics", args);
    assertEquals("SOURCE_UNAVAILABLE", refused.get("code").getAsString()); assertEquals(2, refused.get("api_version").getAsInt());
  }

  @Test void windowBudgetsAreReportedSeparatelyAndExpireWithTheirSession() throws Exception {
    var caps = call("/capabilities", null);
    assertTrue(caps.has("decoded_buffer_budget"), "Publish the client allowance contract");
    var budget = caps.getAsJsonObject("decoded_buffer_budget");
    assertEquals(64L << 20, budget.get("default_bytes").getAsLong());
    assertEquals(16L << 20, budget.get("minimum_bytes").getAsLong());
    assertEquals(1024L << 20, budget.get("maximum_bytes").getAsLong());
    assertTrue(caps.getAsJsonArray("attached_windows").isEmpty());
    var request = new JsonObject(); request.addProperty("decoded_buffer_budget_bytes", 16L << 20);
    var own = call("/window", request);
    assertEquals(windowId(session), own.get("client_id").getAsString());
    assertTrue(own.get("current_session").getAsBoolean());
    assertEquals("client_reported", own.get("basis").getAsString());
    assertEquals(16L << 20, own.get("decoded_buffer_budget_bytes").getAsLong());
    String first = session; session = connect(); request.addProperty("decoded_buffer_budget_bytes", 128L << 20);
    var second = call("/window", request);
    var windows = call("/capabilities", null).getAsJsonArray("attached_windows");
    assertEquals(2, windows.size());
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var args = new JsonObject(); args.addProperty("api_version", 1);
    assertEquals(windows, tool("visualization_capabilities_get", args).getAsJsonObject("capabilities").get("attached_windows"));
    http.send(HttpRequest.newBuilder(URI.create(base()+"/mcp")).header("Mcp-Session-Id",first).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    windows = call("/capabilities", null).getAsJsonArray("attached_windows");
    assertEquals(1, windows.size()); assertEquals(second, windows.get(0));
    assertEquals(404, raw("/window", request.toString(), first, null).statusCode());
    server.stop(); launch(); assertTrue(call("/capabilities", null).getAsJsonArray("attached_windows").isEmpty());
  }

  /** Review 18: a session ID is a bearer credential for every session-bound handle and quota.
   * Reports may say which window holds what, but must give another client nothing it could present
   * as that session. The reviewer listed a peer's paged inventory with an ID read from capabilities. */
  @Test void peerReportsNeverDiscloseASessionIdThatActsAsThatSession() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var recording = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("peer-recording"), 4);
    String first = session;
    var allowance = new JsonObject(); allowance.addProperty("decoded_buffer_budget_bytes", 64L << 20);
    var firstWindow = call("/window", allowance);
    var view = create(source(fixture("peer", 1))); var attached = call(route(view) + "/attach", new JsonObject());
    var open = object("source_ref", recording.reference); open.addProperty("inventory_mode", "paged");
    var opened = call("/source/describe", open); var inventory = object("inventory_id", opened.get("inventory_id"));
    var selfReplies = new LinkedHashMap<String, String>(); // the first session's replies to itself
    selfReplies.put("POST /window", firstWindow.toString()); selfReplies.put("POST /views/<id>/attach", attached.toString());
    selfReplies.put("POST /source/describe", opened.toString());
    assertEquals(200, raw("/source/entries/list", inventory.toString(), first, null).statusCode());

    session = connect(); // another window, or an agent connected over HTTP
    var secondWindow = call("/window", allowance);
    var args = new JsonObject(); args.addProperty("api_version", 1);
    var viewArgs = obj("view_id", view.get("view_id").getAsString()); viewArgs.addProperty("api_version", 1);
    var edit = viewArgs.deepCopy(); edit.addProperty("expected_revision", 1); edit.addProperty("request_id", UUID.randomUUID().toString());
    edit.addProperty("configuration_json", VisualizationContracts.blank().toString());
    var replies = new LinkedHashMap<String, String>();
    replies.put("GET /capabilities", raw("/capabilities", null, session, null).body());
    replies.put("POST /window", secondWindow.toString());
    replies.put("GET /views/<id>", raw(route(view), null, session, null).body());
    replies.put("POST /source/views", raw("/source/views", object("source_ref", view.get("source_ref")).toString(), session, null).body());
    replies.put("visualization_capabilities_get", tool("visualization_capabilities_get", args).toString());
    replies.put("visualization_view_get", tool("visualization_view_get", viewArgs).toString());
    replies.put("visualization_view_list", tool("visualization_view_list", args).toString());
    replies.put("visualization_view_update", tool("visualization_view_update", edit).toString());
    // Every window identifier the second client was given must fail as a session header.
    var caps = JsonParser.parseString(replies.get("GET /capabilities")).getAsJsonObject();
    var identifiers = new LinkedHashSet<String>();
    caps.getAsJsonArray("attached_windows").forEach(w -> identifiers.add(w.getAsJsonObject().get("client_id").getAsString()));
    var seen = JsonParser.parseString(replies.get("GET /views/<id>")).getAsJsonObject().get("attached_client_id");
    if (seen != null && !seen.isJsonNull()) identifiers.add(seen.getAsString());
    var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
    replies.forEach((name, body) -> checks.add(() -> assertFalse(body.contains(first), name + " discloses the first session's ID: " + body)));
    for (var id : identifiers) checks.add(() -> assertEquals(404, raw("/source/entries/list", inventory.toString(), id, null).statusCode(),
        "reported window identifier " + id + " lists the first session's inventory"));
    // Not even a caller's own session ID is echoed: a reply can be logged or pasted elsewhere.
    replies.forEach((name, body) -> checks.add(() -> assertFalse(body.contains(session), name + " echoes the caller's own session ID")));
    selfReplies.forEach((name, body) -> checks.add(() -> assertFalse(body.contains(first), "first " + name + " echoes the caller's own session ID")));
    assertAll(checks);

    // Window IDs still say which window holds what, and the caller can recognize its own window.
    var windows = caps.getAsJsonArray("attached_windows"); assertEquals(2, windows.size());
    var own = windows.asList().stream().map(JsonElement::getAsJsonObject).filter(w -> w.get("current_session").getAsBoolean()).toList();
    var peer = windows.asList().stream().map(JsonElement::getAsJsonObject).filter(w -> !w.get("current_session").getAsBoolean()).toList();
    assertEquals(1, own.size()); assertEquals(1, peer.size());
    assertEquals(secondWindow.get("client_id"), own.get(0).get("client_id"));
    assertTrue(secondWindow.get("current_session").getAsBoolean());
    assertEquals(firstWindow.get("client_id"), peer.get(0).get("client_id"), "a window keeps its ID for its session");
    assertEquals(peer.get(0).get("client_id"), seen, "a view attachment names the window by the same ID");
    assertEquals(windows, JsonParser.parseString(replies.get("visualization_capabilities_get")).getAsJsonObject()
        .getAsJsonObject("capabilities").get("attached_windows"), "HTTP and MCP share the caller-scoped report");
    assertFalse(caps.has("client_id"), "capabilities do not echo a transport session");
    // Ownership is still bound to the transport session, never to a window ID.
    assertEquals(403, raw("/source/entries/list", inventory.toString(), session, null).statusCode());
    assertEquals(409, raw(route(view) + "/attach", "{}", session, null).statusCode());
    assertEquals(200, raw("/source/entries/list", inventory.toString(), first, null).statusCode());
    var firstView = raw("/capabilities", null, first, null).body();
    assertFalse(firstView.contains(session), "the first window cannot read the second session either");
    assertTrue(JsonParser.parseString(firstView).getAsJsonObject().getAsJsonArray("attached_windows").asList().stream()
        .anyMatch(w -> w.getAsJsonObject().get("current_session").getAsBoolean() && w.getAsJsonObject().get("client_id").equals(firstWindow.get("client_id"))));
    assertTrue(LogManager.getInstance().release(recording.root).released());
  }

  @Test void windowBudgetRegistrationRejectsSpoofingAndInvalidAllowances() throws Exception {
    for (var value : List.of("0", "16777215", "1073741825", "16777216.5", "\"67108864\"", "null", "true")) {
      var response = raw("/window", "{\"decoded_buffer_budget_bytes\":"+value+"}", session, null);
      assertEquals(400, response.statusCode(), response.body());
    }
    assertEquals(400, raw("/window", "{\"decoded_buffer_budget_bytes\":67108864,\"client_id\":\"other\"}", session, null).statusCode());
    assertEquals(403, raw("/window", "{\"decoded_buffer_budget_bytes\":67108864}", session, "https://untrusted.invalid").statusCode());
    assertTrue(call("/capabilities", null).getAsJsonArray("attached_windows").isEmpty());
  }

  @Test void denseHistoryKeepsEarlyExtremesAndEveryMemberWithHttpMcpParity() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var recording = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("dense-recording"), 0);
    for (int member = 0; member < 2; member++) {
      int part = member;
      recording.rewrite(member, w -> {
        int id = w.start("/value", "double", "", 0);
        // Backward physical timestamps force the shared chronological ordering. The spike
        // lies outside the last 4096 samples, and equal times span both recording members.
        for (int i = 4999; i >= 0; i--) w.append(id, i * 1000L, WpilogWriter.encodeDouble(i == 10 && part == 0 ? 10000 : part * 2));
      });
    }
    call("/stores", null);
    var args = object("source_ref", recording.reference); args.addProperty("api_version", 1); args.addProperty("entry", "/value");
    args.addProperty("start_time", 0); args.addProperty("end_time", 5); args.addProperty("max_points", 5);
    var result = call("/source/reduction", args);
    assertEquals("ok", result.get("status").getAsString()); assertEquals(10000, result.get("sample_count").getAsInt());
    assertEquals(10000, result.get("finite_count").getAsInt()); assertEquals(10000, result.get("records_visited").getAsInt());
    var buckets = result.getAsJsonArray("buckets"); assertEquals(5, buckets.size());
    for (int i = 0; i < 5; i++) {
      var b = buckets.get(i).getAsJsonObject();
      assertEquals(Long.toString(i * 1_000_000L), b.get("start_us").getAsString());
      assertEquals(Long.toString((i + 1) * 1_000_000L), b.get("end_us").getAsString());
      assertEquals(2000, b.get("count").getAsInt()); assertEquals(2000, b.get("finite_count").getAsInt());
      assertEquals(0, b.get("min").getAsDouble()); assertEquals(i == 0 ? 10000 : 2, b.get("max").getAsDouble());
      assertEquals(i == 0 ? 6 : 1, b.get("mean").getAsDouble());
      assertEquals(0, b.get("first").getAsDouble()); assertEquals(2, b.get("last").getAsDouble());
    }
    assertEquals(recording.reference, result.getAsJsonObject("inputs").get("source_ref"));
    assertEquals(2, result.getAsJsonObject("prefix").getAsJsonArray("members").size());
    assertEquals(result, tool("visualization_source_reduction", args).get("reduction"));
    assertTrue(call("/capabilities", null).get("source_reduction").getAsBoolean());
  }

  @Test void reducedHistoryEmptyAndUnsafeValuesAreExplicitWithInclusiveEdges() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var path = temp.resolve("reduction-gaps.wpilog");
    try (var w = new WpilogWriter(path, "")) {
      int id = w.start("/value", "int64", "", 0);
      w.append(id, 1_000_000, WpilogWriter.encodeInt64(9007199254740993L));
      w.append(id, 2_000_000, WpilogWriter.encodeInt64(4));
    }
    var args = object("source_ref", source(path)); args.addProperty("api_version", 1); args.addProperty("entry", "/value");
    args.addProperty("start_time", 1); args.addProperty("end_time", 2); args.addProperty("max_points", 2);
    var result = call("/source/reduction", args); assertEquals(2, result.get("sample_count").getAsInt());
    assertEquals(1, result.get("finite_count").getAsInt()); assertEquals(1, result.get("unplottable_count").getAsInt());
    var first = result.getAsJsonArray("buckets").get(0).getAsJsonObject();
    for (var key : List.of("min", "max", "mean", "first", "last")) assertTrue(first.get(key).isJsonNull(), key);
    assertEquals(4, result.getAsJsonArray("buckets").get(1).getAsJsonObject().get("mean").getAsInt());
    args.addProperty("start_time", 3); args.addProperty("end_time", 4);
    var empty = call("/source/reduction", args); assertEquals("no_match", empty.get("status").getAsString());
    assertEquals(0, empty.get("sample_count").getAsInt()); assertTrue(empty.getAsJsonArray("buckets").isEmpty());
    assertTrue(empty.has("hint")); assertEquals("no_match", tool("visualization_source_reduction", args).get("status").getAsString());
  }

  @Test void reductionRelativeWindowUsesTheSharedSourceClockAndKeepsInputImmutable() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var args = object("source_ref", source(fixture("reduction-relative", 1))); args.addProperty("api_version", 1); args.addProperty("entry", "/value");
    args.addProperty("last_seconds", .5); args.addProperty("max_points", 1); var before = args.deepCopy();
    var result = call("/source/reduction", args); assertEquals(2, result.get("sample_count").getAsInt());
    assertEquals("1500000", result.getAsJsonObject("window").get("start_us").getAsString()); assertEquals("2000000", result.getAsJsonObject("window").get("end_us").getAsString());
    var b = result.getAsJsonArray("buckets").get(0).getAsJsonObject(); assertEquals(5, b.get("mean").getAsDouble()); assertEquals(4, b.get("min").getAsDouble()); assertEquals(6, b.get("max").getAsDouble());
    assertEquals(before, args); assertEquals(result, tool("visualization_source_reduction", args).get("reduction"));
    args.addProperty("start_time", 1); assertEquals(400, raw("/source/reduction", args.toString(), session, null).statusCode()); args.remove("start_time");
    for (var invalid : List.of("0", "-1", "true", "\"1\"", "1e400")) { args.add("last_seconds", JsonParser.parseString(invalid)); assertEquals(400, raw("/source/reduction", args.toString(), session, null).statusCode()); }
  }

  @Test void reductionLeavesKeepAvailableArrayElementsAndAccountForMissingOnBothAdapters() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var recording = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("array-leaves"), 0);
    recording.rewrite(0, w -> {
      int id = w.start("/n", "double[]", "", 0);
      w.append(id, 1_000_000, java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(3).putDouble(5).array());
      w.append(id, 2_000_000, new byte[0]);
    });
    recording.rewrite(1, w -> {
      int id = w.start("/n", "double[]", "", 0);
      w.append(id, 3_000_000, java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(7).putDouble(11).array());
    });
    call("/stores", null);
    var args = object("source_ref", recording.reference); args.addProperty("api_version", 1); args.addProperty("entry", "/n"); args.addProperty("field", "[1]"); args.addProperty("max_points", 1);
    var result = call("/source/reduction", args); assertEquals(result, tool("visualization_source_reduction", args).get("reduction"));
    assertEquals(3, result.get("sample_count").getAsInt()); assertEquals(2, result.get("finite_count").getAsInt()); assertEquals(1, result.get("unplottable_count").getAsInt());
    var bucket = result.getAsJsonArray("buckets").get(0).getAsJsonObject();
    assertEquals(5, bucket.get("min").getAsDouble()); assertEquals(11, bucket.get("max").getAsDouble()); assertEquals(8, bucket.get("mean").getAsDouble());
    args.addProperty("start_time", 2); args.addProperty("end_time", 2);
    result = call("/source/reduction", args); assertEquals(result, tool("visualization_source_reduction", args).get("reduction"));
    assertEquals(1, result.get("sample_count").getAsInt()); assertEquals(0, result.get("finite_count").getAsInt()); assertEquals(1, result.get("unplottable_count").getAsInt());
    bucket = result.getAsJsonArray("buckets").get(0).getAsJsonObject();
    for (String key : List.of("min", "max", "mean", "first", "last")) assertTrue(bucket.get(key).isJsonNull(), key);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"int64", "enum", "uint64", "uint64_enum"})
  void reductionLeavesCheckExactIntegerBoundariesBeforeConversionOnBothAdapters(String type) throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    long safe = 9_007_199_254_740_991L;
    boolean unsigned = type.startsWith("uint64");
    long[] values = unsigned ? new long[]{0, safe - 1, safe, safe + 1, safe + 2, Long.MAX_VALUE, -1}
        : new long[]{-safe - 2, -safe - 1, -safe, -safe + 1, 0, 1, safe - 1, safe, safe + 1, safe + 2};
    var recording = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("integral-leaves"), 0);
    for (int member = 0; member < 2; member++) {
      int part = member;
      recording.rewrite(member, w -> {
        if (!type.equals("int64")) {
          int schema = w.start("/.schema/struct:Scalar", "structschema", "", 0);
          w.append(schema, 0, ((type.contains("enum") ? "enum {Off=0, On=1} " : "") + (unsigned ? "uint64" : "int64") + " x;").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        int id = w.start("/n", type.equals("int64") ? "int64" : "struct:Scalar", "", 0);
        for (int i = part; i < values.length; i += 2) w.append(id, i * 1_000_000L, WpilogWriter.encodeInt64(values[i]));
      });
    }
    call("/stores", null);
    var args = object("source_ref", recording.reference); args.addProperty("api_version", 1); args.addProperty("entry", "/n");
    if (!type.equals("int64")) args.addProperty("field", ".x");
    args.addProperty("start_time", 0); args.addProperty("end_time", values.length); args.addProperty("max_points", values.length);
    var result = call("/source/reduction", args); assertEquals(result, tool("visualization_source_reduction", args).get("reduction"));
    assertEquals(values.length, result.get("sample_count").getAsInt()); assertEquals(values.length, result.getAsJsonArray("buckets").size());
    int finite = 0;
    for (int i = 0; i < values.length; i++) {
      boolean exact = values[i] >= -safe && values[i] <= safe && (!unsigned || values[i] >= 0);
      if (exact) finite++;
      var bucket = result.getAsJsonArray("buckets").get(i).getAsJsonObject();
      assertEquals(1, bucket.get("count").getAsInt()); assertEquals(exact ? 1 : 0, bucket.get("finite_count").getAsInt(), "ordinal " + i);
      for (String key : List.of("min", "max", "mean", "first", "last")) {
        if (exact) assertEquals((double) values[i], bucket.get(key).getAsDouble(), key + " ordinal " + i);
        else assertTrue(bucket.get(key).isJsonNull(), key + " ordinal " + i);
      }
    }
    assertEquals(finite, result.get("finite_count").getAsInt()); assertEquals(values.length - finite, result.get("unplottable_count").getAsInt());
    var rawArgs = object("source_ref", recording.reference); rawArgs.addProperty("entry", "/n");
    if (!type.equals("int64")) rawArgs.addProperty("field", ".x");
    var exactValue = call("/source/value", rawArgs).getAsJsonObject("value");
    assertEquals(unsigned ? "18446744073709551615" : "9007199254740993", exactValue.get("value").getAsString(),
        "Rejecting unsafe plot values must leave their original raw integers available");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"int64", "uint64"})
  void reductionLeavesUseNumericEnumMeansAcrossMembers(String type) throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var recording = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("enum-means"), 0);
    for (int member = 0; member < 2; member++) {
      int value = member;
      recording.rewrite(member, w -> {
        int schema = w.start("/.schema/struct:Scalar", "structschema", "", 0);
        w.append(schema, 0, ("enum {Off=0, On=1} " + type + " x;").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int id = w.start("/n", "struct:Scalar", "", 0); w.append(id, (value + 1) * 1_000_000L, WpilogWriter.encodeInt64(value));
      });
    }
    call("/stores", null);
    var args = object("source_ref", recording.reference); args.addProperty("api_version", 1);
    args.addProperty("entry", "/n"); args.addProperty("field", ".x"); args.addProperty("max_points", 1);
    var result = call("/source/reduction", args); assertEquals(result, tool("visualization_source_reduction", args).get("reduction"));
    var bucket = result.getAsJsonArray("buckets").get(0).getAsJsonObject();
    assertEquals(2, bucket.get("count").getAsInt()); assertEquals(2, bucket.get("finite_count").getAsInt());
    assertEquals(0, bucket.get("min").getAsDouble()); assertEquals(1, bucket.get("max").getAsDouble());
    assertEquals(.5, bucket.get("mean").getAsDouble()); assertEquals(0, result.get("unplottable_count").getAsInt());
  }

  @Test void reductionRequestLimitsAndAuthorityAreTheSameOnBothAdapters() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var r = object("source_ref", source(fixture("reduction-admission", 1))); r.addProperty("api_version", 1); r.addProperty("entry", "/value");
    assertEquals(3, call("/source/reduction", r).get("sample_count").getAsInt());
    assertEquals(403, raw("/source/reduction", r.toString(), session, "https://untrusted.invalid").statusCode());
    assertEquals(400, raw("/source/reduction", r.toString(), null, null).statusCode());
    assertEquals(400, raw("/source/reduction", "{broken", session, null).statusCode());
    r.addProperty("max_points", 0); assertEquals(400, raw("/source/reduction", r.toString(), session, null).statusCode());
    assertEquals("error", tool("visualization_source_reduction", r).get("status").getAsString()); r.remove("max_points");
    assertEquals(413, raw("/source/reduction", " ".repeat(65536) + r, session, null).statusCode());
    var rpc = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"visualization_source_reduction\",\"arguments\":" + r + "}}";
    var reply = http.send(HttpRequest.newBuilder(URI.create(base() + "/mcp")).header("Content-Type", "application/json").header("Mcp-Session-Id", session)
        .POST(HttpRequest.BodyPublishers.ofString(" ".repeat(65536) + rpc)).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, reply.statusCode());
    var envelope = JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("result");
    assertTrue(envelope.get("isError").getAsBoolean()); assertTrue(reply.body().contains("64 KiB"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"statistics,file", "histogram,file", "scatter,file", "reduction,file",
      "statistics,finalized", "histogram,finalized", "scatter,finalized", "reduction,finalized",
      "statistics,clock", "histogram,clock", "scatter,clock", "reduction,clock",
      "statistics,no-clock", "histogram,no-clock", "scatter,no-clock", "reduction,no-clock",
      "statistics,negative-clock", "histogram,negative-clock", "scatter,negative-clock", "reduction,negative-clock"})
  void emptySourceRelativeWindowsRecoverAfterFirstSampleOnBothAdapters(String operation, String mode) throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var recording = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("empty-relative"), 0);
    boolean active = !mode.equals("file") && !mode.equals("finalized");
    var clock = new java.util.concurrent.atomic.AtomicReference<Double>(mode.equals("no-clock") ? null : mode.equals("negative-clock") ? -3_000_000.0 : 3_000_000.0);
    var path = recording.directory.resolve("capture.wpilog");
    org.triplehelix.wpilogmcp.capture.CaptureWriter writer = null;
    var topic = new org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce("/x", 1, "int", null, new JsonObject());
    try {
      if (active) {
        Files.delete(path);
        var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex((address, start) -> path, LogManager.getInstance(), 10_000_000, clock::get);
        writer = new org.triplehelix.wpilogmcp.capture.CaptureWriter(java.time.Clock.fixed(java.time.Instant.parse(org.triplehelix.wpilogmcp.fixtures.RecordingFixture.WHEN), java.time.ZoneOffset.UTC),
            new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler(), org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, index);
        writer.connected(org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1", 5810, "empty-fixture"), "networktables.first.wpi.edu");
        writer.timeSync(1_000_000, 0); writer.announce(topic);
        var manifest = recording.directory.resolve("session.json"); var state = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        var old = state.getAsJsonArray("files").remove(0).getAsJsonObject();
        var provenance = org.triplehelix.wpilogmcp.store.StoreJson.JSON.fromJson(old.get("provenance"), org.triplehelix.wpilogmcp.store.StoreManifest.Provenance.class);
        var open = org.triplehelix.wpilogmcp.store.StoreJson.JSON.toJsonTree(new org.triplehelix.wpilogmcp.store.StoreManifest.OpenCapture("capture.wpilog", provenance, Files.size(path), 0, 0));
        state.add("open_capture", open); Files.writeString(manifest, state.toString()); LogManager.getInstance().catalogPublished();
      } else recording.rewrite(0, w -> w.start("NT:/x", "int64", "", 0));
      call("/stores", null);
      var source = mode.equals("file") ? source(path) : recording.reference;
      var args = object("source_ref", source); args.addProperty("api_version", 1); args.addProperty("last_seconds", 30);
      if (operation.equals("scatter")) { args.addProperty("x_entry", "NT:/x"); args.addProperty("y_entry", "NT:/x"); }
      else args.addProperty("entry", "NT:/x");
      var result = call("/source/" + operation, args);
      assertEquals("no_match", result.get("status").getAsString()); assertTrue(result.has("hint"));
      var mcp = tool("visualization_source_" + operation, args); assertEquals("no_match", mcp.get("status").getAsString()); assertEquals(result, mcp.get(operation));
      assertFalse(result.has("session_time_range"), "No observed time span may be invented for an empty recording");
      if (operation.equals("statistics") || operation.equals("reduction")) {
        var window = operation.equals("reduction") ? result.getAsJsonObject("window") : result.getAsJsonObject("inputs").getAsJsonObject("window");
        double end = window.get(operation.equals("reduction") ? "end_us" : "end").getAsDouble() / (operation.equals("reduction") ? 1e6 : 1);
        assertEquals(active && clock.get() != null ? clock.get() / 1e6 : 0, end);
      } else {
        assertTrue(result.getAsJsonObject("prefix").getAsJsonObject("clock").get("last_sample_time_sec").isJsonNull());
        for (var window : result.getAsJsonObject("inputs").getAsJsonObject("window").getAsJsonArray("windows"))
          for (var bound : window.getAsJsonArray()) assertTrue(Double.isFinite(bound.getAsDouble()));
      }
      if (active) {
        long timestamp = mode.equals("negative-clock") ? -3_500_000 : 2_500_000;
        writer.value(topic, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, timestamp, 2, 41L), 0);
        result = call("/source/" + operation, args); assertEquals("ok", result.get("status").getAsString(), result.toString());
        assertEquals(result, tool("visualization_source_" + operation, args).get(operation));
        if (operation.equals("statistics")) { assertEquals(1, result.get("count").getAsInt()); assertEquals(41, result.get("mean").getAsDouble()); }
        if (operation.equals("reduction")) { assertEquals(1, result.get("finite_count").getAsInt()); assertEquals(41, result.getAsJsonArray("buckets").get(0).getAsJsonObject().get("mean").getAsDouble()); }
      }
    } finally { if (writer != null) writer.close(); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"histogram", "scatter"})
  void activeChartNullRelativeArgumentMatchesOmittedOnBothAdapters(String operation) throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var recording = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("active-null"), 2);
    var path = recording.directory.resolve("capture.wpilog"); Files.delete(path);
    var logs = LogManager.getInstance(); var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex((address, start) -> path, logs, 10_000_000, () -> 3_000_000.0);
    try (var writer = new org.triplehelix.wpilogmcp.capture.CaptureWriter(java.time.Clock.fixed(java.time.Instant.parse(org.triplehelix.wpilogmcp.fixtures.RecordingFixture.WHEN), java.time.ZoneOffset.UTC),
        new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler(), org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, index)) {
      writer.connected(org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1", 5810, "null-fixture"), "networktables.first.wpi.edu"); writer.timeSync(1_000_000, 0);
      var topic = new org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce("/x", 1, "int", null, new JsonObject()); writer.announce(topic);
      writer.value(topic, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 2_500_000, 2, 41L), 0);
      var manifest = recording.directory.resolve("session.json"); var state = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
      var old = state.getAsJsonArray("files").remove(0).getAsJsonObject();
      var provenance = org.triplehelix.wpilogmcp.store.StoreJson.JSON.fromJson(old.get("provenance"), org.triplehelix.wpilogmcp.store.StoreManifest.Provenance.class);
      state.add("open_capture", org.triplehelix.wpilogmcp.store.StoreJson.JSON.toJsonTree(new org.triplehelix.wpilogmcp.store.StoreManifest.OpenCapture("capture.wpilog", provenance, Files.size(path), 0, 0)));
      Files.writeString(manifest, state.toString()); logs.catalogPublished(); call("/stores", null);
      var args = object("source_ref", recording.reference); args.addProperty("api_version", 1); args.addProperty("start_time", 0); args.addProperty("end_time", 5);
      if (operation.equals("scatter")) { args.addProperty("x_entry", "NT:/x"); args.addProperty("y_entry", "NT:/x"); }
      else args.addProperty("entry", "NT:/x");
      var expected = call("/source/" + operation, args); assertEquals("ok", expected.get("status").getAsString());
      assertEquals(expected, tool("visualization_source_" + operation, args).get(operation));
      args.add("last_seconds", JsonNull.INSTANCE);
      assertEquals(expected, call("/source/" + operation, args)); assertEquals(expected, tool("visualization_source_" + operation, args).get(operation));
    }
  }

  @Test void chartHistogramUsesOriginalSamplesWithHttpMcpParityForFilesAndRecordings() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var file = source(fixture("histogram", 1));
    var recording = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("histogram-recording"), 2);
    recording.rewrite(0, w -> { int id = w.start("/value", "double", "", 0);
      w.append(id, 2_000_000, WpilogWriter.encodeDouble(4)); w.append(id, 1_000_000, WpilogWriter.encodeDouble(2)); });
    recording.rewrite(1, w -> { int id = w.start("/value", "double", "", 0); w.append(id, 2_000_000, WpilogWriter.encodeDouble(6)); });
    call("/stores", null);
    for (var ref : List.of(file, recording.reference)) {
      var args = object("source_ref", ref); args.addProperty("api_version", 1); args.addProperty("entry", "/value");
      var result = call("/source/histogram", args);
      assertEquals(JsonParser.parseString("[{\"low\":2,\"high\":4,\"count\":1},{\"low\":4,\"high\":6,\"count\":2}]"), result.get("bins"));
      assertEquals(3, result.getAsJsonObject("summary").get("count").getAsInt());
      assertEquals(4, result.getAsJsonObject("summary").get("mean").getAsDouble());
      assertEquals(result, tool("visualization_source_histogram", args).get("histogram"));
      assertEquals(ref, result.getAsJsonObject("inputs").get("source_ref"));
      assertTrue(result.has("prefix")); assertTrue(result.has("data_quality"));
    }
  }

  @Test void chartScatterPairsExactTimestampsOnceAndPagesWithoutChangingTotals() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var file = temp.resolve("scatter.wpilog");
    try (var w = new WpilogWriter(file, "")) {
      int x = w.start("/x", "double", "", 0), y = w.start("/y", "double", "", 0);
      for (var a : new long[][] {{2,4},{1,2},{2,6},{3,8}}) w.append(x,a[0]*1_000_000,WpilogWriter.encodeDouble(a[1]));
      for (var a : new long[][] {{2,40},{1,20},{2,60},{2,70},{4,90}}) w.append(y,a[0]*1_000_000,WpilogWriter.encodeDouble(a[1]));
    }
    var args = object("source_ref", source(file)); args.addProperty("api_version",1); args.addProperty("x_entry","/x"); args.addProperty("y_entry","/y"); args.addProperty("limit",2);
    var first = call("/source/scatter",args);
    assertEquals(JsonParser.parseString("[[2,20,1],[4,40,2]]"), first.get("points"));
    assertEquals(3,first.get("matched").getAsInt()); assertEquals(1,first.get("unpaired_x").getAsInt()); assertEquals(2,first.get("unpaired_y").getAsInt());
    assertEquals(2,first.get("next_offset").getAsInt()); assertFalse(first.get("complete").getAsBoolean());
    var limits = first.getAsJsonObject("limits").getAsJsonObject("points"); assertEquals(3,limits.get("total").getAsInt()); assertEquals(2,limits.get("returned").getAsInt());
    assertEquals(first,tool("visualization_source_scatter",args).get("scatter"));
    args.addProperty("offset",2); var second=call("/source/scatter",args);
    assertEquals(JsonParser.parseString("[[6,60,2]]"),second.get("points")); assertTrue(second.get("complete").getAsBoolean());
    assertEquals(3,second.get("matched").getAsInt());
  }

  @Test void chartRefusalsAndNoMatchAreExplainedOnBothAdapters() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var file=source(fixture("charts-empty",1)); var args=object("source_ref",file);args.addProperty("api_version",1);args.addProperty("entry","/value");args.addProperty("start_time",20);args.addProperty("end_time",21);
    var empty=call("/source/histogram",args);assertEquals("no_match",empty.get("status").getAsString());assertTrue(empty.has("looked_for"));assertTrue(empty.has("hint"));
    assertEquals("no_match",tool("visualization_source_histogram",args).get("status").getAsString());
    for (var value:List.of("0","4097","1.5","\"2\"","true")) {
      var scatter=object("source_ref",file);scatter.addProperty("api_version",1);scatter.addProperty("x_entry","/value");scatter.addProperty("y_entry","/value");scatter.add("limit",JsonParser.parseString(value));
      assertEquals(400,raw("/source/scatter",scatter.toString(),session,null).statusCode());
      assertEquals("error",tool("visualization_source_scatter",scatter).get("status").getAsString());
    }
    args.remove("start_time");args.remove("end_time");args.addProperty("unexpected",true);
    assertEquals(400,raw("/source/histogram",args.toString(),session,null).statusCode());
    assertEquals(403,raw("/source/histogram",args.toString(),session,"https://untrusted.invalid").statusCode());
    var missing = raw("/source/histogram",args.toString(),null,null);
    assertEquals(400,missing.statusCode()); assertTrue(missing.body().contains("MISSING_SESSION"));
  }

  @Test void chartPressureAndPermanentLimitsHaveTypedAdapterParity() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var args=object("source_ref",source(fixture("chart-pressure",1)));args.addProperty("entry","/value");args.addProperty("api_version",1);
    var ownerField=VisualizationWorkspace.class.getDeclaredField("sourceCharts");ownerField.setAccessible(true);var owner=ownerField.get(registry.visualizations());
    var slotField=owner.getClass().getDeclaredField("slots");slotField.setAccessible(true);var slots=(java.util.concurrent.Semaphore)slotField.get(owner);slots.acquire(2);
    try {
      var response=raw("/source/histogram",args.toString(),session,null);assertEquals(413,response.statusCode());var refusal=JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals("RESOURCE_LIMIT",refusal.get("code").getAsString());assertTrue(refusal.get("retryable").getAsBoolean());assertEquals(1000,refusal.get("retry_after_ms").getAsInt());
      var mcp=tool("visualization_source_histogram",args);assertEquals(refusal.get("code"),mcp.get("code"));assertTrue(mcp.get("retryable").getAsBoolean());assertEquals(1000,mcp.get("retry_after_ms").getAsInt());
      assertTrue(call("/capabilities",null).get("source_charts").getAsBoolean());
    } finally {slots.release(2);}
    assertEquals(3,call("/source/histogram",args).getAsJsonObject("summary").get("count").getAsInt());
    var file=temp.resolve("chart-large.wpilog");try(var w=new WpilogWriter(file,"")){int n=w.start("/n","double[]","",0);w.append(n,1,new byte[80_000]);}
    args=object("source_ref",source(file));args.addProperty("entry","/n");args.addProperty("field","[0]");args.addProperty("api_version",1);
    var response=raw("/source/histogram",args.toString(),session,null);assertEquals(413,response.statusCode());var refusal=JsonParser.parseString(response.body()).getAsJsonObject();
    assertFalse(refusal.get("retryable").getAsBoolean());assertFalse(refusal.has("retry_after_ms"));
    var mcp=tool("visualization_source_histogram",args);assertFalse(mcp.get("retryable").getAsBoolean());assertFalse(mcp.has("retry_after_ms"));
  }

  @Test void chartProjectionUsesExplicitFieldsEvenWhenAnEntryShadowsTheCombinedName() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var recording=new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("chart-fields"),2);
    for (int i=0;i<2;i++) { final int member=i;recording.rewrite(i,w->{
      int a=w.start("/a","double[]","",0),shadow=w.start("/a[0]","double","",0);
      w.append(a,(member+1)*1_000_000,WpilogWriter.encodeDouble(2+member*4));
      w.append(shadow,(member+1)*1_000_000,WpilogWriter.encodeDouble(100));
    }); }
    var args=object("source_ref",recording.reference);args.addProperty("api_version",1);args.addProperty("x_entry","/a");args.addProperty("x_field","[0]");args.addProperty("y_entry","/a[0]");call("/stores",null);
    var result=call("/source/scatter",args);
    assertEquals(JsonParser.parseString("[[2,100,1],[6,100,2]]"),result.get("points"));
    assertEquals(result,tool("visualization_source_scatter",args).get("scatter"));
    assertEquals(2,result.getAsJsonObject("prefix").getAsJsonArray("members").size());
  }

  @Test void recordingValueAndStatisticsHaveHttpMcpParityAndExactAnswers() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var fixture = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("analysis"), 4);
    var args = object("source_ref", fixture.reference); args.addProperty("entry", "/signal/00000"); args.addProperty("api_version", 1);
    var value = call("/source/value", args);
    assertEquals(value, tool("visualization_source_value", args).get("value"));
    assertEquals("1", value.getAsJsonObject("value").get("value").getAsString());
    assertEquals("rollover.wpilog", value.get("member_id").getAsString());
    var stats = call("/source/statistics", args);
    assertEquals(stats, tool("visualization_source_statistics", args).get("statistics"));
    assertEquals(3, stats.get("count").getAsInt()); assertEquals(1.0 / 3, stats.get("mean").getAsDouble(), 1e-12);
    assertEquals(Math.sqrt(1.0 / 3), stats.get("std_dev").getAsDouble(), 1e-12);
    assertTrue(call("/capabilities", null).get("recording_analysis").getAsBoolean());
    assertEquals(2, call("/capabilities", null).getAsJsonObject("recording_analysis_limits").get("max_concurrent_reads").getAsInt());
  }

  @Test void analysisToolsUseNoMatchAndPartialAndPreserveTheSingleFilePath() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var args = object("source_ref", source(fixture("analysis-single", 1))); args.addProperty("api_version", 1); args.addProperty("entry", "/value");
    assertEquals(call("/source/value", args), tool("visualization_source_value", args).get("value"));
    assertEquals(call("/source/statistics", args), tool("visualization_source_statistics", args).get("statistics"));
    args.addProperty("at_sec", -1); var empty = tool("visualization_source_value", args);
    assertEquals("no_match", empty.get("status").getAsString()); assertTrue(empty.has("looked_for")); assertTrue(empty.has("hint"));
    var f = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("analysis-bad"), 4);
    f.rewrite(0, w -> { int id = w.start("/n", "double", "", 0); w.append(id, 0, new byte[]{1}); w.append(id, 1, WpilogWriter.encodeDouble(2)); });
    call("/stores", null);
    args = object("source_ref", f.reference); args.addProperty("api_version", 1); args.addProperty("entry", "/n");
    for (var name : List.of("visualization_source_value", "visualization_source_statistics")) {
      var result = tool(name, args); assertEquals("partial", result.get("status").getAsString(), result.toString()); assertTrue(result.has("skipped"));
      assertEquals(f.reference, result.getAsJsonObject("inputs").get("source_ref"));
    }
  }

  @Test void emptyStatisticsHaveHttpMcpParityAndKeepTheirPrefixProof() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var recording = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("empty-statistics"), 4);
    for (var source : List.of(source(fixture("empty-statistics-file", 1)), recording.reference)) {
      var args = object("source_ref", source); args.addProperty("api_version", 1);
      args.addProperty("entry", source.get("kind").getAsString().equals("session") ? "/signal/00000" : "/value");
      args.addProperty("start_time", 500); args.addProperty("end_time", 600);
      var empty = call("/source/statistics", args);
      assertEquals("no_match", empty.get("status").getAsString());
      assertEquals(0, empty.get("count").getAsInt()); assertFalse(empty.has("mean"));
      if (source.get("kind").getAsString().equals("session")) {
        assertEquals(source, empty.getAsJsonObject("inputs").get("source_ref")); assertTrue(empty.has("prefix"));
      }
      var mcp = tool("visualization_source_statistics", args);
      assertEquals("no_match", mcp.get("status").getAsString());
      assertEquals(source, mcp.getAsJsonObject("inputs").get("source_ref"));
      assertEquals(empty, mcp.get("statistics"));
      assertTrue(mcp.has("looked_for")); assertTrue(mcp.has("hint"));
    }
  }

  @Test void relativeStatisticsKeepHttpMcpParityForFilesAndRecordings() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var file = source(fixture("relative-analysis", 1));
    var recording = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("relative-recording"), 4);
    recording.rewrite(0, w -> { int id = w.start("/value", "double", "", 0);
      w.append(id, 2_000_000, WpilogWriter.encodeDouble(4));
      w.append(id, 1_000_000, WpilogWriter.encodeDouble(2)); w.append(id, 2_000_000, WpilogWriter.encodeDouble(6)); });
    recording.rewrite(1, w -> { int id = w.start("/other", "double", "", 0); w.append(id, 2_000_000, WpilogWriter.encodeDouble(8)); });
    call("/stores", null);
    for (var source : List.of(file, recording.reference)) {
      var args = object("source_ref", source); args.addProperty("entry", "/value"); args.addProperty("api_version", 1); args.addProperty("last_seconds", .5);
      var result = call("/source/statistics", args);
      assertEquals(2, result.get("count").getAsInt()); assertEquals(5, result.get("mean").getAsDouble());
      assertEquals(1.5, result.getAsJsonObject("inputs").getAsJsonObject("window").get("start").getAsDouble());
      assertEquals(result, tool("visualization_source_statistics", args).get("statistics"));
    }
  }

  @Test void analysisPressureHasTypedAdapterParityAndPermanentLimitsHaveNoDelay() throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var fixture = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("analysis-pressure"), 2);
    var request = object("source_ref", fixture.reference); request.addProperty("entry", "/signal/00000"); request.addProperty("api_version", 1);
    var field = VisualizationWorkspace.class.getDeclaredField("recordingAnalysis"); field.setAccessible(true); var owner = field.get(registry.visualizations());
    var sf = owner.getClass().getDeclaredField("slots"); sf.setAccessible(true); var slots = (java.util.concurrent.Semaphore) sf.get(owner); slots.acquire(2);
    try {
      for (String operation : List.of("value", "statistics")) {
        var response = raw("/source/" + operation, request.toString(), session, null); assertEquals(413, response.statusCode());
        var httpError = JsonParser.parseString(response.body()).getAsJsonObject(); var mcpError = tool("visualization_source_" + operation, request);
        for (String key : List.of("code", "error", "retryable", "retry_after_ms")) assertEquals(httpError.get(key), mcpError.get(key));
        assertTrue(httpError.get("retryable").getAsBoolean()); assertEquals(1000, httpError.get("retry_after_ms").getAsInt());
      }
    } finally { slots.release(2); }
    fixture.rewrite(0, w -> { int id = w.start("/big", "raw", "", 0); w.append(id, 1, new byte[(64 << 10) + 1]); });
    request.addProperty("entry", "/big");
    var refused = raw("/source/value", request.toString(), session, null); assertEquals(413, refused.statusCode());
    var result = tool("visualization_source_value", request); assertEquals("RESOURCE_LIMIT", result.get("code").getAsString());
    assertFalse(result.get("retryable").getAsBoolean()); assertFalse(result.has("retry_after_ms"));
    var single = raw("/source/raw", request.toString(), session, null); assertEquals(409, single.statusCode());
    assertTrue(single.body().contains("recording-aware"), single.body());
  }

  @Test void twoViewsOfAAndOneOfBKeepIndependentConfigurationsAndRecover() throws Exception {
    var a = source(fixture("a", 1)); var b = source(fixture("b", 10));
    var first = create(a); var second = create(a); var third = create(b);
    var body = new JsonObject(); body.addProperty("revision", 1);
    var config = VisualizationContracts.blank(); config.getAsJsonObject("links").addProperty("cursor", false);
    body.add("configuration", config);
    call(route(first) + "/attach", new JsonObject());
    var updated = call(route(first), body); assertEquals(2, updated.get("revision").getAsInt());
    assertEquals(config, updated.get("configuration"));
    assertEquals(VisualizationContracts.blank(), call(route(second), null).get("configuration"));
    assertEquals(VisualizationContracts.blank(), call(route(third), null).get("configuration"));
    assertEquals(409, raw(route(first), body.toString(), session, null).statusCode());
    server.stop(); launch();
    assertEquals(config, call(route(first), null).get("configuration"));
    assertTrue(call(route(first), null).get("attached_client_id").isJsonNull());
    assertEquals(first.get("view_id"), call(route(first) + "/attach", new JsonObject()).get("view_id"));
  }

  @Test void expiredSessionReleasesItsAttachmentsWithoutReleasingAnotherWindow() throws Exception {
    var ref = source(fixture("expiry", 1)); var a = create(ref); var b = create(ref);
    call(route(a) + "/attach", new JsonObject());
    String old = session; session = connect(); call(route(b) + "/attach", new JsonObject());
    assertEquals(409, raw(route(a) + "/attach", "{}", session, null).statusCode());
    // DELETE uses exactly the same removal callback as idle expiry.
    var deleted = http.send(HttpRequest.newBuilder(URI.create(base() + "/mcp")).header("Mcp-Session-Id", old).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, deleted.statusCode());
    assertEquals(windowId(session), call(route(a) + "/attach", new JsonObject()).get("attached_client_id").getAsString());
    assertEquals(windowId(session), call(route(b), null).get("attached_client_id").getAsString());
    server.expireSessions(Duration.ZERO);
    String expired = session; session = connect();
    assertEquals(404, raw(route(a) + "/attach", "{}", expired, null).statusCode());
    assertEquals(windowId(session), call(route(a) + "/attach", new JsonObject()).get("attached_client_id").getAsString());
  }

  @Test void aWindowSilentForTwoMinutesLosesItsViewToAnotherWindow() throws Exception {
    var view = create(source(fixture("silent", 1)));
    call(route(view) + "/attach", new JsonObject());
    String first = session; var attachedAt = java.time.Instant.now();
    // Real time passes, so a check that wrongly refreshed the first window would restart its silence.
    Thread.sleep(1500);
    session = connect();
    assertEquals(409, raw(route(view) + "/attach", "{}", session, null).statusCode(), "the first window answered moments ago");
    server.visualizationNow(() -> attachedAt.plus(Duration.ofSeconds(119)));
    assertEquals(409, raw(route(view) + "/attach", "{}", session, null).statusCode(), "silent for less than two minutes");
    server.visualizationNow(() -> attachedAt.plus(Duration.ofSeconds(121)));
    assertEquals(windowId(session), call(route(view) + "/attach", new JsonObject()).get("attached_client_id").getAsString());
    // The first window, if it comes back, owns the view no more.
    var detach = raw(route(view) + "/detach", "{}", first, null);
    assertEquals(409, detach.statusCode(), detach.body()); assertTrue(detach.body().contains("VIEW_ATTACHED"), detach.body());
  }

  @Test void admissionAndRawJsonNeverTrustASpoofedClient() throws Exception {
    assertEquals(400, raw("/capabilities", null, null, null).statusCode());
    assertEquals(403, raw("/capabilities", null, session, "https://untrusted.invalid").statusCode());
    assertEquals(400, raw("/views", "{\"request_id\":\"one\",\"request_id\":\"two\"}", session, null).statusCode());
    var view = create(source(fixture("owner", 1))); call(route(view) + "/attach", new JsonObject());
    var other = connect();
    var update = new JsonObject(); update.addProperty("revision", 1); update.add("configuration", VisualizationContracts.blank());
    assertEquals(409, raw(route(view), update.toString(), other, null).statusCode());
  }

  @Test void sourceFingerprintChangeNeverRetargetsAnExistingView() throws Exception {
    var file = fixture("changed", 1); var ref = source(file); var view = create(ref);
    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
    var result = raw(route(view) + "/describe", "{}", session, null);
    assertEquals(409, result.statusCode(), result.body()); assertTrue(result.body().contains("SOURCE_UNAVAILABLE"));
    assertNotEquals(ref, source(file));
  }

  @Test void changedManagedBytesCannotKeepTheOldContentIdentity() throws Exception {
    var file = fixture("managed-source", 1);
    var store = LogManager.getInstance().stores().store(temp.resolve("managed-store"));
    var imported = store.importPaths(new org.triplehelix.wpilogmcp.store.LogStore.Request(List.of(file), false, null), ignored -> {}).get();
    var stored = imported.files().get(0).path(); assertNotNull(stored);
    call("/stores", null); // Refresh discovery after the explicit import.
    var ref = source(stored); assertEquals("recorded", ref.get("kind").getAsString());
    var view = create(ref);
    var bytes = Files.readAllBytes(stored); bytes[bytes.length - 1] ^= 1;
    Files.write(stored, bytes); Files.setLastModifiedTime(stored, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
    var result = raw(route(view) + "/describe", "{}", session, null);
    assertEquals(409, result.statusCode(), result.body()); assertTrue(result.body().contains("SOURCE_UNAVAILABLE"));
    server.stop(); launch();
    result = raw(route(view) + "/describe", "{}", session, null);
    assertEquals(409, result.statusCode(), "a restart must not legitimize changed content");
  }

  @Test void savedViewCreatesMetadataOnlyAndOpeningMintsAnotherWorkingId() throws Exception {
    var ref = source(fixture("save", 1)); var view = create(ref); call(route(view) + "/attach", new JsonObject());
    var request = obj("store_id", emptyStore("empty-store").get("store_id").getAsString()); request.addProperty("title", "Fixture layout");
    request.addProperty("request_id", UUID.randomUUID().toString()); request.addProperty("revision", 1);
    var saved = call(route(view) + "/save", request);
    assertFalse(saved.get("saved_view_ref").isJsonNull()); assertFalse(saved.get("native_dirty").getAsBoolean());
    var overview = new JsonObject(); overview.add("source_ref", ref);
    var lists = call("/source/views", overview);
    assertEquals(1, lists.getAsJsonArray("saved_views").size());
    assertFalse(lists.getAsJsonArray("working_views").get(0).getAsJsonObject().has("configuration"), "overview summaries must not copy every working buffer");
    assertFalse(lists.getAsJsonArray("saved_views").get(0).getAsJsonObject().has("configuration"));
    var open = saved.getAsJsonObject("saved_view_ref").deepCopy(); open.addProperty("request_id", UUID.randomUUID().toString());
    var copy = call("/saved/open", open);
    assertNotEquals(view.get("view_id"), copy.get("view_id")); assertEquals(ref, copy.get("source_ref"));
    assertTrue(Files.exists(temp.resolve("empty-store/store.json")));
    assertFalse(Files.exists(temp.resolve("empty-store/.local-ingestion")));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"future", "unknown", "non-json", "unknown-record-field", "missing-title", "invalid-revision", "invalid-request"})
  void unreadableSavedRecordsDoNotHideReadableViews(String defect) throws Exception {
    var ref = source(fixture("save-with-neighbor", 1)); var view = create(ref);
    call(route(view) + "/attach", new JsonObject());
    var request = obj("store_id", emptyStore("store").get("store_id").getAsString()); request.addProperty("title", "Readable layout");
    request.addProperty("request_id", UUID.randomUUID().toString()); request.addProperty("revision", 1);
    var saved = call(route(view) + "/save", request).getAsJsonObject("saved_view_ref");
    var directory = temp.resolve("store/visualizations/saved-views");
    var bad = JsonParser.parseString(Files.readString(directory.resolve(saved.get("saved_view_id").getAsString() + ".json"))).getAsJsonObject();
    var badId = UUID.randomUUID(); bad.addProperty("saved_view_id", badId.toString());
    if (defect.equals("future")) bad.addProperty("schema_version", 2);
    if (defect.equals("unknown")) bad.getAsJsonObject("configuration").addProperty("unexpected", true);
    if (defect.equals("unknown-record-field")) bad.addProperty("unexpected", true);
    if (defect.equals("missing-title")) bad.remove("title");
    if (defect.equals("invalid-revision")) bad.addProperty("revision", 0.5);
    if (defect.equals("invalid-request")) bad.addProperty("request_id", "invalid");
    var badFile = directory.resolve(badId + ".json");
    String originalBytes = defect.equals("non-json") ? "not JSON" : bad.toString();
    Files.writeString(badFile, originalBytes);
    var overview = new JsonObject(); overview.add("source_ref", ref);
    var lists = call("/source/views", overview);
    assertEquals(1, lists.getAsJsonArray("saved_views").size());
    var unreadable = lists.getAsJsonArray("unreadable"); assertEquals(1, unreadable.size());
    var problem = unreadable.get(0).getAsJsonObject();
    assertEquals(badFile.getFileName().toString(), problem.get("file").getAsString());
    assertEquals(saved.get("store_id"), problem.get("store_id"));
    assertFalse(problem.get("reason").getAsString().isBlank());
    var open = saved.deepCopy(); open.addProperty("request_id", UUID.randomUUID().toString());
    assertEquals(ref, call("/saved/open", open).get("source_ref"));
    open.addProperty("saved_view_id", badId.toString()); open.addProperty("request_id", UUID.randomUUID().toString());
    assertEquals(400, raw("/saved/open", open.toString(), session, null).statusCode(), "the requested invalid file still refuses");
    assertEquals(originalBytes, Files.readString(badFile), "shared metadata must remain untouched");
  }

  @Test void overviewIndexesOnlyAndCursorUsesTheLastOriginalSampleAtOrBeforeTime() throws Exception {
    var ref = source(fixture("values", 1));
    var body = new JsonObject(); body.add("source_ref", ref);
    var info = call("/source/describe", body); assertEquals(1, info.getAsJsonArray("entries").size());
    assertEquals(1.0, info.get("start_time_sec").getAsDouble());
    assertEquals(2.0, info.get("end_time_sec").getAsDouble());
    assertEquals(1.0, info.get("duration_sec").getAsDouble());
    var request = new JsonObject(); request.add("source_ref", ref); request.addProperty("entry", "/value"); request.addProperty("at_sec", 1.5);
    var value = call("/source/value", request); assertEquals("1000000", value.get("timestamp_us").getAsString());
    assertEquals(2.0, Double.longBitsToDouble(Long.parseUnsignedLong(value.getAsJsonObject("value").get("value").getAsString(), 16)));
    request.addProperty("at_sec", 2);
    assertEquals(6.0, Double.longBitsToDouble(Long.parseUnsignedLong(call("/source/value", request).getAsJsonObject("value").get("value").getAsString(), 16)));
    request.addProperty("at_sec", 0);
    assertTrue(call("/source/value", request).get("value").isJsonNull());
  }

  @Test void anIncompleteListingDoesNotPreventOpeningAKnownSavedViewBeyondItsBudget() throws Exception {
    var ref = source(fixture("listing-cap", 1)); var view = create(ref); call(route(view) + "/attach", new JsonObject());
    var request = obj("store_id", emptyStore("many-views").get("store_id").getAsString()); request.addProperty("title", "Fixture");
    request.addProperty("request_id", UUID.randomUUID().toString()); request.addProperty("revision", 1);
    var saved = call(route(view) + "/save", request).getAsJsonObject("saved_view_ref");
    var directory = temp.resolve("many-views/visualizations/saved-views");
    var sample = JsonParser.parseString(Files.readString(directory.resolve(saved.get("saved_view_id").getAsString() + ".json"))).getAsJsonObject();
    var ids = new HashSet<String>(); ids.add(saved.get("saved_view_id").getAsString());
    for (int i = 0; i < 1024; i++) {
      var id = UUID.randomUUID().toString(); ids.add(id); sample.addProperty("saved_view_id", id);
      Files.writeString(directory.resolve(id + ".json"), sample.toString());
    }
    var body = new JsonObject(); body.add("source_ref", ref);
    var listing = call("/source/views", body);
    assertTrue(listing.get("listing_incomplete").getAsBoolean());
    assertEquals(1024, listing.getAsJsonArray("saved_views").size());
    assertTrue(listing.getAsJsonArray("unreadable").toString().contains("1024 records"));
    listing.getAsJsonArray("saved_views").forEach(v -> ids.remove(v.getAsJsonObject().get("saved_view_id").getAsString()));
    assertEquals(1, ids.size()); // No filesystem iteration-order assumption.
    var open = saved.deepCopy(); open.addProperty("saved_view_id", ids.iterator().next()); open.addProperty("request_id", UUID.randomUUID().toString());
    assertEquals(ref, call("/saved/open", open).get("source_ref"));
  }

  @Test void cursorReportsAllSkippedRecordsWhileBoundingTheirDetails() throws Exception {
    var file = temp.resolve("damaged.wpilog");
    try (var writer = new WpilogWriter(file, "")) {
      int id = writer.start("/value", "double", "", 0);
      for (int i = 0; i < 101; i++) writer.append(id, i, new byte[] { 1 });
      writer.append(id, 102, WpilogWriter.encodeDouble(7));
    }
    var request = new JsonObject(); request.add("source_ref", source(file)); request.addProperty("entry", "/value");
    var result = call("/source/value", request);
    assertEquals(101, result.get("problem_count").getAsInt());
    assertTrue(result.get("problems_truncated").getAsBoolean());
    assertEquals(100, result.getAsJsonArray("problems").size());
    assertEquals("102", result.get("timestamp_us").getAsString());
  }
  JsonObject emptyStore(String name) throws Exception {
    var root = temp.resolve(name);
    LogManager.getInstance().stores().store(root).importPaths(
        new org.triplehelix.wpilogmcp.store.LogStore.Request(List.of(), false, null), ignored -> {}).get();
    return call("/stores", null).getAsJsonArray("stores").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(s -> s.get("path").getAsString().equals(root.toString())).findFirst().orElseThrow();
  }
  @Test void reusableTemplatesSharePinnedConfigurationAndRetainImportReceipts() throws Exception {
    var a = source(fixture("reuse-a", 1)); var b = source(fixture("reuse-b", 10));
    var destination = emptyStore("templates-a"); var second = emptyStore("templates-b");
    var body = obj("store_id", destination.get("store_id").getAsString());
    body.addProperty("request_id", UUID.randomUUID().toString()); body.addProperty("name", "Fixture template");
    body.addProperty("description", "Synthetic empty layout"); body.add("configuration", VisualizationContracts.blank());
    var template = call("/templates/create", body);
    assertEquals(template, call("/templates/create", body), "retry is idempotent");
    var ref = template.getAsJsonObject("template_ref");
    var apply = obj("request_id", UUID.randomUUID().toString()); apply.add("source_ref", a); apply.add("template_ref", ref);
    var viewA = call("/views", apply);
    apply.addProperty("request_id", UUID.randomUUID().toString()); apply.add("source_ref", b);
    var viewB = call("/views", apply);
    var exported = call("/templates/export", object("template_ref", ref));
    String envelope = exported.get("envelope_json").getAsString();
    assertFalse(envelope.contains("source_ref")); assertFalse(envelope.contains(temp.toString()));
    var imported = obj("store_id", second.get("store_id").getAsString());
    imported.addProperty("request_id", UUID.randomUUID().toString()); imported.addProperty("envelope_json", envelope);
    var copy = call("/templates/import", imported);
    server.stop(); launch();
    imported.addProperty("request_id", UUID.randomUUID().toString());
    assertEquals(copy, call("/templates/import", imported), "portable receipt survives restart and another request id");
    body.add("template_ref", ref); body.addProperty("expected_revision", 1); body.addProperty("request_id", UUID.randomUUID().toString());
    body.getAsJsonObject("configuration").getAsJsonObject("links").addProperty("cursor", false);
    var changed = call("/templates/update", body); assertEquals(2, changed.getAsJsonObject("template").get("revision").getAsInt());
    body.addProperty("request_id", UUID.randomUUID().toString());
    assertEquals(409, raw("/templates/update", body.toString(), session, null).statusCode());
    assertEquals(VisualizationContracts.blank(), call(route(viewA), null).get("configuration"));
    assertEquals(VisualizationContracts.blank(), call(route(viewB), null).get("configuration"));
  }
  static JsonObject object(String key, JsonElement value) { var o = new JsonObject(); o.add(key, value); return o; }
  @Test void bundledCompatibilityNeverGuessesAnExactMissingName() throws Exception {
    var ref = source(fixture("binding", 1));
    var catalog = call("/templates/list", new JsonObject()).getAsJsonArray("templates"); assertEquals(3, catalog.size());
    assertTrue(catalog.asList().stream().allMatch(t -> t.getAsJsonObject().getAsJsonObject("template_ref").get("origin").getAsString().equals("bundled")));
    var config = VisualizationContracts.blank();
    var req = JsonParser.parseString("{\"id\":\"value\",\"selector_kind\":\"entry\",\"entry\":\"/value\",\"field_path\":null,\"accepted_entry_types\":[\"double\"],\"value_type\":\"number\",\"unit\":null}").getAsJsonObject();
    config.getAsJsonArray("requirements").add(req);
    var check = object("source_ref", ref); check.add("configuration", config);
    var result = call("/templates/check", check);
    assertEquals("compatible", result.get("compatibility").getAsString());
    assertEquals("/value", result.getAsJsonArray("bindings").get(0).getAsJsonObject().get("entry").getAsString());
    req.addProperty("entry", "/missing");
    assertEquals("incompatible", call("/templates/check", check).get("compatibility").getAsString());
    req.addProperty("entry", "/value"); req.add("accepted_entry_types", JsonParser.parseString("[\"boolean\"]"));
    assertEquals("incompatible", call("/templates/check", check).get("compatibility").getAsString());
  }
  @Test void recordedStatisticsAndRawPagesUseOriginalRecordsAndOrdinals() throws Exception {
    var request = object("source_ref", source(fixture("panels", 1))); request.addProperty("entry", "/value");
    var stats = call("/source/statistics", request);
    assertEquals(4.0, stats.get("mean").getAsDouble()); assertEquals(2.0, stats.get("std_dev").getAsDouble());
    assertEquals(3, stats.get("count").getAsInt());
    request.addProperty("first_ordinal", 0); request.addProperty("limit", 2);
    var first = call("/source/raw", request);
    assertEquals(2, first.getAsJsonArray("samples").size()); assertFalse(first.get("complete").getAsBoolean());
    assertEquals("2000000", first.getAsJsonArray("samples").get(0).getAsJsonObject().get("timestamp_us").getAsString());
    assertEquals(2, first.get("next_ordinal").getAsInt()); request.addProperty("first_ordinal", 2);
    var last = call("/source/raw", request); assertEquals(1, last.getAsJsonArray("samples").size()); assertTrue(last.get("complete").getAsBoolean());
    assertEquals(2, last.getAsJsonArray("samples").get(0).getAsJsonObject().get("ordinal").getAsInt());
  }
  @Test void changedUnmanagedFingerprintStillListsItsUnavailableDraft() throws Exception {
    var file = fixture("older-fingerprint", 1); var old = source(file); var view = create(old);
    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
    var current = source(file);
    var listing = call("/source/views", object("source_ref", current));
    assertEquals(1, listing.getAsJsonArray("unavailable_views").size());
    var unavailable = listing.getAsJsonArray("unavailable_views").get(0).getAsJsonObject();
    assertEquals(view.get("view_id"), unavailable.get("view_id")); assertTrue(unavailable.get("reason").getAsString().contains("changed"));
  }

  @Test void recordingCompatibilityUsesTheSameCompleteEvidenceOverHttpAndMcp() throws Exception {
    org.triplehelix.wpilogmcp.tools.WpilogTools.registerAll(registry);
    var fixture = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("recording"), 20_000);
    var config = VisualizationContracts.blank(); var requirement = JsonParser.parseString("""
        {"id":"s","selector_kind":"entry","entry":"/signal/19999","accepted_entry_types":["int64"],
         "value_type":"number","field_path":null,"unit":null}
        """).getAsJsonObject(); config.getAsJsonArray("requirements").add(requirement);
    var request = object("source_ref", fixture.reference); request.add("configuration", config);
    var direct = call("/templates/check", request); assertEquals("compatible", direct.get("compatibility").getAsString());
    request.remove("configuration"); request.addProperty("configuration_json", config.toString()); request.addProperty("api_version", 1);
    var mcp = tool("visualization_template_check", request); assertEquals("ok", mcp.get("status").getAsString()); assertEquals(direct, mcp.get("compatibility"));
    assertFalse(call("/capabilities", null).get("live").getAsBoolean());
    assertTrue(call("/capabilities", null).get("recording_bindings").getAsBoolean());
    var create = object("source_ref", fixture.reference); create.addProperty("request_id", UUID.randomUUID().toString()); create.add("configuration", config);
    var view = call("/views", create); assertEquals(fixture.reference, view.get("source_ref"));
    create.addProperty("api_version", 1); create.addProperty("configuration_json", create.remove("configuration").toString());
    var toolView = tool("visualization_view_create", create); assertEquals(view, toolView.get("view"), "Both adapters share the creation receipt");
    assertTrue(call("/capabilities", null).get("recording_views").getAsBoolean());
    assertEquals(400, raw("/source/value", object("source_ref", fixture.reference).toString(), session, null).statusCode(), "Recording analysis requires an exact entry");
    LogManager.getInstance().clearAllowedDirectories(); LogManager.getInstance().addAllowedDirectory(Files.createDirectory(temp.resolve("withdrawn")));
    assertNotEquals(200, raw("/templates/check", request.toString(), session, null).statusCode());
    assertEquals("error", tool("visualization_template_check", request).get("status").getAsString());
    LogManager.getInstance().addAllowedDirectory(temp);
    assertTrue(LogManager.getInstance().release(fixture.root).released());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void recordingCompatibilityPressureSuppliesRetryDelay(boolean mcp) throws Exception {
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var fixture = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(temp.resolve("retry-store"), 2);
    var request = object("source_ref", fixture.reference); request.add("configuration", VisualizationContracts.blank());
    // Hold exactly the two admission permits, without timing-dependent cold disk reads.
    var ownerField = VisualizationWorkspace.class.getDeclaredField("recordingBindings"); ownerField.setAccessible(true);
    var owner = ownerField.get(registry.visualizations());
    var slotsField = owner.getClass().getDeclaredField("slots"); slotsField.setAccessible(true);
    var slots = (java.util.concurrent.Semaphore) slotsField.get(owner); slots.acquire(2);
    try {
      JsonObject failure;
      if (mcp) {
        var args = request.deepCopy(); args.addProperty("api_version", 1);
        args.addProperty("configuration_json", args.remove("configuration").toString());
        failure = tool("visualization_template_check", args); assertEquals("error", failure.get("status").getAsString());
      } else {
        var response = raw("/templates/check", request.toString(), session, null); assertEquals(413, response.statusCode());
        failure = JsonParser.parseString(response.body()).getAsJsonObject();
      }
      assertEquals("RESOURCE_LIMIT", failure.get("code").getAsString()); assertTrue(failure.get("retryable").getAsBoolean());
      assertTrue(failure.has("retry_after_ms"), "Temporary compatibility pressure must provide the inventory retry delay");
      assertEquals(new JsonPrimitive(1000), failure.get("retry_after_ms"));
    } finally { slots.release(2); }
    assertEquals("compatible", call("/templates/check", request).get("compatibility").getAsString());
  }

  JsonObject tool(String name, JsonObject arguments) throws Exception {
    var request = obj("jsonrpc", "2.0"); request.addProperty("id", 77); request.addProperty("method", "tools/call");
    var params = obj("name", name); params.add("arguments", arguments); request.add("params", params);
    var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/mcp")).header("Mcp-Session-Id", session)
        .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString());
    var rpc = JsonParser.parseString(response.body()).getAsJsonObject(); assertFalse(rpc.has("error"), rpc.toString());
    var result = rpc.getAsJsonObject("result");
    return JsonParser.parseString(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"get,false", "get,true", "create,false", "create,true", "export,false", "export,true", "update,false", "update,true"})
  void deletedTemplateHasAnActionableErrorInBothAdapters(String operation, boolean mcp) throws Exception {
    org.triplehelix.wpilogmcp.tools.WpilogTools.registerAll(registry);
    var source = source(fixture("missing-template", 1)); var store = emptyStore("missing-template-store");
    var body = object("store_id", store.get("store_id")); body.addProperty("request_id", UUID.randomUUID().toString());
    body.addProperty("name", "Fixture"); body.addProperty("description", ""); body.add("configuration", VisualizationContracts.blank());
    var template = call("/templates/create", body); var ref = template.getAsJsonObject("template_ref");
    var apply = object("template_ref", ref); apply.add("source_ref", source); apply.addProperty("request_id", UUID.randomUUID().toString());
    var kept = call("/views", apply);
    var path = Path.of(store.get("path").getAsString()).resolve("visualizations/templates/" + ref.get("template_id").getAsString() + ".json");
    Files.delete(path);
    var request = operation.equals("create") ? apply : operation.equals("update") ? body : object("template_ref", ref);
    request.add("template_ref", ref);
    if (operation.equals("create") || operation.equals("update")) request.addProperty("request_id", UUID.randomUUID().toString());
    if (operation.equals("update")) { request.addProperty("expected_revision", 1); request.remove("store_id"); }
    JsonObject error;
    if (mcp) {
      request.addProperty("api_version", 1);
      if (request.has("configuration")) request.addProperty("configuration_json", request.remove("configuration").toString());
      error = tool(operation.equals("create") ? "visualization_view_create" : "visualization_template_" + operation, request);
      assertEquals("error", error.get("status").getAsString(), error.toString());
    } else {
      var response = raw(operation.equals("create") ? "/views" : "/templates/" + operation, request.toString(), session, null);
      assertNotEquals(200, response.statusCode()); error = JsonParser.parseString(response.body()).getAsJsonObject();
    }
    assertEquals("TEMPLATE_UNAVAILABLE", error.get("code").getAsString(), error.toString());
    assertTrue(error.toString().contains("store template is missing"), error.toString());
    assertTrue(error.toString().contains("refresh"), error.toString());
    assertFalse(error.toString().contains(path.toString()), "use the template identity and remedy, not a bare path");
    assertFalse(Files.exists(path), "a missing template must not be recreated by an update");
    assertEquals(kept, call(route(kept), null), "existing views own their copied configuration");
    assertTrue(call("/templates/list", new JsonObject()).getAsJsonArray("templates").asList().stream()
        .noneMatch(t -> t.getAsJsonObject().get("template_ref").equals(ref)));
  }
  @Test void capabilitiesPublishSeparateDeletedRetryBounds() throws Exception {
    var capabilities = call("/capabilities", null);
    assertEquals(256, capabilities.get("max_working_views").getAsInt());
    assertEquals(256, capabilities.get("max_deleted_view_receipts").getAsInt());
    assertEquals(8L << 20, capabilities.get("max_deleted_view_metadata_bytes").getAsLong());
    assertEquals(6, capabilities.get("deleted_view_lifetime_hours").getAsInt());
    assertEquals(30, capabilities.get("draft_lifetime_days").getAsInt());
  }

  @Test void mcpRevisionEditsShareHttpStateAndRejectDuplicateDefinitionKeys() throws Exception {
    org.triplehelix.wpilogmcp.tools.WpilogTools.registerAll(registry);
    var view = create(source(fixture("mcp", 1))); var args = obj("view_id", view.get("view_id").getAsString());
    args.addProperty("api_version", 1); args.addProperty("expected_revision", 1); args.addProperty("request_id", UUID.randomUUID().toString());
    var config = VisualizationContracts.blank(); config.getAsJsonObject("links").addProperty("cursor", false);
    args.addProperty("configuration_json", config.toString());
    var changed = tool("visualization_view_update", args); assertEquals("ok", changed.get("status").getAsString(), changed.toString());
    assertEquals(2, changed.getAsJsonObject("view").get("revision").getAsInt());
    assertEquals(config, call(route(view), null).get("configuration"));
    var conflictingRetry = args.deepCopy(); conflictingRetry.addProperty("configuration_json", VisualizationContracts.blank().toString());
    assertEquals("REQUEST_CONFLICT", tool("visualization_view_update", conflictingRetry).get("code").getAsString());
    assertEquals(changed.get("view"), tool("visualization_view_update", args).get("view"), "same request returns its original result");
    args.addProperty("request_id", UUID.randomUUID().toString());
    assertEquals("REVISION_CONFLICT", tool("visualization_view_update", args).get("code").getAsString());
    args.addProperty("expected_revision", 2); args.addProperty("configuration_json", config.toString().replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"));
    var invalid = tool("visualization_view_update", args); assertEquals("error", invalid.get("status").getAsString()); assertTrue(invalid.toString().contains("duplicate key"));
    assertEquals(2, call(route(view), null).get("revision").getAsInt());
  }

  @Test void negotiatedNotificationsDeliverAnAgentEditOnlyToItsAttachedWindow() throws Exception {
    org.triplehelix.wpilogmcp.tools.WpilogTools.registerAll(registry);
    var initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"capabilities\":{\"experimental\":{\"wpilog/visualizationNotifications\":true}}}}";
    var hello = http.send(HttpRequest.newBuilder(URI.create(base() + "/mcp")).POST(HttpRequest.BodyPublishers.ofString(initialize)).build(), HttpResponse.BodyHandlers.ofString());
    session = hello.headers().firstValue("Mcp-Session-Id").orElseThrow();
    var view = create(source(fixture("notification", 1))); call(route(view) + "/attach", new JsonObject());
    var stream = http.send(HttpRequest.newBuilder(URI.create(base() + "/mcp")).header("Mcp-Session-Id", session).header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    try (var input = stream.body()) {
      var line = new java.util.concurrent.CompletableFuture<String>();
      var reader = new Thread(() -> { try {
        var lines = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)); String text;
        while ((text = lines.readLine()) != null) if (text.startsWith("data:") && text.contains("\"revision\":2")) { line.complete(text); return; }
      } catch (Exception e) { line.completeExceptionally(e); } }); reader.setDaemon(true); reader.start();
      session = connect(); // A separate local agent edits the existing window's view.
      var args = obj("view_id", view.get("view_id").getAsString()); args.addProperty("api_version", 1); args.addProperty("expected_revision", 1); args.addProperty("request_id", UUID.randomUUID().toString());
      var config = VisualizationContracts.blank(); config.getAsJsonObject("links").addProperty("cursor", false); args.addProperty("configuration_json", config.toString());
      assertEquals("ok", tool("visualization_view_update", args).get("status").getAsString());
      String event = line.get(3, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(event.contains(view.get("view_id").getAsString())); assertTrue(event.contains("view_revision_changed")); assertFalse(event.contains("configuration"));
    }
  }

  @Test void saveAndCloneRetriesSurviveLaterEditsAndRestart() throws Exception {
    org.triplehelix.wpilogmcp.tools.WpilogTools.registerAll(registry);
    var view = create(source(fixture("receipts", 1)));
    var args = obj("view_id", view.get("view_id").getAsString()); args.addProperty("api_version", 1);
    args.addProperty("expected_revision", 1); args.addProperty("request_id", UUID.randomUUID().toString());
    args.add("store_id", emptyStore("receipts-store").get("store_id")); args.addProperty("title", "Receipt fixture");
    var saved = tool("visualization_view_save", args); assertEquals("ok", saved.get("status").getAsString(), saved.toString());
    assertEquals(saved.get("view"), tool("visualization_view_save", args).get("view"), "lost Save response");
    var clone = obj("view_id", view.get("view_id").getAsString()); clone.addProperty("api_version", 1);
    clone.addProperty("expected_revision", 2); clone.addProperty("request_id", UUID.randomUUID().toString());
    var cloned = tool("visualization_view_clone", clone); assertEquals("ok", cloned.get("status").getAsString(), cloned.toString());
    var edit = clone.deepCopy(); edit.addProperty("request_id", UUID.randomUUID().toString()); edit.addProperty("configuration_json", VisualizationContracts.blank().toString());
    assertEquals("ok", tool("visualization_view_update", edit).get("status").getAsString());
    server.stop(); launch();
    assertEquals(saved.get("view"), tool("visualization_view_save", args).get("view"), "durable original Save receipt");
    assertEquals(cloned.get("view"), tool("visualization_view_clone", clone).get("view"), "clone does not use the parent's later revision");
    args.addProperty("title", "Different intent"); assertEquals("REQUEST_CONFLICT", tool("visualization_view_save", args).get("code").getAsString());
  }
  @Test void savedTemplateViewPreservesItsPinnedReference() throws Exception {
    var template = call("/templates/list", new JsonObject()).getAsJsonArray("templates").get(0).getAsJsonObject();
    var apply = obj("request_id", UUID.randomUUID().toString()); apply.add("template_ref", template.get("template_ref")); apply.add("source_ref", source(fixture("pin", 1)));
    var view = call("/views", apply); call(route(view) + "/attach", new JsonObject());
    var save = obj("request_id", UUID.randomUUID().toString()); save.addProperty("revision", 1); save.addProperty("title", "Pinned"); save.add("store_id", emptyStore("pin-store").get("store_id"));
    var saved = call(route(view) + "/save", save); var open = saved.getAsJsonObject("saved_view_ref").deepCopy(); open.addProperty("request_id", UUID.randomUUID().toString());
    assertEquals(template.get("template_ref"), call("/saved/open", open).get("template_ref"));
  }

  @Test void portableImportIdentityConflictsCopiesAndExplicitUpdatesRetainProof() throws Exception {
    var store = emptyStore("portable"); var template = BundledTemplates.all().get(0); template.addProperty("template_id", UUID.randomUUID().toString());
    var request = obj("request_id", UUID.randomUUID().toString()); request.add("store_id", store.get("store_id")); request.addProperty("envelope_json", TemplateDefinition.export(template).toString());
    var initial = call("/templates/import", request);
    var conflict = template.deepCopy(); conflict.addProperty("name", "Different bytes"); request.addProperty("request_id", UUID.randomUUID().toString()); request.addProperty("envelope_json", TemplateDefinition.export(conflict).toString());
    assertEquals(409, raw("/templates/import", request.toString(), session, null).statusCode());
    template.addProperty("revision", 8); request.addProperty("request_id", UUID.randomUUID().toString()); request.addProperty("envelope_json", TemplateDefinition.export(template).toString());
    var copy = call("/templates/import", request); assertNotEquals(initial.get("template_ref"), copy.get("template_ref")); assertEquals(1, copy.getAsJsonObject("template").get("revision").getAsInt());
    var another = template.deepCopy(); another.addProperty("template_id", UUID.randomUUID().toString()); request.addProperty("envelope_json", TemplateDefinition.export(another).toString());
    assertEquals(409, raw("/templates/import", request.toString(), session, null).statusCode(), "request id cannot change intent");
    template.addProperty("revision", 9); request.addProperty("request_id", UUID.randomUUID().toString()); request.addProperty("envelope_json", TemplateDefinition.export(template).toString());
    request.addProperty("mode", "update"); request.addProperty("expected_revision", 1);
    var updated = call("/templates/import", request); assertEquals(2, updated.getAsJsonObject("template").get("revision").getAsInt());
    assertEquals(template.get("template_id"), updated.getAsJsonObject("template").get("template_id"));
    var different = template.deepCopy(); different.addProperty("name", "Different portable revision 9");
    var collision = request.deepCopy(); collision.addProperty("request_id", UUID.randomUUID().toString()); collision.addProperty("envelope_json", TemplateDefinition.export(different).toString());
    collision.addProperty("expected_revision", 2);
    assertEquals(409, raw("/templates/import", collision.toString(), session, null).statusCode(), "portable proof conflicts even though revision 9 was stored locally as revision 2");
    server.stop(); launch(); request.addProperty("request_id", UUID.randomUUID().toString());
    assertEquals(updated, call("/templates/import", request), "portable revision 9 remembers its local revision 2");
  }

  @Test void importPreviewOffersUpdateOnlyForANewerKnownRevisionWithoutWriting() throws Exception {
    var template = BundledTemplates.all().get(0); var request = obj("request_id", UUID.randomUUID().toString()); request.add("store_id", emptyStore("import-choice").get("store_id")); request.addProperty("envelope_json", TemplateDefinition.export(template).toString());
    assertFalse(call("/templates/import-preview", request).get("can_update").getAsBoolean());
    call("/templates/import", request); template.addProperty("revision", 3); request.addProperty("envelope_json", TemplateDefinition.export(template).toString());
    var preview = call("/templates/import-preview", request); assertTrue(preview.get("can_update").getAsBoolean()); assertEquals(1, preview.get("expected_revision").getAsInt());
    assertEquals(1, call("/templates/get", object("template_ref", preview.get("existing"))).getAsJsonObject("template").get("revision").getAsInt());
  }

}
