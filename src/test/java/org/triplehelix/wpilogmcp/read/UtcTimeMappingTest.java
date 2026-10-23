/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.WallClock;

class UtcTimeMappingTest {
  private static final long W = 1_700_000_000_000_000L;

  @Test void measuredInterpolationAndLastOffsetHaveIndependentAnswers() {
    var mapping = mapping(at(10_000_000, W), at(20_000_000, W + 12_000_000));
    assertEquals(new UtcTimeMapping.Mapping(null, "Before the first valid wall-clock reading"), mapping.at(4_000_000));
    assertEquals(new UtcTimeMapping.Mapping(W, null), mapping.at(10_000_000));
    assertEquals(new UtcTimeMapping.Mapping(W + 6_000_000, null), mapping.at(15_000_000));
    assertEquals(new UtcTimeMapping.Mapping(W + 12_000_000, null), mapping.at(20_000_000));
    assertEquals(new UtcTimeMapping.Mapping(W + 17_000_000, null), mapping.at(25_000_000));
    assertEquals(new UtcTimeMapping.Basis("systemTime", UtcTimeMapping.ROBOT_CLOCK, 2, "1699999990000000",
        "1699999992000000", "2000000", "nearest_microsecond_ties_to_even"), mapping.basis());
  }

  @Test void largestConsecutiveOffsetChangeIsNotTheOverallOffsetRange() {
    var mapping = mapping(at(10_000_000, W), at(20_000_000, W + 12_000_000),
        at(30_000_000, W + 25_000_000));
    // Offsets from W are -10 s, -8 s, -5 s: consecutive changes 2 s and 3 s, range 5 s.
    assertEquals("3000000", mapping.basis().maxOffsetChangeUs());
    assertEquals("1699999995000000", mapping.basis().lastOffsetUs());
    assertEquals(3, mapping.basis().readingCount());
  }

  @Test void finalEpochParityDecidesHalfMicrosecondTies() {
    var even = mapping(at(0, W), at(2, W + 1));
    var odd = mapping(at(0, W + 1), at(2, W + 2));
    assertEquals(W, even.at(1).epochMicros());
    assertEquals(W + 2, odd.at(1).epochMicros());
    var thirds = mapping(at(0, W), at(3, W + 1));
    assertEquals(W, thirds.at(1).epochMicros());
    assertEquals(W + 1, thirds.at(2).epochMicros());
  }

  /**
   * The long arithmetic a data request maps each row with gives exactly the exact path's answer:
   * on random readings of every rate, with spans small enough to tie on half a microsecond,
   * gaps wide enough to overflow a long's product, and epochs near the end of the range, at
   * times inside each segment, on each side of a reading and past the last.
   */
  @Test void longArithmeticMapsEveryTimeAsTheExactPathDoes() {
    var random = new java.util.Random(23);
    int compared = 0, ties = 0, overflows = 0;
    for (int trial = 0; trial < 400; trial++) {
      int n = 2 + random.nextInt(30);
      long[] logs = new long[n], epochs = new long[n], steps = new long[n];
      logs[0] = random.nextLong(-1_000_000_000_000L, 1_000_000_000_000L);
      long climb = 0;
      for (int i = 1; i < n; i++) {
        long span = switch (random.nextInt(4)) {
          case 0 -> 1 + random.nextInt(4);
          case 1 -> 1 + random.nextInt(40_000);
          case 2 -> 1 + random.nextLong(10_000_000_000L);
          default -> 1 + random.nextLong(4_000_000_000_000L);
        };
        logs[i] = logs[i - 1] + span;
        steps[i] = Math.max(1, Math.round(span * (0.5 + random.nextDouble() * 1.5))) + random.nextInt(3);
        climb += steps[i];
      }
      // Some clocks end just short of the largest epoch, so a time past the last reading overflows.
      epochs[0] = trial % 50 == 0 ? Long.MAX_VALUE - 1_000_000_000_000L - climb : W + random.nextLong(1_000_000_000_000L);
      for (int i = 1; i < n; i++) epochs[i] = epochs[i - 1] + steps[i];
      var mapping = UtcTimeMapping.of("clock", UtcTimeMapping.ROBOT_CLOCK, new WallClock.Prefix(logs, epochs, n));
      assertNull(mapping.unavailableReason().orElse(null));
      for (int query = 0; query < 60; query++) {
        int segment = random.nextInt(n);
        long base = logs[segment];
        long next = segment + 1 < n ? logs[segment + 1] : base + 4_000_000_000_000L;
        long time = query % 3 == 0 ? base + 1 : query % 3 == 1 ? next - 1 : base + 1 + random.nextLong(Math.max(1, next - base - 1));
        int low = mapping.position(time);
        if (low == 0 || low < n && logs[low] == time) continue;
        var expected = mapping.exactAt(time, low);
        assertEquals(expected, mapping.at(time), "time " + time + " after reading " + (low - 1) + " of trial " + trial);
        compared++;
        if (low < n) {
          var span = java.math.BigInteger.valueOf(logs[low] - logs[low - 1]);
          var product = java.math.BigInteger.valueOf(time - logs[low - 1]).multiply(java.math.BigInteger.valueOf(epochs[low] - epochs[low - 1]));
          if (product.mod(span).shiftLeft(1).equals(span)) ties++;
          if (product.bitLength() > 63) overflows++;
        }
      }
    }
    assertTrue(compared > 15_000, "times compared: " + compared);
    assertTrue(ties > 50, "half-microsecond ties: " + ties);
    assertTrue(overflows > 100, "products past a long: " + overflows);
  }

