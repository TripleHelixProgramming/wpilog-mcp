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

class StreamingRequestsTest {
  @Test void aBusyReadIsAJsonRefusalAndDoesNotTakeTheStoreWorkers() throws Exception {
    var transport = new HttpTransport(new ToolRegistry(), 0); transport.start();
    var entered = new java.util.concurrent.CountDownLatch(16);
    var release = new java.util.concurrent.CountDownLatch(1);
    try {
      var field = HttpTransport.class.getDeclaredField("streaming"); field.setAccessible(true);
      var streams = (StreamingRequests) field.get(transport);
      var workerField = StreamingRequests.class.getDeclaredField("workers"); workerField.setAccessible(true);
      var workers = (java.util.concurrent.ExecutorService) workerField.get(streams);
      for (int i = 0; i < 16; i++) workers.submit(() -> {
        entered.countDown();
        try { release.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
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
    } finally { release.countDown(); transport.stop(); }
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
