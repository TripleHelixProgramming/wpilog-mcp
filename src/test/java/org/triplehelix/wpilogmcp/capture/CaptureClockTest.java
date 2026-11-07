/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.WallClock;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.read.UtcTimeMapping;

/**
 * A capture records this server's clock beside the robot's values, so a robot that publishes no
 * clock can still be placed in UTC: a reading at each time synchronization, taken at the next
 * value's time, never forwarded, and never the reason a file rolls over.
 */
class CaptureClockTest {
  @TempDir Path directory;
  /** The server's wall clock at the scheduler's zero: 2026-03-07T14:22:33Z. */
  private static final long W = 1_772_893_353_000_000L;
  private static final Announce TOPIC = new Announce("/x", 17, "int", null, new JsonObject());

  private record Reading(long robotUs, long epochUs) {}

  /** A wall clock that moves with the scheduler, as a real one moves with time. */
  private static Clock moving(ManualScheduler loop) {
    return new Clock() {
      @Override public ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(ZoneId zone) { return this; }
      @Override public Instant instant() { return Instant.EPOCH.plusNanos(Math.multiplyExact(W + loop.nowUs(), 1000)); }
    };
  }

  private static List<Reading> clock(Path file) throws Exception {
    var out = new ArrayList<Reading>();
    int id = -1;
    for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file)))) {
      if (record.isStart() && record.getStartData().name.equals(WallClock.PIT_CLOCK_ENTRY)) {
        assertEquals("int64", record.getStartData().type);
        id = record.getStartData().entry;
      } else if (!record.isControl() && record.getEntry() == id) {
        out.add(new Reading(record.getTimestamp(), record.getInteger()));
      }
    }
    return out;
  }

  private static void value(CaptureWriter writer, long robotUs, long value) {
    writer.value(TOPIC, new ValueFrame(17, robotUs, 2, value), 0);
  }

  @Test
  void eachSynchronizationPlacesTheNextValueOnThisServersClock() throws Exception {
    var loop = new ManualScheduler();
    var path = directory.resolve("clock.wpilog");
    var forwarded = new ArrayList<String>();
    try (var writer = new CaptureWriter(moving(loop), loop, CapturePolicy.ALL, new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return path; }
    })) {
      writer.onContext((name, type, value, time, metadata) -> forwarded.add(name));
      writer.connected(URI.create("ws://127.0.0.1:5810/nt/test"), "networktables.first.wpi.edu");
      writer.timeSync(10_000_000, 0);
      writer.announce(TOPIC);
      value(writer, 10_500_000, 1);
      value(writer, 10_600_000, 2);
      loop.advance(3_000_000);
      // Handled 100 ms after its reply arrived: the reading is the clock at the arrival, W + 2.9 s.
      writer.timeSync(13_000_000, 2_900_000);
      value(writer, 13_200_000, 3);
      value(writer, 13_300_000, 4);
      loop.advance(400_000);
      writer.timeSync(13_400_000, 3_400_000);
      // Within a second of the last reading: the next one waits for a value a second later.
      value(writer, 13_500_000, 5);
      value(writer, 14_300_000, 6);
      writer.disconnected();
    }
    assertEquals(List.of(new Reading(10_500_000, W + 500_000), new Reading(13_200_000, W + 3_100_000),
        new Reading(14_300_000, W + 4_300_000)), clock(path));
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), org.triplehelix.wpilogmcp.fixtures.CaptureRecords.integers(path));
    assertFalse(forwarded.contains(WallClock.PIT_CLOCK_ENTRY), "the clock measures this recorder alone: " + forwarded);
    var manager = LogManager.getInstance();
    manager.addAllowedDirectory(directory.toRealPath());
    try (var use = manager.acquire(path.toString())) {
      var log = use.log();
      assertEquals(10.5, log.minTimestamp(), "readings fall on values' times, so the range is the robot's");
      assertEquals(14.3, log.maxTimestamp());
      var mapping = log.utcMapping();
      assertEquals(UtcTimeMapping.PIT_CLOCK, mapping.basis().clock());
      assertEquals(W + 500_000, mapping.at(10_500_000).epochMicros());
      // Between (10.5 s, W + 0.5 s) and (13.2 s, W + 3.1 s): W + 0.5 s + 1.5 s * 2.6 / 2.7, rounded.
      assertEquals(W + 1_944_444, mapping.at(12_000_000).epochMicros(), "interpolated between the first two readings");
      assertEquals("Before the first valid wall-clock reading", mapping.at(10_499_999).reason());
    } finally {
      manager.unloadLog(path.toString());
    }
  }

  /** Files too small for a reading beside the next value roll over on values alone: no file starts with a reading. */
  @Test
  void aReadingNeverRollsAFileOver() throws Exception {
    var loop = new ManualScheduler();
    var files = new ArrayList<Path>();
    try (var writer = new CaptureWriter(moving(loop), loop, CapturePolicy.ALL, new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return directory.resolve("small.wpilog"); }
      // A rollover names its own next file; each one opens here.
      @Override public void opened(CaptureWriter.Session session) { files.add(session.path()); }
    }, 512)) {
      writer.connected(URI.create("ws://127.0.0.1:5810/nt/test"), "networktables.first.wpi.edu");
      writer.timeSync(10_000_000, 0);
      writer.announce(TOPIC);
      for (int i = 0; i < 120; i++) {
        loop.advance(1_100_000);
        writer.timeSync(10_000_000 + 1_100_000L * (i + 1), loop.nowUs());
        value(writer, 10_000_000 + 1_100_000L * (i + 1) + 50_000, i);
      }
      writer.disconnected();
    }
    assertTrue(files.size() > 3, "the small files roll over: " + files.size());
    long readings = 0;
    for (var file : files) {
      var first = true;
      int clockId = -1;
      for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file)))) {
        if (record.isStart() && record.getStartData().name.equals(WallClock.PIT_CLOCK_ENTRY)) clockId = record.getStartData().entry;
        if (record.isControl()) continue;
        if (first) assertTrue(record.getEntry() != clockId, file + " starts with a clock reading");
        first = false;
      }
      var values = org.triplehelix.wpilogmcp.fixtures.CaptureRecords.robot(file).stream().map(r -> r.getTimestamp()).toList();
      for (var reading : clock(file)) assertTrue(values.contains(reading.robotUs()), file + ": a reading at a value's time");
      readings += clock(file).size();
    }
    assertTrue(readings > 0, "the files that had room took readings");
    assertEquals(120, files.stream().mapToLong(file -> {
      try { return org.triplehelix.wpilogmcp.fixtures.CaptureRecords.robot(file).size(); }
      catch (Exception e) { throw new AssertionError(e); }
    }).sum(), "no value is lost to a reading");
  }

  /**
   * A close that fails partway leaves the clock entry unfinished; the recording that starts after
   * a new robot clock declares its own clock entry, rather than writing under the failed file's.
   */
  @Test
  void aFailedCloseLeavesTheNextFileItsOwnClockEntry() throws Exception {
    var loop = new ManualScheduler();
    var files = new ArrayList<Path>();
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    try (var writer = new CaptureWriter(moving(loop), loop, CapturePolicy.ALL, new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return directory.resolve("failed-close-" + files.size() + ".wpilog"); }
      @Override public void opened(CaptureWriter.Session session) { files.add(session.path()); }
    }, 1 << 20, (path, id, resume) -> new WpilogOutput(path, id, resume) {
      final boolean first = opens.getAndIncrement() == 0;
      @Override public void finish(int entry, long timestampUs) throws java.io.IOException {
        if (first) throw new java.io.IOException("planted finish failure");
        super.finish(entry, timestampUs);
      }
    })) {
      writer.connected(URI.create("ws://127.0.0.1:5810/nt/test"), "networktables.first.wpi.edu");
      writer.timeSync(10_000_000, 0);
      writer.announce(TOPIC);
      value(writer, 10_500_000, 1);
      writer.disconnected();
      // A robot that rebooted: a new clock, a new session and a new file.
      loop.advance(1_000_000);
      writer.connected(URI.create("ws://127.0.0.1:5810/nt/test"), "networktables.first.wpi.edu");
      writer.timeSync(200_000, 1_000_000);
      writer.announce(TOPIC);
      value(writer, 700_000, 2);
      writer.disconnected();
    }
    assertEquals(2, files.size(), files.toString());
    assertEquals(List.of(new Reading(700_000, W + 1_500_000)), clock(files.get(1)),
        "the second file declares its own clock entry and records the reading under it");
  }

  /** A pit gateway forwards its own clock to a laptop recording it: the laptop records its own. */
  @Test
  void anUpstreamRecordersClockIsNotRecorded() throws Exception {
    var loop = new ManualScheduler();
    var path = directory.resolve("upstream.wpilog");
    try (var writer = new CaptureWriter(moving(loop), loop, CapturePolicy.ALL, new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return path; }
    })) {
      writer.connected(URI.create("ws://127.0.0.1:5810/nt/test"), "networktables.first.wpi.edu");
      writer.timeSync(10_000_000, 0);
      var context = new JsonObject();
      context.addProperty("source", "time_sync");
      var upstream = new Announce(WallClock.PIT_CLOCK_ENTRY, 18, "int", null, org.triplehelix.wpilogmcp.nt4.ContextTopic.properties(context));
      writer.announce(TOPIC);
      writer.announce(upstream);
      writer.value(upstream, new ValueFrame(18, 10_100_000, 2, 1_000_000_000_000_000L), 0);
      value(writer, 10_500_000, 1);
      writer.disconnected();
    }
    long starts = 0;
    for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(path))))
      if (record.isStart() && record.getStartData().name.equals(WallClock.PIT_CLOCK_ENTRY)) starts++;
    assertEquals(1, starts, "one clock entry, this recorder's");
    assertEquals(List.of(new Reading(10_500_000, W + 500_000)), clock(path));
  }
}
