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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.data.ArrowSpecReader;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.store.CaptureStore;
import org.triplehelix.wpilogmcp.store.StoreManifest.LogFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.OpenCapture;
import org.triplehelix.wpilogmcp.store.StoreManifest.Provenance;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

/**
 * {@code capture=current} over a recording whose window reaches a closed file of the session as
 * well as the open one, on files whose clocks the fixture sets, so every row is worked out from
 * the fixture alone. W is 2026-01-01T00:00:00Z. File 1 closed at a disconnect: its clock reads
 * W + t at log time t, and it holds the battery at t = 1 to 60 s. File 2 is the reconnect's: its
 * clock reads W + t + 0.25 s, from its first value on, and it starts with the battery's value at
 * t = 60 s re-sent at its original time, then holds t = 70 to 100 s. The battery reads
 * 10 + t / 100.
 */
class DataEndpointRecordingTest {
  @TempDir Path directory;
  private static final long W = 1_767_225_600_000_000L;
  private static final String BATTERY = "NT:/AdvantageKit/SystemStats/BatteryVoltage";
  private static final String CLOCK = "NT:/AdvantageKit/SystemStats/EpochTimeMicros";
  private static final String HEADING = "NT:/Drive/HeadingDegrees";
  /** File 2's clock reads a quarter second later than file 1's, as two synchronizations can. */
  private static final double LATER = 0.25;
  private final LogManager manager = LogManager.getInstance();
  private final AtomicReference<LiveCapture> live = new AtomicReference<>();
  private final HttpClient client = HttpClient.newHttpClient();
  private java.util.Set<Path> previous;
  private HttpServer server;
  private Path folder, first, second;

  @BeforeEach void start() throws Exception {
    directory = directory.toRealPath();
    previous = manager.getAllowedDirectories();
    manager.addAllowedDirectory(directory);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(HttpTransport.DATA_PATH, new DataEndpoint(manager, live::get)::handle);
    server.start();
    folder = directory.resolve("robots/RIO/sessions/2026-01-01/s");
    first = folder.resolve("capture-1.wpilog");
    second = folder.resolve("capture-2.wpilog");
  }

  @AfterEach void stop() {
    server.stop(0);
    manager.unloadAllLogs();
    manager.clearAllowedDirectories();
    previous.forEach(manager::addAllowedDirectory);
  }

  // ---- the fixture ----

  private static long micros(double seconds) { return Math.round(seconds * 1e6); }
  private static String iso(long epochUs) { return Instant.ofEpochSecond(0, epochUs * 1000).toString(); }
  private static double battery(double t) { return 10 + t / 100; }
  private static double[] seconds(double from, double to) {
    var out = new double[(int) Math.round(to - from) + 1];
    for (int i = 0; i < out.length; i++) out[i] = from + i;
    return out;
  }
  private static double[] concat(double[] x, double[] y) {
    var out = new double[x.length + y.length];
    System.arraycopy(x, 0, out, 0, x.length);
    System.arraycopy(y, 0, out, x.length, y.length);
    return out;
  }

  /** A capture file: its clock reads W + offset + t at each log time t of clock, and the battery at each t of times. */
  private static Path capture(Path path, Double offset, double[] clock, double[] times) throws IOException {
    Files.createDirectories(path.getParent());
    try (var writer = new WpilogWriter(path, "recording fixture")) {
      if (offset != null) {
        int c = writer.start(CLOCK, "int64", "", 0);
        for (double t : clock) writer.append(c, micros(t), WpilogWriter.encodeInt64(W + micros(offset + t)));
      }
      int b = writer.start(BATTERY, "double", "", 0);
      for (double t : times) writer.append(b, micros(t), WpilogWriter.encodeDouble(battery(t)));
    }
    return path;
  }

  /** The two files as the class describes them. */
  private void fixture() throws IOException {
    capture(first, 0.0, seconds(1, 60), seconds(1, 60));
    capture(second, LATER, concat(new double[] {60}, seconds(70, 100)), concat(new double[] {60}, seconds(70, 100)));
  }

