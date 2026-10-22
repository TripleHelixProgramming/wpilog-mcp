/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.WallClock;

/**
 * UTC is a presentation of the log's measured clock, never a filename or server-clock estimate.
 * Exact rational interpolation prevents epoch-sized doubles from moving a sample by a
 * microsecond. Ambiguous pairs cannot supply either a forward or an inverse clock.
 *
 * <p>The robot's own clock places a log when it has a valid reading. A capture whose robot
 * publishes none is placed by the clock of the server that recorded it, which the capture
 * records at each NetworkTables time synchronization; {@code clock} in the basis says which. That
 * recorded measurement is the only server clock that supplies UTC, and only here.
 */
public final class UtcTimeMapping {
  public static final String ROUNDING = "nearest_microsecond_ties_to_even";
  /** The robot's own wall clock. */
  public static final String ROBOT_CLOCK = "robot";
  /** The clock of the server that recorded a capture. */
  public static final String PIT_CLOCK = "pit_clock";
  /** Where a capture records its server's clock: epoch microseconds at the robot's time. */
  public static final String PIT_CLOCK_ENTRY = WallClock.PIT_CLOCK_ENTRY;
  private static final java.util.Set<String> CLOCK_TYPES = java.util.Set.of("int64", "double", "float");

  public record Mapping(Long epochMicros, String reason) {}
  public record Basis(String entry, String clock, int readingCount, String firstOffsetUs, String lastOffsetUs,
      String maxOffsetChangeUs, String rounding) {}

  /** Decimal coordinates retain fractional aligned clocks without rounding them into raw ones. */
  public record Coordinate(BigDecimal logMicros, long epochMicros) {}

  /** The readings in log order, sorted only when the retained order was not. */
  private final long[] logs, epochs;
  private final int count;
  private final Basis basis;
  private final String unavailableReason;

  private UtcTimeMapping(long[] logs, long[] epochs, int count, Basis basis, String reason) {
    this.logs = logs;
    this.epochs = epochs;
    this.count = count;
    this.basis = basis;
    unavailableReason = reason;
  }

  /** The log's mapping, its clock entries read whole. */
  public static UtcTimeMapping from(LogData log) {
    return choose(log, (entry, clock) -> of(entry, clock, log.values().get(entry)));
  }

  /**
   * The robot's clock when it has a valid reading, else the recorded server clock, each entry
   * mapped by {@code mapping} (entry, clock). A robot clock that is ambiguous is refused, not
   * replaced: the robot published a clock, and it cannot place the log.
   */
  public static UtcTimeMapping choose(LogData log, java.util.function.BiFunction<String, String, UtcTimeMapping> mapping) {
    var robot = WallClock.entry(log).map(entry -> mapping.apply(entry, ROBOT_CLOCK));
    if (robot.isPresent() && robot.get().count > 0) return robot.get();
    var pit = pitClockEntry(log).map(entry -> mapping.apply(entry, PIT_CLOCK));
    if (pit.isPresent() && pit.get().count > 0) return pit.get();
    return robot.or(() -> pit).orElseGet(() -> unavailable(null, null, "No wall-clock entry"));
  }

  /** The recorded server clock, when the log holds readings of it. */
  public static Optional<String> pitClockEntry(LogData log) {
    var info = log.entries().get(PIT_CLOCK_ENTRY);
    return info != null && CLOCK_TYPES.contains(info.type()) && log.sampleCount(PIT_CLOCK_ENTRY) > 0
        ? Optional.of(PIT_CLOCK_ENTRY) : Optional.empty();
  }

  /** A clock entry's values, in log order, read once. */
  public static UtcTimeMapping of(String entry, String clock, List<TimestampedValue> values) {
    var valid = new WallClock.ValidReadings();
    if (values != null) values.forEach(valid::add);
    return of(entry, clock, valid.prefix(valid.values()));
  }

