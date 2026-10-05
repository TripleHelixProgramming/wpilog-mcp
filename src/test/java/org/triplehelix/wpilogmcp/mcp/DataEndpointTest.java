/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.data.ArrowSpecReader;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * {@code GET /data/entries} on the real transport, over the fixture corpus: the Arrow stream is
 * read back by a reader written from the specification ({@link ArrowSpecReader}) and compared
 * to the fixture's formula, to {@code read_entry}'s buckets, and to {@code export_csv}; the
 * refusals carry their reasons; the ETag turns a repeated request into a 304. Every stream and
 * CSV this test produces is also written under {@code build/arrow-samples/}, where CI's pyarrow
 * check reads the streams with the reference implementation and compares them to the CSV.
 */
class DataEndpointTest {
  private static final double LOOP = 0.02;
  private static final double START = 6.0;
  private static final Path SAMPLES = Path.of("build", "arrow-samples");

  private static ToolRegistry registry;
  private static HttpTransport transport;
  private static HttpClient client;
  private static Path match;
  private static int port;

  @BeforeAll
  static void start() throws IOException {
    var dir = FixtureLogs.defaultDirectory();
    for (var f : FixtureLogs.generateAll(dir)) {
      if (f.id().equals("akit_match")) match = f.path();
    }
    assertNotNull(match);
    LogManager.getInstance().addAllowedDirectory(dir);
    registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    transport = new HttpTransport(registry, 0);
    transport.start();
    port = transport.getPort();
    client = HttpClient.newHttpClient();
    Files.createDirectories(SAMPLES);
  }

  @AfterAll
  static void stop() {
    transport.stop();
    LogManager.getInstance().unloadAllLogs();
  }

  // ---- helpers ----

  private static String url(Map<String, String> params) {
    var sb = new StringBuilder("http://127.0.0.1:" + port + HttpTransport.DATA_PATH + "?");
    boolean first = true;
    for (var e : params.entrySet()) {
      if (!first) sb.append('&');
      first = false;
      sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
          .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
    }
    return sb.toString();
  }

