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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.*;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

class CaptureAdmissionTest {
  @TempDir Path directory;
  static final Announce TOPIC = new Announce("/value", 1, "int", null, new JsonObject());
  static final Instant START = Instant.parse("2026-04-05T12:00:00Z");
  static class Wall extends Clock {
    Instant now = START;
    public Instant instant() { return now; }
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
  }
  final class Placement implements CaptureWriter.Observer {
    final Path path;
    final CompletableFuture<Path> future = new CompletableFuture<>();
    final List<String> abandoned = new ArrayList<>();
    final List<Instant> starts = new ArrayList<>(), ends = new ArrayList<>();
    final List<List<org.triplehelix.wpilogmcp.capture.context.ProviderStatus>> closedProviders = new ArrayList<>();
    int opened, values;
    Placement(String name, boolean immediate) {
      path = directory.resolve(name + ".wpilog");
      if (immediate) future.complete(path);
    }
    public Path create(String address, Instant start) { starts.add(start); return path; }
    public CompletableFuture<Path> createAsync(String address, Instant start, CaptureWriter.Session previous, DeviceIdentity identity) {
      starts.add(start); return future;
    }
    public CompletableFuture<Void> abandoned(Path path, Instant ended, String reason) {
      abandoned.add(reason); return CompletableFuture.completedFuture(null);
    }
    public void opened(CaptureWriter.Session session, boolean resumed) { opened++; }
    public void value(CaptureWriter.Session session, org.triplehelix.wpilogmcp.log.EntryInfo entry,
        ValueFrame frame, WpilogOutput.Written written) { values++; }
    public void closed(CaptureWriter.Session session) { ends.add(session.endedAt()); closedProviders.add(session.statistics().providers()); }
  }
  private CaptureWriter writer(Wall wall, ManualScheduler loop, Placement placement, CaptureWriter.AdmissionLimits limits) {
    return new CaptureWriter(wall, loop, new CapturePolicy(List.of("/skip"), Map.of("/value", 100L)), placement,
        CaptureWriter.DEFAULT_MAX_FILE_BYTES, WpilogOutput::new, limits);
  }
  private static void begin(CaptureWriter writer) {
    writer.connected(URI.create("ws://localhost/nt/test"), "networktables.first.wpi.edu");
    writer.timeSync(1_000_000, 0); writer.announce(TOPIC);
  }
  private static void events(CaptureWriter writer, Wall wall, ManualScheduler loop) {
    writer.value(TOPIC, new ValueFrame(1, 1_000_000, 2, 10L), 1);
    writer.value(TOPIC, new ValueFrame(1, 1_000_099, 2, 11L), 2);
    writer.timeSync(1_000_100, 100);
    var patch = new JsonObject(); patch.addProperty("units", "first");
    writer.properties(new Properties("/value", null, patch)); patch.addProperty("units", "mutated");
    writer.value(TOPIC, new ValueFrame(1, 1_000_100, 2, 12L), 100);
    var skip = new Announce("/skip", 2, "int", null, new JsonObject()); writer.announce(skip);
    writer.value(skip, new ValueFrame(2, 1_000_100, 2, 99L), 100);
    var props = new JsonObject(); props.addProperty("original", true);
    var raw = new Announce("/raw", 3, "raw", null, props); writer.announce(raw); props.addProperty("original", false);
    byte[] bytes = {1, 2, 3}; var frame = new ValueFrame(3, 1_000_101, 5, bytes);
    writer.value(raw, frame, 101); bytes[0] = 9; ((byte[]) frame.value())[1] = 9;
    writer.invalidValue(TOPIC, 1); writer.unannounce(new Unannounce("/raw", 3));
    wall.now = START.plusSeconds(2); loop.elapse(2_000_000); writer.disconnected();
  }
  @Test void deferredControlsValuesClocksAndAccountingEqualImmediateRecording() throws Exception {
    var expected = new Placement("immediate", true); var wall = new Wall(); var loop = new ManualScheduler();
    var immediate = writer(wall, loop, expected, CaptureWriter.ADMISSION_LIMITS);
    begin(immediate); events(immediate, wall, loop);
    var actual = new Placement("deferred", false); wall = new Wall(); loop = new ManualScheduler();
    var delayed = writer(wall, loop, actual, CaptureWriter.ADMISSION_LIMITS);
    begin(delayed); assertNull(delayed.session(), "Pending admission cannot publish a file that has not opened");
    events(delayed, wall, loop); assertEquals(0, actual.values);
    wall.now = START.plusSeconds(20); actual.future.complete(actual.path); loop.drain();
    assertArrayEquals(Files.readAllBytes(expected.path), Files.readAllBytes(actual.path));
    assertEquals(List.of(START), actual.starts); assertEquals(List.of(START.plusSeconds(2)), actual.ends);
    assertEquals(2, delayed.session().statistics().costs().get("/value").records());
    assertEquals(1, delayed.session().statistics().costs().get("/value").thinned());
    assertEquals(1, delayed.session().statistics().costs().get("/value").dropped());
    assertEquals(immediate.session().statistics(), delayed.session().statistics());
    delayed.admissionCompletion().join(); assertFalse(delayed.session().open());
  }
  @Test void drainSlicesGiveOtherLoopWorkATurnWithoutReorderingValues() throws Exception {
    var placement = new Placement("sliced", false); var loop = new ManualScheduler();
    var writer = writer(new Wall(), loop, placement, new CaptureWriter.AdmissionLimits(100_000, 100, 1_000_000, 2, 1_000_000));
    begin(writer); for (int i = 0; i < 5; i++) writer.value(TOPIC, new ValueFrame(1, 1_000_000 + i * 100L, 2, (long) i), i);
    writer.disconnected(); placement.future.complete(placement.path);
    var countAtMarker = new ArrayList<Integer>(); loop.execute(() -> countAtMarker.add(placement.values)); loop.drain();
    assertEquals(List.of(1), countAtMarker, "First slice is announce then first value; remainder yields to ready work");
    assertEquals(5, placement.values); assertFalse(writer.session().open());
    var values = new ArrayList<Long>();
    for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(placement.path)))) if (!record.isControl()) values.add(record.getInteger());
    assertEquals(List.of(0L, 1L, 2L, 3L, 4L), values);
  }
  @Test void eventOverflowIsExplicitAndLateCompletionCannotOpenAHealthySession() {
    var placement = new Placement("events", false); var loop = new ManualScheduler();
    var writer = writer(new Wall(), loop, placement, new CaptureWriter.AdmissionLimits(100_000, 2, 1_000_000, 2, 1000));
    begin(writer); writer.value(TOPIC, new ValueFrame(1, 1, 2, 1L), 0); writer.value(TOPIC, new ValueFrame(1, 2, 2, 2L), 0);
    assertNotNull(writer.admissionFailure()); assertTrue(writer.admissionFailure().contains("3 pending events were not recorded"));
    placement.future.complete(placement.path); loop.drain();
    assertEquals(0, placement.opened); assertEquals(1, placement.abandoned.size()); assertNull(writer.session());
    assertFalse(Files.exists(placement.path)); writer.admissionCompletion().join();
  }
  @Test void byteOverflowAndOversizedFirstAnnouncementAreExplicit() {
    for (boolean first : List.of(false, true)) {
      var placement = new Placement("bytes-" + first, false); var loop = new ManualScheduler();
      var writer = writer(new Wall(), loop, placement, new CaptureWriter.AdmissionLimits(first ? 1 : 1000, 100, 1_000_000, 2, 1000));
      begin(writer);
      if (!first) writer.value(TOPIC, new ValueFrame(1, 1, 5, new byte[2000]), 0);
      assertNotNull(writer.admissionFailure()); assertTrue(writer.admissionFailure().contains("buffer exceeded"));
      placement.future.complete(placement.path); loop.drain(); assertEquals(0, placement.opened); assertEquals(1, placement.abandoned.size());
    }
  }
  @Test void deadlineAndShutdownBothRetireALateAdmission() {
    for (boolean stop : List.of(false, true)) {
      var placement = new Placement("retire-" + stop, false); var loop = new ManualScheduler();
      var writer = writer(new Wall(), loop, placement, new CaptureWriter.AdmissionLimits(10000, 100, 1_000_000, 2, 1000));
      begin(writer);
      if (stop) writer.shutdown(); else { loop.advance(999_999); assertNull(writer.admissionFailure()); loop.advance(1); }
      assertNotNull(writer.admissionFailure()); assertTrue(writer.admissionFailure().contains(stop ? "stopped during admission" : "exceeded 1.0 seconds"));
      placement.future.complete(placement.path); loop.drain();
      assertEquals(0, placement.opened); assertEquals(1, placement.abandoned.size()); writer.admissionCompletion().join();
    }
  }
  @Test void asynchronousCreateFailureExplainsWhyNoRecordingOpened() {
    var placement = new Placement("failed", false); var loop = new ManualScheduler();
    var writer = writer(new Wall(), loop, placement, CaptureWriter.ADMISSION_LIMITS);
    begin(writer); placement.future.completeExceptionally(new IOException("permission denied")); loop.drain();
    assertEquals("Capture admission failed: permission denied", writer.admissionFailure());
    assertNull(writer.session()); assertEquals(0, placement.opened); writer.admissionCompletion().join();
  }
  @Test void timeBudgetYieldsBetweenEventsAndStillMakesProgress() throws Exception {
    var placement = new Placement("time-sliced", false); var loop = new ManualScheduler();
    var writer = writer(new Wall(), loop, placement, new CaptureWriter.AdmissionLimits(100_000, 100, 1_000_000, 100, 1));
    begin(writer); writer.value(TOPIC, new ValueFrame(1, 1_000_000, 2, 1L), 0); writer.disconnected();
    placement.future.complete(placement.path);
    var firstSlice = new ArrayList<Integer>(); loop.execute(() -> firstSlice.add(placement.values)); loop.drain();
    assertEquals(List.of(0), firstSlice, "One declaration consumes the tiny between-event budget before value work");
    assertEquals(1, placement.values); assertFalse(writer.session().open()); writer.admissionCompletion().join();
  }
  @Test void disconnectAndNewBootDuringAdmissionRetainBothRecordings() throws Exception {
    var loop = new ManualScheduler(); var wall = new Wall(); var first = new CompletableFuture<Path>();
    var files = List.of(directory.resolve("boot-one.wpilog"), directory.resolve("boot-two.wpilog"));
    var observer = new CaptureWriter.Observer() {
      int admissions;
      public Path create(String address, Instant start) { return files.get(admissions++); }
      public CompletableFuture<Path> createAsync(String address, Instant start, CaptureWriter.Session previous, DeviceIdentity identity) {
        assertNull(previous, "Reset clock requires a new session");
        return admissions++ == 0 ? first : CompletableFuture.completedFuture(files.get(1));
      }
    };
    var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, observer);
    begin(writer); writer.value(TOPIC, new ValueFrame(1, 1_000_001, 2, 21L), 1); writer.disconnected();
    writer.connected(URI.create("ws://localhost/nt/test"), "networktables.first.wpi.edu");
    writer.timeSync(100, 200); writer.announce(TOPIC); writer.value(TOPIC, new ValueFrame(1, 101, 2, 22L), 201); writer.disconnected();
    first.complete(files.get(0)); loop.drain();
    for (int n = 0; n < 2; n++) {
      var values = new ArrayList<Long>();
      for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(files.get(n))))) if (!record.isControl()) values.add(record.getInteger());
      assertEquals(List.of(21L + n), values);
    }
    assertFalse(writer.session().open()); writer.admissionCompletion().join();
  }

  @Test void aNewBootWaitsForCancelledAdmissionRetirementThenRecordsItsBufferedValues() throws Exception {
    var loop = new ManualScheduler(); var first = new CompletableFuture<Path>();
    var old = directory.resolve("retired.wpilog"); var fresh = directory.resolve("new-boot.wpilog");
    var retired = new ArrayList<String>();
    var observer = new CaptureWriter.Observer() {
      int calls;
      public Path create(String address, Instant start) { return old; }
      public CompletableFuture<Path> createAsync(String address, Instant start, CaptureWriter.Session previous, DeviceIdentity identity) {
        return calls++ == 0 ? first : CompletableFuture.completedFuture(fresh);
      }
      public CompletableFuture<Void> abandoned(Path path, Instant ended, String reason) {
        assertEquals(old, path); retired.add(reason); return CompletableFuture.completedFuture(null);
      }
    };
    var writer = new CaptureWriter(new Wall(), loop, CapturePolicy.ALL, observer, CaptureWriter.DEFAULT_MAX_FILE_BYTES,
        WpilogOutput::new, new CaptureWriter.AdmissionLimits(100000, 100, 1_000_000, 100, 1000));
    begin(writer); loop.advance(1_000_000); assertNotNull(writer.admissionFailure());
    writer.disconnected(); writer.connected(URI.create("ws://localhost/nt/test"), "networktables.first.wpi.edu");
    writer.timeSync(100, 1_000_000); writer.announce(TOPIC);
    writer.value(TOPIC, new ValueFrame(1, 101, 2, 42L), 1_000_001); writer.disconnected();
    first.complete(old); loop.drain();
    assertEquals(1, retired.size()); assertFalse(Files.exists(old)); assertTrue(Files.exists(fresh));
    var values = new ArrayList<Long>();
    for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(fresh)))) if (!record.isControl()) values.add(record.getInteger());
    assertEquals(List.of(42L), values); assertFalse(writer.session().open());
  }

  @Test void retainedTopicMetadataCountsTowardBothValueAndInvalidValueBudgets() {
    for (boolean invalid : List.of(false, true)) {
      var placement = new Placement("metadata-" + invalid, false); var loop = new ManualScheduler();
      var writer = writer(new Wall(), loop, placement, new CaptureWriter.AdmissionLimits(1000, 100, 1_000_000, 100, 1000));
      begin(writer);
      var properties = new JsonObject(); properties.addProperty("large", "x".repeat(1000));
      var topic = new Announce(TOPIC.name(), TOPIC.id(), TOPIC.type(), null, properties);
      if (invalid) writer.invalidValue(topic, 1); else writer.value(topic, new ValueFrame(1, 1, 2, 1L), 0);
      assertNotNull(writer.admissionFailure(), "Retained topic metadata cannot bypass the accounted-event budget");
      assertTrue(writer.admissionFailure().contains("buffer exceeded"));
      placement.future.complete(placement.path); loop.drain(); assertEquals(0, placement.opened);
    }
  }
  @Test void contextAndActivityWaitUntilTheOrderedBacklogIsDrained() throws Exception {
    var placement = new Placement("provider-admission", false); var loop = new ManualScheduler();
    var writer = writer(new Wall(), loop, placement, new CaptureWriter.AdmissionLimits(100000, 100, 1_000_000, 1, 1000));
    var notifications = new ArrayList<Boolean>(); writer.onActivity(() -> notifications.add(writer.ready()));
    begin(writer); writer.value(TOPIC, new ValueFrame(1, 1_000_000, 2, 1L), 0);
    placement.future.complete(placement.path);
    loop.execute(() -> {
      assertFalse(writer.ready());
      assertEquals(0, writer.recordContext("/Daemon/Test", "int64", 7L, 1_000_000, new JsonObject()));
    });
    loop.drain();
    assertEquals(List.of(false, true), notifications); assertTrue(writer.ready());
    assertTrue(writer.recordContext("/Daemon/Test", "int64", 8L, 1_000_001, new JsonObject()) > 0);
    writer.disconnected();
  }
  @Test void defaultAdmissionPolicyIsExplicit() {
    assertEquals(new CaptureWriter.AdmissionLimits(16L << 20, 65_536, 30_000_000, 256, 2_000), CaptureWriter.ADMISSION_LIMITS);
    assertThrows(IllegalArgumentException.class, () -> new CaptureWriter.AdmissionLimits(0, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CaptureWriter.AdmissionLimits(1, 0, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CaptureWriter.AdmissionLimits(1, 1, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CaptureWriter.AdmissionLimits(1, 1, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new CaptureWriter.AdmissionLimits(1, 1, 1, 1, 0));
  }

  @Test void providerSnapshotsKeepTheirEpisodeOrderDuringAdmission() {
    var placement = new Placement("provider-order", false); var loop = new ManualScheduler();
    var writer = writer(new Wall(), loop, placement, CaptureWriter.ADMISSION_LIMITS);
    var first = new org.triplehelix.wpilogmcp.capture.context.ProviderStatus("stats", "sampling", "first boot", 2, null, null, 0, 0, 0, 0, 0, 0);
    var later = new org.triplehelix.wpilogmcp.capture.context.ProviderStatus("stats", "offline", "next connection", 2, null, null, 0, 0, 0, 0, 0, 0);
    writer.providers(List.of(first), null); begin(writer); writer.disconnected();
    var supplied = new ArrayList<>(List.of(later)); writer.providers(supplied, null); supplied.clear();
    placement.future.complete(placement.path); loop.drain();
    assertEquals(List.of(List.of(first)), placement.closedProviders);
  }
  private static final class Steps implements org.triplehelix.wpilogmcp.nt4.client.ClientScheduler {
    record Timer(long at, Runnable action) {}
    final java.util.concurrent.LinkedBlockingDeque<Runnable> ready = new java.util.concurrent.LinkedBlockingDeque<>();
    final List<Timer> timers = new ArrayList<>();
    long now;
    public long nowUs() { return now; }
    public void execute(Runnable action) { ready.add(action); }
    public void schedule(Runnable action, long delay) { timers.add(new Timer(now + delay, action)); }
    public void close() { timers.clear(); }
    void one() throws Exception { var task = ready.poll(5, java.util.concurrent.TimeUnit.SECONDS); assertNotNull(task); task.run(); }
    void timers(long elapsed) { now += elapsed; var due = timers.stream().filter(t -> t.at() <= now).toList(); timers.removeAll(due); due.forEach(t -> t.action().run()); }
  }
  @Test void flushDuringReplayDoesNotPublishItsLaterWallOrReceiptClock() throws Exception {
    var wall = new Wall(); var loop = new Steps(); var placement = new Placement("replay-flush", false);
    var seen = new ArrayList<Instant>(); var seenUs = new ArrayList<Long>();
    var observer = new CaptureWriter.Observer() {
      public Path create(String address, Instant start) { return placement.path; }
      public CompletableFuture<Path> createAsync(String address, Instant start, CaptureWriter.Session previous, DeviceIdentity identity) { return placement.future; }
      public void flushed(CaptureWriter.Session session) { seen.add(session.observedAt()); seenUs.add(session.observedAtUs()); }
    };
    var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, observer, CaptureWriter.DEFAULT_MAX_FILE_BYTES,
        WpilogOutput::new, new CaptureWriter.AdmissionLimits(1_000_000, 2000, 30_000_000, 1, 1000));
    begin(writer); for (int i = 0; i < 1000; i++) writer.value(TOPIC, new ValueFrame(1, 1_000_000 + i, 2, (long) i), i);
    wall.now = START.plusSeconds(2); writer.disconnected(); wall.now = START.plusSeconds(20);
    placement.future.complete(placement.path); loop.one(); // Open and replay the announcement only.
    loop.timers(250_000);
    int callbacks = 0; while (seen.isEmpty() && callbacks++ < 1001) loop.one();
    assertFalse(seen.isEmpty()); assertEquals(START, seen.get(0)); assertEquals(0L, seenUs.get(0));
    while (!writer.admissionCompletion().isDone()) loop.one();
    assertEquals(START.plusSeconds(2), writer.session().endedAt());
  }

}
