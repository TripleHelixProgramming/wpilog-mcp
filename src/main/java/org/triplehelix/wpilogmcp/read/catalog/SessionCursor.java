/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.catalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.triplehelix.wpilogmcp.read.*;

/** Bounded merge of proven clocks: small source batches share an 8 MiB payload allowance.
 * Original coordinates accompany every value, including exact ties between source files. */
final class SessionCursor implements SampleCursor {
  private static final long MAX_LOOKAHEAD_BYTES = 8L << 20;
  static final class Child {
    final SampleCursor cursor;
    final ReadSnapshot.SourceFile source;
    List<Sample> samples = List.of();
    int position;
    boolean complete;
    Child(SampleCursor cursor, ReadSnapshot.SourceFile source) { this.cursor = cursor; this.source = source; }
    Sample peek() { return position < samples.size() ? samples.get(position) : null; }
  }
  private final List<Child> children;
  private final ReadQuery query;
  private boolean closed;
  private long lookaheadBytes;
  SessionCursor(List<Child> children, ReadQuery query) { this.children = List.copyOf(children); this.query = query; }
  private Sample mapped(Sample sample, ReadSnapshot.SourceFile source) throws IOException {
    long time = sample.timestampUs();
    try {
      for (var proof : source.clock()) {
        double approximate = proof.toFpgaTime(time / 1_000_000.) * 1_000_000.;
        if (!Double.isFinite(approximate) || approximate >= Long.MAX_VALUE || approximate <= Long.MIN_VALUE) throw new ArithmeticException();
        time = proof.toFpgaTimeMicros(time);
      }
    } catch (ArithmeticException e) { throw new IOException("Recorded session clock overflows for " + source.path(), e); }
    return new Sample(time, sample.recordOffset(), sample.ordinal(), sample.value(), sample.payloadBytes(),
        new Sample.Source(source.path(), sample.timestampUs()));
  }
  @Override public SampleBatch next() throws IOException {
    if (closed) throw new ReadException(ReadProblem.Code.CLOSED, "Session cursor is closed");
    var values = new ArrayList<Sample>(); var problems = new ArrayList<ReadProblem>();
    int visited = 0; long bytes = 0, outputBytes = 0; boolean done = false;
    outer: while (values.size() < query.batchRecords()) {
      for (var child : children) while (child.peek() == null && !child.complete) {
        if (visited > 4096 - 64 || visited > 0 && bytes >= query.batchBytes()) break outer;
        var batch = child.cursor.next(); visited += batch.recordsVisited(); bytes += batch.payloadBytes();
        var transformed = new ArrayList<Sample>();
        for (var sample : batch.samples()) {
          if (sample.payloadBytes() > MAX_LOOKAHEAD_BYTES - lookaheadBytes)
            throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT,
                "Session merge lookahead exceeds 8 MiB; read the files individually");
          transformed.add(mapped(sample, child.source)); lookaheadBytes += sample.payloadBytes();
        }
        child.samples = transformed; child.position = 0; child.complete = batch.complete();
        for (var p : batch.problems()) problems.add(new ReadProblem(p.code(), p.entry(), p.recordOffset(), child.source.path() + ": " + p.message()));
      }
      Child next = null;
      for (var child : children) if (child.peek() != null
          && (next == null || child.peek().timestampUs() < next.peek().timestampUs())) next = child;
      if (next == null) { done = true; break; }
      var sample = next.peek();
      boolean selected = query.windows().isEmpty() || TimeWindows.containing(query.windows(), sample.timestampUs()) >= 0;
      if (selected && !values.isEmpty() && sample.payloadBytes() > query.batchBytes() - outputBytes) break;
      next.samples.set(next.position++, null); // Release the consumed value along with its charge.
      lookaheadBytes -= sample.payloadBytes();
      if (selected) { values.add(sample); outputBytes += sample.payloadBytes(); }
    }
    return new SampleBatch(values, problems, visited, bytes, done);
  }
  @Override public void close() throws IOException {
    if (closed) return; closed = true; IOException failure = null;
    for (var child : children) try { child.cursor.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    if (failure != null) throw failure;
  }
}
