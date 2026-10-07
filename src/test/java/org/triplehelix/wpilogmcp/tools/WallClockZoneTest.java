/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.WallClock;

/**
 * The zone of the clock that named a log, from the log itself: filename times are local to the
 * clock that wrote them (UTC on a roboRIO, local on a desktop), wall-clock entries are epoch time.
 */
@DisplayName("WallClock filename zone")
class WallClockZoneTest {

  @Test void capturedSystemTimeRetainsItsCalendarRole() {
    var original = log("FRC_20260321_162949.wpilog", "systemTime", Q10_EPOCH_MICROS, 30);
    var captured = log("capture.wpilog", "NT:systemTime", Q10_EPOCH_MICROS, 30);
    assertEquals(WallClock.first(original), WallClock.first(captured));
    assertEquals(WallClock.last(original), WallClock.last(captured));
  }

  /** 2026-03-21 16:29:49 UTC, q10's first wall-clock reading. */
  static final long Q10_EPOCH_MICROS =
      LocalDateTime.of(2026, 3, 21, 16, 29, 49).toEpochSecond(ZoneOffset.UTC) * 1_000_000L;

  /** A log named {@code filename}, whose wall clock reads from {@code epochMicros} at log time 12 s. */
  static LogData log(String filename, String clockEntry, long epochMicros, double seconds) {
    var values = new ArrayList<TimestampedValue>();
    values.add(new TimestampedValue(1.0, 0L)); // before the clock is set: 1970
    for (double t = 12.0; t <= 12.0 + seconds; t += 1.0) {
      values.add(new TimestampedValue(t, epochMicros + Math.round((t - 12.0) * 1e6)));
    }
    return new MockLogBuilder().setPath("/logs/" + filename)
        .addEntry(clockEntry, "int64", values).build();
  }

  @Test
  @DisplayName("filename times: AdvantageKit, DataLogManager, and REV names; none or invalid")
  void filenameTimes() {
    assertEquals(Optional.of(LocalDateTime.of(2026, 3, 21, 16, 29, 56)),
        WallClock.filenameTime("akit_26-03-21_16-29-56_vache_q10.wpilog"));
    assertEquals(Optional.of(LocalDateTime.of(2026, 9, 30, 0, 10, 26)),
        WallClock.filenameTime("akit_26-09-30_00-10-26.wpilog"));
    assertEquals(Optional.of(LocalDateTime.of(2025, 3, 4, 1, 2, 3)),
        WallClock.filenameTime("FRC_20250304_010203.wpilog"));
    assertEquals(Optional.of(LocalDateTime.of(2026, 3, 21, 16, 29, 32)),
        WallClock.filenameTime("REV_20260321_162932_canivore.revlog"));
    assertTrue(WallClock.filenameTime("2026-akit_match.wpilog").isEmpty());
    assertTrue(WallClock.filenameTime("FRC_20251304_010203.wpilog").isEmpty(), "month 13");
    assertTrue(WallClock.filenameTime("akit_26-03-21_25-00-00.wpilog").isEmpty(), "hour 25");
  }

  @Test
  @DisplayName("a roboRIO names files in UTC: q10's name and EpochTimeMicros agree at UTC")
  void roborioUtc() {
    var log = log("akit_26-03-21_16-29-56_vache_q10.wpilog", "/SystemStats/EpochTimeMicros",
        Q10_EPOCH_MICROS, 150);
    assertEquals(Optional.of(ZoneOffset.UTC), WallClock.filenameOffset(log));
    var zone = WallClock.revlogFilenameZone(log);
    assertEquals(ZoneOffset.UTC, zone.offset());
    assertTrue(zone.basis().startsWith("UTC, the zone the wpilog's own filename"), zone.basis());
  }

  @Test
  @DisplayName("a desktop names files in its zone: UTC-04:00 and India's UTC+05:30")
  void desktopZones() {
    var edt = log("akit_26-03-21_12-29-56.wpilog", "/SystemStats/EpochTimeMicros",
        Q10_EPOCH_MICROS, 150);
    assertEquals(Optional.of(ZoneOffset.ofHours(-4)), WallClock.filenameOffset(edt));
    assertTrue(WallClock.revlogFilenameZone(edt).basis().startsWith("UTC-04:00"));
    var ist = log("FRC_20260321_215956.wpilog", "systemTime", Q10_EPOCH_MICROS, 150);
    assertEquals(Optional.of(ZoneOffset.ofHoursMinutes(5, 30)), WallClock.filenameOffset(ist));
  }

  @Test
  @DisplayName("a clock read before the Driver Station set it (a default date) is not used: "
      + "the readings after its last jump are")
  void unsetClockThenSet() {
    // As a real log: 2024-12-18 14:08 (the roboRIO's unset clock) for 33 minutes from FPGA 7.8,
    // then set to 2026-03-21 16:17:29 at FPGA 1988
    long unset = LocalDateTime.of(2024, 12, 18, 14, 8, 5).toEpochSecond(ZoneOffset.UTC)
        * 1_000_000L;
    long set = LocalDateTime.of(2026, 3, 21, 16, 17, 29).toEpochSecond(ZoneOffset.UTC)
        * 1_000_000L;
    var values = new ArrayList<TimestampedValue>();
    for (double t = 7.8; t < 1988; t += 10) {
      values.add(new TimestampedValue(t, unset + Math.round((t - 7.8) * 1e6)));
    }
    for (double t = 1988; t <= 2305; t += 10) {
      values.add(new TimestampedValue(t, set + Math.round((t - 1988) * 1e6)));
    }
    var log = new MockLogBuilder().setPath("/logs/akit_26-03-21_16-17-36_vache.wpilog")
        .addEntry("/SystemStats/EpochTimeMicros", "int64", values).build();
    var first = WallClock.first(log).orElseThrow();
    assertEquals(1988.0, first.logTime(), 1e-9);
    assertEquals(set, first.epochMicros());
    assertEquals(32, WallClock.validReadings(log).size());
    // The filename (named when the clock was set) agrees with the set clock: UTC
    assertEquals(Optional.of(ZoneOffset.UTC), WallClock.filenameOffset(log));
    // A clock never set has only its default-date readings: nothing jumps, all are "valid"
    var never = new MockLogBuilder().setPath("/logs/x.wpilog")
        .addEntry("/SystemStats/EpochTimeMicros", "int64", values.subList(0, 10)).build();
    assertEquals(10, WallClock.validReadings(never).size());
  }

