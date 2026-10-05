/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.util.ArrayList;
import java.util.List;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * A numeric series reduced to at most {@code max_points} buckets of equal duration, for a reader
 * that cannot take every sample: {@code read_entry} with {@code max_points}, and the data
 * endpoint's bucketed form. One rule, shared, so a bucket the assistant reads over MCP and a
 * bucket the viewer draws from the data endpoint are the same bucket.
 *
 * <p>The window is divided into {@code max_points} buckets of equal duration, and each bucket
 * that holds samples reports its start, its sample count, the minimum, maximum, and mean of its
 * finite samples, and its first and last samples as logged. The extremes are kept because a
 * spike one sample wide is the thing a person looks for, and a mean would hide it. The first and
 * last samples let a reader draw a change-only entry's holds correctly across a bucket. A bucket
 * with no finite sample has no minimum, maximum, or mean: those are null, never zero and never
 * NaN, as the project's rule on uncomputable values requires; its first and last are the samples
 * as logged, NaN included, since a logged value is a fact.
 *
 * <p>Buckets with no samples are left out rather than reported as empty: a gap in a change-only
 * entry is a hold, not missing data, and an empty bucket would read as the latter.
 */
public final class Buckets {
  private Buckets() {}

  /**
   * One bucket.
   *
   * @param start The bucket's start, in seconds
   * @param count Samples in the bucket, finite or not
   * @param min The least finite sample, or null when none is finite
   * @param max The greatest finite sample, or null when none is finite
   * @param mean The mean of the finite samples, or null when none is finite
   * @param first The first sample in time order, as logged
   * @param last The last sample in time order, as logged
   */
  public record Bucket(double start, int count, Double min, Double max, Double mean,
      double first, double last) {}

  /** The buckets of a window: their duration, and the ones that hold samples. */
  public record Result(double bucketSec, List<Bucket> buckets) {}

  /**
   * Buckets a numeric series that is already in time order and already within the window.
   *
   * <p>The window runs from {@code startTime} to {@code endTime}; a null bound is the first or
   * last sample's time, so a request with no window buckets the series over its own span. A
   * window of no duration (one instant, or a single sample with no bounds) is one bucket. The
   * last bucket includes the window's end, so a sample at {@code endTime} is counted.
   *
   * @param values The samples, each value a {@link Number}, in time order, within the window
   * @param startTime The window's start, or null for the first sample's time
   * @param endTime The window's end, or null for the last sample's time
   * @param maxPoints How many buckets at most, at least 1
   */
  public static Result of(List<TimestampedValue> values, Double startTime, Double endTime,
      int maxPoints) {
    if (maxPoints < 1) throw new IllegalArgumentException("max_points must be at least 1");
    if (values.isEmpty()) return new Result(0, List.of());
    double start = startTime != null ? startTime : values.get(0).timestamp();
    double end = endTime != null ? endTime : values.get(values.size() - 1).timestamp();
    if (end < start) throw new IllegalArgumentException("the window ends before it starts");
    double span = end - start;
    double bucketSec = span > 0 ? span / maxPoints : 0;

    var buckets = new ArrayList<Bucket>();
    int index = -1;
    int count = 0;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    double sum = 0;
    int finite = 0;
    double first = 0;
    double last = 0;
    for (var tv : values) {
      double t = tv.timestamp();
      double v = ((Number) tv.value()).doubleValue();
      int i = bucketSec > 0 ? (int) Math.min(maxPoints - 1, Math.floor((t - start) / bucketSec)) : 0;
      if (i < 0) i = 0; // a sample before the window's start, if a caller passes one, counts first
      if (i != index) {
        if (index >= 0) {
          buckets.add(bucket(start + index * bucketSec, count, min, max, sum, finite, first, last));
        }
        index = i;
        count = 0;
        min = Double.POSITIVE_INFINITY;
        max = Double.NEGATIVE_INFINITY;
        sum = 0;
        finite = 0;
        first = v;
      }
      count++;
      last = v;
      if (Double.isFinite(v)) {
        finite++;
        sum += v;
        if (v < min) min = v;
        if (v > max) max = v;
      }
    }
    buckets.add(bucket(start + index * bucketSec, count, min, max, sum, finite, first, last));
    return new Result(bucketSec, buckets);
  }

  private static Bucket bucket(double start, int count, double min, double max, double sum,
      int finite, double first, double last) {
    return finite > 0
        ? new Bucket(start, count, min, max, sum / finite, first, last)
        : new Bucket(start, count, null, null, null, first, last);
  }
}
