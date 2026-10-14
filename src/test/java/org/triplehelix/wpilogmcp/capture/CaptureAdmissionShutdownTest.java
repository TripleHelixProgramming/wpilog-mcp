/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreCatalog;

class CaptureAdmissionShutdownTest {
  @TempDir Path directory;
  @Test void serviceShutdownRetiresPendingPlacementAndCanBeRepeated() throws Exception {
    var security = new org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator(); security.addAllowedDirectory(directory);
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(directory);
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var stderr = System.err; var messages = new java.io.ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(messages, true, java.nio.charset.StandardCharsets.UTF_8));
    CaptureService capture = null;
    try (var robot = new Nt4Gateway(new java.net.InetSocketAddress("127.0.0.1", 0), () -> 1_000_000, List.of(Nt4Client.V40))) {
      robot.start().get(5, TimeUnit.SECONDS);
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", robot.port(), "admission-shutdown")),
          directory.resolve("store"), .001, CapturePolicy.ALL, 0);
      capture = new CaptureService(config, manager, Clock.systemUTC(), ClientScheduler.daemon(), WpilogOutput::new, Duration.ofMillis(200));
      capture.start().get(5, TimeUnit.SECONDS);
      var store = manager.stores().store(config.store());
      var source = ImportFixture.write(directory.resolve("import.wpilog"), 3);
      var importing = store.importPaths(new LogStore.Request(List.of(source), false, null), progress -> {
        if (progress.phase().equals("inspecting")) {
          entered.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      robot.announce("/pending", "int", new JsonObject()).join();
      var service = capture;
      await(() -> !service.live().topics().isEmpty());
      robot.value("/pending", 1_000_001, 2, 9L).join(); await(() -> service.live().receivedValues() == 1);
      long start = System.nanoTime(); capture.close();
      assertTrue((System.nanoTime() - start) / 1e6 < 2000, "Shutdown must use its bounded wait");
      assertTrue(messages.toString(java.nio.charset.StandardCharsets.UTF_8).contains("Capture stopped during admission"));
      release.countDown(); importing.get(10, TimeUnit.SECONDS);
      await(() -> {
        try { return StoreCatalog.read(config.store(), security).sessions().stream()
            .anyMatch(s -> s.session().endReason() != null && s.session().endReason().contains("Capture stopped during admission")); }
        catch (java.io.IOException e) { return false; }
      });
      assertDoesNotThrow(capture::close);
      capture = null;
    } finally {
      release.countDown(); if (capture != null) { try { capture.close(); } catch (RuntimeException ignored) {} }
      System.setErr(stderr); manager.release(directory); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
    }
  }
  private static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), "Expected asynchronous service state did not appear");
  }
}
