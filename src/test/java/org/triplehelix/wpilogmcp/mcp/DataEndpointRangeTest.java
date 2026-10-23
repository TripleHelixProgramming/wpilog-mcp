/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
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
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.data.ArrowSpecReader;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;
import org.triplehelix.wpilogmcp.store.StoreManifest.LogFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.OpenCapture;
import org.triplehelix.wpilogmcp.store.StoreManifest.Provenance;
import org.triplehelix.wpilogmcp.store.StoreManifest.Robot;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

/**
 * A robot range over files whose clocks the fixture sets, so every row is worked out from the
 * fixture alone. W is 2026-01-01T00:00:00Z. The roboRIO's log A holds the battery at W+101 to
 * W+120 s, and B at W+201 to W+210 s; the pit's recording C holds it, under its own name, at W+90
 * to W+170 s. A's catalog time is an hour off, as a time from a file's name can be, and A logs a
 * sample before its clock's first reading, which no UTC places.
 */
class DataEndpointRangeTest {
  @TempDir Path directory;
  private static final long W = 1_767_225_600_000_000L;
  private static final String RIO = "/SystemStats/BatteryVoltage";
  private static final String PIT = "NT:/AdvantageKit/SystemStats/BatteryVoltage";
  private static final String RIO_CLOCK = "/SystemStats/EpochTimeMicros";
  private static final String PIT_CLOCK = "NT:/AdvantageKit/SystemStats/EpochTimeMicros";
  private final LogManager logs = LogManager.getInstance();
  private Set<Path> previous;
  private HttpTransport transport;
  private HttpClient client;
  private Path root;

  /** One expected sample: its UTC, the file it comes from, and its value. */
  private record Expected(long utcUs, String file, double value) {}

  @BeforeEach void start() throws Exception {
    directory = directory.toRealPath();
    previous = logs.getAllowedDirectories();
    logs.clearAllowedDirectories();
    logs.addAllowedDirectory(directory);
    root = store("store");
    transport = new HttpTransport(new ToolRegistry(), 0);
    transport.setStoreDirectories(Set.of(root));
    transport.start();
    client = HttpClient.newHttpClient();
  }

  @AfterEach void stop() {
    if (transport != null) transport.stop();
    logs.unloadAllLogs();
    logs.clearAllowedDirectories();
    previous.forEach(logs::addAllowedDirectory);
  }

  // ---- the fixture ----

  /** A store, initialized by an empty import as an ordinary first use would. */
  private Path store(String name) throws Exception {
    var path = directory.resolve(name);
    logs.stores().store(path).importPaths(new org.triplehelix.wpilogmcp.store.LogStore.Request(List.of(), false, null), p -> {}).get();
    return path;
  }

  private static long micros(double seconds) { return Math.round(seconds * 1e6); }
  private static String iso(long epochUs) { return Instant.ofEpochSecond(0, epochUs * 1000).toString(); }
  private static double[] seconds(double from, double to) {
    var out = new double[(int) Math.round(to - from) + 1];
    for (int i = 0; i < out.length; i++) out[i] = from + i;
    return out;
  }

  /** A log whose clock reads W + offset + t at log time t, for each t in clock, and a battery entry. */
  private static Path log(Path path, String clock, double offset, double[] clockTimes, String battery,
      double[] times, DoubleUnaryOperator value) throws IOException {
    Files.createDirectories(path.getParent());
    try (var writer = new WpilogWriter(path, "robot range fixture")) {
      if (clock != null) {
        int c = writer.start(clock, "int64", "", 0);
        for (double t : clockTimes) writer.append(c, micros(t), WpilogWriter.encodeInt64(W + micros(offset + t)));
      }
      int b = writer.start(battery, "double", "", 0);
      for (double t : times) writer.append(b, micros(t), WpilogWriter.encodeDouble(value.applyAsDouble(t)));
    }
    return path;
  }

