/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.util.List;
import java.util.Objects;

/** Exact recorded samples of one explicitly named entry. Empty windows means the whole entry.
 * Windows are disjoint and sorted, with an explicit right boundary; no hold, interpolation,
 * coercion or reduction happens implicitly. Projection and reduction are explicit opt-ins. */
public record ReadQuery(String entry, List<Window> windows, Order order, int batchRecords, int batchBytes, String field, Reduction reduction, int firstOrdinal) {
  /** Explicit min/max/mean buckets, independently in each requested window. */
  public record Reduction(int maxPoints) {
    public Reduction {
      if (maxPoints < 1 || maxPoints > 4096) throw new IllegalArgumentException("max_points must be 1..4096");
    }
  }
  public ReadQuery(String entry, List<Window> windows, Order order, int batchRecords, int batchBytes) {
    this(entry, windows, order, batchRecords, batchBytes, null, null, 0);
  }
  public ReadQuery(String entry, List<Window> windows, Order order, int batchRecords, int batchBytes, String field, Reduction reduction) {
    this(entry, windows, order, batchRecords, batchBytes, field, reduction, 0);
  }
  public ReadQuery fromOrdinal(int first) { return new ReadQuery(entry, windows, order, batchRecords, batchBytes, field, reduction, first); }
  public ReadQuery project(String path) { return new ReadQuery(entry, windows, order, batchRecords, batchBytes, path, reduction, firstOrdinal); }
  public ReadQuery reduce(int maxPoints) { return new ReadQuery(entry, windows, order, batchRecords, batchBytes, field, new Reduction(maxPoints), firstOrdinal); }
  public enum Order { TIME, FILE }
  public record Window(long startUs, long endUs, boolean endInclusive) {
    public Window {
      if (endUs < startUs) throw new IllegalArgumentException("Window end precedes start");
    }
    public boolean contains(long timestampUs) {
      return TimeWindows.contains(startUs, endUs, endInclusive, timestampUs);
    }
  }
  public ReadQuery {
    if (entry == null || entry.isEmpty()) throw new IllegalArgumentException("An entry name is required");
    windows = List.copyOf(windows); Objects.requireNonNull(order);
    if (windows.size() > 4096) throw new IllegalArgumentException("At most 4096 windows are allowed");
    if (firstOrdinal < 0) throw new IllegalArgumentException("first_ordinal must be nonnegative");
    if (field != null) FieldPath.parse(field);
    if (reduction != null && order != Order.TIME) throw new IllegalArgumentException("Reduction requires time order");
    for (int i = 1; i < windows.size(); i++) {
      var previous = windows.get(i - 1); var next = windows.get(i);
      if (next.startUs() < previous.endUs()
          || next.startUs() == previous.endUs() && previous.endInclusive())
        throw new IllegalArgumentException("Windows must be sorted and disjoint");
    }
    if (batchRecords < 1 || batchRecords > 4096) throw new IllegalArgumentException("batchRecords must be 1..4096");
    if (batchBytes < 1 || batchBytes > 4 << 20) throw new IllegalArgumentException("batchBytes must be 1..4194304");
  }
  public static ReadQuery all(String entry) { return new ReadQuery(entry, List.of(), Order.TIME, 4096, 4 << 20); }
  public static ReadQuery window(String entry, long startUs, long endUs, boolean endInclusive) {
    return new ReadQuery(entry, List.of(new Window(startUs, endUs, endInclusive)), Order.TIME, 4096, 4 << 20);
  }
}
