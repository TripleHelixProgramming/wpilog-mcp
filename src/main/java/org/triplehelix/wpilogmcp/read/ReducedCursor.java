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
 * Empty output batches preserve bounded work while a bucket spans many input batches. */
final class ReducedCursor implements SampleCursor {
  private final SampleCursor source;
  private final List<ReadQuery.Window> windows;
  private final int points;
  private NumericBucket bucket;
  private Sample first;
  private int window = -1, index = -1;
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
      int i = bucketIndex(windows.get(w), sample.timestampUs());
      if (bucket == null || window != w || index != i) {
        if (bucket != null) output.add(finish());
        window = w; index = i; bucket = new NumericBucket(); first = sample;
      }
      bucket.add(number.doubleValue());
    }
    if (input.complete()) {
      if (bucket != null) { output.add(finish()); bucket = null; }
      complete = true;
    }
    if (output.size() > 4096) deferred = output.remove(output.size() - 1);
    return new SampleBatch(output, input.problems(), input.recordsVisited(), input.payloadBytes(), complete && deferred == null);
  }
  private int bucketIndex(ReadQuery.Window w, long time) {
    return BucketCoordinates.index(w.startUs(), w.endUs(), points, time);
  }
  private long boundary(int i) {
    var w = windows.get(window);
    return BucketCoordinates.boundary(w.startUs(), w.endUs(), points, i);
  }
  private Sample finish() {
    var value = new LinkedHashMap<String, Object>();
    long start = boundary(index), end = boundary(index + 1);
    value.put("start_us", start); value.put("end_us", end); value.put("count", bucket.count());
    value.put("finite_count", bucket.finite()); value.put("min", bucket.min()); value.put("max", bucket.max());
    value.put("mean", bucket.mean() != null && Double.isFinite(bucket.mean()) ? bucket.mean() : null); value.put("first", bucket.first()); value.put("last", bucket.last());
    return new Sample(start, first.recordOffset(), first.ordinal(), java.util.Collections.unmodifiableMap(value), 0, first.source());
  }
  @Override public void close() throws IOException { source.close(); }
}
