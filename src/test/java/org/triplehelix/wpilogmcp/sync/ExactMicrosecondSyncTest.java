/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;
import org.triplehelix.wpilogmcp.log.subsystems.LogParser;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;

/** Exact WPILOG ordering is an input to REV synchronization even without a REV decoder change. */
class ExactMicrosecondSyncTest {
  @TempDir Path temp;

  @Test void collapsedAdjacentClockReadingsUseTheirOriginalMicrosecondOrder() throws Exception {
    long first = 9_007_199_254_740_992L;
    assertEquals(first / 1e6, (first + 1) / 1e6, "The witness must collapse in double seconds");
    long epochUs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() * 1000;
    var file = temp.resolve("synthetic-clock-order.wpilog");
    try (var writer = new WpilogWriter(file, "synthetic exact-order synchronization witness")) {
      int clock = writer.start("systemTime", "int64", "", 0);
      writer.append(clock, first + 1, WpilogWriter.encodeInt64(epochUs + 120_000_000L));
      writer.append(clock, first, WpilogWriter.encodeInt64(epochUs));
    }
    // Correct time order leaves the reading after the 120-second wall-clock jump. The REV
    // filename is 120 seconds before that reading, so extrapolation is exactly first-120 s.
    long expectedOffsetUs = 9_007_199_134_740_992L;
    var eager = new LogParser().parse(file);
    try (var reader = new ScopedLogReader(file);
         var lazy = new LazyParsedLog(file.toString(), reader.reader(), 1_048_576)) {
      assertAll(() -> verify(eager, expectedOffsetUs), () -> verify(lazy, expectedOffsetUs));
    }
  }

  private void verify(LogData wpilog, long expectedOffsetUs) {
    var revlog = new ParsedRevLog(temp.resolve("REV_20260101_000000.revlog").toString(),
        "20260101_000000", Map.of(), Map.of(), 0, 1, 0);
    var synchronizer = new LogSynchronizer(6000, 100, 60000, 1e-6, 0.5, 0.7, ZoneOffset.UTC);
    var result = synchronizer.synchronize(wpilog, revlog);
    assertEquals(SyncMethod.SYSTEM_TIME_ONLY, result.method());
    assertEquals(expectedOffsetUs, result.offsetMicros(),
        "REV alignment must use clock readings in original microsecond order");
    assertEquals(0, result.signalPairs().size(), "This witness isolates clock alignment");
  }
}
