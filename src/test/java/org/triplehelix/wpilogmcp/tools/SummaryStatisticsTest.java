/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The summary statistics every tool reports, against exact decimal arithmetic on the same
 * doubles: telemetry-like series whose values sit far from zero relative to their spread, where
 * a plain one-pass deviation loses digits, a sum that cancels, combined summaries, and the
 * boundaries a single value, an empty set and the largest doubles reach.
 */
class SummaryStatisticsTest {
  private static final MathContext EXACT = new MathContext(60);

  private static BigDecimal exactMean(double[] values) {
    var sum = BigDecimal.ZERO;
    for (double v : values) sum = sum.add(new BigDecimal(v));
    return sum.divide(BigDecimal.valueOf(values.length), EXACT);
  }

  private static double exactStdDev(double[] values) {
    var mean = exactMean(values);
    var squares = BigDecimal.ZERO;
    for (double v : values) {
      var d = new BigDecimal(v).subtract(mean);
      squares = squares.add(d.multiply(d));
    }
    return squares.divide(BigDecimal.valueOf(values.length - 1), EXACT).sqrt(EXACT).doubleValue();
  }

  private static long ulps(double actual, double expected) {
    return Math.round(Math.abs(actual - expected) / Math.ulp(expected));
  }

  /** Synthetic series shaped like telemetry, seeded so every run sees the same doubles. */
  private static Map<String, double[]> series() {
    var r = new Random(1);
    var out = new LinkedHashMap<String, double[]>();
    out.put("battery 12 V +- 0.5", fill(3000, i -> 12 + 0.5 * r.nextGaussian()));
    out.put("encoder count 1e6 +- 10", fill(30000, i -> 1e6 + 10 * r.nextGaussian()));
    out.put("timestamp ramp 1e5 s in 20 ms steps", fill(30000, i -> 1e5 + i * 0.02));
    out.put("epoch microseconds ramp", fill(30000, i -> 1.7e15 + i * 20000.0));
    out.put("accumulated gyro 3600 deg +- 0.01", fill(30000, i -> 3600 + 0.01 * r.nextGaussian()));
    out.put("1e9 + U(0, 1e-3)", fill(30000, i -> 1e9 + 1e-3 * r.nextDouble()));
    out.put("1e8 +- 1e-2", fill(30000, i -> 1e8 + 1e-2 * r.nextGaussian()));
    out.put("signed around zero", fill(5000, i -> r.nextGaussian() * 1e-3 - 5e-4));
    return out;
  }

  private static double[] fill(int n, java.util.function.IntToDoubleFunction value) {
    var out = new double[n];
    for (int i = 0; i < n; i++) out[i] = value.applyAsDouble(i);
    return out;
  }

  @Test
  void theMeanAndDeviationAreExactToTheLastPlacesOnTelemetry() {
    for (var series : series().entrySet()) {
      var values = series.getValue();
      var summary = SummaryStatistics.of(values);
      double mean = exactMean(values).doubleValue();
      double std = exactStdDev(values);
      assertTrue(ulps(summary.mean(), mean) <= 1, series.getKey() + ": mean off by " + ulps(summary.mean(), mean) + " ulp");
      assertTrue(ulps(summary.stdDev(), std) <= 2, series.getKey() + ": deviation off by " + ulps(summary.stdDev(), std) + " ulp");
      assertEquals(values.length, summary.count());
      assertEquals(java.util.Arrays.stream(values).min().orElseThrow(), summary.min());
      assertEquals(java.util.Arrays.stream(values).max().orElseThrow(), summary.max());
    }
  }

  /** A plain sum loses each 1 beside 1e16; the compensated sum keeps all four. */
  @Test
  void smallValuesAfterALargeOneAreKept() {
    var values = new double[] {1e16, 1, 1, 1, 1};
    assertEquals(exactMean(values).doubleValue(), SummaryStatistics.of(values).mean());
    double plain = 0;
    for (double v : values) plain += v;
    assertTrue(plain / values.length != exactMean(values).doubleValue(), "a plain sum would lose them");
  }

  /** The compensated sum's final correction moves this mean one place, onto the exact value. */
  @Test
  void theFinalCorrectionReachesTheExactMean() {
    var values = new double[] {1.00000000947E8, 1.00000000194E8, 1.00000000069E8, 1.00000000279E8, 1.00000000018E9};
    assertEquals(2.800000003338E8, exactMean(values).doubleValue());
    assertEquals(2.800000003338E8, SummaryStatistics.of(values).mean());
  }

