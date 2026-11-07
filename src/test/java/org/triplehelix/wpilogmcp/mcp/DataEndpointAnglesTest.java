/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.data.ArrowSpecReader;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * Presentation angles follow the shortest turns without changing raw typed reads. The answers
 * here come from the two-degree crossing, not from another route's implementation.
 */
class DataEndpointAnglesTest {
  @TempDir Path directory;
  private final LogManager logs = LogManager.getInstance();
  private Set<Path> previous;
  private HttpTransport transport;
  private HttpClient client;
  private Path path;

  @BeforeEach void start() throws Exception {
    directory = directory.toRealPath();
    path = directory.resolve("synthetic-angles.wpilog");
    try (var writer = new WpilogWriter(path, "synthetic presentation angles")) {
      int schema = writer.start("/.schema/struct:Rotation2d", "structschema", "", 0);
      writer.append(schema, 0, "double value;".getBytes(StandardCharsets.UTF_8));
      int rotation = writer.start("/Rotation", "struct:Rotation2d", "", 0);
      int degrees = writer.start("/Plain", "double", "", 0);
      int radians = writer.start("/Radians", "double", "", 0);
      int integer = writer.start("/Integer", "int64", "", 0);
      int large = writer.start("/Large", "int64", "", 0);
      int clock = writer.start("systemTime", "int64", "", 0);
      writer.append(clock, 0, WpilogWriter.encodeInt64(1_767_225_600_000_000L));
      for (int i = 0; i < 2; i++) {
        double value = i == 0 ? 179 : -179;
        writer.append(rotation, i * 1_000_000L, WpilogWriter.encodeDouble(Math.toRadians(value)));
        writer.append(degrees, i * 1_000_000L, WpilogWriter.encodeDouble(value));
        writer.append(radians, i * 1_000_000L, WpilogWriter.encodeDouble(Math.toRadians(value)));
        writer.append(integer, i * 1_000_000L, WpilogWriter.encodeInt64(i == 0 ? 3 : -3));
        writer.append(large, i * 1_000_000L, WpilogWriter.encodeInt64(9_007_199_254_740_993L + i));
      }
    }
    previous = logs.getAllowedDirectories();
    logs.clearAllowedDirectories();
    logs.addAllowedDirectory(directory);
    transport = new HttpTransport(new ToolRegistry(), 0);
    transport.start();
    client = HttpClient.newHttpClient();
  }

  @AfterEach void stop() {
    if (transport != null) transport.stop();
    logs.unloadAllLogs();
    logs.clearAllowedDirectories();
    previous.forEach(logs::addAllowedDirectory);
  }

  @ParameterizedTest @ValueSource(strings = {"arrow", "csv"})
  void declaredDegreesReduceAcrossTheWrap(String format) throws Exception {
    var reply = get("/Rotation._derived.degrees", format, "&max_points=1");
    assertColumn(reply, format, "mean", 180);
    assertColumn(reply, format, "min", 179);
    assertColumn(reply, format, "max", 181);
    assertColumn(reply, format, "first", 179);
    assertColumn(reply, format, "last", 181);
    assertColumn(reply, format, "count", 2);
  }

  @ParameterizedTest @ValueSource(strings = {"arrow", "csv"})
  void declaredAnglesUnwrapBothExactShapesAndKeepUtc(String format) throws Exception {
    for (String resolution : List.of("", "&max_points=3")) {
      var reply = get("/Rotation.value", format, resolution + "&utc=true");
      if (resolution.isEmpty()) assertColumn(reply, format, "value",
          Math.toRadians(179), Math.toRadians(181));
      else {
        assertColumn(reply, format, "mean", Math.toRadians(179), Math.toRadians(181));
        assertColumn(reply, format, "count", 1, 1);
      }
      if (format.equals("arrow")) {
        var stream = ArrowSpecReader.read(reply.body());
        assertEquals("timestamp_utc", stream.fields().get(1).name());
        assertEquals(List.of(1_767_225_600_000_000L, 1_767_225_601_000_000L),
            stream.batches().get(0).columns().get(1));
      } else assertTrue(text(reply).contains("2026-01-01T00:00:01.000000Z"));
    }
  }

