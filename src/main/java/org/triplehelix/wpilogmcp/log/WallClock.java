/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The entry that maps a log's FPGA time to wall-clock time: WPILib's {@code systemTime}
 * (DataLogManager) or AdvantageKit's {@code /SystemStats/EpochTimeMicros}, both epoch
 * microseconds. Matched by exact name, not by substring.
 *
 * <p>Also the time zone of the clock that named a log's files. The roboRIO names files in its
 * own zone (UTC unless a team changes it), and a desktop running simulation in its local zone,
 * so a filename time is compared with a true wall-clock time only through the offset the log
 * itself shows: its filename time against its wall clock.
 *
 * @since 0.9.0
 */
public final class WallClock {

  private WallClock() {}

  private static final Set<String> TYPES = Set.of("int64", "double", "float");

  /** A plausible wall-clock reading: the log time it was logged at, and its epoch microseconds. */
  public record Reading(double logTime, long epochMicros) {}

  /**
   * The zone offset in which to read the times in REV log filenames recorded with a wpilog, and
   * the basis for it.
   */
  public record FilenameZone(ZoneOffset offset, String basis) {}

  /** AdvantageKit's _yy-MM-dd_HH-mm-ss, or DataLogManager's and REV's _yyyyMMdd_HHmmss. */
  private static final Pattern DASHED =
      Pattern.compile("_(\\d{2})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})(?:_|\\.)");
  private static final Pattern COMPACT =
      Pattern.compile("_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})(?:_|\\.)");

  /** Zone offsets are whole quarter hours. */
  private static final int QUARTER_HOUR_SEC = 900;

  /** No zone is further than 14 hours from UTC. */
  private static final int MAX_OFFSET_SEC = 14 * 3600;

  /** How far outside the wall clock's span a filename time may fall and still describe it. */
  private static final long SPAN_SLACK_MICROS = 60_000_000L;

  /** The wall-clock entry: {@code systemTime} first, then {@code EpochTimeMicros}. */
  public static Optional<String> entry(LogData log) {
    return log.entries().values().stream()
        .filter(e -> TYPES.contains(e.type()) && log.sampleCount(e.name()) > 0)
        .filter(e -> rank(e.name()) >= 0)
        .min(Comparator.comparingInt((EntryInfo e) -> rank(e.name()))
            .thenComparingInt(EntryInfo::id))
        .map(EntryInfo::name);
  }

  /** Epoch microseconds after 2015: before the roboRIO's clock is set it reads near 1970. */
  public static boolean plausible(long epochMicros) {
    return epochMicros > 1_420_070_400_000_000L;
  }

  /**
   * A wall clock that moves this much more (or less) than FPGA time between two readings was
   * changed there: readings before the change do not describe the same clock.
   */
  static final double JUMP_SEC = 60.0;

  /**
   * A forward jump this large is the clock being set (the Driver Station sets it when it
   * connects, from 1970 or the roboRIO's default date, months or decades behind); an unset
   * clock also steps by minutes, which is not a set.
   */
  static final double SET_JUMP_SEC = 86_400.0;

  /** The first valid wall-clock reading of the log (see {@link #validReadings}). */
  public static Optional<Reading> first(LogData log) {
    var valid = validReadings(log);
    return valid.isEmpty() ? Optional.empty() : Optional.of(valid.get(0));
  }

  /** The last valid wall-clock reading of the log. */
  public static Optional<Reading> last(LogData log) {
    var valid = validReadings(log);
    return valid.isEmpty() ? Optional.empty() : Optional.of(valid.get(valid.size() - 1));
  }

  /**
   * The wall-clock readings of the clock as set: the plausible readings after its last jump.
   * Before the Driver Station sets it, a roboRIO's clock reads 1970 or a fixed default date (a
   * real log read 2024-12-18 for 33 minutes, then jumped to 2026-03-21): those readings, and REV
   * log names from that time, place nothing in real time.
   */
  public static List<Reading> validReadings(LogData log) {
    var valid = new ArrayList<Reading>();
    for (var reading : readings(log)) {
      if (!valid.isEmpty()) {
        var previous = valid.get(valid.size() - 1);
        double wallStep = (reading.epochMicros() - previous.epochMicros()) / 1e6;
        double fpgaStep = reading.logTime() - previous.logTime();
        if (Math.abs(wallStep - fpgaStep) > JUMP_SEC) valid.clear();
      }
      if (plausible(reading.epochMicros())) valid.add(reading);
    }
    return valid;
  }

  /** Every numeric reading of the wall-clock entry, in log order (1970 included). */
  private static List<Reading> readings(LogData log) {
    var clock = entry(log);
    if (clock.isEmpty()) return List.of();
    var values = log.values().get(clock.get());
    if (values == null) return List.of();
    var out = new ArrayList<Reading>();
    for (var tv : values) {
      if (tv.value() instanceof Number num) out.add(new Reading(tv.timestamp(), num.longValue()));
    }
    return out;
  }

  /** Whether the clock was set during the log: a forward jump of more than a day. */
  static boolean setDuringLog(LogData log) {
    var all = readings(log);
    for (int i = 1; i < all.size(); i++) {
      var a = all.get(i - 1);
      var b = all.get(i);
      double jump = (b.epochMicros() - a.epochMicros()) / 1e6 - (b.logTime() - a.logTime());
      if (jump > SET_JUMP_SEC && plausible(b.epochMicros())) return true;
    }
    return false;
  }