  private static Provenance captured(Path path) {
    return new Provenance(Provenance.CAPTURED, null, path.getFileName().toString(), "2026-01-01T00:00:00Z", false);
  }

  private LogFile closed(Path path, double min, double max, long endedUs) throws IOException {
    return new LogFile(folder.relativize(path).toString().replace('\\', '/'), "0".repeat(64), Files.size(path), "wpilog",
        captured(path), true, min, max, iso(W), iso(endedUs), "pit_clock", false, null);
  }

  /** The capture's state: file 2 records, and the published manifest lists the closed files given. */
  private LiveCapture recording(List<LogFile> closedFiles, OpenCapture named) throws IOException {
    var capture = new LiveCapture(directory, new ManualScheduler());
    capture.status(new CaptureStore.Status(second, Instant.ofEpochSecond(0, W * 1000), null, "127.0.0.1", true, null,
        null, null, null, null, null, null));
    var open = named != null ? named : new OpenCapture("capture-2.wpilog", captured(second), Files.size(second), 60, 100);
    capture.manifest(folder.resolve("session.json"), new Session("s", iso(W), iso(W + micros(100)), "pit_clock",
        null, null, null, null, closedFiles, open));
    live.set(capture);
    return capture;
  }

  private void standard() throws IOException {
    fixture();
    recording(List.of(closed(first, 1, 60, W + micros(60))), null);
  }

  // ---- the requests ----

