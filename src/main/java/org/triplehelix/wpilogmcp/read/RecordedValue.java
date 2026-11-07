/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.util.List;

/** Cursor tables show the last logged sample at or before the cursor, never interpolation.
 * Equal clock times use the last physical ordinal, and problems remain beside the value. */
public final class RecordedValue {
  private RecordedValue() {}
  public record Result(Sample sample, List<ReadProblem> problems, long problemCount) {}
  /** Timestamp first, physical ordinal second; a later file record can have an older clock. */
  public static boolean later(Sample sample, Sample previous) {
    return previous == null || sample.timestampUs() > previous.timestampUs()
        || sample.timestampUs() == previous.timestampUs() && sample.ordinal() > previous.ordinal();
  }
  public static Result at(ReadSnapshot snapshot, String entry, String field, Long atUs) throws IOException {
    var query = atUs == null ? ReadQuery.all(entry) : ReadQuery.window(entry, Long.MIN_VALUE, atUs, true);
    if (field != null) query = query.project(field);
    Sample last = null; var problems = new java.util.ArrayList<ReadProblem>(); long problemCount = 0;
    try (var cursor = snapshot.read(query)) {
      while (true) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Recorded value request cancelled");
        var batch = cursor.next();
        for (var sample : batch.samples()) if (later(sample, last)) last = sample;
        for (var problem : batch.problems()) if (problems.size() < 100) problems.add(problem);
        problemCount += batch.problems().size();
        if (batch.complete()) return new Result(last, List.copyOf(problems), problemCount);
      }
    }
  }
}
