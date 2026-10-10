/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

/**
 * A value with an associated timestamp from a log entry.
 *
 * @param timestamp The timestamp in seconds
 * @param value The value (can be primitive, String, or decoded struct Map)
 * @since 0.4.0
 */
public record TimestampedValue(double timestamp, Object value) {
  /**
   * Immutable time order for analysis; equal timestamps retain their file order. The scan's
   * offsets stay in byte order for resume. Ordinary ordered entries require no sorting copy.
   */
  public static java.util.List<TimestampedValue> inTimeOrder(java.util.List<TimestampedValue> values) {
    for (int i = 1; i < values.size(); i++) {
      if (values.get(i).timestamp() < values.get(i - 1).timestamp()) {
        var sorted = new java.util.ArrayList<>(values);
        sorted.sort(java.util.Comparator.comparingDouble(TimestampedValue::timestamp));
        return java.util.Collections.unmodifiableList(sorted);
      }
    }
    return java.util.Collections.unmodifiableList(values);
  }
}
