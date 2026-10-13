/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;

class HttpAdmissionTest {
  @Test void aPoolOfPeersThatNeverReadCannotStarveHealth(@TempDir Path directory) throws Exception {
    var path = directory.resolve("unread.wpilog");
    try (var writer = new WpilogWriter(path, "synthetic stalled reader")) {
      int id = writer.start("/Wide", "double[]", "", 0);
      byte[] payload = new byte[8192];
      for (int i = 0; i < 4096; i++) writer.append(id, i * 1000L, payload);
    }
    var manager = LogManager.getInstance();
    var directories = manager.getConfiguredDirectories();
    manager.addAllowedDirectory(directory);
    var transport = new HttpTransport(new ToolRegistry(), 0);
    var sockets = new ArrayList<Socket>();
    transport.start();
    try {
      var body = new com.google.gson.JsonObject(); body.addProperty("path", path.toString());
      var opened = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
          "http://127.0.0.1:" + transport.getPort() + "/read/v1/snapshots"))
          .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(201, opened.statusCode(), opened.body());
      String route = "/read/v1/snapshots/" + com.google.gson.JsonParser.parseString(opened.body())
          .getAsJsonObject().get("id").getAsString() + "/query";
      String query = "{\"entry\":\"/Wide\",\"format\":\"arrow\",\"batch_records\":4,\"batch_bytes\":65536}";
      for (int i = 0; i < 32; i++) {
        var socket = new Socket(); sockets.add(socket);
        socket.setReceiveBufferSize(1024);
        socket.connect(new java.net.InetSocketAddress("127.0.0.1", transport.getPort()));
        socket.setSoTimeout(10000);
        socket.getOutputStream().write(("POST " + route + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: "
            + query.length() + "\r\n\r\n" + query).getBytes(StandardCharsets.US_ASCII));
        // Headers prove the exchange is admitted; stop reading before the large response.
        var status = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream())).readLine();
        assertTrue(status.contains("200") || status.contains("503"), status);
      }
      var health = assertDoesNotThrow(() -> HttpClient.newHttpClient().send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + transport.getPort() + "/health")).timeout(Duration.ofSeconds(3)).build(),
          HttpResponse.BodyHandlers.ofString()), "32 stalled streams must not exhaust health's dispatch workers");
      assertEquals(200, health.statusCode());
    } finally {
      for (var socket : sockets) socket.close();
      transport.stop(); manager.unloadLog(path.toString());
      manager.clearAllowedDirectories(); directories.forEach(manager::addAllowedDirectory);
    }
  }

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

  @Test void fourStalledDataStreamsLeaveHealthAnswering(@TempDir Path directory) throws Exception {
    var path = directory.resolve("stream.wpilog");
    try (var writer = new WpilogWriter(path, "synthetic dispatch fixture")) {
      int entry = writer.start("/Value", "double", "", 0);
      writer.append(entry, 1_000_000, WpilogWriter.encodeDouble(1));
    }
    var manager = LogManager.getInstance();
    var configuredDirectories = manager.getConfiguredDirectories();
    manager.addAllowedDirectory(directory);
    var transport = new HttpTransport(new ToolRegistry(), 0);
    var writing = new CountDownLatch(4);
    var finish = new CountDownLatch(1);
    var streams = new ArrayList<CompletableFuture<HttpResponse<byte[]>>>();
    transport.start();
    try {
      // Use the real endpoint on the transport's executor, gating its output rather than
      // depending on kernel socket buffers or a large fixture to keep four handlers busy.
      var field = HttpTransport.class.getDeclaredField("server");
      field.setAccessible(true);
      var server = (HttpServer) field.get(transport);
      var context = server.createContext("/stalled-data", new DataEndpoint(manager)::handle);
      context.getFilters().add(new Filter() {
        @Override public String description() { return "Gate data writes for the admission test"; }
        @Override public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
          exchange.setStreams(null, new FilterOutputStream(exchange.getResponseBody()) {
            private boolean started;
            private void awaitRelease() throws IOException {
              if (started) return;
              started = true;
              writing.countDown();
              try {
                if (!finish.await(30, TimeUnit.SECONDS)) throw new IOException("Data write guard expired");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Data write interrupted", e);
              }
            }
            @Override public void write(int value) throws IOException {
              awaitRelease(); out.write(value);
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
              awaitRelease(); out.write(bytes, offset, length);
            }
          });
          chain.doFilter(exchange);
        }
      });
      var client = HttpClient.newHttpClient();
      var base = "http://127.0.0.1:" + transport.getPort();
      var data = URI.create(base + "/stalled-data?path="
          + URLEncoder.encode(path.toString(), StandardCharsets.UTF_8) + "&names=/Value");
      for (int i = 0; i < 4; i++) {
        streams.add(client.sendAsync(HttpRequest.newBuilder(data).build(),
            HttpResponse.BodyHandlers.ofByteArray()));
      }
      assertTrue(writing.await(30, TimeUnit.SECONDS), "All four data streams entered their writes");
      assertTrue(streams.stream().noneMatch(CompletableFuture::isDone), "Data streams are still stalled");
      var health = assertDoesNotThrow(() -> client.send(HttpRequest.newBuilder(
          URI.create(base + "/health")).timeout(Duration.ofSeconds(5)).build(),
          HttpResponse.BodyHandlers.ofString()), "Health must answer while four data streams are stalled");
      assertEquals(200, health.statusCode(), health.body());
      assertEquals(1, finish.getCount(), "Health did not need the streams released");
      finish.countDown();
      for (var stream : streams) {
        var response = stream.get(30, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().length > 0);
      }
    } finally {
      finish.countDown();
      transport.stop();
      manager.unloadLog(path.toString());
      manager.clearAllowedDirectories();
      configuredDirectories.forEach(manager::addAllowedDirectory);
    }
  }
}
