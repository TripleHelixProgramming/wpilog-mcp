/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PhotonVisionProviderTest {
  record Snapshot(long timestamp, List<PhotonSettings.Camera> cameras, com.google.gson.JsonObject metadata) {}
  @Test void selectiveBurstsCoalesceUntilTheNextOneSecondRefreshBoundary() throws Exception {
    try (var backend = new PhotonFixture()) {
      var loop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
      var snapshots = new java.util.ArrayList<Snapshot>();
      try (var provider = new PhotonVisionProvider(backend.address(), () -> 7_000_000.0,
          (session, timestamp, cameras, metadata) -> { snapshots.add(new Snapshot(timestamp, cameras, metadata)); return CompletableFuture.completedFuture(null); },
          () -> {}, java.net.http.HttpClient.newHttpClient(), loop)) {
        provider.session(new Object(), true); until(loop, () -> snapshots.size() == 1);
        for (int n = 1; n <= 3; n++) {
          if (n > 1) loop.advance(50_000);
          backend.change(20 + n); long received = n;
          until(loop, () -> refreshField(provider, "changes") == received);
          assertEquals(1, refreshField(provider, "revision"), "Notifications inside one second cannot start a refresh");
        }
        loop.advance(899_999); assertEquals(1, refreshField(provider, "revision"));
        loop.advance(1); until(loop, () -> snapshots.size() == 2);
        assertEquals(2, backend.connections.get());
        assertEquals(23, snapshots.get(1).cameras().get(0).settings().getAsJsonObject("pipeline").get("exposure_raw").getAsInt());
        assertEquals(24, snapshots.get(1).cameras().get(1).settings().getAsJsonObject("pipeline").get("exposure_raw").getAsInt());
        backend.change(24); until(loop, () -> refreshField(provider, "changes") == 4);
        var gate = new java.util.concurrent.CountDownLatch(1); backend.fullSnapshotGate = gate;
        try {
          loop.advance(1_000_000); until(loop, () -> backend.connections.get() == 3);
          backend.change(25); until(loop, () -> refreshField(provider, "changes") == 5);
        } finally { gate.countDown(); }
        until(loop, () -> snapshots.size() == 3);
        loop.advance(999_999); assertEquals(3, refreshField(provider, "revision"));
        loop.advance(1); until(loop, () -> snapshots.size() == 4);
        assertEquals(4, backend.connections.get(), "An in-flight notification requires one refresh afterwards");
        assertEquals(25, snapshots.get(3).cameras().get(0).settings().getAsJsonObject("pipeline").get("exposure_raw").getAsInt());
      }
    }
  }
  /** The message counter is the delivery barrier; advancing time alone cannot prove receipt. */
  private static long refreshField(PhotonVisionProvider provider, String name) {
    try {
      var field = PhotonVisionProvider.class.getDeclaredField("attempt"); field.setAccessible(true);
      var attempt = field.get(provider); var value = attempt.getClass().getDeclaredField(name); value.setAccessible(true);
      return value.getLong(attempt);
    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
  }
  @Test void snapshotsUseTheRobotReceiptClockAndSelectiveChangesRefreshWithoutGuessingACamera() throws Exception {
    try (var backend = new PhotonFixture()) {
      var loop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
      var clock = new AtomicLong(7_000_000); var snapshots = new LinkedBlockingQueue<Snapshot>();
      try (var provider = new PhotonVisionProvider(backend.address(), () -> (double) clock.get(), (session, timestamp, cameras, metadata) -> {
        snapshots.add(new Snapshot(timestamp, cameras, metadata)); return CompletableFuture.completedFuture(null);
      }, () -> {}, java.net.http.HttpClient.newHttpClient(), loop)) {
        var session = new Object(); provider.session(session, true);
        until(loop, () -> snapshots.size() == 1);
        var first = snapshots.poll(30, TimeUnit.SECONDS); assertNotNull(first);
        assertEquals(7_000_000, first.timestamp()); assertEquals(1, backend.exports.get());
        assertEquals(List.of("front", "rear"), first.cameras().stream().map(PhotonSettings.Camera::name).toList());
        var settings = first.cameras().get(0).settings();
        assertEquals(10, settings.getAsJsonObject("pipeline").get("exposure_raw").getAsInt());
        assertEquals("AprilTag", settings.getAsJsonObject("pipeline").get("type").getAsString());
        assertEquals(List.of(.25, .5), settings.getAsJsonArray("calibrations").get(0).getAsJsonObject()
            .getAsJsonArray("mean_reprojection_errors_px").asList().stream().map(com.google.gson.JsonElement::getAsDouble).toList());
        assertEquals("v2026.3.4", first.metadata().get("photonvision_release").getAsString());
        backend.holdRefreshUntilOldSocketCloses = true;
        clock.set(9_000_000); backend.change(24);
        until(loop, () -> refreshField(provider, "changes") == 1); loop.advance(1_000_000);
        until(loop, () -> snapshots.size() == 1);
        var second = snapshots.poll(30, TimeUnit.SECONDS); assertNotNull(second, "Selective settings notification must not be ignored");
        assertEquals(9_000_000, second.timestamp());
        assertEquals(24, second.cameras().get(0).settings().getAsJsonObject("pipeline").get("exposure_raw").getAsInt());
        assertEquals(25, second.cameras().get(1).settings().getAsJsonObject("pipeline").get("exposure_raw").getAsInt());
        assertEquals(2, backend.connections.get()); assertEquals(1, backend.exports.get());
        provider.session(session, true); assertEquals("following", provider.status().state());
      }
    }
  }
  @Test void missingFieldsAndVanishedBackendsStandDownForTheSessionKeepingTheLastSnapshot() throws Exception {
    try (var backend = new PhotonFixture()) {
      var loop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
      var statuses = new LinkedBlockingQueue<Boolean>(); var snapshots = new LinkedBlockingQueue<Snapshot>();
      var delivery = new java.util.concurrent.atomic.AtomicReference<>(CompletableFuture.<Void>completedFuture(null));
      try (var provider = new PhotonVisionProvider(backend.address(), () -> 3_000_000.0, (session, timestamp, cameras, metadata) -> {
        snapshots.add(new Snapshot(timestamp, cameras, metadata)); statuses.add(true); return delivery.get();
      }, () -> statuses.add(true), java.net.http.HttpClient.newHttpClient(), loop)) {
        var session = new Object(); provider.session(session, true);
        until(loop, () -> !snapshots.isEmpty() && backend.pings.get() == 1); assertNotNull(snapshots.poll());
        var malformed = PhotonFixture.document(12);
        malformed.getAsJsonArray("cameraSettings").get(0).getAsJsonObject().getAsJsonObject("currentPipelineSettings").remove("cameraGain");
        statuses.clear();
        // The sink can notify after its snapshot wakes the caller and the caller clears events.
        // Keep that stale notice pending, with malformed-message processing still on the loop.
        statuses.add(true); backend.push(malformed);
        until(loop, () -> provider.status().state().equals("stand_down"));
        assertTrue(provider.status().reason().contains("cameraGain")); assertTrue(snapshots.isEmpty());
        provider.session(session, true); assertEquals(1, backend.connections.get(), "No retry storm in the failed session");
        int previousPings = backend.pings.get();
        provider.session(new Object(), true);
        until(loop, () -> !snapshots.isEmpty() && backend.pings.get() > previousPings);
        assertNotNull(snapshots.poll());
        // This message follows the initial pong, proving it was consumed before shutdown.
        // Hold its delivery so no request(1) is outstanding when the backend vanishes. The
        // JDK can lose an abrupt close in this gap; only the keepalive is reliable.
        delivery.set(new CompletableFuture<>()); backend.push(PhotonFixture.document(13));
        until(loop, () -> !snapshots.isEmpty()); assertNotNull(snapshots.poll());
        backend.close();
        // A blocking status wait freezes this manual clock, including the keepalive which
        // detects the lost close. Advance past its next ping and five-second reply deadline.
        for (int step = 0; step < 7 && !provider.status().state().equals("stand_down"); step++) {
          loop.advance(1_000_000); loop.drain();
        }
        assertEquals("stand_down", provider.status().state(), () -> provider.status().toString());
        until(loop, () -> provider.status().reason() != null);
        String reason = provider.status().reason();
        assertTrue(reason.startsWith("WebSocket keepalive:") || reason.equals("WebSocket unanswered ping exceeded 5 seconds"), reason);
        assertTrue(snapshots.isEmpty()); delivery.get().complete(null);
      }
    }
  }
  @Test void versionArchiveShapeAndUnsupportedMessagesNeverBecomeGuessedSettings() throws Exception {
    var document = PhotonFixture.document(1); document.getAsJsonObject("settings").getAsJsonObject("general").addProperty("version", "v0.0.0");
    assertTrue(assertThrows(IllegalArgumentException.class, () -> PhotonSettings.snapshot(document)).getMessage().contains("version"));
    assertThrows(java.io.IOException.class, () -> PhotonVisionProvider.validateExport(new byte[]{1,2,3}));
    try (var backend = new PhotonFixture()) {
      backend.exportStatus = 404; var events = new LinkedBlockingQueue<Boolean>();
      try (var provider = new PhotonVisionProvider(backend.address(), () -> 1.0,
          (session, timestamp, cameras, meta) -> { fail("No snapshot after export refusal"); return null; }, () -> events.add(true))) {
        provider.session(new Object(), true); awaitState(provider, events, "stand_down");
        assertTrue(provider.status().reason().contains("404")); assertEquals(0, backend.connections.get());
      }
    }
  }
  @Test void unansweredPingDetectsASilentBackendWithoutChargingWorkerDelayToAHealthyPeer() throws Exception {
    try (var backend = new PhotonFixture()) {
      var loop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
      var received = new java.util.concurrent.atomic.AtomicInteger();
      try (var provider = new PhotonVisionProvider(backend.address(), () -> 1_000_000.0,
          (session, timestamp, cameras, metadata) -> { received.incrementAndGet(); return CompletableFuture.completedFuture(null); },
          () -> {}, java.net.http.HttpClient.newHttpClient(), loop)) {
        provider.session(new Object(), true);
        until(loop, () -> received.get() == 1 && backend.pings.get() == 1);
        // A subsequent data message is delivered after the first ping/pong exchange, so its
        // callback is the barrier proving that the network listener consumed the pong.
        backend.push(PhotonFixture.document(11)); until(loop, () -> received.get() == 2);
        loop.advance(1_500_000); assertEquals("following", provider.status().state());
        backend.answerPing = false; loop.advance(500_000); until(loop, () -> backend.pings.get() == 2);
        loop.advance(4_999_999); assertEquals("following", provider.status().state());
        loop.advance(1); assertEquals("stand_down", provider.status().state());
        assertTrue(provider.status().reason().contains("unanswered ping"));
      }
    }
  }
  private static void until(org.triplehelix.wpilogmcp.nt4.client.ManualScheduler loop, java.util.function.BooleanSupplier ready) {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!ready.getAsBoolean() && System.nanoTime() < end) { loop.drain(); java.util.concurrent.locks.LockSupport.parkNanos(100_000); }
    assertTrue(ready.getAsBoolean()); loop.drain();
  }
  private static void awaitState(PhotonVisionProvider provider, LinkedBlockingQueue<Boolean> events, String state) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!provider.status().state().equals(state) && System.nanoTime() < end) events.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
    assertEquals(state, provider.status().state(), () -> provider.status().toString());
  }
}