  @Test void adjacentLargeLogCoordinatesRemainDistinct() {
    long large = 9_007_199_254_740_992L;
    var first = at(large, W);
    var next = at(large + 1, W + 1);
    assertEquals(first.timestamp(), next.timestamp(), "The legacy double view loses this distinction");
    var log = log("systemTime", first, next, at(large + 2, W + 2));
    assertEquals(large + 1, WallClock.validExactReadings(log).get(1).logMicros());
    var mapping = UtcTimeMapping.from(log);
    assertEquals(W + 1, mapping.at(large + 1).epochMicros());
    assertNull(mapping.at(large + 1).reason());
  }

  @Test void ambiguousMappingsRefuseButKeepTheirMeasuredBasis() {
    var duplicate = mapping(at(10_000_000, W), at(20_000_000, W));
    assertEquals("Ambiguous wall-clock mapping: one epoch reading names multiple log timestamps",
        duplicate.at(15_000_000).reason());
    assertNull(duplicate.at(10_000_000).epochMicros());
    assertEquals(2, duplicate.basis().readingCount());
    assertEquals("10000000", duplicate.basis().maxOffsetChangeUs());
    var conflict = mapping(at(10_000_000, W), at(10_000_000, W + 1));
    assertEquals("Ambiguous wall-clock mapping: one log timestamp has conflicting epoch readings",
        conflict.at(10_000_000).reason());
    var backward = mapping(at(10_000_000, W + 2_000_000), at(20_000_000, W + 1_000_000));
    assertEquals("Ambiguous wall-clock mapping: epoch readings move backwards",
        backward.at(10_000_000).reason());
  }

  @Test void identicalPairsContributeToTheBasisButDoNotMakeAZeroLengthSegment() {
    var mapping = mapping(at(10_000_000, W), at(10_000_000, W), at(20_000_000, W + 12_000_000));
    assertEquals(W + 6_000_000, mapping.at(15_000_000).epochMicros());
    assertEquals(3, mapping.basis().readingCount());
    assertEquals("2000000", mapping.basis().maxOffsetChangeUs());
  }

  @Test void clockSetPartwayAndClockJumpRetainExactlyTheLegacyReadings() {
    var log = log("systemTime", at(0, 0), at(10_000_000, W),
        at(20_000_000, W + 10_000_000), at(30_000_000, W + 100_000_000),
        at(40_000_000, W + 110_000_000));
    assertEquals(List.of(W + 100_000_000, W + 110_000_000),
        WallClock.validReadings(log).stream().map(WallClock.Reading::epochMicros).toList());
    assertEquals(List.of(new WallClock.ExactReading(30_000_000, W + 100_000_000),
        new WallClock.ExactReading(40_000_000, W + 110_000_000)), WallClock.validExactReadings(log));
    var mapping = UtcTimeMapping.from(log);
    assertNull(mapping.at(29_999_999).epochMicros());
    assertEquals(W + 105_000_000, mapping.at(35_000_000).epochMicros());
    assertEquals(2, mapping.basis().readingCount());
  }