  /**
   * Whether the log's wall clock is known to have been set: it was set during the log (a
   * forward jump of more than a day, from 1970 or the default date), or the log's filename time
   * agrees with it. AdvantageKit and DataLogManager name a log with its time once the clock is set (a
   * log whose clock never was keeps a placeholder name such as {@code akit_cfb6568c35d66529}), so
   * a clock that reads one plausible date throughout, in a log whose name carries no time, may be
   * the roboRIO's unset default: it cannot place the log in time.
   */
  public static boolean confirmed(LogData log) {
    return !validReadings(log).isEmpty()
        && (filenameOffset(log).isPresent() || setDuringLog(log));
  }

  /** Why a log's wall clock does not place it in time, for results; empty when it does. */
  public static Optional<String> unconfirmedReason(LogData log) {
    if (entry(log).isEmpty() || confirmed(log)) return Optional.empty();
    return Optional.of("The wpilog's wall clock was never seen being set (the clock reads one "
        + "date throughout and the log's name carries no time to confirm it), so it may be the "
        + "roboRIO's unset default date, which every boot shares; REV logs are not matched to it "
        + "by time.");
  }

  /**
   * The date and time in a log or REV log filename, in the zone of the clock that named it:
   * AdvantageKit's {@code akit_26-03-21_16-29-56...}, DataLogManager's
   * {@code FRC_20260321_162956...}, or REV's {@code REV_20260321_162932...}.
   */
  public static Optional<LocalDateTime> filenameTime(String filename) {
    try {
      var dashed = DASHED.matcher(filename);
      if (dashed.find()) {
        return Optional.of(LocalDateTime.of(2000 + Integer.parseInt(dashed.group(1)),
            Integer.parseInt(dashed.group(2)), Integer.parseInt(dashed.group(3)),
            Integer.parseInt(dashed.group(4)), Integer.parseInt(dashed.group(5)),
            Integer.parseInt(dashed.group(6))));
      }
      var compact = COMPACT.matcher(filename);
      if (compact.find()) {
        return Optional.of(LocalDateTime.of(Integer.parseInt(compact.group(1)),
            Integer.parseInt(compact.group(2)), Integer.parseInt(compact.group(3)),
            Integer.parseInt(compact.group(4)), Integer.parseInt(compact.group(5)),
            Integer.parseInt(compact.group(6))));
      }
    } catch (java.time.DateTimeException e) {
      // digits that are not a date (month 13, hour 25): no filename time
    }
    return Optional.empty();
  }

  /**
   * The offset of the clock that named this log, from the log itself: its filename time minus
   * its first plausible wall-clock reading, rounded to a quarter hour. Empty when the filename
   * carries no time, the log has no wall clock, or the filename time, so corrected, falls outside
   * the wall clock's span (then the two do not describe the same moment).
   */
  public static Optional<ZoneOffset> filenameOffset(LogData log) {
    if (log.path() == null) return Optional.empty();
    var named = filenameTime(Path.of(log.path()).getFileName().toString());
    var first = first(log);
    var last = last(log);
    if (named.isEmpty() || first.isEmpty() || last.isEmpty()) return Optional.empty();
    long namedAsUtcMicros = named.get().toEpochSecond(ZoneOffset.UTC) * 1_000_000L;
    double differenceSec = (namedAsUtcMicros - first.get().epochMicros()) / 1e6;
    int offsetSec = (int) Math.round(differenceSec / QUARTER_HOUR_SEC) * QUARTER_HOUR_SEC;
    if (Math.abs(offsetSec) > MAX_OFFSET_SEC) return Optional.empty();
    long namedMicros = namedAsUtcMicros - offsetSec * 1_000_000L;
    if (namedMicros < first.get().epochMicros() - SPAN_SLACK_MICROS
        || namedMicros > last.get().epochMicros() + SPAN_SLACK_MICROS) {
      return Optional.empty();
    }
    return Optional.of(ZoneOffset.ofTotalSeconds(offsetSec));
  }

  /**
   * How to read REV log filename times for this wpilog. REVLib in robot code names its files by
   * the same roboRIO clock that named the wpilog, so the wpilog's own offset applies; without it
   * (no filename time, or no wall clock to check it against), UTC, the roboRIO's default zone.
   * A REV log recorded by the REV Hardware Client on a laptop is named in the laptop's zone
   * instead; set_revlog_offset corrects such a pairing.
   */
  public static FilenameZone revlogFilenameZone(LogData wpilog) {
    return filenameOffset(wpilog)
        .map(offset -> new FilenameZone(offset, "UTC" + (offset.getTotalSeconds() == 0 ? ""
            : offset.getId()) + ", the zone the wpilog's own filename time shows against its "
            + "wall clock (the same clock is taken to have named the REV log)"))
        .orElseGet(() -> new FilenameZone(ZoneOffset.UTC, "UTC, the roboRIO's default zone "
            + "(the wpilog has no filename time and wall clock to show its zone)"));
  }

  private static int rank(String name) {
    // Capture uses DataLogManager's NT: recording prefix even for replayed systemTime.
    if (name.startsWith("NT:")) name = name.substring(3);
    if (name.equals("systemTime")) return 0;
    if (name.equals("/SystemStats/EpochTimeMicros")
        || name.equals("/AdvantageKit/SystemStats/EpochTimeMicros")) return 1;
    return -1;
  }
}
