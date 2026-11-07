/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Unannounce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

class CaptureWriterTest {
  @TempDir Path directory;
  private static final Clock WALL = Clock.fixed(Instant.parse("2026-03-07T14:22:33Z"), ZoneOffset.UTC);
  private static final Announce TOPIC = new Announce("/x", 17, "int", null, new JsonObject());
  private static void connect(CaptureWriter writer, long serverUs, long receivedUs) {
    writer.connected(URI.create("ws://127.0.0.1:5810/nt/test"), "networktables.first.wpi.edu");
    writer.timeSync(serverUs, receivedUs); writer.announce(TOPIC);
  }
  @Test void publicationTimingUsesReceiptAndCompletionClockNotTheRobotSampleTimestamp() throws Exception {
    var loop = new ManualScheduler(); var times = new ArrayList<Long>();
    var measurements = new ArrayList<CaptureIndex.PublicationTiming>();
    var placement = new CaptureWriter.Observer() {
      public Path create(String address, Instant startedAt) { return directory.resolve("timing.wpilog"); }
      public void value(CaptureWriter.Session session, org.triplehelix.wpilogmcp.log.EntryInfo entry, ValueFrame frame, WpilogOutput.Written written) {
        loop.elapse(7_000);
      }
      public void publication(CaptureWriter.Session session, long receivedUs, long publishedUs) {
        times.add(receivedUs); times.add(publishedUs);
      }
    };
    var manager = org.triplehelix.wpilogmcp.log.LogManager.getInstance();
    // An empty grant list admits every path, but a test that ran earlier in this JVM may have
    // granted its own folder, and the capture here would then be refused.
    var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(directory);
    var index = new CaptureIndex(placement, manager, 10_000, () -> null, measurements::add);
    var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, index);
    try {
      connect(writer, 90_000_000, 0); loop.elapse(3_000);
      writer.value(TOPIC, new ValueFrame(17, 1, 2, 42L), 1_000);
      assertEquals(List.of(1_000L, 10_000L), times, "9 ms includes the 2 ms wait and 7 ms publication; the sample remains 1 us");
      assertEquals(1, measurements.size()); assertEquals(9.0, measurements.get(0).receiveToPublicationMs());
      assertEquals(WALL.instant().toString(), measurements.get(0).recordingStartedAt());
      assertFalse(measurements.get(0).writerIdentity().isBlank());
    } finally {
      writer.close(); manager.release(directory);
      manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
    }
  }

  @Test void specificationBytesAndOffsetsIncludeNoNativeWriter() throws Exception {
    var path = directory.resolve("bytes.wpilog");
    try (var writer = new WpilogOutput(path)) {
      int id = writer.start("test", "int64", "", 1_000_000);
      var written = writer.append(id, 1_000_000, WpilogOutput.payload(2, 3L));
      assertEquals(44, written.offset()); assertEquals(14, written.size()); assertEquals(50, written.payloadOffset());
      writer.setMetadata(id, "{\"source\":\"NT\"}", 1_000_000);
      assertEquals(30, WpilogOutput.metadataSize("{\"source\":\"NT\"}", 1_000_000));
      writer.finish(id, 1_000_000); writer.flush();
      assertArrayEquals(HexFormat.of().parseHex("5750494c4f47000100000000"
          + "20001a40420f0001000000040000007465737405000000696e74363400000000"
          + "20010840420f0300000000000000"
          + "20001840420f02010000000f0000007b22736f75726365223a224e54227d"
          + "20000540420f0101000000"), Files.readAllBytes(path));
      assertTrue(new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(path))).isValid());
    }
  }

  /**
   * A closed file is never written again, since the store may have published its hash: the same
   * boot's reconnect continues its session in the next file, as a rollover does.
   */
  @Test void continuingClockContinuesTheSessionInItsNextFileButResetAndExcessiveDriftStartNewSessions() throws Exception {
    var loop = new ManualScheduler(); var paths = new ArrayList<Path>();
    var opened = new ArrayList<Path>(); var sessions = new ArrayList<CaptureWriter.Session>();
    var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant startedAt) {
        assertEquals("127.0.0.1", address); assertEquals(WALL.instant(), startedAt);
        var path = directory.resolve(paths.size() + ".wpilog"); paths.add(path); return path;
      }
      @Override public void opened(CaptureWriter.Session session) { opened.add(session.path()); sessions.add(session); }
    });
    connect(writer, 100_000_000, 0);
    writer.value(TOPIC, new ValueFrame(17, 1, 2, 3L), 0); // Retained data does not decide boot identity.
    writer.disconnected();
    byte[] closed = Files.readAllBytes(paths.get(0));
    connect(writer, 102_000_000, 2_000_000);
    writer.value(TOPIC, new ValueFrame(17, 102_000_000, 2, 4L), 2_000_000); writer.disconnected();
    connect(writer, 1_000_000, 3_000_000); writer.disconnected();
    connect(writer, 30_000_000, 4_000_000); writer.disconnected();
    var next = paths.get(0).resolveSibling("capture-2.wpilog");
    assertEquals(List.of(paths.get(0), next, paths.get(1), paths.get(2)), opened, "Only a reset or drift asks for a new session");
    assertSame(sessions.get(0), sessions.get(1), "The continuing clock keeps its session");
    assertNotSame(sessions.get(1), sessions.get(2)); assertNotSame(sessions.get(2), sessions.get(3));
    assertArrayEquals(closed, Files.readAllBytes(paths.get(0)), "A closed file is never written again");
    assertEquals(List.of(3L), org.triplehelix.wpilogmcp.fixtures.CaptureRecords.integers(paths.get(0)));
    assertEquals(List.of(4L), org.triplehelix.wpilogmcp.fixtures.CaptureRecords.integers(next));
    for (var file : List.of(paths.get(0), next)) {
      var reader = new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file)));
      var ids = new ArrayList<Integer>(); var clocks = new ArrayList<Integer>();
      for (var record : reader) {
        if (record.isStart()) (record.getStartData().name.equals(org.triplehelix.wpilogmcp.log.WallClock.PIT_CLOCK_ENTRY) ? clocks : ids).add(record.getStartData().entry);
      }
      // Each file declares its entries from id 1, the recorder's clock at its first reading.
      assertEquals(List.of(1), ids, file.toString()); assertEquals(List.of(2), clocks, file.toString());
    }
  }

  @Test void serviceForwardsPropertiesWithoutReplacingRecorderProvenance() throws Exception {
    var loop = new ManualScheduler(); var file = directory.resolve("properties.wpilog");
    var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return file; }
    });
    var listener = CaptureService.listener(writer, new org.triplehelix.wpilogmcp.capture.pull.PullGate(loop::nowUs, 0));
    var properties = new JsonObject(); properties.addProperty("source", "publisher"); properties.addProperty("unit", "initial");
    listener.connected(URI.create("ws://127.0.0.1/nt/test"), "networktables.first.wpi.edu");
    listener.timeSync(1_000_000, 0); listener.announce(new Announce("/x", 17, "int", null, properties));
    var patch = new JsonObject(); patch.add("source", com.google.gson.JsonNull.INSTANCE); patch.addProperty("unit", "changed");
    listener.properties(new org.triplehelix.wpilogmcp.nt4.ControlMessage.Properties("/x", null, patch));
    listener.disconnected();
    int updates = 0;
    for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file)))) {
      if (!record.isSetMetadata()) continue;
      updates++;
      var metadata = com.google.gson.JsonParser.parseString(record.getSetMetadataData().metadata).getAsJsonObject();
      assertEquals("nt4", metadata.get("source").getAsString()); assertEquals("127.0.0.1", metadata.get("robot").getAsString());
      assertEquals("changed", metadata.getAsJsonObject("nt4_properties").get("unit").getAsString());
      assertFalse(metadata.getAsJsonObject("nt4_properties").has("source"));
    }
    assertEquals(1, updates);
  }

  @Test void exclusionThinningMetadataFlushAndFinishAreExplicit() throws Exception {
    var loop = new ManualScheduler(); var flushes = new AtomicInteger(); var closed = new AtomicInteger();
    var reports = new AtomicInteger();
    var writer = new CaptureWriter(WALL, loop, new CapturePolicy(List.of("/skip"), Map.of("/", 2_000_000L, "/x", 1_000_000L)),
        new CaptureWriter.Observer() {
          @Override public Path create(String address, Instant startedAt) { return directory.resolve("policy.wpilog"); }
          @Override public void flushed(CaptureWriter.Session session) { flushes.incrementAndGet(); }
          @Override public void closed(CaptureWriter.Session session) { closed.incrementAndGet(); assertFalse(session.open()); }
          @Override public void cost(String topic, TopicCost.Snapshot cost) {
            reports.incrementAndGet(); assertEquals("/x", topic); assertEquals(2, cost.records());
          }
        });
    connect(writer, 1_000_000, 0);
    writer.announce(new Announce("/skip/x", 18, "int", null, new JsonObject()));
    for (long time : List.of(0L, 999_999L, 1_000_000L)) writer.value(TOPIC, new ValueFrame(17, time, 2, time), time);
    writer.invalidValue(TOPIC, 2);
    writer.value(TOPIC, new ValueFrame(17, 2_000_000, 1, 1.0), 2_000_000);
    loop.advance(249_999); assertEquals(0, flushes.get()); loop.advance(1); loop.until(() -> flushes.get() == 1); assertEquals(1, flushes.get());
    loop.advance(CaptureWriter.REPORT_PERIOD_US - CaptureWriter.FLUSH_PERIOD_US - 1); assertEquals(0, reports.get());
    loop.advance(CaptureWriter.FLUSH_PERIOD_US); loop.until(() -> reports.get() == 1); assertEquals(1, reports.get());
    int flushedBeforeClose = flushes.get();
    writer.unannounce(new Unannounce("/x", 17)); writer.disconnected();
    assertEquals(2, writer.rejectedValues()); assertEquals(1, closed.get());
    loop.advance(1_000_000); assertEquals(flushedBeforeClose, flushes.get()); assertEquals(1, reports.get());
    var starts = new ArrayList<String>();
    for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(writer.session().path())))) {
      // The recorder's own clock is no topic, so it has no period of its own.
      if (!record.isStart() || record.getStartData().name.equals(org.triplehelix.wpilogmcp.log.WallClock.PIT_CLOCK_ENTRY)) continue;
      starts.add(record.getStartData().name); assertTrue(record.getStartData().metadata.contains("\"period_sec\":1.0"));
    }
    assertEquals(List.of("NT:/x"), starts); assertEquals(List.of(0L, 1_000_000L), org.triplehelix.wpilogmcp.fixtures.CaptureRecords.integers(writer.session().path()));
  }

  @Test void rolloverFinishesAndRedeclaresEntriesWithoutLosingAValue() throws Exception {
    var files = new ArrayList<Path>(); var loop = new ManualScheduler();
    var observer = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return directory.resolve("capture.wpilog"); }
      @Override public void opened(CaptureWriter.Session session) { files.add(session.path()); }
    };
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, observer, 512)) {
      connect(writer, 10_000_000, 0);
      var second = new Announce("/y", 18, "string", null, new JsonObject()); writer.announce(second);
      for (int i = 0; i < 100; i++) {
        writer.value(TOPIC, new ValueFrame(17, 10_000_000 + i, 2, (long) i), i);
        writer.value(second, new ValueFrame(18, 10_000_000 + i, 4, "value-" + i), i);
      }
    }
    assertTrue(files.size() > 2, "The small bound must roll more than once");
    var ints = new ArrayList<Long>(); var strings = new ArrayList<String>();
    for (int file = 0; file < files.size(); file++) {
      var path = files.get(file);
      assertEquals(file == 0 ? "capture.wpilog" : "capture-" + (file + 1) + ".wpilog", path.getFileName().toString());
      assertTrue(Files.size(path) <= 512, path.toString());
      var reader = new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(path)));
      assertTrue(reader.isValid());
      var names = new java.util.LinkedHashMap<Integer, String>(); var finishes = new ArrayList<Integer>();
      // Use record boundaries: the wpiutil iterator omits a short last finish record.
      long pos = edu.wpi.first.util.datalog.DataLogAccess.firstRecordOffset(path);
      while (pos < Files.size(path)) {
        var record = edu.wpi.first.util.datalog.DataLogAccess.getRecord(reader, pos);
        pos = edu.wpi.first.util.datalog.DataLogAccess.recordEnd(reader, pos);
        assertTrue(pos > 0);
        if (record.isStart()) names.put(record.getStartData().entry, record.getStartData().name);
        else if (record.isFinish()) finishes.add(record.getFinishEntry());
        else if (!record.isControl()) {
          if (names.get(record.getEntry()).equals(org.triplehelix.wpilogmcp.log.WallClock.PIT_CLOCK_ENTRY)) continue;
          if (names.get(record.getEntry()).equals("NT:/x")) { assertEquals(10_000_000 + ints.size(), record.getTimestamp()); ints.add(record.getInteger()); }
          else { assertEquals(10_000_000 + strings.size(), record.getTimestamp()); strings.add(record.getString()); }
        }
      }
      // A file that took a clock reading declares the recorder's clock too, and finishes it like the rest.
      assertEquals(List.of("NT:/x", "NT:/y"), names.values().stream().filter(name -> !name.equals(org.triplehelix.wpilogmcp.log.WallClock.PIT_CLOCK_ENTRY)).toList());
      assertEquals(List.copyOf(names.keySet()), finishes);
    }
    assertEquals(java.util.stream.LongStream.range(0, 100).boxed().toList(), ints);
    assertEquals(java.util.stream.IntStream.range(0, 100).mapToObj(i -> "value-" + i).toList(), strings);
  }

  @Test void outputFailureEndsRecordingUntilANewRobotClockWithoutEscapingTheListener() throws Exception {
    var paths = new ArrayList<Path>(); var closes = new AtomicInteger();
    var observer = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) {
        var path = directory.resolve("failure-" + paths.size() + ".wpilog"); paths.add(path); return path;
      }
      @Override public void closed(CaptureWriter.Session session) { closes.incrementAndGet(); }
    };
    var opens = new AtomicInteger();
    try (var writer = new CaptureWriter(WALL, new ManualScheduler(), CapturePolicy.ALL, observer, 1024,
        (path, id, resume) -> new WpilogOutput(path, id, resume) {
          final boolean fail = opens.getAndIncrement() == 0;
          int records;
          /** The recorder's clock entry: the planted failure counts the robot's values alone. */
          int clock = -1;
          @Override public int start(String name, String type, String metadata, long timestampUs) throws java.io.IOException {
            int started = super.start(name, type, metadata, timestampUs);
            if (name.equals(org.triplehelix.wpilogmcp.log.WallClock.PIT_CLOCK_ENTRY)) clock = started;
            return started;
          }
          @Override public Written append(int entry, long time, byte[] payload) throws java.io.IOException {
            if (fail && entry != clock && records++ == 2) throw new java.io.IOException("planted disk full");
            return super.append(entry, time, payload);
          }
        })) {
      connect(writer, 10_000_000, 0);
      for (int i = 0; i < 20; i++) {
        long n = i;
        assertDoesNotThrow(() -> writer.value(TOPIC, new ValueFrame(17, 10_000_000 + n, 2, n), n));
      }
      assertFalse(writer.session().open()); assertEquals(1, closes.get()); assertEquals(1, paths.size());
      writer.disconnected(); connect(writer, 11_000_000, 1_000_000);
      writer.value(TOPIC, new ValueFrame(17, 11_000_000, 2, 42L), 1_000_000);
      assertEquals(1, paths.size()); assertFalse(writer.session().open());
      writer.disconnected(); connect(writer, 100_000, 2_000_000);
      writer.value(TOPIC, new ValueFrame(17, 100_000, 2, 43L), 2_000_000);
      assertTrue(writer.session().open()); assertEquals(2, paths.size());
    }
    assertEquals(2, closes.get());
    var first = new ArrayList<Long>();
    first.addAll(org.triplehelix.wpilogmcp.fixtures.CaptureRecords.integers(paths.get(0)));
    assertEquals(List.of(0L, 1L), first);
  }

  @Test void partialRecordFailureLeavesOnlyTheCompletedPrefix() throws Exception {
    var path = directory.resolve("partial.wpilog");
    var armed = new java.util.concurrent.atomic.AtomicBoolean(); var pieces = new AtomicInteger();
    try (var output = new WpilogOutput(path) {
      @Override protected void write(ByteBuffer bytes) throws java.io.IOException {
        if (armed.get() && pieces.incrementAndGet() == 2) {
          bytes.limit(bytes.position() + 2); super.write(bytes); armed.set(false);
          throw new java.io.IOException("planted partial payload");
        }
        super.write(bytes);
      }
    }) {
      int id = output.start("x", "int64", "", 1_000_000);
      output.append(id, 1_000_000, WpilogOutput.payload(2, 1L));
      byte[] complete = Files.readAllBytes(path);
      armed.set(true);
      assertEquals("planted partial payload", assertThrows(java.io.IOException.class,
          () -> output.append(id, 2_000_000, WpilogOutput.payload(2, 2L))).getMessage());
      assertArrayEquals(complete, Files.readAllBytes(path), "No finish record may follow an incomplete payload");
      assertEquals(complete.length, output.size());
      output.finish(id, 1_000_000);
    }
  }

  @Test void aRecordThatCannotFitStopsOnceWithoutCreatingOversizedFiles() throws Exception {
    var files = new ArrayList<Path>();
    var observer = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return directory.resolve("capture.wpilog"); }
      @Override public void opened(CaptureWriter.Session session) { files.add(session.path()); }
    };
    try (var writer = new CaptureWriter(WALL, new ManualScheduler(), CapturePolicy.ALL, observer, 256)) {
      connect(writer, 10_000_000, 0);
      var large = new Announce("/large", 18, "raw", null, new JsonObject()); writer.announce(large);
      assertDoesNotThrow(() -> writer.value(large, new ValueFrame(18, 10_000_000, 5, new byte[1024]), 0));
      assertFalse(writer.session().open()); assertTrue(writer.session().endReason().contains("capture.max_file_bytes"));
      assertEquals(1, files.size()); assertTrue(Files.size(files.get(0)) <= 256);
      assertFalse(Files.exists(directory.resolve("capture-2.wpilog")));
    }
  }

  @Test void aFlushFailureClosesOnceAndStopsTheFlushTimer() throws Exception {
    var armed = new java.util.concurrent.atomic.AtomicBoolean(); var closes = new AtomicInteger();
    var flushes = new AtomicInteger(); var loop = new ManualScheduler();
    var observer = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return directory.resolve("flush.wpilog"); }
      @Override public void closed(CaptureWriter.Session session) { closes.incrementAndGet(); }
    };
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, observer, 4096,
        (path, id, resume) -> new WpilogOutput(path, id, resume) {
          @Override public void flush() throws java.io.IOException {
            flushes.incrementAndGet();
            if (armed.getAndSet(false)) throw new java.io.IOException("planted force failure");
            super.flush();
          }
        })) {
      connect(writer, 10_000_000, 0); writer.value(TOPIC, new ValueFrame(17, 10_000_000, 2, 1L), 0);
      armed.set(true); assertDoesNotThrow(() -> loop.advance(250_000)); loop.until(() -> !writer.session().open());
      assertEquals("Capture write failed: planted force failure", writer.session().endReason());
      assertFalse(writer.session().open()); assertEquals(1, closes.get());
      int stoppedAt = flushes.get(); loop.advance(100_000_000);
      assertEquals(stoppedAt, flushes.get()); assertEquals(1, closes.get());
    }
  }

  @Test void aFailedNewCreateCannotOverwriteThePreviousSessionsReason() throws Exception {
    var creates = new AtomicInteger(); var closes = new AtomicInteger();
    var observer = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) throws java.io.IOException {
        if (creates.getAndIncrement() > 0) throw new java.io.IOException("planted create permission failure");
        return directory.resolve("previous.wpilog");
      }
      @Override public void closed(CaptureWriter.Session session) { closes.incrementAndGet(); }
    };
    try (var writer = new CaptureWriter(WALL, new ManualScheduler(), CapturePolicy.ALL, observer)) {
      connect(writer, 10_000_000, 0); writer.disconnected();
      var previous = writer.session();
      assertDoesNotThrow(() -> connect(writer, 100_000, 1_000_000));
      assertNull(previous.endReason()); assertEquals(1, closes.get()); assertNull(writer.session());
      writer.announce(TOPIC); assertEquals(2, creates.get());
    }
  }

  @Test void costUsesABoundedMinuteAndCountsDropsSeparately() {
    var cost = new TopicCost(); cost.add(0, 10); cost.add(59_000_000, 20); cost.drop(); cost.thin();
    assertEquals(new TopicCost.Snapshot(2, 30, 1, 1, 2 / 60.0, 0.5), cost.snapshot(59_000_000));
    assertEquals(new TopicCost.Snapshot(2, 30, 1, 1, 1 / 60.0, 20 / 60.0), cost.snapshot(60_000_000));
    assertEquals(0, cost.snapshot(119_000_000).recordsPerSecond());
  }

  @Test void allPayloadFamiliesMatchHandEncodedLittleEndianBytes() {
    record Case(int code, Object value, String hex) {}
    for (var c : List.of(new Case(0, true, "01"), new Case(1, 1.5, "000000000000f83f"),
        new Case(2, -2L, "feffffffffffffff"), new Case(3, 1.5f, "0000c03f"),
        new Case(4, "é", "c3a9"), new Case(5, new byte[] {0, -1}, "00ff"),
        new Case(16, List.of(true, false), "0100"), new Case(17, List.of(1.5), "000000000000f83f"),
        new Case(18, List.of(-2L), "feffffffffffffff"), new Case(19, List.of(1.5f), "0000c03f"),
        new Case(20, List.of("é", ""), "0200000002000000c3a900000000"))) {
      assertArrayEquals(HexFormat.of().parseHex(c.hex()), WpilogOutput.payload(c.code(), c.value()), "code " + c.code());
    }
    assertThrows(IllegalArgumentException.class, () -> WpilogOutput.payload(6, 0));
  }

  @Test void wideHeadersNegativeTimeAndExistingFileArePreserved() throws Exception {
    var path = directory.resolve("wide.wpilog");
    try (var writer = new WpilogOutput(path, 300, false)) {
      int id = writer.start("wide", "raw", "", 0);
      assertEquals(300, id);
      var written = writer.append(id, -1, new byte[256]); writer.flush();
      byte[] bytes = Files.readAllBytes(path);
      assertArrayEquals(HexFormat.of().parseHex("752c010001ffffffffffffffff"),
          java.util.Arrays.copyOfRange(bytes, (int) written.offset(), (int) written.payloadOffset()));
      assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> new WpilogOutput(path));
      assertThrows(IllegalArgumentException.class, () -> writer.append(0, 0, new byte[0]));
    }
  }
}
