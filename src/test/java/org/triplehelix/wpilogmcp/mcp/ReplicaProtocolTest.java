/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.*;

class ReplicaProtocolTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  final HttpClient client = HttpClient.newHttpClient();
  Set<Path> previous;
  HttpTransport server;
  ToolRegistry registry;
  LogStore store;
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    store = manager.stores().store(temp.resolve("master"));
    store.importPaths(new LogStore.Request(List.of(ImportFixture.write(temp.resolve("one.wpilog"), 7)), false, "RIO"), p -> {}).get();
    var header = StoreJson.JSON.fromJson(Files.readString(store.root().resolve("store.json")), StoreManifest.Header.class);
    store.migrateReplicas(header.id()).get();
    registry = new ToolRegistry(); server = new HttpTransport(registry, 0); server.setStoreDirectories(Set.of(store.root())); server.start();
  }
  @AfterEach void cleanup() {
    if (server != null) server.stop(); manager.unloadAllLogs();
    manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
  HttpResponse<String> request(String route, String body, String... headers) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + route))
        .timeout(java.time.Duration.ofSeconds(5));
    if (body == null) b.GET(); else b.POST(HttpRequest.BodyPublishers.ofString(body));
    for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }
  @Test void handshakeAndSnapshotExposeFinalizedIdentitiesWithoutTrustingNames() throws Exception {
    var handshake = request("/replicas/v1/handshake", null);
    assertEquals(200, handshake.statusCode(), "versioned replica handshake must be available");
    var hello = JsonParser.parseString(handshake.body()).getAsJsonObject();
    assertEquals(1, hello.get("protocol_version").getAsInt());
    var choice = hello.getAsJsonArray("stores").get(0).getAsJsonObject();
    var page = request("/replicas/v1/catalog", "{\"store_id\":\"" + choice.get("store_id").getAsString() + "\",\"limit\":1}",
        "Replica-Session", hello.get("session_id").getAsString());
    assertEquals(200, page.statusCode(), page.body());
    var data = JsonParser.parseString(page.body()).getAsJsonObject();
    assertEquals(1, data.get("total").getAsInt()); assertTrue(data.get("next_cursor").isJsonNull());
    var file = data.getAsJsonArray("offers").get(0).getAsJsonObject();
    assertEquals(choice.get("master_store_id"), file.getAsJsonObject("identity").get("master_store_id"));
    assertEquals("master_local", file.get("verification_claim").getAsString());
    assertEquals("completed_import", file.get("completion_basis").getAsString());
    assertFalse(file.has("path"), "wire offers must not reveal server filesystem paths");
  }
  @Test void invalidReplicaRequestsHaveBoundedSpecificRefusals() throws Exception {
    assertEquals(400, request("/replicas/v1/catalog", "{").statusCode(), "malformed JSON is an argument refusal");
    assertEquals(413, request("/replicas/v1/catalog", " ".repeat(65_537)).statusCode());
    assertEquals(403, request("/replicas/v1/handshake", null, "Origin", "https://untrusted.example").statusCode());
    assertEquals(400, request("/replicas/v1/catalog", "{}", "Replica-Session", "x".repeat(2048)).statusCode());
    assertEquals(200, request("/health", null).statusCode());
  }
  @Test void catalogIoFailuresDoNotExposeServerPathsAsProtocolCodes() throws Exception {
    String id = store.replicaHeader().id();
    var hello = JsonParser.parseString(request("/replicas/v1/handshake", null).body()).getAsJsonObject();
    Files.writeString(store.root().resolve("store.json"), "{");
    var response = request("/replicas/v1/catalog", "{\"store_id\":\"" + id + "\"}",
        "Replica-Session", hello.get("session_id").getAsString());
    assertEquals(409, response.statusCode());
    var refusal = JsonParser.parseString(response.body()).getAsJsonObject();
    assertEquals("SOURCE_UNAVAILABLE", refusal.get("code").getAsString(), response.body());
    assertEquals("Replica source is unavailable", refusal.get("message").getAsString());
    assertFalse(response.body().contains(temp.toString()), "wire refusals must not expose server paths");
    assertFalse(refusal.get("retryable").getAsBoolean());
  }
  @Test void filesystemDriveLettersAndOrdinaryIoMessagesAreNotReplicaCodes() {
    for (var failure : List.of(new java.nio.file.NoSuchFileException("C:\\private\\capture.wpilog"),
        new java.io.IOException("/private/capture: disappeared"), new java.io.IOException())) {
      var refusal = ReplicaEndpoint.refusal(failure);
      assertEquals("SOURCE_UNAVAILABLE", refusal.get("code"));
      assertEquals("Replica source is unavailable", refusal.get("message"));
    }
    var busy = ReplicaEndpoint.refusal(new java.io.IOException("BUSY: retry catalog admission"));
    assertEquals("BUSY", busy.get("code")); assertEquals(true, busy.get("retryable"));
  }
  @Test void slowReplicaReadersDoNotReserveVisualizationMetadataCapacity() throws Exception {
    var input = temp.resolve("large.wpilog");
    try (var writer = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(input, "")) {
      int entry = writer.start("/Payload", "raw", "", 0); byte[] block = new byte[1 << 20];
      for (int i = 0; i < 16; i++) writer.append(entry, i * 1_000_000L, block);
    }
    store.importPaths(new LogStore.Request(List.of(input), false, "RIO"), p -> {}).get();
    var file = store.replicaHeader().replicas().files().stream().max(Comparator.comparingLong(ReplicaCatalog.Entry::sizeBytes)).orElseThrow();
    var hello = JsonParser.parseString(request("/replicas/v1/handshake", null).body()).getAsJsonObject();
    String session = hello.get("session_id").getAsString(); var body = new com.google.gson.JsonObject();
    body.addProperty("store_id", store.replicaHeader().id()); body.addProperty("master_store_id", file.identity().masterStoreId());
    body.addProperty("file_id", file.identity().fileId()); body.addProperty("file_version", file.identity().fileVersion()); body.addProperty("sha256", file.sha256());
    var bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var sockets = new ArrayList<java.net.Socket>();
    var count = org.triplehelix.wpilogmcp.visualization.RecordingInventories.class.getDeclaredMethod("responseBytes"); count.setAccessible(true);
    try {
      for (int i = 0; i < 4; i++) {
        var socket = new java.net.Socket(); socket.setReceiveBufferSize(1024); socket.connect(new java.net.InetSocketAddress("127.0.0.1", server.getPort()));
        socket.setSoTimeout(5000); sockets.add(socket);
        socket.getOutputStream().write(("POST /replicas/v1/file HTTP/1.1\r\nHost: localhost\r\nReplica-Session: " + session + "\r\nContent-Length: " + bytes.length + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
        var headers = new StringBuilder(); while (!headers.toString().endsWith("\r\n\r\n")) { int value = socket.getInputStream().read(); assertTrue(value >= 0); headers.append((char) value); }
        assertTrue(headers.toString().startsWith("HTTP/1.1 200"), headers.toString());
      }
      assertEquals(0, count.invoke(registry.visualizations().inventories), "file streams must not reserve visualization replies");
      try (var response = registry.visualizations().inventories.reserveMetadataResponse()) {
        assertEquals(2 << 20, count.invoke(registry.visualizations().inventories), "inventory and library metadata admission stays available");
      }
      assertEquals(200, request("/replicas/v1/handshake", null).statusCode(), "replica metadata has its own workers");
      assertEquals(403, request("/replicas/v1/handshake", null, "Origin", "https://untrusted.example").statusCode());
      assertEquals(200, request("/health", null).statusCode());
      var init = request("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"); assertEquals(200, init.statusCode());
      String mcp = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
      assertEquals(200, request("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", "Mcp-Session-Id", mcp).statusCode());
      assertEquals(200, request("/replicas/v1/close", "{}", "Replica-Session", session).statusCode());
      assertEquals(0, count.invoke(registry.visualizations().inventories));
    } finally { for (var socket : sockets) socket.close(); }
    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while ((int) count.invoke(registry.visualizations().inventories) != 0 && System.nanoTime() < end) Thread.sleep(10);
    assertEquals(0, count.invoke(registry.visualizations().inventories));
  }
  @Test void completedImportsAfterMigrationReceiveStableVersionIdentities() throws Exception {
    store.importPaths(new LogStore.Request(List.of(ImportFixture.write(temp.resolve("two.wpilog"), 99)), false, "RIO"), p -> {}).get();
    var header = store.replicaHeader();
    assertEquals(2, header.replicas().files().size(), "post-migration import must publish a second finalized identity");
    assertEquals(2, header.replicas().files().stream().map(ReplicaCatalog.Entry::identity).distinct().count());
    assertTrue(header.replicas().files().stream().allMatch(f -> f.verificationBasis().equals("master_local")));
  }
  @Test void movingAnImportDoesNotOverwriteTheNewReplicaCatalogWithAnOlderHeader() throws Exception {
    var before = store.replicaHeader().replicas().files().get(0).identity();
    var input = ImportFixture.write(temp.resolve("moved.wpilog"), 101);
    var result = store.importPaths(new LogStore.Request(List.of(input), true, "RIO"), p -> {}).get();
    assertFalse(Files.exists(input), result.toString());
    assertEquals(2, store.replicaHeader().replicas().files().size());
    assertTrue(store.replicaHeader().replicas().files().stream().anyMatch(f -> before.equals(f.identity())));
  }
  @Test void configuredMirrorPullsAndConfirmsOnlyThroughItsDirectMaster() throws Exception {
    var mirror = manager.stores().store(temp.resolve("replica"));
    var config = new org.triplehelix.wpilogmcp.config.MirrorConfig("http://127.0.0.1:" + server.getPort(), mirror.root(),
        14, 20_000_000, List.of(), List.of(), 30, 0, "all", 1_048_576);
    assertEquals("migration_required", mirror.mirror(config, p -> {}).get().state());
    mirror.migrateReplicas(mirror.replicaHeader().id()).get();
    var result = mirror.mirror(config, p -> {}).get(20, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals("synchronized", result.state(), result.toString());
    var local = mirror.replicaHeader().replicas().files(); assertEquals(1, local.size());
    assertEquals("verified", local.get(0).verificationBasis());
    assertEquals(store.replicaHeader().replicas().files().get(0).identity(), local.get(0).identity());
    assertEquals(config.origin(), local.get(0).confirmation().obtainedFrom());
    assertArrayEquals(Files.readAllBytes(store.root().resolve(store.replicaHeader().replicas().files().get(0).path())),
        Files.readAllBytes(mirror.root().resolve(local.get(0).path())));
    assertEquals(0, mirror.mirror(config, p -> {}).get().bytesCopied(), "repeat reconciliation must reuse bytes");
  }
}
