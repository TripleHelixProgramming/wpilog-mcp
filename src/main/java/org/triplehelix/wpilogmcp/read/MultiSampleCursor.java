/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Independent tables in caller order, never an implicit join or resampling.
 * Every child is admitted together so retirement cannot split a multi-entry read. */
public interface MultiSampleCursor extends AutoCloseable {
  record Batch(int queryIndex, String entry, SampleBatch batch, boolean complete) {}
  Batch next() throws IOException;
  @Override void close() throws IOException;
  static List<ReadQuery> validate(List<ReadQuery> queries) {
    queries = List.copyOf(queries);
    if (queries.isEmpty() || queries.size() > 64) throw new IllegalArgumentException("A multi-read needs 1..64 queries");
    return queries;
  }
  static MultiSampleCursor open(ReadSnapshot snapshot, List<ReadQuery> requested) throws IOException {
    var queries = validate(requested);
    var cursors = new ArrayList<SampleCursor>();
    try { for (var query : queries) cursors.add(snapshot.read(query)); }
    catch (Throwable failure) {
      for (var cursor : cursors) try { cursor.close(); } catch (IOException e) { failure.addSuppressed(e); }
      throw failure;
    }
    return new MultiSampleCursor() {
      int at;
      boolean closed;
      public Batch next() throws IOException {
        if (closed) throw new ReadException(ReadProblem.Code.CLOSED, "Multi-entry cursor is closed");
        if (at == queries.size()) return new Batch(at - 1, queries.get(at - 1).entry(), new SampleBatch(List.of(), List.of(), 0, 0, true), true);
        int index = at; var batch = cursors.get(at).next();
        if (batch.complete()) { cursors.get(at).close(); at++; }
        return new Batch(index, queries.get(index).entry(), batch, at == queries.size());
      }
      public void close() throws IOException {
        if (closed) return; closed = true; IOException failure = null;
        for (var cursor : cursors) try { cursor.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
      }
    };
  }
}
