/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
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

  /** The first plausible wall-clock reading of the log, if it has a wall clock. */
  public static Optional<Reading> first(LogData log) {
    return readings(log, false);
  }

  /** The last plausible wall-clock reading of the log, if it has a wall clock. */
  public static Optional<Reading> last(LogData log) {
    return readings(log, true);
  }

  private static Optional<Reading> readings(LogData log, boolean last) {
    var clock = entry(log);
    if (clock.isEmpty()) return Optional.empty();
    var values = log.values().get(clock.get());
    if (values == null) return Optional.empty();
    Reading found = null;
    for (var tv : values) {
      if (tv.value() instanceof Number num && plausible(num.longValue())) {
        found = new Reading(tv.timestamp(), num.longValue());
        if (!last) break;
      }
    }
    return Optional.ofNullable(found);
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
    var leaf = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    if (leaf.equals("systemtime") && !name.contains("/")) return 0;
    if (leaf.equals("epochtimemicros")) return 1;
    return -1;
  }
}
