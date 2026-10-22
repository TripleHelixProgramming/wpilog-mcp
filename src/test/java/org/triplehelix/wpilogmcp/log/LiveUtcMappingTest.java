/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.read.UtcTimeMapping;

/**
 * A recording's UTC mapping is extended reading by reading, and gives at every prefix what a
 * fresh read of the same prefix gives: the same basis, the same refusal, the same instant for
 * every time, whichever view extended it and in whatever order the views ask.
 */
class LiveUtcMappingTest {
  /** 2026-01-01T00:00:00Z. */
  private static final long W = 1_767_225_600_000_000L;
  private static final String ROBOT = "NT:/AdvantageKit/SystemStats/EpochTimeMicros";
  @TempDir Path directory;

  private long offset = 12;

  private LiveLog recording(String name) throws Exception {
    var path = directory.resolve(name);
    Files.write(path, new byte[12]);
    return new LiveLog(path, 0, () -> null, UUID.randomUUID().toString(), OpenColumns.Placement.HEAP);
  }

  private void number(LiveLog live, EntryInfo entry, long timeUs, double value) throws Exception {
    live.append(entry, new ValueFrame(entry.id(), timeUs, 1, value), new WpilogOutput.Written(offset, 17, offset + 9, 8));
    offset += 17;
  }

  private void clock(LiveLog live, EntryInfo entry, long timeUs, long epochMicros) throws Exception {
    live.append(entry, new ValueFrame(entry.id(), timeUs, 2, epochMicros), new WpilogOutput.Written(offset, 17, offset + 9, 8));
    offset += 17;
  }

  /** Every time a panel could ask about: each sample's, between samples, before the first and past the last. */
  private static void assertSameMapping(UtcTimeMapping fresh, UtcTimeMapping extended, List<Long> times, String where) {
    assertEquals(fresh.basis(), extended.basis(), where + ": basis");
    assertEquals(fresh.unavailableReason(), extended.unavailableReason(), where + ": reason");
    for (long t : times) {
      assertEquals(fresh.at(t), extended.at(t), where + " at " + t);
      assertEquals(fresh.at(t + 7), extended.at(t + 7), where + " at " + (t + 7));
    }
    assertEquals(fresh.at(-1), extended.at(-1), where + " before the first");
    assertEquals(fresh.at(Long.MAX_VALUE / 2), extended.at(Long.MAX_VALUE / 2), where + " far past the last");
  }

  /** The rule as it was written before the accumulator, kept here as the oracle the accumulator must match. */
  private static List<WallClock.ExactReading> previousRule(List<TimestampedValue> values) {
    var valid = new ArrayList<WallClock.ExactReading>();
    double previousLogTime = 0;
    for (var value : values) {
      if (!(value.value() instanceof Number number)) continue;
      long epochMicros = number.longValue();
      if (!valid.isEmpty()) {
        var previous = valid.get(valid.size() - 1);
        double wallStep = (epochMicros - previous.epochMicros()) / 1e6;
        double fpgaStep = value.timestamp() - previousLogTime;
        if (Math.abs(wallStep - fpgaStep) > 60.0) valid.clear();
      }
      if (WallClock.plausible(epochMicros)) {
        valid.add(new WallClock.ExactReading(value.timestampUs(), epochMicros));
        previousLogTime = value.timestamp();
      }
    }
    return List.copyOf(valid);
  }

  /** Random clocks, unset, set, stepped either way, with gaps and readings that are not numbers, against the rule as it was. */
  @Test
  void theAccumulatorKeepsTheReadingsThePreviousRuleKept() {
    // A reading just under the 2015 floor is dropped without ending the run, and the step after
    // it is measured from the last reading kept: 128 s of wall clock over 70 s of log time differ
    // by 58 s, within the minute, so the run goes on.
    long floor = 1_420_070_400_000_000L;
    var nearFloor = List.of(TimestampedValue.atMicros(0, floor + 10_000_000L),
        TimestampedValue.atMicros(5_000_000, floor - 1_000_000L),
        TimestampedValue.atMicros(70_000_000, floor + 138_000_000L));
    var nearFloorReadings = new WallClock.ValidReadings();
    nearFloor.forEach(nearFloorReadings::add);
    assertEquals(previousRule(nearFloor).size(), nearFloorReadings.prefix(3).count());
    assertEquals(2, nearFloorReadings.prefix(3).count(), "the dropped reading does not move the time a step is measured from");
    var random = new Random(41);
    for (int run = 0; run < 60; run++) {
      var values = new ArrayList<TimestampedValue>();
      long time = random.nextInt(5_000_000), epoch = random.nextBoolean() ? 86_400_000_000L : W;
      for (int i = 0; i < 300; i++) {
        int event = random.nextInt(40);
        if (event == 0) epoch = W + random.nextInt(1_000_000_000);
        else if (event == 1) epoch -= 120_000_000L;
        else if (event == 2) epoch = 1_000_000L;
        else if (event == 3) time += 90_000_000L;
        long step = 20_000L + random.nextInt(3) * 10_000L;
        time += step;
        epoch += step + random.nextInt(41) - 20;
        values.add(TimestampedValue.atMicros(time, event == 4 ? (Object) "text" : (Object) epoch));
      }
      var accumulated = new WallClock.ValidReadings();
      for (int f = 0; f <= values.size(); f++) {
        if (f > 0) accumulated.add(values.get(f - 1));
        var expected = previousRule(values.subList(0, f));
        var kept = accumulated.prefix(f);
        assertEquals(expected.size(), kept.count(), "run " + run + ", prefix " + f);
        for (int i = 0; i < expected.size(); i++)
          assertEquals(expected.get(i), new WallClock.ExactReading(kept.logMicros()[i], kept.epochMicros()[i]), "run " + run + ", prefix " + f + ", reading " + i);
      }
    }
  }

