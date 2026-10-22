/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.util.ArrayList;
import java.util.List;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.read.BucketFill;
import org.triplehelix.wpilogmcp.read.NumericSamples;

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
  public static final class Result {
    private final double bucketSec;
    private final List<Bucket> buckets;
    private final transient long[] startsUs;
    public Result(double bucketSec, List<Bucket> buckets) {
      this(bucketSec, buckets, buckets.stream().mapToLong(b -> Math.round(b.start() * 1e6)).toArray());
    }
    private Result(double bucketSec, List<Bucket> buckets, long[] startsUs) {
      this.bucketSec = bucketSec; this.buckets = List.copyOf(buckets); this.startsUs = startsUs;
    }
    public double bucketSec() { return bucketSec; }
    public List<Bucket> buckets() { return buckets; }
    /** Arrow retains the exact bucket coordinate even when its seconds label rounds. */
    public long startUs(int index) { return startsUs[index]; }
    @Override public boolean equals(Object other) {
      return other instanceof Result that && Double.compare(bucketSec, that.bucketSec) == 0 && buckets.equals(that.buckets);
    }
    @Override public int hashCode() { return 31 * Double.hashCode(bucketSec) + buckets.hashCode(); }
    @Override public String toString() { return "Result[bucketSec=" + bucketSec + ", buckets=" + buckets + "]"; }
  }

  /**
   * Buckets a numeric series that is already in time order and already within the window.
   *
   * <p>The window runs from {@code startTime} to {@code endTime}; a null bound is the first or
   * last sample's exact logged microsecond, so a request with no window buckets the series over
   * its own span. Explicit second bounds use the data endpoint's existing nearest-microsecond
   * conversion. Derived clocks keep that same conversion; their timestamps are not relabeled as logged. A
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
    return of(NumericSamples.of(values), startTime, endTime, maxPoints);
  }

  /**
   * Buckets samples from a decoded log or a column file alike, by the one bucket rule every
   * overview shares; see {@link #of(List, Double, Double, int)}.
   */
  public static Result of(NumericSamples samples, Double startTime, Double endTime, int maxPoints) {
    if (maxPoints < 1) throw new IllegalArgumentException("max_points must be at least 1");
    if (samples.size() == 0) return new Result(0, List.of());
    if (startTime != null && endTime != null && endTime < startTime)
      throw new IllegalArgumentException("the window ends before it starts");
    long start = startTime != null ? Math.round(startTime * 1e6) : samples.timeUs(0);
    long end = endTime != null ? Math.round(endTime * 1e6) : samples.timeUs(samples.size() - 1);
    if (end < start) throw new IllegalArgumentException("the window ends before it starts");
    double spanUs = java.math.BigInteger.valueOf(end).subtract(java.math.BigInteger.valueOf(start)).doubleValue();
    double bucketSec = spanUs / 1e6 / maxPoints;

    var buckets = new ArrayList<Bucket>();
    var starts = new ArrayList<Long>();
    var fill = new BucketFill(start, end, maxPoints);
    for (int i = 0; i < samples.size(); i++) {
      var closed = fill.add(samples.timeUs(i), samples.number(i));
      if (closed != null) addBucket(buckets, starts, closed);
    }
    addBucket(buckets, starts, fill.finish());
    return new Result(bucketSec, buckets, starts.stream().mapToLong(Long::longValue).toArray());
  }

  private static void addBucket(List<Bucket> buckets, List<Long> starts, BucketFill.Bucket closed) {
    starts.add(closed.startUs());
    buckets.add(bucket(closed.startUs() / 1e6, closed.values()));
  }

  private static Bucket bucket(double start, org.triplehelix.wpilogmcp.read.NumericBucket value) {
    return new Bucket(start, Math.toIntExact(value.count()), value.min(), value.max(), value.mean(), value.first(), value.last());
  }
}
