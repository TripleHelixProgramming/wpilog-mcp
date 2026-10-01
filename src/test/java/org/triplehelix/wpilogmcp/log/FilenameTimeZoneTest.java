/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.sync.SyncResult;
import org.triplehelix.wpilogmcp.sync.SynchronizedLogs;

/** File-name times are the roboRIO's (UTC) unless a desktop simulation named the file (review 6, 3.5). */
class FilenameTimeZoneTest {

  @TempDir Path dir;

  @Test
  @DisplayName("a roboRIO-named log's time is read as UTC; a simulation log's in the local zone")
  void zones() throws Exception {
    Files.createFile(dir.resolve("frc_26-03-21_18-50-00_vache_qm5.wpilog"));
    Files.createFile(dir.resolve("frc_26-03-21_18-50-00_vache_qm6_sim.wpilog"));
    var logDir = LogDirectory.getInstance();
    var saved = logDir.getLogDirectory();
    logDir.setLogDirectory(dir.toString());
    try {
      var logs = logDir.listAvailableLogs();
      assertEquals(2, logs.size());
      var named = LocalDateTime.of(2026, 3, 21, 18, 50, 0);
      for (var log : logs) {
        boolean sim = log.filename().contains("_sim");
        long expected = named.atZone(sim ? ZoneId.systemDefault() : ZoneOffset.UTC)
            .toInstant().toEpochMilli();
        assertEquals(expected, log.logCreationTime(), log.filename());
      }
    } finally {
      logDir.setLogDirectory(saved == null ? null : saved.toString());
      logDir.clearCache();
    }
  }

  @Test
  @DisplayName("a REV log's file-name time is read as UTC")
  void revlogUtc() {
    var info = new LogDirectory.RevLogFileInfo(Path.of("/x/REV_20260321_185000.revlog"),
        "20260321_185000", LocalDateTime.of(2026, 3, 21, 18, 50, 0), null, 0);
    assertEquals(LocalDateTime.of(2026, 3, 21, 18, 50, 0).toInstant(ZoneOffset.UTC)
        .toEpochMilli(), info.timestampMillis());
  }

  @Test
  @DisplayName("a REV log named with a bus is reported under that bus")
  void busFromFilename() {
    var wpilog = new ParsedLog("/x/a.wpilog", Map.of(), Map.of(), 0, 1);
    var revlog = new ParsedRevLog("/x/REV_20260321_185000_canivore.revlog", "20260321_185000",
        Map.of(), Map.of(), 0, 1, 0);
    var named = new LogDirectory.RevLogFileInfo(Path.of(revlog.path()), "20260321_185000",
        LocalDateTime.of(2026, 3, 21, 18, 50, 0), "canivore", 0);
    var unnamed = new LogDirectory.RevLogFileInfo(Path.of("/x/REV_20260321_185000.revlog"),
        "20260321_185000", LocalDateTime.of(2026, 3, 21, 18, 50, 0), null, 0);
    var builder = new SynchronizedLogs.Builder().wpilog(wpilog);
    LogManager.addRevLog(builder, revlog, SyncResult.fromUserOffset(0), named);
    LogManager.addRevLog(builder, revlog.at("/x/REV_20260321_185000.revlog", "20260321_185000"),
        SyncResult.fromUserOffset(0), unnamed);
    var synced = builder.build().revlogs();
    assertEquals("canivore", synced.get(0).canBusName());
    assertEquals("can1", synced.get(1).canBusName(), "inferred from the index: not the CANivore");
  }
}
