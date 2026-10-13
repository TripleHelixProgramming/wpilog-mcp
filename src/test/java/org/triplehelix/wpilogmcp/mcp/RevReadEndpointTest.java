/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.read.LogAccess;
import org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.read.http.RemoteRevRead;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class RevReadEndpointTest {
  @TempDir Path temp;

  @Test void remoteRevDiscoveryNamesSignalsAndRecordedProofWithoutSendingTheirValues() throws Exception {
    var fixture = fixture();
    try (var access = LogAccess.local(List.of(temp));
         var endpoint = new ReadEndpoint(access, () -> new StoreReadCatalog(List.of(fixture.root())))) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(ReadEndpoint.PATH, endpoint::handle); server.start();
      var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      try {
        var description = RemoteRevRead.describe(base, fixture.rev().toString(), fixture.anchor().toString());
        assertEquals("recorded", description.state()); assertEquals(250_000, description.alignment().offsetMicros());
        var output = description.signals().get("SparkMax_3/AppliedOutput");
        assertNotNull(output); assertEquals("AppliedOutput", output.name()); assertEquals("SparkMax_3", output.deviceKey());
        assertEquals("duty_cycle", output.unit()); assertEquals(2, output.sampleCount());
        assertThrows(UnsupportedOperationException.class, () -> description.signals().clear());
        var body = new JsonObject(); body.addProperty("companion_path", fixture.rev().toString());
        body.addProperty("anchor_path", fixture.anchor().toString());
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(base.resolve("/read/v1/rev/describe")).header("Content-Type", "application/json");
        var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); assertFalse(response.body().contains("\"values\""));
        body.remove("anchor_path");
        var refused = client.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, refused.statusCode()); assertTrue(refused.body().contains("anchor_path"));
      } finally { server.stop(0); }
    }
  }

  @Test void remoteRevCarriesRecordedProofAndBothExplicitClocksWithoutResynchronizing() throws Exception {
    var fixture = fixture();
    try (var access = LogAccess.local(List.of(temp));
         var endpoint = new ReadEndpoint(access, () -> new StoreReadCatalog(List.of(fixture.root())))) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(ReadEndpoint.PATH, endpoint::handle); server.start();
      var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      try {
        for (String clock : List.of("raw", "fpga")) {
          try (var read = RemoteRevRead.open(base, fixture.rev().toString(), fixture.anchor().toString(), "SparkMax_3/AppliedOutput", clock)) {
            assertEquals("recorded", read.descriptor().state());
            assertEquals(250_000, read.descriptor().alignment().offsetMicros());
            assertEquals(0.9, read.descriptor().alignment().confidence());
            assertEquals(clock, read.descriptor().clock());
            assertEquals(2, read.descriptor().sampleCount());
            var values = new java.util.ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
            while (!read.complete()) values.addAll(read.next());
            assertEquals(clock.equals("raw") ? List.of(1.0, 3.0) : List.of(1.25, 3.252),
                values.stream().map(v -> v.timestamp()).toList());
            assertEquals(2, values.size());
          }
        }
        var body = new JsonObject(); body.addProperty("companion_path", fixture.rev().toString());
        body.addProperty("anchor_path", fixture.anchor().toString()); body.addProperty("signal", "SparkMax_3/AppliedOutput");
        body.addProperty("clock", "guessed");
        var bad = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve("/read/v1/rev/query"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("clock"));
      } finally { server.stop(0); }
    }
  }

  @Test void remoteRevNeverAcceptsAnEofWithoutItsCompletionRecord() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/read/v1/rev/query", exchange -> {
      exchange.getRequestBody().readAllBytes();
      // Even all advertised samples are insufficient: EOF is not proof that the server completed.
      byte[] bytes = (header(1) + "\n" + exactSample()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
      exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    try (var read = RemoteRevRead.open(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "rev", "anchor", "signal", "raw")) {
      assertEquals(1, read.next().size());
      assertFalse(read.complete());
      assertThrows(java.io.IOException.class, read::next);
      assertFalse(read.complete());
    } finally { server.stop(0); }
  }

  @Test void remoteRevRetainsDoubleSecondBitsAndNumericPayloadBits() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/read/v1/rev/query", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] bytes = (header(1) + "\n" + exactSample() + "{\"kind\":\"end\",\"status\":\"ok\"}\n")
          .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    try (var read = RemoteRevRead.open(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "rev", "anchor", "signal", "raw")) {
      var sample = read.next().get(0);
      assertEquals(0x3ff0000000000001L, Double.doubleToRawLongBits(sample.timestamp()), "REV seconds must not be rounded to microseconds");
      assertInstanceOf(Double.class, sample.value());
      assertEquals(0x7ff8000000000042L, Double.doubleToRawLongBits((Double) sample.value()), "A NaN's recorded bits must survive");
      assertFalse(read.complete()); assertTrue(read.next().isEmpty()); assertTrue(read.complete());
    } finally { server.stop(0); }
  }

  @Test void remoteRevWithoutRecordedProofIsRawReadableAndRefusesTheFpgaClock() throws Exception {
    var fixture = fixture(false);
    try (var access = LogAccess.local(List.of(temp));
         var endpoint = new ReadEndpoint(access, () -> new StoreReadCatalog(List.of(fixture.root())))) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(ReadEndpoint.PATH, endpoint::handle); server.start();
      var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      try {
        try (var read = RemoteRevRead.open(base, fixture.rev().toString(), fixture.anchor().toString(), "SparkMax_3/AppliedOutput", "raw")) {
          assertEquals("unsynchronized", read.descriptor().state());
          assertNull(read.descriptor().alignment(), "An absent alignment must not look like a zero offset");
          assertFalse(read.descriptor().reason().isBlank());
          assertEquals(List.of(1.0, 3.0), read.next().stream().map(v -> v.timestamp()).toList());
          assertTrue(read.next().isEmpty()); assertTrue(read.complete());
        }
        var failure = assertThrows(java.io.IOException.class, () -> RemoteRevRead.open(base,
            fixture.rev().toString(), fixture.anchor().toString(), "SparkMax_3/AppliedOutput", "fpga"));
        assertTrue(failure.getMessage().contains("unsynchronized"), failure.getMessage());
        var request = new JsonObject(); request.addProperty("companion_path", fixture.rev().toString());
        request.addProperty("anchor_path", fixture.anchor().toString()); request.addProperty("signal", "SparkMax_3/AppliedOutput");
        request.addProperty("clock", "fpga");
        var refused = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve("/read/v1/rev/query"))
            .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, refused.statusCode(), "The endpoint itself refuses an absent mapping before streaming, independently of client validation");
        assertTrue(refused.body().contains("unsynchronized"));
      } finally { server.stop(0); }
    }
  }

  private static JsonObject header(int count) {
    var header = new JsonObject(); header.addProperty("kind", "rev"); header.addProperty("api_version", 1);
    header.addProperty("path", "fixture.revlog"); header.addProperty("signal", "signal"); header.addProperty("clock", "raw");
    header.addProperty("state", "unsynchronized"); header.addProperty("reason", "no association"); header.addProperty("unit", "A");
    header.add("alignment", ReadWire.JSON.toJsonTree(org.triplehelix.wpilogmcp.sync.SyncResult.failed("no association")));
    header.addProperty("sample_count", Integer.toString(count)); return header;
  }
  private static String exactSample() {
    // Handwritten IEEE-754 hex: the first representable double above one, and a noncanonical NaN.
    // No production encoder builds this oracle, and neither fits an ordinary JSON number policy.
    return """
        {"kind":"rev_samples","samples":[{"timestamp_sec":{"type":"f64","value":"3ff0000000000001"},"value":{"type":"f64","value":"7ff8000000000042"}}]}
        """;
  }

  private record Fixture(Path root, Path anchor, Path rev) {}
  private Fixture fixture() throws Exception { return fixture(true); }
  private Fixture fixture(boolean matched) throws Exception {
    var root = Files.createDirectories(temp.resolve("store"));
    var robot = Files.createDirectories(root.resolve("robots/fixture"));
    var session = Files.createDirectories(robot.resolve("sessions/2026-01-01/session"));
    var anchor = session.resolve("capture.wpilog"); var rev = session.resolve("bus.revlog");
    try (var writer = new WpilogWriter(anchor, "")) { writer.start("count", "int64", "", 0); }
    try (var writer = new WpilogWriter(rev, "")) {
      int id = writer.start("CAN/3/Periodic Status 0", "raw", "", 0);
      writer.append(id, 1_000_000, FixtureLogs.sparkStatus0(0.25, 12, 2, 30, false));
      writer.append(id, 3_000_000, FixtureLogs.sparkStatus0(0.5, 12, 2, 30, false)); writer.finish(id, 3_000_001);
    }
    String when = "2026-01-01T00:00:00Z";
    var provenance = new Provenance("fixture", "/fixture", "capture.wpilog", when, false);
    var primary = file(anchor, "wpilog", provenance, null);
    var companion = file(rev, "revlog", provenance, matched
        ? new Matching("by_correlation", primary.sha256(), 250_000, 0.9, 1_000_000, 1, "data") : null);
    Files.writeString(root.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, when, "store", List.of())));
    Files.writeString(robot.resolve("robot.json"), StoreJson.JSON.toJson(new Robot("fixture", "fixture", "", "", "logged")));
    Files.writeString(session.resolve("session.json"), StoreJson.JSON.toJson(new Session("session", when, when, "log", null, null, null, null, List.of(primary, companion))));
    return new Fixture(root, anchor, rev);
  }
  private static LogFile file(Path path, String kind, Provenance provenance, Matching matching) throws Exception {
    return new LogFile(path.getFileName().toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))),
        Files.size(path), kind, provenance, true, 1, 3, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "log", false, matching);
  }
}
