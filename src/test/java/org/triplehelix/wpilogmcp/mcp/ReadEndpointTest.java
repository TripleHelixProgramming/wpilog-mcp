/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.data.ArrowSpecReader;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.read.LogAccess;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.ReadSnapshot;
import org.triplehelix.wpilogmcp.read.SampleCursor;
import org.triplehelix.wpilogmcp.read.SampleBatch;
import org.triplehelix.wpilogmcp.read.http.RemoteLogAccess;

/** The native HTTP interface exposes original coordinates and values, with explicit read leases. */
class ReadEndpointTest {
  @TempDir Path temp;
  private HttpServer server;
  private ReadEndpoint endpoint;
  private LogAccess local;
  private final HttpClient client = HttpClient.newHttpClient();
  private URI base;
  private java.util.concurrent.ExecutorService workers;

  private Path fixture() throws Exception {
    var path = temp.resolve("exact.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int value = writer.start("/integer", "int64", "{\"source\":\"fixture\"}", 0);
      writer.append(value, 9_007_199_254_740_993L, WpilogWriter.encodeInt64(Long.MAX_VALUE));
      writer.append(value, 9_007_199_254_740_994L, WpilogWriter.encodeInt64(Long.MIN_VALUE));
      int bytes = writer.start("/unknown", "vendor:payload", "opaque", 0);
      writer.append(bytes, 1, new byte[] {0, 1, (byte) 255});
    }
    return path.toRealPath();
  }

  @Test void multiEntryAdmissionCarriesHeterogeneousQueriesInOneRequest() throws Exception {
    var path = fixture(); start(2, new AtomicLong());
    var id = json(open(path)).get("id").getAsString();
    var response = request("POST", "/read/v1/snapshots/" + id + "/query",
        "{\"queries\":[{\"entry\":\"/integer\"},{\"entry\":\"/unknown\"}]}");
    assertEquals(200, response.statusCode(), new String(response.body()));
    var lines = new String(response.body()).lines().map(JsonParser::parseString).map(e -> e.getAsJsonObject()).toList();
    assertEquals("/integer", lines.get(0).get("entry").getAsString());
    assertEquals("/unknown", lines.get(1).get("entry").getAsString());
    assertFalse(lines.get(0).get("complete").getAsBoolean()); assertTrue(lines.get(1).get("complete").getAsBoolean());
    assertEquals("ok", lines.get(2).get("status").getAsString());
  }

  @Test void localAndRemoteMultiReadsHaveTheSameTablesAndOutliveRetirement() throws Exception {
    var path = fixture(); start(2, new AtomicLong());
    var queries = List.of(ReadQuery.all("/integer"), ReadQuery.all("/unknown"));
    try (var remote = new RemoteLogAccess(base); var snapshot = remote.open(path);
        var cursor = snapshot.readAll(queries)) {
      snapshot.close(); remote.close();
      var first = cursor.next(); assertEquals(0, first.queryIndex()); assertFalse(first.complete());
      assertEquals(Long.MAX_VALUE, first.batch().samples().get(0).value());
      var second = cursor.next(); assertEquals(1, second.queryIndex()); assertTrue(second.complete());
      assertArrayEquals(new byte[]{0,1,(byte)255}, (byte[]) second.batch().samples().get(0).value());
    }
    try (var snapshot = local.open(path); var cursor = snapshot.readAll(queries)) {
      snapshot.close(); assertEquals(2, cursor.next().batch().samples().size());
      assertTrue(cursor.next().complete());
    }
  }

