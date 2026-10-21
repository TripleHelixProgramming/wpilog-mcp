/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.triplehelix.wpilogmcp.read.BucketCoordinates;
import org.triplehelix.wpilogmcp.read.NumericBucket;

/**
 * An overview of a selection's window, bucketed as the data endpoint buckets decoded values: the
 * same integer-microsecond coordinates and the same per-bucket arithmetic, applied to the same
 * values in the same order, so every count, extreme and mean is the one the endpoint computes.
 *
 * <p>It reads every sample of the window. Block summaries would skip most of them, but a mean
 * summed block by block differs in its last bits from the endpoint's running sum. A sample's
 * bucket is computed only when it reaches the next bucket's first microsecond: samples come in
 * time order, and bucket {@code i} holds exactly the microseconds from its boundary up to the
 * next, so the division the coordinates need is paid once a bucket, not once a sample. Each
 * boundary is computed once, in long arithmetic where it cannot overflow.
 *
 * @param bucketSec each bucket's duration
 * @param buckets the buckets that hold samples, in time order
 */
public record ColumnOverview(double bucketSec, List<Bucket> buckets) {

  /**
   * One bucket. {@code min}, {@code max} and {@code mean} cover finite values and are null when
   * there are none; {@code first} and {@code last} are as logged, NaN included.
   */
  public record Bucket(long startUs, long count, long finite, Double min, Double max, Double mean,
      double first, double last) {}

  /**
   * Buckets the samples of {@code range}.
   *
   * @param startTime the window's start in seconds, or null for the first sample's time
   * @param endTime the window's end in seconds, or null for the last sample's time
   * @param transform applied to each value in read order before it is bucketed, as the endpoint
   *     unwraps an angle across the window; null for none
   */
  public static ColumnOverview of(ColumnFile.Selection selection, ColumnFile.Range range,
      Double startTime, Double endTime, int maxPoints, DoubleUnaryOperator transform) {
    if (maxPoints < 1) throw new IllegalArgumentException("max_points must be at least 1");
    if (range.isEmpty()) return new ColumnOverview(0, List.of());
    if (startTime != null && endTime != null && endTime < startTime)
      throw new IllegalArgumentException("the window ends before it starts");
    long start = startTime != null ? Math.round(startTime * 1e6) : selection.firstTimeUs(range);
    long end = endTime != null ? Math.round(endTime * 1e6) : selection.lastTimeUs(range);
    if (end < start) throw new IllegalArgumentException("the window ends before it starts");
    double spanUs = BigInteger.valueOf(end).subtract(BigInteger.valueOf(start)).doubleValue();

    var buckets = new ArrayList<Bucket>();
    var sink = new ColumnFile.Sink() {
      int index = -1;
      long first;
      long next = Long.MIN_VALUE;
      NumericBucket values = new NumericBucket();

      @Override
      public void accept(long timeUs, double number) {
        if (timeUs >= next) {
          int at = BucketCoordinates.index(start, end, maxPoints, timeUs);
          if (at != index) {
            if (index >= 0) buckets.add(bucket(first, values));
            first = at == index + 1 && index >= 0 ? next : boundary(start, end, maxPoints, at);
            index = at;
            values = new NumericBucket();
          }
          next = start == end || at + 1 >= maxPoints ? Long.MAX_VALUE : boundary(start, end, maxPoints, at + 1);
        }
        values.add(transform == null ? number : transform.applyAsDouble(number));
      }
    };
    selection.forEach(range, sink);
    buckets.add(bucket(sink.first, sink.values));
    return new ColumnOverview(spanUs / 1e6 / maxPoints, List.copyOf(buckets));
  }

  /**
   * {@link BucketCoordinates#boundary} in long arithmetic: start + ceil(span * index / points)
   * is exact wherever no step overflows, and the shared rule computes the rest.
   */
  static long boundary(long start, long end, int points, int index) {
    try {
      long scaled = Math.addExact(Math.multiplyExact(Math.subtractExact(end, start), index), points - 1);
      return Math.addExact(start, scaled / points);
    } catch (ArithmeticException overflow) {
      return BucketCoordinates.boundary(start, end, points, index);
    }
  }

  private static Bucket bucket(long first, NumericBucket values) {
    return new Bucket(first, values.count(), values.finite(), values.min(), values.max(), values.mean(),
        values.first(), values.last());
  }
}
