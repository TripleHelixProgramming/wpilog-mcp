/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

/**
 * One requested interval's shortest-turn presentation, shared by materialized and streamed
 * reads so crossing a batch or recording member cannot introduce a different angle rule.
 *
 * <p>The first finite value stays as recorded. Whole turns use {@link Math#rint(double)},
 * retaining the existing nearest-even convention at exactly half a period. Non-finite values
 * pass through without resetting continuity. This object belongs to one traversal, never to
 * a shared tool or cache.
 */
public final class AngleUnwrapper {
  private final double period;
  private double previous = Double.NaN;
  private double turns;

  public AngleUnwrapper(double period) {
    if (!Double.isFinite(period) || period <= 0)
      throw new IllegalArgumentException("Angle period must be finite and positive");
    this.period = period;
  }

  /** Keeps gaps as gaps while continuing from the preceding finite observation. */
  public double apply(double value) {
    if (!Double.isFinite(value)) return value;
    if (!Double.isNaN(previous)) turns -= Math.rint((value - previous) / period);
    previous = value;
    return turns == 0 ? value : value + turns * period;
  }
}
