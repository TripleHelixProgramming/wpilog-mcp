/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;

class IndexMemoryTest {
  @Test void largestGeneratedFixtureKeepsFourByteOffsets() throws Exception {
    var fixtures = FixtureLogs.generateAll(FixtureLogs.defaultDirectory());
    var largest = fixtures.stream().max(Comparator.comparingLong(f -> f.path().toFile().length())).orElseThrow();
    long records;
    long bytes = 0;
    long arrays = 0;
    int entries;
    long begin = System.nanoTime();
    try (var reader = new ScopedLogReader(largest.path());
         var log = new LazyParsedLog(largest.path().toString(), reader.reader(), 1 << 20)) {
      entries = log.entries().size();
      records = log.entries().keySet().stream().mapToLong(log::sampleCount).sum();
      var field = LazyParsedLog.class.getDeclaredField("recordOffsets"); field.setAccessible(true);
      for (var offsets : ((java.util.Map<?, ?>) field.get(log)).values()) {
        long payload = ((RecordOffsets) offsets).storageBytes(); bytes += payload;
        arrays += (payload + 16 + 7) & ~7L;
      }
      assertEquals(records * 4, bytes);
    }
    // Array/object overhead below is the HotSpot compressed-reference, 8-byte-alignment layout;
    // payload bytes above are exact regardless of VM. Map and entry metadata are unchanged.
    var report = Path.of("build/reports/round17/index-memory.txt"); Files.createDirectories(report.getParent());
    Files.writeString(report, "fixture=" + largest.id() + " file_bytes=" + Files.size(largest.path())
        + " records=" + records + " entries=" + entries + " offset_bytes=" + bytes
        + " narrow_array_bytes=" + arrays + " entry_container_bytes=" + entries * 24L + " index_bytes=" + (arrays + entries * 24L) + " scan_ms=" + (System.nanoTime() - begin) / 1_000_000.0 + "\n",
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }
}