  @Test
  void combinedSummariesAreTheSummaryOfEveryValue() {
    for (var series : series().entrySet()) {
      var values = series.getValue();
      var parts = new SummaryStatistics[] {new SummaryStatistics(), new SummaryStatistics(), new SummaryStatistics()};
      for (int i = 0; i < values.length; i++) parts[i * 3 / values.length].add(values[i]);
      var combined = new SummaryStatistics();
      for (var part : parts) combined.combine(part);
      double mean = exactMean(values).doubleValue();
      double std = exactStdDev(values);
      assertEquals(values.length, combined.count(), series.getKey());
      assertTrue(ulps(combined.mean(), mean) <= 2, series.getKey() + ": mean off by " + ulps(combined.mean(), mean) + " ulp");
      assertTrue(ulps(combined.stdDev(), std) <= 8, series.getKey() + ": deviation off by " + ulps(combined.stdDev(), std) + " ulp");
      assertEquals(java.util.Arrays.stream(values).min().orElseThrow(), combined.min());
      assertEquals(java.util.Arrays.stream(values).max().orElseThrow(), combined.max());
    }
    var values = new double[] {3, 1, 4, 1, 5};
    var into = new SummaryStatistics();
    into.combine(SummaryStatistics.of(values));
    var direct = SummaryStatistics.of(values);
    assertEquals(direct.mean(), into.mean(), "combining into an empty summary copies it");
    assertEquals(direct.stdDev(), into.stdDev());
    direct.combine(new SummaryStatistics());
    assertEquals(SummaryStatistics.of(values).stdDev(), direct.stdDev(), "combining an empty summary changes nothing");
  }

  @Test
  void oneValueHasNoDeviationAndNoValueHasNoStatistic() {
    var one = SummaryStatistics.of(new double[] {42});
    assertEquals(1, one.count());
    assertEquals(42.0, one.mean());
    assertEquals(42.0, one.min());
    assertEquals(42.0, one.max());
    assertNull(one.stdDev());
    var none = new SummaryStatistics();
    assertEquals(0, none.count());
    assertNull(none.mean());
    assertNull(none.stdDev());
    assertNull(none.min());
    assertNull(none.max());
    assertEquals(Math.sqrt(2), SummaryStatistics.of(new double[] {11, 13}).stdDev());
  }

  /** Averaging the middle two of an even count by adding them first overflows near the top. */
  @Test
  void theMedianOfTheLargestDoublesIsFinite() {
    double big = 1.7e308;
    assertEquals(big, SummaryStatistics.median(new double[] {big, big}));
    assertEquals(2.5, SummaryStatistics.median(new double[] {1, 2, 3, 4}));
    assertEquals(3.0, SummaryStatistics.median(new double[] {1, 3, 7}));
    assertEquals(1.0, SummaryStatistics.percentile(new double[] {1, 2, 3, 4}, 0));
    assertEquals(4.0, SummaryStatistics.percentile(new double[] {1, 2, 3, 4}, 1));
    assertEquals(1.75, SummaryStatistics.percentile(new double[] {1, 2, 3, 4}, 0.25));
  }

  /** As the JDK's own summaries do, a non-finite value carries through rather than throwing. */
  @Test
  void aNonFiniteValueCarriesThrough() {
    var summary = SummaryStatistics.of(new double[] {1, Double.POSITIVE_INFINITY});
    assertEquals(Double.POSITIVE_INFINITY, summary.mean());
    assertEquals(Double.POSITIVE_INFINITY, summary.max());
  }

  /**
   * 0 and 90 degrees: the mean direction is 45, R = sqrt(0.5), and the circular deviation is
   * sqrt(-2 ln R) = sqrt(ln 2) radians. Three directions a third of a turn apart have no mean.
   */
  @Test
  void circularStatisticsMatchTheirFormulas() {
    var right = new SummaryStatistics.Circular(360);
    right.add(0);
    right.add(90);
    right.add(Double.NaN);
    assertEquals(2, right.count());
    assertEquals(45.0, right.mean(), 1e-12);
    assertEquals(Math.sqrt(0.5), right.resultantLength(), 1e-15);
    assertEquals(Math.toDegrees(Math.sqrt(Math.log(2))), right.stdDev(), 1e-12);
    var even = new SummaryStatistics.Circular(360);
    for (double a : new double[] {0, 120, 240}) even.add(a);
    assertTrue(even.resultantLength() < 1e-12);
    assertNull(even.mean());
    assertNull(even.stdDev());
    var halves = new SummaryStatistics.Circular(2 * Math.PI);
    var other = new SummaryStatistics.Circular(2 * Math.PI);
    halves.add(3.1);
    other.add(-3.1);
    halves.combine(other);
    assertEquals(Math.PI, Math.abs(halves.mean()), 1e-12);
    assertNull(new SummaryStatistics.Circular(360).mean());
  }
}
