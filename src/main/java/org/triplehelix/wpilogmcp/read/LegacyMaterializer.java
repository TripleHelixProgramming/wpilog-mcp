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

/** Existing tools keep whole-entry diagnostics and their historical double timestamp ordering. */
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
        values.add(new TimestampedValue(time / 1_000_000.0, value));
      }
      @Override public void problem(ReadProblem problem) {
        failed++;
        if (firstFailure == null) firstFailure = problem.message();
      }
    }
    var collector = new Collector();
    // Legacy ordering sorts double seconds stably after decoding in file order. Adjacent large
    // microsecond timestamps can round to the same double, so sorting longs here would differ.
    try {
      SampleCursors.consume(series, name, collector);
    } catch (IOException failure) {
      throw new UncheckedIOException("Could not materialize entry '" + name + "'", failure);
    }
    return new Values(TimestampedValue.inTimeOrder(values), collector.failed == 0 ? null
        : new DecodeProblem(collector.firstFailure, collector.failed, series.size()));
  }
}
