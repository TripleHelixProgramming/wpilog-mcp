/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Incremental min/max buckets never decode beyond one source batch in one next call.
 * Empty output batches preserve bounded work while a bucket spans many input batches. Each
 * window's buckets are filled by the one bucket rule every overview shares. */
final class ReducedCursor implements SampleCursor {
  private final SampleCursor source;
  private final List<ReadQuery.Window> windows;
  private final int points;
  private BucketFill fill;
  private Sample first;
  private int window = -1;
  private Sample deferred;
  private boolean complete;

  ReducedCursor(SampleCursor source, ReadQuery query, long first, long last) {
    this.source = source; points = query.reduction().maxPoints();
    windows = query.windows().isEmpty() ? List.of(new ReadQuery.Window(first, last, true)) : query.windows();
  }
  @Override public SampleBatch next() throws IOException {
    if (deferred != null) {
      var result = deferred; deferred = null;
      return new SampleBatch(List.of(result), List.of(), 0, 0, true);
    }
    if (complete) return new SampleBatch(List.of(), List.of(), 0, 0, true);
    var input = source.next(); var output = new ArrayList<Sample>();
    for (var sample : input.samples()) {
      if (!(sample.value() instanceof Number number))
        throw new ReadException(ReadProblem.Code.INVALID_REQUEST, "Reduction requires a scalar numeric value; select a numeric field first");
      int w = TimeWindows.containing(windows, sample.timestampUs());
      if (w < 0) throw new IOException("Source returned a sample outside the reduction windows");
      if (fill == null || window != w) {
        if (fill != null) output.add(finish(fill.finish()));
        var bounds = windows.get(w);
        window = w; fill = new BucketFill(bounds.startUs(), bounds.endUs(), points);
      }
      int before = fill.index();
      var closed = fill.add(sample.timestampUs(), number.doubleValue());
      if (closed != null) output.add(finish(closed));
      if (fill.index() != before) first = sample;
    }
    if (input.complete()) {
      if (fill != null) { output.add(finish(fill.finish())); fill = null; }
      complete = true;
    }
    if (output.size() > 4096) deferred = output.remove(output.size() - 1);
    return new SampleBatch(output, input.problems(), input.recordsVisited(), input.payloadBytes(), complete && deferred == null);
  }
  /** A closed bucket as a sample: its own coordinates, and its first contributing sample's offset. */
  private Sample finish(BucketFill.Bucket closed) {
    var bucket = closed.values();
    var value = new LinkedHashMap<String, Object>();
    long start = closed.startUs(), end = closed.endUs();
    value.put("start_us", start); value.put("end_us", end); value.put("count", bucket.count());
    value.put("finite_count", bucket.finite()); value.put("min", bucket.min()); value.put("max", bucket.max());
    value.put("mean", bucket.mean() != null && Double.isFinite(bucket.mean()) ? bucket.mean() : null); value.put("first", bucket.first()); value.put("last", bucket.last());
    return new Sample(start, first.recordOffset(), first.ordinal(), java.util.Collections.unmodifiableMap(value), 0, first.source());
  }
  @Override public void close() throws IOException { source.close(); }
}