  @Test void multiArrowCarriesAnIndependentIpcStreamPerQuery() throws Exception {
    var path = fixture(); start(2, new AtomicLong());
    var id = json(open(path)).get("id").getAsString();
    var response = request("POST", "/read/v1/snapshots/" + id + "/query",
        "{\"format\":\"arrow\",\"queries\":[{\"entry\":\"/integer\"},{\"entry\":\"/unknown\"}]}");
    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("multipart/mixed; boundary="));
    byte[] bytes = response.body();
    String boundary = response.headers().firstValue("Content-Type").orElseThrow().split("boundary=")[1];
    var text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    var parts = text.split(java.util.regex.Pattern.quote("--" + boundary));
    for (int i = 1; i <= 2; i++) {
      String part = parts[i]; int start = part.indexOf("\r\n\r\n") + 4;
      var ipc = part.substring(start, part.length() - 2).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
      var arrow = ArrowSpecReader.read(ipc); assertTrue(arrow.endMarker());
      assertEquals(i == 1 ? "/integer" : "/unknown", arrow.metadata().get("entry"));
    }
    assertTrue(text.endsWith("--" + boundary + "--\r\n"));
  }

  @Test void projectedAndReducedArrowUsesTheDeclaredQueryShape() throws Exception {
    var path = temp.resolve("projected.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int id = writer.start("/array", "double[]", "", 0);
      writer.append(id, 0, WpilogWriter.encodeDoubleArray(new double[]{100, 2}));
      writer.append(id, 2, WpilogWriter.encodeDoubleArray(new double[]{200, 6}));
    }
    start(2, new AtomicLong());
    var id = json(open(path)).get("id").getAsString();
    for (boolean reduced : List.of(false, true)) {
      var body = new JsonObject(); body.addProperty("entry", "/array"); body.addProperty("field", "[1]"); body.addProperty("format", "arrow");
      if (reduced) { var r = new JsonObject(); r.addProperty("max_points", 1); body.add("reduction", r); }
      var response = request("POST", "/read/v1/snapshots/" + id + "/query", body.toString());
      assertEquals(200, response.statusCode(), new String(response.body()));
      var arrow = ArrowSpecReader.read(response.body());
      var rows = arrow.batches().stream().flatMap(b -> b.columns().get(3).stream()).toList();
      assertEquals(reduced ? 1 : 2, rows.size());
      if (reduced) assertEquals(4., ((java.util.Map<?, ?>) rows.get(0)).get("mean"));
      else assertEquals(2., rows.get(0));
    }
  }

  @Test void arrowSchemaFailureNamesTheMissingSchemaRatherThanBlamingTheRequest() throws Exception {
    var path = temp.resolve("schema-less.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int id = writer.start("/struct", "struct:Unpublished", "", 0);
      writer.append(id, 1, new byte[8]);
    }
    start(1, new AtomicLong());
    var opened = open(path);
    var response = request("POST", "/read/v1/snapshots/" + json(opened).get("id").getAsString() + "/query",
        "{\"entry\":\"/struct\",\"format\":\"arrow\"}");
    assertEquals(400, response.statusCode());
    assertEquals("SCHEMA_UNAVAILABLE", json(response).get("code").getAsString());
    assertTrue(json(response).get("message").getAsString().contains("Unpublished"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void arrowFailureWithoutAMessageStillHasItsTerminalProof(boolean multi) throws Exception {
    var path = fixture(); local = LogAccess.local(List.of(temp));
    var access = new LogAccess() {
      public ReadSnapshot open(Path source) throws IOException {
        var snapshot = local.open(source);
        return (ReadSnapshot) java.lang.reflect.Proxy.newProxyInstance(ReadSnapshot.class.getClassLoader(),
            new Class<?>[] {ReadSnapshot.class}, (proxy, method, args) -> {
          try {
            if (method.getName().equals("readAll")) return org.triplehelix.wpilogmcp.read.MultiSampleCursor.open(
                (ReadSnapshot) proxy, (List<ReadQuery>) args[0]);
            var value = method.invoke(snapshot, args);
            if (!method.getName().equals("read")) return value;
            var cursor = (SampleCursor) value;
            return new SampleCursor() {
              int calls;
              public SampleBatch next() throws IOException {
                if (++calls == 2) throw new IOException();
                return cursor.next();
              }
              public void close() throws IOException { cursor.close(); }
            };
          } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
      }
      public void close() {}
    };
    endpoint = new ReadEndpoint(access);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(ReadEndpoint.PATH, endpoint::handle); server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    var opened = open(path);
    var response = request("POST", "/read/v1/snapshots/" + json(opened).get("id").getAsString() + "/query",
        multi ? "{\"format\":\"arrow\",\"queries\":[{\"entry\":\"/integer\",\"batch_records\":1},{\"entry\":\"/unknown\"}]}"
            : "{\"entry\":\"/integer\",\"format\":\"arrow\",\"batch_records\":1}");
    byte[] ipc = response.body();
    if (multi) {
      String boundary = response.headers().firstValue("Content-Type").orElseThrow().split("boundary=")[1];
      String text = new String(ipc, java.nio.charset.StandardCharsets.ISO_8859_1);
      assertFalse(text.endsWith("--" + boundary + "--\r\n"), "A failed query cannot complete the multipart operation");
      assertFalse(text.contains("X-Query-Index: 1"), "A failed part stops later queries");
      ipc = text.substring(text.indexOf("\r\n\r\n") + 4).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }
    var arrow = ArrowSpecReader.read(ipc);
    assertTrue(arrow.endMarker());
    var terminal = arrow.batches().get(arrow.batches().size() - 1).metadata();
    assertEquals("error", terminal.get("status"));
    assertTrue(terminal.get("message").contains("IOException"), terminal.toString());
  }

  @Test void catalogRefusalsPreserveTheTypedRetryDecision() throws Exception {
    var retryable = new java.util.concurrent.atomic.AtomicBoolean(true);
    start(2, new AtomicLong(), () -> { throw new ReadException(ReadProblem.Code.FILE_CHANGED, "catalog changed", retryable.get()); });
    for (boolean expected : List.of(true, false)) {
      retryable.set(expected); var response = request("GET", "/read/v1/catalog", null);
      assertEquals(409, response.statusCode());
      assertEquals("FILE_CHANGED", json(response).get("code").getAsString());
      assertEquals(new JsonPrimitive(expected), json(response).get("retryable"));
      // The Java client reports the same decision the server sent.
      try (var remote = new org.triplehelix.wpilogmcp.read.http.RemoteLogAccess(base)) {
        var failure = assertThrows(ReadException.class, remote::catalog);
        assertEquals(ReadProblem.Code.FILE_CHANGED, failure.code());
        assertEquals(expected, failure.retryable());
      }
    }
  }

  @Test void requestsRetainTheirCatalogOwnerUntilConfiguredRootsChange() throws Exception {
    var firstRoot = Files.createDirectory(temp.resolve("first"));
    var secondRoot = Files.createDirectory(temp.resolve("second"));
    var root = new java.util.concurrent.atomic.AtomicReference<Path>(firstRoot);
    try (var access = LogAccess.local(List.of(temp));
         var reads = new ReadEndpoint(access, () -> new org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog(List.of(root.get())))) {
      try (var first = reads.catalog(); var same = reads.catalog()) {
        assertSame(first.value(), same.value(), "Describe and query must reuse their catalog and REV decode owner");
        root.set(secondRoot);
        try (var next = reads.catalog()) {
          assertNotSame(first.value(), next.value());
          assertDoesNotThrow(first.value()::snapshot, "An admitted request outlives a root change");
        }
      }
    }
  }

  private void start(int limit, AtomicLong clock) throws Exception {
    start(limit, clock, () -> null);
  }
  private void start(int limit, AtomicLong clock, ReadEndpoint.Catalogs catalogs) throws Exception {
    local = LogAccess.local(List.of(temp));
    endpoint = new ReadEndpoint(local, catalogs, limit, 100, clock::get);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers = java.util.concurrent.Executors.newCachedThreadPool(r -> { var t = new Thread(r, "read-endpoint-test"); t.setDaemon(true); return t; });
    server.setExecutor(workers);
    server.createContext(ReadEndpoint.PATH, endpoint::handle);
    server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach void stop() throws Exception {
    if (endpoint != null) endpoint.close();
    if (server != null) server.stop(0);
    if (workers != null) workers.shutdownNow();
    if (local != null) local.close();
  }

  private HttpResponse<byte[]> request(String method, String path, String body) throws Exception {
    return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
        .header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .build(), HttpResponse.BodyHandlers.ofByteArray());
  }
  private static JsonObject json(HttpResponse<byte[]> response) {
    return JsonParser.parseString(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
  }
  private HttpResponse<byte[]> open(Path path) throws Exception {
    var body = new JsonObject(); body.addProperty("path", path.toString());
    return request("POST", "/read/v1/snapshots", body.toString());
  }

  @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"6000,64", "40,60000"})
  void defaultRemoteQueryCarriesWideArraysWithoutChangingItsAccounting(int count, int width) throws Exception {
    var path = temp.resolve("wide.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int id = writer.start("/wide", "double[]", "", 0);
      double[] values = new double[width];
      for (int i = 0; i < width; i++) values[i] = i * 0.25;
      for (int i = 0; i < count; i++) writer.append(id, i, WpilogWriter.encodeDoubleArray(values));
    }
    start(2, new AtomicLong());
    try (var remote = new RemoteLogAccess(base); var snapshot = remote.open(path);
         var cursor = snapshot.read(ReadQuery.all("/wide"))) {
      long records = 0, payload = 0; int seen = 0;
      while (true) {
        var batch = cursor.next(); records += batch.recordsVisited(); payload += batch.payloadBytes();
        assertTrue(batch.problems().isEmpty());
        for (var sample : batch.samples()) {
          assertEquals(seen++, sample.timestampUs());
          var value = (double[]) sample.value(); assertEquals(width, value.length);
          for (int i = 0; i < width; i++) assertEquals(i * 0.25, value[i]);
        }
        if (batch.complete()) break;
      }
      assertEquals(count, seen); assertEquals(count, records); assertEquals((long) count * width * 8, payload);
    }
  }

  @Test void descriptorAndStreamRetainExactIntegerCoordinatesAndMetadata() throws Exception {
    var path = fixture(); start(2, new AtomicLong());
    var opened = open(path); assertEquals(201, opened.statusCode());
    var descriptor = json(opened);
    assertEquals("1", descriptor.get("api_version").getAsString());
    assertEquals(path.toString(), descriptor.get("path").getAsString());
    assertFalse(descriptor.get("live").getAsBoolean());
    assertEquals("{\"source\":\"fixture\"}", descriptor.getAsJsonObject("entries")
        .getAsJsonObject("/integer").get("metadata").getAsString());
    var response = request("POST", "/read/v1/snapshots/" + descriptor.get("id").getAsString() + "/query",
        "{\"entry\":\"/integer\",\"format\":\"ndjson\",\"batch_records\":1}");
    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/x-ndjson"));
    var lines = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8).lines()
        .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    var sample = lines.get(0).getAsJsonArray("samples").get(0).getAsJsonObject();
    assertTrue(sample.get("timestamp_us").getAsJsonPrimitive().isString(), "Never round timestamps through JavaScript Number");
    assertEquals("9007199254740993", sample.get("timestamp_us").getAsString());
    assertEquals("9223372036854775807", sample.getAsJsonObject("value").get("value").getAsString());
    assertEquals("i64", sample.getAsJsonObject("value").get("type").getAsString());
    assertEquals("end", lines.get(lines.size() - 1).get("kind").getAsString());
    assertEquals("ok", lines.get(lines.size() - 1).get("status").getAsString());
  }

  @Test void arrowUsesExactIntegersAndTheSharedEncoder() throws Exception {
    var path = fixture(); start(2, new AtomicLong());
    var opened = open(path); assertEquals(201, opened.statusCode());
    var response = request("POST", "/read/v1/snapshots/" + json(opened).get("id").getAsString() + "/query",
        "{\"entry\":\"/integer\",\"format\":\"arrow\"}");
    assertEquals(200, response.statusCode());
    var arrow = ArrowSpecReader.read(response.body());
    assertTrue(arrow.endMarker());
    assertEquals(json(opened).get("id").getAsString(), arrow.metadata().get("snapshot_id"));
    assertFalse(arrow.metadata().containsKey("snapshot"), "A single-entry stream must not repeat every entry and schema");
    var batch = arrow.batches().stream().filter(b -> b.rows() > 0).findFirst().orElseThrow();
    assertEquals(List.of(9_007_199_254_740_993L, 9_007_199_254_740_994L), batch.columns().get(0));
    assertEquals(List.of(Long.MAX_VALUE, Long.MIN_VALUE), batch.columns().get(3));
    assertEquals("ok", arrow.batches().get(arrow.batches().size() - 1).metadata().get("status"));
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void arrowSchemaIsCompleteWhenTheFirstBatchHasNoStructElement(boolean filtered) throws Exception {
    var path = temp.resolve("enum-array.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int schema = writer.start("/.schema/struct:WithEnum", "structschema", "", 0);
      writer.append(schema, 0, "enum {ZERO=0, ONE=1} int32 state;Rotation2d angle;".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      int entry = writer.start("/values", "struct:WithEnum[]", "", 0);
      writer.append(entry, 1, new byte[0]);
      writer.append(entry, 2, java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1).putDouble(0.5).array());
    }
    start(2, new AtomicLong());
    var opened = open(path); assertEquals(201, opened.statusCode());
    var response = request("POST", "/read/v1/snapshots/" + json(opened).get("id").getAsString() + "/query",
        "{\"entry\":\"/values\",\"format\":\"arrow\",\"order\":\"file\",\"batch_records\":1"
            + (filtered ? ",\"windows\":[{\"start_us\":\"2\",\"end_us\":\"2\",\"end_inclusive\":true}]" : "") + "}");
    assertEquals(200, response.statusCode());
    var arrow = ArrowSpecReader.read(response.body());
    assertEquals("ok", arrow.batches().get(arrow.batches().size() - 1).metadata().get("status"));
    var rows = arrow.batches().stream().flatMap(b -> b.columns().get(3).stream()).map(v -> (List<?>) v)
        .filter(v -> !v.isEmpty()).findFirst().orElseThrow();
    var struct = (java.util.Map<?, ?>) rows.get(0);
    assertEquals(java.util.Map.of("value", 1L, "label", "ONE"), struct.get("state"));
    var angle = (java.util.Map<?, ?>) struct.get("angle");
    assertEquals(0.5 * 180 / Math.PI, ((Number) ((java.util.Map<?, ?>) angle.get("_derived")).get("degrees")).doubleValue(), 1e-12);
  }

  @Test void leaseCapExpiryExplicitCloseAndShutdownReleaseAdmission() throws Exception {
    var path = fixture(); var clock = new AtomicLong(); start(1, clock);
    var first = open(path); assertEquals(201, first.statusCode());
    String id = json(first).get("id").getAsString();
    assertEquals(429, open(path).statusCode());
    clock.set(101);
    assertEquals(410, request("GET", "/read/v1/snapshots/" + id, null).statusCode());
    var second = open(path); assertEquals(201, second.statusCode());
    assertEquals(204, request("DELETE", "/read/v1/snapshots/" + json(second).get("id").getAsString(), null).statusCode());
    assertEquals(201, open(path).statusCode());
    endpoint.close(); assertEquals(503, open(path).statusCode());
  }

  @Test void changedFileAndUnknownEntryAreExplainedAndOutsidePathsStayRefused() throws Exception {
    var path = fixture(); start(2, new AtomicLong());
    var opened = open(path); assertEquals(201, opened.statusCode());
    String query = "/read/v1/snapshots/" + json(opened).get("id").getAsString() + "/query";
    assertEquals(404, request("POST", query, "{\"entry\":\"/missing\"}").statusCode());
    Files.write(path, new byte[] {1}, java.nio.file.StandardOpenOption.APPEND);
    var changed = request("POST", query, "{\"entry\":\"/integer\"}");
    assertEquals(409, changed.statusCode()); assertEquals("FILE_CHANGED", json(changed).get("code").getAsString());
    var outside = Files.createTempFile("read-outside", ".wpilog");
    try { assertEquals(403, open(outside).statusCode()); }
    finally { Files.delete(outside); }
    assertEquals(404, request("GET", "/read/v1/not-a-route", null).statusCode());
  }

  @Test void aLooseDirectorySymlinkCannotBypassStoreManifestMembership() throws Exception {
    var source = fixture(); var store = Files.createDirectory(temp.resolve("store"));
    Files.writeString(store.resolve("store.json"), "{\"format_version\":1,\"created_at\":\"2026-01-01T00:00:00Z\",\"id\":\"store\",\"moves\":[]}");
    var stray = Files.copy(source, store.resolve("stray.wpilog"));
    var alias = temp.resolve("outside-store.wpilog");
    try { Files.createSymbolicLink(alias, stray); }
    catch (IOException | UnsupportedOperationException failure) { org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: " + failure.getMessage()); }
    start(2, new AtomicLong(), () -> new org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog(List.of(store)));
    assertEquals(403, open(stray).statusCode());
    assertEquals(403, open(alias).statusCode(), "Canonical store membership applies even when the caller spells an outside alias");
  }

  @Test void remoteAdapterReadsIndependentExactValuesAndPreservesSchemaSources() throws Exception {
    var path = fixture(); start(2, new AtomicLong());
    try (var remote = new RemoteLogAccess(base); var snapshot = remote.open(path)) {
      assertEquals("wpilib", snapshot.schemas().info("SwerveModuleState").orElseThrow().source().wire());
      assertEquals("assumed", snapshot.schemas().info("PoseObservation").orElseThrow().source().wire());
      assertEquals(Files.size(path), snapshot.boundary().size());
      try (var cursor = snapshot.read(ReadQuery.all("/integer"))) {
        var batch = cursor.next(); assertTrue(batch.complete());
        assertEquals(9_007_199_254_740_993L, batch.samples().get(0).timestampUs());
        assertEquals(Long.MIN_VALUE, batch.samples().get(1).value());
      }
      try (var cursor = snapshot.read(ReadQuery.all("/unknown"))) {
        assertArrayEquals(new byte[] {0, 1, (byte) 255}, (byte[]) cursor.next().samples().get(0).value());
      }
    }
  }

  @Test void anAdmittedCursorFinishesAfterRemoteSnapshotAndAccessClose() throws Exception {
    var path = fixture(); start(2, new AtomicLong());
    try (var remote = new RemoteLogAccess(base); var snapshot = remote.open(path);
         var cursor = snapshot.read(ReadQuery.all("/integer"))) {
      snapshot.close(); remote.close();
      assertThrows(IOException.class, () -> snapshot.read(ReadQuery.all("/integer")));
      assertThrows(IOException.class, () -> remote.open(path));
      var batch = cursor.next(); assertTrue(batch.complete());
      assertEquals(List.of(Long.MAX_VALUE, Long.MIN_VALUE), batch.samples().stream().map(s -> s.value()).toList());
    }
  }

  @Test void idleExpiryStartsAfterAnAdmittedStreamingCursorFinishes() throws Exception {
    var path = fixture(); var clock = new AtomicLong();
    var waiting = new CountDownLatch(1); var resume = new CountDownLatch(1);
    var queryFinished = new CountDownLatch(1);
    local = LogAccess.local(List.of(temp));
    var delayed = new LogAccess() {
      @Override public ReadSnapshot open(Path source) throws IOException {
        var snapshot = local.open(source);
        return (ReadSnapshot) java.lang.reflect.Proxy.newProxyInstance(ReadSnapshot.class.getClassLoader(),
            new Class<?>[] {ReadSnapshot.class}, (proxy, method, arguments) -> {
              try {
                Object value = method.invoke(snapshot, arguments);
                if (!method.getName().equals("read")) return value;
                var original = (SampleCursor) value;
                return new SampleCursor() {
                  int calls;
                  @Override public SampleBatch next() throws IOException {
                    if (++calls == 2) {
                      waiting.countDown();
                      try { if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("Test did not release the second batch"); }
                      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    }
                    return original.next();
                  }
                  @Override public void close() throws IOException { original.close(); }
                };
              } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
      }
      @Override public void close() {}
    };
    endpoint = new ReadEndpoint(delayed, 2, 100, clock::get);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers = java.util.concurrent.Executors.newCachedThreadPool(r -> { var t = new Thread(r, "read-expiry-test"); t.setDaemon(true); return t; });
    server.setExecutor(workers); server.createContext(ReadEndpoint.PATH, exchange -> {
      try { endpoint.handle(exchange); }
      finally { if (exchange.getRequestURI().getPath().endsWith("/query")) queryFinished.countDown(); }
    }); server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    try (var remote = new RemoteLogAccess(base); var snapshot = remote.open(path);
         var cursor = snapshot.read(new ReadQuery("/integer", List.of(), ReadQuery.Order.TIME, 1, 1024))) {
      assertFalse(cursor.next().complete());
      assertTrue(waiting.await(5, TimeUnit.SECONDS));
      clock.set(101);
      assertEquals(200, request("GET", "/read/v1/snapshots/" + snapshot.descriptor().id(), null).statusCode(), "An active stream is not idle");
      resume.countDown();
      var batch = cursor.next(); assertTrue(batch.complete());
      assertEquals(Long.MIN_VALUE, batch.samples().get(0).value());
      assertTrue(queryFinished.await(5, TimeUnit.SECONDS), "Idle time starts after the handler releases its lease");
      String route = "/read/v1/snapshots/" + snapshot.descriptor().id();
      clock.set(150); assertEquals(200, request("GET", route, null).statusCode());
      clock.set(251); assertEquals(410, request("GET", route, null).statusCode());
    } finally { resume.countDown(); }
  }

  @Test void catalogAndStableStoreReferenceCarryTheRecordedReceipt() throws Exception {
    var source = fixture();
    var root = Files.createDirectories(temp.resolve("catalog-store"));
    var robot = Files.createDirectories(root.resolve("robots/synthetic"));
    var session = Files.createDirectories(robot.resolve("sessions/2026-01-01/fixed"));
    var path = Files.copy(source, session.resolve("capture.wpilog"));
    String when = "2026-01-01T00:00:00Z";
    var provenance = new org.triplehelix.wpilogmcp.store.StoreManifest.Provenance("fixture", "/synthetic", "capture.wpilog", when, false);
    String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    var file = new org.triplehelix.wpilogmcp.store.StoreManifest.LogFile("capture.wpilog", hash,
        Files.size(path), "wpilog", provenance, true, 0, 1, when, when, "log", false, null);
    var json = org.triplehelix.wpilogmcp.store.StoreJson.JSON;
    Files.writeString(root.resolve("store.json"), json.toJson(new org.triplehelix.wpilogmcp.store.StoreManifest.Header(1, when, "fixed-store", List.of())));
    Files.writeString(robot.resolve("robot.json"), json.toJson(new org.triplehelix.wpilogmcp.store.StoreManifest.Robot("synthetic", "synthetic", "", "", "logged")));
    Files.writeString(session.resolve("session.json"), json.toJson(new org.triplehelix.wpilogmcp.store.StoreManifest.Session(
        "fixed-session", when, when, "log", null, null, null, null, List.of(file))));
    start(2, new AtomicLong(), () -> new org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog(List.of(root)));
    var response = request("GET", "/read/v1/catalog", null);
    assertEquals(200, response.statusCode());
    assertTrue(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8).contains(hash));
    // Pin literal wire fields, not reflection against the Java records that supply them.
    var catalog = json(response).getAsJsonObject("catalog");
    assertTrue(catalog.keySet().containsAll(List.of("stores", "problems", "shared_system_logs")));
    var listed = catalog.getAsJsonArray("stores").get(0).getAsJsonObject();
    assertTrue(listed.keySet().containsAll(List.of("root", "header", "robots", "files", "unmanaged", "moved", "open_captures", "sessions")));
    assertEquals("fixed-store", listed.getAsJsonObject("header").get("id").getAsString());
    assertTrue(listed.getAsJsonObject("header").keySet().containsAll(List.of("format_version", "created_at", "id", "moves")));
    var member = listed.getAsJsonArray("files").get(0).getAsJsonObject();
    assertTrue(member.keySet().containsAll(List.of("path", "manifest_path", "robot", "session", "file")));
    assertEquals("synthetic", member.getAsJsonObject("robot").get("serial_number").getAsString());
    assertEquals("fixed-session", member.getAsJsonObject("session").get("id").getAsString());
    try (var remote = new RemoteLogAccess(base);
         var snapshot = remote.open("fixed-store", "fixed-session", "capture.wpilog");
         var cursor = snapshot.read(ReadQuery.all("/integer"))) {
      assertEquals(path.toRealPath().toString(), snapshot.serverPath());
      var store = snapshot.descriptor().store();
      assertEquals("fixed-store", store.storeId());
      var receipt = store.facts().file();
      assertEquals(hash, receipt.sha256());
      assertEquals("/synthetic", receipt.provenance().originalPath());
      assertEquals(Long.MAX_VALUE, cursor.next().samples().get(0).value());
    }
    var union = request("POST", "/read/v1/snapshots",
        "{\"store_id\":\"fixed-store\",\"session_id\":\"fixed-session\",\"anchor_path\":\"capture.wpilog\",\"overlap\":\"keep_all\"}");
    assertEquals(201, union.statusCode(), new String(union.body()));
    assertFalse(json(union).get("live").getAsBoolean());
    assertEquals(1, json(union).getAsJsonArray("sources").size());
  }

  @Test void realTransportAppliesItsOriginGateToEveryNativeReadRoute() throws Exception {
    var transport = new HttpTransport(new ToolRegistry(), 0); transport.start();
      try {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + transport.getPort()
            + "/read/v1/catalog")).header("Origin", "https://untrusted.example").GET().build(),
            HttpResponse.BodyHandlers.discarding());
        assertEquals(403, response.statusCode());
      } finally { transport.stop(); }
  }
}
