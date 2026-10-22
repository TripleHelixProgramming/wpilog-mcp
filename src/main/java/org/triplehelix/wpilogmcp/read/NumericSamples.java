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
   * Bits that are equal for two samples exactly when their decoded values are equal, as the
   * values' own {@code equals} compares them: a double's bits with every NaN alike, and an
   * integer's own bits, since two integers past 2^53 can share a double.
   */
  default long valueBits(int index) {
    return Double.doubleToLongBits(number(index));
  }

  /** The samples from position {@code from} up to {@code to}; none when {@code to} is not past it. */
  static NumericSamples range(NumericSamples samples, int from, int to) {
    int size = Math.max(0, to - from);
    if (from == 0 && size == samples.size()) return samples;
    if (from < 0 || from + size > samples.size()) throw new IndexOutOfBoundsException("Range " + from + " to " + to);
    return new NumericSamples() {
      @Override public int size() { return size; }
      @Override public long timeUs(int index) { return samples.timeUs(from + java.util.Objects.checkIndex(index, size)); }
      @Override public double seconds(int index) { return samples.seconds(from + java.util.Objects.checkIndex(index, size)); }
      @Override public double number(int index) { return samples.number(from + java.util.Objects.checkIndex(index, size)); }
      @Override public long valueBits(int index) { return samples.valueBits(from + java.util.Objects.checkIndex(index, size)); }
    };
  }

  /** The samples' times with other numbers, one for each sample, as presentation computes them. */
  static NumericSamples withNumbers(NumericSamples samples, double[] numbers) {
    if (numbers.length != samples.size()) throw new IllegalArgumentException("One number for each sample");
    return new NumericSamples() {
      @Override public int size() { return numbers.length; }
      @Override public long timeUs(int index) { return samples.timeUs(index); }
      @Override public double seconds(int index) { return samples.seconds(index); }
      @Override public double number(int index) { return numbers[index]; }
    };
  }

  /**
   * Decoded values as the tools read them: numbers, and booleans as 1 or 0; a struct is projected
   * first, as today's callers do.
   */
  static NumericSamples of(List<TimestampedValue> values) {
    return new NumericSamples() {
      @Override public int size() { return values.size(); }
      @Override public long timeUs(int index) { return values.get(index).timestampUs(); }
      @Override public double seconds(int index) { return values.get(index).timestamp(); }
      @Override public double number(int index) {
        var value = values.get(index).value();
        return value instanceof Boolean b ? (b ? 1.0 : 0.0) : ((Number) value).doubleValue();
      }
      @Override public long valueBits(int index) {
        return values.get(index).value() instanceof Long l ? l : Double.doubleToLongBits(number(index));
      }
    };
  }

  /**
   * The samples as values, each made when it is read, with its logged coordinate and its number as
   * a Double, as a projection makes them: nothing is kept per sample.
   */
  static List<TimestampedValue> asValues(NumericSamples samples) {
    final class View extends java.util.AbstractList<TimestampedValue> implements java.util.RandomAccess {
      @Override
      public TimestampedValue get(int index) {
        java.util.Objects.checkIndex(index, samples.size());
        return TimestampedValue.atMicros(samples.timeUs(index), samples.number(index));
      }

      @Override
      public int size() {
        return samples.size();
      }
    }
    return new View();
  }
}