  @Test void missingAndUnsetClocksHaveDifferentReasonsAndNoInventedBasis() {
    var absent = UtcTimeMapping.from(new ParsedLog("synthetic.wpilog", Map.of(), Map.of(), 0, 1));
    assertEquals(new UtcTimeMapping.Mapping(null, "No wall-clock entry"), absent.at(0));
    assertEquals(new UtcTimeMapping.Basis(null, null, 0, null, null, null, UtcTimeMapping.ROUNDING), absent.basis());
    var unset = mapping(at(0, 0), at(1_000_000, 1_000_000));
    assertEquals(new UtcTimeMapping.Mapping(null, "No valid wall-clock readings"), unset.at(1_000_000));
    assertEquals(new UtcTimeMapping.Basis("systemTime", UtcTimeMapping.ROBOT_CLOCK, 0, null, null, null, UtcTimeMapping.ROUNDING), unset.basis());
  }

  @Test void exactHelperKeepsPublishedClockAliasesAndSelectionPriority() {
    for (String name : List.of("systemTime", "NT:systemTime", "/SystemStats/EpochTimeMicros",
        "/AdvantageKit/SystemStats/EpochTimeMicros", "NT:/AdvantageKit/SystemStats/EpochTimeMicros")) {
      var mapping = UtcTimeMapping.from(log(name, at(0, W)));
      assertEquals(name, mapping.basis().entry());
      assertEquals(W + 1, mapping.at(1).epochMicros());
    }
    var both = new ParsedLog("synthetic.wpilog", Map.of(
        "systemTime", new EntryInfo(2, "systemTime", "int64", ""),
        "/SystemStats/EpochTimeMicros", new EntryInfo(1, "/SystemStats/EpochTimeMicros", "int64", "")),
        Map.of("systemTime", List.of(at(0, W)), "/SystemStats/EpochTimeMicros", List.of(at(0, W + 7))),
        0, 1);
    assertEquals(W, UtcTimeMapping.from(both).at(0).epochMicros());
  }

  @Test void offsetsAndFinalOverflowDoNotWrapAtLongBoundaries() {
    var negativeLog = mapping(at(Long.MIN_VALUE, W));
    assertEquals("9225072036854775808", negativeLog.basis().firstOffsetUs());
    assertEquals(W + 1, negativeLog.at(Long.MIN_VALUE + 1).epochMicros());
    var largest = mapping(at(0, Long.MAX_VALUE - 1));
    assertEquals(Long.MAX_VALUE, largest.at(1).epochMicros());
    assertNull(largest.at(2).epochMicros());
    assertEquals("Mapped UTC timestamp is outside the signed 64-bit microsecond range", largest.at(2).reason());
  }

  @Test void sharedValidationPreservesFractionalCoordinatesAndInputOrderIndependence() {
    var a = new UtcTimeMapping.Coordinate(new BigDecimal("0.1"), W);
    var b = new UtcTimeMapping.Coordinate(new BigDecimal("0.2"), W + 1);
    assertNull(UtcTimeMapping.ambiguityReason(List.of(b, a, a)));
    assertTrue(UtcTimeMapping.ambiguityReason(List.of(a,
        new UtcTimeMapping.Coordinate(new BigDecimal("0.10"), W + 1)))
        .contains("one log timestamp"));
  }

  @Test void exactInverseRoundsBinaryMidpointTiesToEven() {
    var halfUlpAtOne = BigDecimal.ONE.divide(new BigDecimal(java.math.BigInteger.ONE.shiftLeft(53)));
    assertEquals(0x3ff0000000000000L,
        inverseBits(BigDecimal.ONE.add(halfUlpAtOne)));
    assertEquals(0x3ff0000000000002L,
        inverseBits(BigDecimal.ONE.add(halfUlpAtOne.multiply(new BigDecimal("3")))));
    assertEquals(0xbff0000000000000L,
        inverseBits(BigDecimal.ONE.add(halfUlpAtOne).negate()));
  }

