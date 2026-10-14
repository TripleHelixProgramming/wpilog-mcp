/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;

class ReplayCaptureTest {
  @TempDir Path directory;
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void replayReportsTheDisconnectInsteadOfWaitingForValuesOrPullPlacement(boolean manual) throws Exception {
    var loop = manual ? new ManualScheduler() : org.triplehelix.wpilogmcp.nt4.client.ClientScheduler.daemon();
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 1_000_000L, java.util.List.of(Nt4Client.V40))) {
      gateway.start().get(10, TimeUnit.SECONDS); gateway.announce("/x", "int", new com.google.gson.JsonObject()).get();
      try (var capture = new ReplayCapture(RobotAddress.uri("127.0.0.1", gateway.port(), "disconnect"),
          directory.resolve("unused.wpilog"), directory.resolve("capture"), Clock.systemUTC(), null, 1L << 20, loop)) {
        capture.ready(1); gateway.dropClients().get(10, TimeUnit.SECONDS);
        if (loop instanceof ManualScheduler scheduler) scheduler.until(() -> !capture.connected());
        else org.triplehelix.wpilogmcp.harness.HarnessHttp.await("disconnect", 5, () -> !capture.connected());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
          var failure = assertThrows(AssertionError.class, () -> capture.receivedThrough(1));
          assertTrue(failure.getMessage().contains("Replay capture disconnected"), failure::getMessage);
          assertTrue(failure.getMessage().contains("NT4 peer closed (1001)"), failure::getMessage);
        });
        if (loop instanceof ManualScheduler scheduler) {
          scheduler.advance(1_000_000); scheduler.until(capture::connected);
          // A reconnect while the replayer was occupied must not conceal the interruption.
          var failure = assertThrows(AssertionError.class, () -> capture.receivedThrough(0));
          assertTrue(failure.getMessage().contains("NT4 peer closed (1001)"), failure::getMessage);
        }
      }
    }
  }

  @Test void reconnectBeforeReplayWaitsForTheNewSubscriptionsAnnouncements() throws Exception {
    var loop = new ManualScheduler();
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 1_000_000L, java.util.List.of(Nt4Client.V40))) {
      gateway.start().get(10, TimeUnit.SECONDS);
      gateway.announce("/one", "int", new com.google.gson.JsonObject()).get();
      gateway.announce("/two", "int", new com.google.gson.JsonObject()).get();
      try (var capture = new ReplayCapture(RobotAddress.uri("127.0.0.1", gateway.port(), "readiness"),
          directory.resolve("unused.wpilog"), directory.resolve("capture"), Clock.systemUTC(), null, 1L << 20, loop)) {
        capture.ready(2); assertEquals(2, capture.announcements.get());
        gateway.dropClients().get(10, TimeUnit.SECONDS);
        // Peer delivery is complete; only the already-enqueued client action remains.
        loop.drain(); assertFalse(capture.connected());
        assertEquals(0, capture.announcements.get(), "A disconnected generation cannot satisfy a new subscription");
        loop.advance(1_000_000);
        capture.ready(2); assertEquals(2, capture.announcements.get()); assertEquals(0, capture.received.get());
      }
    }
  }
  @Test void pullReplayKeepsTheListenerResponsiveAndWaitsForAsyncAdmission() throws Exception {
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 1_000_000L, java.util.List.of(Nt4Client.V40))) {
      gateway.start().get(10, TimeUnit.SECONDS);
      var device = new org.triplehelix.wpilogmcp.capture.context.DeviceIdentity("ASYNC-REPLAY", "fixture", "127.0.0.1", "SHA256:fixture", java.util.Map.of());
      try (var capture = new ReplayCapture(RobotAddress.uri("127.0.0.1", gateway.port(), "async-admission"),
          directory.resolve("unused.wpilog"), directory.resolve("capture"), Clock.systemUTC(), device)) {
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
          var importing = capture.store.importPaths(new org.triplehelix.wpilogmcp.store.LogStore.Request(java.util.List.of(), false, null), progress -> {
            if (progress.phase().equals("starting")) { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); }
              catch (InterruptedException e) { throw new AssertionError(e); } }
          });
          assertTrue(entered.await(5, TimeUnit.SECONDS)); timer.schedule(release::countDown, 1, TimeUnit.SECONDS);
          long begin = System.nanoTime(); gateway.announce("/x", "int", new com.google.gson.JsonObject()).get();
          org.triplehelix.wpilogmcp.harness.HarnessHttp.await("ordered announcement callback", 5, () -> capture.announcements.get() == 1);
          double waitMs = (System.nanoTime() - begin) / 1e6;
          assertTrue(waitMs < 250, "Replay observer must forward async admission, loop waited " + waitMs + " ms");
          var ready = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { capture.ready(1); } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
          });
          assertThrows(java.util.concurrent.TimeoutException.class, () -> ready.get(50, TimeUnit.MILLISECONDS), "Replay readiness must await the recording, not just its topic table");
          release.countDown(); importing.get(5, TimeUnit.SECONDS); ready.get(5, TimeUnit.SECONDS);
          assertTrue(capture.writer.ready()); assertEquals(1, capture.files.size());
        } finally { release.countDown(); timer.shutdownNow(); }
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void stoppedPullReplayRetiresItsPendingManifestBeforeReturning(boolean manual) throws Exception {
    var loop = manual ? new ManualScheduler() : org.triplehelix.wpilogmcp.nt4.client.ClientScheduler.daemon();
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 1_000_000L, java.util.List.of(Nt4Client.V40))) {
      gateway.start().get(10, TimeUnit.SECONDS);
      var device = new org.triplehelix.wpilogmcp.capture.context.DeviceIdentity("CANCELLED-REPLAY", "fixture", "127.0.0.1", "SHA256:fixture", java.util.Map.of());
      try (var capture = new ReplayCapture(RobotAddress.uri("127.0.0.1", gateway.port(), "cancel-admission"),
          directory.resolve("unused.wpilog"), directory.resolve("capture"), Clock.systemUTC(), device, 1L << 20, loop)) {
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
          var importing = capture.store.importPaths(new org.triplehelix.wpilogmcp.store.LogStore.Request(java.util.List.of(), false, null), progress -> {
            if (progress.phase().equals("starting")) { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); }
              catch (InterruptedException e) { throw new AssertionError(e); } }
          });
          assertTrue(entered.await(5, TimeUnit.SECONDS)); timer.schedule(release::countDown, 1, TimeUnit.SECONDS);
          gateway.announce("/x", "int", new com.google.gson.JsonObject()).get();
          if (loop instanceof ManualScheduler scheduler) scheduler.until(() -> capture.announcements.get() == 1);
          else org.triplehelix.wpilogmcp.harness.HarnessHttp.await("ordered announcement callback", 5, () -> capture.announcements.get() == 1);
          capture.stop(); importing.get(5, TimeUnit.SECONDS);
          var catalog = org.triplehelix.wpilogmcp.store.StoreCatalog.readManaged(capture.directory, capture.manager.testGetSecurityValidator());
          assertEquals(1, catalog.sessions().size()); assertTrue(catalog.files().isEmpty()); assertTrue(catalog.openCaptures().isEmpty());
          assertTrue(String.valueOf(catalog.sessions().get(0).session().endReason()).contains("Capture stopped during admission"));
        } finally { release.countDown(); timer.shutdownNow(); }
      }
    }
  }

}
