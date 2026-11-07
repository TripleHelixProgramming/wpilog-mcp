/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.read.ReadQuery.Window;
import org.triplehelix.wpilogmcp.sync.*;

class SessionClockTest {
  private static SyncResult clock(long offset, double drift, double reference) {
    return new SyncResult(offset, 1, ConfidenceLevel.HIGH, List.of(), SyncMethod.USER_PROVIDED,
        "Fixture clock", drift, reference);
  }
  @Test void offsetInverseIsExactBeyondDoubleIntegerPrecisionAndAtLongEdges() {
    long time = 9_007_199_254_740_993L;
    assertEquals(List.of(new Window(time - 1, time, true)), SessionClock.sourceWindows(
        List.of(new Window(time, time + 2, false)), List.of(clock(1, 0, 0))));
    assertEquals(List.of(new Window(Long.MAX_VALUE - 1, Long.MAX_VALUE, true)), SessionClock.sourceWindows(
        List.of(new Window(Long.MAX_VALUE - 2, Long.MAX_VALUE, true)), List.of(clock(-1, 0, 0))));
    assertTrue(SessionClock.sourceWindows(List.of(new Window(Long.MIN_VALUE, Long.MIN_VALUE, true)),
        List.of(clock(1, 0, 0))).isEmpty());
    assertTrue(SessionClock.sourceWindows(List.of(new Window(0, 0, false)), List.of(clock(1, 0, 0))).isEmpty());
  }
  @Test void inverseChainsIncludeExactEdgeSamplesAndMergeOverlappingMargins() {
    // First transform: y = x + 500000 + .001*(x - 500000).
    // Second: z = y - 250000. x=8500000 gives y=9008000, z=8758000.
    var chain = List.of(clock(500_000, 1_000_000, .5), clock(-250_000, 0, 0));
    var windows = SessionClock.sourceWindows(List.of(new Window(8_758_000, 8_758_000, true),
        new Window(8_758_001, 8_758_001, true)), chain);
    assertEquals(1, windows.size(), "Outward rounding must not read an edge record twice");
    assertTrue(windows.get(0).contains(8_500_000));
    assertTrue(windows.get(0).endUs() - windows.get(0).startUs() < 100);
  }
  @Test void negativeDriftAndFloatingRoundingNeverRemoveAnEdge() {
    // These assertions check interval containment independently of the cursor and its window
    // filter, including rounding plateaus where a strict floating inverse loses a microsecond.
    for (double drift : new double[] {-900_000_000, -1_000_000, 1_000_000, 900_000_000}) {
      var transform = clock(17, drift, .5);
      for (long center : new long[] {1_000_000, 9_007_199_254_740_993L}) {
        for (long x = center - 8; x < center + 8; x++) {
          long y = x + 17 + Math.round((x / 1_000_000. - .5) * drift / 1000.);
          long sourceTime = x;
          var intervals = SessionClock.sourceWindows(List.of(new Window(y, y, true)), List.of(transform));
          assertTrue(intervals.stream().anyMatch(w -> w.contains(sourceTime)), "Missing " + x + " at drift " + drift);
        }
      }
    }
  }
  @Test void saturatedDriftFallsBackToReadingRatherThanLosingData() {
    assertEquals(List.of(new Window(Long.MIN_VALUE, Long.MAX_VALUE, true)), SessionClock.sourceWindows(
        List.of(new Window(1, 2, true)), List.of(clock(0, Double.MAX_VALUE, 1))));
  }
}