  @ParameterizedTest @ValueSource(strings = {"degrees", "deg", "radians", "rad"})
  void explicitUnitsApplyToRawAndBucketedNumericReads(String unit) throws Exception {
    boolean degrees = unit.startsWith("deg");
    String name = degrees ? "/Plain" : "/Radians";
    double factor = degrees ? 1 : Math.PI / 180;
    for (String format : List.of("arrow", "csv")) {
      assertColumn(get(name, format, "&angle=" + unit), format, "value", 179 * factor, 181 * factor);
      assertColumn(get(name, format, "&angle=" + unit + "&max_points=1"), format, "mean", 180 * factor);
    }
  }

  @ParameterizedTest @ValueSource(strings = {"arrow", "csv"})
  void plainWithoutOverrideAndLaterWindowRemainTheirOwnValues(String format) throws Exception {
    assertColumn(get("/Plain", format, "&max_points=1"), format, "mean", 0);
    assertColumn(get("/Rotation._derived.degrees", format, "&start_time=1"), format, "value", -179);
    assertColumn(get("/Plain", format, "&angle=degrees&start_time=1"), format, "value", -179);
  }

  @Test void flaggedIntegerRadiansPromoteButOrdinaryIntegersStayExact() throws Exception {
    var raw = ArrowSpecReader.read(get("/Large", "arrow", "").body());
    assertEquals("int64", raw.fields().get(1).type());
    assertEquals(List.of(9_007_199_254_740_993L, 9_007_199_254_740_994L),
        raw.batches().get(0).columns().get(1));
    var reply = get("/Integer", "arrow", "&angle=radians");
    var stream = ArrowSpecReader.read(reply.body());
    assertEquals("float64", stream.fields().get(1).type(),
        "A fractional turn adjustment must not be truncated by the int64 Arrow builder");
    assertColumn(reply, "arrow", "value", 3, 2 * Math.PI - 3);
  }

  @ParameterizedTest @ValueSource(strings = {"", "turns", "degrees&angle=radians"})
  void malformedOrRepeatedAngleIsRefused(String value) throws Exception {
    var reply = get("/Plain", "csv", "&angle=" + value);
    assertEquals(400, reply.statusCode(), "angle must be validated, not silently ignored");
    assertTrue(JsonParser.parseString(text(reply)).getAsJsonObject().get("error").getAsString().contains("angle"));
  }

  @Test void aWholeStructIsNotGuessedWhenAnAngleIsExplicit() throws Exception {
    var reply = get("/Rotation", "csv", "&angle=degrees");
    assertEquals(400, reply.statusCode(), "An explicit numeric request must name its field");
  }

  private HttpResponse<byte[]> get(String name, String format, String suffix) throws Exception {
    String url = "http://127.0.0.1:" + transport.getPort() + HttpTransport.DATA_PATH
        + "?path=" + encode(path.toString()) + "&names=" + encode(name) + "&format=" + format + suffix;
    return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String text(HttpResponse<byte[]> reply) {
    return new String(reply.body(), StandardCharsets.UTF_8);
  }

  private static void assertColumn(HttpResponse<byte[]> reply, String format, String name,
      double... expected) {
    assertEquals(200, reply.statusCode(), () -> text(reply));
    List<Double> actual;
    if (format.equals("arrow")) {
      var stream = ArrowSpecReader.read(reply.body());
      int column = stream.fields().stream().map(ArrowSpecReader.FieldSpec::name).toList().indexOf(name);
      assertTrue(column >= 0, name);
      actual = stream.batches().stream().flatMap(batch -> batch.columns().get(column).stream())
          .map(value -> ((Number) value).doubleValue()).toList();
    } else {
      var lines = text(reply).lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
      int column = Arrays.asList(lines.get(0).split(",", -1)).indexOf(name);
      assertTrue(column >= 0, name);
      actual = lines.stream().skip(1).map(line -> Double.valueOf(line.split(",", -1)[column])).toList();
    }
    assertEquals(expected.length, actual.size(), name);
    for (int i = 0; i < expected.length; i++) assertEquals(expected[i], actual.get(i), 1e-12, name + "[" + i + "]");
  }
}