  @Test void exactInverseHandlesSubnormalAndMinimumNormalTies() {
    var quantum = BigDecimal.ONE.divide(new BigDecimal(java.math.BigInteger.ONE.shiftLeft(1074)));
    var half = quantum.divide(new BigDecimal("2"));
    assertEquals(0L, inverseBits(half));
    assertEquals(Long.MIN_VALUE, inverseBits(half.negate()));
    assertEquals(2L, inverseBits(quantum.add(half)));
    var minimumNormal = BigDecimal.ONE.divide(new BigDecimal(java.math.BigInteger.ONE.shiftLeft(1022)));
    assertEquals(0x0010000000000000L, inverseBits(minimumNormal.subtract(half)));
  }

  @Test void exactInverseCarriesRoundedSignificandsAndUsesTheFiniteOverflowBoundary() {
    var halfUlpBelowTwo = BigDecimal.ONE.divide(new BigDecimal(java.math.BigInteger.ONE.shiftLeft(53)));
    assertEquals(0x4000000000000000L, inverseBits(new BigDecimal("2").subtract(halfUlpBelowTwo)));
    var maximum = new BigDecimal(Double.MAX_VALUE);
    var threshold = maximum.add(new BigDecimal(Math.ulp(Double.MAX_VALUE)).divide(new BigDecimal("2")));
    assertEquals(Double.MAX_VALUE, inverseSeconds(threshold.subtract(BigDecimal.ONE)));
    assertNull(inverseSeconds(threshold), "The exact halfway overflow rounds to infinity, which is unavailable");
  }

  @Test void exactInverseKeepsSubMicrosecondCoordinatesAndDeclinesOnlyNonfiniteResults() {
    var inverse = UtcTimeMapping.inverse(List.of(
        new UtcTimeMapping.Coordinate(new BigDecimal("0.125"), W),
        new UtcTimeMapping.Coordinate(new BigDecimal("0.375"), W + 1)));
    assertEquals(0.00000025, inverse.at(BigDecimal.valueOf(W).add(new BigDecimal("0.5"))));
    assertEquals(-0.000000875, inverse.at(BigDecimal.valueOf(W - 1)));
    assertEquals(0.000001375, inverse.at(BigDecimal.valueOf(W + 2)));
    var tooLarge = UtcTimeMapping.inverse(List.of(
        new UtcTimeMapping.Coordinate(new BigDecimal(Double.MAX_VALUE).scaleByPowerOfTen(7), W)));
    assertNull(tooLarge.at(BigDecimal.valueOf(W)));
  }

  @Test void exactInverseRejectsTheSameAmbiguitiesAndCollapsesIdenticalPairs() {
    var a = new UtcTimeMapping.Coordinate(BigDecimal.ZERO, W);
    var b = new UtcTimeMapping.Coordinate(new BigDecimal("1"), W + 1);
    var inverse = UtcTimeMapping.inverse(List.of(b, a, a));
    assertNull(inverse.reason());
    assertEquals(0.0000005, inverse.at(BigDecimal.valueOf(W).add(new BigDecimal("0.5"))));
    var ambiguous = UtcTimeMapping.inverse(List.of(a,
        new UtcTimeMapping.Coordinate(new BigDecimal("1"), W)));
    assertEquals("Ambiguous wall-clock mapping: one epoch reading names multiple log timestamps", ambiguous.reason());
    assertNull(ambiguous.at(BigDecimal.valueOf(W)));
  }

  private static long inverseBits(BigDecimal exactSeconds) {
    return Double.doubleToRawLongBits(inverseSeconds(exactSeconds));
  }

  private static Double inverseSeconds(BigDecimal exactSeconds) {
    var inverse = UtcTimeMapping.inverse(List.of(
        new UtcTimeMapping.Coordinate(exactSeconds.scaleByPowerOfTen(6), W)));
    return inverse.at(BigDecimal.valueOf(W));
  }

  private static TimestampedValue at(long logMicros, long epochMicros) {
    return TimestampedValue.atMicros(logMicros, epochMicros);
  }

  private static UtcTimeMapping mapping(TimestampedValue... values) {
    return UtcTimeMapping.from(log("systemTime", values));
  }

  private static ParsedLog log(String entry, TimestampedValue... values) {
    return new ParsedLog("synthetic-clock.wpilog",
        Map.of(entry, new EntryInfo(1, entry, "int64", "")), Map.of(entry, List.of(values)), 0, 30);
  }
}
