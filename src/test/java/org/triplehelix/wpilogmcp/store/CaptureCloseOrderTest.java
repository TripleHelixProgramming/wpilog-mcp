/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;

/**
 * Capture's close waits for the final manifest and then for settled clock records. The clock pass
 * for the file just closed was asked for after its manifest completed, so a close could miss it
 * and the stop close the store under it.
 */
class CaptureCloseOrderTest {
  private static final Clock WALL = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
  @TempDir Path directory;
  private final LogManager manager = LogManager.getInstance();
  private Set<Path> allowed;
  private LogStore store;

  @BeforeEach void setup() throws Exception {
    directory = directory.toRealPath();
    allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(directory);
    store = manager.stores().store(directory.resolve("store"));
  }

  @AfterEach void cleanup() throws Exception {
    if (store != null) store.close();
    manager.unloadAllLogs();
    manager.clearAllowedDirectories();
    allowed.forEach(manager::addAllowedDirectory);
  }

  @Test void theClosedFilesClockPassIsAskedForBeforeItsFinalManifestCompletes() throws Exception {
    var passes = new CountDownLatch(1);
    store.clockPassProbe(() -> {
      try { passes.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    });
    var placement = store.captures(WALL);
    var loop = new ManualScheduler();
    var queueHeld = new CountDownLatch(1);
    var queueRelease = new CountDownLatch(1);
    try {
      java.util.concurrent.CompletableFuture<Void> holding;
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, placement)) {
        writer.connected(RobotAddress.uri("127.0.0.1", 1, "close-order"), "networktables.first.wpi.edu");
        writer.timeSync(10_000_000, 0);
        var topic = new ControlMessage.Announce("/Value", 1, "int", null, new JsonObject());
        writer.announce(topic);
        loop.until(writer::ready);
        writer.value(topic, new ValueFrame(1, 10_000_001, 2, 23L), 0);
        // The final manifest update queues behind this turn, so its completion is observed as it happens.
        holding = store.recordingAsync(io -> {
          queueHeld.countDown();
          try { queueRelease.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          return null;
        });
        assertTrue(queueHeld.await(5, TimeUnit.SECONDS));
      }
      // Attached before the final manifest completes, the check runs inside that completion, on the thread
      // that completes it, so it sees whether the clock pass was asked for first.
      var settledAtCompletion = placement.completion().thenApply(ignored -> store.clocksSettled().isDone());
      queueRelease.countDown();
      holding.get(10, TimeUnit.SECONDS);
      assertEquals(false, settledAtCompletion.get(10, TimeUnit.SECONDS),
          "When the final manifest completes, the closed file's clock pass is already asked for and still running");
    } finally {
      queueRelease.countDown();
      passes.countDown();
      store.clocksSettled().get(10, TimeUnit.SECONDS);
    }
  }
}
