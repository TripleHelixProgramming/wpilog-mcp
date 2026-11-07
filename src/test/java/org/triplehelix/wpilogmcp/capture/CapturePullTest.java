/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.*;
import org.triplehelix.wpilogmcp.config.*;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.client.*;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;

class CapturePullTest {
  @TempDir Path temp;
  private java.util.Set<Path> allowed;
  @org.junit.jupiter.api.BeforeEach void allow() { allowed = LogManager.getInstance().getAllowedDirectories(); }
  @org.junit.jupiter.api.AfterEach void restore() { var manager = LogManager.getInstance(); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  @Test void actualCaptureListenerGatesTheWorkerAndWritesDeviceIdentityOnItsOwnLoop() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); manager.addAllowedDirectory(temp);
    var loop = new ManualScheduler(); var worker = new ManualScheduler(); var observedGate = new AtomicReference<PullGate>();
    var calls = new AtomicInteger(); var controls = new AtomicInteger(); var contexts = new ArrayList<String>();
    var robot = new FakeRobot(); var input = temp.resolve("robot.wpilog");
    try (var fixture = new WpilogWriter(input, "synthetic pull wiring")) {
      fixture.append(fixture.start("/raw", "raw", "", 0), 10_000_000, new byte[150_000]);
    }
    robot.files.put("/u/logs/fixture.wpilog", Files.readAllBytes(input));
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000 + loop.nowUs(), List.of(Nt4Client.V40))) {
      gateway.start().get(10, TimeUnit.SECONDS); gateway.announce("/FMSInfo/FMSControlData", "int", new JsonObject()).join();
      var pull = new PullConfig(true, PullConfig.DISABLED.directories(), 5_000_000, 1_000_000, PullConfig.DISABLED.ssh());
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", gateway.port(), "capture-pull")), temp.resolve("store"), 0.001, CapturePolicy.ALL, 0, 1 << 20, pull);
      var service = new CaptureService(config, manager, Clock.systemUTC(), loop, (path, next, resume) -> new WpilogOutput(path, next, resume) {
        final java.util.Map<Integer, String> names = new HashMap<>();
        @Override public int start(String name, String type, String metadata, long time) throws IOException {
          int id = super.start(name, type, metadata, time); names.put(id, name); return id;
        }
        @Override public Written append(int id, long time, byte[] payload) throws IOException {
          var written = super.append(id, time, payload);
          if ("NT:/FMSInfo/FMSControlData".equals(names.get(id))) controls.incrementAndGet();
          if ("/Daemon/Robot/Identity".equals(names.get(id))) contexts.add(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
          return written;
        }
      }, Duration.ofSeconds(10), (settings, gate, store, wall, identity) -> {
        observedGate.set(gate);
        return new PullCoordinator(settings, gate, store, wall, identity, worker, (host, options, pin) -> { calls.incrementAndGet(); return robot; });
      });
      try {
        service.start().get(10, TimeUnit.SECONDS); worker.drain(); assertEquals(0, calls.get());
        loop.until(() -> observedGate.get().address() != null);
        gateway.value("/FMSInfo/FMSControlData", 10_000_000, 2, 0L).join(); loop.until(() -> controls.get() == 1);
        loop.advance(4_999_999); worker.advance(4_999_999); assertEquals(0, calls.get());
        loop.advance(1); worker.advance(250_000); assertEquals(1, calls.get()); assertEquals(1, robot.reads.size());
        assertTrue(contexts.isEmpty(), "The pull worker must enqueue the context, not write on its thread");
        loop.drain(); assertEquals(1, contexts.size());
        assertEquals(robot.device.json(), com.google.gson.JsonParser.parseString(contexts.get(0)));
        gateway.value("/FMSInfo/FMSControlData", 15_000_001, 2, 1L).join(); loop.until(() -> controls.get() == 2);
        worker.advance(1_000_000); assertEquals(1, robot.reads.size());
        gateway.value("/FMSInfo/FMSControlData", 15_000_002, 2, 0L).join(); loop.until(() -> controls.get() == 3);
        loop.advance(5_000_000); worker.advance(5_000_000); assertEquals(2, robot.reads.size());
        assertEquals(65536, robot.reads.get(1).offset());
        gateway.unannounce("/FMSInfo/FMSControlData").join(); loop.until(() -> !observedGate.get().open());
        worker.advance(1_000_000); assertEquals(2, robot.reads.size());
      } finally {
        var stopped = CompletableFuture.runAsync(service::close);
        loop.until(() -> observedGate.get().address() == null); stopped.get(15, TimeUnit.SECONDS);
      }
    } finally { manager.release(temp); }
  }

  @Test void disabledPullNeverConstructsATransportWorker() throws Exception {
    var manager = LogManager.getInstance(); manager.addAllowedDirectory(temp);
    var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", 9, "disabled")), temp.resolve("store"), 0.01, CapturePolicy.ALL, 0);
    try (var service = new CaptureService(config, manager, Clock.systemUTC(), ClientScheduler.daemon(), WpilogOutput::new,
        Duration.ofSeconds(5), (settings, gate, store, wall, identity) -> { throw new AssertionError("pull is disabled"); })) {
      assertFalse(config.pull().enabled());
    } finally { manager.release(temp); }
  }
}
