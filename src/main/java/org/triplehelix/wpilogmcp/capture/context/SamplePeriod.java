/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

/** The robot's measured command round trip controls sampling; local worker backlog does not. */
public final class SamplePeriod {
  public static final long MAX_US = 30_000_000;
  private final long baseUs, budgetUs;
  private long periodUs;
  public SamplePeriod(long baseUs, long budgetUs) {
    if (baseUs <= 0 || baseUs > MAX_US || budgetUs <= 0) throw new IllegalArgumentException("Invalid sample period or budget");
    this.baseUs = baseUs; this.budgetUs = budgetUs; periodUs = baseUs;
  }
  public long periodUs() { return periodUs; }
  public void measured(long roundTripUs) {
    if (roundTripUs < 0) throw new IllegalArgumentException("Negative round trip");
    periodUs = roundTripUs > budgetUs ? Math.min(MAX_US, periodUs * 2) : Math.max(baseUs, periodUs / 2);
  }
}
