/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.util.List;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * A path's samples as numbers, read by position in the order the tools read them, with each
 * sample's microsecond and seconds. It is the one form the server's overviews and windows read,
 * whether the samples come from a decoded log or a column file, so both are bucketed and windowed
 * by the same code.
 */
public interface NumericSamples {
  int size();

  /** The sample's microsecond, as {@code TimestampedValue.timestampUs} gives it. */
  long timeUs(int index);

  /** The sample's seconds, as {@code TimestampedValue.timestamp} gives them. */
  double seconds(int index);

  /** The sample's value as {@code Number.doubleValue} converts the decoded value. */
  double number(int index);

  /**
   * Decoded values as the tools read them. Every value must be a {@link Number}; a boolean or a
   * struct is converted or projected first, as today's callers do.
   */
  static NumericSamples of(List<TimestampedValue> values) {
    return new NumericSamples() {
      @Override public int size() { return values.size(); }
      @Override public long timeUs(int index) { return values.get(index).timestampUs(); }
      @Override public double seconds(int index) { return values.get(index).timestamp(); }
      @Override public double number(int index) { return ((Number) values.get(index).value()).doubleValue(); }
    };
  }
}
