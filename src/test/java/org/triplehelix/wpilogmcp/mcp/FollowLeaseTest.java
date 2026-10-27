/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.read.*;
import org.triplehelix.wpilogmcp.read.server.OrdinalFollow;

class FollowLeaseTest {
  @TempDir Path temp;
  final AtomicLong clock = new AtomicLong();
  final AtomicBoolean finalized = new AtomicBoolean(), changed = new AtomicBoolean();
  final AtomicReference<ReadException> openingFailure = new AtomicReference<>();
  final AtomicInteger opened = new AtomicInteger(), released = new AtomicInteger(), waiting = new AtomicInteger();
  final HttpClient client = HttpClient.newHttpClient();
  HttpServer server; StreamingRequests streams; ReadEndpoint reads; FollowEndpoint follows; LogAccess access; URI base;
  static final String CREATE = """
      {"source_ref":{"kind":"session","origin_store_id":"11111111-1111-4111-8111-111111111111",
       "session_id":"22222222-2222-4222-8222-222222222222","anchor_member":{"original_name":"capture.wpilog",
       "recording_started_at":"2026-01-01T00:00:00Z"}},"queries":[{"entry":"x","order":"file"}]}
      """;
  /** Holds a follow inside its per-page access check, then fails the check as a torn-down owner would. */
  record Gate(CountDownLatch entered, CountDownLatch release, java.io.IOException failure) {}
  volatile Gate gate;
  @BeforeEach void start() throws Exception {
    var path = temp.resolve("capture.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int id = writer.start("x", "int64", "", 0); writer.append(id, 1, WpilogWriter.encodeInt64(7));
    }
    access = LogAccess.local(ReadOptions.defaults(List.of(temp))); reads = new ReadEndpoint(access);
    follows = new FollowEndpoint(reads, reference -> new OrdinalFollow.Source() {
      public OrdinalFollow.Prefix open() throws java.io.IOException {
        var refusal = openingFailure.getAndSet(null); if (refusal != null) throw refusal;
        opened.incrementAndGet(); var delegate = access.open(path);
        var snapshot = (ReadSnapshot) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ReadSnapshot.class}, (proxy, method, args) -> {
          if (method.getName().equals("close")) released.incrementAndGet();
          try { return method.invoke(delegate, args); }
          catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
        return new OrdinalFollow.Prefix("recording", 1, finalized.get(), List.of(new OrdinalFollow.Member("member", 0, snapshot)));
      }
      @Override public void authorize() throws java.io.IOException {
        var held = gate; if (held == null) return;
        held.entered().countDown();
        try { held.release().await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        throw held.failure();
      }
      public boolean awaitChange(Duration wait, java.util.function.BooleanSupplier cancelled) throws java.io.IOException {
        if (changed.getAndSet(false)) return true;
        waiting.incrementAndGet(); long end = System.nanoTime() + wait.toNanos();
        try {
          while (!cancelled.getAsBoolean() && System.nanoTime() < end) {
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException(); }
          }
          return false;
        } finally { waiting.decrementAndGet(); }
      }
    }, 2, Duration.ofSeconds(60), clock::get);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); streams = new StreamingRequests(2, Duration.ofSeconds(5));
    var next = streams.handle(follows::handle);
    server.createContext(FollowEndpoint.PATH, exchange -> {
      if (exchange.getRequestURI().getPath().endsWith("/next")) next.handle(exchange); else follows.handle(exchange);
    });
    server.createContext("/health", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
    server.start(); base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }
  @AfterEach void stop() throws Exception {
    follows.close(); server.stop(0); streams.close(); reads.close(); access.close();
  }
  HttpRequest request(String method, String path, String body) {
    return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30)).method(method,
        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
  }
  HttpResponse<String> call(String method, String path, String body) throws Exception {
    return client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
  }
  JsonObject json(HttpResponse<String> response, int status) {
    assertEquals(status, response.statusCode(), response.body()); return JsonParser.parseString(response.body()).getAsJsonObject();
  }
  JsonObject create() throws Exception { return json(call("POST", FollowEndpoint.PATH, CREATE), 201); }
  String path(JsonObject lease) { return FollowEndpoint.PATH + "/" + lease.get("id").getAsString(); }
  String body(String cursor, int wait) { return "{\"cursor\":\"" + cursor + "\",\"wait_ms\":" + wait + "}"; }
  String bootstrap(JsonObject lease) throws Exception {
    return json(call("POST", path(lease) + "/next", body(lease.get("cursor").getAsString(), 0)), 200).get("next_cursor").getAsString();
  }
  static void until(java.util.function.BooleanSupplier ready) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
    assertTrue(ready.getAsBoolean(), "Lifecycle condition did not arrive before deadline");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"access", "unavailable", "catalog"})
  void aFollowTheStopCatchesInsideItsAccessCheckEndsClosed(String kind) throws Exception {
    // Each failure is one an owner torn down by the stop raises in its own words; Windows saw the first.
    java.io.IOException failure = switch (kind) {
      case "access" -> new java.nio.file.AccessDeniedException("Recording store is no longer configured");
      case "unavailable" -> new java.io.IOException("SOURCE_UNAVAILABLE: recording store is no longer configured");
      default -> new java.io.IOException("Source catalog is closed");
    };
    var lease = create(); String cursor = bootstrap(lease);
    var held = new Gate(new CountDownLatch(1), new CountDownLatch(1), failure); gate = held;
    var page = client.sendAsync(request("POST", path(lease) + "/next", body(cursor, 0)), HttpResponse.BodyHandlers.ofString());
    assertTrue(held.entered().await(5, TimeUnit.SECONDS));
    follows.close(); // The transport's stop closes follow admission first.
    held.release().countDown();
    var response = page.get(10, TimeUnit.SECONDS);
    assertEquals(410, response.statusCode(), response.body());
    assertEquals("CLOSED", JsonParser.parseString(response.body()).getAsJsonObject().get("code").getAsString());
  }

  @Test void withoutAStopAnAccessRefusalStillEndsTheFollowWithItsCode() throws Exception {
    var lease = create(); String cursor = bootstrap(lease);
    gate = new Gate(new CountDownLatch(1), new CountDownLatch(0), new java.nio.file.AccessDeniedException("Recording store is no longer configured"));
    var response = call("POST", path(lease) + "/next", body(cursor, 0));
    assertEquals(403, response.statusCode(), response.body());
  }

  @Test void idleExpiryClosesUnacknowledgedSnapshotsAndCachedBytesBeforeReusingCapacity() throws Exception {
    var a = create(); bootstrap(a); create();
    assertTrue(follows.heldBytes() > 0); assertEquals(429, call("POST", FollowEndpoint.PATH, CREATE).statusCode());
    clock.set(Duration.ofSeconds(60).toNanos()); follows.expire();
    assertEquals(0, follows.size()); assertEquals(0, follows.heldBytes()); assertEquals(2, released.get());
    assertEquals("CURSOR_EXPIRED", json(call("POST", path(a) + "/next", body(a.get("cursor").getAsString(), 0)), 410).get("code").getAsString());
    create(); assertEquals(3, opened.get());
  }

  @Test void aTerminalAcknowledgementReleasesDataButKeepsItsResultUntilExpiry() throws Exception {
    finalized.set(true); var lease = create(); String cursor = bootstrap(lease);
    assertTrue(follows.heldBytes() > 0); assertEquals(0, released.get());
    for (int i = 0; i < 2; i++) assertEquals("FOLLOW_ENDED",
        json(call("POST", path(lease) + "/next", body(cursor, 25000)), 410).get("code").getAsString());
    assertEquals(0, follows.heldBytes()); assertEquals(1, released.get()); assertEquals(1, opened.get());
    assertEquals(0, waiting.get()); assertEquals(1, follows.size());
    clock.set(Duration.ofSeconds(60).toNanos()); follows.expire(); assertEquals(0, follows.size());
    assertEquals("CURSOR_EXPIRED", json(call("POST", path(lease) + "/next", body(cursor, 0)), 410).get("code").getAsString());
  }
  @Test void saturatedWaitsLeaveControlCapacityAndIdleTimeoutCannotExpireAnActiveRequest() throws Exception {
    var a = create(); var b = create(); String ca = bootstrap(a), cb = bootstrap(b);
    var first = client.sendAsync(request("POST", path(a) + "/next", body(ca, 25000)), HttpResponse.BodyHandlers.ofString());
    var second = client.sendAsync(request("POST", path(b) + "/next", body(cb, 25000)), HttpResponse.BodyHandlers.ofString());
    until(() -> waiting.get() == 2);
    assertEquals(2, released.get(), "Neither idle follow holds a native mapping");
    clock.set(Duration.ofHours(1).toNanos()); follows.expire(); assertEquals(2, follows.size());
    assertEquals(503, call("POST", path(a) + "/next", body(ca, 0)).statusCode());
    assertEquals(204, call("GET", "/health", null).statusCode());
    assertEquals(204, call("DELETE", path(a), null).statusCode());
    assertEquals(410, first.get(5, TimeUnit.SECONDS).statusCode());
    assertFalse(second.isDone()); assertEquals(1, follows.size());
    follows.close(); assertEquals(410, second.get(5, TimeUnit.SECONDS).statusCode());
    assertEquals(0, follows.heldBytes()); assertEquals(2, opened.get());
  }
  @Test void oneLeaseCannotHaveTwoConcurrentNextCallsAndForeignCursorsNeverRebootstrap() throws Exception {
    var lease = create(); String cursor = bootstrap(lease);
    var pending = client.sendAsync(request("POST", path(lease) + "/next", body(cursor, 25000)), HttpResponse.BodyHandlers.ofString());
    until(() -> waiting.get() == 1);
    assertEquals("BUSY", json(call("POST", path(lease) + "/next", body(cursor, 0)), 503).get("code").getAsString());
    call("DELETE", path(lease), null); pending.get(5, TimeUnit.SECONDS);
    var other = create();
    assertEquals("INVALID_REQUEST", json(call("POST", path(other) + "/next", body(cursor, 0)), 400).get("code").getAsString());
    assertEquals(2, opened.get());
  }

  @Test void sharedBytePressureIsRetryableAndTheSameCursorResumesAfterBytesAreReleased() throws Exception {
    var field = FollowEndpoint.class.getDeclaredField("budget"); field.setAccessible(true);
    field.set(follows, new OrdinalFollow.Budget(128 << 10));
    String create = CREATE.replace("\"queries\"", "\"max_page_bytes\":65536,\"queries\"");
    var a = json(call("POST", FollowEndpoint.PATH, create), 201);
    var b = json(call("POST", FollowEndpoint.PATH, create), 201);
    bootstrap(a); String cursor = b.get("cursor").getAsString();
    var failure = json(call("POST", path(b) + "/next", body(cursor, 0)), 413);
    assertEquals("RESOURCE_LIMIT", failure.get("code").getAsString());
    assertEquals(new JsonPrimitive(true), failure.get("retryable"));
    assertEquals(204, call("DELETE", path(a), null).statusCode());
    var resumed = json(call("POST", path(b) + "/next", body(cursor, 0)), 200);
    assertEquals(0, resumed.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch")
        .getAsJsonArray("samples").get(0).getAsJsonObject().get("ordinal").getAsInt());
  }

  @Test void aStructuralPageLimitIsNotRetryable() throws Exception {
    var failure = json(call("POST", FollowEndpoint.PATH,
        CREATE.replace("\"queries\"", "\"max_page_bytes\":1024,\"queries\"")), 413);
    assertEquals(new JsonPrimitive(false), failure.get("retryable"));
  }
  @Test void transientCatalogRefusalOnCreateHasATypedRetryAndDoesNotConsumeALease() throws Exception {
    openingFailure.set(new ReadException(ReadProblem.Code.FILE_CHANGED, "catalog changed", true));
    var failure = json(call("POST", FollowEndpoint.PATH, CREATE), 409);
    assertEquals("FILE_CHANGED", failure.get("code").getAsString()); assertEquals(new JsonPrimitive(true), failure.get("retryable"));
    assertEquals(0, follows.size()); create(); assertEquals(1, follows.size());
  }
  @Test void transientCatalogRefusalKeepsTheAcknowledgedCursorAndNeverRebootstraps() throws Exception {
    var lease = create(); String cursor = bootstrap(lease);
    openingFailure.set(new ReadException(ReadProblem.Code.FILE_CHANGED, "catalog changed", true)); changed.set(true);
    var failure = json(call("POST", path(lease) + "/next", body(cursor, 0)), 409);
    assertEquals("FILE_CHANGED", failure.get("code").getAsString()); assertEquals(new JsonPrimitive(true), failure.get("retryable"));
    assertEquals(1, follows.size()); changed.set(true);
    var response = call("POST", path(lease) + "/next", body(cursor, 0)); var delta = json(response, 200);
    assertEquals(lease.get("id"), delta.get("follow_id")); assertFalse(delta.get("bootstrap").getAsBoolean());
    assertTrue(delta.getAsJsonArray("batches").isEmpty(), "The first ordinal was already acknowledged");
    assertEquals(response.body(), call("POST", path(lease) + "/next", body(cursor, 0)).body());
    assertEquals(2, opened.get());
  }
  @Test void permanentHistoryChangesStayNonretryable() throws Exception {
    var lease = create(); String cursor = bootstrap(lease);
    openingFailure.set(new ReadException(ReadProblem.Code.FILE_CHANGED, "writer identity changed")); changed.set(true);
    var failure = json(call("POST", path(lease) + "/next", body(cursor, 0)), 409);
    assertEquals("HISTORY_UNAVAILABLE", failure.get("code").getAsString()); assertEquals(new JsonPrimitive(false), failure.get("retryable"));
  }

}
