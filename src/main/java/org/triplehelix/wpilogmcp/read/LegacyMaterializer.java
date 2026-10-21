/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.triplehelix.wpilogmcp.log.DecodeProblem;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/** Existing tools keep whole-entry diagnostics and the source's exact microsecond ordering. */
public final class LegacyMaterializer {
  private LegacyMaterializer() {}
  /** A null problem means every indexed record decoded. */
  public record Values(List<TimestampedValue> values, DecodeProblem problem) {}

  public static Values read(String name, RecordSeries series) {
    var values = new ArrayList<TimestampedValue>(series.size());
    class Collector implements SampleCursors.Sink {
      int failed;
      String firstFailure;
      @Override public void sample(long time, long offset, int ordinal, Object value) {
        values.add(TimestampedValue.atMicros(time, value));
      }
      @Override public void problem(ReadProblem problem) {
        failed++;
        if (firstFailure == null) firstFailure = problem.message();
      }
    }
    var collector = new Collector();
    // Decode in file order, then sort by retained original coordinates; equal instants remain stable.
    try {
      SampleCursors.consume(series, name, collector);
    } catch (IOException failure) {
      throw new UncheckedIOException("Could not materialize entry '" + name + "'", failure);
    }
    return new Values(TimestampedValue.inTimeOrder(values), collector.failed == 0 ? null
        : new DecodeProblem(collector.firstFailure, collector.failed, series.size()));
  }
}
