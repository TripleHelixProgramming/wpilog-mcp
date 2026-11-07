/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

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
import org.triplehelix.wpilogmcp.read.UtcTimeMapping;

/** A written clock cannot choose one robot time when the retained pairs disagree. */
class SystemLogClocksHardeningTest {
  private static final long W = 1_700_000_000_000_000L;

  @Test void duplicateEpochRefusesEvenAnExactlyMatchingWrittenTime() {
    var clocks = clocks(at(10, W), at(20, W));
    assertAmbiguous(clocks.map(journal(W), "journal"));
  }

  @Test void backwardEpochRefusesTheWholeMappingIncludingItsAnchors() {
    var clocks = clocks(at(10, W + 2_000_000), at(20, W + 1_000_000));
    assertAmbiguous(clocks.map(journal(W + 2_000_000), "journal"));
  }

  @Test void conflictingEpochsAtOneLogTimeRefuseTheWholeMapping() {
    var clocks = clocks(at(10, W), at(10, W + 1_000_000), at(20, W + 11_000_000));
    assertAmbiguous(clocks.map(journal(W), "journal"));
  }

  @Test void identicalPairsAreRedundantAndFractionalInverseIsNotRounded() {
    var clocks = clocks(at(10, W), at(10, W), at(20, W + 12_000_000));
    // A 12-second wall interval spans 10 robot seconds: one wall second is 5/6 robot second.
    var fractional = clocks.map(journal(W + 1_000_000), "journal");
    assertEquals(10 + 5.0 / 6, fractional.seconds(), 1e-12);
    assertEquals("system_time", fractional.basis());
    assertNull(fractional.reason());
    assertEquals(15.0, clocks.map(journal(W + 6_000_000), "journal").seconds());
    // Existing inverse consumers retain first/last offsets outside the measured anchors.
    assertEquals(8.0, clocks.map(journal(W - 2_000_000), "journal").seconds());
    assertEquals(22.0, clocks.map(journal(W + 14_000_000), "journal").seconds());
  }

  @Test void ambiguityOfWallPairsDoesNotChangeKernelPairing() {
    var clocks = clocks(at(10, W), at(20, W));
    clocks.addKernel(List.of(new double[] {100, 10}, new double[] {120, 20}));
    clocks.sort();
    var mapped = clocks.map("[105.000] synthetic", "dmesg");
    assertEquals(12.5, mapped.seconds());
    assertEquals("uptime_pairing", mapped.basis());
    assertNull(mapped.reason());
    assertNull(clocks.map("[99.000] synthetic", "dmesg").seconds());
  }

  @Test void rawCoordinatesThatShareADoubleDoNotBecomeFalseConflicts() {
    long large = 9_007_199_254_740_992L;
    var log = log(TimestampedValue.atMicros(large, W), TimestampedValue.atMicros(large + 1, W + 1));
    var clocks = new SystemLogClocks(0, Double.POSITIVE_INFINITY);
    clocks.add(log, new SystemLogClocks.RobotTime(t -> t, true), true, false);
    clocks.sort();
    assertNull(clocks.map(journal(W), "journal").reason());
    assertEquals(large / 1e6, clocks.map(journal(W), "journal").seconds());
  }

  @Test void normalForwardAndInverseAgreeWithinEpochQuantization() {
    var log = log(at(10, W), at(20, W + 12_000_000));
    var mapping = UtcTimeMapping.from(log);
    var clocks = clocks(at(10, W), at(20, W + 12_000_000));
    long[] logMicros = {10_000_000, 10_000_001, 15_000_000, 19_999_999, 20_000_000};
    long[] expectedEpoch = {W, W + 1, W + 6_000_000, W + 11_999_999, W + 12_000_000};
    for (int i = 0; i < logMicros.length; i++) {
      assertEquals(expectedEpoch[i], mapping.at(logMicros[i]).epochMicros());
      double robot = clocks.map(journal(expectedEpoch[i]), "journal").seconds();
      // Quantization is in epoch microseconds; inverse error expands when the clock slope is small.
      double epochErrorUs = Math.abs(robot - logMicros[i] / 1e6) * 1e6 * 1.2;
      assertTrue(epochErrorUs <= 0.500001, "epoch error_us=" + epochErrorUs);
    }
    var slowLog = log(at(10, W), at(20, W + 1));
    var slowClock = clocks(at(10, W), at(20, W + 1));
    assertEquals(W, UtcTimeMapping.from(slowLog).at(15_000_000).epochMicros());
    assertEquals(10.0, slowClock.map(journal(W), "journal").seconds());
    // W+0.5 us rounds to even W; five robot seconds here are exactly half an epoch microsecond.
    assertEquals(0.5, (15 - slowClock.map(journal(W), "journal").seconds()) / 10);
  }

  @Test void derivedRobotClocksKeepTheirFractionalPrecision() {
    var log = log(at(10, W), at(20, W + 12_000_000));
    var clocks = new SystemLogClocks(0, 30);
    clocks.add(log, new SystemLogClocks.RobotTime(t -> t / 3, false), true, false);
    clocks.sort();
    assertEquals((10 + 5.0 / 6) / 3, clocks.map(journal(W + 1_000_000), "journal").seconds(), 1e-12);
    assertNull(clocks.map(journal(W + 1_000_000), "journal").reason());
  }

