/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

/**
 * One window's buckets, filled with samples one at a time: the bucket rule every overview in the
 * server shares. The tools, the data endpoint, native reductions and column files all fill their
 * buckets here, so none of them can place a sample in another bucket or sum it differently.
 *
 * <p>A sample goes in the bucket {@link BucketCoordinates#index} gives it. The index is computed
 * only when a sample falls outside the current bucket's microseconds, from its boundary up to the
 * next, since every sample inside them has the current bucket's index: in time order the division
 * the coordinates need is paid once a bucket, not once a sample. A sample out of time order is
 * placed by the same rule, so the buckets are always those a computation for every sample gives.
 */
public final class BucketFill {
  /** A closed bucket: its index, its microseconds from {@code startUs} up to {@code endUs}, and
   * its arithmetic. */
  public record Bucket(int index, long startUs, long endUs, NumericBucket values) {}

  private final long startUs;
  private final long endUs;
  private final int points;
  private int index = -1;
  /** Every microsecond from {@code low} up to {@code high} has the current bucket's index. */
  private long low;
  private long high;
  private NumericBucket values;

  public BucketFill(long startUs, long endUs, int points) {
    if (points < 1 || endUs < startUs) throw new IllegalArgumentException("Invalid bucket window or count");
    this.startUs = startUs;
    this.endUs = endUs;
    this.points = points;
  }

  /** The current bucket's index, or -1 before the first sample and after {@link #finish}. */
  public int index() {
    return index;
  }

  /** Adds a sample, and returns the bucket it closed when it opened another; otherwise null. */
  public Bucket add(long timeUs, double value) {
    Bucket closed = null;
    if (index < 0 || timeUs < low || timeUs >= high) {
      int at = BucketCoordinates.index(startUs, endUs, points, timeUs);
      if (at != index) {
        if (index >= 0) closed = close();
        index = at;
        values = new NumericBucket();
        // The first bucket also holds every time before the window, the last every time after.
        boolean single = startUs == endUs;
        low = at == 0 || single ? Long.MIN_VALUE : BucketCoordinates.boundary(startUs, endUs, points, at);
        high = at + 1 >= points || single ? Long.MAX_VALUE : BucketCoordinates.boundary(startUs, endUs, points, at + 1);
      }
    }
    values.add(value);
    return closed;
  }

  /** Closes the current bucket and returns it; null when no sample was added since the last. */
  public Bucket finish() {
    if (index < 0) return null;
    var closed = close();
    index = -1;
    return closed;
  }

  private Bucket close() {
    return new Bucket(index, BucketCoordinates.boundary(startUs, endUs, points, index),
        BucketCoordinates.boundary(startUs, endUs, points, index + 1), values);
  }
}