  private static HttpResponse<byte[]> get(Map<String, String> params, String... headers)
      throws IOException, InterruptedException {
    var builder = HttpRequest.newBuilder(URI.create(url(params))).GET();
    for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static JsonObject json(HttpResponse<byte[]> response) {
    return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
  }

  private static JsonObject call(String tool, Object... keyValues) throws Exception {
    var args = new JsonObject();
    args.addProperty("path", match.toString());
    for (int i = 0; i < keyValues.length; i += 2) {
      if (keyValues[i + 1] instanceof Number n) args.addProperty((String) keyValues[i], n);
      else if (keyValues[i + 1] instanceof Boolean b) args.addProperty((String) keyValues[i], b);
      else args.addProperty((String) keyValues[i], keyValues[i + 1].toString());
    }
    return registry.getTool(tool).execute(args).getAsJsonObject();
  }

  /** Saves a response for the pyarrow check, named for the sample. */
  private static void save(String name, HttpResponse<byte[]> arrow, HttpResponse<byte[]> csv)
      throws IOException {
    Files.write(SAMPLES.resolve(name + ".arrow"), arrow.body());
    Files.write(SAMPLES.resolve(name + ".csv"), csv.body());
  }

  private static Map<String, String> params(String... keyValues) {
    var map = new java.util.LinkedHashMap<String, String>();
    map.put("path", match.toString());
    for (int i = 0; i < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
    return map;
  }

  /** The fixture's battery voltage at a time: see FixtureLogs.akitMatch. */
  private static double battery(double t) {
    boolean enabled = (t >= 20.0 && t < 40.0) || (t >= 43.0 && t < 183.0);
    double load = enabled ? 1.5 * Math.abs(Math.sin(0.4 * t)) : 0.0;
    double v = enabled ? 12.2 - load : 12.6;
    if (t >= 150.0 && t < 150.06) v = 7.1;
    return v;
  }

  private static List<Double> loopTimes(double start, double end) {
    var out = new ArrayList<Double>();
    for (int i = 0; ; i++) {
      double t = Math.round((START + i * LOOP) * 1e6) / 1e6;
      if (t > end) break;
      if (t >= start) out.add(t);
    }
    return out;
  }

  // ---- the stream ----

  @Test
  @DisplayName("every sample of a double entry over a window, timestamps exact, with the metadata a tool result carries")
  void exactDouble() throws Exception {
    var p = params("names", "/SystemStats/BatteryVoltage", "start_time", "20", "end_time", "40");
    var response = get(p);
    assertEquals(200, response.statusCode());
    assertEquals(DataEndpoint.ARROW_TYPE, response.headers().firstValue("Content-Type").orElse(""));
    assertTrue(response.headers().firstValue("ETag").isPresent());
    var stream = ArrowSpecReader.read(response.body());
    assertTrue(stream.endMarker());
    assertEquals(List.of("timestamp", "value"), stream.fields().stream().map(ArrowSpecReader.FieldSpec::name).toList());
    assertEquals("timestamp[us]", stream.fields().get(0).type());
    assertEquals("float64", stream.fields().get(1).type());
    var times = loopTimes(20, 40);
    var timestamps = new ArrayList<Long>();
    var values = new ArrayList<Double>();
    for (var batch : stream.batches()) {
      assertEquals("/SystemStats/BatteryVoltage", batch.metadata().get("entry"));
      for (var t : batch.columns().get(0)) timestamps.add((Long) t);
      for (var v : batch.columns().get(1)) values.add((Double) v);
    }
    assertEquals(times.size(), timestamps.size());
    for (int i = 0; i < times.size(); i++) {
      assertEquals(Math.round(times.get(i) * 1e6), timestamps.get(i), "timestamp " + i);
      assertEquals(battery(times.get(i)), values.get(i), 1e-9, "value " + i);
    }
    // The metadata
    assertEquals("wpilog-mcp", stream.metadata().get("server"));
    assertEquals(org.triplehelix.wpilogmcp.Version.VERSION, stream.metadata().get("server_version"));
    var inputs = JsonParser.parseString(stream.metadata().get("inputs")).getAsJsonObject();
    assertEquals(match.toString(), inputs.get("log").getAsString());
    assertEquals(20.0, inputs.getAsJsonObject("window").get("start_time").getAsDouble());
    assertTrue(inputs.getAsJsonObject("file").get("size").getAsLong() > 0);
    var entries = JsonParser.parseString(stream.metadata().get("entries")).getAsJsonArray();
    var entry = entries.get(0).getAsJsonObject();
    assertEquals("double", entry.get("type").getAsString());
    assertEquals("periodic", entry.get("sampling").getAsString());
    assertEquals(times.size(), entry.get("total_in_range").getAsInt());
    assertEquals(loopTimes(START, 200).size(), entry.get("sample_count").getAsInt());
    assertFalse(entry.has("unit"), "BatteryVoltage states no unit suffix");
    assertEquals("false", stream.metadata().get("bucketed"));
    var range = JsonParser.parseString(stream.metadata().get("time_range_sec")).getAsJsonObject();
    assertEquals(START, range.get("start").getAsDouble(), 1e-6);
    save("battery_window", response, get(params("names", "/SystemStats/BatteryVoltage", "start_time", "20",
        "end_time", "40", "format", "csv")));
  }

  @Test
  @DisplayName("CSV is the export tool's form, and holds the same numbers as the stream")
  void csv() throws Exception {
    var response = get(params("names", "/SystemStats/BatteryVoltage", "start_time", "20",
        "end_time", "21", "format", "csv"));
    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
    var lines = new String(response.body(), StandardCharsets.UTF_8).lines().toList();
    assertTrue(lines.get(0).startsWith("# wpilog-mcp "));
    assertTrue(lines.get(1).startsWith("# inputs: "));
    assertTrue(lines.get(2).startsWith("# entries: "));
    assertEquals("# entry: /SystemStats/BatteryVoltage", lines.get(3));
    assertEquals("timestamp_sec,value", lines.get(4));
    var exported = call("export_csv", "name", "/SystemStats/BatteryVoltage", "start_time", 20,
        "end_time", 21, "inline", true, "max_rows", 5000);
    assertEquals("ok", exported.get("status").getAsString());
    var rows = exported.getAsJsonArray("rows");
    assertEquals(rows.size(), lines.size() - 5);
    for (int i = 0; i < rows.size(); i++) {
      var cells = rows.get(i).getAsJsonArray();
      assertEquals(cells.get(0).getAsDouble() + "," + cells.get(1).getAsDouble(), lines.get(5 + i));
    }
  }

  @Test
  @DisplayName("bucketed, the stream's buckets are read_entry's buckets")
  void bucketed() throws Exception {
    var response = get(params("names", "/SystemStats/BatteryVoltage", "start_time", "20",
        "end_time", "40", "max_points", "4"));
    assertEquals(200, response.statusCode());
    var stream = ArrowSpecReader.read(response.body());
    assertEquals(List.of("timestamp", "count", "min", "max", "mean", "first", "last"),
        stream.fields().stream().map(ArrowSpecReader.FieldSpec::name).toList());
    var tool = call("read_entry", "name", "/SystemStats/BatteryVoltage", "start_time", 20,
        "end_time", 40, "max_points", 4);
    var samples = tool.getAsJsonArray("samples");
    var rows = stream.batches().stream().filter(b -> b.rows() > 0).toList();
    assertEquals(1, rows.size());
    var batch = rows.get(0);
    assertEquals(samples.size(), batch.rows());
    for (int i = 0; i < samples.size(); i++) {
      var s = samples.get(i).getAsJsonObject();
      assertEquals(Math.round(s.get("timestamp_sec").getAsDouble() * 1e6), batch.columns().get(0).get(i));
      assertEquals(s.get("count").getAsLong(), batch.columns().get(1).get(i));
      assertEquals(s.get("min").getAsDouble(), (Double) batch.columns().get(2).get(i), 1e-12);
      assertEquals(s.get("max").getAsDouble(), (Double) batch.columns().get(3).get(i), 1e-12);
      assertEquals(s.get("mean").getAsDouble(), (Double) batch.columns().get(4).get(i), 1e-12);
      assertEquals(s.get("first").getAsDouble(), (Double) batch.columns().get(5).get(i), 1e-12);
      assertEquals(s.get("last").getAsDouble(), (Double) batch.columns().get(6).get(i), 1e-12);
    }
    var entries = JsonParser.parseString(stream.metadata().get("entries")).getAsJsonArray();
    assertEquals(5.0, entries.get(0).getAsJsonObject().get("bucket_sec").getAsDouble(), 1e-12);
    assertEquals("true", stream.metadata().get("bucketed"));
    save("battery_buckets", response, get(params("names", "/SystemStats/BatteryVoltage", "start_time", "20",
        "end_time", "40", "max_points", "4", "format", "csv")));
    // Two numeric entries of different types bucket together, one batch each
    var two = get(params("names", "/SystemStats/BatteryVoltage,/SystemStats/CANBus/OffCount", "max_points", "3"));
    assertEquals(200, two.statusCode());
    var twoStream = ArrowSpecReader.read(two.body());
    assertEquals(List.of("/SystemStats/BatteryVoltage", "/SystemStats/CANBus/OffCount"),
        twoStream.batches().stream().map(b -> b.metadata().get("entry")).toList());
    assertEquals(1, twoStream.batches().get(1).rows(), "one sample: its own bucket, exact");
  }

  @Test
  @DisplayName("a struct entry is a struct column with the schema's fields; a string entry is text; a field path is a number")
  void types() throws Exception {
    var pose = get(params("names", "/RealOutputs/Drive/Pose", "start_time", "43", "end_time", "43.1"));
    assertEquals(200, pose.statusCode());
    var stream = ArrowSpecReader.read(pose.body());
    var value = stream.fields().get(1);
    assertEquals("struct", value.type());
    assertEquals(List.of("translation", "rotation"), value.children().stream().map(ArrowSpecReader.FieldSpec::name).toList());
    var rows = stream.batches().get(0);
    @SuppressWarnings("unchecked")
    var first = (Map<String, Object>) rows.columns().get(1).get(0);
    @SuppressWarnings("unchecked")
    var translation = (Map<String, Object>) first.get("translation");
    assertEquals(3.0 + 2.0 * Math.sin(0.05 * 43.0), (Double) translation.get("x"), 1e-6);
    save("pose_struct", pose, get(params("names", "/RealOutputs/Drive/Pose", "start_time", "43", "end_time", "43.1", "format", "csv")));

    var text = get(params("names", "/RealOutputs/Console"));
    assertEquals(200, text.statusCode());
    var textStream = ArrowSpecReader.read(text.body());
    assertEquals("utf8", textStream.fields().get(1).type());
    assertTrue(((String) textStream.batches().get(0).columns().get(1).get(1)).contains("overrun"));
    save("console_text", text, get(params("names", "/RealOutputs/Console", "format", "csv")));

    var field = get(params("names", "/RealOutputs/Drive/Pose.translation.x", "start_time", "43", "end_time", "43.1"));
    assertEquals(200, field.statusCode());
    var fieldStream = ArrowSpecReader.read(field.body());
    assertEquals("float64", fieldStream.fields().get(1).type());
    assertEquals((Double) translation.get("x"), (Double) fieldStream.batches().get(0).columns().get(1).get(0), 1e-12);
    var entries = JsonParser.parseString(fieldStream.metadata().get("entries")).getAsJsonArray();
    assertEquals("/RealOutputs/Drive/Pose", entries.get(0).getAsJsonObject().get("entry").getAsString());
    assertEquals(".translation.x", entries.get(0).getAsJsonObject().get("field").getAsString());
    // The pose's fields over a teleop stretch, for the extension's field view checks
    for (var part : List.of("translation.x", "translation.y", "rotation.value")) {
      var name = "/RealOutputs/Drive/Pose." + part;
      save("pose_" + part.replace('.', '_'),
          get(params("names", name, "start_time", "43", "end_time", "63")),
          get(params("names", name, "start_time", "43", "end_time", "63", "format", "csv")));
    }

    var array = get(params("names", "/PowerDistribution/ChannelCurrent", "start_time", "43", "end_time", "43.2"));
    assertEquals(200, array.statusCode());
    var arrayStream = ArrowSpecReader.read(array.body());
    assertEquals("list", arrayStream.fields().get(1).type());
    assertEquals(24, ((List<?>) arrayStream.batches().get(0).columns().get(1).get(0)).size());
    save("channel_currents", array, get(params("names", "/PowerDistribution/ChannelCurrent", "start_time", "43", "end_time", "43.2", "format", "csv")));

    var bool = get(params("names", "/DriverStation/Enabled"));
    assertEquals(200, bool.statusCode());
    assertEquals("bool", ArrowSpecReader.read(bool.body()).fields().get(1).type());
    var unit = get(params("names", "/Elevator/VelocityMetersPerSec", "max_points", "10"));
    var unitEntries = JsonParser.parseString(ArrowSpecReader.read(unit.body()).metadata().get("entries")).getAsJsonArray();
    assertEquals("m/s", unitEntries.get(0).getAsJsonObject().get("unit").getAsString());
  }

  @Test
  @DisplayName("two entries share a stream only when they share a value type")
  void mixedTypes() throws Exception {
    var same = get(params("names", "/SystemStats/BatteryVoltage,/SystemStats/BatteryCurrent", "start_time", "20", "end_time", "20.1"));
    assertEquals(200, same.statusCode());
    var stream = ArrowSpecReader.read(same.body());
    assertEquals(List.of("/SystemStats/BatteryVoltage", "/SystemStats/BatteryCurrent"),
        stream.batches().stream().map(b -> b.metadata().get("entry")).toList());
    var mixed = get(params("names", "/SystemStats/BatteryVoltage,/RealOutputs/Console"));
    assertEquals(400, mixed.statusCode());
    var body = json(mixed);
    assertTrue(body.get("error").getAsString().contains("/RealOutputs/Console"));
    assertTrue(body.get("hint").getAsString().contains("separately"));
  }

  // ---- refusals ----

  @Test
  @DisplayName("a missing entry, a missing path, a file outside the log directories, and bad numbers are refused with their reasons")
  void refusals() throws Exception {
    var missing = get(params("names", "/Nope"));
    assertEquals(404, missing.statusCode());
    assertTrue(json(missing).get("error").getAsString().contains("/Nope"));
    var noPath = get(Map.of("names", "/x"));
    assertEquals(400, noPath.statusCode());
    assertTrue(json(noPath).get("error").getAsString().contains("path"));
    var noNames = get(Map.of("path", match.toString()));
    assertEquals(400, noNames.statusCode());
    var outside = Files.createTempFile("outside", ".wpilog");
    try {
      var refused = get(Map.of("path", outside.toString(), "names", "/x"));
      assertEquals(403, refused.statusCode(), new String(refused.body(), StandardCharsets.UTF_8));
    } finally {
      Files.delete(outside);
    }
    var gone = get(Map.of("path", match.resolveSibling("nope.wpilog").toString(), "names", "/x"));
    assertTrue(gone.statusCode() == 404 || gone.statusCode() == 403, String.valueOf(gone.statusCode()));
    assertEquals(400, get(params("names", "/SystemStats/BatteryVoltage", "max_points", "0")).statusCode());
    assertEquals(400, get(params("names", "/SystemStats/BatteryVoltage", "start_time", "abc")).statusCode());
    assertEquals(400, get(params("names", "/SystemStats/BatteryVoltage", "start_time", "5", "end_time", "4")).statusCode());
    assertEquals(400, get(params("names", "/SystemStats/BatteryVoltage", "format", "xml")).statusCode());
    assertEquals(400, get(params("names", "/RealOutputs/Console", "max_points", "5")).statusCode(), "text cannot be bucketed");
    var post = client.send(HttpRequest.newBuilder(URI.create(url(params("names", "/x"))))
        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(405, post.statusCode());
  }

  @Test
  @DisplayName("a response over the cap is refused with the count, the size, and the hint, never cut")
  void overCap() throws Exception {
    transport.setDataMaxBytes(1000);
    try {
      var refused = get(params("names", "/SystemStats/BatteryVoltage"));
      assertEquals(413, refused.statusCode());
      var body = json(refused);
      assertTrue(body.get("rows").getAsLong() > 1000);
      assertTrue(body.get("bytes").getAsLong() > 1000);
      assertEquals(1000, body.get("max_bytes").getAsLong());
      assertTrue(body.get("hint").getAsString().contains("max_points"));
      // Bucketed, the same entry fits
      assertEquals(200, get(params("names", "/SystemStats/BatteryVoltage", "max_points", "10")).statusCode());
    } finally {
      transport.setDataMaxBytes(DataEndpoint.DEFAULT_MAX_BYTES);
    }
  }

  @Test
  @DisplayName("a browser origin is refused, as the MCP endpoint refuses it")
  void origin() throws Exception {
    var refused = get(params("names", "/SystemStats/BatteryVoltage"), "Origin", "https://evil.example");
    assertEquals(403, refused.statusCode());
    var local = get(params("names", "/SystemStats/BatteryVoltage", "max_points", "5"), "Origin", "http://localhost:3000");
    assertEquals(200, local.statusCode());
  }

  @Test
  @DisplayName("the same request for an unchanged file is a 304; a changed file gets a new ETag")
  void etag() throws Exception {
    var p = params("names", "/SystemStats/CANBus/Utilization", "max_points", "5");
    var first = get(p);
    assertEquals(200, first.statusCode());
    var etag = first.headers().firstValue("ETag").orElseThrow();
    var again = get(p, "If-None-Match", etag);
    assertEquals(304, again.statusCode());
    assertEquals(0, again.body().length);
    var other = get(params("names", "/SystemStats/CANBus/Utilization", "max_points", "6"), "If-None-Match", etag);
    assertEquals(200, other.statusCode(), "another query is another resource");
    assertNotEquals(etag, other.headers().firstValue("ETag").orElseThrow());
    // The file changes: its modification time moves, so the log is loaded again and the tag differs
    var was = Files.getLastModifiedTime(match);
    Files.setLastModifiedTime(match, FileTime.fromMillis(was.toMillis() + 5000));
    try {
      var changed = get(p, "If-None-Match", etag);
      assertEquals(200, changed.statusCode());
      assertNotEquals(etag, changed.headers().firstValue("ETag").orElseThrow());
      assertNull(ArrowSpecReader.read(changed.body()).batches().get(0).metadata().get("file_changed"));
    } finally {
      Files.setLastModifiedTime(match, was);
    }
  }

  @Test
  @DisplayName("get_server_guide names the endpoint once it is set, and not before")
  void guide() throws Exception {
    org.triplehelix.wpilogmcp.tools.DiscoveryTools.setDataEndpoint(null);
    var without = registry.getTool("get_server_guide").execute(new JsonObject()).getAsJsonObject();
    assertFalse(without.has("data_endpoint"));
    org.triplehelix.wpilogmcp.tools.DiscoveryTools.setDataEndpoint(transport.dataEndpointUrl());
    try {
      var with = registry.getTool("get_server_guide").execute(new JsonObject()).getAsJsonObject();
      var endpoint = with.getAsJsonObject("data_endpoint");
      assertEquals("http://127.0.0.1:" + port + "/data/entries", endpoint.get("url").getAsString());
      assertTrue(endpoint.get("parameters").getAsString().contains("max_points"));
    } finally {
      org.triplehelix.wpilogmcp.tools.DiscoveryTools.setDataEndpoint(null);
    }
  }
}
