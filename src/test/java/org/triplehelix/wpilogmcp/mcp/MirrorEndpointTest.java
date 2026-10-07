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
  @Test void configurationSyncPinsAndOfflineListingUseTheLocalServer() throws Exception {
    assertEquals("disabled", status().get("state").getAsString());
    var configured = request("/store/mirror/configure", "POST", configuration().toString()); assertEquals(202, configured.statusCode(), configured.body());
    var synchronizedState = await("synchronized"); assertEquals(0, synchronizedState.get("remaining_files").getAsInt());
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
    var offline = await("offline"); assertEquals(synchronizedState.get("last_sync"), offline.get("last_sync"));
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

  @Test void aSlowOriginDoesNotDelayTheLocalHealthEndpoint() throws Exception {
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
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(200, request("/health", "GET", null).statusCode());
      assertEquals("synchronizing", status().get("state").getAsString());
    } finally { release.countDown(); await("synchronized"); proxy.stop(0); }
  }
}
