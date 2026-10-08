/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureService;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.*;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;

class CaptureGatewayTest {
  @TempDir Path temp;
  static int port() throws Exception {
    try (var socket = new java.net.ServerSocket()) {
      socket.bind(new InetSocketAddress("127.0.0.1", 0)); return socket.getLocalPort();
    }
  }
  private static Object next(LinkedBlockingQueue<Object> events) throws Exception {
    var value = events.poll(10, TimeUnit.SECONDS); assertNotNull(value, "Expected ordered gateway event"); return value;
  }
  private static long monotonicUs() { return System.nanoTime() / 1000; }

  @Test void busyGatewayPortDoesNotPreventCaptureOrDelayShutdown() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(temp);
    var stderr = System.err; var messages = new java.io.ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(messages, true, java.nio.charset.StandardCharsets.UTF_8));
    try (var held = new java.net.ServerSocket();
        var robot = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000)) {
      held.bind(new InetSocketAddress("127.0.0.1", 0)); robot.start().get(10, TimeUnit.SECONDS);
      robot.announce("/signal", "int", new JsonObject()).join();
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", robot.port(), "busy-port")), temp.resolve("store"),
          .001, CapturePolicy.ALL, 0, 1 << 20, PullConfig.DISABLED, held.getLocalPort());
      var capture = new CaptureService(config, manager);
      try {
        capture.start().get(5, TimeUnit.SECONDS);
        HarnessHttp.await("capture despite busy gateway", 5, () -> capture.live().topics().containsKey("/signal"));
        robot.value("/signal", 10_000_001, 2, 7L).join();
        HarnessHttp.await("captured value", 5, () -> capture.live().current().statistics().records() == 1);
        assertEquals(7L, capture.live().latest().get("/signal").value());
      } finally { assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), capture::close); }
      assertFalse(messages.toString(java.nio.charset.StandardCharsets.UTF_8).contains("shutdown could not finish"));
    } finally { System.setErr(stderr); manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }

  @Test void orderedCaptureFeedMirrorsTypesPropertiesValuesClockAndSessionBoundariesWithoutAcceptingWrites() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(temp);
    var offset = new AtomicLong(10_000_000 - monotonicUs());
    var events = new LinkedBlockingQueue<Object>();
    try (var robot = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> monotonicUs() + offset.get())) {
      robot.start().get(10, TimeUnit.SECONDS);
      robot.announce("/retired", "int", new JsonObject()).join(); robot.unannounce("/retired").join();
      var properties = new JsonObject(); properties.addProperty("unit", "synthetic");
      robot.announce("/signal", "int", properties).join();
      int gatewayPort = port();
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", robot.port(), "capture")), temp.resolve("store"),
          0.001, new CapturePolicy(List.of("/excluded"), java.util.Map.of()), 600_000_000, 1 << 20, PullConfig.DISABLED, gatewayPort);
      Path firstFile;
      try (var capture = new CaptureService(config, manager, Clock.systemUTC(), ClientScheduler.daemon())) {
        capture.start().get(10, TimeUnit.SECONDS);
        HarnessHttp.await("capture topic", 10, () -> capture.live().topics().containsKey("/signal"));
        var listener = new Nt4Client.Listener() {
          @Override public void announce(Announce a) { events.add(a); }
          @Override public void unannounce(Unannounce u) { events.add(u); }
          @Override public void properties(Properties p) { events.add(p); }
          @Override public void value(Announce a, ValueFrame v, long received) { events.add(v); }
        };
        try (var downstream = new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", gatewayPort, "downstream")), 0.001, listener)) {
          downstream.start(); var announcement = (Announce) next(events);
          assertEquals("/signal", announcement.name()); assertEquals("int", announcement.type());
          assertEquals(properties, announcement.properties());
          var upstream = capture.live().topics().get("/signal");
          assertTrue(capture.live().connected(), () -> "Capture disconnected: " + capture.live().disconnectReason());
          assertNotNull(upstream, () -> "Capture topic disappeared: " + capture.live().disconnectReason());
          assertNotEquals(upstream.id(), announcement.id(), "Gateway ids belong to its own session");
          for (long n = 1; n <= 3; n++) {
            robot.value("/signal", 11_000_000 + n, 2, n * 7).join();
            var value = (ValueFrame) next(events);
            assertEquals(11_000_000 + n, value.timestampUs()); assertEquals(n * 7, value.value());
          }
          var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
          var job = manager.stores().store(config.store()).importPaths(
              new org.triplehelix.wpilogmcp.store.LogStore.Request(List.of(), false, null), progress -> {
                if (progress.phase().equals("starting")) {
                  entered.countDown();
                  try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                  catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                }
              });
          try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            robot.value("/signal", 11_000_004, 2, 28L).join();
            assertEquals(28L, ((ValueFrame) next(events)).value());
            HarnessHttp.await("flush independent of store queue", 2, () -> capture.live().current().statistics().records() == 4);
            assertFalse(job.isDone());
          } finally { release.countDown(); job.get(10, TimeUnit.SECONDS); }
          var update = new JsonObject(); update.addProperty("unit", "changed"); robot.properties("/signal", update).join();
          assertEquals(update, ((Properties) next(events)).update());
          // Recorder exclusion is not a gateway subscription policy.
          robot.announce("/excluded", "double", new JsonObject()).join();
          assertEquals("/excluded", ((Announce) next(events)).name());
          robot.value("/excluded", 11_000_004, 1, 2.5).join(); assertEquals(2.5, ((ValueFrame) next(events)).value());

          try (var writing = new GatewaySocketTest.WireClient(gatewayPort, Nt4Client.V40)) {
            writing.send(new Publish("/injected", 71, "int", new JsonObject()));
            assertEquals(71, ((Announce) writing.next()).pubuid());
            writing.send(new ValueFrame(71, 12_000_000, 2, 999L));
            writing.send(new SetProperties("/signal", properties));
            var ack = (Properties) writing.next(); assertTrue(ack.ack()); assertEquals(update, ack.update());
            long sent = monotonicUs(); writing.send(new ValueFrame(-1, 0, 2, sent));
            var reply = (ValueFrame) writing.next(); long received = monotonicUs();
            assertEquals(sent, reply.value());
            double error = Math.abs(reply.timestampUs() - (sent + (received - sent) / 2.0 + offset.get()));
            double uncertainty = (received - sent + capture.live().timeEstimate().orElseThrow().roundTripUs()) / 2.0 + 1;
            assertTrue(error <= uncertainty, "Gateway clock error must fit the two measured round trips");
            assertEquals(2, capture.gatewayClients());
          }
          assertFalse(capture.live().topics().containsKey("/injected"));
          firstFile = capture.live().sessions().get(0).directory().resolve("capture.wpilog");
          // A reset produces unannounces before a new clock and new declarations.
          robot.dropClients().join();
          assertEquals("/signal", ((Unannounce) next(events)).name());
          assertEquals("/excluded", ((Unannounce) next(events)).name());
          offset.set(100_000 - monotonicUs());
          robot.endSession().join();
          robot.announce("/signal", "int", update).join();
          robot.announce("/excluded", "double", new JsonObject()).join();
          robot.value("/signal", 100_000, 2, 28L).join();
          assertEquals("/signal", ((Announce) next(events)).name());
          var resumed = (ValueFrame) next(events); assertEquals(100_000, resumed.timestampUs()); assertEquals(28L, resumed.value());
          assertEquals("/excluded", ((Announce) next(events)).name());
          HarnessHttp.await("new capture session", 10, () -> capture.live().sessions().size() == 2);
        }
      }
      try (var log = manager.acquire(firstFile.toString())) {
        assertFalse(log.log().entries().containsKey("NT:/injected"));
        assertFalse(log.log().entries().containsKey("NT:/excluded"));
        assertEquals(4, log.log().sampleCount("NT:/signal"));
      }
    } finally { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }

  @Test void withoutRobotSyncTheReplyUsesLocalMonotonicTimeAndReportsNoRobotConnection() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(temp);
    var clock = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler(); clock.advance(37_000_000);
    var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", 9, "absent")), temp.resolve("store"),
        0.01, CapturePolicy.ALL, 0, 1 << 20, PullConfig.DISABLED, port());
    var capture = new CaptureService(config, manager, Clock.systemUTC(), clock);
    try {
      capture.start().get(10, TimeUnit.SECONDS);
      try (var client = new GatewaySocketTest.WireClient(config.gatewayPort(), Nt4Client.V40)) {
        client.send(new ValueFrame(-1, 0, 2, 17L));
        assertEquals(new ValueFrame(-1, 37_000_000, 2, 17L), client.next());
        assertEquals(1, capture.gatewayClients()); assertFalse(capture.live().connected());
      }
    } finally {
      var closed = java.util.concurrent.CompletableFuture.runAsync(capture::close);
      closed.whenComplete((ignored, failure) -> clock.execute(() -> {}));
      clock.until(closed::isDone); closed.get(10, TimeUnit.SECONDS);
      manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
    }
  }

  @Test void firstRobotTimeForcesANewDownstreamTimeEstimate() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(temp);
    var clock = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
    try (var robot = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 12_000_000, List.of(Nt4Client.V40))) {
      robot.start().get(5, TimeUnit.SECONDS);
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", robot.port(), "upstream")), temp.resolve("store"),
          0.01, CapturePolicy.ALL, 0, 1 << 20, PullConfig.DISABLED, port());
      var capture = new CaptureService(config, manager, Clock.systemUTC(), clock);
      try {
        capture.start().get(10, TimeUnit.SECONDS);
        try (var before = new GatewaySocketTest.WireClient(config.gatewayPort(), Nt4Client.V40)) {
          before.send(new ValueFrame(-1, 0, 2, 1L)); assertEquals(0, ((ValueFrame) before.next()).timestampUs());
          clock.until(() -> capture.live().timeEstimate().isPresent());
          // A hard disconnect is also a valid reset; a peer must not retain its first offset.
          try { before.closed.get(5, TimeUnit.SECONDS); }
          catch (java.util.concurrent.ExecutionException expected) { assertInstanceOf(java.io.IOException.class, expected.getCause()); }
        }
        try (var after = new GatewaySocketTest.WireClient(config.gatewayPort(), Nt4Client.V40)) {
          after.send(new ValueFrame(-1, 0, 2, 2L)); assertEquals(12_000_000, ((ValueFrame) after.next()).timestampUs());
        }
      } finally {
        var closed = java.util.concurrent.CompletableFuture.runAsync(capture::close);
        closed.whenComplete((ignored, failure) -> clock.execute(() -> {}));
        clock.until(closed::isDone); closed.get(10, TimeUnit.SECONDS);
      }
    } finally { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
}