  private static LogFile file(Path path, String kind, long startUs, long endUs) throws IOException {
    var provenance = new Provenance(kind, null, path.getFileName().toString(), "2026-01-01T00:00:00Z", false);
    return new LogFile(path.getFileName().toString(), sha256(path), Files.size(path), "wpilog", provenance,
        true, 0, 100, iso(startUs), iso(endUs), Provenance.CAPTURED.equals(kind) ? "pit_clock" : "filename", false, null);
  }

  private static String sha256(Path path) throws IOException {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  private Path folder(Path store, String session) { return store.resolve("robots/RIO/sessions/2026-01-01").resolve(session); }

  private void session(Path store, String id, List<LogFile> files, OpenCapture open, long startUs, long endUs,
      String basis) throws IOException {
    Files.createDirectories(folder(store, id));
    Files.writeString(store.resolve("robots/RIO/robot.json"),
        StoreJson.JSON.toJson(new Robot("RIO", "RIO-1", "Fixture", "fixture", "device")));
    Files.writeString(folder(store, id).resolve("session.json"), StoreJson.JSON.toJson(
        new Session(id, iso(startUs), iso(endUs), basis, null, null, null, null, files, open)));
  }

  private Path a, b, c;

  /** A, B and C as the class describes them. */
  private void fixture() throws IOException {
    a = log(folder(root, "a").resolve("akit_a.wpilog"), RIO_CLOCK, 100, seconds(1, 20), RIO,
        concat(new double[] {0.5}, seconds(1, 20)), t -> 12 + t / 100);
    session(root, "a", List.of(file(a, "pulled", W + micros(101 - 3600), W + micros(120 - 3600))), null,
        W + micros(101 - 3600), W + micros(120 - 3600), "filename");
    b = log(folder(root, "b").resolve("akit_b.wpilog"), RIO_CLOCK, 200, seconds(1, 10), RIO, seconds(1, 10), t -> 11 + t / 100);
    session(root, "b", List.of(file(b, "pulled", W + micros(201), W + micros(210))), null, W + micros(201), W + micros(210),
        "logged:/SystemStats/EpochTimeMicros");
    c = log(folder(root, "c").resolve("capture.wpilog"), PIT_CLOCK, 89, seconds(1, 81), PIT, seconds(1, 81), t -> 13 + t / 1000);
    session(root, "c", List.of(file(c, Provenance.CAPTURED, W + micros(90), W + micros(170))), null,
        W + micros(90), W + micros(170), "pit_clock");
  }

  private static double[] concat(double[] x, double[] y) {
    var out = new double[x.length + y.length];
    System.arraycopy(x, 0, out, 0, x.length);
    System.arraycopy(y, 0, out, x.length, y.length);
    return out;
  }

  /** The samples a default range over [W+90, W+215] answers with, from the fixture's definition. */
  private List<Expected> expectedDefault() {
    var out = new ArrayList<Expected>();
    for (int t = 1; t <= 11; t++) out.add(new Expected(W + micros(89 + t), c.toString(), 13 + t / 1000.0));
    for (int t = 1; t <= 20; t++) out.add(new Expected(W + micros(100 + t), a.toString(), 12 + t / 100.0));
    for (int t = 32; t <= 81; t++) out.add(new Expected(W + micros(89 + t), c.toString(), 13 + t / 1000.0));
    for (int t = 1; t <= 10; t++) out.add(new Expected(W + micros(200 + t), b.toString(), 11 + t / 100.0));
    return out;
  }

  // ---- the requests ----

  private HttpResponse<byte[]> get(String path, Map<String, String> query) throws Exception {
    var text = new StringBuilder();
    query.forEach((k, v) -> text.append(text.isEmpty() ? "?" : "&").append(URLEncoder.encode(k, StandardCharsets.UTF_8))
        .append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + transport.getPort() + path + text)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
  }

  private Map<String, String> range(long startUs, long endUs, String... more) {
    var query = new LinkedHashMap<String, String>();
    query.put("robot", "RIO");
    query.put("start_utc", iso(startUs));
    query.put("end_utc", iso(endUs));
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

  private static List<String> files(Csv csv) {
    var out = new ArrayList<String>();
    csv.inputs().getAsJsonArray("files").forEach(f -> out.add(f.getAsJsonObject().get("path").getAsString()));
    return out;
  }

  private static JsonObject refusal(HttpResponse<byte[]> response, int status) {
    assertEquals(status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
  }

  // ---- the tests ----

  @Test void eachStretchReadsTheRoboRioLogWhereOneExistsAndTheRecordingOtherwise() throws Exception {
    fixture();
    var csv = csv(get(HttpTransport.DATA_PATH, range(W + micros(90), W + micros(215), "names", RIO, "capture_names", PIT)));
    var paths = files(csv);
    assertEquals(List.of(a.toString(), c.toString(), b.toString()), paths, "In calendar order, A's an hour early");
    assertEquals(0, csv.inputs().getAsJsonArray("skipped").size());
    assertEquals(List.of("timestamp_utc", "file", "timestamp_sec", "value"), csv.headers().get(RIO));
    var expected = expectedDefault();
    var rows = csv.rows().get(RIO);
    assertEquals(expected.size(), rows.size(), "No recording sample inside a roboRIO log's span, none before a clock's first reading");
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i); var e = expected.get(i);
      assertEquals(e.utcUs(), utc(row.get(0)), "row " + i);
      assertEquals(e.file(), paths.get(Integer.parseInt(row.get(1))), "row " + i);
      assertEquals(e.value(), Double.parseDouble(row.get(3)), 0, "row " + i);
    }
    var stretches = csv.inputs().getAsJsonArray("stretches");
    var spans = new ArrayList<String>();
    stretches.forEach(s -> {
      var o = s.getAsJsonObject();
      spans.add(o.get("source").getAsString() + " " + utc(o.get("start_utc").getAsString()) + " " + utc(o.get("end_utc").getAsString()));
    });
    assertEquals(List.of("capture " + (W + micros(90)) + " " + (W + micros(101) - 1),
        "robot_log " + (W + micros(101)) + " " + (W + micros(120)),
        "capture " + (W + micros(120) + 1) + " " + (W + micros(170)),
        "robot_log " + (W + micros(201)) + " " + (W + micros(210))), spans);
    var entry = csv.entries().get(0).getAsJsonObject();
    assertEquals(RIO, entry.get("robot_log_name").getAsString()); assertEquals(PIT, entry.get("capture_name").getAsString());
    assertEquals(expected.size(), entry.get("total_in_range").getAsInt());
  }

  @Test void bucketsSpanTheWholeWindowAcrossFiles() throws Exception {
    fixture();
    long start = W + micros(90), end = W + micros(215);
    int points = 5;
    var csv = csv(get(HttpTransport.DATA_PATH, range(start, end, "names", RIO, "capture_names", PIT, "max_points", "5")));
    // The rule worked out apart from the server: bucket floor((t - start) * points / span), starting
    // at start + ceil(span * index / points); a bucket with no sample is left out.
    var expected = new LinkedHashMap<Integer, List<Double>>();
    for (var sample : expectedDefault()) {
      long t = sample.utcUs();
      int index = t <= start ? 0 : t >= end ? points - 1 : (int) ((t - start) * points / (end - start));
      expected.computeIfAbsent(index, k -> new ArrayList<>()).add(sample.value());
    }
    var rows = csv.rows().get(RIO);
    assertEquals(List.of("timestamp_utc", "count", "min", "max", "mean", "first", "last"), csv.headers().get(RIO));
    assertEquals(expected.size(), rows.size());
    int r = 0;
    for (var bucket : expected.entrySet()) {
      var values = bucket.getValue(); var row = rows.get(r++);
      long boundary = start + Math.floorDiv((end - start) * bucket.getKey() + points - 1, points);
      assertEquals(boundary, utc(row.get(0)));
      assertEquals(values.size(), Integer.parseInt(row.get(1)));
      assertEquals(values.stream().mapToDouble(Double::doubleValue).min().getAsDouble(), Double.parseDouble(row.get(2)), 0);
      assertEquals(values.stream().mapToDouble(Double::doubleValue).max().getAsDouble(), Double.parseDouble(row.get(3)), 0);
      assertEquals(values.stream().mapToDouble(Double::doubleValue).average().getAsDouble(), Double.parseDouble(row.get(4)), 1e-12);
      assertEquals(values.get(0), Double.parseDouble(row.get(5)), 0);
      assertEquals(values.get(values.size() - 1), Double.parseDouble(row.get(6)), 0);
    }
    var arrow = ArrowSpecReader.read(get(HttpTransport.DATA_PATH, range(start, end, "names", RIO, "capture_names", PIT,
        "max_points", "5", "format", "arrow")).body());
    assertEquals(List.of("timestamp_utc", "count", "min", "max", "mean", "first", "last"),
        arrow.fields().stream().map(ArrowSpecReader.FieldSpec::name).toList());
    long arrowRows = arrow.batches().stream().mapToLong(ArrowSpecReader.Batch::rows).sum();
    assertEquals(rows.size(), arrowRows, "Arrow carries the same buckets");
  }

  @Test void theRecordingSourceReadsTheRecordingsAlone() throws Exception {
    fixture();
    var csv = csv(get(HttpTransport.DATA_PATH, range(W + micros(90), W + micros(215), "source", "capture", "capture_names", PIT)));
    assertEquals(List.of(c.toString()), files(csv));
    var rows = csv.rows().get(PIT);
    assertEquals(81, rows.size());
    for (int t = 1; t <= 81; t++) {
      assertEquals(W + micros(89 + t), utc(rows.get(t - 1).get(0)));
      assertEquals(13 + t / 1000.0, Double.parseDouble(rows.get(t - 1).get(3)), 0);
    }
  }

  @Test void twoRoboRioLogsOverTheSameMinutesAreRefusedTogether() throws Exception {
    fixture();
    var d = log(folder(root, "d").resolve("other.wpilog"), RIO_CLOCK, 110, seconds(1, 5), RIO, seconds(1, 5), t -> 10);
    session(root, "d", List.of(file(d, "imported", W + micros(111), W + micros(115))), null, W + micros(111), W + micros(115), "filename");
    var body = refusal(get(HttpTransport.DATA_PATH, range(W + micros(90), W + micros(215), "names", RIO)), 409);
    var both = new ArrayList<String>();
    body.getAsJsonArray("files").forEach(f -> both.add(f.getAsString()));
    assertEquals(Set.of(a.toString(), d.toString()), Set.copyOf(both));
  }

  @Test void theFilesOfOneRecordingAreReadInOrderAsOne() throws Exception {
    fixture();
    // A same-boot reconnect's file starts with a value the closed file already holds, at W+170.
    var next = log(folder(root, "c").resolve("capture-2.wpilog"), PIT_CLOCK, 89, seconds(81, 91), PIT, seconds(81, 91), t -> 13 + t / 1000);
    session(root, "c", List.of(file(c, Provenance.CAPTURED, W + micros(90), W + micros(170)),
        file(next, Provenance.CAPTURED, W + micros(170), W + micros(180))), null, W + micros(90), W + micros(180), "pit_clock");
    var csv = csv(get(HttpTransport.DATA_PATH, range(W + micros(90), W + micros(215), "source", "capture", "capture_names", PIT)));
    assertEquals(List.of(c.toString(), next.toString()), files(csv));
    var rows = csv.rows().get(PIT);
    assertEquals(81 + 11, rows.size(), "Both files of the recording, the repeated value included");
    for (int i = 1; i < rows.size(); i++) assertTrue(utc(rows.get(i - 1).get(0)) <= utc(rows.get(i).get(0)), "in UTC order");
  }

  @Test void aFileWithoutAMeasuredClockIsListedAndSkipped() throws Exception {
    fixture();
    var unplaced = log(folder(root, "e").resolve("noclock.wpilog"), null, 0, new double[0], RIO, seconds(1, 5), t -> 9);
    session(root, "e", List.of(file(unplaced, "pulled", W + micros(130), W + micros(135))), null, W + micros(130), W + micros(135), "filename");
    var csv = csv(get(HttpTransport.DATA_PATH, range(W + micros(90), W + micros(215), "names", RIO, "capture_names", PIT)));
    assertFalse(files(csv).contains(unplaced.toString()));
    var skipped = csv.inputs().getAsJsonArray("skipped");
    assertEquals(1, skipped.size());
    assertEquals(unplaced.toString(), skipped.get(0).getAsJsonObject().get("path").getAsString());
    assertTrue(skipped.get(0).getAsJsonObject().get("reason").getAsString().startsWith("Not placed in UTC"));
    assertEquals(expectedDefault().size(), csv.rows().get(RIO).size());
  }

  @Test void aManifestTheCatalogCannotReadIsReportedNotLeftOutSilently() throws Exception {
    fixture();
    Files.createDirectories(folder(root, "broken"));
    Files.writeString(folder(root, "broken").resolve("session.json"), "{\"id\":\"broken\"}");
    var csv = csv(get(HttpTransport.DATA_PATH, range(W + micros(90), W + micros(215), "names", RIO, "capture_names", PIT)));
    var problems = csv.inputs().getAsJsonArray("catalog_problems");
    assertEquals(1, problems.size());
    assertEquals(folder(root, "broken").resolve("session.json").toString(), problems.get(0).getAsJsonObject().get("manifest").getAsString());
    assertEquals(expectedDefault().size(), csv.rows().get(RIO).size(), "The readable files still answer");
    var sessions = new String(get(HttpTransport.DATA_SESSIONS_PATH, Map.of("robot", "RIO")).body(), StandardCharsets.UTF_8);
    assertTrue(sessions.startsWith("# catalog_problems: "), sessions);
  }

  @Test void theRecordingStillOpenAnswersForItsSource() throws Exception {
    fixture();
    var provenance = new Provenance(Provenance.CAPTURED, null, "capture.wpilog", "2026-01-01T00:00:00Z", false);
    session(root, "c", List.of(), new OpenCapture("capture.wpilog", provenance, Files.size(c), 1, 81), W + micros(90), W + micros(170), "pit_clock");
    var csv = csv(get(HttpTransport.DATA_PATH, range(W + micros(90), W + micros(215), "source", "capture", "capture_names", PIT)));
    assertEquals(1, csv.inputs().getAsJsonArray("files").size(), csv.inputs().toString());
    var file = csv.inputs().getAsJsonArray("files").get(0).getAsJsonObject();
    assertEquals(c.toString(), file.get("path").getAsString()); assertTrue(file.get("recording").getAsBoolean());
    assertEquals(81, csv.rows().get(PIT).size());
  }

  @Test void refusalsSayWhatToAskInstead() throws Exception {
    fixture();
    long s = W + micros(90), e = W + micros(215);
    assertTrue(refusal(get(HttpTransport.DATA_PATH, Map.of("robot", "NOBODY", "names", RIO, "start_utc", iso(s), "end_utc", iso(e))), 404)
        .get("error").getAsString().contains("NOBODY"));
    refusal(get(HttpTransport.DATA_PATH, range(s, e, "source", "capture", "names", RIO)), 400);
    refusal(get(HttpTransport.DATA_PATH, range(s, e, "names", RIO + "," + RIO, "capture_names", PIT)), 400);
    refusal(get(HttpTransport.DATA_PATH, range(s, e, "names", RIO, "path", a.toString())), 400);
    refusal(get(HttpTransport.DATA_PATH, Map.of("robot", "RIO", "names", RIO, "start_utc", iso(s))), 400);
    refusal(get(HttpTransport.DATA_PATH, range(s, e, "names", RIO, "utc", "false")), 400);
    assertTrue(refusal(get(HttpTransport.DATA_PATH, range(s, e, "names", "REV/SparkMax_3/AppliedOutput")), 400)
        .get("error").getAsString().contains("REV signal"));
    // A second store and no choice: there is no default store.
    var other = store("other");
    transport.stop();
    transport = new HttpTransport(new ToolRegistry(), 0);
    transport.setStoreDirectories(Set.of(root, other));
    transport.start();
    var several = refusal(get(HttpTransport.DATA_PATH, range(s, e, "names", RIO)), 400);
    assertEquals(2, several.getAsJsonArray("stores").size());
    String id = StoreJson.JSON.fromJson(Files.readString(root.resolve("store.json")), Header.class).id();
    assertEquals(expectedDefault().size() - 61, csv(get(HttpTransport.DATA_PATH, range(s, e, "names", RIO, "store", id))).rows().get(RIO).size(),
        "With the store named, the roboRIO's logs alone answer: no capture_names, no recording");
  }

  @Test void aRangeOfMoreThanSixtyFourFilesIsRefusedWithTheTrueCount() throws Exception {
    var many = new ArrayList<LogFile>();
    for (int i = 0; i < 65; i++) {
      var path = log(folder(root, "many").resolve("log-" + i + ".wpilog"), null, 0, new double[0], RIO, seconds(1, 1), t -> 1);
      many.add(file(path, "pulled", W + micros(i), W + micros(i + 1)));
    }
    session(root, "many", many, null, W, W + micros(66), "filename");
    var body = refusal(get(HttpTransport.DATA_PATH, range(W, W + micros(70), "names", RIO)), 413);
    assertEquals(65, body.get("files").getAsInt()); assertEquals(64, body.get("max_files").getAsInt());
  }

  @Test void theListingsReadTheCatalog() throws Exception {
    fixture();
    String id = StoreJson.JSON.fromJson(Files.readString(root.resolve("store.json")), Header.class).id();
    var robots = new String(get(HttpTransport.DATA_ROBOTS_PATH, Map.of()).body(), StandardCharsets.UTF_8);
    assertEquals("robot_id,serial_number,name,basis,store_id\nRIO,RIO-1,Fixture,device," + id + "\n", robots);
    var response = get(HttpTransport.DATA_SESSIONS_PATH, Map.of("robot", "RIO-1", "start_utc", iso(W + micros(150)), "end_utc", iso(W + micros(215))));
    assertEquals(200, response.statusCode());
    var lines = new String(response.body(), StandardCharsets.UTF_8).split("\n");
    assertEquals("session_id,started_at,ended_at,start_ms,end_ms,time_basis,event,match_type,match_number,sources,files,recording", lines[0]);
    // A's catalog time is an hour early, so only C and B are in this window by the catalog alone.
    assertEquals(3, lines.length);
    assertEquals("c," + iso(W + micros(90)) + "," + iso(W + micros(170)) + "," + (W + micros(90)) / 1000 + "," + (W + micros(170)) / 1000
        + ",pit_clock,,,,capture,1,false", lines[1]);
    assertEquals("b," + iso(W + micros(201)) + "," + iso(W + micros(210)) + "," + (W + micros(201)) / 1000 + "," + (W + micros(210)) / 1000
        + ",logged:/SystemStats/EpochTimeMicros,,,,robot_log,1,false", lines[2]);
    var before = new String(get(HttpTransport.DATA_SESSIONS_PATH, Map.of("robot", "RIO", "start_utc", iso(W + micros(150)),
        "end_utc", iso(W + micros(200)))).body(), StandardCharsets.UTF_8).split("\n");
    assertEquals(2, before.length, "B starts after this window ends"); assertTrue(before[1].startsWith("c,"));
    refusal(get(HttpTransport.DATA_SESSIONS_PATH, Map.of("robot", "NOBODY")), 404);
  }
}
