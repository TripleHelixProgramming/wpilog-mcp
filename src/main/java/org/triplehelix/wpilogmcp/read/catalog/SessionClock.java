/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.triplehelix.wpilogmcp.read.ReadQuery.Window;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/** Conservative inverse selection, followed by the existing exact forward transform at emission.
 * Inverting in double alone can drop edge samples above 2^53 or after drift rounding. Account for
 * each floating operation's error over the entire long domain, then divide outwards in decimal.
 * This runs once per window, never per record. Extreme coefficients fall back to a complete read. */
final class SessionClock {
  private SessionClock() {}
  private static final BigDecimal MIN = BigDecimal.valueOf(Long.MIN_VALUE);
  private static final BigDecimal MAX = BigDecimal.valueOf(Long.MAX_VALUE);
  private static final Window ALL = new Window(Long.MIN_VALUE, Long.MAX_VALUE, true);

  static List<Window> sourceWindows(List<Window> windows, List<SyncResult> clock) {
    if (clock.isEmpty() || windows.isEmpty()) return windows;
    var result = new ArrayList<Window>();
    for (var window : windows) {
      if (!window.endInclusive() && window.startUs() == window.endUs()) continue;
      BigDecimal low = BigDecimal.valueOf(window.startUs());
      BigDecimal high = BigDecimal.valueOf(window.endInclusive() ? window.endUs() : window.endUs() - 1);
      for (int i = clock.size() - 1; i >= 0; i--) {
        var step = clock.get(i);
        var offset = BigDecimal.valueOf(step.offsetMicros());
        if (step.driftRateNanosPerSec() == 0 || step.referenceTimeSec() == 0) {
          low = low.subtract(offset); high = high.subtract(offset);
        } else {
          double error = errorBound(step);
          if (!Double.isFinite(error)) return List.of(ALL);
          // new BigDecimal(double) retains the exact recorded binary coefficient, rather
          // than its shortest printable decimal. The forward transform uses that double.
          var drift = new BigDecimal(step.driftRateNanosPerSec());
          var scale = BigDecimal.ONE.add(drift.scaleByPowerOfTen(-9));
          var intercept = offset.subtract(new BigDecimal(step.referenceTimeSec()).multiply(drift).scaleByPowerOfTen(-3));
          var margin = new BigDecimal(error);
          low = low.subtract(intercept).subtract(margin).divide(scale, 0, RoundingMode.FLOOR);
          high = high.subtract(intercept).add(margin).divide(scale, 0, RoundingMode.CEILING);
        }
        if (low.compareTo(MAX) > 0 || high.compareTo(MIN) < 0) { low = null; break; }
        low = low.max(MIN); high = high.min(MAX);
      }
      if (low != null) result.add(new Window(low.longValueExact(), high.longValueExact(), true));
    }
    // Outward rounding can overlap adjacent query windows. Read each source record once;
    // the original disjoint windows still decide membership after transformation.
    result.sort(java.util.Comparator.comparingLong(Window::startUs));
    var merged = new ArrayList<Window>();
    for (var window : result) {
      if (!merged.isEmpty() && window.startUs() <= merged.get(merged.size() - 1).endUs()) {
        var previous = merged.remove(merged.size() - 1);
        merged.add(new Window(previous.startUs(), Math.max(previous.endUs(), window.endUs()), true));
      } else merged.add(window);
    }
    return List.copyOf(merged);
  }

  private static double errorBound(SyncResult step) {
    // SyncResult: round(((long-as-double / 1e6) - reference) * drift / 1000).
    // A full ulp at each operation (twice the worst round-to-nearest error), plus one
    // microsecond for Math.round, deliberately overestimates error without guessing a margin.
    double seconds = Math.nextUp((double) Long.MAX_VALUE / 1_000_000.);
    double input = Math.nextUp(2048. / 1_000_000. + Math.ulp(seconds));
    double delta = Math.nextUp(seconds + Math.abs(step.referenceTimeSec()));
    double subtract = Math.nextUp(input + Math.ulp(delta));
    double drift = Math.abs(step.driftRateNanosPerSec());
    double product = Math.nextUp(delta * drift);
    // Math.round saturates outside the long range, where an affine inverse is not valid.
    if (product / 1000. >= Long.MAX_VALUE) return Double.POSITIVE_INFINITY;
    double multiply = Math.nextUp(Math.nextUp(subtract * drift) + Math.ulp(product));
    return Math.nextUp(Math.nextUp(multiply / 1000.) + Math.ulp(Math.nextUp(product / 1000.)) + 1.);
  }
}
