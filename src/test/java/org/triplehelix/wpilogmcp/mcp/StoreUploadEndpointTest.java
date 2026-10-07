/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

/** Laptop bytes enter the same classified/verified pipeline, never a client-selected server path. */
class StoreUploadEndpointTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  final HttpClient client = HttpClient.newHttpClient();
  Set<Path> saved;
  HttpTransport server;
  Path root, source;
  String base, storeId;
  byte[] bytes;
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); saved = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    root = temp.resolve("store");
    manager.stores().store(root).importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
    storeId = StoreCatalog.readManaged(root, manager.testGetSecurityValidator()).header().id();
    source = ImportFixture.write(temp.resolve("laptop/source.wpilog"), 17, "SYNTHETIC-UPLOAD", 1_767_225_600_000_000L);
    bytes = Files.readAllBytes(source);
    server = new HttpTransport(new ToolRegistry(), 0, "0.0.0.0", null, null);
    server.setStoreDirectories(Set.of(root)); server.start();
    base = "http://127.0.0.1:" + server.getPort();
  }
  @AfterEach void stop() {
    server.stop(); manager.unloadAllLogs(); manager.clearAllowedDirectories(); saved.forEach(manager::addAllowedDirectory);
  }
  String hash(byte[] value) throws Exception {
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
  }
  HttpResponse<String> upload(String endpoint, String filename, String selector, byte[] body, String sha, String... headers) throws Exception {
    var uri = URI.create(endpoint + "/store/import?filename=" + URLEncoder.encode(filename, StandardCharsets.UTF_8)
        + (selector == null ? "" : "&store=" + URLEncoder.encode(selector, StandardCharsets.UTF_8)));
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/octet-stream")
        .header("X-WPILOG-SHA256", sha).POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (headers.length > 0) request.headers(headers);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
  JsonObject await(String endpoint, HttpResponse<String> accepted) throws Exception {
    assertEquals(202, accepted.statusCode(), accepted.body());
    String route = JsonParser.parseString(accepted.body()).getAsJsonObject().get("url").getAsString();
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      var response = client.send(HttpRequest.newBuilder(URI.create(endpoint + route)).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      var result = JsonParser.parseString(response.body()).getAsJsonObject();
      if (result.get("state").getAsString().equals("done")) return result.getAsJsonObject("result");
      assertNotEquals("failed", result.get("state").getAsString(), result.toString());
      Thread.yield();
    }
    throw new AssertionError("Upload import did not finish");
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aLaptopUploadIsVerifiedPlacedBySerialAndRecognizedAgain(boolean network) throws Exception {
    if (network) {
      var address = java.net.NetworkInterface.networkInterfaces().flatMap(java.net.NetworkInterface::inetAddresses)
          .filter(a -> a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()).findFirst();
      org.junit.jupiter.api.Assumptions.assumeTrue(address.isPresent(), "No nonloopback interface");
      base = "http://" + address.orElseThrow().getHostAddress() + ":" + server.getPort();
    }
    String name = "uploaded café.wpilog";
    var response = upload(base, name, storeId, bytes, hash(bytes));
    // The native path parser, independently of the server's check, decides what this JVM
    // can represent. Windows paths are Unicode even with a legacy sun.jnu.encoding.
    try { Path.of(name); }
    catch (java.nio.file.InvalidPathException unrepresentable) {
      assertEquals(400, response.statusCode(), response.body());
      assertTrue(response.body().contains(System.getProperty("sun.jnu.encoding")), response.body());
      assertTrue(response.body().contains("start the server with a UTF-8 locale"), response.body());
      assertFalse(response.body().contains(unrepresentable.getReason()), response.body());
      assertTrue(StoreCatalog.readManaged(root, manager.testGetSecurityValidator()).files().isEmpty());
      assertArrayEquals(bytes, Files.readAllBytes(source));
      return;
    }
    var first = await(base, response);
    assertEquals("imported", first.getAsJsonArray("files").get(0).getAsJsonObject().get("status").getAsString());
    var catalog = StoreCatalog.read(root, manager.testGetSecurityValidator());
    assertEquals(1, catalog.files().size()); assertEquals(1, catalog.sessions().size());
    var file = catalog.files().get(0);
    assertEquals("SYNTHETIC-UPLOAD", file.robot().serialNumber());
    assertEquals(name, file.path().getFileName().toString()); assertEquals(hash(bytes), file.file().sha256());
    assertEquals(bytes.length, file.file().sizeBytes()); assertTrue(file.file().verified());
    assertArrayEquals(bytes, Files.readAllBytes(file.path())); assertArrayEquals(bytes, Files.readAllBytes(source));
    assertEquals("imported", file.file().provenance().kind());
    assertEquals("upload:" + name, file.file().provenance().originalPath());
    assertFalse(file.file().provenance().moved());
    var again = await(base, upload(base, name, null, bytes, hash(bytes)));
    assertEquals("present", again.getAsJsonArray("files").get(0).getAsJsonObject().get("status").getAsString());
    assertEquals(1, StoreCatalog.readManaged(root, manager.testGetSecurityValidator()).files().size());
    try (var staged = Files.walk(root.resolve("inbox"))) {
      assertEquals(0, staged.filter(Files::isRegularFile).count(), "An HTTP-owned temporary copy must be reclaimed");
    }
  }
  @Test void theNetworkWriteSurfaceAcceptsBytesButNotServerPathsOrAssignment() throws Exception {
    var address = java.net.NetworkInterface.networkInterfaces().flatMap(java.net.NetworkInterface::inetAddresses)
        .filter(a -> a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()).findFirst();
    org.junit.jupiter.api.Assumptions.assumeTrue(address.isPresent(), "No nonloopback interface");
    String network = "http://" + address.orElseThrow().getHostAddress() + ":" + server.getPort();
    var body = new JsonObject(); body.addProperty("store", root.toString()); body.addProperty("move", false);
    body.addProperty("stated_robot", "synthetic"); var paths = new com.google.gson.JsonArray(); paths.add(source.toString()); body.add("paths", paths);
    for (String route : List.of("/store/import", "/store/assign")) {
      var response = client.send(HttpRequest.newBuilder(URI.create(network + route)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(403, response.statusCode(), response.body());
    }
    assertTrue(StoreCatalog.readManaged(root, manager.testGetSecurityValidator()).files().isEmpty());
  }
  @Test void aHashMismatchOrUnreadableUploadCannotEnterTheCatalog() throws Exception {
    assertEquals(400, upload(base, "wrong.wpilog", null, bytes, "0".repeat(64)).statusCode());
    byte[] invalid = "synthetic invalid format".getBytes(StandardCharsets.UTF_8);
    var result = await(base, upload(base, "invalid.wpilog", null, invalid, hash(invalid)));
    var refusal = result.getAsJsonArray("files").get(0).getAsJsonObject();
    assertEquals("refused", refusal.get("status").getAsString()); assertFalse(refusal.get("reason").getAsString().isBlank());
    assertTrue(StoreCatalog.read(root, manager.testGetSecurityValidator()).files().isEmpty());
  }
  @Test void uploadsRefuseTraversalOriginsMirrorsAndStoresOutsideTheDoor() throws Exception {
    assertEquals(400, upload(base, "../outside.wpilog", null, bytes, hash(bytes)).statusCode());
    assertEquals(403, upload(base, "web.wpilog", null, bytes, hash(bytes), "Origin", "https://untrusted.example").statusCode());
    var outside = manager.stores().store(temp.resolve("leased-store"));
    outside.importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
    String id = StoreCatalog.readManaged(outside.root(), manager.testGetSecurityValidator()).header().id();
    assertEquals(404, upload(base, "outside.wpilog", id, bytes, hash(bytes)).statusCode());
    var header = JsonParser.parseString(Files.readString(root.resolve("store.json"))).getAsJsonObject();
    header.addProperty("mirror", true); Files.writeString(root.resolve("store.json"), header.toString());
    assertEquals(409, upload(base, "mirror.wpilog", storeId, bytes, hash(bytes)).statusCode());
    assertTrue(StoreCatalog.readManaged(root, manager.testGetSecurityValidator()).files().isEmpty());
  }
}
