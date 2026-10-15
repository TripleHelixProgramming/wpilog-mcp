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
  HttpTransport transport; CaptureWriter writer; Path file, manifest; URI base;
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
    Files.writeString(manifest, StoreJson.JSON.toJson(new Session(SESSION, WHEN, WHEN, "pit_clock", null, null, null, null, List.of(), open)));
    transport = new HttpTransport(new ToolRegistry(), 0); transport.setStoreDirectories(Set.of(temp)); transport.start();
    base = URI.create("http://127.0.0.1:" + transport.getPort());
  }
  @AfterEach void stop() throws Exception {
    logs.addAllowedDirectory(temp);
    if (writer != null) writer.close(); if (transport != null) transport.stop();
    logs.release(temp); logs.clearAllowedDirectories(); allowed.forEach(logs::addAllowedDirectory);
  }
  void append(long timestamp, long value) { writer.value(topic, new ValueFrame(1, timestamp, 2, value), 0); }
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
  String nextPath(JsonObject lease) { return "/read/v1/follows/" + lease.get("id").getAsString() + "/next"; }
  String nextBody(String cursor, int wait) { return "{\"cursor\":\"" + cursor + "\",\"wait_ms\":" + wait + "}"; }

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
  @Test void finalizationDrainsTheLastRecordsAndNeverSwitchesToANewBoot() throws Exception {
    var lease = create(); var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
    append(1_000_002, 42); finalizeRecording(false);
    var last = json(call("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 100)), 200);
    assertTrue(last.getAsJsonObject("frontier").get("finalized").getAsBoolean());
    assertEquals(org.triplehelix.wpilogmcp.store.LogStore.contentHash(file), last.getAsJsonObject("frontier")
        .getAsJsonArray("members").get(0).getAsJsonObject().get("content_sha256").getAsString());
    assertEquals(first.getAsJsonObject("frontier").get("recording_id"), last.getAsJsonObject("frontier").get("recording_id"));
    assertEquals(1, last.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch").getAsJsonArray("samples").size());
    Files.writeString(manifest, StoreJson.JSON.toJson(new Session(UUID.randomUUID().toString(), WHEN, WHEN, "pit_clock", null, null, null, null, List.of())));
    var done = json(call("POST", nextPath(lease), nextBody(last.get("next_cursor").getAsString(), 25000)), 200);
    assertTrue(done.get("frontier_complete").getAsBoolean()); assertTrue(done.getAsJsonArray("batches").isEmpty());
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

  @Test void theRealTransportKeepsHealthAndDeleteAvailableWhenAllFollowWorkersWait() throws Exception {
    var leases = new ArrayList<JsonObject>(); var waits = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
    var field = HttpTransport.class.getDeclaredField("followStreaming"); field.setAccessible(true);
    var pool = (StreamingRequests) field.get(transport);
    try {
      for (int i = 0; i < 16; i++) {
        var lease = create(); leases.add(lease);
        var first = json(call("POST", nextPath(lease), nextBody(lease.get("cursor").getAsString(), 0)), 200);
        waits.add(http.sendAsync(request("POST", nextPath(lease), nextBody(first.get("next_cursor").getAsString(), 25000)), HttpResponse.BodyHandlers.ofString()));
      }
      FollowLeaseTest.until(() -> pool.activeCount() == 16);
      assertEquals(503, call("POST", nextPath(leases.get(0)), nextBody("unused", 0)).statusCode());
      assertEquals(200, call("GET", "/health", null).statusCode());
      assertEquals(204, call("DELETE", "/read/v1/follows/" + leases.get(0).get("id").getAsString(), null).statusCode());
      assertEquals(410, waits.get(0).get(5, TimeUnit.SECONDS).statusCode());
      assertFalse(waits.get(1).isDone());
    } finally {
      transport.stop();
      for (var wait : waits) assertTrue(wait.isDone() || wait.get(5, TimeUnit.SECONDS).statusCode() >= 400);
    }
  }
}
