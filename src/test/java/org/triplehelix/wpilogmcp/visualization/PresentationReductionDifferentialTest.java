/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.triplehelix.wpilogmcp.data.ArrowSpecReader;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.CallerContext;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.http.RemoteLogAccess;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * Five real consumers are checked against literal trajectories and independent integer bins.
 * The only numerical exceptions are exact-when-few, the chart's occupied window, its finite
 * filtering, and native reads' raw angles. Explicit plain-angle overrides have separate route
 * tests because chart and preview have no corresponding request parameter.
 */
class PresentationReductionDifferentialTest {
  @TempDir Path directory;
  private static final String ENTRY = "/Value";
  private static final double EPSILON = 1e-9;
  private final LogManager logs = LogManager.getInstance();
  private Set<Path> previous;
  private ToolRegistry registry;
  private HttpTransport transport;
  private VisualizationWorkspace workspace;
  private HttpClient client;

  private enum Shape {
    SCALAR("double", null, false), ARRAY("double[]", "[0]", false),
    DEGREES("struct:Rotation2d", "_derived.degrees", true),
    RADIANS("struct:Rotation2d", "value", true),
    NESTED("struct:HeadingSample", "heading._derived.degrees", true);
    final String type, field;
    final boolean angle;
    Shape(String type, String field, boolean angle) {
      this.type = type; this.field = field; this.angle = angle;
    }
    String selector() { return ENTRY + (field == null ? "" : field.startsWith("[") ? field : "." + field); }
    double unit(double degrees) { return this == RADIANS ? Math.toRadians(degrees) : degrees; }
  }

  /** Continuous values are supplied literally, never computed by the server's unwrap routine. */
  private record Raw(long time, double value, double continuous) {}
  private record Point(long time, double value) {}
  private record Spec(String name, Shape shape, List<Raw> samples, long start, long end) {
    @Override public String toString() { return name; }
  }
  private record Bucket(long start, long count, long finite, Double min, Double max,
      Double mean, double first, double last) {}

  @BeforeEach void start() throws Exception {
    directory = directory.toRealPath();
    previous = logs.getAllowedDirectories();
    logs.clearAllowedDirectories();
    logs.addAllowedDirectory(directory);
    registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    transport = new HttpTransport(registry, 0);
    transport.start();
    workspace = registry.visualizations();
    client = HttpClient.newHttpClient();
  }

  @AfterEach void stop() {
    if (transport != null) transport.stop();
    logs.unloadAllLogs();
    logs.clearAllowedDirectories();
    previous.forEach(logs::addAllowedDirectory);
  }

  static Stream<Arguments> matrix() {
    var crossing = List.of(new Raw(0, 179, 179), new Raw(10, -179, 181));
    var boundary = List.of(new Raw(0, 10, 10), new Raw(0, 11, 11), new Raw(1, 20, 20),
        new Raw(1, 21, 21), new Raw(5, 30, 30), new Raw(5, 31, 31));
    long base = 8_589_934_592_000_000L;
    var specs = List.of(
        new Spec("declared degrees", Shape.DEGREES, crossing, 0, 10),
        new Spec("declared radians", Shape.RADIANS, crossing, 0, 10),
        new Spec("nested declared degrees", Shape.NESTED, crossing, 0, 10),
        new Spec("plain arithmetic crossing", Shape.SCALAR, crossing, 0, 10),
        new Spec("integer boundaries", Shape.SCALAR, boundary, 0, 5),
        new Spec("projected integer boundaries", Shape.ARRAY, boundary, 0, 5),
        new Spec("chart occupied extent", Shape.SCALAR,
            List.of(new Raw(4, 2, 2), new Raw(6, 4, 4), new Raw(14, 6, 6), new Raw(16, 8, 8)), 0, 20),
        new Spec("nonfinite angle gaps", Shape.DEGREES,
            List.of(new Raw(0, 179, 179), new Raw(5, Double.NaN, Double.NaN),
                new Raw(10, -179, 181), new Raw(15, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)), 0, 15),
        new Spec("nonfinite numeric edges", Shape.SCALAR,
            List.of(new Raw(0, Double.NaN, Double.NaN), new Raw(5, 2, 2), new Raw(10, 4, 4),
                new Raw(15, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)), 0, 15),
        new Spec("adjacent coordinates", Shape.ARRAY,
            List.of(new Raw(base + 2, 2, 2), new Raw(base + 1, 1, 1)), base, base + 4),
        new Spec("zero width preserves ties", Shape.SCALAR,
            List.of(new Raw(0, 2, 2), new Raw(10, 4, 4), new Raw(10, 6, 6)), 10, 10),
        new Spec("later angle window resets", Shape.DEGREES, crossing, 5, 15),
        new Spec("empty requested window", Shape.DEGREES, crossing, 30, 40));
    return specs.stream().flatMap(spec -> Arrays.stream(new int[] {1, 5, 8})
        .mapToObj(points -> Arguments.of(spec, points)));
  }

