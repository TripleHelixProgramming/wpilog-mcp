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
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.capture.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.*;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class FollowEndpointTest {
  @TempDir Path temp;
  final HttpClient http = HttpClient.newHttpClient();
  final LogManager logs = LogManager.getInstance();
  final Announce topic = new Announce("/x", 1, "int", null, new JsonObject());
  static final String WHEN = "2026-01-01T00:00:00Z";
  static final String STORE = "11111111-1111-4111-8111-111111111111", SESSION = "22222222-2222-4222-8222-222222222222";
  HttpTransport transport; ToolRegistry registry; CaptureWriter writer; Path file, manifest; URI base;
  Set<Path> allowed;

  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); allowed = logs.getAllowedDirectories(); logs.addAllowedDirectory(temp);
    var robot = Files.createDirectories(temp.resolve("robots/fixture"));
    var directory = Files.createDirectories(robot.resolve("sessions/2026-01-01/session"));
    file = directory.resolve("capture.wpilog"); manifest = directory.resolve("session.json");
    Files.writeString(temp.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, WHEN, STORE, List.of())));
    Files.writeString(robot.resolve("robot.json"), StoreJson.JSON.toJson(new Robot("fixture", "fixture", null, null, "logged")));
    var index = new CaptureIndex((address, start) -> file, logs, 10_000_000);
    writer = new CaptureWriter(Clock.fixed(Instant.parse(WHEN), ZoneOffset.UTC), new ManualScheduler(), CapturePolicy.ALL, index);
    writer.connected(RobotAddress.uri("127.0.0.1", 5810, "follow-test"), "networktables.first.wpi.edu");
    writer.timeSync(1_000_000, 0); writer.announce(topic); append(1_000_001, 41);
    var open = new OpenCapture("capture.wpilog", new Provenance("captured", null, "capture.wpilog", WHEN, false), Files.size(file), 1, 1);
    var session = StoreJson.JSON.toJsonTree(new Session(SESSION, WHEN, WHEN, "pit_clock", null, null, null, null, List.of(), open)).getAsJsonObject();
    try (var use = logs.acquire(file.toString())) {
      session.getAsJsonObject("open_capture").getAsJsonObject("provenance").addProperty("writer_identity",
          ((org.triplehelix.wpilogmcp.log.LiveLog.View) use.log()).captureIdentity());
    }
    Files.writeString(manifest, session.toString());
    registry = new ToolRegistry(); transport = new HttpTransport(registry, 0); transport.setStoreDirectories(Set.of(temp)); transport.start();
    base = URI.create("http://127.0.0.1:" + transport.getPort());
  }
  @AfterEach void stop() throws Exception {
    logs.addAllowedDirectory(temp);
    if (writer != null) writer.close(); if (transport != null) transport.stop();
    logs.release(temp); logs.clearAllowedDirectories(); allowed.forEach(logs::addAllowedDirectory);
  }
  void append(long timestamp, long value) { writer.value(topic, new ValueFrame(1, timestamp, 2, value), 0); }
  @Test void finalizedCapturedFileOpensWithoutEvictingItsWriterIndex() throws Exception {
    String owner = client(); finalizeRecording(false);
    try (var held = logs.acquire(file.toString())) {
      assertInstanceOf(org.triplehelix.wpilogmcp.log.LiveLog.View.class, held.log());
      assertNotNull(held.snapshot(), "The closed writer has a verified file boundary");
      var identify = new JsonObject(); identify.addProperty("path", file.toString());
      var summary = json(local(owner, "/visualizations/v2/source", identify), 200);
      var request = new JsonObject(); request.add("source_ref", summary.get("source_ref"));
      var inventory = json(local(owner, "/visualizations/v2/source/describe", request), 200);
      var entry = inventory.getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject)
          .filter(row -> row.get("name").getAsString().equals("NT:/x")).findFirst().orElseThrow();
      assertEquals(1, entry.get("sample_count").getAsInt());
      request.addProperty("entry", "NT:/x");
      var stats = json(local(owner, "/visualizations/v2/source/statistics", request), 200);
      assertEquals(1, stats.get("count").getAsInt()); assertEquals(41, stats.get("mean").getAsDouble());
    }
  }
  @Test void recordingAnalysisReadsFreshFixedPrefixesDuringAppendAndAfterFinalization() throws Exception {
    String owner = client(); var input = new JsonObject();
    input.add("source_ref", JsonParser.parseString(creation()).getAsJsonObject().get("source_ref")); input.addProperty("entry", "NT:/x");
    var first = json(local(owner, "/visualizations/v1/source/value", input), 200);
    assertEquals("41", first.getAsJsonObject("value").get("value").getAsString());
    assertFalse(first.getAsJsonObject("prefix").get("finalized").getAsBoolean());
    append(1_000_002, 43); append(1_000_001, 45); // Backward physical tail must not replace timestamp-latest.
    var latest = json(local(owner, "/visualizations/v1/source/value", input), 200);
    assertEquals("43", latest.getAsJsonObject("value").get("value").getAsString());
    var stats = json(local(owner, "/visualizations/v1/source/statistics", input), 200);
    assertEquals(3, stats.get("count").getAsInt()); assertEquals(43, stats.get("mean").getAsDouble()); assertEquals(2, stats.get("std_dev").getAsDouble());
    assertEquals("1", first.getAsJsonObject("prefix").getAsJsonArray("members").get(0).getAsJsonObject().get("entry_records").getAsString());
    finalizeRecording(false); logs.release(temp);
    var finished = json(local(owner, "/visualizations/v1/source/value", input), 200);
    assertTrue(finished.getAsJsonObject("prefix").get("finalized").getAsBoolean());
    assertEquals(latest.get("value"), finished.get("value")); assertEquals(latest.get("ordinal"), finished.get("ordinal"));
    assertEquals(org.triplehelix.wpilogmcp.store.LogStore.contentHash(file), finished.getAsJsonObject("prefix").getAsJsonArray("members").get(0).getAsJsonObject().get("content_sha256").getAsString());
    assertTrue(logs.release(temp).released());
  }
  HttpRequest request(String method, String path, String body) {
    return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
  }
  HttpResponse<String> call(String method, String path, String body) throws Exception {
    return http.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
  }
  JsonObject json(HttpResponse<String> response, int status) {
    assertEquals(status, response.statusCode(), response.body()); return JsonParser.parseString(response.body()).getAsJsonObject();
  }
  String creation() {
    return """
        {"source_ref":{"kind":"session","origin_store_id":"%s","session_id":"%s",
          "anchor_member":{"original_name":"capture.wpilog","recording_started_at":"2026-01-01T00:00:00Z"}},
         "queries":[{"entry":"NT:/x","order":"file"}]}
        """.formatted(STORE, SESSION);
  }
  JsonObject create() throws Exception {
    return json(call("POST", "/read/v1/follows", creation()), 201);
  }
  @ParameterizedTest
  @ValueSource(strings = {"foreign-server", "missing-scope", "local-master", "local-mirror", "top-level-scope"})
  void rawFollowRefusesQualificationBeforeAdmittingALease(String selection) throws Exception {
    var input = JsonParser.parseString(creation()).getAsJsonObject();
    String scope = selection.equals("local-master") ? STORE : UUID.randomUUID().toString();
    if (selection.equals("local-mirror")) mirror(scope);
    var server = selection.equals("foreign-server") ? UUID.randomUUID() : registry.visualizations().views.serverId();
    if (selection.equals("top-level-scope")) input.addProperty("scope_id", scope);
    else input.add("source_ref", org.triplehelix.wpilogmcp.visualization.VisualizationContracts.qualify(
        server, scope, input.getAsJsonObject("source_ref")));
    var response = json(call("POST", "/read/v1/follows", input.toString()), 400);
    assertEquals("INVALID_REQUEST", response.get("code").getAsString());
    assertTrue(response.get("message").getAsString().contains("view_binding"), response.toString());
    assertFalse(response.has("id")); assertEquals(0, endpoint().size()); assertEquals(0, endpoint().heldBytes());
    if (!selection.equals("local-mirror")) {
      var legacy = create();
      var page = json(call("POST", nextPath(legacy), nextBody(legacy.get("cursor").getAsString(), 0)), 200);
      assertEquals(41, page.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch")
          .getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonObject("value").get("value").getAsLong());
      assertEquals(204, call("DELETE", "/read/v1/follows/" + legacy.get("id").getAsString(), null).statusCode());
    }
  }
  FollowEndpoint endpoint() throws Exception {
    var field = HttpTransport.class.getDeclaredField("followEndpoint"); field.setAccessible(true);
    return (FollowEndpoint) field.get(transport);
  }
  Path mirror(String id) throws Exception {
    finalizeRecording(false);
    var root = Files.createDirectory(temp.resolve("mirror"));
    for (var original : List.of(file, manifest, temp.resolve("robots/fixture/robot.json"))) {
      var target = root.resolve(temp.relativize(original)); Files.createDirectories(target.getParent()); Files.copy(original, target);
    }
    Files.writeString(root.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, WHEN, id, List.of())
        .withOrigin(new MirrorOrigin(STORE, "http://master.invalid", WHEN, List.of(), Map.of()))));
    logs.addAllowedDirectory(root);
    transport.stop(); registry = new ToolRegistry(); transport = new HttpTransport(registry, 0);
    transport.setStoreDirectories(Set.of(temp, root)); transport.start();
    base = URI.create("http://127.0.0.1:" + transport.getPort()); return root;
  }
  String nextPath(JsonObject lease) { return "/read/v1/follows/" + lease.get("id").getAsString() + "/next"; }
  String nextBody(String cursor, int wait) { return "{\"cursor\":\"" + cursor + "\",\"wait_ms\":" + wait + "}"; }

  JsonObject viewConfiguration(String entry) {
    var config = org.triplehelix.wpilogmcp.visualization.VisualizationContracts.blank();
    config.getAsJsonArray("requirements").add(JsonParser.parseString("""
        {"id":"signal","selector_kind":"entry","entry":"%s","accepted_entry_types":["int64"],
         "field_path":null,"value_type":"number","unit":null}
        """.formatted(entry)));
    config.getAsJsonObject("time").add("live", JsonParser.parseString("{\"mode\":\"fixed\",\"start_sec\":1,\"end_sec\":2}"));
    return config;
  }
  String client() throws Exception {
    var response = call("POST", "/mcp", """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",
        "capabilities":{},"clientInfo":{"name":"bound-follow-fixture","version":"1"}}}
        """);
    assertEquals(200, response.statusCode(), response.body()); return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
  }
  HttpResponse<String> local(String client, String path, JsonObject body) throws Exception {
    return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30)).header("Mcp-Session-Id", client)
        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
  }
  JsonObject createView(String client) throws Exception {
    var input = new JsonObject(); input.add("source_ref", JsonParser.parseString(creation()).getAsJsonObject().get("source_ref"));
    input.addProperty("request_id", UUID.randomUUID().toString()); input.add("configuration", viewConfiguration("NT:/x"));
    return json(local(client, "/visualizations/v1/views", input), 200);
  }
  JsonObject boundRequest(JsonObject view) {
    var out = new JsonObject(); var binding = new JsonObject(); binding.add("view_id", view.get("view_id"));
    binding.add("expected_revision", view.get("revision")); out.add("view_binding", binding); return out;
  }
  @Test void qualifiedViewFollowKeepsSelectedMirrorAndRechecksItOnReplay() throws Exception {
    String scope = UUID.randomUUID().toString(); var mirror = mirror(scope); String client = client();
    var ref = org.triplehelix.wpilogmcp.visualization.VisualizationContracts.qualify(
        registry.visualizations().views.serverId(), scope, JsonParser.parseString(creation()).getAsJsonObject().getAsJsonObject("source_ref"));
    var input = new JsonObject(); input.add("source_ref", ref); input.addProperty("request_id", UUID.randomUUID().toString());
    input.add("configuration", viewConfiguration("NT:/x"));
    var view = json(local(client, "/visualizations/v2/views", input), 200);
    var lease = json(local(client, "/read/v1/follows", boundRequest(view)), 201);
    assertEquals(ref, lease.get("source_ref"));
    String cursor = lease.get("cursor").getAsString();
    var page = json(call("POST", nextPath(lease), nextBody(cursor, 0)), 200);
    var batch = page.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch");
    assertEquals(mirror.resolve(temp.relativize(file)).toString(), page.getAsJsonObject("frontier")
        .getAsJsonArray("members").get(0).getAsJsonObject().getAsJsonObject("snapshot").get("path").getAsString());
    assertEquals(41, batch.getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonObject("value").get("value").getAsLong());
    // Revoking the selected root leaves the master configured; a cached reply cannot use it.
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    assertEquals(403, call("POST", nextPath(lease), nextBody(cursor, 0)).statusCode());
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
    assertEquals(0, endpoint().size()); assertEquals(0, endpoint().heldBytes());
  }
  @Test void viewFollowPinsRevisionAndServerQueriesWithOneMiBQueriedPages() throws Exception {
    String client = client(); var view = createView(client); var input = boundRequest(view);
    var lease = json(local(client, "/read/v1/follows", input), 201);
    assertEquals("queries", lease.get("frontier_scope").getAsString()); assertEquals(1 << 20, lease.get("max_page_bytes").getAsInt());
    var binding = lease.getAsJsonObject("view_binding"); assertEquals(view.get("view_id"), binding.get("view_id"));
    assertEquals(1, binding.get("revision").getAsInt());
    assertEquals("NT:/x", binding.getAsJsonArray("queries").get(0).getAsJsonObject().get("entry").getAsString());
    var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    assertEquals(new JsonPrimitive("queries"), first.getAsJsonObject("frontier").get("frontier_scope"));
    var sample = first.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch").getAsJsonArray("samples").get(0).getAsJsonObject();
    assertEquals(41, sample.getAsJsonObject("value").get("value").getAsLong()); assertEquals(1_000_001, sample.get("timestamp_us").getAsLong());
    json(local(client, "/visualizations/v1/views/" + view.get("view_id").getAsString() + "/attach", new JsonObject()), 200);
    var edit = new JsonObject(); edit.addProperty("revision", 1); edit.add("configuration", viewConfiguration("NT:/missing"));
    json(local(client, "/visualizations/v1/views/" + view.get("view_id").getAsString(), edit), 200);
    var stale = json(local(client, "/read/v1/follows", input), 409); assertEquals("REVISION_CONFLICT", stale.get("code").getAsString());
    append(500_000, 42); // Deltas keep physical ordinals, even outside the historical window.
    var delta = json(call("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 0)), 200);
    assertEquals(42, delta.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch").getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonObject("value").get("value").getAsLong());
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
  }
  @Test void viewBindingReadIsRevisionCheckedAndMcpMatchesHttp() throws Exception {
    String client = client(); var view = createView(client); var body = boundRequest(view).getAsJsonObject("view_binding").deepCopy();
    var result = json(local(client, "/visualizations/v1/views/" + view.get("view_id").getAsString() + "/bindings", body), 200);
    assertEquals(view.get("view_id"), result.get("view_id")); assertEquals(1, result.get("revision").getAsInt());
    assertEquals("compatible", result.getAsJsonObject("compatibility").get("compatibility").getAsString());
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    body.addProperty("api_version", 1);
    var rpc = new JsonObject(); rpc.addProperty("jsonrpc", "2.0"); rpc.addProperty("id", 2); rpc.addProperty("method", "tools/call");
    var params = new JsonObject(); params.addProperty("name", "visualization_view_bind"); params.add("arguments", body); rpc.add("params", params);
    var response = new McpMessageHandler(registry).handleMessage(rpc, null, CallerContext.Authority.STDIO_LOCAL).response();
    var mcp = JsonParser.parseString(response.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
    assertEquals("ok", mcp.get("status").getAsString(), mcp.toString()); assertEquals(result, mcp.get("binding"));
    body.addProperty("expected_revision", 2);
    assertEquals("REVISION_CONFLICT", json(local(client, "/visualizations/v1/views/" + view.get("view_id").getAsString() + "/bindings", body), 409).get("code").getAsString());
  }

  @Test void viewFollowRechecksSessionAndGrantsOnRetainedRetries() throws Exception {
    String client = client(); var view = createView(client);
    assertEquals(403, call("POST", "/read/v1/follows", boundRequest(view).toString()).statusCode());
    var lease = json(local(client, "/read/v1/follows", boundRequest(view)), 201);
    String cursor = lease.get("cursor").getAsString(); json(call("POST", nextPath(lease), nextBody(cursor, 0)), 200);
    assertEquals(200, http.send(HttpRequest.newBuilder(base.resolve("/mcp")).header("Mcp-Session-Id", client).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(403, call("POST", nextPath(lease), nextBody(cursor, 0)).statusCode());
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
    client = client(); lease = json(local(client, "/read/v1/follows", boundRequest(view)), 201);
    cursor = lease.get("cursor").getAsString(); json(call("POST", nextPath(lease), nextBody(cursor, 0)), 200);
    logs.clearAllowedDirectories();
    assertEquals(403, call("POST", nextPath(lease), nextBody(cursor, 0)).statusCode());
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
  }
  @Test void viewFollowRefusesQueryOverridesAndBindingChangesButDrainsFinalization() throws Exception {
    String client = client(); var view = createView(client); var request = boundRequest(view);
    request.addProperty("max_page_bytes", 4 << 20);
    assertEquals(400, local(client, "/read/v1/follows", request).statusCode()); request.remove("max_page_bytes");
    var lease = json(local(client, "/read/v1/follows", request), 201);
    var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    var properties = new JsonObject(); properties.addProperty("description", "synthetic metadata revision");
    writer.properties(new org.triplehelix.wpilogmcp.nt4.ControlMessage.Properties(topic.name(), false, properties));
    var refused = json(call("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 0)), 409);
    assertEquals("BINDING_CHANGED", refused.get("code").getAsString()); assertFalse(refused.get("retryable").getAsBoolean());
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
    lease = json(local(client, "/read/v1/follows", request), 201);
    first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    append(1_000_002, 42); finalizeRecording(false);
    var last = json(call("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 0)), 200);
    assertTrue(last.get("ended").getAsBoolean());
    assertEquals(42, last.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch").getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonObject("value").get("value").getAsLong());
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
  }

  @Test void viewFollowUsesRecordedClockForLastNWindowAndRetriesSharedSlotPressure() throws Exception {
    String client = client(); var view = createView(client); var config = viewConfiguration("NT:/x");
    config.getAsJsonObject("time").add("live", JsonParser.parseString("{\"mode\":\"follow\",\"window_sec\":2}"));
    view = registry.visualizations().views.update(CallerContext.Authority.STDIO_LOCAL, UUID.fromString(view.get("view_id").getAsString()), 1, config).get();
    var lease = json(local(client, "/read/v1/follows", boundRequest(view)), 201);
    var binding = lease.getAsJsonObject("view_binding"); assertEquals("last_sample", binding.getAsJsonObject("clock").get("basis").getAsString());
    var window = binding.getAsJsonArray("queries").get(0).getAsJsonObject().getAsJsonArray("windows").get(0).getAsJsonObject();
    assertEquals("-999999", window.get("start_us").getAsString()); assertEquals("1000001", window.get("end_us").getAsString());
    var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    var field = registry.visualizations().getClass().getDeclaredField("recordingBindings"); field.setAccessible(true); var owner = field.get(registry.visualizations());
    var slotField = owner.getClass().getDeclaredField("slots"); slotField.setAccessible(true); var slots = (java.util.concurrent.Semaphore) slotField.get(owner);
    slots.acquire(2); append(1_000_003, 43);
    try {
      var refused = json(call("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 0)), 413);
      assertTrue(refused.get("retryable").getAsBoolean());
    } finally { slots.release(2); }
    var resumed = json(call("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 0)), 200);
    var value = resumed.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch").getAsJsonArray("samples").get(0).getAsJsonObject();
    assertEquals(1, value.get("ordinal").getAsInt()); assertEquals(43, value.getAsJsonObject("value").get("value").getAsInt());
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
  }

  @Test void viewAndRawFollowsShareTheLeaseCap() throws Exception {
    String client = client(); var view = createView(client); var leases = new ArrayList<JsonObject>();
    try {
      leases.add(json(local(client, "/read/v1/follows", boundRequest(view)), 201));
      for (int i = 1; i < 32; i++) leases.add(create());
      assertEquals(429, local(client, "/read/v1/follows", boundRequest(view)).statusCode());
    } finally { for (var lease : leases) assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode()); }
    var reopened = json(local(client, "/read/v1/follows", boundRequest(view)), 201);
    assertEquals(204, call("DELETE", "/read/v1/follows/" + reopened.get("id").getAsString(), null).statusCode());
  }
  /** A follow's reply goes out before its request has finished. By then the slot it reserved while opening must be its
   * lease, or a client that opens the next follow at once is counted twice and refused one below the limit. */
  @ParameterizedTest @ValueSource(booleans = {false, true})
  void aFollowCountsOnceAsSoonAsItsReplyIsSent(boolean bound) throws Exception {
    String client = client(); var leases = new ArrayList<JsonObject>();
    var held = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
    var first = new java.util.concurrent.atomic.AtomicBoolean(true);
    endpoint().afterReply(() -> {
      if (!first.getAndSet(false)) return;
      held.countDown();
      try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    });
    try {
      leases.add(bound ? json(local(client, "/read/v1/follows", boundRequest(createView(client))), 201) : create());
      assertTrue(held.await(10, TimeUnit.SECONDS), "The first follow's request is still finishing");
      for (int i = 1; i < 32; i++) leases.add(create());
      assertEquals(429, call("POST", "/read/v1/follows", creation()).statusCode(), "The limit itself still holds");
    } finally {
      release.countDown(); endpoint().afterReply(() -> {});
      for (var lease : leases) assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
    }
  }

  @ParameterizedTest @ValueSource(ints = {5000, 10000})
  void queriedFrontierBootstrapsLargeTwoMemberRecordingsAtOneMiB(int topics) throws Exception {
    var rollover = file.resolveSibling("rollover.wpilog"); var queries = new JsonArray();
    try (var out = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(rollover, "")) {
      out.start("NT:/x", "int64", "", 0);
      for (int n = 0; n < topics - 1; n++) {
        String name = "/subsystem/inputs/synthetic/signal-" + String.format(java.util.Locale.ROOT, "%05d", n);
        var properties = new JsonObject(); properties.addProperty("unit", "synthetic");
        var signal = new Announce(name, n + 2, "int", null, properties); writer.announce(signal);
        int id = out.start("NT:" + name, "int64", "{\"unit\":\"synthetic\"}", 0);
        if (n < 32) {
          writer.value(signal, new ValueFrame(n + 2, 2_000_000 + n, 2, 100L + n), 0);
          out.append(id, 1_000_000 + n, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeInt64(200L + n));
          var query = new JsonObject(); query.addProperty("entry", "NT:" + name); query.addProperty("order", "file"); queries.add(query);
        }
      }
    }
    addRollover(rollover);
    var body = JsonParser.parseString(creation()).getAsJsonObject(); body.add("queries", queries); body.addProperty("max_page_bytes", 1 << 20);
    json(call("POST", "/read/v1/follows", body.toString()), 413);
    body.addProperty("frontier_scope", "queries");
    var lease = json(call("POST", "/read/v1/follows", body.toString()), 201);
    assertEquals("queries", lease.get("frontier_scope").getAsString());
    var response = call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0));
    assertTrue(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1 << 20);
    var page = json(response, 200); var frontier = page.getAsJsonObject("frontier");
    assertEquals("queries", frontier.get("frontier_scope").getAsString());
    assertEquals(2, frontier.getAsJsonArray("members").size());
    for (var member : frontier.getAsJsonArray("members")) {
      var snapshot = member.getAsJsonObject().getAsJsonObject("snapshot");
      assertEquals(32, snapshot.getAsJsonObject("entries").size());
      assertEquals(32, snapshot.getAsJsonObject("sample_counts").size());
      assertEquals(0, snapshot.getAsJsonArray("schemas").size(), "Scalar selections need no bundled schemas");
    }
    int samples = 0;
    for (var batch : page.getAsJsonArray("batches")) {
      var b = batch.getAsJsonObject(); int n = Integer.parseInt(b.get("entry").getAsString().substring(b.get("entry").getAsString().length() - 5));
      boolean rolled = b.get("member_id").getAsString().equals("rollover.wpilog");
      assertTrue(b.getAsJsonObject("batch").getAsJsonArray("problems").isEmpty());
      for (var value : b.getAsJsonObject("batch").getAsJsonArray("samples")) {
        var sample = value.getAsJsonObject(); assertEquals(0, sample.get("ordinal").getAsInt());
        assertEquals((rolled ? 1_000_000 : 2_000_000) + n, sample.get("timestamp_us").getAsLong());
        assertEquals((rolled ? 200 : 100) + n, sample.getAsJsonObject("value").get("value").getAsLong()); samples++;
      }
    }
    assertEquals(64, samples); assertTrue(page.get("frontier_complete").getAsBoolean());
    assertEquals(response.body(), call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)).body());
    System.out.println("Queried frontier: " + topics + " topics/member, 2 members, 32 selections, "
        + response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " bytes, 64 exact samples");
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
  }

  void addRollover(Path path) throws Exception {
    var metadata = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    var closed = new LogFile(path.getFileName().toString(), org.triplehelix.wpilogmcp.store.LogStore.contentHash(path), Files.size(path), "wpilog",
        new Provenance("captured", null, path.getFileName().toString(), WHEN, false), true, 0, 0, WHEN, WHEN, "pit_clock", false, null);
    metadata.getAsJsonArray("files").add(StoreJson.JSON.toJsonTree(closed)); Files.writeString(manifest, metadata.toString()); logs.catalogPublished();
  }

  @ParameterizedTest @ValueSource(strings = {"null", "7", "true", "{}", "\"unknown\""})
  void frontierScopeIsAnExplicitClosedVocabulary(String scope) throws Exception {
    var body = JsonParser.parseString(creation()).getAsJsonObject(); body.add("frontier_scope", JsonParser.parseString(scope));
    var refused = json(call("POST", "/read/v1/follows", body.toString()), 400);
    assertEquals("INVALID_REQUEST", refused.get("code").getAsString());
    assertTrue(refused.get("message").getAsString().contains("frontier_scope"));
  }

  @ParameterizedTest @ValueSource(ints = {2, 3})
  void queriedFrontierDoesNotChargeUnselectedMetadataButKeepsSelectedMetadataBounded(int metadataMiB) throws Exception {
    var properties = new JsonObject(); properties.addProperty("description", "x".repeat(metadataMiB << 20));
    writer.announce(new Announce("/unselected", 2, "double", null, properties));
    var body = JsonParser.parseString(creation()).getAsJsonObject(); body.addProperty("frontier_scope", "queries"); body.addProperty("max_page_bytes", 1 << 20);
    var lease = json(call("POST", "/read/v1/follows", body.toString()), 201);
    var page = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    assertEquals(Set.of("NT:/x"), page.getAsJsonObject("frontier").getAsJsonArray("members").get(0).getAsJsonObject().getAsJsonObject("snapshot").getAsJsonObject("entries").keySet());
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
    body.getAsJsonArray("queries").get(0).getAsJsonObject().addProperty("entry", "NT:/unselected");
    assertFalse(json(call("POST", "/read/v1/follows", body.toString()), 413).get("retryable").getAsBoolean());
  }

  @Test void sourceInspectionOverHttpAndMcpSuppliesTheSameAnchorToAOneMiBFollow() throws Exception {
    var connected = call("POST", "/mcp", """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",
        "capabilities":{},"clientInfo":{"name":"inspection-fixture","version":"1"}}}
        """);
    assertEquals(200, connected.statusCode(), connected.body()); String client = connected.headers().firstValue("Mcp-Session-Id").orElseThrow();
    var path = new JsonObject(); path.addProperty("path", file.toString());
    var request = HttpRequest.newBuilder(base.resolve("/visualizations/v1/source")).header("Mcp-Session-Id", client)
        .POST(HttpRequest.BodyPublishers.ofString(path.toString())).build();
    var identified = json(http.send(request, HttpResponse.BodyHandlers.ofString()), 200);
    var ref = identified.getAsJsonObject("source_ref");
    assertEquals(JsonParser.parseString(creation()).getAsJsonObject().get("source_ref"), ref);
    var query = new JsonObject(); query.add("source_ref", ref);
    request = HttpRequest.newBuilder(base.resolve("/visualizations/v1/source/describe")).header("Mcp-Session-Id", client)
        .POST(HttpRequest.BodyPublishers.ofString(query.toString())).build();
    var inventory = json(http.send(request, HttpResponse.BodyHandlers.ofString()), 200);
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var arguments = path.deepCopy(); arguments.addProperty("api_version", 1);
    var rpc = new JsonObject(); rpc.addProperty("jsonrpc", "2.0"); rpc.addProperty("id", 2); rpc.addProperty("method", "tools/call");
    var params = new JsonObject(); params.addProperty("name", "visualization_source_inspect"); params.add("arguments", arguments); rpc.add("params", params);
    var response = new McpMessageHandler(registry).handleMessage(rpc, null, CallerContext.Authority.STDIO_LOCAL).response();
    var result = JsonParser.parseString(response.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
    assertEquals("ok", result.get("status").getAsString(), result.toString());
    assertEquals(ref, result.getAsJsonObject("source").get("source_ref"));
    assertEquals(inventory, result.getAsJsonObject("source").get("inventory"), "Both transports use one inspection owner");
    query.add("queries", JsonParser.parseString("[{\"entry\":\"NT:/x\",\"order\":\"file\"}]")); query.addProperty("max_page_bytes", 1 << 20);
    var lease = json(call("POST", "/read/v1/follows", query.toString()), 201);
    var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    assertEquals(inventory.get("recording_id"), first.getAsJsonObject("frontier").get("recording_id"));
    append(500_000, 42);
    var delta = json(call("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 100)), 200);
    var sample = delta.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch").getAsJsonArray("samples").get(0).getAsJsonObject();
    assertEquals(1, sample.get("ordinal").getAsInt()); assertEquals("42", sample.getAsJsonObject("value").get("value").getAsString());
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
    var changed = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    changed.getAsJsonObject("open_capture").getAsJsonObject("provenance").addProperty("writer_identity", UUID.randomUUID().toString());
    Files.writeString(manifest, changed.toString()); logs.catalogPublished();
    var refused = json(http.send(request, HttpResponse.BodyHandlers.ofString()), 409);
    assertEquals("HISTORY_UNAVAILABLE", refused.get("code").getAsString()); assertFalse(refused.get("retryable").getAsBoolean());
    response = new McpMessageHandler(registry).handleMessage(rpc, null, CallerContext.Authority.STDIO_LOCAL).response();
    result = JsonParser.parseString(response.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
    assertEquals("error", result.get("status").getAsString()); assertEquals("HISTORY_UNAVAILABLE", result.get("code").getAsString());
    assertFalse(result.get("retryable").getAsBoolean());
  }

  @Test void httpFollowBootstrapsReplaysWaitsAndReturnsBackwardAppendsWithoutReopeningIdlePrefixes() throws Exception {
    var lease = create(); String path = nextPath(lease), firstCursor = lease.get("cursor").getAsString();
    var response = call("POST", path, nextBody(firstCursor, 0)); var first = json(response, 200);
    assertEquals(lease.get("id"), first.get("follow_id"));
    assertEquals(response.body(), call("POST", path, nextBody(firstCursor, 25000)).body(), "Lost-response retry must be byte-identical and not wait");
    assertEquals("41", first.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch").getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonObject("value").get("value").getAsString());
    String cursor = first.get("next_cursor").getAsString();
    assertEquals(204, call("POST", path, nextBody(cursor, 100)).statusCode());
    var waiting = http.sendAsync(request("POST", path, nextBody(cursor, 25000)), HttpResponse.BodyHandlers.ofString());
    assertFalse(waiting.isDone()); append(500_000, 42);
    var delta = json(waiting.get(5, TimeUnit.SECONDS), 200);
    assertFalse(delta.get("bootstrap").getAsBoolean());
    var batch = delta.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch");
    assertEquals("42", batch.getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonObject("value").get("value").getAsString());
    assertEquals("500000", batch.getAsJsonArray("samples").get(0).getAsJsonObject().get("timestamp_us").getAsString());
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
    assertEquals("CURSOR_EXPIRED", json(call("POST", path, nextBody(cursor, 0)), 410).get("code").getAsString());
    append(600_000, 43); // closing a consumer never stops acquisition
    try (var snapshot = logs.readAccess().open(file)) { assertEquals(3, snapshot.sampleCount("NT:/x")); }
  }

  @ParameterizedTest @ValueSource(ints = {4, 20}) @Timeout(60)
  void continuousCaptureAppendsKeepTheSameHttpFollowAndExactOrdinals(int intervalMs) throws Exception {
    var lease = create(); String cursor = lease.get("cursor").getAsString();
    int records = 4000 / intervalMs;
    var producer = java.util.concurrent.Executors.newSingleThreadExecutor();
    var writing = producer.submit(() -> {
      for (int n = 1; n <= records; n++) {
        append(1_000_001L + n, 41L + n);
        try { Thread.sleep(intervalMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      }
    });
    int polls = 0, refused = 0, ordinal = 0; var refusals = new ArrayList<String>();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    try {
      while (ordinal <= records && System.nanoTime() < deadline) {
        var response = call("POST", nextPath(lease), nextBody(cursor, 100)); polls++;
        if (response.statusCode() == 409) { refused++; refusals.add(response.body()); continue; }
        if (response.statusCode() == 204) continue;
        var page = json(response, 200); assertEquals(lease.get("id"), page.get("follow_id"));
        for (var batch : page.getAsJsonArray("batches")) {
          var data = batch.getAsJsonObject().getAsJsonObject("batch"); assertTrue(data.getAsJsonArray("problems").isEmpty());
          for (var value : data.getAsJsonArray("samples")) {
            var sample = value.getAsJsonObject(); assertEquals(ordinal, sample.get("ordinal").getAsInt());
            assertEquals(Long.toString(1_000_001L + ordinal), sample.get("timestamp_us").getAsString());
            assertEquals(Long.toString(41L + ordinal), sample.getAsJsonObject("value").get("value").getAsString()); ordinal++;
          }
        }
        cursor = page.get("next_cursor").getAsString();
      }
      writing.get(5, TimeUnit.SECONDS);
      System.out.printf("Continuous capture: interval_ms=%d polls=%d refusals=%d exact_records=%d%n", intervalMs, polls, refused, ordinal);
      assertEquals(records + 1, ordinal, "All synthetic values arrive once on the original lease");
      assertEquals(0, refused, refusals.toString());
    } finally {
      writing.cancel(true); producer.shutdownNow(); assertTrue(producer.awaitTermination(5, TimeUnit.SECONDS));
      call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null);
    }
  }

  @Test void cachedRetriesCannotOutliveConfiguredStoreAdmission() throws Exception {
    var lease = create(); String cursor = lease.get("cursor").getAsString();
    json(call("POST", nextPath(lease), nextBody(cursor, 0)), 200);
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(Files.createDirectories(temp.resolve("elsewhere")));
    assertEquals("ACCESS_DENIED", json(call("POST", nextPath(lease), nextBody(cursor, 0)), 403).get("code").getAsString());
  }

  @Test void metadataOnlyPublicationAndLateSignalsWakeTheRealSource() throws Exception {
    var body = JsonParser.parseString(creation()).getAsJsonObject();
    body.getAsJsonArray("queries").add(JsonParser.parseString("{\"entry\":\"NT:/late\",\"order\":\"file\"}"));
    var lease = json(call("POST", "/read/v1/follows", body.toString()), 201);
    var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    String cursor = first.get("next_cursor").getAsString();
    assertEquals(204, call("POST", nextPath(lease), nextBody(cursor, 0)).statusCode());
    var late = new Announce("/late", 2, "int", null, new JsonObject()); writer.announce(late);
    var metadata = json(call("POST", nextPath(lease), nextBody(cursor, 500)), 200);
    assertTrue(metadata.getAsJsonArray("batches").isEmpty());
    assertTrue(metadata.getAsJsonObject("frontier").toString().contains("NT:/late"));
    writer.value(late, new ValueFrame(2, 100, 2, 9L), 0);
    var delta = json(call("POST", nextPath(lease), nextBody(metadata.get("next_cursor").getAsString(), 500)), 200);
    var batch = delta.getAsJsonArray("batches").get(0).getAsJsonObject(); assertEquals("NT:/late", batch.get("entry").getAsString());
    var sample = batch.getAsJsonObject("batch").getAsJsonArray("samples").get(0).getAsJsonObject();
    assertEquals(0, sample.get("ordinal").getAsInt()); assertEquals("9", sample.getAsJsonObject("value").get("value").getAsString());
  }

  @Test void aPublicationTokenCannotKeepTheCaptureIndexAliveAfterPrefixRelease() {
    var token = logs.capturePublication(file); assertNotNull(token);
    for (var component : token.getClass().getRecordComponents()) {
      assertTrue(component.getType().isPrimitive() || component.getType().equals(String.class),
          "The wait token retains native index state through " + component.getName());
    }
  }

  @Test void aFinalizedFileCannotChangeBetweenHashVerificationAndSnapshotAdmission() throws Exception {
    finalizeRecording(false); logs.release(temp);
    var changed = new java.util.concurrent.atomic.AtomicBoolean();
    var access = new org.triplehelix.wpilogmcp.read.LogAccess() {
      @Override public org.triplehelix.wpilogmcp.read.ReadSnapshot open(Path path) throws java.io.IOException {
        var time = Files.getLastModifiedTime(path);
        var bytes = Files.readAllBytes(path); bytes[bytes.length - 1] ^= 1;
        Files.write(path, bytes);
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(time.toInstant().plusSeconds(2)));
        changed.set(true);
        return logs.readAccess().open(path);
      }
      @Override public void close() {}
    };
    try (var catalog = new org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog(
        org.triplehelix.wpilogmcp.read.ReadOptions.defaults(List.of(temp)), access, 128L << 20)) {
      var source = new org.triplehelix.wpilogmcp.read.server.CatalogRecordingSource(logs,
          () -> new org.triplehelix.wpilogmcp.read.server.CatalogRecordingSource.Lease() {
            @Override public org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog value() { return catalog; }
            @Override public void close() {}
          }, new org.triplehelix.wpilogmcp.read.catalog.RecordingReference(STORE, SESSION, "capture.wpilog", WHEN, List.of()));
      var failure = assertThrows(java.io.IOException.class, () -> { try (var ignored = source.open()) {} });
      assertTrue(changed.get(), "The fixture must change the file after its final hash was checked");
      assertTrue(failure.getMessage().contains("changed after hash verification"), failure.getMessage());
    }
  }

  void finalizeRecording(boolean corruptHash) throws Exception {
    writer.close(); writer = null;
    var before = StoreJson.JSON.fromJson(Files.readString(manifest), Session.class);
    String hash = corruptHash ? "0".repeat(64) : org.triplehelix.wpilogmcp.store.LogStore.contentHash(file);
    var closed = new LogFile("capture.wpilog", hash, Files.size(file), "wpilog", before.openCapture().provenance(),
        true, .5, 2, WHEN, WHEN, "pit_clock", false, null);
    Files.writeString(manifest, StoreJson.JSON.toJson(new Session(before.id(), WHEN, WHEN, "pit_clock",
        null, null, null, null, List.of(closed)))); logs.catalogPublished();
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void finalizationDrainsTheLastRecordsAndNeverSwitchesToANewBoot(boolean cold) throws Exception {
    var lease = create(); var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    append(1_000_002, 42); finalizeRecording(false);
    if (cold) logs.release(temp);
    var last = json(call("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 100)), 200);
    assertTrue(last.getAsJsonObject("frontier").get("finalized").getAsBoolean());
    assertEquals(org.triplehelix.wpilogmcp.store.LogStore.contentHash(file), last.getAsJsonObject("frontier")
        .getAsJsonArray("members").get(0).getAsJsonObject().get("content_sha256").getAsString());
    assertEquals(first.getAsJsonObject("frontier").get("recording_id"), last.getAsJsonObject("frontier").get("recording_id"));
    assertEquals(1, last.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch").getAsJsonArray("samples").size());
    Files.writeString(manifest, StoreJson.JSON.toJson(new Session(UUID.randomUUID().toString(), WHEN, WHEN, "pit_clock", null, null, null, null, List.of())));
    var done = json(call("POST", nextPath(lease), nextBody(last.get("next_cursor").getAsString(), 25000)), 410);
    assertEquals("FOLLOW_ENDED", done.get("code").getAsString());
  }

  @Test void aLiveManifestCannotClaimAnotherWritersIncarnation() throws Exception {
    var json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    json.getAsJsonObject("open_capture").getAsJsonObject("provenance").addProperty("writer_identity", UUID.randomUUID().toString());
    Files.writeString(manifest, json.toString()); logs.catalogPublished();
    assertEquals("HISTORY_UNAVAILABLE", json(call("POST", "/read/v1/follows", creation()), 409).get("code").getAsString());
  }

  @Test void terminalPagesDrainInOrderReplayExactlyAndRetainTheirAcknowledgement() throws Exception {
    var request = JsonParser.parseString(creation()).getAsJsonObject(); request.addProperty("max_records", 1);
    var lease = json(call("POST", "/read/v1/follows", request.toString()), 201);
    var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    assertEquals(new JsonPrimitive(false), first.get("ended"));
    append(1_000_002, 42); append(1_000_003, 43); finalizeRecording(false);
    var second = json(call("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 0)), 200);
    assertEquals(new JsonPrimitive(false), second.get("ended"));
    String input = second.get("next_cursor").getAsString();
    var response = call("POST", nextPath(lease), nextBody(input, 0)); var last = json(response, 200);
    assertEquals(new JsonPrimitive(true), last.get("ended"));
    assertTrue(last.get("frontier_complete").getAsBoolean());
    assertEquals(2, last.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch")
        .getAsJsonArray("samples").get(0).getAsJsonObject().get("ordinal").getAsInt());
    assertEquals(response.body(), call("POST", nextPath(lease), nextBody(input, 25000)).body());
    String terminal = last.get("next_cursor").getAsString();
    for (int i = 0; i < 2; i++) assertEquals("FOLLOW_ENDED",
        json(call("POST", nextPath(lease), nextBody(terminal, 25000)), 410).get("code").getAsString());
    assertEquals("INVALID_REQUEST", json(call("POST", nextPath(lease), nextBody(input, 0)), 400).get("code").getAsString());
    assertEquals(204, call("DELETE", "/read/v1/follows/" + lease.get("id").getAsString(), null).statusCode());
    assertEquals("CURSOR_EXPIRED", json(call("POST", nextPath(lease), nextBody(terminal, 0)), 410).get("code").getAsString());
  }
  @Test void aChangedFinalHashIsUnavailableAndCannotBeAcceptedAsContinuousHistory() throws Exception {
    var lease = create(); var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    finalizeRecording(true);
    assertEquals("SOURCE_UNAVAILABLE", json(call("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 0)), 409).get("code").getAsString());
  }
  @Test void originAndReferenceAdmissionRejectRequestsBeforeServingData() throws Exception {
    assertEquals(405, call("GET", "/read/v1/follows", null).statusCode());
    var foreignOrigin = HttpRequest.newBuilder(base.resolve("/read/v1/follows")).header("Origin", "https://untrusted.invalid")
        .POST(HttpRequest.BodyPublishers.ofString(creation())).build();
    assertEquals(403, http.send(foreignOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
    for (String body : List.of("{}", "{\"source_ref\":[]}", creation().replace("\"file\"", "\"time\""),
        creation().replace("\"order\":\"file\"", "\"order\":\"file\",\"windows\":{}"),
        creation().replace("\"order\":\"file\"", "\"order\":\"file\",\"record_limit\":[]"),
        creation().replace("\"queries\":[", "\"max_records\":0,\"queries\":[")))
      assertEquals("INVALID_REQUEST", json(call("POST", "/read/v1/follows", body), 400).get("code").getAsString());
    assertEquals("SOURCE_UNAVAILABLE", json(call("POST", "/read/v1/follows", creation().replace(SESSION, "33333333-3333-4333-8333-333333333333")), 409).get("code").getAsString());
    var lease = create();
    for (int wait : List.of(-1, 25001)) assertEquals(400,
        call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), wait)).statusCode());
  }

  @Test void theExistingNativeFileRouteAlsoResolvesASessionMergeAlias() throws Exception {
    var session = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    session.addProperty("id", "33333333-3333-4333-8333-333333333333");
    session.add("merged_session_ids", StoreJson.JSON.toJsonTree(List.of(SESSION)));
    Files.writeString(manifest, session.toString());
    var request = "{\"store_id\":\"" + STORE + "\",\"session_id\":\"" + SESSION + "\",\"file_path\":\"capture.wpilog\"}";
    var snapshot = json(call("POST", "/read/v1/snapshots", request), 201);
    assertTrue(snapshot.get("live").getAsBoolean());
    assertEquals(204, call("DELETE", "/read/v1/snapshots/" + snapshot.get("id").getAsString(), null).statusCode());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"create", "next", "snapshot"})
  void malformedJsonBodiesAreInvalidRequestsWithoutChangingTheRetryPage(String route) throws Exception {
    var lease = create(); String cursor = lease.get("cursor").getAsString();
    var first = call("POST", nextPath(lease), nextBody(cursor, 0)); var page = json(first, 200);
    String path = switch (route) {
      case "create" -> "/read/v1/follows";
      case "next" -> nextPath(lease);
      default -> "/read/v1/snapshots";
    };
    var bodies = new ArrayList<byte[]>();
    for (String body : List.of("{not json", "{\"cursor\":\"" + page.get("next_cursor").getAsString() + "\",",
        "{} {}", "{\"cursor\":\"a\",\"cursor\":\"b\"}", "[]"))
      bodies.add(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    bodies.add(new byte[] {'{', '"', (byte) 0xc3, '"', ':', '0', '}'}); // invalid UTF-8
    for (var body : bodies) {
      var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
          .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
      var error = json(http.send(request, HttpResponse.BodyHandlers.ofString()), 400);
      assertEquals("INVALID_REQUEST", error.get("code").getAsString());
      assertEquals("Invalid read JSON", error.get("message").getAsString(), "Do not expose parser implementation advice");
      assertEquals(first.body(), call("POST", nextPath(lease), nextBody(cursor, 0)).body(),
          "Rejecting a body must neither acknowledge nor discard the retained page");
    }
    assertEquals(204, call("POST", nextPath(lease), nextBody(page.get("next_cursor").getAsString(), 0)).statusCode());
  }

  @Test void aReplacementWriterCannotReuseTheSameProvenanceAndCounts() throws Exception {
    var lease = create(); var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    String cursor = first.get("next_cursor").getAsString();
    assertEquals(204, call("POST", nextPath(lease), nextBody(cursor, 0)).statusCode());
    org.triplehelix.wpilogmcp.log.EntryInfo entry;
    try (var snapshot = logs.readAccess().open(file)) { entry = snapshot.entries().get("NT:/x"); }
    writer.close(); writer = null;
    var replacement = new org.triplehelix.wpilogmcp.log.LiveLog(file, 10_000_000);
    try (var output = new WpilogOutput(file, entry.id() + 1, true)) {
      logs.beginCapture(replacement); replacement.announce(entry);
      var written = output.append(entry.id(), 1_000_001, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeInt64(99));
      replacement.append(entry, new ValueFrame(entry.id(), 1_000_001, 2, 99L), written);
      assertEquals("HISTORY_UNAVAILABLE", json(call("POST", nextPath(lease), nextBody(cursor, 0)), 409).get("code").getAsString());
    } finally { logs.finishCapture(replacement); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void finalizationWithoutTheOriginalWriterProofRequiresExplicitRebootstrap(boolean legacy) throws Exception {
    var lease = create(); var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    finalizeRecording(false);
    var json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    var provenance = json.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonObject("provenance");
    if (legacy) provenance.remove("writer_identity"); else provenance.addProperty("writer_identity", UUID.randomUUID().toString());
    Files.writeString(manifest, json.toString()); logs.catalogPublished();
    var refusal = json(call("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 0)), 409);
    assertEquals("HISTORY_UNAVAILABLE", refusal.get("code").getAsString());
    // The independently hashed finalized file remains readable through an explicit new bootstrap.
    var fresh = create();
    assertEquals(200, call("POST", nextPath(fresh), nextBody(fresh.get("cursor").getAsString(), 0)).statusCode());
  }

  @Test void aReplacementThatFinalizesBetweenPollsCannotPassWithTheSameEntryCounts() throws Exception {
    var lease = create(); var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    String cursor = first.get("next_cursor").getAsString();
    assertEquals(204, call("POST", nextPath(lease), nextBody(cursor, 0)).statusCode());
    writer.close(); logs.release(temp); Files.delete(file);
    var index = new CaptureIndex((address, start) -> file, logs, 10_000_000);
    writer = new CaptureWriter(Clock.fixed(Instant.parse(WHEN), ZoneOffset.UTC), new ManualScheduler(), CapturePolicy.ALL, index);
    writer.connected(RobotAddress.uri("127.0.0.1", 5810, "replacement"), "networktables.first.wpi.edu");
    writer.timeSync(1_000_000, 0); writer.announce(topic); append(1_000_001, 99);
    var metadata = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    try (var use = logs.acquire(file.toString())) {
      metadata.getAsJsonObject("open_capture").getAsJsonObject("provenance").addProperty("writer_identity",
          ((org.triplehelix.wpilogmcp.log.LiveLog.View) use.log()).captureIdentity());
    }
    Files.writeString(manifest, metadata.toString()); finalizeRecording(false); logs.release(temp);
    assertEquals("HISTORY_UNAVAILABLE", json(call("POST", nextPath(lease), nextBody(cursor, 0)), 409).get("code").getAsString());
    var fresh = create(); var accepted = json(call("POST", nextPath(fresh), nextBody(fresh.get("cursor").getAsString(), 0)), 200);
    assertEquals("99", accepted.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch")
        .getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonObject("value").get("value").getAsString());
  }

  @Test void bytePressureAfterAcknowledgementResumesTheDeltaWithoutRebootstrap() throws Exception {
    var field = HttpTransport.class.getDeclaredField("followEndpoint"); field.setAccessible(true);
    var endpoint = (FollowEndpoint) field.get(transport);
    var budget = FollowEndpoint.class.getDeclaredField("budget"); budget.setAccessible(true);
    budget.set(endpoint, new org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Budget(256 << 10));
    var request = JsonParser.parseString(creation()).getAsJsonObject(); request.addProperty("max_page_bytes", 128 << 10);
    var a = json(call("POST", "/read/v1/follows", request.toString()), 201);
    var first = json(call("POST", nextPath(a), nextBody(a.get("cursor").getAsString(), 0)), 200);
    request.addProperty("max_page_bytes", 64 << 10);
    var b = json(call("POST", "/read/v1/follows", request.toString()), 201);
    json(call("POST", nextPath(b), nextBody(b.get("cursor").getAsString(), 0)), 200);
    append(1_000_002, 42); String cursor = first.get("next_cursor").getAsString();
    var failure = json(call("POST", nextPath(a), nextBody(cursor, 0)), 413);
    assertEquals(new JsonPrimitive(true), failure.get("retryable"));
    call("DELETE", "/read/v1/follows/" + b.get("id").getAsString(), null);
    var response = call("POST", nextPath(a), nextBody(cursor, 0)); var delta = json(response, 200);
    assertFalse(delta.get("bootstrap").getAsBoolean());
    assertEquals(1, delta.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch")
        .getAsJsonArray("samples").get(0).getAsJsonObject().get("ordinal").getAsInt());
    assertEquals(response.body(), call("POST", nextPath(a), nextBody(cursor, 0)).body());
  }

  @Test void oversizedFollowBodiesAreStructuralRefusalsAndLeaveTheRetryPageIntact() throws Exception {
    var lease = create(); String cursor = lease.get("cursor").getAsString();
    var first = call("POST", nextPath(lease), nextBody(cursor, 0)); json(first, 200);
    String oversized = " ".repeat((1 << 20) + 1);
    for (String path : List.of("/read/v1/follows", nextPath(lease))) {
      var failure = json(call("POST", path, oversized), 413);
      assertEquals("RESOURCE_LIMIT", failure.get("code").getAsString());
      assertEquals(new JsonPrimitive(false), failure.get("retryable"));
      assertEquals(first.body(), call("POST", nextPath(lease), nextBody(cursor, 0)).body());
    }
  }

  @Test void theRealTransportKeepsHealthAndDeleteAvailableWhenAllFollowWorkersWait() throws Exception {
    var leases = new ArrayList<JsonObject>(); var waits = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
    var field = HttpTransport.class.getDeclaredField("followStreaming"); field.setAccessible(true);
    var pool = (StreamingRequests) field.get(transport);
    try {
      for (int i = 0; i < 16; i++) {
        var lease = create(); leases.add(lease);
        var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
        // A page's slot is free once its last byte is out, before its worker's thread is: the wait goes out once the
        // first page's slot is free, and the next lease opens once the wait holds one.
        int parked = i;
        FollowLeaseTest.until(() -> pool.freeSlots() == 16 - parked);
        waits.add(http.sendAsync(request("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 25000)), HttpResponse.BodyHandlers.ofString()));
        FollowLeaseTest.until(() -> pool.freeSlots() == 15 - parked);
      }
      var refused = call("POST", nextPath(leases.get(0)), nextBody("unused", 0));
      // A lease whose page is waiting answers a second page with 503 as well; only the limit's refusal shows the limit.
      assertEquals(503, refused.statusCode());
      assertTrue(refused.body().contains("Streaming capacity is in use"), refused.body());
      assertEquals(200, call("GET", "/health", null).statusCode());
      assertEquals(204, call("DELETE", "/read/v1/follows/" + leases.get(0).get("id").getAsString(), null).statusCode());
      assertEquals(410, waits.get(0).get(5, TimeUnit.SECONDS).statusCode());
      assertFalse(waits.get(1).isDone());
    } finally { transport.stop(); }
    // Every follow the stop finds parked ends with 410 CLOSED and is opened again on the next server.
    for (var wait : waits) assertEquals(410, wait.get(5, TimeUnit.SECONDS).statusCode(), wait.get().body());
  }
}
