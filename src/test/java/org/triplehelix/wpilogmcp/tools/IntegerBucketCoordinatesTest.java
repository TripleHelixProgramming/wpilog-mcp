/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.data.ArrowSpecReader;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.LogParser;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.read.LogAccess;
import org.triplehelix.wpilogmcp.read.ReadQuery;

/** Concrete independent witnesses for integer bucket membership and stable microsecond order. */
class IntegerBucketCoordinatesTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  Set<Path> allowed;
  ToolRegistry registry;
  HttpTransport transport;
  HttpClient client;

  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath();
    allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(temp);
    registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    transport = new HttpTransport(registry, 0);
    transport.start();
    client = HttpClient.newHttpClient();
  }

  @AfterEach void stop() throws Exception {
    if (transport != null) transport.stop();
    manager.release(temp);
    manager.clearAllowedDirectories();
    allowed.forEach(manager::addAllowedDirectory);
  }

  @Test void aOneMicrosecondBoundaryDoesNotMoveToThePreviousBucket() throws Exception {
    // Window [0,5] us / 5 points: indices are exactly 0,1,4. The old division uses
    // 1.0000000000000002e-6 seconds and therefore puts timestamp 1 us into bucket zero.
    var path = fixture("boundary", new long[] {0,0,1,1,5,5}, new double[] {10,11,20,21,30,31});
    var expected = List.of(
        new Expected(0,2,10,11,10.5,10,11),
        new Expected(1,2,20,21,20.5,20,21),
        new Expected(4,2,30,31,30.5,30,31));
    assertAll(
        () -> verify(path, "/Value", null, 5, expected),
        () -> verify(path, "/Array[0]", "[0]", 5, expected));
  }

  @Test void adjacentLargeMicrosecondsKeepTheirRecordedTimeOrder() throws Exception {
    long first = 9_007_199_254_740_992L;
    assertEquals(first / 1e6, (first + 1) / 1e6,
        "This witness must contain two distinct microseconds that collapse to one double");
    // File order is the reverse of time order. Correct first/last are 1/2, and mean is 1.5.
    var path = fixture("adjacent", new long[] {first+1, first}, new double[] {2,1});
    var expected = List.of(new Expected(first,2,1,2,1.5,1,2));
    var projected = fixture("adjacent-projected", new long[] {first+5,first+4,first+3,first+2,first+1,first},
        new double[] {6,5,4,3,2,1});
    // Six adjacent instants, three buckets: pairs (1,2), (3,4), (5,6). A projection which
    // reconstructs coordinates from seconds instead produces counts 2,1,3 after rounding.
    var projectedExpected = List.of(new Expected(first,2,1,2,1.5,1,2),
        new Expected(first+2,2,3,4,3.5,3,4), new Expected(first+4,2,5,6,5.5,5,6));
    assertAll(
        () -> verify(path, "/Value", null, 1, expected),
        () -> verify(path, "/Array[0]", "[0]", 1, expected),
        () -> verify(projected, "/Array[0]", "[0]", 3, projectedExpected),
        () -> verifyRawArrow(path, first),
        () -> {
          var eager = new LogParser().parse(path);
          var wire = new com.google.gson.Gson().toJsonTree(eager.values().get("/Value")).getAsJsonArray();
          assertEquals(Set.of("timestamp","value"), wire.get(0).getAsJsonObject().keySet(),
              "Retaining raw coordinates must not change legacy JSON fields");
          assertEquals(List.of(1.0,2.0), eager.values().get("/Value").stream().map(v -> v.value()).toList(),
              "The eager decoder must preserve the same raw microsecond order as the lazy reader");
        });
  }

  private Path fixture(String name, long[] times, double[] values) throws Exception {
    var path = temp.resolve(name + ".wpilog");
    try (var writer = new WpilogWriter(path, "synthetic exact bucket coordinates")) {
      int scalar = writer.start("/Value", "double", "", 0);
      int array = writer.start("/Array", "double[]", "", 0);
      for (int i = 0; i < times.length; i++) {
        writer.append(scalar, times[i], WpilogWriter.encodeDouble(values[i]));
        writer.append(array, times[i], WpilogWriter.encodeDoubleArray(new double[] {values[i]}));
      }
    }
    return path;
  }

  private record Expected(long startUs, long count, double min, double max, double mean,
      double first, double last) {}

  private void verify(Path path, String name, String field, int points, List<Expected> expected) throws Exception {
    // Every number below is specified above, independently of all four implementations.
    assertAll(
        () -> verifyNative(path, field == null ? name : "/Array", field, points, expected),
        () -> verifyTool("read_entry", path, name, points, expected),
        () -> verifyTool("render_chart", path, name, points, expected),
        () -> verifyArrow(path, name, points, expected));
  }

  private void verifyNative(Path path, String name, String field, int points, List<Expected> expected) throws Exception {
    var values = new ArrayList<Map<?, ?>>();
    try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(path)) {
      var query = ReadQuery.all(name).reduce(points);
      if (field != null) query = query.project(field);
      try (var cursor = snapshot.read(query)) {
        for (;;) {
          var batch = cursor.next();
          assertTrue(batch.problems().isEmpty(), batch.problems()::toString);
          batch.samples().forEach(sample -> values.add((Map<?, ?>) sample.value()));
          if (batch.complete()) break;
        }
      }
    }
    assertEquals(expected.size(), values.size(), "native bucket count");
    for (int i = 0; i < values.size(); i++) {
      var actual = values.get(i); var e = expected.get(i);
      assertEquals(e.startUs(), actual.get("start_us"));
      assertEquals(e.count(), actual.get("count"));
      for (var entry : fields(e).entrySet()) assertEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey());
    }
  }

  private void verifyTool(String tool, Path path, String name, int points, List<Expected> expected) throws Exception {
    var request = new JsonObject();
    request.addProperty("path", path.toString()); request.addProperty("name", name);
    request.addProperty("max_points", points);
    var result = registry.getTool(tool).execute(request).getAsJsonObject();
    assertNotEquals("error", result.get("status").getAsString(), result::toString);
    JsonArray buckets = tool.equals("read_entry") ? result.getAsJsonArray("samples")
        : result.getAsJsonObject("chart_spec").getAsJsonArray("series").get(0).getAsJsonObject().getAsJsonArray("buckets");
    assertNotNull(buckets, result::toString);
    assertEquals(expected.size(), buckets.size(), tool + " bucket count");
    for (int i = 0; i < buckets.size(); i++) {
      var actual = buckets.get(i).getAsJsonObject(); var e = expected.get(i);
      assertEquals(e.startUs() / 1e6, actual.get(tool.equals("read_entry") ? "timestamp_sec" : "start").getAsDouble());
      assertEquals(e.count(), actual.get("count").getAsLong());
      for (var entry : fields(e).entrySet()) assertEquals(entry.getValue(), actual.get(entry.getKey()).getAsDouble(), tool + " " + entry.getKey());
    }
  }

  private void verifyArrow(Path path, String name, int points, List<Expected> expected) throws Exception {
    String url = "http://127.0.0.1:" + transport.getPort() + HttpTransport.DATA_PATH
        + "?path=" + encode(path.toString()) + "&names=" + encode(name) + "&max_points=" + points;
    var response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode(), () -> new String(response.body(), StandardCharsets.UTF_8));
    var rows = new ArrayList<List<Object>>();
    for (var batch : ArrowSpecReader.read(response.body()).batches()) {
      for (int row = 0; row < batch.rows(); row++) {
        var values = new ArrayList<Object>();
        for (var column : batch.columns()) values.add(column.get(row));
        rows.add(values);
      }
    }
    assertEquals(expected.size(), rows.size(), "Arrow bucket count");
    for (int i = 0; i < rows.size(); i++) {
      var e = expected.get(i);
      assertEquals(List.of(e.startUs(),e.count(),e.min(),e.max(),e.mean(),e.first(),e.last()), rows.get(i));
    }
  }

  private void verifyRawArrow(Path path, long first) throws Exception {
    String url = "http://127.0.0.1:" + transport.getPort() + HttpTransport.DATA_PATH
        + "?path=" + encode(path.toString()) + "&names=" + encode("/Value");
    var response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode());
    var timestamps = new ArrayList<Object>(); var values = new ArrayList<Object>();
    for (var batch : ArrowSpecReader.read(response.body()).batches()) {
      timestamps.addAll(batch.columns().get(0)); values.addAll(batch.columns().get(1));
    }
    assertEquals(List.of(first, first+1), timestamps, "Raw Arrow must retain both logged microseconds");
    assertEquals(List.of(1.0,2.0), values);
  }

  private static Map<String,Double> fields(Expected value) {
    return Map.of("min",value.min(),"max",value.max(),"mean",value.mean(),"first",value.first(),"last",value.last());
  }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