  @ParameterizedTest(name = "{0}, max_points={1}")
  @MethodSource("matrix")
  void fiveRoutesHaveOnlyTheFourNamedDifferences(Spec spec, int points) throws Exception {
    var path = write(spec);
    verifyAll(path, spec, points);
  }

  /**
   * The same answers, against the same oracle, when the log's numbers come from its column file:
   * every shape but the plain array, which a column file does not hold, is read from it.
   */
  @ParameterizedTest(name = "{0}, max_points={1}, from the column file")
  @MethodSource("matrix")
  void fiveRoutesAnswerTheSameFromTheColumnFile(Spec spec, int points) throws Exception {
    var path = write(spec);
    logs.testReadColumnFiles(true);
    try {
      org.triplehelix.wpilogmcp.log.ColumnFixtures.build(logs, path);
      if (spec.shape() != Shape.ARRAY) {
        try (var use = logs.acquire(path.toString())) {
          var field = spec.shape().field == null ? org.triplehelix.wpilogmcp.read.FieldPath.ROOT
              : org.triplehelix.wpilogmcp.read.FieldPath.parse(spec.shape().field);
          assertTrue(use.log().numericColumn(ENTRY, field).isPresent(), "read from the column file");
        }
      }
      verifyAll(path, spec, points);
    } finally {
      logs.testReadColumnFiles(false);
    }
  }

  @Test void theIntegerOracleHasLiteralBoundaryAndAdjacentTimeWitnesses() {
    var points = List.of(new Point(0, 10), new Point(0, 11), new Point(1, 20),
        new Point(1, 21), new Point(5, 30), new Point(5, 31));
    assertEquals(List.of(new Bucket(0, 2, 2, 10.0, 11.0, 10.5, 10, 11),
        new Bucket(1, 2, 2, 20.0, 21.0, 20.5, 20, 21),
        new Bucket(4, 2, 2, 30.0, 31.0, 30.5, 30, 31)), bins(points, 0, 5, 5));
    long first = 8_589_934_592_000_001L;
    assertEquals(first / 1e6, (first + 1) / 1e6,
        "The adjacent original microseconds must collapse to one double-second coordinate");
  }

  private void verifyAll(Path path, Spec spec, int points) {
    var retained = selected(spec, spec.shape().angle);
    var raw = selected(spec, false);
    var finite = retained.stream().filter(point -> Double.isFinite(point.value())).toList();
    assertAll(spec.name(),
        () -> verifyReadEntry(path, spec, points, retained),
        () -> verifyData(path, spec, points, retained, "arrow"),
        () -> verifyData(path, spec, points, retained, "csv"),
        () -> verifyNative(path, spec, points, raw),
        () -> verifyPreview(path, spec, points, retained),
        () -> verifyChart(path, spec, points, finite));
  }

  private void verifyReadEntry(Path path, Spec spec, int points, List<Point> expected) throws Exception {
    var args = arguments(path, spec, points);
    var result = registry.getTool("read_entry").execute(args).getAsJsonObject();
    assertNotEquals("error", result.get("status").getAsString(), result::toString);
    assertEquals(expected.size(), result.get("total_in_range").getAsInt());
    boolean bucketed = expected.size() > points;
    assertEquals(bucketed, result.get("bucketed").getAsBoolean(), "exact-when-few policy");
    if (bucketed) assertBuckets(bins(expected, spec.start(), spec.end(), points),
        jsonRows(result.getAsJsonArray("samples")), "timestamp_sec", false, false, "read_entry");
    else assertExact(expected, jsonRows(result.getAsJsonArray("samples")), "timestamp_sec", false,
        "value", "read_entry exact");
  }

