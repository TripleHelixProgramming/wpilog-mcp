/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/** The bucketing rule, on series whose answers are worked out by hand. */
class BucketsTest {

  private static List<TimestampedValue> series(double... timesAndValues) {
    var out = new ArrayList<TimestampedValue>();
    for (int i = 0; i < timesAndValues.length; i += 2) {
      out.add(new TimestampedValue(timesAndValues[i], timesAndValues[i + 1]));
    }
    return out;
  }

  @Test
  @DisplayName("the window is divided into equal buckets, each with its count, extremes, mean, first, and last")
  void equalBuckets() {
    // Ten samples at 0..9 s, values 0..9: four buckets of 2.25 s over the series' own span
    var values = new ArrayList<TimestampedValue>();
    for (int i = 0; i < 10; i++) values.add(new TimestampedValue(i, (double) i));
    var result = Buckets.of(values, null, null, 4);
    assertEquals(2.25, result.bucketSec(), 1e-12);
    var b = result.buckets();
    assertEquals(4, b.size());
    // [0, 2.25): 0, 1, 2; [2.25, 4.5): 3, 4; [4.5, 6.75): 5, 6; [6.75, 9]: 7, 8, 9
    assertEquals(new Buckets.Bucket(0.0, 3, 0.0, 2.0, 1.0, 0.0, 2.0), b.get(0));
    assertEquals(new Buckets.Bucket(2.25, 2, 3.0, 4.0, 3.5, 3.0, 4.0), b.get(1));
    assertEquals(new Buckets.Bucket(4.5, 2, 5.0, 6.0, 5.5, 5.0, 6.0), b.get(2));
    assertEquals(new Buckets.Bucket(6.75, 3, 7.0, 9.0, 8.0, 7.0, 9.0), b.get(3));
  }

  @Test
  @DisplayName("a sample at the window's end goes in the last bucket, not a bucket of its own")
  void endInclusive() {
    var result = Buckets.of(series(0, 1, 5, 2, 10, 3), 0.0, 10.0, 2);
    assertEquals(2, result.buckets().size());
    assertEquals(new Buckets.Bucket(0.0, 1, 1.0, 1.0, 1.0, 1.0, 1.0), result.buckets().get(0));
    assertEquals(new Buckets.Bucket(5.0, 2, 2.0, 3.0, 2.5, 2.0, 3.0), result.buckets().get(1));
  }

  @Test
  @DisplayName("buckets with no samples are left out: a hold is not missing data")
  void emptyBucketsOmitted() {
    var result = Buckets.of(series(0, 1, 9, 2), 0.0, 10.0, 10);
    assertEquals(1.0, result.bucketSec(), 1e-12);
    assertEquals(2, result.buckets().size());
    assertEquals(0.0, result.buckets().get(0).start());
    assertEquals(9.0, result.buckets().get(1).start(), 1e-12);
  }

  @Test
  @DisplayName("a spike one sample wide is the maximum of its bucket, and the mean does not hide it")
  void spikeKept() {
    var values = new ArrayList<TimestampedValue>();
    for (int i = 0; i < 100; i++) values.add(new TimestampedValue(i * 0.02, i == 57 ? 40.0 : 12.0));
    var result = Buckets.of(values, null, null, 5);
    var spiked = result.buckets().get(2); // samples 40..59
    assertEquals(20, spiked.count());
    assertEquals(40.0, spiked.max());
    assertEquals(12.0, spiked.min());
    assertEquals((19 * 12.0 + 40.0) / 20, spiked.mean(), 1e-12);
    assertEquals(12.0, result.buckets().get(0).max());
  }

  @Test
  @DisplayName("a bucket with no finite sample has null extremes and mean, and its samples as logged")
  void nonFinite() {
    var result = Buckets.of(series(0, Double.NaN, 1, Double.POSITIVE_INFINITY, 5, 3, 6, Double.NaN),
        0.0, 6.0, 2);
    var first = result.buckets().get(0);
    assertEquals(2, first.count());
    assertNull(first.min());
    assertNull(first.max());
    assertNull(first.mean());
    assertTrue(Double.isNaN(first.first()));
    assertTrue(Double.isInfinite(first.last()));
    var second = result.buckets().get(1);
    assertEquals(2, second.count());
    assertEquals(3.0, second.min());
    assertEquals(3.0, second.mean());
    assertTrue(Double.isNaN(second.last()), "the last sample is reported as logged");
  }

  @Test
  @DisplayName("one bucket, a single sample, and a window of no duration")
  void degenerate() {
    var one = Buckets.of(series(0, 1, 1, 2, 2, 3), null, null, 1);
    assertEquals(2.0, one.bucketSec(), 1e-12);
    assertEquals(List.of(new Buckets.Bucket(0.0, 3, 1.0, 3.0, 2.0, 1.0, 3.0)), one.buckets());
    var single = Buckets.of(series(4, 7), null, null, 10);
    assertEquals(0.0, single.bucketSec());
    assertEquals(List.of(new Buckets.Bucket(4.0, 1, 7.0, 7.0, 7.0, 7.0, 7.0)), single.buckets());
    var instant = Buckets.of(series(4, 7, 4, 9), 4.0, 4.0, 3);
    assertEquals(List.of(new Buckets.Bucket(4.0, 2, 7.0, 9.0, 8.0, 7.0, 9.0)), instant.buckets());
    assertEquals(List.of(), Buckets.of(List.of(), null, null, 3).buckets());
  }

  @Test
  @DisplayName("a window wider than the samples still divides the window, not the samples' span")
  void windowBounds() {
    // Five buckets of 2 s: 3 s is in [2, 4) and 4 s in [4, 6), two buckets of one sample each
    var result = Buckets.of(series(3, 1, 4, 2), 0.0, 10.0, 5);
    assertEquals(2.0, result.bucketSec(), 1e-12);
    assertEquals(2, result.buckets().size());
    assertEquals(new Buckets.Bucket(2.0, 1, 1.0, 1.0, 1.0, 1.0, 1.0), result.buckets().get(0));
    assertEquals(new Buckets.Bucket(4.0, 1, 2.0, 2.0, 2.0, 2.0, 2.0), result.buckets().get(1));
  }

  @Test
  @DisplayName("bad arguments are refused")
  void badArguments() {
    assertThrows(IllegalArgumentException.class, () -> Buckets.of(series(0, 1), null, null, 0));
    assertThrows(IllegalArgumentException.class, () -> Buckets.of(series(0, 1), 5.0, 2.0, 3));
  }
}
