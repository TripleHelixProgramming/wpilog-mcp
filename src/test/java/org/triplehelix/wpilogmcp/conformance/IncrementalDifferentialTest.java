/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.log.*;

class IncrementalDifferentialTest {
  @TempDir Path temp;
  @Test void largestFixtureResumesWithoutReadingTheIndexedPrefixAndAgreesWithIndependentRecords() throws Exception {
    var fixture = FixtureLogs.generateAll(FixtureLogs.defaultDirectory()).stream()
        .max(Comparator.comparingLong(f -> f.path().toFile().length())).orElseThrow();
    byte[] bytes = Files.readAllBytes(fixture.path()); int cut;
    try (var independent = new IndependentLog.Records(fixture.path())) {
      cut = independent.first;
      while (cut < bytes.length * 0.95) cut = independent.at(cut).end();
    }
    var file = temp.resolve("growing.wpilog"); Files.write(file, Arrays.copyOf(bytes, cut));
    LogScan previous;
    try (var reader = new ScopedLogReader(file)) { previous = LogScan.of(reader.reader(), file); }
    Files.write(file, Arrays.copyOfRange(bytes, cut, bytes.length), StandardOpenOption.APPEND);
    try (var reader = new ScopedLogReader(file); var independent = new IndependentLog.Records(file)) {
      var resumed = LogScan.resume(previous, reader.reader(), file);
      var fresh = LogScan.of(reader.reader(), file);
      assertEquals(cut, resumed.scannedFrom()); assertEquals(fresh.entries(), resumed.entries());
      assertEquals(fresh.dataRecords(), resumed.dataRecords());
      assertEquals(fresh.minTimestamp(), resumed.minTimestamp()); assertEquals(fresh.maxTimestamp(), resumed.maxTimestamp());
      assertEquals(fresh.truncated(), resumed.truncated()); assertEquals(fresh.damaged(), resumed.damaged());
      for (var entry : resumed.offsets().entrySet()) {
        var offsets = entry.getValue(); assertEquals(fresh.offsets().get(entry.getKey()).size(), offsets.size());
        for (int i = 0; i < offsets.size(); i++) {
          long pos = offsets.get(i); assertEquals(fresh.offsets().get(entry.getKey()).get(i), pos);
          var expected = independent.at(Math.toIntExact(pos)); var actual = reader.reader().getRecord(pos);
          assertEquals(expected.timestampUs(), actual.getTimestamp());
          assertEquals(expected.id(), actual.getEntry()); assertArrayEquals(independent.payload(expected), actual.getRaw());
        }
      }
      // Same mapping and warmed JVM, alternating order; five measured runs after two warmups.
      long[] fullNs = new long[5], resumeNs = new long[5];
      for (int i = -2; i < 5; i++) {
        for (int j = 0; j < 2; j++) {
          boolean append = (i + j) % 2 == 0; long begin = System.nanoTime();
          var result = append ? LogScan.resume(previous, reader.reader(), file) : LogScan.of(reader.reader(), file);
          long elapsed = System.nanoTime() - begin; assertEquals(fresh.dataRecords(), result.dataRecords());
          if (i >= 0) (append ? resumeNs : fullNs)[i] = elapsed;
        }
      }
      Arrays.sort(fullNs); Arrays.sort(resumeNs);
      var report = Path.of("build/reports/round18/rescan-cost.txt"); Files.createDirectories(report.getParent());
      Files.writeString(report, "fixture=" + fixture.id() + " bytes=" + bytes.length + " previous_bytes=" + cut
          + " records=" + fresh.dataRecords() + " appended_records=" + (resumed.dataRecords() - previous.dataRecords())
          + " fresh_median_ms=" + fullNs[2] / 1_000_000. + " resume_median_ms=" + resumeNs[2] / 1_000_000. + "\n");
    }
  }
}