  private void verifyChart(Path path, Spec spec, int points, List<Point> finite) throws Exception {
    var args = arguments(path, spec, points);
    args.addProperty("width", 160); args.addProperty("height", 120);
    var result = registry.getTool("render_chart").execute(args).getAsJsonObject();
    if (finite.isEmpty()) {
      assertEquals("no_match", result.get("status").getAsString(), result::toString);
      return;
    }
    assertNotEquals("error", result.get("status").getAsString(), result::toString);
    var summary = result.getAsJsonArray("summary").get(0).getAsJsonObject();
    assertEquals(finite.size(), summary.get("count").getAsLong(), "chart filters non-finite samples");
    var series = result.getAsJsonObject("chart_spec").getAsJsonArray("series").get(0).getAsJsonObject();
    if (finite.size() > points) {
      assertFalse(series.has("points"));
      // This route alone buckets over the occupied finite extent of the selected part.
      assertBuckets(bins(finite, finite.get(0).time(), finite.get(finite.size() - 1).time(), points),
          jsonRows(series.getAsJsonArray("buckets")), "start", false, false, "chart buckets");
    } else {
      assertFalse(series.has("buckets"));
      var actual = series.getAsJsonArray("points");
      assertEquals(finite.size(), actual.size(), "chart exact points");
      for (int i = 0; i < finite.size(); i++) {
        var pair = actual.get(i).getAsJsonArray();
        assertEquals(finite.get(i).time() / 1e6, pair.get(0).getAsDouble());
        assertNumber(finite.get(i).value(), pair.get(1), false, "chart exact value " + i);
      }
    }
  }