  /**
   * The readings a clock entry's prefix keeps. The arrays may be longer than the prefix and grow
   * after it; nothing below its count changes, so a recording's prefix is mapped without a copy.
   */
  public static UtcTimeMapping of(String entry, String clock, WallClock.Prefix prefix) {
    int n = prefix.count();
    if (n == 0) return unavailable(entry, clock, "No valid wall-clock readings");
    long[] logs = prefix.logMicros(), epochs = prefix.epochMicros();
    var firstOffset = offset(logs[0], epochs[0]);
    var lastOffset = offset(logs[n - 1], epochs[n - 1]);
    var maxChange = BigInteger.ZERO;
    boolean sorted = true;
    for (int i = 1; i < n; i++) {
      maxChange = maxChange(maxChange, logs, epochs, i);
      sorted &= logs[i] >= logs[i - 1];
    }
    if (!sorted) {
      // Readings come in log order from every reader of a log; sort a copy in case one does not.
      Integer[] order = new Integer[n];
      for (int i = 0; i < n; i++) order[i] = i;
      long[] retained = logs, readings = epochs;
      // Stable, as the list sort was: equal times keep the order they were logged in.
      java.util.Arrays.sort(order, Comparator.comparingLong(i -> retained[i]));
      long[] sortedLogs = new long[n], sortedEpochs = new long[n];
      for (int i = 0; i < n; i++) {
        sortedLogs[i] = retained[order[i]];
        sortedEpochs[i] = readings[order[i]];
      }
      logs = sortedLogs;
      epochs = sortedEpochs;
    }
    var basis = new Basis(entry, clock, n, firstOffset.toString(), lastOffset.toString(), maxChange.toString(), ROUNDING);
    return new UtcTimeMapping(logs, epochs, n, basis, ambiguityReason(logs, epochs, n));
  }

  private static UtcTimeMapping unavailable(String entry, String clock, String reason) {
    return new UtcTimeMapping(new long[0], new long[0], 0, new Basis(entry, clock, 0, null, null, null, ROUNDING), reason);
  }

  /** The largest change of offset so far, in exact integers: an offset of extreme values may not fit a long. */
  private static BigInteger maxChange(BigInteger max, long[] logs, long[] epochs, int i) {
    try {
      long change = Math.absExact(Math.subtractExact(Math.subtractExact(epochs[i], logs[i]), Math.subtractExact(epochs[i - 1], logs[i - 1])));
      return max.bitLength() < 63 && change <= max.longValue() ? max : max.max(BigInteger.valueOf(change));
    } catch (ArithmeticException wide) {
      return max.max(offset(logs[i], epochs[i]).subtract(offset(logs[i - 1], epochs[i - 1])).abs());
    }
  }

  /** {@link #ambiguityReason(List)} for readings already in log order. */
  private static String ambiguityReason(long[] logs, long[] epochs, int n) {
    for (int i = 1; i < n; i++) {
      int logOrder = Long.compare(logs[i], logs[i - 1]);
      int epochOrder = Long.compare(epochs[i], epochs[i - 1]);
      if (logOrder == 0 && epochOrder != 0)
        return "Ambiguous wall-clock mapping: one log timestamp has conflicting epoch readings";
      if (logOrder != 0 && epochOrder == 0)
        return "Ambiguous wall-clock mapping: one epoch reading names multiple log timestamps";
      if (epochOrder < 0)
        return "Ambiguous wall-clock mapping: epoch readings move backwards";
    }
    return null;
  }

  public Basis basis() { return basis; }

  /**
   * Build once for a system-log search, rather than sorting clock pairs again for every line.
   * Unlike forward UTC presentation, the existing inverse keeps the first offset before its
   * anchors too. Only its final seconds result is rounded to double.
   */
  public static Inverse inverse(List<Coordinate> coordinates) {
    return new Inverse(coordinates);
  }

  public static final class Inverse {
    private record Anchor(long epochMicros, Rational logMicros) {}
    private final List<Anchor> anchors;
    private final String reason;

    private Inverse(List<Coordinate> coordinates) {
      reason = ambiguityReason(coordinates);
      var ordered = coordinates.stream().sorted(Comparator.comparingLong(Coordinate::epochMicros)).toList();
      var distinct = new ArrayList<Anchor>();
      for (var coordinate : ordered) {
        var anchor = new Anchor(coordinate.epochMicros(), Rational.decimal(coordinate.logMicros()));
        if (distinct.isEmpty() || !distinct.get(distinct.size() - 1).equals(anchor)) distinct.add(anchor);
      }
      anchors = List.copyOf(distinct);
    }

