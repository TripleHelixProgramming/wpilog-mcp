/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

/** One numeric bucket's arithmetic, shared by MCP, legacy Arrow and native cursors.
 * The accumulation order intentionally preserves the legacy floating-point answers. */
public final class NumericBucket {
  private long count, finite;
  private double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum, first, last;
  public void add(double value) {
    if (count++ == 0) first = value;
    last = value;
    if (Double.isFinite(value)) {
      finite++; sum += value;
      if (value < min) min = value;
      if (value > max) max = value;
    }
  }
  public long count() { return count; }
  public long finite() { return finite; }
  public Double min() { return finite == 0 ? null : min; }
  public Double max() { return finite == 0 ? null : max; }
  public Double mean() { return finite == 0 ? null : sum / finite; }
  public double first() { return first; }
  public double last() { return last; }
}
