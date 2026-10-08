/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
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

class StoreDoorTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  final HttpClient client = HttpClient.newHttpClient();
  Set<Path> previous;
  HttpTransport transport;
  Path root, log;

  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); root = temp.resolve("store");
    previous = manager.getAllowedDirectories(); manager.clearAllowedDirectories(); manager.addAllowedDirectory(temp);
    var source = ImportFixture.write(temp.resolve("source.wpilog"), 23);
    log = manager.stores().store(root).importPaths(new LogStore.Request(List.of(source), false, "practice"), p -> {})
        .get().files().get(0).path();
    transport = new HttpTransport(new ToolRegistry(), 0); transport.start();
  }
  @AfterEach void cleanup() {
    transport.stop(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
  HttpResponse<byte[]> get(String path, String... headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + transport.getPort() + path))
        .timeout(Duration.ofSeconds(10)).GET();
    for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }
  static String text(HttpResponse<byte[]> response) { return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8); }
  String path() { return "/store/files/" + root.relativize(log).toString().replace(java.io.File.separatorChar, '/'); }

  @Test void descriptorManifestsRangesAndHashesDescribeExactlyTheCatalog() throws Exception {
    var descriptor = get("/store"); assertEquals(200, descriptor.statusCode(), text(descriptor));
    var json = JsonParser.parseString(text(descriptor)).getAsJsonObject();
    assertEquals(1, json.get("format_version").getAsInt()); assertFalse(json.get("mirror").getAsBoolean());
    assertEquals(org.triplehelix.wpilogmcp.Version.VERSION, json.get("server_version").getAsString());
    assertEquals(JsonParser.parseString(Files.readString(root.resolve("store.json"))).getAsJsonObject().get("id"), json.get("id"));
    var bytes = Files.readAllBytes(log);
    var file = get(path()); assertEquals(200, file.statusCode()); assertArrayEquals(bytes, file.body());
    var range = get(path(), "Range", "bytes=3-10"); assertEquals(206, range.statusCode());
    assertEquals("bytes 3-10/" + bytes.length, range.headers().firstValue("Content-Range").orElseThrow());
    assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 3, 11), range.body());
    var hash = get(path() + "/prefix-hash?bytes=11"); assertEquals(200, hash.statusCode());
    assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(java.util.Arrays.copyOf(bytes, 11))), JsonParser.parseString(text(hash)).getAsJsonObject().get("sha256").getAsString());
    assertEquals(416, get(path(), "Range", "bytes=" + bytes.length + "-").statusCode());
    assertEquals(400, get(path() + "/prefix-hash?bytes=-1").statusCode());
    assertEquals(416, get(path() + "/prefix-hash?bytes=" + (bytes.length + 1)).statusCode());
    var sessions = JsonParser.parseString(text(get("/store/sessions"))).getAsJsonObject().getAsJsonArray("sessions");
    assertEquals(1, sessions.size());
    assertEquals(Files.size(log), sessions.get(0).getAsJsonObject().getAsJsonObject("manifest")
        .getAsJsonArray("files").get(0).getAsJsonObject().get("size_bytes").getAsLong());
    for (String query : List.of("robot=missing", "event=missing", "since=2030-01-01T00:00:00Z")) {
      assertEquals(0, JsonParser.parseString(text(get("/store/sessions?" + query))).getAsJsonObject().getAsJsonArray("sessions").size());
    }
  }

  @Test void anUnreadableStoreIsNamedWithoutHidingReadableStores() throws Exception {
    var broken = Files.createDirectories(temp.resolve("broken"));
    Files.writeString(broken.resolve("store.json"), "{invalid");
    var response = get("/store"); assertEquals(200, response.statusCode(), text(response));
    var json = JsonParser.parseString(text(response)).getAsJsonObject();
    assertEquals(1, json.getAsJsonArray("stores").size());
    var problem = json.getAsJsonArray("unreadable").get(0).getAsJsonObject();
    assertEquals(broken.toString(), problem.get("path").getAsString());
    assertTrue(problem.get("reason").getAsString().contains("Invalid manifest"));
    String id = json.getAsJsonArray("stores").get(0).getAsJsonObject().get("id").getAsString();
    assertEquals(200, get("/store/sessions?store=" + id).statusCode());
    assertEquals(200, get(path() + "?store=" + id).statusCode());
  }

  @Test void theHttpRemoteReadsTheSameListingRangesAndContentProof() throws Exception {
    try (var remote = new org.triplehelix.wpilogmcp.sync.HttpRemoteFiles("http://127.0.0.1:" + transport.getPort() + "/store")) {
      var listed = remote.list(); assertEquals(1, listed.size());
      var file = listed.get(0); assertEquals(Files.size(log), file.size());
      assertEquals(1, remote.robots().size()); assertNotNull(remote.description().id());
      byte[] expected = Files.readAllBytes(log);
      assertArrayEquals(java.util.Arrays.copyOfRange(expected, 7, 19), remote.read(file.name(), 7, 12));
      assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(java.util.Arrays.copyOf(expected, 19))), remote.prefixHash(file.name(), 19).orElseThrow());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"robot.json", "prefix-hash"})
  void manifestedUnassignedPayloadsAreReadableEvenWithAControlFilename(String filename) throws Exception {
    var source = ImportFixture.write(temp.resolve("input").resolve(filename), 24);
    var copied = manager.stores().store(root).importPaths(new LogStore.Request(List.of(source), false, null), p -> {})
        .get().files().get(0).path();
    var listing = JsonParser.parseString(text(get("/store/sessions"))).getAsJsonObject().getAsJsonArray("unassigned");
    assertEquals(1, listing.size());
    assertEquals(root.relativize(copied).toString().replace(java.io.File.separatorChar, '/'),
        listing.get(0).getAsJsonObject().get("path").getAsString());
    log = copied;
    var response = get(path()); assertEquals(200, response.statusCode(), text(response));
    assertArrayEquals(Files.readAllBytes(source), response.body());
    var proof = get(path() + "/prefix-hash?bytes=16"); assertEquals(200, proof.statusCode(), text(proof));
    assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(java.util.Arrays.copyOf(Files.readAllBytes(source), 16))),
        JsonParser.parseString(text(proof)).getAsJsonObject().get("sha256").getAsString());
  }

  @Test void traversalStraysInboxControlManifestsAndOutsidePathsAreRefused() throws Exception {
    var stray = log.resolveSibling("stray.wpilog"); Files.copy(log, stray);
    Files.createDirectories(root.resolve("inbox")); Files.copy(log, root.resolve("inbox/held.wpilog"));
    var robot = root.resolve("robots/practice");
    Files.writeString(robot.resolve("pull.json"), org.triplehelix.wpilogmcp.store.StoreJson.JSON
        .toJson(org.triplehelix.wpilogmcp.sync.PullManifest.empty(null)));
    for (var manifest : List.of(robot.resolve("robot.json"), robot.resolve("pull.json"))) {
      var text = Files.readString(manifest);
      for (String secret : List.of("password", "key_path", "private_key")) assertFalse(text.contains(secret));
    }
    var paths = List.of("../source.wpilog", "%2e%2e/source.wpilog", "%2e%2e%5csource.wpilog",
        "inbox/held.wpilog", "robots/practice/robot.json", "robots/practice/pull.json",
        root.relativize(stray).toString().replace(java.io.File.separatorChar, '/'),
        temp.resolve("source.wpilog").toString().replace(java.io.File.separatorChar, '/'));
    assertAll(paths.stream().map(p -> (org.junit.jupiter.api.function.Executable)
        () -> assertEquals(404, get("/store/files/" + p).statusCode(), p)));
  }

  @Test void aNetworkBindServesTheStoreButRefusesLeasesKeysAndUntrustedOrigins() throws Exception {
    transport.stop(); transport = new HttpTransport(new ToolRegistry(), 0, "0.0.0.0", null, null); transport.start();
    assertEquals(200, get("/store").statusCode()); assertEquals(200, get(path()).statusCode());
    for (var route : List.of("/directories", "/tba-key")) {
      var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + transport.getPort() + route))
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(403, response.statusCode()); assertTrue(response.body().contains("loopback"));
    }
    assertEquals(403, get("/store", "Origin", "https://untrusted.example").statusCode());
    assertEquals(403, get(path(), "Origin", "https://untrusted.example").statusCode());
  }

  @Test void leasesCannotPublishAStoreAndMultipleConfiguredStoresRequireSelection() throws Exception {
    var second = manager.stores().store(temp.resolve("second"));
    second.importPaths(new LogStore.Request(List.of(temp.resolve("source.wpilog")), false, "practice"), p -> {}).get();
    manager.clearAllowedDirectories(); manager.addAllowedDirectory(root);
    var leases = org.triplehelix.wpilogmcp.config.ClientLeases.getInstance();
    leases.replaceDirectories("store-door-test", List.of(new org.triplehelix.wpilogmcp.config.ClientLeases.Directory(
        second.root(), null)));
    try {
      var descriptor = JsonParser.parseString(text(get("/store"))).getAsJsonObject();
      assertTrue(descriptor.has("id"), "A lease must not expose a second store");
      manager.addAllowedDirectory(second.root());
      var all = JsonParser.parseString(text(get("/store"))).getAsJsonObject().getAsJsonArray("stores");
      assertEquals(2, all.size());
      assertEquals(404, get("/store/sessions").statusCode());
      String id = all.get(0).getAsJsonObject().get("id").getAsString();
      assertEquals(200, get("/store/sessions?store=" + id).statusCode());
      assertEquals(404, get("/store/sessions?store=missing").statusCode());
    } finally { leases.remove("store-door-test"); }
  }

  @Test void anOpenCaptureServesItsCurrentPrefixRatherThanTheCoalescedManifestSize() throws Exception {
    var stored = manager.stores().store(root);
    var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-05-01T00:00:00Z"), java.time.ZoneOffset.UTC);
    var placement = stored.captures(clock); var loop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
    try (var writer = new org.triplehelix.wpilogmcp.capture.CaptureWriter(clock, loop,
        org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, placement)) {
      writer.connected(org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1", 5810, "test"), "networktables.first.wpi.edu");
      writer.timeSync(1_000_000, 0);
      var topic = new org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce("/x", 1, "int", null, new com.google.gson.JsonObject());
      writer.announce(topic); writer.value(topic, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 1_000_000, 2, 1L), 0);
      loop.advance(250_000); loop.until(() -> writer.session().observedAtUs() == loop.nowUs()); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
      log = writer.session().path(); long before = Files.size(log);
      writer.value(topic, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 2_000_000, 2, 2L), 0);
      var response = get(path()); assertEquals(200, response.statusCode());
      assertTrue(response.body().length > before); assertArrayEquals(Files.readAllBytes(log), response.body());
      var sessions = JsonParser.parseString(text(get("/store/sessions"))).getAsJsonObject().getAsJsonArray("sessions");
      var open = java.util.stream.StreamSupport.stream(sessions.spliterator(), false).map(e -> e.getAsJsonObject().getAsJsonObject("manifest"))
          .filter(m -> !m.get("open_capture").isJsonNull()).findFirst().orElseThrow().getAsJsonObject("open_capture");
      assertEquals(Files.size(log), open.get("size_bytes").getAsLong());
      var hash = JsonParser.parseString(text(get(path() + "/prefix-hash?bytes=" + before))).getAsJsonObject();
      assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(java.util.Arrays.copyOf(Files.readAllBytes(log), (int) before))), hash.get("sha256").getAsString());
    } finally { placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS); }
  }
}