  @Test
  @DisplayName("a clock is confirmed set by its filename time, by being set during the log (from "
      + "1970 or a default date), and not by one plausible date throughout in an unnamed log")
  void confirmed() {
    assertTrue(WallClock.confirmed(log("akit_26-03-21_16-29-56_vache_q10.wpilog",
        "/SystemStats/EpochTimeMicros", Q10_EPOCH_MICROS, 150)), "filename agrees");
    // log() starts with a 1970 reading at 1 s: set during the log
    assertTrue(WallClock.confirmed(log("match.wpilog", "systemTime", Q10_EPOCH_MICROS, 150)));
    // One date throughout, no filename time: AdvantageKit's placeholder name for a clock that
    // was never set
    var values = new ArrayList<TimestampedValue>();
    for (double t = 7.8; t < 300; t += 10) {
      values.add(new TimestampedValue(t, Q10_EPOCH_MICROS + Math.round(t * 1e6)));
    }
    var never = new MockLogBuilder().setPath("/logs/akit_cfb6568c35d66529.wpilog")
        .addEntry("/SystemStats/EpochTimeMicros", "int64", values).build();
    assertFalse(WallClock.confirmed(never));
    assertTrue(WallClock.unconfirmedReason(never).orElseThrow().contains("unset default"));
    // An unset clock that steps +127 s and then stands still (as a real log did) was not set
    long unset = LocalDateTime.of(2024, 12, 18, 14, 4, 32).toEpochSecond(ZoneOffset.UTC)
        * 1_000_000L;
    var stepping = new ArrayList<TimestampedValue>();
    for (double t = 11.2; t < 62.7; t += 5) {
      stepping.add(new TimestampedValue(t, unset + Math.round((t - 11.2) * 1e6)));
    }
    long stepped = unset + 178_000_000L; // 14:07:30
    stepping.add(new TimestampedValue(62.7, stepped));
    stepping.add(new TimestampedValue(199.9, stepped + 160_000L));
    stepping.add(new TimestampedValue(213.5, stepped + 13_800_000L));
    var steppingLog = new MockLogBuilder().setPath("/logs/akit_da009b002dba910f.wpilog")
        .addEntry("/SystemStats/EpochTimeMicros", "int64", stepping).build();
    assertFalse(WallClock.confirmed(steppingLog));
    // No wall clock at all: nothing to confirm, no reason given
    var none = new MockLogBuilder().setPath("/logs/x.wpilog")
        .addNumericEntry("/X", new double[] {0, 1}, new double[] {0, 1}).build();
    assertFalse(WallClock.confirmed(none));
    assertTrue(WallClock.unconfirmedReason(none).isEmpty());
  }

  @Test
  @DisplayName("no inference without both a filename time and a wall clock, or when they "
      + "do not describe the same moment: UTC, the roboRIO's default")
  void fallbacks() {
    var unnamed = log("2026-akit_match.wpilog", "systemTime", Q10_EPOCH_MICROS, 150);
    assertTrue(WallClock.filenameOffset(unnamed).isEmpty());
    var fallback = WallClock.revlogFilenameZone(unnamed);
    assertEquals(ZoneOffset.UTC, fallback.offset());
    assertTrue(fallback.basis().startsWith("UTC, the roboRIO's default zone"), fallback.basis());

    var noClock = new MockLogBuilder().setPath("/logs/akit_26-03-21_16-29-56.wpilog")
        .addNumericEntry("/X", new double[] {0, 1}, new double[] {0, 1}).build();
    assertTrue(WallClock.filenameOffset(noClock).isEmpty());

    // Named 10 minutes into a 2-minute log: no quarter-hour offset puts the name inside it
    var late = log("akit_26-03-21_16-39-49.wpilog", "systemTime", Q10_EPOCH_MICROS, 120);
    assertTrue(WallClock.filenameOffset(late).isEmpty());

    // More than 14 hours apart: not a zone
    var far = log("akit_26-03-23_16-29-56.wpilog", "systemTime", Q10_EPOCH_MICROS, 150);
    assertTrue(WallClock.filenameOffset(far).isEmpty());

    // A clock that is never set (all 1970) has no reading
    var unset = new MockLogBuilder().setPath("/logs/akit_26-03-21_16-29-56.wpilog")
        .addEntry("systemTime", "int64", java.util.List.of(new TimestampedValue(1.0, 5L)))
        .build();
    assertTrue(WallClock.first(unset).isEmpty());
    assertTrue(WallClock.filenameOffset(unset).isEmpty());
  }
}