    public String reason() { return reason; }

    public Double at(BigDecimal epochMicros) {
      if (reason != null || anchors.isEmpty()) return null;
      var query = Rational.decimal(epochMicros);
      int low = 0, high = anchors.size();
      while (low < high) {
        int middle = (low + high) >>> 1;
        if (query.compareTo(Rational.integer(anchors.get(middle).epochMicros())) > 0) low = middle + 1;
        else high = middle;
      }
      Rational robot;
      if (low < anchors.size() && query.compareTo(Rational.integer(anchors.get(low).epochMicros())) == 0) {
        robot = anchors.get(low).logMicros();
      } else if (low == 0 || low == anchors.size()) {
        var anchor = anchors.get(low == 0 ? 0 : anchors.size() - 1);
        robot = anchor.logMicros().add(query.subtract(Rational.integer(anchor.epochMicros())));
      } else {
        var before = anchors.get(low - 1);
        var after = anchors.get(low);
        var epochSpan = Rational.integer(after.epochMicros()).subtract(Rational.integer(before.epochMicros()));
        robot = before.logMicros().add(query.subtract(Rational.integer(before.epochMicros()))
            .multiply(after.logMicros().subtract(before.logMicros())).divide(epochSpan));
      }
      double seconds = robot.divide(Rational.integer(1_000_000)).toDouble();
      return Double.isFinite(seconds) ? seconds : null;
    }
  }

