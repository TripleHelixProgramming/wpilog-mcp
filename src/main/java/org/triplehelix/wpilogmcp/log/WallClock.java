/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The entry that maps a log's FPGA time to wall-clock time: WPILib's {@code systemTime}
 * (DataLogManager) or AdvantageKit's {@code /SystemStats/EpochTimeMicros}, both epoch
 * microseconds. Matched by exact name, not by substring.
 *
 * @since 0.9.0
 */
public final class WallClock {

  private WallClock() {}

  private static final Set<String> TYPES = Set.of("int64", "double", "float");

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

  private static int rank(String name) {
    var leaf = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    if (leaf.equals("systemtime") && !name.contains("/")) return 0;
    if (leaf.equals("epochtimemicros")) return 1;
    return -1;
  }
}
