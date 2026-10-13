/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.revlog;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/** Native readers retain two doubles per sample, not a record, a boxed Double and a list slot.
 * Fixed chunks avoid a doubling array's transient copy. The parser alone can append; clients
 * see an immutable List whose small pages materialize TimestampedValue objects on demand. */
final class PackedSignalValues extends AbstractList<TimestampedValue> implements RandomAccess {
  static final int CHUNK_SAMPLES = 64;
  static final long CHUNK_BYTES = 2L * Double.BYTES * CHUNK_SAMPLES + 80;
  static final class Budget {
    final long limit;
    long used;
    Budget(long limit) { this.limit = limit; }
    void reserve(long bytes) {
      if (bytes > limit - used) throw new RevLogParser.SampleLimitException("REV decoded memory limit exceeded: " + limit + " bytes");
      used += bytes;
    }
  }
  private final List<double[]> chunks = new ArrayList<>();
  private final Budget budget;
  private int count;
  private long bytes;

  PackedSignalValues(Budget budget, String key) {
    this.budget = budget;
    bytes = 256 + 2L * key.length(); budget.reserve(bytes);
  }
  void append(double time, double value) {
    if (count % CHUNK_SAMPLES == 0) {
      budget.reserve(CHUNK_BYTES); bytes += CHUNK_BYTES;
      chunks.add(new double[2 * CHUNK_SAMPLES]);
    }
    var chunk = chunks.get(count / CHUNK_SAMPLES); int index = 2 * (count % CHUNK_SAMPLES);
    chunk[index] = time; chunk[index + 1] = value; count++;
  }
  long bytes() { return bytes; }
  @Override public int size() { return count; }
  @Override public TimestampedValue get(int index) {
    Objects.checkIndex(index, count);
    var chunk = chunks.get(index / CHUNK_SAMPLES); int position = 2 * (index % CHUNK_SAMPLES);
    return new TimestampedValue(chunk[position], chunk[position + 1]);
  }
}
