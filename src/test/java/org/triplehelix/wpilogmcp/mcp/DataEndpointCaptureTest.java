/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CaptureIndex;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.store.CaptureStore;

/**
 * {@code capture=current} answers from the capture recording when each request arrives, names
 * the file it read, follows the capture to its next file, and when nothing records says so and
 * names the last session, so a panel shows why it is blank.
 */
class DataEndpointCaptureTest {
  @TempDir Path directory;
  private final LogManager manager = LogManager.getInstance();
  private final AtomicReference<LiveCapture> live = new AtomicReference<>();
  private HttpServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private java.util.Set<Path> previous;

  @BeforeEach void start() throws Exception {
    directory = directory.toRealPath();
    previous = manager.getAllowedDirectories();
    manager.addAllowedDirectory(directory);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(HttpTransport.DATA_PATH, new DataEndpoint(manager, live::get)::handle);
    server.start();
  }

  @AfterEach void stop() {
    server.stop(0);
    manager.clearAllowedDirectories();
    previous.forEach(manager::addAllowedDirectory);
  }

  private HttpResponse<String> get(Map<String, String> query) throws Exception {
    var text = new StringBuilder();
    new java.util.TreeMap<>(query).forEach((key, value) -> text.append(text.length() == 0 ? "?" : "&")
        .append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
    var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + HttpTransport.DATA_PATH + text);
    return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonObject inputs(String csv) {
    var line = csv.lines().filter(l -> l.startsWith("# inputs: ")).findFirst().orElseThrow();
    return JsonParser.parseString(line.substring("# inputs: ".length())).getAsJsonObject();
  }

  /** The value column of a one-entry CSV, row by row, found by its header. */
  private static List<Double> values(String csv) {
    var lines = csv.lines().filter(line -> !line.startsWith("#") && !line.isBlank()).toList();
    int column = java.util.Arrays.asList(lines.get(0).split(",")).indexOf("value");
    assertTrue(column >= 0, lines.get(0));
    return lines.stream().skip(1).map(line -> Double.parseDouble(line.split(",")[column])).toList();
  }

  private static CaptureStore.Status status(Path path, boolean open) {
    return new CaptureStore.Status(path, Instant.parse("2026-10-22T10:00:00Z"), open ? null : Instant.parse("2026-10-22T10:05:00Z"),
        "127.0.0.1", open, open ? null : "disconnected", null, null, null, null, null, null);
  }

  private static CaptureWriter recording(Path path, AtomicReference<Double> now, long... values) throws Exception {
    var index = new CaptureIndex((address, start) -> path, LogManager.getInstance(), 10_000_000, now::get);
    var topic = new Announce("/x", 1, "int", null, new JsonObject());
    var writer = new CaptureWriter(java.time.Clock.systemUTC(), new ManualScheduler(), CapturePolicy.ALL, index);
    writer.connected(RobotAddress.uri("127.0.0.1", 5810, "data-endpoint-capture-test"), "networktables.first.wpi.edu");
    writer.timeSync(1_000_000, 0);
    writer.announce(topic);
    for (int i = 0; i < values.length; i++) writer.value(topic, new ValueFrame(1, 1_000_000 + 20_000L * i, 2, values[i]), 0);
    return writer;
  }

  @Test
  void theCurrentCaptureAnswersAndIsFollowedToItsNextFile() throws Exception {
    var first = directory.resolve("capture-1.wpilog");
    var second = directory.resolve("capture-2.wpilog");
    var now = new AtomicReference<>(3_000_000.0);
    var request = Map.of("capture", "current", "names", "NT:/x", "format", "csv");
    try (var one = recording(first, now, 7, 8); var two = recording(second, now, 9)) {
      var capture = new LiveCapture(directory, new ManualScheduler());
      live.set(capture);
      capture.status(status(first, true));
      var response = get(request);
      assertEquals(200, response.statusCode(), response.body());
      assertEquals(List.of(7.0, 8.0), values(response.body()));
      assertEquals(first.toString(), inputs(response.body()).get("log").getAsString());
      assertEquals("current", inputs(response.body()).get("capture").getAsString());
      assertTrue(response.headers().firstValue("ETag").isEmpty(), "a recording has no validator to repeat");
      // The capture moved on to its next file: the same request follows it.
      capture.status(status(second, true));
      response = get(request);
      assertEquals(200, response.statusCode(), response.body());
      assertEquals(List.of(9.0), values(response.body()));
      assertEquals(second.toString(), inputs(response.body()).get("log").getAsString());
      // Recording stopped: refused, naming the last session and when it ended.
      capture.status(status(second, false));
      response = get(request);
      assertEquals(404, response.statusCode(), response.body());
      var body = JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals("No capture session is open", body.get("error").getAsString());
      assertEquals(second.toString(), body.get("last_session").getAsString());
      assertEquals("2026-10-22T10:05:00Z", body.get("ended_at").getAsString());
    }
  }

  /**
   * A dashboard's refresh on a two-hour recording costs what its window holds: ten seconds by
   * UTC decode a few dozen of the 700 blocks each entry holds, and the clock's mapping is
   * extended by the readings added since the last refresh. Counted, not timed.
   */
  @Test
  void aTenSecondUtcWindowOnATwoHourRecordingReadsOnlyItsBlocks() throws Exception {
    long w = 1_767_225_600_000_000L;
    var path = directory.resolve("long.wpilog");
    // A header alone: the numbers are read from the recording's columns, never from the file.
    java.nio.file.Files.write(path, new byte[] {'W', 'P', 'I', 'L', 'O', 'G', 0, 1, 0, 0, 0, 0});
    var columns = directory.resolve("long-open.col");
    var log = new org.triplehelix.wpilogmcp.log.LiveLog(path, 0, () -> null, java.util.UUID.randomUUID().toString(),
        new org.triplehelix.wpilogmcp.log.OpenColumns.Placement() {
          @Override public java.util.Optional<Path> file(String identity) { return java.util.Optional.of(columns); }
          @Override public boolean reserveShort() { return false; }
        });
    var x = new org.triplehelix.wpilogmcp.log.EntryInfo(1, "NT:/x", "double", "");
    var clock = new org.triplehelix.wpilogmcp.log.EntryInfo(2, "NT:/AdvantageKit/SystemStats/EpochTimeMicros", "int64", "");
    log.announce(x);
    log.announce(clock);
    long offset = 12;
    int records = 2 * 3600 * 50;
    manager.beginCapture(log);
    try {
      for (int i = 0; i < records + 50; i++) {
        long time = 1_000_000L + 20_000L * i;
        log.append(clock, new ValueFrame(2, time, 2, w + 20_000L * i), new org.triplehelix.wpilogmcp.capture.WpilogOutput.Written(offset, 17, offset + 9, 8));
        log.append(x, new ValueFrame(1, time, 1, i * 0.5), new org.triplehelix.wpilogmcp.capture.WpilogOutput.Written(offset + 17, 17, offset + 26, 8));
        offset += 34;
        if (i == records - 1) {
          // The first refresh reads the clock whole, once.
          var first = get(Map.of("path", path.toString(), "names", "NT:/x", "format", "csv",
              "start_utc", instant(w + 20_000L * (records - 500)), "end_utc", instant(w + 20_000L * (records - 1))));
          assertEquals(200, first.statusCode(), first.body());
          assertEquals(500, values(first.body()).size());
        }
      }
      long before = log.columnBlocksRead();
      int last = records + 49;
      var response = get(Map.of("path", path.toString(), "names", "NT:/x", "format", "csv",
          "start_utc", instant(w + 20_000L * (last - 500)), "end_utc", instant(w + 20_000L * last)));
      long read = log.columnBlocksRead() - before;
      assertEquals(200, response.statusCode(), response.body());
      var rows = values(response.body());
      assertEquals(501, rows.size(), "ten seconds at 50 Hz, both ends included");
      assertEquals((last - 500) * 0.5, rows.get(0));
      assertEquals(last * 0.5, rows.get(500));
      assertTrue(read <= 40, "blocks decoded for the window and the clock's new readings: " + read + " of " + 2 * (records / 512));
    } finally {
      manager.finishCapture(log);
      manager.unloadLog(path.toString());
      log.discard();
    }
  }

  private static String instant(long epochUs) {
    return Instant.ofEpochSecond(Math.floorDiv(epochUs, 1_000_000L), Math.floorMod(epochUs, 1_000_000L) * 1000).toString();
  }

  @Test
  void withoutARecordingTheRequestIsRefusedWithItsReason() throws Exception {
    var request = Map.of("capture", "current", "names", "NT:/x");
    var response = get(request);
    assertEquals(404, response.statusCode());
    var body = JsonParser.parseString(response.body()).getAsJsonObject();
    assertEquals("Capture is not enabled on this server", body.get("error").getAsString());
    assertTrue(body.get("last_session").isJsonNull());
    live.set(new LiveCapture(directory, new ManualScheduler()));
    body = JsonParser.parseString(get(request).body()).getAsJsonObject();
    assertEquals("No capture session is open", body.get("error").getAsString());
    assertTrue(body.get("last_session").isJsonNull(), "a store with no capture names none");
  }

  @Test
  void captureIsOneWayToNameTheLog() throws Exception {
    var path = directory.resolve("capture-1.wpilog").toString();
    assertEquals(400, get(Map.of("capture", "current", "path", path, "names", "NT:/x")).statusCode());
    assertEquals(400, get(Map.of("capture", "latest", "names", "NT:/x")).statusCode());
    assertEquals(400, get(Map.of("capture", "current", "names", "NT:/x",
        "scope_id", java.util.UUID.randomUUID().toString())).statusCode());
    var missing = get(Map.of("names", "NT:/x"));
    assertEquals(400, missing.statusCode());
    assertTrue(missing.body().contains("one path"), missing.body());
  }
}
