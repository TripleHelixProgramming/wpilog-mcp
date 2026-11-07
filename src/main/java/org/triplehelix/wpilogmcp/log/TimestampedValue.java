/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

/**
 * A legacy seconds view of one sample, retaining a logged integer coordinate when available.
 * Adjacent large microsecond timestamps may have the same double seconds. Keeping that fact
 * through field projection prevents reversed first/last samples and inconsistent buckets.
 * The coordinate is transient so existing JSON still contains only timestamp and value.
 *
 * @since 0.4.0
 */
public final class TimestampedValue {
  private final double timestamp;
  private final Object value;
  private final transient long loggedMicros;
  private final transient boolean exactMicros;

  /** Derived clocks retain their existing double seconds; they are not relabeled as logged. */
  public TimestampedValue(double timestamp, Object value) {
    this(timestamp, value, 0, false);
  }
  private TimestampedValue(double timestamp, Object value, long micros, boolean exact) {
    this.timestamp = timestamp; this.value = value; loggedMicros = micros; exactMicros = exact;
  }
  public static TimestampedValue atMicros(long timestampUs, Object value) {
    return new TimestampedValue(timestampUs / 1_000_000.0, value, timestampUs, true);
  }
  public double timestamp() { return timestamp; }
  public Object value() { return value; }
  /** Logged coordinates are exact; derived seconds retain the data endpoint's existing rounding. */
  public long timestampUs() { return exactMicros ? loggedMicros : Math.round(timestamp * 1e6); }
  /** A projection changes the payload, never the sample's original coordinate. */
  public TimestampedValue withValue(Object replacement) {
    return new TimestampedValue(timestamp, replacement, loggedMicros, exactMicros);
  }

  private static int compare(TimestampedValue first, TimestampedValue second) {
    if (first.exactMicros && second.exactMicros) return Long.compare(first.loggedMicros, second.loggedMicros);
    // BigDecimal merges signed zero; retain Double's order so exact zero cannot make
    // derived -0.0 equal to +0.0 while those same derived values compare differently.
    if (first.timestamp == 0 && second.timestamp == 0)
      return Double.compare(first.timestamp, second.timestamp);
    if (first.exactMicros == second.exactMicros || !Double.isFinite(first.timestamp) || !Double.isFinite(second.timestamp))
      return Double.compare(first.timestamp, second.timestamp);
    // Mixed provenance is uncommon, but comparing only rounded doubles would not be transitive.
    var a = first.exactMicros ? java.math.BigDecimal.valueOf(first.loggedMicros)
        : java.math.BigDecimal.valueOf(first.timestamp).scaleByPowerOfTen(6);
    var b = second.exactMicros ? java.math.BigDecimal.valueOf(second.loggedMicros)
        : java.math.BigDecimal.valueOf(second.timestamp).scaleByPowerOfTen(6);
    return a.compareTo(b);
  }

  /** Immutable chronological order; equal original coordinates retain their file order. */
  public static java.util.List<TimestampedValue> inTimeOrder(java.util.List<TimestampedValue> values) {
    for (int i = 1; i < values.size(); i++) {
      if (compare(values.get(i), values.get(i - 1)) < 0) {
        var sorted = new java.util.ArrayList<>(values);
        sorted.sort(TimestampedValue::compare);
        return java.util.Collections.unmodifiableList(sorted);
      }
    }
    return java.util.Collections.unmodifiableList(values);
  }

  // Preserve the legacy two-component value contract as well as its serialized form.
  @Override public boolean equals(Object other) {
    return other instanceof TimestampedValue that && Double.compare(timestamp, that.timestamp) == 0
        && java.util.Objects.equals(value, that.value);
  }
  @Override public int hashCode() { return 31 * Double.hashCode(timestamp) + java.util.Objects.hashCode(value); }
  @Override public String toString() { return "TimestampedValue[timestamp=" + timestamp + ", value=" + value + "]"; }
}
