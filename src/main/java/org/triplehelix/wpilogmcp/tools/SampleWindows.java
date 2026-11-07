/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.util.ArrayList;
import java.util.List;
import org.triplehelix.wpilogmcp.read.NumericSamples;

/**
 * A signal's samples window by window, by position, as the statistics tools read them: the
 * windows a scope splits, the finite samples of each, unwrapped within its window when asked,
 * in the order and with the arithmetic the tools' lists had, so a column file's samples are read
 * where they lie and nothing is made per sample.
 */
final class SampleWindows {
  private SampleWindows() {}

  /** The finite samples of a window, in order; the window itself when every sample is finite. */
  static NumericSamples finite(NumericSamples window) {
    int count = 0;
    for (int i = 0; i < window.size(); i++) if (Double.isFinite(window.number(i))) count++;
    if (count == window.size()) return window;
    var positions = new int[count];
    for (int i = 0, k = 0; i < window.size(); i++) if (Double.isFinite(window.number(i))) positions[k++] = i;
    return new NumericSamples() {
      @Override public int size() { return positions.length; }
      @Override public long timeUs(int index) { return window.timeUs(positions[index]); }
      @Override public double seconds(int index) { return window.seconds(positions[index]); }
      @Override public double number(int index) { return window.number(positions[index]); }
      @Override public long valueBits(int index) { return window.valueBits(positions[index]); }
    };
  }

  /** Each window's finite samples, unwrapped within the window when {@code unwrap} is set. */
  static List<NumericSamples> finiteWindows(NumericSignal signal, List<NumericSamples> windows, boolean unwrap) {
    var out = new ArrayList<NumericSamples>(windows.size());
    for (var window : windows) {
      var finite = finite(window);
      out.add(unwrap ? signal.unwrap(finite) : finite);
    }
    return out;
  }

  /** The windows' samples one after another, as one view; a sample two windows hold appears twice. */
  static NumericSamples concat(List<NumericSamples> windows) {
    if (windows.size() == 1) return windows.get(0);
    var starts = new int[windows.size() + 1];
    for (int w = 0; w < windows.size(); w++) starts[w + 1] = starts[w] + windows.get(w).size();
    int size = starts[windows.size()];
    return new NumericSamples() {
      /** The window of the last read, which a reading in order finds again first. */
      private int window;

      private int locate(int index) {
        java.util.Objects.checkIndex(index, size);
        if (index < starts[window] || index >= starts[window + 1]) {
          int found = java.util.Arrays.binarySearch(starts, index);
          window = found >= 0 ? found : -found - 2;
          while (starts[window + 1] == index) window++;
        }
        return index - starts[window];
      }

      @Override public int size() { return size; }
      @Override public long timeUs(int index) { int at = locate(index); return windows.get(window).timeUs(at); }
      @Override public double seconds(int index) { int at = locate(index); return windows.get(window).seconds(at); }
      @Override public double number(int index) { int at = locate(index); return windows.get(window).number(at); }
      @Override public long valueBits(int index) { int at = locate(index); return windows.get(window).valueBits(at); }
    };
  }

  /**
   * As {@link ToolUtils#getValueAtTimeLinear}, over samples in time order: the value at
   * {@code t} interpolated between its neighbors, null outside the samples' span.
   */
  static Double linearAt(NumericSamples values, double t) {
    if (values.size() == 0) return null;
    if (t < values.seconds(0) || t > values.seconds(values.size() - 1)) return null;
    int lo = 0, hi = values.size() - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (values.seconds(mid) <= t) lo = mid;
      else hi = mid - 1;
    }
    double v1 = values.number(lo);
    if (values.seconds(lo) == t || lo == values.size() - 1) return v1;
    double t1 = values.seconds(lo), t2 = values.seconds(lo + 1), v2 = values.number(lo + 1);
    if (t2 == t1) return v1;
    double fraction = (t - t1) / (t2 - t1);
    return v1 + (v2 - v1) * fraction;
  }

  /** The samples with every number multiplied by {@code factor}, as an angle changes its unit. */
  static NumericSamples scaled(NumericSamples values, double factor) {
    var numbers = new double[values.size()];
    for (int i = 0; i < numbers.length; i++) numbers[i] = values.number(i) * factor;
    return NumericSamples.withNumbers(values, numbers);
  }

  /** Samples in all the windows, counting a sample twice when two windows hold it. */
  static int count(List<NumericSamples> windows) {
    int count = 0;
    for (var window : windows) count += window.size();
    return count;
  }

  /** The windows' numbers, one after another. */
  static double[] numbers(List<NumericSamples> windows) {
    var out = new double[count(windows)];
    int k = 0;
    for (var window : windows) for (int i = 0; i < window.size(); i++) out[k++] = window.number(i);
    return out;
  }

  /** The windows' seconds, one after another. */
  static double[] seconds(List<NumericSamples> windows) {
    var out = new double[count(windows)];
    int k = 0;
    for (var window : windows) for (int i = 0; i < window.size(); i++) out[k++] = window.seconds(i);
    return out;
  }
}
