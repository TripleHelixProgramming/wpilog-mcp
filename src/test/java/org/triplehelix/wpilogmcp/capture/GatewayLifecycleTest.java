/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.PullCoordinator;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.nt4.client.*;
import org.triplehelix.wpilogmcp.nt4.server.GatewayStatus;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.tools.LiveTools;

/** Exercise the service's bind choice and lifecycle, not just the socket adapter in isolation. */
class GatewayLifecycleTest {
  @TempDir Path temp;
  static JsonObject health(String host, int port) throws Exception {
    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/health"))
        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode()); return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  @Test void captureAndPullContinueWhileWaitingAndPublishedStateChangesWhenThePortIsReleased() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(temp);
    var clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), java.time.ZoneOffset.UTC);
    var client = new ManualScheduler(); var binds = new ManualScheduler(); var worker = new ManualScheduler();
    var contacts = new AtomicInteger(); var stderr = System.err; var messages = new java.io.ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(messages, true, java.nio.charset.StandardCharsets.UTF_8));
    try (var held = new ServerSocket(); var robot = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0),
        () -> 10_000_000 + client.nowUs(), List.of(Nt4Client.V40))) {
      held.bind(new InetSocketAddress("127.0.0.1", 0)); robot.start().get(5, TimeUnit.SECONDS);
      robot.announce("/signal", "int", new JsonObject()).join();
      robot.announce("/FMSInfo/FMSControlData", "int", new JsonObject()).join();
      var pull = new PullConfig(true, PullConfig.DISABLED.directories(), 0, 1_000_000, PullConfig.DISABLED.ssh());
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", robot.port(), "waiting")), temp.resolve("store"),
          .001, CapturePolicy.ALL, 0, 1 << 20, pull, held.getLocalPort());
      var capture = new CaptureService(config, manager, clock, client, WpilogOutput::new, Duration.ofSeconds(30),
          (settings, gate, store, wall, identity) -> new PullCoordinator(settings, gate, store, wall, identity, worker,
              (host, options, pin) -> { contacts.incrementAndGet(); throw new java.io.IOException("synthetic absent SSH"); }),
          "127.0.0.1", binds);
      var tools = new ToolRegistry(); LiveTools.registerAll(tools, capture.live());
      var http = new HttpTransport(tools, 0); http.setGatewayStatus(capture.live()::gateway); http.start();
      try {
        capture.start().get(5, TimeUnit.SECONDS); binds.drain();
        client.until(() -> capture.live().topics().size() == 2);
        robot.value("/signal", 10_000_001, 2, 7L).join();
        robot.value("/FMSInfo/FMSControlData", 10_000_002, 2, 0L).join();
        client.until(() -> capture.live().receivedValues() == 2); client.advance(250_000);
        client.until(() -> capture.live().current().statistics().records() == 2);
        worker.drain(); assertEquals(1, contacts.get(), "The puller must start even when the view cannot bind");
        var waiting = health("127.0.0.1", http.getPort()).getAsJsonObject("gateway");
        assertEquals("waiting", waiting.get("state").getAsString());
        assertEquals(config.gatewayPort(), waiting.get("port").getAsInt());
        assertTrue(waiting.get("cause").getAsString().contains("BindException"));
        assertEquals(clock.instant().toString(), waiting.get("since").getAsString());
        var mcp = new HarnessHttp(http.getPort()); mcp.initialize();
        var sessions = mcp.call("list_sessions", new JsonObject());
        assertEquals(waiting, sessions.getAsJsonObject("gateway"));
        assertEquals(2, sessions.getAsJsonArray("sessions").get(0).getAsJsonObject().get("records").getAsLong());
        held.close();
        binds.advance(999_999); assertEquals(waiting, capture.live().gateway().json());
        binds.advance(1); binds.until(() -> capture.live().gateway().state() == GatewayStatus.State.LISTENING);
        var listening = health("127.0.0.1", http.getPort()).getAsJsonObject("gateway");
        assertEquals("listening", listening.get("state").getAsString());
        assertEquals(config.gatewayPort(), listening.get("port").getAsInt()); assertTrue(listening.get("cause").isJsonNull());
        assertEquals(listening, mcp.call("list_sessions", new JsonObject()).get("gateway"));
        var value = new CompletableFuture<Long>();
        try (var downstream = new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", config.gatewayPort(), "recovered")), .001,
            new Nt4Client.Listener() {
              @Override public void value(org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce a,
                  org.triplehelix.wpilogmcp.nt4.ValueFrame v, long received) {
                if (a.name().equals("/signal")) { assertEquals(10_000_001, v.timestampUs()); value.complete((Long) v.value()); }
              }
            })) {
          downstream.start(); assertEquals(7L, value.get(5, TimeUnit.SECONDS), "Topics received while waiting survive the bind retry");
        }
      } finally {
        var closed = CompletableFuture.runAsync(capture::close);
        closed.whenComplete((ignored, error) -> client.execute(() -> {}));
        client.until(closed::isDone, Duration.ofSeconds(5)); closed.get(5, TimeUnit.SECONDS); http.stop();
      }
      String log = messages.toString(java.nio.charset.StandardCharsets.UTF_8);
      assertFalse(log.contains("shutdown could not finish"), log);
    } finally { System.setErr(stderr); manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }

  @Test void serviceUsesTheHttpBindAddressAndDoesNotAlsoListenOnLoopback() throws Exception {
    var local = java.net.NetworkInterface.networkInterfaces().flatMap(java.net.NetworkInterface::inetAddresses)
        .filter(a -> a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()).findFirst();
    org.junit.jupiter.api.Assumptions.assumeTrue(local.isPresent(), "No nonloopback interface");
    String host = local.orElseThrow().getHostAddress(); int port;
    try (var reserve = new ServerSocket()) { reserve.bind(new InetSocketAddress(host, 0)); port = reserve.getLocalPort(); }
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", 9, "absent")), temp.resolve("store"), .01,
        CapturePolicy.ALL, 0, 1 << 20, PullConfig.DISABLED, port);
    var http = new HttpTransport(new ToolRegistry(), 0, host, null, null);
    try (var capture = new CaptureService(config, manager, host)) {
      http.setGatewayStatus(capture.live()::gateway); http.start(); capture.start().get(5, TimeUnit.SECONDS);
      HarnessHttp.await("gateway listening", 5, () -> capture.live().gateway().state() == GatewayStatus.State.LISTENING);
      assertEquals(port, health(host, http.getPort()).getAsJsonObject("gateway").get("port").getAsInt());
      var socket = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols(Nt4Client.V40)
          .buildAsync(RobotAddress.uri(host, port, "nonloopback"), new java.net.http.WebSocket.Listener() {}).get(5, TimeUnit.SECONDS);
      try (var refused = new java.net.Socket()) {
        assertThrows(java.io.IOException.class, () -> refused.connect(new InetSocketAddress("127.0.0.1", port), 1000));
      } finally { socket.abort(); }
    } finally { http.stop(); manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }

  @Test void failedGatewayCloseReportsItsCauseWithoutClaimingADeadlineExpired() throws Exception {
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    var client = new ManualScheduler();
    var binds = new ClientScheduler() {
      public long nowUs() { return 0; }
      public void execute(Runnable action) { fail("The service was never started"); }
      public void schedule(Runnable action, long delayUs) { fail("The service was never started"); }
      public void close() { throw new IllegalStateException("synthetic bind-scheduler close failure"); }
    };
    var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", 9, "absent")), temp.resolve("store"), .01,
        CapturePolicy.ALL, 0, 1 << 20, PullConfig.DISABLED, 5810);
    var messages = new java.io.ByteArrayOutputStream(); var stderr = System.err;
    try (var output = new java.io.PrintStream(messages, true, java.nio.charset.StandardCharsets.UTF_8)) {
      System.setErr(output);
      var service = new CaptureService(config, manager, Clock.systemUTC(), client, WpilogOutput::new,
          Duration.ofSeconds(30), PullCoordinator::new, "127.0.0.1", binds);
      var closed = CompletableFuture.runAsync(service::close);
      closed.whenComplete((ignored, error) -> client.execute(() -> {}));
      client.until(closed::isDone, Duration.ofSeconds(5)); closed.get(5, TimeUnit.SECONDS);
    } finally { System.setErr(stderr); manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
    String log = messages.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(log.contains("WARN") && log.contains("Capture shutdown failed"), log);
    assertTrue(log.contains("synthetic bind-scheduler close failure"), log);
    assertFalse(log.contains("shutdown could not finish"), log);
  }
}
