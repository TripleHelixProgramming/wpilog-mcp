/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/** Remote diagnostics must read the selected owner's publication, never infer a missing session. */
class ReplicaPublicationStatusTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  final HttpClient client = HttpClient.newHttpClient();
  Set<Path> previous;
  LogStore store;
  HttpTransport server;
  String peer, storeId;

  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath();
    previous = manager.getAllowedDirectories();
    manager.addAllowedDirectory(temp);
    store = manager.stores().store(temp.resolve("master"));
    store.importPaths(new LogStore.Request(
        List.of(ImportFixture.write(temp.resolve("one.wpilog"), 7)), false, "RIO"), p -> {})
        .get(10, TimeUnit.SECONDS);
    storeId = store.replicaHeader().id();
    store.migrateReplicas(storeId).get(10, TimeUnit.SECONDS);
    store.refreshStatus().get(10, TimeUnit.SECONDS);
    server = new HttpTransport(new ToolRegistry(), 0);
    server.start();
    var hello = request("/handshake", null);
    assertEquals(200, hello.statusCode(), hello.body());
    peer = json(hello).get("session_id").getAsString();
  }

  @AfterEach void cleanup() {
    if (server != null) server.stop();
    if (store != null) store.close();
    manager.unloadAllLogs();
    manager.clearAllowedDirectories();
    if (previous != null) previous.forEach(manager::addAllowedDirectory);
  }

  private HttpResponse<String> request(String route, String body, String... headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(
        "http://127.0.0.1:" + server.getPort() + "/replicas/v1" + route))
        .timeout(Duration.ofSeconds(3));
    if (peer != null) request.header("Replica-Session", peer);
    if (body == null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body));
    for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonObject json(HttpResponse<String> response) {
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  private String selection() {
    var body = new JsonObject();
    body.addProperty("store_id", storeId);
    return body.toString();
  }

  private String selection(String selectedSession, int limit) {
    var body = JsonParser.parseString(selection()).getAsJsonObject();
    body.addProperty("session_id", selectedSession);
    body.addProperty("limit", limit);
    return body.toString();
  }

  @Test void remoteStatusReportsKnownEmptyPendingStateAndRealCapacity() throws Exception {
    var response = request("/status", selection());
    assertEquals(200, response.statusCode(),
        "The selected replica store needs bounded remote publication status: " + response.body());
    var body = json(response);
    assertEquals("ok", body.get("status").getAsString());
    assertEquals(storeId, body.get("store_id").getAsString());
    assertEquals("ready", body.get("publication_state").getAsString());
    assertEquals(0, body.get("pending_total").getAsInt());
    assertEquals(0, body.getAsJsonArray("pending_publications").size());
    assertTrue(body.get("oldest_failed_at").isJsonNull());
    assertTrue(body.get("oldest_time_complete").getAsBoolean());
    var capacity = body.getAsJsonObject("catalog_capacity");
    assertEquals(1, capacity.get("records").getAsInt());
    assertEquals(4096, capacity.get("records_limit").getAsInt());
    assertFalse(capacity.get("near_limit").getAsBoolean());
    assertFalse(response.body().contains(temp.toString()), "Remote status cannot reveal paths");
  }

  @Test void handshakeAdvertisesTheAdditivePublicationStatusCapability() throws Exception {
    var response = request("/handshake", null);
    assertTrue(json(response).getAsJsonArray("capabilities").asList().stream()
        .anyMatch(value -> value.getAsString().equals("publication_status")),
        "Peers must be able to discover the additive publication-status route");
  }

  @Test void metricsReportACompletePendingCountWithoutPathsOrSessionIds() throws Exception {
    var request = HttpRequest.newBuilder(URI.create(
        "http://127.0.0.1:" + server.getPort() + "/metrics"))
        .timeout(Duration.ofSeconds(3)).GET().build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    String expected = "wpilog_replica_pending_publications{store_id=\"" + storeId + "\"} 0";
    assertTrue(response.body().lines().anyMatch(expected::equals),
        "A complete empty publication inventory must expose its independently known zero count");
    assertFalse(response.body().contains(temp.toString()));
    assertFalse(response.body().contains("session_id="));
  }

  @Test void pendingLookupReachesBeyondTheSummaryAndRetryClearsItsCause() throws Exception {
    var original = store.replicaHeader();
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    var ids = new HashSet<String>();
    var markers = new ArrayList<Path>();
    for (int i = 0; i < 2; i++) {
      var clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z").plusSeconds(60L * i), ZoneOffset.UTC);
      var file = ReplicaPublicationFixture.completedCapture(store, clock, 23 + i);
      var receipt = StoreJson.JSON.fromJson(Files.readString(file.resolveSibling("session.json")), StoreManifest.Session.class);
      ids.add(receipt.id());
      markers.add(file.resolveSibling(".replica-publication.json"));
    }
    assertEquals(2, ids.size());
    var response = request("/status", selection(null, 1));
    assertEquals(200, response.statusCode(), response.body());
    var body = json(response);
    assertEquals(2, body.get("pending_total").getAsInt());
    assertEquals(2, body.get("known_pending_count").getAsInt());
    assertEquals(1, body.getAsJsonArray("pending_publications").size());
    assertEquals(4096, body.getAsJsonObject("catalog_capacity").get("records").getAsInt());
    var limits = body.getAsJsonObject("limits").getAsJsonObject("pending_publications");
    assertEquals(1, limits.get("returned").getAsInt());
    assertEquals(2, limits.get("total").getAsInt());
    assertEquals(1, limits.get("limit").getAsInt());
    String listed = body.getAsJsonArray("pending_publications").get(0).getAsJsonObject().get("session_id").getAsString();
    assertTrue(ids.contains(listed));
    String omitted = ids.stream().filter(id -> !id.equals(listed)).findFirst().orElseThrow();
    var refusal = request("/status", selection(omitted, 1));
    assertEquals(409, refusal.statusCode(), refusal.body());
    var error = json(refusal);
    assertEquals("error", error.get("status").getAsString());
    assertEquals("PUBLICATION_PENDING", error.get("code").getAsString());
    assertTrue(error.get("retryable").getAsBoolean());
    assertEquals(omitted, error.get("session_id").getAsString());
    var cause = error.getAsJsonObject("cause");
    assertEquals("RESOURCE_LIMIT", cause.get("code").getAsString());
    assertEquals("Replica catalog metadata capacity is exhausted.", cause.get("message").getAsString());
    assertTrue(cause.get("cause_durable").getAsBoolean());
    assertDoesNotThrow(() -> Instant.parse(cause.get("failed_at").getAsString()));
    assertDoesNotThrow(() -> Instant.parse(cause.get("first_failed_at").getAsString()));
    assertFalse(refusal.body().contains(temp.toString()));

    new StoreFiles(store.root(), manager.testGetSecurityValidator())
        .writeDurable(store.root().resolve("store.json"), original);
    store.recoverMasterPublication().get(10, TimeUnit.SECONDS);
    for (var marker : markers) assertFalse(Files.exists(marker));
    var cleared = request("/status", selection());
    assertEquals(200, cleared.statusCode(), cleared.body());
    assertEquals(0, json(cleared).get("pending_total").getAsInt());
    assertEquals(0, json(cleared).getAsJsonArray("pending_publications").size());
    var published = request("/status", selection(omitted, 1));
    assertEquals(200, published.statusCode(), published.body());
    assertEquals("published", json(published).get("publication_state").getAsString());
    var identities = store.replicaHeader().replicas().files().stream().map(ReplicaCatalog.Entry::identity).toList();
    store.recoverMasterPublication().get(10, TimeUnit.SECONDS);
    assertEquals(identities, store.replicaHeader().replicas().files().stream().map(ReplicaCatalog.Entry::identity).toList());
    var absent = request("/status", selection("absent-synthetic-session", 1));
    assertEquals(409, absent.statusCode());
    assertEquals("SOURCE_UNAVAILABLE", json(absent).get("code").getAsString());
    assertFalse(json(absent).get("retryable").getAsBoolean());
  }

  @Test void statusNeverWalksOrJoinsAStoreTurnEvenAfterDiscoveryExpires() throws Exception {
    var field = HttpTransport.class.getDeclaredField("storeDoor");
    field.setAccessible(true);
    var door = (StoreDoor) field.get(server);
    // Invalidate discovery explicitly: any accidental select()/inventory() must now walk.
    door.refresh();
    long walks = door.discoveryWalks(), recoveries = store.publicationWalks();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var held = StoreQueueTestBarrier.hold(store, entered, release);
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      for (int i = 0; i < 3; i++) {
        var response = request("/status", selection());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(0, json(response).get("pending_total").getAsInt());
      }
      var missing = request("/status", selection("absent", 1));
      assertEquals("SOURCE_UNAVAILABLE", json(missing).get("code").getAsString());
      var metricRequest = HttpRequest.newBuilder(URI.create(
          "http://127.0.0.1:" + server.getPort() + "/metrics")).timeout(Duration.ofSeconds(3)).GET().build();
      var metrics = client.send(metricRequest, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, metrics.statusCode());
      assertTrue(metrics.body().lines().anyMatch(
          ("wpilog_replica_pending_publications{store_id=\"" + storeId + "\"} 0")::equals));
      assertEquals(walks, door.discoveryWalks(), "Status cannot trigger discovery, even with no valid cached listing");
      assertEquals(recoveries, store.publicationWalks(), "Status cannot trigger the recovery walker");
    } finally {
      release.countDown();
      held.get(10, TimeUnit.SECONDS);
    }
  }

  @Test void statusRechecksRevokedGrantsAndChangedStoreIdentity() throws Exception {
    var original = store.replicaHeader();
    manager.clearAllowedDirectories();
    manager.addAllowedDirectory(Files.createDirectory(temp.resolve("unrelated")));
    var revoked = request("/status", selection());
    assertEquals(409, revoked.statusCode(), revoked.body());
    assertEquals("SOURCE_UNAVAILABLE", json(revoked).get("code").getAsString());
    manager.addAllowedDirectory(temp);
    var replacement = new StoreManifest.Header(2, original.createdAt(), UUID.randomUUID().toString(),
        List.of(), java.util.Map.of(), false, List.of(), null,
        new ReplicaCatalog(1, UUID.randomUUID().toString(), 1, List.of(), List.of(), List.of()));
    new StoreFiles(store.root(), manager.testGetSecurityValidator())
        .writeDurable(store.root().resolve("store.json"), replacement);
    var changed = request("/status", selection());
    assertEquals(409, changed.statusCode(), changed.body());
    assertEquals("SOURCE_UNAVAILABLE", json(changed).get("code").getAsString());
    peer = json(request("/handshake", null)).get("session_id").getAsString();
    storeId = replacement.id();
    var oldOwner = request("/status", selection());
    assertEquals(503, oldOwner.statusCode(), oldOwner.body());
    assertEquals("BUSY", json(oldOwner).get("code").getAsString(), "An old owner cannot relabel pending entries for a new store");
  }

  @Test void malformedStatusRequestsAndClosedSessionsKeepSpecificBoundedRefusals() throws Exception {
    for (String body : List.of("{", "[]", "{}", "{\"store_id\":7}", selection(null, 0),
        selection(null, 257), selection("x".repeat(1025), 1), "{\"store_id\":\"" + storeId + "\",\"extra\":1}")) {
      var response = request("/status", body);
      assertEquals(400, response.statusCode(), response.body());
      assertEquals("INVALID_ARGUMENT", json(response).get("code").getAsString());
    }
    assertEquals(413, request("/status", " ".repeat(65_537)).statusCode());
    assertEquals(403, request("/status", selection(), "Origin", "https://untrusted.example").statusCode());
    assertEquals(400, request("/status?store_id=" + storeId, selection()).statusCode());
    var unknown = JsonParser.parseString(selection()).getAsJsonObject();
    unknown.addProperty("store_id", UUID.randomUUID().toString());
    assertEquals(409, request("/status", unknown.toString()).statusCode());
    assertEquals(200, request("/close", "{}").statusCode());
    var expired = request("/status", selection());
    assertEquals(409, expired.statusCode());
    assertEquals("SESSION_EXPIRED", json(expired).get("code").getAsString());
    assertEquals(200, request("/close", "{}").statusCode(), "Closing is idempotent");
  }

  @Test void encodedByteShorteningPreservesTheTrueTotalAndExactSelectedLookup() throws Exception {
    var field = LogStore.class.getDeclaredField("replicaPublications");
    field.setAccessible(true);
    var publications = (ReplicaPublications) field.get(store);
    var names = new ArrayList<String>();
    // JSON escapes each NUL as six ASCII bytes, while the accepted ID is only1003 characters.
    for (int i = 0; i < 256; i++) names.add(String.format(java.util.Locale.ROOT, "%03d", i) + "\0".repeat(1000));
    store.captureAsync(io -> {
      publications.begin(storeId);
      for (int i = 0; i < names.size(); i++) publications.observed(new ReplicaPublications.Event(
          store.root().resolve("synthetic-" + i + "/session.json"),
          new ReplicaPublications.Entry(names.get(i), ReplicaPublications.unknown())));
      publications.finish(true, null);
      return null;
    }).get(10, TimeUnit.SECONDS);
    var response = request("/status", selection(null, 256));
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1_048_576);
    var body = json(response);
    var rows = body.getAsJsonArray("pending_publications");
    assertTrue(rows.size() > 0 && rows.size() < 256, "Escaped row bytes must shorten the count-bounded page");
    assertEquals(256, body.get("pending_total").getAsInt());
    assertFalse(body.get("oldest_time_complete").getAsBoolean());
    assertTrue(body.get("oldest_failed_at").isJsonNull());
    var limits = body.getAsJsonObject("limits").getAsJsonObject("pending_publications");
    assertEquals(rows.size(), limits.get("returned").getAsInt());
    assertEquals(256, limits.get("total").getAsInt());
    assertEquals(256, limits.get("limit").getAsInt());
    for (int i = 0; i < rows.size(); i++)
      assertEquals(names.get(i), rows.get(i).getAsJsonObject().get("session_id").getAsString());
    var omitted = request("/status", selection(names.get(255), 1));
    assertEquals(409, omitted.statusCode());
    assertEquals("PUBLICATION_PENDING", json(omitted).get("code").getAsString());
    assertEquals(names.get(255), json(omitted).get("session_id").getAsString());
    assertEquals("CAUSE_NOT_RECORDED", json(omitted).getAsJsonObject("cause").get("code").getAsString());
  }

  @ParameterizedTest @ValueSource(strings = {"grant", "session"})
  void accessRevokedDuringFinalStatusRecheckCannotPublishAReply(String kind) throws Exception {
    finalStatusRecheck(kind);
  }

  @ParameterizedTest @ValueSource(strings = {"header", "owner"})
  void changedPublicationDuringFinalStatusRecheckCannotPublishAReply(String kind) throws Exception {
    finalStatusRecheck(kind);
  }

  private void finalStatusRecheck(String kind) throws Exception {
    var grants = new java.util.concurrent.atomic.AtomicReference<>(Set.of(temp));
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var armed = new java.util.concurrent.atomic.AtomicBoolean();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var door = new StoreDoor(() -> {
      if (armed.get() && calls.incrementAndGet() == 2) {
        entered.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Final grant recheck barrier expired"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
      }
      return grants.get();
    });
    try (var protocol = new ReplicaProtocol(door, manager.stores())) {
      var hello = protocol.handshake();
      armed.set(true);
      var executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "synthetic-status-recheck"); thread.setDaemon(true); return thread;
      });
      try {
        var reply = executor.submit(() -> protocol.status(hello.sessionId(), storeId, null, 64));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        switch (kind) {
          case "grant" -> grants.set(Set.of());
          case "session" -> protocol.closeSession(hello.sessionId());
          case "header" -> {
            var beforeOwner = store.publicationStatus();
            var beforeHeader = store.replicaHeader();
            var catalog = beforeHeader.replicas();
            var changed = beforeHeader.withReplicas(new ReplicaCatalog(catalog.schemaVersion(),
                catalog.epoch(), Math.addExact(catalog.revision(), 1), catalog.files(),
                catalog.includedSessions(), catalog.exclusions()));
            new StoreFiles(store.root(), manager.testGetSecurityValidator())
                .writeDurable(store.root().resolve("store.json"), changed);
            assertSame(beforeOwner, store.publicationStatus(), "Only the catalog witness changes in this control");
            assertEquals(catalog.revision() + 1, store.replicaHeader().replicas().revision());
          }
          case "owner" -> {
            var beforeOwner = store.publicationStatus();
            var beforeHeader = store.replicaHeader();
            var field = LogStore.class.getDeclaredField("replicaPublications");
            field.setAccessible(true);
            var publications = (ReplicaPublications) field.get(store);
            store.captureAsync(io -> {
              publications.begin(storeId);
              publications.finish(true, null);
              return null;
            }).get(5, TimeUnit.SECONDS);
            assertNotSame(beforeOwner, store.publicationStatus(), "The immutable owner witness is replaced");
            assertEquals(beforeHeader, store.replicaHeader(), "Only the owner witness changes in this control");
          }
          default -> throw new AssertionError("Unknown synthetic recheck case: " + kind);
        }
        release.countDown();
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> reply.get(5, TimeUnit.SECONDS), "A changed " + kind + " cannot publish the prepared status reply");
        assertInstanceOf(java.io.IOException.class, failure.getCause());
        if (kind.equals("session")) assertTrue(failure.getCause().getMessage().startsWith("SESSION_EXPIRED:"));
        if (kind.equals("header") || kind.equals("owner"))
          assertTrue(failure.getCause().getMessage().startsWith("BUSY:"), "A changed publication asks for a fresh read");
      } finally { release.countDown(); executor.shutdownNow(); }
    }
  }

  @Test void incompleteInventoryKeepsAKnownCauseButNeverPublishesAnInventedTotal() throws Exception {
    var field = LogStore.class.getDeclaredField("replicaPublications");
    field.setAccessible(true);
    var publications = (ReplicaPublications) field.get(store);
    store.captureAsync(io -> {
      publications.begin(storeId);
      publications.observed(new ReplicaPublications.Event(store.root().resolve("known/session.json"),
          new ReplicaPublications.Entry("known", ReplicaPublications.unknown())));
      publications.finish(false, "SOURCE_UNAVAILABLE");
      return null;
    }).get(10, TimeUnit.SECONDS);
    var status = request("/status", selection());
    assertEquals(503, status.statusCode(), status.body());
    assertEquals("BUSY", json(status).get("code").getAsString());
    assertTrue(json(status).get("retryable").getAsBoolean());
    assertFalse(json(status).has("pending_total"), "Incomplete knowledge cannot become a complete zero total");
    var known = request("/status", selection("known", 1));
    assertEquals(409, known.statusCode());
    assertEquals("PUBLICATION_PENDING", json(known).get("code").getAsString());
    assertEquals("CAUSE_NOT_RECORDED", json(known).getAsJsonObject("cause").get("code").getAsString());
    var unknown = request("/status", selection("unknown", 1));
    assertEquals(503, unknown.statusCode());
    assertEquals("BUSY", json(unknown).get("code").getAsString(), "Incomplete is not absent");
    assertTrue(manager.stores().publicationCounts().stream().noneMatch(row -> row.storeId().equals(storeId)),
        "Metrics omit incomplete totals rather than replacing them with the visible count");
  }

  @Test void copiedStoreIdentitiesRemainAmbiguousWithinTheHandshake() throws Exception {
    var copy = manager.stores().store(temp.resolve("copied"));
    try {
      new StoreFiles(copy.root(), manager.testGetSecurityValidator())
          .writeDurable(copy.root().resolve("store.json"), store.replicaHeader());
      var field = HttpTransport.class.getDeclaredField("storeDoor");
      field.setAccessible(true);
      ((StoreDoor) field.get(server)).refresh();
      var hello = request("/handshake", null);
      assertEquals(200, hello.statusCode(), hello.body());
      peer = json(hello).get("session_id").getAsString();
      var response = request("/status", selection());
      assertEquals(409, response.statusCode(), response.body());
      assertEquals("SOURCE_SELECTION_REQUIRED", json(response).get("code").getAsString());
    } finally { copy.close(); }
  }
}
