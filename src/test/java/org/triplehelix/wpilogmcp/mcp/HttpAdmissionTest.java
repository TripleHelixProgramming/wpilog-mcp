/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.net.Socket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HttpAdmissionTest {
  @Test void anUnfinishedBodyHasADeadlineAndReleasesItsAdmissionSlot() throws Exception {
    var transport = new HttpTransport(new ToolRegistry(), 0);
    transport.requestLimits(1024, 1, Duration.ofMillis(200)); transport.start();
    try (var socket = new Socket("127.0.0.1", transport.getPort())) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 100\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
      assertEquals(-1, socket.getInputStream().read(), "The deadline closes an unfinished body");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (transport.readingBodies() != 0 && System.nanoTime() < deadline) Thread.onSpinWait();
      assertEquals(0, transport.readingBodies());
    } finally { transport.stop(); }
  }
  static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
  @Test void oversizedBodiesAreRefusedBeforeJsonParsing() throws Exception {
    var transport = new HttpTransport(new ToolRegistry(), 0);
    transport.requestLimits(128, 2, Duration.ofSeconds(10)); transport.start();
    try {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + transport.getPort() + "/mcp"))
          .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE + " ".repeat(256))).build();
      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(413, response.statusCode(), response.body());
      assertTrue(response.body().contains("128"));
    } finally { transport.stop(); }
  }

  @Test void stalledBodiesCannotQueueWithoutBoundOrStarveHealth() throws Exception {
    var transport = new HttpTransport(new ToolRegistry(), 0);
    transport.requestLimits(1024, 1, Duration.ofSeconds(30)); transport.start();
    try (var socket = new Socket("127.0.0.1", transport.getPort())) {
      socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 100\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (transport.readingBodies() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
      assertEquals(1, transport.readingBodies(), "First request entered its blocked body read");
      var client = HttpClient.newHttpClient();
      var base = "http://127.0.0.1:" + transport.getPort();
      var health = client.send(HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, health.statusCode());
      var overload = client.send(HttpRequest.newBuilder(URI.create(base + "/mcp")).timeout(Duration.ofSeconds(5))
          .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE)).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(503, overload.statusCode(), "A request beyond admission must not join an unbounded queue");
    } finally { transport.stop(); }
  }
}