  /** Readings the accumulator keeps for any prefix equal those a read of that prefix keeps. */
  @Test
  void validReadingsOfEveryPrefixEqualAFreshRead() {
    var random = new Random(3);
    var values = new ArrayList<TimestampedValue>();
    long epoch = 1_000_000L;
    for (int i = 0; i < 400; i++) {
      long time = 1_000_000L + 20_000L * i;
      // Unset (1970), then set at 100, set again forward at 250, with jitter and a repeat now and then.
      if (i == 100) epoch = W;
      if (i == 250) epoch += 3_600_000_000L;
      epoch += i % 37 == 0 ? 0 : 20_000 + random.nextInt(9) - 4;
      values.add(TimestampedValue.atMicros(time, i % 53 == 7 ? (Object) "not a number" : (Object) epoch));
    }
    var accumulated = new WallClock.ValidReadings();
    var prefixes = new ArrayList<WallClock.Prefix>();
    for (int f = 0; f <= values.size(); f++) {
      if (f > 0) accumulated.add(values.get(f - 1));
      prefixes.add(accumulated.prefixOf(f).orElseThrow());
    }
    for (int f = 0; f <= values.size(); f++) {
      var fresh = WallClock.validExactReadings(new ParsedLog("prefix.wpilog",
          java.util.Map.of("systemTime", new EntryInfo(1, "systemTime", "int64", "")),
          java.util.Map.of("systemTime", values.subList(0, f)), 0, 10));
      var kept = prefixes.get(f);
      assertEquals(fresh.size(), kept.count(), "prefix " + f);
      for (int i = 0; i < fresh.size(); i++) {
        assertEquals(fresh.get(i), new WallClock.ExactReading(kept.logMicros()[i], kept.epochMicros()[i]), "prefix " + f + " reading " + i);
      }
    }
    // Only a prefix from before the latest jump has lost its run, and it says so.
    assertTrue(accumulated.prefixOf(250).isEmpty(), "the jump at value 250 began the current run");
    assertTrue(accumulated.prefixOf(251).isPresent());
  }

  /**
   * The clock is set partway, then stepped an hour forward, which begins a new run; views ask at
   * sampled prefixes, newest first, then older ones held meanwhile, from before the step too.
   */
  @Test
  void everyPrefixMapsAsAFreshReadOfIt() throws Exception {
    var live = recording("set.wpilog");
    var robot = new EntryInfo(1, ROBOT, "int64", "");
    var x = new EntryInfo(2, "NT:/x", "double", "");
    live.announce(robot);
    live.announce(x);
    var times = new ArrayList<Long>();
    var held = new ArrayList<LiveLog.View>();
    var random = new Random(9);
    long epoch = 86_400_000_000L;
    try {
      for (int i = 0; i < 6000; i++) {
        long time = 5_000_000L + 20_000L * i;
        if (i == 700) epoch = W;
        if (i == 3000) epoch += 3_600_000_000L;
        epoch += 20_000 + random.nextInt(5) - 2;
        clock(live, robot, time, epoch);
        number(live, x, time + 3_000, i * 0.5);
        times.add(time);
        times.add(time + 3_000);
        if (i % 997 == 0 || i == 699 || i == 700 || i == 2999 || i == 3000 || i == 5999) {
          held.add(live.retainView());
          var view = live.retainView();
          try (view) {
            assertSameMapping(UtcTimeMapping.from(view), view.utcMapping(), times, "newest view at " + i);
          }
        }
      }
      // Views taken before the readings were extended past them map their own prefixes.
      for (int k = held.size() - 1; k >= 0; k--) {
        var view = held.get(k);
        assertSameMapping(UtcTimeMapping.from(view), view.utcMapping(), times, "held view " + k);
      }
      try (var view = live.retainView()) {
        var mapping = view.utcMapping();
        assertEquals(UtcTimeMapping.ROBOT_CLOCK, mapping.basis().clock());
        assertEquals(3000, mapping.basis().readingCount(), "the step began the run the newest view maps");
        assertEquals("Before the first valid wall-clock reading", mapping.at(5_000_000L + 20_000L * 2999).reason());
      }
    } finally {
      for (var view : held) view.close();
      live.finish(); live.close();
    }
  }

