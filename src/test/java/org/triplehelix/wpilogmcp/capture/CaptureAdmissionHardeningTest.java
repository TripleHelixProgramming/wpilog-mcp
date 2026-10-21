/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.store.CaptureStore;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreRegistry;

/** Admission must survive unrelated payload work and a short external store-lock holder. */
class CaptureAdmissionHardeningTest {
  @TempDir Path directory;
  private static final Clock WALL = Clock.fixed(Instant.parse("2026-04-05T12:00:00Z"), ZoneOffset.UTC);
  private static final CaptureWriter.AdmissionLimits LIMITS =
      new CaptureWriter.AdmissionLimits(1 << 20, 1_000, 200_000, 256, 2_000);
  private static final Announce TOPIC = new Announce("/ordered", 1, "int", null, new JsonObject());

  @Test void inspectionPastTheAdmissionDeadlineDoesNotStopCapture() throws Exception {
    var manager = LogManager.getInstance();
    var allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(directory);
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    var inspecting = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var stores = new StoreRegistry(security, manager)) {
      var store = stores.store(directory.resolve("store"));
      var sources = new ArrayList<Path>();
      for (int i = 0; i < 128; i++)
        sources.add(ImportFixture.write(directory.resolve("source-" + i + ".wpilog"), 19 + i));
      var importing = store.importPaths(new LogStore.Request(sources, false, "SYNTHETIC-IMPORT"), progress -> {
        if (!progress.phase().equals("inspecting")) return;
        inspecting.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Import stall was not released");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      });
      assertTrue(inspecting.await(10, TimeUnit.SECONDS), "The real import must reach inspection");
      var loop = new ManualScheduler();
      var placement = store.captures(WALL);
      var writer = writer(loop, placement);
      try {
        start(writer);
        send(writer);
        pump(loop, Duration.ofMillis(350));
        assertNull(writer.admissionFailure(),
            "Import inspection longer than the 200 ms deadline must not stop recording");
        assertTrue(writer.ready(), "Capture must be admitted while import inspection remains stalled");
        assertTrue(stores.queueMetrics().longestTurnUs() < 1_000_000,
            "The outside-observed store turns must remain within the agreed one-second budget");
        release.countDown();
        var result = importing.get(10, TimeUnit.SECONDS);
        assertEquals(128, result.files().size(), "Every independently generated source has an outcome");
        assertTrue(result.files().stream().allMatch(file -> "imported".equals(file.status())), result.toString());
        writer.disconnected();
        loop.drain();
        placement.completion().get(10, TimeUnit.SECONDS);
        assertRecordedValues(writer);
      } finally {
        release.countDown();
        importing.get(10, TimeUnit.SECONDS);
        writer.shutdown();
        loop.drain();
        placement.completion().get(10, TimeUnit.SECONDS);
        loop.close();
      }
    } finally {
      release.countDown();
      manager.clearAllowedDirectories();
      allowed.forEach(manager::addAllowedDirectory);
    }
  }

  @Test void aHeldStoreLockReleasedWithinAdmissionDeadlineIsRetried() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      var loop = new ManualScheduler();
      var placement = store.captures(WALL);
      var writer = writer(loop, placement);
      try {
        try (var channel = FileChannel.open(store.root().resolve("store.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = channel.lock()) {
          start(writer);
          send(writer);
          pump(loop, Duration.ofMillis(50));
        }
        pump(loop, Duration.ofMillis(300));
        assertNull(writer.admissionFailure(),
            "A store lock released after 50 ms must be retried within the 200 ms admission deadline");
        assertTrue(writer.ready(), "Capture must open after the other channel releases store.lock");
        writer.disconnected();
        loop.drain();
        placement.completion().get(10, TimeUnit.SECONDS);
        assertRecordedValues(writer);
      } finally {
        writer.shutdown();
        loop.drain();
        placement.completion().get(10, TimeUnit.SECONDS);
        loop.close();
      }
    }
  }

  private static CaptureWriter writer(ManualScheduler loop, CaptureStore placement) {
    return new CaptureWriter(WALL, loop, CapturePolicy.ALL, placement,
        CaptureWriter.DEFAULT_MAX_FILE_BYTES, WpilogOutput::new, LIMITS);
  }

  private static void start(CaptureWriter writer) throws Exception {
    writer.connected(java.net.URI.create("ws://localhost/nt/test"), "networktables.first.wpi.edu");
    writer.timeSync(1_000_000, 0);
    writer.announce(TOPIC);
  }

  private static void send(CaptureWriter writer) throws Exception {
    for (int i = 0; i < 3; i++)
      writer.value(TOPIC, new ValueFrame(1, 1_000_100 + i * 20_000, 2, 17L + i), i * 20_000);
  }

  /** Advance the listener clock with real elapsed time so its configured deadline is exercised. */
  private static void pump(ManualScheduler loop, Duration duration) throws InterruptedException {
    long previous = System.nanoTime();
    long deadline = previous + duration.toNanos();
    while (previous < deadline) {
      Thread.sleep(2);
      long now = System.nanoTime();
      loop.advance(Math.max(1, (now - previous) / 1_000));
      previous = now;
    }
  }

  private static void assertRecordedValues(CaptureWriter writer) throws Exception {
    assertNotNull(writer.session());
    var values = new ArrayList<Long>();
    var times = new ArrayList<Long>();
    for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(writer.session().path())))) {
      if (record.isControl()) continue;
      times.add(record.getTimestamp());
      values.add(record.getInteger());
    }
    assertEquals(List.of(17L, 18L, 19L), values, "Every value admitted before opening must be recorded");
    assertEquals(List.of(1_000_100L, 1_020_100L, 1_040_100L), times,
        "Admission retains the three original robot timestamps");
  }
}
