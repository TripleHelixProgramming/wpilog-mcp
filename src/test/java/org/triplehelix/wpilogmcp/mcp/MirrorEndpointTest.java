/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.tools.CoreTools;

class MirrorEndpointTest {
  @TempDir Path temp;
  @TempDir Path forbidden;
  final LogManager manager = LogManager.getInstance();
  final HttpClient client = HttpClient.newHttpClient();
  Set<Path> previous; HttpTransport origin, local; Path source, target;
  List<Path> listed;
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); previous = manager.getAllowedDirectories(); manager.clearAllowedDirectories(); manager.addAllowedDirectory(temp);
    var input = ImportFixture.write(temp.resolve("fixture.wpilog"), 1);
    var store = manager.stores().store(temp.resolve("origin"));
    source = store.importPaths(new LogStore.Request(List.of(input), false, "RIO"), p -> {}).get().files().get(0).path();
    target = temp.resolve("mirror");
    listed = org.triplehelix.wpilogmcp.log.LogDirectory.getInstance().getLogDirectories();
    org.triplehelix.wpilogmcp.log.LogDirectory.getInstance().setLogDirectories(List.of(target.toString()));
    origin = new HttpTransport(new ToolRegistry(), 0); origin.setStoreDirectories(Set.of(store.root())); origin.start();
    local = new HttpTransport(new ToolRegistry(), 0); local.setStoreDirectories(Set.of(target)); local.start();
  }
  @AfterEach void cleanup() {
    local.stop(); origin.stop(); manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
    org.triplehelix.wpilogmcp.log.LogDirectory.getInstance().setLogDirectories(listed.stream().map(Path::toString).toList());
  }
  HttpResponse<String> request(String route, String method, String body, String... headers) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + local.getPort() + route)).timeout(Duration.ofSeconds(5));
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
  JsonObject configuration() {
    var result = new JsonObject(); result.addProperty("origin", "http://127.0.0.1:" + origin.getPort()); result.addProperty("folder", target.toString());
    result.addProperty("days", 365000); return result;
  }
  JsonObject status() throws Exception { return JsonParser.parseString(request("/store/mirror", "GET", null).body()).getAsJsonObject(); }
  JsonObject await(String state) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    JsonObject status;
    do { status = status(); if (status.get("state").getAsString().equals(state)) return status; Thread.yield(); } while (System.nanoTime() < deadline);
    fail("Mirror did not reach " + state + ": " + status); return null;
  }
  @Test void turningTheMirrorOffWaitsForAChangeInProgressAndTheTwoControlsShareOneGuard() throws Exception {
    // Held: one of the two controls is changing. Neither may start, and the mirror may not be turned
    // off and reported disabled while a configuration is about to start its next service.
    var guard = new java.util.concurrent.atomic.AtomicBoolean(true);
    try (var mirror = new MirrorEndpoint(manager.stores(), () -> null, guard);
         var live = new LiveSourceEndpoint(manager, new java.util.concurrent.atomic.AtomicReference<>(), mirror::folder, guard)) {
      assertTrue(assertThrows(java.io.IOException.class, mirror::disable).getMessage().contains("already changing"));
      var request = JsonParser.parseString("{\"robot\":{\"host\":\"127.0.0.1\",\"port\":9},\"store\":"
          + new com.google.gson.JsonPrimitive(temp.resolve("live").toString()) + "}").getAsJsonObject();
      assertTrue(assertThrows(java.io.IOException.class, () -> live.configure(request)).getMessage().contains("already changing"));
      var config = org.triplehelix.wpilogmcp.config.MirrorConfig.parse(configuration(), v -> v);
      assertTrue(assertThrows(java.io.IOException.class, () -> mirror.configure(config)).getMessage().contains("already changing"));
      assertEquals("disabled", JsonParser.parseString(request("/store/mirror", "GET", null).body()).getAsJsonObject().get("state").getAsString());
      guard.set(false);
      assertEquals(java.util.Map.of("state", "disabled"), mirror.disable());
      assertFalse(guard.get(), "the guard is released after turning the mirror off");
    }
  }
  @Test void theServersMirrorAndLiveControlsShareTheirGuard() throws Exception {
    local.localControlsGuard().set(true); // a change of either control in progress
    try {
      assertEquals(409, request("/store/mirror/configure", "POST", configuration().toString()).statusCode());
      assertEquals(409, request("/store/mirror", "DELETE", null).statusCode());
      var live = "{\"robot\":{\"host\":\"127.0.0.1\",\"port\":9},\"store\":" + new com.google.gson.JsonPrimitive(temp.resolve("live").toString()) + "}";
      var refused = request("/store/live/configure", "POST", live);
      assertEquals(409, refused.statusCode(), refused.body()); assertTrue(refused.body().contains("already changing"), refused.body());
    } finally { local.localControlsGuard().set(false); }
    assertEquals(200, request("/store/mirror", "DELETE", null).statusCode());
  }
  @Test void configurationPinsAndLegacyListingRemainAvailableWhileMigrationIsPaused() throws Exception {
    assertEquals("disabled", status().get("state").getAsString());
    org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(manager.stores().store(target),
        org.triplehelix.wpilogmcp.config.MirrorConfig.parse(configuration(), s -> s), p -> {}).get();
    var configured = request("/store/mirror/configure", "POST", configuration().toString()); assertEquals(202, configured.statusCode(), configured.body());
    var synchronizedState = await("migration_required"); assertEquals(0, synchronizedState.get("remaining_files").getAsInt());
    var catalog = StoreCatalog.readManaged(target, manager.testGetSecurityValidator()); String id = catalog.sessions().get(0).session().id();
    var pin = new JsonObject(); pin.addProperty("session_id", id);
    for (boolean pinned : List.of(true, false)) {
      assertEquals(202, request("/store/mirror/" + (pinned ? "pin_session" : "unpin_session"), "POST", pin.toString()).statusCode());
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (status().getAsJsonObject("origin").getAsJsonArray("pinned_sessions").size() != (pinned ? 1 : 0) && System.nanoTime() < deadline) Thread.yield();
      assertEquals(pinned ? 1 : 0, status().getAsJsonObject("origin").getAsJsonArray("pinned_sessions").size());
    }
    origin.stop();
    assertEquals(202, request("/store/mirror/sync", "POST", "{}").statusCode());
    var offline = await("migration_required"); assertEquals(synchronizedState.get("last_sync"), offline.get("last_sync"));
    assertTrue(offline.get("age_sec").getAsLong() >= 0);
    var registry = new ToolRegistry(); CoreTools.registerAll(registry);
    var result = registry.getTool("list_available_logs").execute(new JsonObject()).getAsJsonObject();
    assertEquals("ok", result.get("status").getAsString(), result.toString());
    var log = result.getAsJsonArray("logs").asList().stream().map(e -> e.getAsJsonObject())
        .filter(e -> e.get("path").getAsString().startsWith(target.toString())).findFirst().orElseThrow();
    var session = log.getAsJsonObject("session");
    assertEquals(configuration().get("origin"), session.get("origin")); assertTrue(session.get("complete").getAsBoolean());
    assertFalse(session.get("growing").getAsBoolean()); assertEquals(synchronizedState.getAsJsonObject("origin").getAsJsonObject("sessions").getAsJsonObject(id).get("last_sync"), session.get("last_sync")); assertTrue(session.has("age_sec"));
    assertEquals("ok", result.get("status").getAsString());
    assertEquals(200, request("/store/mirror", "DELETE", null).statusCode()); assertEquals("disabled", status().get("state").getAsString());
    assertTrue(Files.exists(catalog.files().get(0).path()));
  }
  @Test void controlsRefuseOutsidePathsInvalidKeysAndWebOrigins() throws Exception {
    assertEquals(403, request("/store/mirror", "GET", null, "Origin", "https://evil.example").statusCode());
    assertEquals(400, request("/store/mirror/configure", "POST", "{\"typo\":1}").statusCode());
    var outside = configuration(); outside.addProperty("folder", forbidden.resolve("mirror").toString());
    assertEquals(409, request("/store/mirror/configure", "POST", outside.toString()).statusCode());
    assertEquals(409, request("/store/mirror/pin_session", "POST", "{\"session_id\":\"x\"}").statusCode());
    assertEquals(200, request("/health", "GET", null).statusCode());
  }

  @Test void migrationIsExplicitAndDoesNotEnableThePayloadWriter() throws Exception {
    assertEquals(202, request("/store/mirror/configure", "POST", configuration().toString()).statusCode());
    await("migration_required");
    var header = JsonParser.parseString(Files.readString(target.resolve("store.json"))).getAsJsonObject();
    var body = new JsonObject(); body.add("store_id", header.get("id"));
    var migrated = request("/store/mirror/migrate", "POST", body.toString());
    assertEquals(202, migrated.statusCode(), migrated.body());
    await("writer_not_ready");
    var after = JsonParser.parseString(Files.readString(target.resolve("store.json"))).getAsJsonObject();
    assertEquals(2, after.get("format_version").getAsInt());
    assertTrue(after.getAsJsonObject("replicas").getAsJsonArray("files").isEmpty());
    assertTrue(Files.isRegularFile(target.resolve(".replicas/format-1-store.json")));
    assertEquals(409, request("/store/mirror/cleanup", "POST", "{}").statusCode());
    assertEquals(200, request("/health", "GET", null).statusCode());
  }

  @Test void masterFormatMigrationUsesAnExplicitLocalJobAndPreservesPayloads() throws Exception {
    var root = temp.resolve("origin"); local.stop();
    local = new HttpTransport(new ToolRegistry(), 0); local.setStoreDirectories(Set.of(root)); local.start();
    String storeId = StoreCatalog.readManaged(root, manager.testGetSecurityValidator()).header().id();
    byte[] expected = Files.readAllBytes(source);
    var input = new JsonObject(); input.addProperty("store_id", storeId); String body = input.toString();
    var response = request("/store/replicas/migrate", "POST", body);
    assertEquals(202, response.statusCode(), response.body());
    String id = JsonParser.parseString(response.body()).getAsJsonObject().get("job_id").getAsString();
    JsonObject job = null; long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    do {
      job = JsonParser.parseString(request("/store/replicas/jobs/" + id, "GET", null).body()).getAsJsonObject();
      if (!job.get("state").getAsString().equals("running")) break;
    } while (System.nanoTime() < deadline);
    assertEquals("complete", job.get("state").getAsString(), job.toString());
    assertEquals(1, job.get("files").getAsInt()); assertEquals(2, job.get("format_version").getAsInt());
    assertArrayEquals(expected, Files.readAllBytes(source));
    assertEquals(403, request("/store/replicas/migrate", "POST", body, "Origin", "https://evil.example").statusCode());
    assertEquals(403, request("/store/replicas/migrate", "POST", body, "X-Forwarded-For", "192.0.2.1").statusCode());
    assertEquals(400, request("/store/replicas/migrate", "POST",
        "{\"store_id\":\"" + storeId + "\",\"store_id\":\"" + storeId + "\"}").statusCode());
    assertEquals(400, request("/store/replicas/migrate", "POST", "{\"pad\":\"" + "x".repeat(65_536) + "\"}").statusCode());
    assertEquals(400, request("/store/replicas/migrate", "POST", "{\"pad\":" + "[".repeat(160) + "0" + "]".repeat(160) + "}").statusCode());
    assertEquals(409, request("/store/replicas/migrate", "POST", "{\"store_id\":\"" + java.util.UUID.randomUUID() + "\"}").statusCode());
    assertEquals(404, request("/store/replicas/jobs/" + java.util.UUID.randomUUID(), "GET", null).statusCode());
  }

  @Test void replicaPagesAndPreviewAdvanceWithExplicitInclusion() throws Exception {
    var second = ImportFixture.write(temp.resolve("second.wpilog"), 2);
    manager.stores().store(temp.resolve("origin")).importPaths(new LogStore.Request(List.of(second), false, "RIO"), p -> {}).get();
    org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(manager.stores().store(target),
        org.triplehelix.wpilogmcp.config.MirrorConfig.parse(configuration(), s -> s), p -> {}).get();
    var expected = StoreCatalog.readManaged(target, manager.testGetSecurityValidator()).allFiles().stream()
        .map(file -> file.file().sha256()).collect(java.util.stream.Collectors.toSet());
    assertEquals(2, expected.size());
    assertEquals(202, request("/store/mirror/configure", "POST", configuration().toString()).statusCode()); await("migration_required");
    var header = JsonParser.parseString(Files.readString(target.resolve("store.json"))).getAsJsonObject();
    assertEquals(202, request("/store/mirror/migrate", "POST", "{\"store_id\":" + header.get("id") + "}").statusCode()); await("writer_not_ready");
    var seen = new java.util.HashSet<String>(); JsonObject first = null;
    for (int offset = 0; offset < 3; offset++) {
      var response = request("/store/mirror/files?offset=" + offset + "&limit=1", "GET", null);
      assertEquals(200, response.statusCode(), response.body());
      var page = JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals(2, page.get("total").getAsInt()); assertEquals(1, page.get("catalog_revision").getAsLong());
      assertEquals(offset == 0, page.get("has_more").getAsBoolean());
      assertEquals(offset < 2 ? 1 : 0, page.getAsJsonArray("files").size());
      if (offset < 2) {
        var entry = page.getAsJsonArray("files").get(0).getAsJsonObject(); if (first == null) first = entry;
        assertTrue(seen.add(entry.get("sha256").getAsString()), "duplicate page member");
        assertEquals("legacy_unconfirmed", entry.get("verification_basis").getAsString());
      }
    }
    assertEquals(expected, seen); assertEquals(400, request("/store/mirror/files?limit=257", "GET", null).statusCode());
    assertEquals(202, request("/store/mirror/pin_session", "POST", "{\"session_id\":" + first.get("session_id") + "}").statusCode());
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (status().getAsJsonObject("origin").getAsJsonArray("pinned_sessions").isEmpty() && System.nanoTime() < deadline) Thread.yield();
    assertEquals(1, status().getAsJsonObject("origin").getAsJsonArray("pinned_sessions").size());
    var response = request("/store/mirror/cleanup_preview", "POST", "{\"keys\":[" + first.get("key") + "]}");
    assertEquals(200, response.statusCode(), response.body()); var preview = JsonParser.parseString(response.body()).getAsJsonObject();
    assertEquals(2, preview.get("catalog_revision").getAsLong());
    assertEquals(first.get("size_bytes").getAsLong(), preview.get("payload_bytes").getAsLong());
    assertEquals(first, preview.getAsJsonArray("files").get(0));
    assertEquals(2, status().getAsJsonObject("replicas").get("catalog_revision").getAsLong());
    assertEquals(expected, StoreCatalog.readManaged(target, manager.testGetSecurityValidator()).allFiles().stream()
        .map(file -> file.file().sha256()).collect(java.util.stream.Collectors.toSet()));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"configure", "sync", "pin_session", "unpin_session"})
  void aNetworkBoundServerRefusesEveryMirrorWriteRoute(String route) throws Exception {
    local.stop(); local = new HttpTransport(new ToolRegistry(), 0, "0.0.0.0", null, null);
    local.setStoreDirectories(Set.of(target)); local.start();
    var address = java.net.NetworkInterface.networkInterfaces().flatMap(java.net.NetworkInterface::inetAddresses)
        .filter(a -> a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()).findFirst();
    org.junit.jupiter.api.Assumptions.assumeTrue(address.isPresent(), "No nonloopback interface available");
    var uri = URI.create("http://" + address.orElseThrow().getHostAddress() + ":" + local.getPort() + "/store/mirror/" + route);
    var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
        .POST(HttpRequest.BodyPublishers.ofString(configuration().toString())).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(403, response.statusCode(), route + ": " + response.body());
    assertTrue(response.body().contains("loopback"));
    assertFalse(Files.exists(target.resolve("store.json")));
  }

  @Test void aPausedMirrorDoesNotContactTheOriginAndHealthStaysAvailable() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
    var proxy = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    proxy.createContext("/", exchange -> {
      try {
        if (exchange.getRequestURI().getPath().equals("/store")) {
          entered.countDown(); if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("test origin timed out");
        }
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + origin.getPort() + exchange.getRequestURI())).GET();
        String range = exchange.getRequestHeaders().getFirst("Range"); if (range != null) request.header("Range", range);
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        response.headers().firstValue("Content-Range").ifPresent(value -> exchange.getResponseHeaders().set("Content-Range", value));
        exchange.sendResponseHeaders(response.statusCode(), response.body().length); exchange.getResponseBody().write(response.body());
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      finally { exchange.close(); }
    });
    proxy.start();
    try {
      var config = configuration(); config.addProperty("origin", "http://127.0.0.1:" + proxy.getAddress().getPort());
      assertEquals(202, request("/store/mirror/configure", "POST", config.toString()).statusCode());
      await("migration_required"); assertEquals(1, entered.getCount(), "No origin request while migration is required");
      assertEquals(200, request("/health", "GET", null).statusCode());
      assertEquals("migration_required", status().get("state").getAsString());
    } finally { release.countDown(); proxy.stop(0); }
  }
}