  private HttpResponse<byte[]> get(Map<String, String> query) throws Exception {
    var text = new StringBuilder();
    query.forEach((k, v) -> text.append(text.isEmpty() ? "?" : "&").append(URLEncoder.encode(k, StandardCharsets.UTF_8))
        .append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
    var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + HttpTransport.DATA_PATH + text);
    return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static Map<String, String> query(String... pairs) {
    var out = new LinkedHashMap<String, String>();
    out.put("capture", "current");
    out.put("format", "csv");
    for (int i = 0; i + 1 < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
    return out;
  }

  private static Map<String, String> window(String names, double fromSec, double toSec, String... more) {
    var out = query(more);
    out.put("names", names);
    out.put("start_utc", iso(W + micros(fromSec)));
    out.put("end_utc", iso(W + micros(toSec)));
    return out;
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

  /** One expected sample: its log time, its UTC, and its value. */
  private record Expected(long logUs, long utcUs, double value) {}

  /** UTC by the fixture's clocks: file 1's up to its last record at 60 s, file 2's after it. */
  private static long placed(long logUs) {
    return W + logUs + (logUs <= micros(60) ? 0 : micros(LATER));
  }

  private static List<Expected> samples(double... times) {
    var out = new ArrayList<Expected>();
    for (double t : times) out.add(new Expected(micros(t), placed(micros(t)), battery(t)));
    return out;
  }

  private static void assertRows(List<Expected> expected, List<List<String>> rows) {
    assertEquals(expected.size(), rows.size(), rows.toString());
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i); var e = expected.get(i);
      assertEquals(e.logUs(), micros(Double.parseDouble(row.get(0))), "log time of row " + i);
      assertEquals(e.utcUs(), utc(row.get(1)), "UTC of row " + i);
      assertEquals(e.value(), Double.parseDouble(row.get(2)), 0, "value of row " + i);
    }
  }

  private static List<String> files(Csv csv) {
    var out = new ArrayList<String>();
    csv.inputs().getAsJsonArray("files").forEach(f -> out.add(f.getAsJsonObject().get("path").getAsString()));
    return out;
  }

  // ---- the tests ----

  @Test void aWindowAcrossAReconnectReadsBothFilesEachTimeOnTheClockOfTheFileRecordingThen() throws Exception {
    standard();
    var csv = csv(get(window(BATTERY, 50, 80)));
    assertEquals(List.of("timestamp_sec", "timestamp_utc", "value"), csv.headers().get(BATTERY));
    // File 1's 50 to 60 s, the value file 2 repeats at 60 s placed where file 1 recorded it, and
    // file 2's own values to 79 s, whose UTC is 79.25 s; 80 s would be 80.25 s, outside the window.
    var expected = samples(concat(concat(seconds(50, 60), new double[] {60}), seconds(70, 79)));
    assertRows(expected, csv.rows().get(BATTERY));
    assertEquals(List.of(first.toString(), second.toString()), files(csv));
    var closedFile = csv.inputs().getAsJsonArray("files").get(0).getAsJsonObject();
    assertEquals(60.0, closedFile.get("end_sec").getAsDouble(), 0);
    assertTrue(csv.inputs().getAsJsonArray("files").get(1).getAsJsonObject().get("recording").getAsBoolean());
    assertEquals(0, csv.inputs().getAsJsonArray("skipped").size());
    assertEquals(second.toString(), csv.inputs().get("log").getAsString(), "The open file still names the read");
    var entry = csv.entries().get(0).getAsJsonObject();
    assertEquals(60 + 32, entry.get("sample_count").getAsInt(), "Every sample of the entry in both files");
    assertEquals(expected.size(), entry.get("total_in_range").getAsInt());
    assertEquals(0, entry.getAsJsonObject("utc_basis").get("unplaced_sample_count").getAsInt());
  }

  @Test void bucketsSpanTheWindowAcrossTheRecordingsFiles() throws Exception {
    standard();
    int points = 4;
    var csv = csv(get(window(BATTERY, 50, 80, "max_points", "4")));
    // The window's robot times: from 50 s, by file 1's clock, to 79.75 s, the last microsecond file
    // 2's clock places at or before W + 80 s. The rule worked out apart from the server: bucket
    // floor((t - start) * points / span), starting at start + ceil(span * index / points), and
    // placed by the clock of the file recording at that start; a bucket with no sample is left out.
    long start = micros(50), end = micros(79.75);
    var expected = new LinkedHashMap<Integer, List<Double>>();
    for (var sample : samples(concat(concat(seconds(50, 60), new double[] {60}), seconds(70, 79)))) {
      long t = sample.logUs();
      int index = t <= start ? 0 : t >= end ? points - 1 : (int) ((t - start) * points / (end - start));
      expected.computeIfAbsent(index, k -> new ArrayList<>()).add(sample.value());
    }
    assertEquals(List.of(0, 1, 2, 3), List.copyOf(expected.keySet()));
    assertEquals(List.of("timestamp_sec", "timestamp_utc", "count", "min", "max", "mean", "first", "last"), csv.headers().get(BATTERY));
    var rows = csv.rows().get(BATTERY);
    assertEquals(expected.size(), rows.size());
    var boundaries = new ArrayList<Long>();
    int r = 0;
    for (var bucket : expected.entrySet()) {
      var values = bucket.getValue(); var row = rows.get(r++);
      long boundary = start + Math.floorDiv((end - start) * bucket.getKey() + points - 1, points);
      boundaries.add(boundary);
      assertEquals(boundary, micros(Double.parseDouble(row.get(0))));
      assertEquals(placed(boundary), utc(row.get(1)), "bucket " + bucket.getKey() + " placed by the clock recording then");
      assertEquals(values.size(), Integer.parseInt(row.get(2)));
      assertEquals(values.stream().mapToDouble(Double::doubleValue).min().getAsDouble(), Double.parseDouble(row.get(3)), 0);
      assertEquals(values.stream().mapToDouble(Double::doubleValue).max().getAsDouble(), Double.parseDouble(row.get(4)), 0);
      assertEquals(values.stream().mapToDouble(Double::doubleValue).average().getAsDouble(), Double.parseDouble(row.get(5)), 1e-12);
      assertEquals(values.get(0), Double.parseDouble(row.get(6)), 0);
      assertEquals(values.get(values.size() - 1), Double.parseDouble(row.get(7)), 0);
    }
    var arrowQuery = window(BATTERY, 50, 80, "max_points", "4");
    arrowQuery.put("format", "arrow");
    var arrow = ArrowSpecReader.read(get(arrowQuery).body());
    assertEquals("timestamp_utc", arrow.fields().get(1).name());
    var utcColumn = new ArrayList<Object>();
    arrow.batches().forEach(batch -> utcColumn.addAll(batch.columns().get(1)));
    assertEquals(boundaries.stream().map(DataEndpointRecordingTest::placed).toList(), utcColumn, "Arrow places them alike");
  }

  @Test void aWindowInTheLogsSecondsHoldsTheSameTimesInEveryFile() throws Exception {
    standard();
    var request = query("names", BATTERY, "start_time", "55", "end_time", "75", "utc", "true");
    var csv = csv(get(request));
    assertRows(samples(concat(concat(seconds(55, 60), new double[] {60}), seconds(70, 75))), csv.rows().get(BATTERY));
    request.remove("utc");
    var plain = csv(get(request));
    assertEquals(List.of("timestamp_sec", "value"), plain.headers().get(BATTERY));
    assertEquals(13, plain.rows().get(BATTERY).size());
  }

  @Test void aWindowInsideTheOpenFileAnswersAsTheOpenFileAlone() throws Exception {
    standard();
    var csv = csv(get(window(BATTERY, 90.25, 100.25)));
    assertRows(samples(seconds(90, 100)), csv.rows().get(BATTERY));
    assertEquals(List.of(first.toString(), second.toString()), files(csv), "The closed file was nominated and read");
    var byPath = window(BATTERY, 90.25, 100.25);
    byPath.remove("capture");
    byPath.put("path", second.toString());
    assertEquals(csv(get(byPath)).rows(), csv.rows(), "The same rows as the open file read by its path");
  }

  @Test void withoutAClosedFileInTheManifestTheOpenFileAnswersAlone() throws Exception {
    fixture();
    recording(List.of(), null);
    var csv = csv(get(window(BATTERY, 50, 80)));
    assertFalse(csv.inputs().has("files"), "A recording of one file answers as one file does");
    // Alone, file 2's clock places its own repeated value at 60.25 s.
    var rows = csv.rows().get(BATTERY);
    assertEquals(11, rows.size());
    assertEquals(W + micros(60 + LATER), utc(rows.get(0).get(1)));
  }

  @Test void aPeersCaptureOfTheSameBootIsNotPartOfTheRecording() throws Exception {
    fixture();
    var peer = folder.resolve("peer").resolve("a".repeat(64)).resolve("capture-1.wpilog");
    capture(peer, 0.0, seconds(1, 60), seconds(1, 60));
    var copied = new LogFile(folder.relativize(peer).toString().replace('\\', '/'), "a".repeat(64), Files.size(peer), "wpilog",
        captured(peer), true, 1, 60, iso(W), iso(W + micros(60)), "pit_clock", false, null);
    recording(List.of(closed(first, 1, 60, W + micros(60)), copied), null);
    var csv = csv(get(window(BATTERY, 50, 80)));
    assertEquals(List.of(first.toString(), second.toString()), files(csv));
    assertEquals(22, csv.rows().get(BATTERY).size(), "The peer's copies of 50 to 60 s are not merged in");
  }

  @Test void aClosedFileTheManifestStillNamesOpenIsReadAsClosed() throws Exception {
    fixture();
    // The store's update after the close has not been published yet: the manifest names file 1 open.
    recording(List.of(), new OpenCapture("capture-1.wpilog", captured(first), Files.size(first), 1, 50));
    var csv = csv(get(window(BATTERY, 50, 80)));
    assertEquals(List.of(first.toString(), second.toString()), files(csv));
    assertRows(samples(concat(concat(seconds(50, 60), new double[] {60}), seconds(70, 79))), csv.rows().get(BATTERY));
    // In the log's seconds it is judged by its start alone, since its span was still growing.
    var seconds = csv(get(query("names", BATTERY, "start_time", "55", "end_time", "75")));
    assertEquals(13, seconds.rows().get(BATTERY).size());
  }

  @Test void aClosedFileThatCannotBeReadIsListedAndTheOthersAnswer() throws Exception {
    standard();
    Files.delete(first);
    var csv = csv(get(window(BATTERY, 50, 80)));
    var skipped = csv.inputs().getAsJsonArray("skipped");
    assertEquals(1, skipped.size());
    assertEquals(first.toString(), skipped.get(0).getAsJsonObject().get("path").getAsString());
    assertTrue(skipped.get(0).getAsJsonObject().get("reason").getAsString().startsWith("Unreadable: "));
    assertEquals(List.of(second.toString()), files(csv));
    // Alone, file 2's clock places its repeated value at 60.25 s.
    var rows = csv.rows().get(BATTERY);
    assertEquals(11, rows.size());
    assertEquals(W + micros(60 + LATER), utc(rows.get(0).get(1)));
  }

  @Test void aFileWithoutAClockLeavesItsStretchUnplacedAndTheOthersAnswer() throws Exception {
    capture(first, null, new double[0], seconds(1, 60));
    capture(second, LATER, concat(new double[] {60}, seconds(70, 100)), concat(new double[] {60}, seconds(70, 100)));
    recording(List.of(closed(first, 1, 60, W + micros(60))), null);
    var csv = csv(get(window(BATTERY, 50, 80)));
    assertRows(samples(seconds(70, 79)), csv.rows().get(BATTERY));
    var basis = csv.entries().get(0).getAsJsonObject().getAsJsonObject("utc_basis");
    assertEquals(60 + 1, basis.get("unplaced_sample_count").getAsInt(), "File 1's samples, and file 2's repeat in file 1's stretch");
    var byPath = query("names", BATTERY, "start_utc", iso(W), "end_utc", iso(W + micros(80)));
    byPath.remove("capture");
    byPath.put("path", first.toString());
    var alone = refusal(get(byPath), 422).get("error").getAsString();
    assertEquals("This log cannot be placed in UTC: " + basis.get("unplaced_reason").getAsString(), alone, "File 1's own reason");
  }

  @Test void aRecordingNoFileOfWhichCanBePlacedIsRefused() throws Exception {
    capture(first, null, new double[0], seconds(1, 60));
    capture(second, null, new double[0], concat(new double[] {60}, seconds(70, 100)));
    recording(List.of(closed(first, 1, 60, W + micros(60))), null);
    var body = refusal(get(window(BATTERY, 50, 80)), 422);
    assertTrue(body.get("error").getAsString().startsWith("No file of this recording can be placed in UTC: "), body.toString());
    assertTrue(body.has("utc_basis"));
  }

  @Test void anAngleUnwrapsOnceAcrossTheRecordingsFiles() throws Exception {
    Files.createDirectories(folder);
    try (var writer = new WpilogWriter(first, "recording fixture")) {
      int h = writer.start(HEADING, "double", "", 0);
      writer.append(h, micros(58), WpilogWriter.encodeDouble(170));
      writer.append(h, micros(59), WpilogWriter.encodeDouble(175));
      writer.append(h, micros(60), WpilogWriter.encodeDouble(179));
    }
    try (var writer = new WpilogWriter(second, "recording fixture")) {
      int h = writer.start(HEADING, "double", "", 0);
      writer.append(h, micros(70), WpilogWriter.encodeDouble(-178));
      writer.append(h, micros(71), WpilogWriter.encodeDouble(-175));
    }
    recording(List.of(closed(first, 58, 60, W + micros(60))), new OpenCapture("capture-2.wpilog", captured(second), Files.size(second), 70, 71));
    var csv = csv(get(query("names", HEADING, "angle", "degrees")));
    var values = csv.rows().get(HEADING).stream().map(row -> Double.parseDouble(row.get(1))).toList();
    assertEquals(List.of(170.0, 175.0, 179.0, 182.0, 185.0), values, "Unwrapped across the reconnect, not again in each file");
  }

  @Test void anEntryOnlyAClosedFileHoldsStillAnswersAndOneNoFileHoldsIsRefused() throws Exception {
    fixture();
    Files.delete(second);
    try (var writer = new WpilogWriter(second, "recording fixture")) {
      int c = writer.start(CLOCK, "int64", "", 0);
      writer.append(c, micros(70), WpilogWriter.encodeInt64(W + micros(70 + LATER)));
    }
    recording(List.of(closed(first, 1, 60, W + micros(60))), new OpenCapture("capture-2.wpilog", captured(second), Files.size(second), 70, 70));
    var csv = csv(get(window(BATTERY, 50, 80)));
    assertRows(samples(seconds(50, 60)), csv.rows().get(BATTERY));
    var missing = refusal(get(window("NT:/NoSuchEntry", 50, 80)), 404);
    assertTrue(missing.get("error").getAsString().contains("NoSuchEntry"), missing.toString());
  }

  @Test void anArrowStreamRefusesAnEntryWhoseTypeDiffersBetweenFilesAndARevSignal() throws Exception {
    fixture();
    Files.delete(first);
    try (var writer = new WpilogWriter(first, "recording fixture")) {
      int c = writer.start(CLOCK, "int64", "", 0);
      int b = writer.start(BATTERY, "int64", "", 0);
      for (double t : seconds(50, 60)) {
        writer.append(c, micros(t), WpilogWriter.encodeInt64(W + micros(t)));
        writer.append(b, micros(t), WpilogWriter.encodeInt64(12));
      }
    }
    recording(List.of(closed(first, 50, 60, W + micros(60))), null);
    var arrow = window(BATTERY, 50, 80);
    arrow.put("format", "arrow");
    var body = refusal(get(arrow), 400);
    assertTrue(body.get("error").getAsString().contains("does not share the value type of the same entry in an earlier file"), body.toString());
    var csv = csv(get(window(BATTERY, 50, 80)));
    assertEquals(11 + 1 + 10, csv.rows().get(BATTERY).size(), "CSV carries both types");
    var rev = refusal(get(window("REV/Fixture/Signal", 50, 80)), 400);
    assertTrue(rev.get("error").getAsString().contains("REV signal"), rev.toString());
  }

  @Test void nominationReadsWhatAWindowCanReachAndRefusesMoreFilesThanTheLimit() throws Exception {
    long start = W + micros(3 * 3600);
    var near = new LiveCapture.SessionFile(Path.of("near.wpilog"), 0, 10, iso(W), iso(start - DataRanges.NOMINATION_MARGIN_US));
    var far = new LiveCapture.SessionFile(Path.of("far.wpilog"), 0, 10, iso(W), iso(start - DataRanges.NOMINATION_MARGIN_US - 1));
    var growing = new LiveCapture.SessionFile(Path.of("growing.wpilog"), 0, 10, iso(W), null);
    assertEquals(List.of(near, growing), DataRecording.nominate(List.of(near, far, growing), null, null, start, null));
    // In the log's seconds a span decides, except for a span still growing when published.
    assertEquals(List.of(growing), DataRecording.nominate(List.of(near, far, growing), 11.0, null, null, null));
    assertEquals(List.of(), DataRecording.nominate(List.of(near), null, -1.0, null, null));
    var many = new ArrayList<LiveCapture.SessionFile>();
    for (int i = 0; i < DataRanges.MAX_FILES - 1; i++) many.add(near);
    assertEquals(DataRanges.MAX_FILES - 1, DataRecording.nominate(many, null, null, null, null).size());
    many.add(near);
    var refused = assertThrows(DataEndpoint.Refusal.class, () -> DataRecording.nominate(many, null, null, null, null));
    assertEquals(413, refused.status);
    assertEquals(DataRanges.MAX_FILES + 1, refused.body.get("files").getAsInt());
  }
}
