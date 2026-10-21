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
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.WallClock;

/**
 * UTC is a presentation of the log's measured clock, never a filename or server-clock estimate.
 * Exact rational interpolation prevents epoch-sized doubles from moving a sample by a
 * microsecond. Ambiguous pairs cannot supply either a forward or an inverse clock.
 */
public final class UtcTimeMapping {
  public static final String ROUNDING = "nearest_microsecond_ties_to_even";
  public record Mapping(Long epochMicros, String reason) {}
  public record Basis(String entry, int readingCount, String firstOffsetUs, String lastOffsetUs,
      String maxOffsetChangeUs, String rounding) {}

  /** Decimal coordinates retain fractional aligned clocks without rounding them into raw ones. */
  public record Coordinate(BigDecimal logMicros, long epochMicros) {}

  private final List<WallClock.ExactReading> readings;
  private final Basis basis;
  private final String unavailableReason;

  private UtcTimeMapping(List<WallClock.ExactReading> readings, Basis basis, String reason) {
    this.readings = readings;
    this.basis = basis;
    unavailableReason = reason;
  }

  public static UtcTimeMapping from(LogData log) {
    String entry = WallClock.entry(log).orElse(null);
    var retained = WallClock.validExactReadings(log);
    if (retained.isEmpty()) return new UtcTimeMapping(List.of(),
        new Basis(entry, 0, null, null, null, ROUNDING),
        entry == null ? "No wall-clock entry" : "No valid wall-clock readings");
    var coordinates = retained.stream()
        .map(reading -> new Coordinate(BigDecimal.valueOf(reading.logMicros()), reading.epochMicros()))
        .toList();
    String reason = ambiguityReason(coordinates);
    var firstOffset = offset(retained.get(0));
    var lastOffset = offset(retained.get(retained.size() - 1));
    var maxChange = BigInteger.ZERO;
    for (int i = 1; i < retained.size(); i++)
      maxChange = maxChange.max(offset(retained.get(i)).subtract(offset(retained.get(i - 1))).abs());
    var basis = new Basis(entry, retained.size(), firstOffset.toString(), lastOffset.toString(),
        maxChange.toString(), ROUNDING);
    var distinct = new ArrayList<WallClock.ExactReading>();
    retained.stream().sorted(Comparator.comparingLong(WallClock.ExactReading::logMicros))
        .forEach(reading -> {
          if (distinct.isEmpty() || !distinct.get(distinct.size() - 1).equals(reading))
            distinct.add(reading);
        });
    return new UtcTimeMapping(List.copyOf(distinct), basis, reason);
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

  public Mapping at(long logMicros) {
    if (unavailableReason != null) return new Mapping(null, unavailableReason);
    var first = readings.get(0);
    if (logMicros < first.logMicros())
      return new Mapping(null, "Before the first valid wall-clock reading");
    int low = 0, high = readings.size();
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (readings.get(middle).logMicros() < logMicros) low = middle + 1; else high = middle;
    }
    if (low < readings.size() && readings.get(low).logMicros() == logMicros)
      return new Mapping(readings.get(low).epochMicros(), null);
    var before = readings.get(low - 1);
    var delta = BigInteger.valueOf(logMicros).subtract(BigInteger.valueOf(before.logMicros()));
    BigInteger epoch;
    if (low == readings.size()) {
      epoch = BigInteger.valueOf(before.epochMicros()).add(delta);
    } else {
      var after = readings.get(low);
      var span = BigInteger.valueOf(after.logMicros()).subtract(BigInteger.valueOf(before.logMicros()));
      var wallSpan = BigInteger.valueOf(after.epochMicros()).subtract(BigInteger.valueOf(before.epochMicros()));
      var numerator = BigInteger.valueOf(before.epochMicros()).multiply(span).add(delta.multiply(wallSpan));
      epoch = rounded(numerator, span);
    }
    try {
      return new Mapping(epoch.longValueExact(), null);
    } catch (ArithmeticException outsideLong) {
      return new Mapping(null, "Mapped UTC timestamp is outside the signed 64-bit microsecond range");
    }
  }

  private static BigInteger offset(WallClock.ExactReading reading) {
    return BigInteger.valueOf(reading.epochMicros()).subtract(BigInteger.valueOf(reading.logMicros()));
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
