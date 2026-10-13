/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

/** File-order records of one entry. The owner retains its source for the cursor's lifetime;
 * headers do not decode values, and returned values must not retain mapped buffers. A series is
 * owned by one cursor: an adapter may retain its last decoded record for exact diagnostics. */
public interface RecordSeries {
  int size();
  long timestampUs(int index);
  long offset(int index);
  long payloadBytes(int index);
  boolean monotonic();
  Object decode(int index);

  /** Read a contiguous range in one backend call. Whole-entry clients pay no per-record query
   * dispatch; selective clients use this same operation after header-only admission. */
  default void readRange(int start, int end, String entry, SampleCursors.Sink sink) {
    for (int index = start; index < end; index++) {
      long offset = offset(index);
      long time;
      Object value;
      try {
        time = timestampUs(index);
        value = decode(index);
      } catch (RuntimeException failure) {
        reportFailure(index, offset, entry, failure, sink);
        continue;
      }
      // The consumer is outside the decode boundary: its failure is never blamed on the log.
      sink.sample(time, offset, index, value);
    }
  }

  /** Shared failure conversion, off the successful record path. */
  default void reportFailure(int index, long offset, String entry, RuntimeException failure,
      SampleCursors.Sink sink) {
    sink.problem(new ReadProblem(ReadProblem.Code.MALFORMED_RECORD, entry, offset,
        problemMessage(index, failure)));
  }
  /** Preserve the source's diagnostics policy; rethrowing refuses to skip that failure. */
  String problemMessage(int index, Exception failure);
}