  @Test void validInverseRoundsOnlyTheFinalExactFraction() {
    var clocks = clocks(at(10, W), at(20, W + 12_000_000));
    // 10 + (250 / 1,000,000) * (10 / 12) = 48001 / 4800 seconds.
    double expected = new BigDecimal("48001")
        .divide(new BigDecimal("4800"), java.math.MathContext.DECIMAL128).doubleValue();
    var mapped = clocks.map(journal(W + 250), "journal");
    assertEquals(expected, mapped.seconds(),
        "Exact wall interpolation may round only its final fractional-seconds result");
    assertNull(mapped.reason());
  }

  @Test void exactIncreasingIntervalDoesNotDisappearWhenRobotDoublesCoincide() {
    long first = Long.MAX_VALUE - 20;
    var log = log(TimestampedValue.atMicros(first, W), TimestampedValue.atMicros(first + 10, W + 10));
    var clocks = new SystemLogClocks(0, Double.MAX_VALUE);
    clocks.add(log, new SystemLogClocks.RobotTime(t -> t, true), true, false);
    clocks.sort();
    // A midpoint in a 10-us/10-us segment is exactly first+5 us. Decimal scaling is exact.
    double expected = BigDecimal.valueOf(first + 5).scaleByPowerOfTen(-6).doubleValue();
    var mapped = clocks.map(journal(W + 5), "journal");
    assertEquals(expected, mapped.seconds(),
        "An exact increasing interval remains invertible when its legacy doubles coincide");
    assertEquals("system_time", mapped.basis());
    assertNull(mapped.reason());
  }

  @Test void rawOddMicrosecondAnchorRoundsToTheIndependentDouble() {
    long raw = 9_007_199_254_740_993L;
    var clocks = new SystemLogClocks(0, Double.MAX_VALUE);
    clocks.add(log(TimestampedValue.atMicros(raw, W)),
        new SystemLogClocks.RobotTime(t -> t, true), true, false);
    clocks.sort();
    // Independently derived by Python Fraction(raw, 1000000): 0x1.0c6f7a0b5ed8ep+33.
    // Rounding raw to double first instead yields the adjacent ...ed8d bit pattern.
    assertEquals(0x4200c6f7a0b5ed8eL,
        Double.doubleToRawLongBits(clocks.map(journal(W), "journal").seconds()));
  }

  @Test void exactWallParserPreservesIsoZonesAndWrittenNanoseconds() {
    var clocks = clocks(at(10, W), at(20, W + 12_000_000));
    // ISO and journal write the same W+250.001us; robot time is 10 + 250.001us * 5/6.
    var expected = new BigDecimal("10").add(new BigDecimal("250.001")
        .divide(new BigDecimal("1200000"), java.math.MathContext.DECIMAL128)).doubleValue();
    assertEquals(expected, clocks.map("1700000000.000250001 synthetic", "journal").seconds());
    assertEquals(expected, clocks.map("2023-11-14T22:13:20.000250001Z synthetic", "text").seconds());
    assertEquals(expected, clocks.map("2023-11-14T17:13:20.000250001-05:00 synthetic", "text").seconds());
    assertNull(clocks.map("1700000000e0 synthetic", "journal").seconds(), "Exponent grammar stays refused");
    assertNull(clocks.map("9".repeat(400) + " synthetic", "journal").seconds(), "Nonfinite range stays refused");
    assertNull(clocks.map("Nov 14 22:13:20 synthetic", "text").seconds(), "A missing year/zone stays unguessed");
  }

  @Test void exactJournalParserKeepsAcceptedLongFractionalLiterals() {
    var clocks = clocks(at(10, W), at(20, W + 12_000_000));
    // Extra nonzero precision is far below the final double's half ULP, but no digit cap refuses it.
    String literal = "1700000000.000250" + "0".repeat(5_000) + "1 synthetic";
    double expected = new BigDecimal("48001")
        .divide(new BigDecimal("4800"), java.math.MathContext.DECIMAL128).doubleValue();
    assertEquals(expected, clocks.map(literal, "journal").seconds());
  }

  private static void assertAmbiguous(SystemLogClocks.Mapped mapped) {
    assertNull(mapped.seconds(), "An ambiguous wall clock must not choose a robot time");
    assertNull(mapped.basis());
    assertTrue(mapped.reason().startsWith("Ambiguous wall-clock mapping:"), mapped.reason());
  }

  private static TimestampedValue at(long seconds, long epochMicros) {
    return TimestampedValue.atMicros(seconds * 1_000_000, epochMicros);
  }

  private static SystemLogClocks clocks(TimestampedValue... samples) {
    var log = log(samples);
    assertEquals(samples.length, WallClock.validReadings(log).size(),
        "This test concerns retained readings, not the existing clock-set jump policy");
    var clocks = new SystemLogClocks(0, 30);
    clocks.add(log, t -> t, true, false);
    clocks.sort();
    return clocks;
  }

  private static ParsedLog log(TimestampedValue... samples) {
    return new ParsedLog("synthetic-wall-clock.wpilog",
        Map.of("systemTime", new EntryInfo(1, "systemTime", "int64", "")),
        Map.of("systemTime", List.of(samples)), 0, 30);
  }

  private static String journal(long epochMicros) {
    return BigDecimal.valueOf(epochMicros).scaleByPowerOfTen(-6).toPlainString() + " synthetic";
  }
}
