/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog;
import org.triplehelix.wpilogmcp.read.server.CatalogRecordingSource;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;
import org.triplehelix.wpilogmcp.store.StoreManifest.LogFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.Matching;
import org.triplehelix.wpilogmcp.store.StoreManifest.Provenance;
import org.triplehelix.wpilogmcp.store.StoreManifest.Robot;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

/**
 * REV signals in a robot range, on files whose clocks the fixture sets, so every row is worked
 * out from the fixture alone. W is 2026-01-01T00:00:00Z. One session holds the roboRIO's log A,
 * whose clock reads W + 100 + t at log time t, for t = 1 to 30 s; the pit's recording C of the
 * same minutes, whose clock reads half a second later, W + 100.5 + t; and the REV log R, whose
 * SPARK MAX 3 reports an applied output of t / 100 at its own t = 1 to 20 s. The store recorded
 * R's offset onto A, 0.25 s, and A's onto C, zero, so R places at W + 100.25 + t through A and at
 * W + 100.75 + t through C.
 */
class DataEndpointRangeRevTest {
  @TempDir Path directory;
  private static final long W = 1_767_225_600_000_000L;
  private static final String WHEN = "2026-01-01T00:00:00Z";
  private static final String RIO = "/SystemStats/BatteryVoltage";
  private static final String PIT = "NT:/AdvantageKit/SystemStats/BatteryVoltage";
  private static final String OUTPUT = "REV/SparkMax_3/AppliedOutput";
  /** The DBC's scale for a SPARK MAX's applied output, as the fixture encodes it. */
  private static final double APPLIED_SCALE = 0.00003082369457075716;
  private final LogManager manager = LogManager.getInstance();
  private final HttpClient client = HttpClient.newHttpClient();
  private java.util.Set<Path> previous;
  private HttpServer server;
  private StoreReadCatalog catalog;
  private Path root, session, a, c, r;

  @BeforeEach void start() throws Exception {
    directory = directory.toRealPath();
    previous = manager.getAllowedDirectories();
    manager.clearAllowedDirectories();
    manager.addAllowedDirectory(directory);
    root = directory.resolve("store");
    session = root.resolve("robots/RIO/sessions/2026-01-01/s");
    a = session.resolve("robot/akit.wpilog");
    c = session.resolve("capture-1.wpilog");
    r = session.resolve("robot/bus.revlog");
  }

  @AfterEach void stop() throws IOException {
    if (server != null) server.stop(0);
    if (catalog != null) catalog.close();
    manager.unloadAllLogs();
    manager.clearAllowedDirectories();
    previous.forEach(manager::addAllowedDirectory);
  }

  // ---- the fixture ----

  private static long micros(double seconds) { return Math.round(seconds * 1e6); }
  private static String iso(long epochUs) { return Instant.ofEpochSecond(0, epochUs * 1000).toString(); }

  /** A WPILOG whose clock reads W + offset + t at log time t, and a battery entry, for t = 1 to 30 s. */
  private static void wpilog(Path path, String clock, double offset, String battery) throws IOException {
    Files.createDirectories(path.getParent());
    try (var writer = new WpilogWriter(path, "robot range fixture")) {
      int k = writer.start(clock, "int64", "", 0);
      int b = writer.start(battery, "double", "", 0);
      for (int t = 1; t <= 30; t++) {
        writer.append(k, micros(t), WpilogWriter.encodeInt64(W + micros(offset + t)));
        writer.append(b, micros(t), WpilogWriter.encodeDouble(12 + t / 100.0));
      }
    }
  }

  /** A REV log: SPARK MAX 3's Periodic Status 0 at its own t = from to to s, with applied output t / 100. */
  private static void revlog(Path path, int from, int to) throws IOException {
    revlog(path, 3, from, to);
  }

  private static void revlog(Path path, int device, int from, int to) throws IOException {
    Files.createDirectories(path.getParent());
    try (var writer = new WpilogWriter(path, "")) {
      int id = writer.start("CAN/" + device + "/Periodic Status 0", "raw", "", 0);
      for (int t = from; t <= to; t++) writer.append(id, micros(t), FixtureLogs.sparkStatus0(t / 100.0, 12, 2, 30, false));
      writer.finish(id, micros(to) + 1);
    }
  }