  /**
   * Decimal inputs and measured ratios stay exact until the final binary result. A decimal
   * working precision followed by double conversion would introduce a second rounding at ties.
   * Operand widths grow by the written literal's width plus bounded anchor widths, not by an
   * exponent accepted from the input; the caller retains the existing timestamp grammar.
   */
  private record Rational(BigInteger numerator, BigInteger denominator) implements Comparable<Rational> {
    Rational {
      if (denominator.signum() == 0) throw new ArithmeticException("Zero rational denominator");
      if (denominator.signum() < 0) {
        numerator = numerator.negate();
        denominator = denominator.negate();
      }
      var divisor = numerator.gcd(denominator);
      numerator = numerator.divide(divisor);
      denominator = denominator.divide(divisor);
    }
    static Rational integer(long value) { return new Rational(BigInteger.valueOf(value), BigInteger.ONE); }
    static Rational decimal(BigDecimal value) {
      return value.scale() >= 0
          ? new Rational(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
          : new Rational(value.unscaledValue().multiply(BigInteger.TEN.pow(-value.scale())), BigInteger.ONE);
    }
    Rational add(Rational other) {
      return new Rational(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
          denominator.multiply(other.denominator));
    }
    Rational subtract(Rational other) {
      return add(new Rational(other.numerator.negate(), other.denominator));
    }
    Rational multiply(Rational other) {
      return new Rational(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }
    Rational divide(Rational other) {
      return new Rational(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }
    @Override public int compareTo(Rational other) {
      return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    double toDouble() {
      if (numerator.signum() == 0) return 0;
      long sign = numerator.signum() < 0 ? Long.MIN_VALUE : 0;
      var magnitude = numerator.abs();
      int exponent = magnitude.bitLength() - denominator.bitLength();
      if (exponent > 1024) return Double.longBitsToDouble(sign | 0x7ff0000000000000L);
      if (exponent < -1075) return Double.longBitsToDouble(sign);
      int comparison = exponent >= 0 ? magnitude.compareTo(denominator.shiftLeft(exponent))
          : magnitude.shiftLeft(-exponent).compareTo(denominator);
      if (comparison < 0) exponent--;
      if (exponent < -1022) {
        // A subnormal's quantum is fixed at 2^-1074, including the tie to minimum normal.
        var significand = rounded(magnitude.shiftLeft(1074), denominator);
        return Double.longBitsToDouble(sign | significand.longValueExact());
      }
      int shift = 52 - exponent;
      var significand = shift >= 0 ? rounded(magnitude.shiftLeft(shift), denominator)
          : rounded(magnitude, denominator.shiftLeft(-shift));
      if (significand.bitLength() > 53) {
        significand = significand.shiftRight(1);
        exponent++;
      }
      if (exponent > 1023) return Double.longBitsToDouble(sign | 0x7ff0000000000000L);
      long fraction = significand.longValueExact() & 0x000fffffffffffffL;
      return Double.longBitsToDouble(sign | ((long) exponent + 1023) << 52 | fraction);
    }
  }

  /**
   * One validation policy serves UTC presentation and the system-log inverse. Repeated identical
   * pairs add evidence, but a duplicate of only one coordinate or a backwards clock chooses no
   * unique time. Sorting allows separately loaded session members to contribute in any order.
   */
  public static String ambiguityReason(List<Coordinate> coordinates) {
    var ordered = coordinates.stream().sorted(Comparator.comparing(Coordinate::logMicros)).toList();
    Coordinate previous = null;
    for (var current : ordered) {
      if (previous != null) {
        int logOrder = current.logMicros().compareTo(previous.logMicros());
        int epochOrder = Long.compare(current.epochMicros(), previous.epochMicros());
        if (logOrder == 0 && epochOrder != 0)
          return "Ambiguous wall-clock mapping: one log timestamp has conflicting epoch readings";
        if (logOrder != 0 && epochOrder == 0)
          return "Ambiguous wall-clock mapping: one epoch reading names multiple log timestamps";
        if (epochOrder < 0)
          return "Ambiguous wall-clock mapping: epoch readings move backwards";
      }
      previous = current;
    }
    return null;
  }

  /** The log time of the first reading; nothing before it has UTC. Only for a mapping that places samples. */
  public long firstLogMicros() {
    if (unavailableReason != null) throw new IllegalStateException(unavailableReason);
    return logs[0];
  }

  /** Whether the mapping places samples, and the reason it does not. */
  public Optional<String> unavailableReason() {
    return Optional.ofNullable(unavailableReason);
  }

  public Mapping at(long logMicros) {
    if (unavailableReason != null) return new Mapping(null, unavailableReason);
    if (logMicros < logs[0])
      return new Mapping(null, "Before the first valid wall-clock reading");
    int low = 0, high = count;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (logs[middle] < logMicros) low = middle + 1; else high = middle;
    }
    if (low < count && logs[low] == logMicros)
      return new Mapping(epochs[low], null);
    long beforeLog = logs[low - 1], beforeEpoch = epochs[low - 1];
    var delta = BigInteger.valueOf(logMicros).subtract(BigInteger.valueOf(beforeLog));
    BigInteger epoch;
    if (low == count) {
      epoch = BigInteger.valueOf(beforeEpoch).add(delta);
    } else {
      var span = BigInteger.valueOf(logs[low]).subtract(BigInteger.valueOf(beforeLog));
      var wallSpan = BigInteger.valueOf(epochs[low]).subtract(BigInteger.valueOf(beforeEpoch));
      var numerator = BigInteger.valueOf(beforeEpoch).multiply(span).add(delta.multiply(wallSpan));
      epoch = rounded(numerator, span);
    }
    try {
      return new Mapping(epoch.longValueExact(), null);
    } catch (ArithmeticException outsideLong) {
      return new Mapping(null, "Mapped UTC timestamp is outside the signed 64-bit microsecond range");
    }
  }

  private static BigInteger offset(long logMicros, long epochMicros) {
    return BigInteger.valueOf(epochMicros).subtract(BigInteger.valueOf(logMicros));
  }

  /** Round once at the final epoch, including its parity, rather than rounding the offset. */
  private static BigInteger rounded(BigInteger numerator, BigInteger denominator) {
    var division = numerator.divideAndRemainder(denominator);
    int comparison = division[1].abs().shiftLeft(1).compareTo(denominator);
    if (comparison > 0 || comparison == 0 && division[0].testBit(0))
      return division[0].add(BigInteger.valueOf(numerator.signum()));
    return division[0];
  }
}
