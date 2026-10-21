/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Properties;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.store.DiskSpaceBudgets;

class CaptureLowDiskTest {
  @TempDir Path directory;
  private static final Announce TOPIC = new Announce("/x", 17, "int", null, new JsonObject());
  private static final Announce SCHEMA = new Announce("/.schema/struct:Point", 18, "structschema", null, new JsonObject());
  private static final DiskSpaceBudgets.Policy POLICY = new DiskSpaceBudgets.Policy(200, 100, 50);
  private static final class Wall extends Clock {
    Instant now = Instant.parse("2026-10-20T01:00:00Z");
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
  private final class Placement implements CaptureWriter.Observer {
    final List<Path> paths = new ArrayList<>();
    final List<CaptureWriter.Session> closed = new ArrayList<>();
    final List<Boolean> append = new ArrayList<>();
    @Override public Path create(String address, Instant started) {
      var path = directory.resolve("capture-" + paths.size() + ".wpilog"); paths.add(path); return path;
    }
    @Override public void opened(CaptureWriter.Session session, boolean resumed) { append.add(resumed); }
    @Override public void closed(CaptureWriter.Session session) { closed.add(session); }
  }
  private static void connect(CaptureWriter writer) {
    writer.connected(URI.create("ws://127.0.0.1:5810/nt/test"), "networktables.first.wpi.edu");
    writer.timeSync(1_000_000, 0); writer.announce(TOPIC);
  }
  private static DiskSpaceBudgets.Snapshot free(Long bytes) {
    return new DiskSpaceBudgets.Snapshot("filesystem_1", bytes == null ? DiskSpaceBudgets.State.UNKNOWN
        : DiskSpaceBudgets.State.AVAILABLE, bytes, 0, POLICY.reserveBytes(), 1);
  }
  private static Map<String, List<Long>> integers(Path file) throws Exception {
    var names = new HashMap<Integer, String>(); var values = new HashMap<String, List<Long>>();
    for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file)))) {
      if (record.isStart()) {
        var entry = record.getStartData();
        if (entry.type.equals("int64")) names.put(entry.entry, entry.name);
      } else if (!record.isControl() && names.containsKey(record.getEntry())) {
        values.computeIfAbsent(names.get(record.getEntry()), ignored -> new ArrayList<>()).add(record.getInteger());
      }
    }
    return values;
  }

  @Test void lowDiskClosesCleanlyAndResumesANewSessionWithoutAnotherRobotClock() throws Exception {
    var wall = new Wall(); var loop = new ManualScheduler(); var placement = new Placement();
    try (var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, placement)) {
      writer.diskSpace(free(500L), POLICY); connect(writer);
      writer.value(TOPIC, new ValueFrame(17, 1_000_000, 2, 1L), 0);
      wall.now = wall.now.plusSeconds(1); loop.elapse(1_000_000);
      writer.diskSpace(free(100L), POLICY);
      assertTrue(writer.diskPaused(), "At the floor, capture must pause without needing a changed robot clock");
      assertFalse(writer.ready()); assertEquals(1, placement.closed.size());
      assertEquals("Capture paused for low disk space: free_bytes=100, floor_bytes=100", placement.closed.get(0).endReason());
      assertEquals(Instant.parse("2026-10-20T01:00:01Z"), placement.closed.get(0).endedAt());
      writer.value(TOPIC, new ValueFrame(17, 2_000_000, 2, 2L), 1_000_000);
      writer.diskSpace(free(150L), POLICY);
      assertTrue(writer.diskPaused(), "At floor plus margin, capture remains paused");
      assertEquals(1, placement.paths.size());
      wall.now = wall.now.plusSeconds(2); loop.elapse(2_000_000);
      writer.diskSpace(free(151L), POLICY);
      assertFalse(writer.diskPaused()); assertTrue(writer.ready());
      assertEquals(List.of(false, false), placement.append, "Recovery creates a new file rather than appending over the gap");
      assertEquals(Instant.parse("2026-10-20T01:00:03Z"), writer.session().startedAt());
      writer.value(TOPIC, new ValueFrame(17, 4_000_000, 2, 3L), 3_000_000);
      assertEquals(1, writer.diskDroppedValues());
    }
    assertEquals(Map.of("NT:/x", List.of(1L)), integers(placement.paths.get(0)));
    assertEquals(Map.of("NT:/x", List.of(3L)), integers(placement.paths.get(1)));
  }

  @Test void unknownSpacePreservesTheCurrentStateAndDoesNotFabricateZero() throws Exception {
    var placement = new Placement(); var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(new Wall(), loop, CapturePolicy.ALL, placement)) {
      writer.diskSpace(free(500L), POLICY); connect(writer);
      writer.diskSpace(free(null), POLICY);
      assertTrue(writer.ready()); assertFalse(writer.diskPaused());
      assertNotNull(writer.diskSpace(), "Unknown measurements must be published");
      assertEquals(DiskSpaceBudgets.State.UNKNOWN, writer.diskSpace().state());
      assertNull(writer.diskSpace().freeBytes());
      writer.value(TOPIC, new ValueFrame(17, 1_000_000, 2, 1L), 0);
      writer.diskSpace(free(100L), POLICY); writer.diskSpace(free(null), POLICY);
      assertTrue(writer.diskPaused(), "Unknown space cannot establish the resume threshold");
      assertEquals(1, placement.paths.size());
    }
    assertEquals(Map.of("NT:/x", List.of(1L)), integers(placement.paths.get(0)));
  }

  @Test void recoverySeedsTheLatestSchemaAndRedeclaresContextWithoutInventingMeasurements() throws Exception {
    var placement = new Placement(); var loop = new ManualScheduler(); var wall = new Wall();
    byte[] original = "int32 x;".getBytes(StandardCharsets.UTF_8);
    byte[] changed = "int32 x;int32 y;".getBytes(StandardCharsets.UTF_8);
    try (var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, placement)) {
      writer.diskSpace(free(500L), POLICY); connect(writer); writer.announce(SCHEMA);
      writer.value(SCHEMA, new ValueFrame(18, 1_000_000, 5, original), 0);
      writer.recordContext("/Daemon/Probe/Count", "int64", 41L, 1_000_000, new JsonObject());
      writer.diskSpace(free(99L), POLICY);
      writer.value(SCHEMA, new ValueFrame(18, 2_000_000, 5, changed), 1_000_000);
      assertEquals(0, writer.recordContext("/Daemon/Probe/Count", "int64", 42L, 2_000_000, new JsonObject()));
      var patch = new JsonObject(); patch.addProperty("unit", "updated while paused");
      writer.properties(new Properties("/x", null, patch));
      writer.timeSync(3_000_000, 2_000_000); wall.now = wall.now.plusSeconds(2);
      writer.diskSpace(free(151L), POLICY);
      assertTrue(writer.ready());
      int declarationsBeforeAnyNewProviderValue = 0;
      for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(placement.paths.get(1))))) {
        if (record.isStart() && record.getStartData().name.equals("/Daemon/Probe/Count"))
          declarationsBeforeAnyNewProviderValue++;
      }
      assertEquals(1, declarationsBeforeAnyNewProviderValue,
          "The new file must retain the context declaration before a provider publishes again");
      writer.recordContext("/Daemon/Probe/Count", "int64", 43L, 3_000_000, new JsonObject());
    }
    var starts = new HashMap<Integer, edu.wpi.first.util.datalog.DataLogRecord.StartRecordData>();
    int seeds = 0; int contextDeclarations = 0; boolean updatedProperties = false;
    for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(placement.paths.get(1))))) {
      if (record.isStart()) {
        var entry = record.getStartData(); starts.put(entry.entry, entry);
        if (entry.name.equals("/Daemon/Probe/Count")) contextDeclarations++;
        if (entry.name.equals("NT:/x")) updatedProperties = entry.metadata.contains("updated while paused");
      } else if (!record.isControl() && starts.get(record.getEntry()).type.equals("structschema")) {
        seeds++; assertEquals(3_000_000, record.getTimestamp()); assertArrayEquals(changed, record.getRaw());
        assertTrue(starts.get(record.getEntry()).metadata.contains("\"capture_schema_seed\":true"));
      }
    }
    assertEquals(1, seeds); assertEquals(1, contextDeclarations); assertTrue(updatedProperties);
    assertEquals(Map.of("/Daemon/Probe/Count", List.of(43L)), integers(placement.paths.get(1)));
  }

  @Test void lowDiskBeforeAdmissionNeverOpensAndStillResumesTheSameConnection() throws Exception {
    var placement = new Placement(); var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(new Wall(), loop, CapturePolicy.ALL, placement)) {
      writer.diskSpace(free(99L), POLICY); connect(writer);
      assertEquals(0, placement.paths.size(), "A known low disk must refuse file opening before writing a header");
      writer.value(TOPIC, new ValueFrame(17, 1_000_000, 2, 11L), 0);
      writer.diskSpace(free(151L), POLICY);
      assertTrue(writer.ready()); assertEquals(1, placement.paths.size());
      writer.value(TOPIC, new ValueFrame(17, 2_000_000, 2, 12L), 1_000_000);
    }
    assertEquals(Map.of("NT:/x", List.of(12L)), integers(placement.paths.get(0)));
  }

  @Test void aLowDiskPauseRetiresLateAdmissionAndKeepsQueuedDeclarations() throws Exception {
    var loop = new ManualScheduler(); var pending = new CompletableFuture<Path>();
    var abandoned = new ArrayList<String>(); var calls = new ArrayList<Long>();
    var second = new Announce("/second", 19, "int", null, new JsonObject());
    var placement = new CaptureWriter.Observer() {
      public Path create(String address, Instant started) { return directory.resolve("recovered.wpilog"); }
      @Override public CompletableFuture<Path> createAsync(String address, Instant start, CaptureWriter.Session previous,
          org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity, long timeoutUs) {
        calls.add(timeoutUs);
        return calls.size() == 1 ? pending : CompletableFuture.completedFuture(create(address, start));
      }
      @Override public CompletableFuture<Void> abandoned(Path path, Instant end, String reason) {
        abandoned.add(reason); return CompletableFuture.completedFuture(null);
      }
    };
    var limits = new CaptureWriter.AdmissionLimits(4096, 8, 200_000, 2, 2_000);
    try (var writer = new CaptureWriter(new Wall(), loop, CapturePolicy.ALL, placement,
        CaptureWriter.DEFAULT_MAX_FILE_BYTES, WpilogOutput::new, limits)) {
      writer.diskSpace(free(500L), POLICY); connect(writer); writer.announce(second);
      writer.value(TOPIC, new ValueFrame(17, 1_000_000, 2, 1L), 0);
      writer.diskSpace(free(99L), POLICY); loop.drain();
      for (int i = 0; i < 100; i++) writer.value(TOPIC, new ValueFrame(17, 1_000_001 + i, 2, (long) i), i + 1);
      assertNull(writer.admissionFailure(), "Paused values must not consume the bounded admission backlog");
      writer.diskSpace(free(151L), POLICY);
      assertEquals(1, calls.size(), "Recovery must retire the earlier placement before starting another");
      pending.complete(directory.resolve("late.wpilog")); loop.drain();
      assertEquals(1, abandoned.size()); assertTrue(abandoned.get(0).contains("low disk space"));
      assertEquals(List.of(200_000L, 200_000L), calls, "Store retries inherit the writer's 200 ms deadline");
      assertTrue(writer.ready()); assertFalse(Files.exists(directory.resolve("late.wpilog")));
      writer.value(second, new ValueFrame(19, 2_000_000, 2, 22L), 1_000_000);
    }
    assertEquals(Map.of("NT:/second", List.of(22L)), integers(directory.resolve("recovered.wpilog")));
  }

  @Test void actualStoreManifestsDelimitTheLowDiskGapAndKeepBothFinalHashes() throws Exception {
    var security = new org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator();
    security.addAllowedDirectory(directory);
    var wall = new Wall(); var loop = new ManualScheduler(); var root = directory.resolve("store");
    try (var stores = new org.triplehelix.wpilogmcp.store.StoreRegistry(security)) {
      var placement = stores.store(root).captures(wall);
      try (var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, placement)) {
        writer.diskSpace(free(500L), POLICY); connect(writer); loop.until(writer::ready);
        writer.value(TOPIC, new ValueFrame(17, 1_000_000, 2, 11L), 0);
        wall.now = wall.now.plusSeconds(1); loop.elapse(1_000_000);
        writer.diskSpace(free(99L), POLICY);
        assertTrue(writer.diskPaused(), "The store-backed writer must close before recording the gap");
        placement.completion().get(5, TimeUnit.SECONDS);
        writer.value(TOPIC, new ValueFrame(17, 2_000_000, 2, 22L), 1_000_000);
        wall.now = wall.now.plusSeconds(2); loop.elapse(2_000_000);
        writer.diskSpace(free(151L), POLICY); loop.until(writer::ready);
        writer.value(TOPIC, new ValueFrame(17, 4_000_000, 2, 33L), 3_000_000);
        writer.disconnected(); placement.completion().get(5, TimeUnit.SECONDS);
      }
      var catalog = org.triplehelix.wpilogmcp.store.StoreCatalog.read(root, security);
      assertTrue(catalog.openCaptures().isEmpty()); assertEquals(2, catalog.files().size());
      var files = catalog.files().stream().sorted(java.util.Comparator.comparing(file -> file.session().startedAt())).toList();
      assertNotEquals(files.get(0).session().id(), files.get(1).session().id());
      assertEquals("2026-10-20T01:00:01Z", files.get(0).session().endedAt());
      assertEquals("2026-10-20T01:00:03Z", files.get(1).session().startedAt());
      assertEquals("Capture paused for low disk space: free_bytes=99, floor_bytes=100", files.get(0).session().endReason());
      assertEquals(Map.of("NT:/x", List.of(11L)), integers(files.get(0).path()));
      assertEquals(Map.of("NT:/x", List.of(33L)), integers(files.get(1).path()));
      for (var file : files) {
        var expectedHash = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.path())));
        assertEquals(expectedHash, file.file().sha256()); assertEquals(Files.size(file.path()), file.file().sizeBytes());
      }
    }
  }

  @Test void pausedSchemaValuesKeepTheirOrderBehindDeferredTopicReplacement() throws Exception {
    var loop = new ManualScheduler(); var pending = new CompletableFuture<Path>();
    var calls = new ArrayList<Long>();
    var placement = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return directory.resolve("recovered.wpilog"); }
      @Override public CompletableFuture<Path> createAsync(String address, Instant start, CaptureWriter.Session previous,
          org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity, long timeoutUs) {
        calls.add(timeoutUs);
        return calls.size() == 1 ? pending : CompletableFuture.completedFuture(create(address, start));
      }
    };
    byte[] expected = "int32 y;".getBytes(StandardCharsets.UTF_8);
    var limits = new CaptureWriter.AdmissionLimits(4096, 16, 200_000, 1, 2_000);
    try (var writer = new CaptureWriter(new Wall(), loop, CapturePolicy.ALL, placement,
        CaptureWriter.DEFAULT_MAX_FILE_BYTES, WpilogOutput::new, limits)) {
      writer.diskSpace(free(500L), POLICY); connect(writer);
      writer.announce(SCHEMA);
      writer.unannounce(new org.triplehelix.wpilogmcp.nt4.ControlMessage.Unannounce(SCHEMA.name(), SCHEMA.id()));
      writer.announce(SCHEMA);
      writer.diskSpace(free(99L), POLICY);
      // The pause drains only one old event. This value belongs after the queued replacement,
      // so replaying that replacement must not erase the schema retained for the new file.
      writer.value(SCHEMA, new ValueFrame(SCHEMA.id(), 2_000_000, 5, expected), 1_000_000);
      loop.drain(); writer.diskSpace(free(151L), POLICY);
      pending.complete(directory.resolve("abandoned.wpilog")); loop.drain();
      assertTrue(writer.ready()); assertEquals(2, calls.size());
    }
    var entries = new HashMap<Integer, edu.wpi.first.util.datalog.DataLogRecord.StartRecordData>();
    int seeds = 0;
    for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(directory.resolve("recovered.wpilog"))))) {
      if (record.isStart()) entries.put(record.getStartData().entry, record.getStartData());
      else if (!record.isControl() && entries.get(record.getEntry()).type.equals("structschema")) {
        seeds++; assertArrayEquals(expected, record.getRaw());
        assertTrue(entries.get(record.getEntry()).metadata.contains("\"capture_schema_seed\":true"));
      }
    }
    assertEquals(1, seeds, "The schema received after topic replacement must survive ordered pause replay");
  }

  @Test void anOrdinaryOutputFailureDoesNotBecomeARecoverableDiskPause() throws Exception {
    var placement = new Placement(); var loop = new ManualScheduler();
    CaptureWriter.OutputFactory broken = (path, next, resume) -> new WpilogOutput(path, next, resume) {
      @Override public Written append(int id, long timestamp, byte[] payload) throws IOException {
        throw new IOException("synthetic media failure");
      }
    };
    try (var writer = new CaptureWriter(new Wall(), loop, CapturePolicy.ALL, placement,
        CaptureWriter.DEFAULT_MAX_FILE_BYTES, broken)) {
      writer.diskSpace(free(500L), POLICY); connect(writer);
      writer.value(TOPIC, new ValueFrame(17, 1_000_000, 2, 1L), 0);
      assertEquals("Capture write failed: synthetic media failure", writer.session().endReason());
      assertFalse(writer.diskPaused());
      writer.diskSpace(free(99L), POLICY); writer.diskSpace(free(500L), POLICY);
      assertFalse(writer.ready()); assertEquals(1, placement.paths.size());
      assertEquals("Capture write failed: synthetic media failure", writer.session().endReason());
    }
  }

  @Test void aBlockedSpaceProbeDoesNotBlockTheListenerAndReserveOutlivesSampling() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var loop = new ManualScheduler(); var placement = new Placement();
    try (var budgets = new DiskSpaceBudgets(filesystem -> {
      entered.countDown();
      try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test release missing"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
      return 500;
    }); var writer = new CaptureWriter(new Wall(), loop, CapturePolicy.ALL, placement)) {
      var budget = budgets.store(directory);
      var monitor = new CaptureDiskMonitor(loop, writer, budget, POLICY);
      try {
        assertEquals(200, budget.snapshot().captureReserveBytes(), "Configured waiting captures already protect their reserve");
        var initial = assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), monitor::start,
            "Starting measurement must not join a blocked filesystem query");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var observed = new AtomicReference<String>(); loop.execute(() -> observed.set("listener ran"));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), loop::drain,
            "A blocked filesystem query must not occupy the listener");
        assertEquals("listener ran", observed.get()); assertTrue(initial.isDone(), "Unknown free space must not block NT4 startup");
        assertEquals(DiskSpaceBudgets.State.UNKNOWN, budget.snapshot().state());
        monitor.stopSampling(); assertEquals(200, budget.snapshot().captureReserveBytes());
        release.countDown(); loop.until(initial::isDone);
        assertEquals(200, budget.snapshot().captureReserveBytes(), "The reserve remains after sampling and capture stop");
      } finally { release.countDown(); monitor.stopSampling(); }
    }
  }
}
