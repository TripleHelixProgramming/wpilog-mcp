/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The shared bucket fill against the rule it replaces in every bucket loop: each sample's index
 * computed afresh, a new bucket whenever the index changes, and boundaries computed exactly with
 * big integers.
 */
class BucketFillTest {

  /** The buckets the loops computed before the fill: one index computation per sample. */
  private static List<BucketFill.Bucket> perSample(long start, long end, int points, long[] times, double[] values) {
    var out = new ArrayList<BucketFill.Bucket>();
    int index = -1;
    NumericBucket bucket = null;
    for (int i = 0; i < times.length; i++) {
      int next = BucketCoordinates.index(start, end, points, times[i]);
      if (next != index) {
        if (index >= 0) out.add(exact(start, end, points, index, bucket));
        index = next;
        bucket = new NumericBucket();
      }
      bucket.add(values[i]);
    }
    if (index >= 0) out.add(exact(start, end, points, index, bucket));
    return out;
  }

  private static BucketFill.Bucket exact(long start, long end, int points, int index, NumericBucket bucket) {
    return new BucketFill.Bucket(index, BucketCoordinates.exactBoundary(start, end, points, index),
        BucketCoordinates.exactBoundary(start, end, points, index + 1), bucket);
  }

  private static List<BucketFill.Bucket> filled(long start, long end, int points, long[] times, double[] values) {
    var out = new ArrayList<BucketFill.Bucket>();
    var fill = new BucketFill(start, end, points);
    for (int i = 0; i < times.length; i++) {
      var closed = fill.add(times[i], values[i]);
      if (closed != null) out.add(closed);
    }
    var last = fill.finish();
    if (last != null) out.add(last);
    assertNull(fill.finish(), "a finished fill holds no bucket");
    return out;
  }

  private static void assertSame(List<BucketFill.Bucket> expected, List<BucketFill.Bucket> actual, String where) {
    assertEquals(expected.size(), actual.size(), where + ": buckets");
    for (int i = 0; i < expected.size(); i++) {
      var e = expected.get(i);
      var a = actual.get(i);
      var at = where + " bucket " + i;
      assertEquals(e.index(), a.index(), at + ": index");
      assertEquals(e.startUs(), a.startUs(), at + ": start");
      assertEquals(e.endUs(), a.endUs(), at + ": end");
      assertEquals(e.values().count(), a.values().count(), at + ": count");
      assertEquals(e.values().finite(), a.values().finite(), at + ": finite");
      assertEquals(bits(e.values().min()), bits(a.values().min()), at + ": min");
      assertEquals(bits(e.values().max()), bits(a.values().max()), at + ": max");
      assertEquals(bits(e.values().mean()), bits(a.values().mean()), at + ": mean");
      assertEquals(Double.doubleToRawLongBits(e.values().first()), Double.doubleToRawLongBits(a.values().first()), at + ": first");
      assertEquals(Double.doubleToRawLongBits(e.values().last()), Double.doubleToRawLongBits(a.values().last()), at + ": last");
    }
  }

  private static Long bits(Double value) {
    return value == null ? null : Double.doubleToRawLongBits(value);
  }

  /**
   * Windows of every span from none to one beyond a long's range, bucket counts from one to the
   * most an int holds, and times in order, out of order, repeated and outside the window.
   */
  @Test
  void fillingGivesTheBucketsOfComputingEverySampleAfresh() {
    var random = new Random(23);
    long[] spans = {0, 1, 2, 3, 7, 999, 1_000_000, 3_600_000_000L, 1L << 40, Long.MAX_VALUE / 3};
    int[] counts = {1, 2, 3, 7, 1000, 65_536, Integer.MAX_VALUE};
    int cases = 0;
    for (int round = 0; round < 400; round++) {
      long span = spans[round % spans.length];
      int points = counts[random.nextInt(counts.length)];
      long start = random.nextBoolean() ? random.nextInt(1_000_000) : -random.nextLong(1L << 50);
      long end = start + span;
      int n = 1 + random.nextInt(round % 5 == 0 ? 20_000 : 400);
      var times = new long[n];
      var values = new double[n];
      for (int i = 0; i < n; i++) {
        long t = span == 0 ? start : start + (long) (random.nextDouble() * span);
        if (random.nextInt(50) == 0) t = random.nextBoolean() ? start - 1 - random.nextInt(1000) : end + random.nextInt(1000);
        times[i] = t;
        values[i] = switch (random.nextInt(40)) {
          case 0 -> Double.NaN;
          case 1 -> Double.POSITIVE_INFINITY;
          case 2 -> -0.0;
          default -> random.nextGaussian() * 1e3;
        };
      }
      if (round % 3 != 0) Arrays.sort(times);
      if (round % 7 == 1) for (int i = 1; i < n; i += 5) times[i] = times[i - 1];
      var where = "start " + start + ", span " + span + ", " + points + " points, " + n + " samples"
          + (round % 3 == 0 ? ", out of order" : "");
      assertSame(perSample(start, end, points, times, values), filled(start, end, points, times, values), where);
      cases++;
    }
    assertTrue(cases == 400);
  }

  /** The boundary's long arithmetic against the exact rule, overflow included. */
  @Test
  void boundariesInLongArithmeticEqualTheExactRule() {
    long[] starts = {Long.MIN_VALUE, -1_000_000_000_000L, -1, 0, 1, 1_000_000_000L, Long.MAX_VALUE - 10};
    long[] spans = {0, 1, 7, 1_000_000, 3_500_000, 1L << 40, 1L << 62, Long.MAX_VALUE};
    int[] points = {1, 2, 3, 7, 1000, 65_536, Integer.MAX_VALUE};
    int compared = 0;
    for (long start : starts) for (long span : spans) for (int count : points) {
      long end;
      try {
        end = Math.addExact(start, span);
      } catch (ArithmeticException beyond) {
        continue;
      }
      for (int index : new int[] {0, 1, count / 2, count - 1, count}) {
        if (index < 0 || index > count) continue;
        assertEquals(BucketCoordinates.exactBoundary(start, end, count, index),
            BucketCoordinates.boundary(start, end, count, index), start + " + " + span + " at " + index + "/" + count);
        compared++;
      }
    }
    var random = new Random(11);
    for (int i = 0; i < 100_000; i++) {
      long start = random.nextLong() >> random.nextInt(64);
      long span = random.nextLong() >>> (1 + random.nextInt(63));
      long end;
      try {
        end = Math.addExact(start, span);
      } catch (ArithmeticException beyond) {
        continue;
      }
      int count = 1 + random.nextInt(i % 2 == 0 ? 2000 : Integer.MAX_VALUE - 1);
      int index = random.nextInt(count) + random.nextInt(2);
      assertEquals(BucketCoordinates.exactBoundary(start, end, count, index),
          BucketCoordinates.boundary(start, end, count, index), start + " + " + span + " at " + index + "/" + count);
      compared++;
    }
    assertTrue(compared > 50_000, "compared " + compared);
  }
}
