/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

/**
 * The window {@code read_entry} and the data endpoint take: samples whose seconds are at or after
 * {@code start} and at or before {@code end}, either end open when null. The seconds are the
 * decoded value's, so the window keeps the same samples whether they come from a decoded log or a
 * column file. A NaN bound keeps nothing, as the comparisons do.
 */
public record SecondsWindow(Double start, Double end) {

  public static final SecondsWindow ALL = new SecondsWindow(null, null);

  public boolean open() {
    return start == null && end == null;
  }

  public boolean contains(double seconds) {
    return atOrAfterStart(seconds) && atOrBeforeEnd(seconds);
  }

  private boolean atOrAfterStart(double seconds) {
    return start == null || seconds >= start;
  }

  private boolean atOrBeforeEnd(double seconds) {
    return end == null || seconds <= end;
  }

  /** The first position of samples in time order that the window contains, or later. */
  public int from(NumericSamples samples) {
    return TimeWindows.bound(samples.size(), i -> !atOrAfterStart(samples.seconds(i)));
  }

  /** One past the last position of samples in time order that the window contains. */
  public int to(NumericSamples samples) {
    return Math.max(from(samples), TimeWindows.bound(samples.size(), i -> atOrBeforeEnd(samples.seconds(i))));
  }
}