  /** A robot clock never set leaves the recording to the server's clock, until the robot's is set. */
  @Test
  void theServersClockPlacesARecordingOnlyUntilTheRobotsClockIsSet() throws Exception {
    var live = recording("pit.wpilog");
    var robot = new EntryInfo(1, ROBOT, "int64", "");
    var pit = new EntryInfo(2, UtcTimeMapping.PIT_CLOCK_ENTRY, "int64", "{\"source\":\"time_sync\"}");
    live.announce(robot);
    live.announce(pit);
    var times = new ArrayList<Long>();
    try {
      for (int i = 0; i < 3000; i++) {
        long time = 1_000_000L + 20_000L * i;
        clock(live, robot, time, i < 2000 ? 1_000_000L + 20_000L * i : W + 20_000L * i);
        if (i % 50 == 0) {
          clock(live, pit, time + 1_000, W + 61_000_000L + 20_000L * i);
          times.add(time + 1_000);
        }
        times.add(time);
        if (i == 1999 || i == 2000) {
          try (var view = live.retainView()) {
            var mapping = view.utcMapping();
            assertSameMapping(UtcTimeMapping.from(view), mapping, times, "at " + i);
            assertEquals(i == 1999 ? UtcTimeMapping.PIT_CLOCK : UtcTimeMapping.ROBOT_CLOCK, mapping.basis().clock(), "at " + i);
            assertEquals(i == 1999 ? UtcTimeMapping.PIT_CLOCK_ENTRY : ROBOT, mapping.basis().entry(), "at " + i);
          }
        }
      }
    } finally {
      live.finish(); live.close();
    }
  }

  /** A robot clock that steps backwards is refused at once and from then on, extended or read afresh, and the server's clock does not stand in. */
  @Test
  void anAmbiguousClockIsRefusedBothWays() throws Exception {
    var live = recording("ambiguous.wpilog");
    var robot = new EntryInfo(1, ROBOT, "int64", "");
    var pit = new EntryInfo(2, UtcTimeMapping.PIT_CLOCK_ENTRY, "int64", "");
    live.announce(robot);
    live.announce(pit);
    var times = new ArrayList<Long>();
    try {
      for (int i = 0; i < 1200; i++) {
        long time = 1_000_000L + 20_000L * i;
        clock(live, robot, time, W + 20_000L * i - (i == 800 ? 30_000 : 0));
        clock(live, pit, time, W + 20_000L * i);
        times.add(time);
        if (i == 799 || i == 800 || i == 1199) {
          try (var view = live.retainView()) {
            var mapping = view.utcMapping();
            assertSameMapping(UtcTimeMapping.from(view), mapping, times, "at " + i);
            assertEquals(i < 800, mapping.unavailableReason().isEmpty(), "at " + i);
            assertEquals(UtcTimeMapping.ROBOT_CLOCK, mapping.basis().clock(), "at " + i);
          }
        }
      }
    } finally {
      live.finish(); live.close();
    }
  }

  /** Two hours of a 50 Hz clock: a later view extends the readings by the blocks added since, counted. */
  @Test
  void extendingTheMappingReadsOnlyTheClocksNewBlocks() throws Exception {
    var path = directory.resolve("long.wpilog");
    Files.write(path, new byte[12]);
    var live = new LiveLog(path, 0, () -> null, UUID.randomUUID().toString(), new OpenColumns.Placement() {
      @Override public java.util.Optional<Path> file(String identity) { return java.util.Optional.of(directory.resolve("long-open.col")); }
      @Override public boolean reserveShort() { return false; }
    });
    var robot = new EntryInfo(1, ROBOT, "int64", "");
    live.announce(robot);
    int readings = 2 * 3600 * 50;
    try {
      for (int i = 0; i < readings; i++) clock(live, robot, 1_000_000L + 20_000L * i, W + 20_000L * i);
      try (var view = live.retainView()) {
        assertEquals(readings, view.utcMapping().basis().readingCount());
      }
      for (int i = readings; i < readings + 600; i++) clock(live, robot, 1_000_000L + 20_000L * i, W + 20_000L * i);
      long before = live.columnBlocksRead();
      try (var view = live.retainView()) {
        var mapping = view.utcMapping();
        assertEquals(readings + 600, mapping.basis().readingCount());
        assertEquals(W + 20_000L * (readings + 599), mapping.at(1_000_000L + 20_000L * (readings + 599)).epochMicros());
      }
      long read = live.columnBlocksRead() - before;
      assertTrue(read <= 3, "blocks decoded to extend the mapping by 600 readings: " + read);
    } finally {
      live.finish(); live.close(); live.discard();
    }
  }
}