  /** The applied output the DBC decodes from t / 100, as the fixture's frame carries it. */
  private static double applied(int t) {
    return (short) Math.round(t / 100.0 / APPLIED_SCALE) * APPLIED_SCALE;
  }

  private static String sha256(Path path) throws IOException {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  private LogFile file(Path path, String kind, String provenance, double min, double max, double startSec, double endSec,
      Matching matching) throws IOException {
    return new LogFile(session.relativize(path).toString().replace('\\', '/'), sha256(path), Files.size(path), kind,
        new Provenance(provenance, null, path.getFileName().toString(), WHEN, false), true, min, max,
        iso(W + micros(startSec)), iso(W + micros(endSec)), Provenance.CAPTURED.equals(provenance) ? "pit_clock" : "filename",
        false, matching);
  }

  private static Matching offset(Path reference, long offsetUs) throws IOException {
    return new Matching("by_correlation", sha256(reference), offsetUs, 0.9, 0, 0, "data");
  }

  /** The store as the class describes it; {@code aligned} false leaves R without its recorded offset. */
  private void fixture(boolean aligned, List<LogFile> more) throws Exception {
    wpilog(a, "/SystemStats/EpochTimeMicros", 100, RIO);
    wpilog(c, "NT:/AdvantageKit/SystemStats/EpochTimeMicros", 100.5, PIT);
    revlog(r, 1, 20);
    var files = new ArrayList<>(List.of(
        file(a, "wpilog", "pulled", 1, 30, 101, 130, offset(c, 0)),
        file(c, "wpilog", Provenance.CAPTURED, 1, 30, 101.5, 130.5, null),
        file(r, "revlog", "pulled", 1, 20, 101, 120, aligned ? offset(a, 250_000) : null)));
    files.addAll(more);
    Files.writeString(Files.createDirectories(root).resolve("store.json"), StoreJson.JSON.toJson(new Header(1, WHEN, "store", List.of())));
    Files.writeString(root.resolve("robots/RIO/robot.json"), StoreJson.JSON.toJson(new Robot("RIO", "RIO-1", "Fixture", "fixture", "device")));
    Files.writeString(session.resolve("session.json"), StoreJson.JSON.toJson(
        new Session("s", iso(W + micros(101)), iso(W + micros(130.5)), "pit_clock", null, null, null, null, files)));
    catalog = new StoreReadCatalog(List.of(root));
    var lease = new CatalogRecordingSource.Lease() {
      @Override public StoreReadCatalog value() { return catalog; }
      @Override public void close() {}
    };
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(HttpTransport.DATA_PATH, new DataEndpoint(manager, () -> null, () -> lease)::handle);
    server.start();
  }

  // ---- the requests ----

  private HttpResponse<byte[]> get(Map<String, String> query) throws Exception {
    var text = new StringBuilder();
    query.forEach((k, v) -> text.append(text.isEmpty() ? "?" : "&").append(URLEncoder.encode(k, StandardCharsets.UTF_8))
        .append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
    var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + HttpTransport.DATA_PATH + text);
    return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static Map<String, String> range(String... more) {
    var query = new LinkedHashMap<String, String>();
    query.put("robot", "RIO");
    query.put("start_utc", iso(W + micros(100)));
    query.put("end_utc", iso(W + micros(140)));
    query.put("format", "csv");
    for (int i = 0; i + 1 < more.length; i += 2) query.put(more[i], more[i + 1]);
    return query;
  }

  /** A CSV response: its inputs and entries metadata, and per entry its header and rows. */
  private record Csv(JsonObject inputs, JsonArray entries, Map<String, List<String>> headers, Map<String, List<List<String>>> rows) {}

  private static Csv csv(HttpResponse<byte[]> response) {
    assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    JsonObject inputs = null; JsonArray entries = null;
    var headers = new LinkedHashMap<String, List<String>>();
    var rows = new LinkedHashMap<String, List<List<String>>>();
    String current = null; boolean header = false;
    for (var line : new String(response.body(), StandardCharsets.UTF_8).split("\n")) {
      if (line.startsWith("# inputs: ")) inputs = JsonParser.parseString(line.substring(10)).getAsJsonObject();
      else if (line.startsWith("# entries: ")) entries = JsonParser.parseString(line.substring(11)).getAsJsonArray();
      else if (line.startsWith("# entry: ")) { current = line.substring(9); header = true; rows.put(current, new ArrayList<>()); }
      else if (line.startsWith("#") || line.isEmpty()) continue;
      else if (header) { headers.put(current, List.of(line.split(",", -1))); header = false; }
      else rows.get(current).add(List.of(line.split(",", -1)));
    }
    return new Csv(inputs, entries, headers, rows);
  }

  private static long utc(String text) {
    var instant = Instant.parse(text);
    return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1000;
  }

  private static JsonObject refusal(HttpResponse<byte[]> response, int status) {
    assertEquals(status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
  }

  private static JsonObject fileOf(Csv csv, Path path) {
    for (var f : csv.inputs().getAsJsonArray("files"))
      if (f.getAsJsonObject().get("path").getAsString().equals(path.toString())) return f.getAsJsonObject();
    return fail("No file " + path + " in " + csv.inputs());
  }

  /** The REV rows: each sample's UTC, file, time on its anchor's clock and value, against R's samples placed by {@code clockOffset}. */
  private void assertRevRows(Csv csv, double clockOffset) {
    var rows = csv.rows().get(OUTPUT);
    assertEquals(20, rows.size(), rows.toString());
    int index = fileOf(csv, r).get("index").getAsInt();
    for (int t = 1; t <= 20; t++) {
      var row = rows.get(t - 1);
      assertEquals(W + micros(t + 0.25) + micros(clockOffset), utc(row.get(0)), "UTC of the sample at " + t + " s");
      assertEquals(index, Integer.parseInt(row.get(1)));
      assertEquals(micros(t + 0.25), micros(Double.parseDouble(row.get(2))), "on its anchor's clock");
      assertEquals(applied(t), Double.parseDouble(row.get(3)), 1e-12);
    }
  }

  private void assertNoSynchronization() {
    for (var log : List.of(a, c)) {
      assertNull(manager.getSynchronizedLogs(log.toString()), "No REV synchronization of " + log);
      assertFalse(manager.isRevLogSyncInProgress(log.toString()));
    }
  }

  // ---- the tests ----

  @Test void aRevSignalIsPlacedThroughItsRecordedAlignmentWithTheRoboRioLogBeforeTheRecording() throws Exception {
    fixture(true, List.of());
    long decoded = catalog.revSignalsDecoded();
    var csv = csv(get(range("names", RIO + "," + OUTPUT, "capture_names", PIT + "," + OUTPUT)));
    assertRevRows(csv, 100);
    assertEquals(1, catalog.revSignalsDecoded() - decoded, "Only the signal asked for is decoded");
    assertNoSynchronization();
    var rev = fileOf(csv, r);
    assertEquals("rev", rev.get("source").getAsString());
    assertEquals(fileOf(csv, a).get("index").getAsInt(), rev.get("anchor").getAsInt(), "Placed by the roboRIO's log");
    assertEquals(0.25, rev.get("offset_seconds").getAsDouble(), 0);
    assertEquals(W + micros(101.25), utc(rev.get("start_utc").getAsString()));
    assertEquals(W + micros(120.25), utc(rev.get("end_utc").getAsString()));
    var battery = csv.rows().get(RIO);
    assertEquals(31, battery.size(), "The roboRIO's log's 30, and the recording's last, after the log ends");
    assertEquals(W + micros(130.5), utc(battery.get(30).get(0)));
    assertEquals(fileOf(csv, c).get("index").getAsInt(), Integer.parseInt(battery.get(30).get(1)));
    for (var stretch : csv.inputs().getAsJsonArray("stretches"))
      assertNotEquals("rev", stretch.getAsJsonObject().get("source").getAsString(), "A REV log answers no stretch of a WPILOG entry");
    var entry = csv.entries().get(1).getAsJsonObject().getAsJsonArray("files");
    assertEquals(1, entry.size(), "Only the REV log answers the REV signal: " + entry);
    assertEquals(20, entry.get(0).getAsJsonObject().get("sample_count").getAsInt());
    // Buckets span the range in UTC as for a WPILOG entry: one each for the 20 samples at 40 points.
    var buckets = csv(get(range("names", OUTPUT, "capture_names", OUTPUT, "max_points", "40"))).rows().get(OUTPUT);
    assertEquals(20, buckets.size());
    assertEquals(applied(7), Double.parseDouble(buckets.get(6).get(4)), 1e-12);
  }

  @Test void withTheRecordingsAloneARevLogIsPlacedThroughTheRecording() throws Exception {
    fixture(true, List.of());
    var csv = csv(get(range("source", "capture", "capture_names", OUTPUT)));
    assertRevRows(csv, 100.5);
    assertEquals(fileOf(csv, c).get("index").getAsInt(), fileOf(csv, r).get("anchor").getAsInt());
    assertEquals("COMBINED", fileOf(csv, r).get("sync_method").getAsString(), "Through A's offset onto C as well as R's onto A");
    // No roboRIO log is read, so nothing would cut a REV log out of the stretches if it were one.
    var stretches = csv.inputs().getAsJsonArray("stretches");
    assertEquals(1, stretches.size(), stretches.toString());
    assertEquals("capture", stretches.get(0).getAsJsonObject().get("source").getAsString());
    assertNoSynchronization();
  }

  @Test void aRevLogWithoutARecordedAlignmentIsListedAndNeverCorrelatedOrDecoded() throws Exception {
    fixture(false, List.of());
    long decoded = catalog.revSignalsDecoded();
    var csv = csv(get(range("names", OUTPUT)));
    assertEquals(0, csv.rows().get(OUTPUT).size());
    var skipped = csv.inputs().getAsJsonArray("skipped");
    assertEquals(1, skipped.size());
    assertEquals(r.toString(), skipped.get(0).getAsJsonObject().get("path").getAsString());
    assertEquals("No WPILOG the range placed has a recorded alignment with it", skipped.get(0).getAsJsonObject().get("reason").getAsString());
    assertEquals(0, catalog.revSignalsDecoded() - decoded);
    assertNoSynchronization();
  }

  @Test void aRangeWithoutARevSignalOpensNoRevLog() throws Exception {
    fixture(true, List.of());
    long decoded = catalog.revSignalsDecoded();
    var csv = csv(get(range("names", RIO, "capture_names", PIT)));
    assertFalse(csv.inputs().getAsJsonArray("files").toString().contains("bus.revlog"));
    assertEquals(0, catalog.revSignalsDecoded() - decoded);
    assertNoSynchronization();
  }

  @Test void aRevLogThatHoldsNoneOfTheSignalsAskedForIsListed() throws Exception {
    fixture(true, List.of());
    var csv = csv(get(range("names", "REV/SparkMax_9/AppliedOutput")));
    var skipped = csv.inputs().getAsJsonArray("skipped");
    assertEquals(1, skipped.size());
    assertEquals("Holds none of the REV signals asked for", skipped.get(0).getAsJsonObject().get("reason").getAsString());
  }

  @Test void twoRevLogsThatAnswerOneSignalOverTheSameMinutesAreRefusedTogether() throws Exception {
    var second = session.resolve("robot/again.revlog");
    revlog(second, 10, 25);
    // The fixture writes A again, byte for byte, so its hash is known before.
    wpilog(a, "/SystemStats/EpochTimeMicros", 100, RIO);
    fixture(true, List.of(file(second, "revlog", "pulled", 10, 25, 110, 125, offset(a, 250_000))));
    var body = refusal(get(range("names", OUTPUT)), 409);
    assertEquals(List.of(r.toString(), second.toString()),
        body.getAsJsonArray("files").asList().stream().map(f -> f.getAsString()).toList());
  }

  @Test void twoRevLogsOverTheSameMinutesThatAnswerDifferentSignalsBothAnswer() throws Exception {
    var other = session.resolve("robot/second-bus.revlog");
    revlog(other, 4, 1, 20);
    wpilog(a, "/SystemStats/EpochTimeMicros", 100, RIO);
    fixture(true, List.of(file(other, "revlog", "pulled", 1, 20, 101, 120, offset(a, 250_000))));
    String four = "REV/SparkMax_4/AppliedOutput";
    var csv = csv(get(range("names", OUTPUT + "," + four)));
    assertRevRows(csv, 100);
    assertEquals(20, csv.rows().get(four).size(), "Each bus's log answers its own device");
  }

  @Test void aRevSignalIsNamedAlikeInBothListsAndNamesASignal() throws Exception {
    fixture(true, List.of());
    assertTrue(refusal(get(range("names", OUTPUT, "capture_names", PIT)), 400).get("error").getAsString().contains("REV signal is one signal"));
    assertTrue(refusal(get(range("names", "REV/")), 400).get("error").getAsString().contains("names a device and a signal"));
  }
}
