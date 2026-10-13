/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.util.List;

/** Sorted, disjoint windows can be selected without examining every window for each record. */
public final class TimeWindows {
  private TimeWindows() {}
  /** One half-open/closed-end rule, retaining each caller's original numeric representation. */
  private static boolean inside(boolean atOrAfterStart, boolean beforeEnd, boolean atEnd, boolean inclusive) {
    return atOrAfterStart && (beforeEnd || inclusive && atEnd);
  }
  public static boolean contains(long start, long end, boolean inclusive, long time) {
    return inside(time >= start, time < end, time == end, inclusive);
  }
  public static boolean contains(double start, double end, boolean inclusive, double time) {
    return inside(time >= start, time < end, time == end, inclusive);
  }
  /** First index whose caller-supplied comparison is no longer before the requested boundary.
   * Primitive predicates keep exact microseconds and legacy double seconds out of each other's clocks. */
  public static int bound(int size, java.util.function.IntPredicate before) {
    int low = 0, high = size;
    while (low < high) {
      int middle = low + (high - low) / 2;
      if (before.test(middle)) low = middle + 1; else high = middle;
    }
    return low;
  }
  public static int containing(List<ReadQuery.Window> windows, long timestampUs) {
    int low = 0, high = windows.size();
    while (low < high) {
      int middle = low + (high - low) / 2;
      if (windows.get(middle).startUs() <= timestampUs) low = middle + 1; else high = middle;
    }
    return low > 0 && windows.get(low - 1).contains(timestampUs) ? low - 1 : -1;
  }
}
