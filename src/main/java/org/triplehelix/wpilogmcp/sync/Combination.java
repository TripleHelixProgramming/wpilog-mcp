/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import java.util.List;

/**
 * The recorded measurements a {@link SyncMethod#COMBINED} alignment was derived from. When two
 * files of a session were each measured against one reference (a capture file, or a capture
 * recording, whose files share the robot's clock), each recorded offset maps its file onto the
 * reference, and together they map one file onto the other: exact arithmetic, with no correlation
 * between the two files. Each measurement keeps its method and confidence, so a reader can see what
 * the result rests on: it is no more confident than the least confident of them, and its error is
 * at most the sum of theirs.
 *
 * @param reference The file or capture recording the measurements lead to
 * @param measurements Each recorded offset used, in the order it applies
 * @since 0.10.0
 */
public record Combination(String reference, List<Measurement> measurements) {
  /**
   * One recorded offset, which maps {@code file}'s time onto {@code against}'s.
   *
   * @param reversed Whether the combination applies it from {@code against} onto {@code file}:
   *        the anchor's own measurement, used to reach the anchor from the reference
   */
  public record Measurement(String file, String against, boolean reversed, SyncMethod method,
      double confidence, ConfidenceLevel confidenceLevel, long offsetMicros,
      double driftRateNanosPerSec, double referenceTimeSec) {
    /**
     * The most this measurement is expected to be off, in milliseconds: its level's bound for a
     * correlation or a wall-clock estimate; NaN for an offset whose accuracy the server cannot judge.
     */
    public double maxErrorMs() {
      return method == SyncMethod.CROSS_CORRELATION || method == SyncMethod.SYSTEM_TIME_ONLY
          ? confidenceLevel.getMaxErrorMs() : Double.NaN;
    }
  }

  public Combination {
    // Not List.copyOf: a null element read back from JSON is refused by consistent(), not by a crash here.
    measurements = measurements == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(measurements));
  }

  /** Whether a result's method and combination agree, and a combination has every field: input
   * read back from a file or another server must not report a combination it cannot name. */
  public static boolean consistent(SyncMethod method, Combination combination) {
    if (combination == null) return method != SyncMethod.COMBINED;
    if (method != SyncMethod.COMBINED || combination.reference() == null || combination.measurements() == null) return false;
    for (var m : combination.measurements()) {
      if (m == null || m.file() == null || m.against() == null || m.method() == null || m.method() == SyncMethod.COMBINED
          || m.confidenceLevel() == null || !Double.isFinite(m.confidence()) || !Double.isFinite(m.driftRateNanosPerSec())
          || !Double.isFinite(m.referenceTimeSec())) return false;
    }
    return true;
  }

  /** The most the combination can be off, in milliseconds: the sum of its measurements' bounds. */
  public double maxErrorMs() {
    return measurements.stream().mapToDouble(Measurement::maxErrorMs).sum();
  }

  /** A bound as the tools print it: whole milliseconds when it is whole. */
  public static String milliseconds(double value) {
    return value == Math.rint(value) && Math.abs(value) < 1e15 ? String.valueOf((long) value) : String.valueOf(value);
  }
}
