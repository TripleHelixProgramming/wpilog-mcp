/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One selection and batching implementation for local snapshots and compatibility readers. */
public final class SampleCursors {
  private SampleCursors() {}

  public interface Sink {
    void sample(long timestampUs, long recordOffset, int ordinal, Object value);
    void problem(ReadProblem problem);
  }

  /** Whole-entry compatibility reads need no transient sample/batch objects or duplicate framing. */
  public static void consume(RecordSeries series, String entry, Sink sink) throws IOException {
    Objects.requireNonNull(series);
    Objects.requireNonNull(sink);
    series.readRange(0, series.size(), entry, sink);
  }

  /**
   * The owner supplies a stable chronological permutation when timestamps are not monotonic.
   * Keeping that index with the snapshot lets cursors share its memory budget and lifetime.
   */
  public static SampleCursor open(RecordSeries series, ReadQuery query, int[] order, int maxRecordBytes) {
    Objects.requireNonNull(series);
    Objects.requireNonNull(query);
    if (maxRecordBytes < 1) throw new IllegalArgumentException("maxRecordBytes must be positive");
    if (order != null && order.length != series.size())
      throw new IllegalArgumentException("Record order must cover the whole entry");
    if (query.order() == ReadQuery.Order.TIME && order == null && !series.monotonic())
      throw new IllegalArgumentException("Unordered timestamps require a chronological record order");
    SampleCursor cursor = new Cursor(series, query, order, maxRecordBytes);
    if (query.reduction() != null) {
      long first = series.size() == 0 ? 0 : series.timestampUs(order == null ? 0 : order[0]);
      long last = series.size() == 0 ? 0 : series.timestampUs(order == null ? series.size() - 1 : order[order.length - 1]);
      cursor = new ReducedCursor(cursor, query, first, last);
    }
    return cursor;
  }

  /** Reduction also applies after a proven session union, using an explicit clock window. */
  public static SampleCursor reduce(SampleCursor source, ReadQuery query, long first, long last) {
    return query.reduction() == null ? source : new ReducedCursor(source, query, first, last);
  }

  private record Range(int start, int end) {}
  private record Progress(int recordsVisited, long payloadBytes, boolean complete) {}

  private static final class Cursor implements SampleCursor {
    private final RecordSeries series;
    private final ReadQuery query;
    private final int[] order;
    private final int maxRecordBytes;
    private final List<Range> ranges;
    private final FieldPath field;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private int range, position;
    private long selectedPayload;

    Cursor(RecordSeries series, ReadQuery query, int[] order, int maxRecordBytes) {
      this.series = series;
      this.field = FieldPath.parse(query.field());
      this.query = query;
      this.order = query.order() == ReadQuery.Order.FILE ? null : order;
      this.maxRecordBytes = maxRecordBytes;
      var selected = new ArrayList<Range>();
      if (query.windows().isEmpty() || query.order() == ReadQuery.Order.FILE) {
        selected.add(new Range(query.order() == ReadQuery.Order.FILE ? Math.min(query.firstOrdinal(), series.size()) : 0, series.size()));
      } else {
        for (var window : query.windows()) {
          int start = bound(window.startUs(), false);
          int end = bound(window.endUs(), window.endInclusive());
          if (start < end) selected.add(new Range(start, end));
        }
      }
      ranges = List.copyOf(selected);
      position = ranges.isEmpty() ? 0 : ranges.get(0).start();
    }

    private int ordinal(int sorted) { return order == null ? sorted : order[sorted]; }
    private long timestamp(int sorted) { return series.timestampUs(ordinal(sorted)); }

    private int bound(long timestampUs, boolean afterEqual) {
      return TimeWindows.bound(series.size(), middle -> {
        long value = timestamp(middle);
        return value < timestampUs || afterEqual && value == timestampUs;
      });
    }

    private boolean seek() {
      while (range < ranges.size()) {
        if (position >= ranges.get(range).end()) {
          range++;
          if (range < ranges.size()) position = ranges.get(range).start();
          continue;
        }
        return true;
      }
      return false;
    }

    @Override public SampleBatch next() throws IOException {
      var samples = new ArrayList<Sample>();
      var problems = new ArrayList<ReadProblem>();
      var progress = produce(new Sink() {
        public void sample(long time, long offset, int ordinal, Object value) {
          samples.add(new Sample(time, offset, ordinal, field.hasWildcard() ? field.resolveAll(value) : field.resolveOne(value), selectedPayload));
        }
        public void problem(ReadProblem problem) { problems.add(problem); }
      });
      return new SampleBatch(samples, problems, progress.recordsVisited(), progress.payloadBytes(),
          progress.complete());
    }

    private Progress produce(Sink sink) throws IOException {
      if (!active.compareAndSet(false, true))
        throw new IllegalStateException("One cursor cannot be read concurrently");
      try {
        if (closed.get()) throw new ReadException(ReadProblem.Code.CLOSED, "Sample cursor is closed");
        int records = 0;
        long bytes = 0;
        batches:
        while (records < query.batchRecords() && seek()) {
          int stop = position + Math.min(ranges.get(range).end() - position,
              query.batchRecords() - records);
          while (position < stop) {
            if (closed.get())
              throw new ReadException(ReadProblem.Code.CLOSED, "Sample cursor was cancelled");
            int ordinal = ordinal(position);
            if (ordinal < query.firstOrdinal()) { records++; position++; continue; }
            long offset = series.offset(ordinal);
            long payload = 0;
            long time = 0;
            boolean selected = true;
            try {
              time = series.timestampUs(ordinal);
              if (query.order() == ReadQuery.Order.FILE && !query.windows().isEmpty()) {
                selected = TimeWindows.containing(query.windows(), time) >= 0;
                if (!selected) { records++; position++; continue; }
              }
              // Header-only admission avoids decoding or copying excluded or oversized values.
              payload = series.payloadBytes(ordinal);
              if (payload > maxRecordBytes) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT,
                  "Record at byte " + offset + " of '" + query.entry() + "' has " + payload
                      + " payload bytes, above maxRecordBytes=" + maxRecordBytes);
              if (records > 0 && payload > query.batchBytes() - bytes) break batches;
            } catch (RuntimeException failure) {
              selected = false;
              sink.problem(new ReadProblem(ReadProblem.Code.MALFORMED_RECORD, query.entry(), offset,
                  series.problemMessage(ordinal, failure)));
            }
            // A consumer exception belongs to the consumer, not the log's decode diagnostics.
            selectedPayload = payload;
            if (selected) series.readRange(ordinal, ordinal + 1, query.entry(), sink);
            records++;
            bytes += payload;
            position++;
          }
        }
        return new Progress(records, bytes, !seek());
      } finally {
        active.set(false);
      }
    }

    @Override public void close() { closed.set(true); }
  }
}