  private void verifyData(Path path, Spec spec, int points, List<Point> retained, String format)
      throws Exception {
    String query = "?path=" + encode(path.toString()) + "&names=" + encode(spec.shape().selector())
        + "&start_time=" + spec.start() / 1e6 + "&end_time=" + spec.end() / 1e6
        + "&max_points=" + points + "&format=" + format;
    var response = client.send(HttpRequest.newBuilder(base().resolve("/data/entries" + query)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode(), () -> new String(response.body(), StandardCharsets.UTF_8));
    var expected = retained.size() > points ? bins(retained, spec.start(), spec.end(), points)
        : retained.stream().map(point -> bucket(point.time(), List.of(point))).toList();
    if (format.equals("arrow")) {
      var stream = ArrowSpecReader.read(response.body());
      assertTrue(stream.endMarker());
      var names = stream.fields().stream().map(ArrowSpecReader.FieldSpec::name).toList();
      assertEquals(List.of("timestamp", "count", "min", "max", "mean", "first", "last"), names);
      var rows = new ArrayList<Map<String, Object>>();
      for (var batch : stream.batches()) {
        assertEquals(spec.shape().selector(), batch.metadata().get("entry"));
        for (int row = 0; row < batch.rows(); row++) {
          var value = new LinkedHashMap<String, Object>();
          for (int col = 0; col < names.size(); col++) value.put(names.get(col), batch.columns().get(col).get(row));
          rows.add(value);
        }
      }
      assertBuckets(expected, rows, "timestamp", true, false, "data Arrow");
    } else {
      var lines = new String(response.body(), StandardCharsets.UTF_8).lines()
          .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
      assertFalse(lines.isEmpty());
      var names = List.of(lines.get(0).split(",", -1));
      assertEquals(List.of("timestamp_sec", "count", "min", "max", "mean", "first", "last"), names);
      var rows = new ArrayList<Map<String, Object>>();
      for (String line : lines.subList(1, lines.size())) {
        var cells = line.split(",", -1);
        assertEquals(names.size(), cells.length);
        var value = new LinkedHashMap<String, Object>();
        for (int i = 0; i < cells.length; i++) value.put(names.get(i), cells[i].isEmpty() ? null : Double.valueOf(cells[i]));
        rows.add(value);
      }
      assertBuckets(expected, rows, "timestamp_sec", false, false, "data CSV");
    }
  }

  private void verifyNative(Path path, Spec spec, int points, List<Point> raw) throws Exception {
    var rows = new ArrayList<Map<String, Object>>();
    try (var remote = new RemoteLogAccess(base()); var snapshot = remote.open(path);
         var cursor = snapshot.read(ReadQuery.window(ENTRY, spec.start(), spec.end(), true)
             .project(spec.shape().field).reduce(points))) {
      for (;;) {
        var batch = cursor.next();
        assertTrue(batch.problems().isEmpty(), batch.problems()::toString);
        for (var sample : batch.samples()) {
          assertInstanceOf(Map.class, sample.value());
          var value = new LinkedHashMap<String, Object>();
          ((Map<?, ?>) sample.value()).forEach((key, item) -> value.put(key.toString(), item));
          rows.add(value);
        }
        if (batch.complete()) break;
      }
    }
    assertBuckets(bins(raw, spec.start(), spec.end(), points), rows, "start_us", true, true,
        "native HTTP retains raw angles");
  }

  private void verifyPreview(Path path, Spec spec, int points, List<Point> retained) throws Exception {
    var source = workspace.sources.identify(CallerContext.Authority.STDIO_LOCAL, path).getAsJsonObject("source_ref");
    var result = workspace.reduction(previewRequest(source, spec.shape(), spec.start(), spec.end(), points), () -> true);
    assertEquals(retained.isEmpty() ? "no_match" : "ok", result.get("status").getAsString(), result::toString);
    assertEquals(retained.size(), result.get("sample_count").getAsLong());
    assertEquals(retained.stream().filter(point -> Double.isFinite(point.value())).count(),
        result.get("finite_count").getAsLong());
    assertBuckets(bins(retained, spec.start(), spec.end(), points), jsonRows(result.getAsJsonArray("buckets")),
        "start_us", true, true, "preview");
  }

  private static JsonObject previewRequest(JsonObject source, Shape shape, long start, long end, int points) {
    var request = new JsonObject(); request.add("source_ref", source);
    request.addProperty("entry", ENTRY);
    if (shape.field != null) request.addProperty("field", shape.field);
    request.addProperty("start_time", start / 1e6); request.addProperty("end_time", end / 1e6);
    request.addProperty("max_points", points);
    return request;
  }

  private static JsonObject arguments(Path path, Spec spec, int points) {
    var args = new JsonObject(); args.addProperty("path", path.toString());
    args.addProperty("name", spec.shape().selector()); args.addProperty("max_points", points);
    args.addProperty("start_time", spec.start() / 1e6); args.addProperty("end_time", spec.end() / 1e6);
    return args;
  }

  private Path write(Spec spec) throws Exception {
    var path = directory.resolve("synthetic-presentation.wpilog");
    try (var writer = new WpilogWriter(path, "synthetic presentation differential")) {
      writeValues(writer, spec.shape(), spec.samples());
    }
    return path;
  }

  private static void writeValues(WpilogWriter writer, Shape shape, List<Raw> samples) throws Exception {
    if (shape.angle) {
      int schema = writer.start("/.schema/struct:Rotation2d", "structschema", "", 0);
      writer.append(schema, 0, WpilogWriter.encodeString("double value"));
      if (shape == Shape.NESTED) {
        int nested = writer.start("/.schema/struct:HeadingSample", "structschema", "", 0);
        writer.append(nested, 0, WpilogWriter.encodeString("Rotation2d heading"));
      }
    }
    int entry = writer.start(ENTRY, shape.type, "", 0);
    for (var sample : samples) {
      double stored = shape.angle ? Math.toRadians(sample.value()) : sample.value();
      writer.append(entry, sample.time(), shape == Shape.ARRAY
          ? WpilogWriter.encodeDoubleArray(new double[] {stored}) : WpilogWriter.encodeDouble(stored));
    }
  }

  private URI base() { return URI.create("http://127.0.0.1:" + transport.getPort()); }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

  /** Literal continuous traces are rebased to the first selected finite sample of this request. */
  private static List<Point> selected(Spec spec, boolean presentation) {
    var selected = spec.samples().stream().filter(sample -> sample.time() >= spec.start() && sample.time() <= spec.end())
        .sorted(Comparator.comparingLong(Raw::time)).toList();
    double shift = 0;
    if (presentation) {
      for (var sample : selected) if (Double.isFinite(sample.value())) {
        shift = sample.continuous() - sample.value(); break;
      }
    }
    var result = new ArrayList<Point>();
    for (var sample : selected) result.add(new Point(sample.time(), spec.shape().unit(
        presentation && Double.isFinite(sample.value()) ? sample.continuous() - shift : sample.value())));
    return result;
  }

  /** Integer inequalities define membership; no production coordinate or accumulator helper is called. */
  private static List<Bucket> bins(List<Point> samples, long start, long end, int points) {
    if (samples.isEmpty()) return List.of();
    long span = end - start;
    if (span == 0) return List.of(bucket(start, samples));
    var output = new ArrayList<Bucket>();
    for (int index = 0; index < points; index++) {
      var members = new ArrayList<Point>();
      for (var sample : samples) {
        long scaled = (sample.time() - start) * points;
        if (sample.time() == end ? index == points - 1
            : scaled >= index * span && scaled < (index + 1L) * span) members.add(sample);
      }
      if (!members.isEmpty()) {
        long relative = 0;
        // All fixture spans are tiny; enumerate the first microsecond meeting this rational edge.
        while (relative * points < index * span) relative++;
        output.add(bucket(start + relative, members));
      }
    }
    return output;
  }

  private static Bucket bucket(long start, List<Point> values) {
    long finite = values.stream().filter(point -> Double.isFinite(point.value())).count();
    double total = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    for (var point : values) if (Double.isFinite(point.value())) {
      total += point.value(); min = Math.min(min, point.value()); max = Math.max(max, point.value());
    }
    return new Bucket(start, values.size(), finite, finite == 0 ? null : min, finite == 0 ? null : max,
        finite == 0 ? null : total / finite, values.get(0).value(), values.get(values.size() - 1).value());
  }

  private static List<Map<String, Object>> jsonRows(JsonArray rows) {
    assertNotNull(rows);
    var result = new ArrayList<Map<String, Object>>();
    for (var item : rows) {
      var row = new LinkedHashMap<String, Object>();
      item.getAsJsonObject().entrySet().forEach(entry -> row.put(entry.getKey(), entry.getValue()));
      result.add(row);
    }
    return result;
  }

  private static void assertExact(List<Point> expected, List<Map<String, Object>> actual,
      String timestamp, boolean exactTime, String value, String label) {
    assertEquals(expected.size(), actual.size(), label + " rows");
    for (int i = 0; i < expected.size(); i++) {
      if (exactTime) assertEquals(expected.get(i).time(), integer(actual.get(i).get(timestamp)), label + " time");
      else assertEquals(expected.get(i).time() / 1e6, number(actual.get(i).get(timestamp)), label + " time");
      assertNumber(expected.get(i).value(), actual.get(i).get(value), false, label + " value " + i);
    }
  }

  private static void assertBuckets(List<Bucket> expected, List<Map<String, Object>> actual,
      String timestamp, boolean exactTime, boolean finiteCount, String label) {
    assertEquals(expected.size(), actual.size(), label + " buckets");
    for (int i = 0; i < expected.size(); i++) {
      var e = expected.get(i); var a = actual.get(i); String at = label + " bucket " + i;
      if (exactTime) assertEquals(e.start(), integer(a.get(timestamp)), at + " exact start");
      else assertEquals(e.start() / 1e6, number(a.get(timestamp)), at + " start");
      assertEquals(e.count(), integer(a.get("count")), at + " count");
      if (finiteCount) assertEquals(e.finite(), integer(a.get("finite_count")), at + " finite count");
      assertNumber(e.min(), a.get("min"), false, at + " min");
      assertNumber(e.max(), a.get("max"), false, at + " max");
      assertNumber(e.mean(), a.get("mean"), false, at + " mean");
      boolean gapsAreNull = label.equals("preview") || label.equals("rollover merge") || label.equals("new interval");
      assertNumber(e.first(), a.get("first"), gapsAreNull, at + " first");
      assertNumber(e.last(), a.get("last"), gapsAreNull, at + " last");
    }
  }

  private static Object primitive(Object value) {
    if (value instanceof JsonElement json) {
      if (json.isJsonNull()) return null;
      return json.getAsJsonPrimitive().isString() ? json.getAsString() : json.getAsDouble();
    }
    return value;
  }
  private static long integer(Object value) {
    if (value instanceof JsonElement json) return json.getAsLong();
    if (value instanceof Number number) return number.longValue();
    return Long.parseLong(value.toString());
  }
  private static double number(Object value) {
    value = primitive(value);
    return value instanceof Number number ? number.doubleValue() : Double.parseDouble(value.toString());
  }
  private static void assertNumber(Double expected, Object actual, boolean gapIsNull, String label) {
    actual = primitive(actual);
    if (expected == null || gapIsNull && !Double.isFinite(expected)) { assertNull(actual, label); return; }
    assertNotNull(actual, label);
    double value = number(actual);
    if (!Double.isFinite(expected)) assertEquals(expected.doubleValue(), value, label);
    else assertEquals(expected.doubleValue(), value, EPSILON, label);
  }
}
