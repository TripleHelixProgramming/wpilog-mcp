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
    var request = obj("store_path", temp.resolve("empty-store").toString()); request.addProperty("title", "Fixture layout");
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
    var request = obj("store_path", temp.resolve("store").toString()); request.addProperty("title", "Readable layout");
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
    var request = obj("store_path", temp.resolve("many-views").toString()); request.addProperty("title", "Fixture");
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
}
