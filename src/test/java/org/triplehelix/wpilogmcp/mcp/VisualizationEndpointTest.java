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
    var builder = HttpRequest.newBuilder(URI.create(base() + "/visualizations/v1" + route))
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
    assertEquals(session, call(route(a) + "/attach", new JsonObject()).get("attached_client_id").getAsString());
    assertEquals(session, call(route(b), null).get("attached_client_id").getAsString());
    server.expireSessions(Duration.ZERO);
    String expired = session; session = connect();
    assertEquals(404, raw(route(a) + "/attach", "{}", expired, null).statusCode());
    assertEquals(session, call(route(a) + "/attach", new JsonObject()).get("attached_client_id").getAsString());
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
    assertEquals(409, raw("/views", object("source_ref", fixture.reference).toString(), session, null).statusCode(), "Live creation remains gated");
    LogManager.getInstance().clearAllowedDirectories(); LogManager.getInstance().addAllowedDirectory(Files.createDirectory(temp.resolve("withdrawn")));
    assertNotEquals(200, raw("/templates/check", request.toString(), session, null).statusCode());
    assertEquals("error", tool("visualization_template_check", request).get("status").getAsString());
    LogManager.getInstance().addAllowedDirectory(temp);
    assertTrue(LogManager.getInstance().release(fixture.root).released());
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
