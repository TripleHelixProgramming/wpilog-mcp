/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StreamingRequestsTest {
  @Test void aBusyReadIsAJsonRefusalAndDoesNotTakeTheStoreWorkers() throws Exception {
    var transport = new HttpTransport(new ToolRegistry(), 0); transport.start();
    ReplyCapacity work = null; int taken = 0;
    try {
      var field = HttpTransport.class.getDeclaredField("streaming"); field.setAccessible(true);
      var streams = (StreamingRequests) field.get(transport);
      var workField = StreamingRequests.class.getDeclaredField("work"); workField.setAccessible(true);
      work = (ReplyCapacity) workField.get(streams);
      // Sixteen transfers in progress hold the read pool's sixteen slots.
      while (work.tryAcquire()) taken++;
      assertEquals(16, taken);
      var client = java.net.http.HttpClient.newHttpClient();
      var base = "http://127.0.0.1:" + transport.getPort();
      var busy = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/read/v1/catalog"))
          .timeout(Duration.ofSeconds(5)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(503, busy.statusCode());
      assertTrue(busy.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), busy.body());
      assertEquals("BUSY", com.google.gson.JsonParser.parseString(busy.body()).getAsJsonObject().get("code").getAsString());
      var store = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/store"))
          .timeout(Duration.ofSeconds(5)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, store.statusCode(), "Reading telemetry must not consume store transfer admission: " + store.body());
    } finally { for (int i = 0; i < taken; i++) work.release(); transport.stop(); }
  }

  static HttpServer serve(com.sun.net.httpserver.HttpHandler handler) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", handler); server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); server.start();
    return server;
  }
  /** A fresh client, so each request has its own connection. */
  static java.net.http.HttpResponse<String> get(HttpServer server) throws Exception {
    return java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/")).timeout(Duration.ofSeconds(10)).build(),
        java.net.http.HttpResponse.BodyHandlers.ofString());
  }
  static void hold(java.util.concurrent.CountDownLatch release) {
    try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }

  /** The first request's worker stays after its reply has reached the client, as a busy machine can. With one slot, the
   * next request used to be refused as busy until that thread was back in the pool. */
  @ParameterizedTest @ValueSource(strings = {"fixed", "chunked", "empty"})
  void aRequestAtTheLimitIsAdmittedOnceThePreviousReplysLastByteIsWritten(String kind) throws Exception {
    var replied = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
    var first = new java.util.concurrent.atomic.AtomicBoolean(true);
    try (var requests = new StreamingRequests(1, Duration.ofSeconds(10))) {
      var server = serve(requests.handle(exchange -> {
        boolean held = first.getAndSet(false); byte[] body = "reply".getBytes(StandardCharsets.UTF_8);
        switch (kind) {
          case "fixed" -> { exchange.sendResponseHeaders(200, body.length); var out = exchange.getResponseBody(); out.write(body); out.flush(); }
          case "chunked" -> { exchange.sendResponseHeaders(200, 0); var out = exchange.getResponseBody(); out.write(body); out.close(); }
          default -> exchange.sendResponseHeaders(204, -1);
        }
        if (held) { replied.countDown(); hold(release); }
      }));
      try {
        int expected = kind.equals("empty") ? 204 : 200;
        assertEquals(expected, get(server).statusCode());
        assertTrue(replied.await(5, TimeUnit.SECONDS), "the first worker stays after its reply");
        var next = get(server);
        assertEquals(expected, next.statusCode(), "the next request is admitted: " + next.body());
      } finally { release.countDown(); server.stop(0); }
    }
  }

  /** Two refusal threads stay after their 503s have reached the clients. A third refused request used to find no refusal
   * thread and have its connection closed with no reply at all. */
  @Test void aRefusalAtTheLimitStillAnswersWhileEarlierRefusalsAreFinishing() throws Exception {
    var occupied = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
    var finishing = new java.util.concurrent.CountDownLatch(2); var refused = new java.util.concurrent.atomic.AtomicInteger();
    try (var requests = new StreamingRequests(1, Duration.ofSeconds(10))) {
      com.sun.net.httpserver.HttpHandler busy = exchange -> {
        byte[] body = "{\"code\":\"BUSY\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(503, body.length); var out = exchange.getResponseBody(); out.write(body); out.flush();
        if (refused.getAndIncrement() < 2) { finishing.countDown(); hold(release); }
      };
      var server = serve(requests.handle(exchange -> { occupied.countDown(); hold(release); exchange.sendResponseHeaders(204, -1); },
          exchange -> requests.refuse(exchange, busy)));
      try {
        var running = java.util.concurrent.CompletableFuture.runAsync(() -> { try { get(server); } catch (Exception ignored) { } });
        assertTrue(occupied.await(5, TimeUnit.SECONDS), "one request holds the only slot");
        for (int i = 0; i < 2; i++) assertEquals(503, get(server).statusCode());
        assertTrue(finishing.await(5, TimeUnit.SECONDS), "both refusal threads stay after their replies");
        assertEquals(503, get(server).statusCode(), "the third refusal is answered, not dropped");
        release.countDown(); running.get(10, TimeUnit.SECONDS);
      } finally { release.countDown(); server.stop(0); }
    }
  }

  @Test void aRequestWaitsForASlotOnlyWhileAReplyIsFinishing() throws Exception {
    var capacity = new ReplyCapacity(1); assertTrue(capacity.tryAcquire());
    assertFalse(capacity.tryAcquire(), "with no reply finishing, a full limit refuses");
    assertEquals(0, capacity.waited(), "and refuses at once, without holding the thread that asked");
    capacity.finishing();
    var waiter = java.util.concurrent.CompletableFuture.supplyAsync(capacity::tryAcquire);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!capacity.waiting() && System.nanoTime() < deadline) Thread.onSpinWait();
    assertTrue(capacity.waiting(), "while a reply is finishing, a request waits for its slot");
    capacity.release(); capacity.finished();
    assertTrue(waiter.get(5, TimeUnit.SECONDS), "and takes the slot the finishing reply frees");
    assertEquals(1, capacity.waited());
  }

  @Test void aReplyThatFinishesBetweenAdmissionsTwoLooksLeavesItsSlotToTheRequest() {
    var capacity = new ReplyCapacity(1); assertTrue(capacity.tryAcquire());
    capacity.finishing();
    // The reply frees its slot after the request found none and before it looked for a finishing reply.
    capacity.betweenLooksProbe(() -> { capacity.release(); capacity.finished(); });
    assertTrue(capacity.tryAcquire(), "the slot that reply freed is taken, not refused");
    assertEquals(0, capacity.waited(), "at once: no reply is finishing any more");
  }

  @Test void finalRejectionsCannotDrainOnDispatchWorkersAfterBothPoolsFill() throws Exception {
    var transport = new HttpTransport(new ToolRegistry(), 0); transport.streamTimeout(Duration.ofSeconds(2)); transport.start();
    var sockets = new java.util.ArrayList<Socket>();
    try {
      var client = java.net.http.HttpClient.newHttpClient(); String base = "http://127.0.0.1:" + transport.getPort();
      var initialized = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/mcp"))
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"id\":1,\"method\":\"initialize\"}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      // On the old path: four inventory workers, sixteen fallback workers, then all 32 dispatch workers.
      for (int i = 0; i < 52; i++) {
        var socket = new Socket("127.0.0.1", transport.getPort()); sockets.add(socket);
        socket.getOutputStream().write(("POST /visualizations/v1/source/entries/list HTTP/1.1\r\nHost: localhost\r\nMcp-Session-Id: " + session
            + "\r\nContent-Length: 1024\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
      }
      Thread.sleep(3000); // Deliberately exceed every configured streaming I/O deadline.
      var health = assertDoesNotThrow(() -> client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/health"))
          .timeout(Duration.ofSeconds(2)).build(), java.net.http.HttpResponse.BodyHandlers.ofString()));
      assertEquals(200, health.statusCode());
      assertEquals(0, transport.activeStreams());
      assertTrue(sockets.stream().noneMatch(Socket::isClosed), "No client disconnected to rescue dispatch workers");
    } finally { for (var socket : sockets) socket.close(); transport.stop(); }
  }

  @Test void aWriteWithNoProgressReleasesItsWorkerWithoutThePeerDisconnecting() throws Exception {
    try (var streams = new StreamingRequests(1, Duration.ofMillis(200)); var socket = new Socket()) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/large", streams.handle(exchange -> {
        exchange.sendResponseHeaders(200, 0);
        byte[] block = new byte[65536];
        for (int i = 0; i < 2048; i++) exchange.getResponseBody().write(block);
        exchange.close();
      }));
      server.start();
      try {
        socket.setReceiveBufferSize(1024);
        socket.connect(server.getAddress()); socket.setSoTimeout(5000);
        socket.getOutputStream().write("GET /large HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals('H', socket.getInputStream().read());
        // Receipt of the status proves admission. Under load the watchdog may already have
        // fired before this thread resumes; only eventual release is the proposition here.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (streams.activeCount() != 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(0, streams.activeCount(), "The watchdog must interrupt the blocked write and release the worker");
        assertFalse(socket.isClosed(), "The client did not rescue the blocked handler by disconnecting");
      } finally { server.stop(0); }
    }
  }
}
