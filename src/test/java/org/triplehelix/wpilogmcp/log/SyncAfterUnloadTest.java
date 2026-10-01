/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;

/**
 * A background revlog sync that finishes after its wpilog was unloaded must not put the log's
 * sync result (which holds the parsed REV log and the wpilog) back into the cache.
 */
@DisplayName("revlog sync after unload")
class SyncAfterUnloadTest {

  @Test
  @DisplayName("a sync that finishes after its log was unloaded does not bring it back; one "
      + "that finishes while the log is loaded replaces its placeholder")
  void syncAfterUnload(@TempDir Path dir) throws Exception {
    var wpilog = FixtureLogs.writeRevlogPair(dir, "2026-sync_after_unload.wpilog",
        ZoneOffset.UTC, "systemTime").toString();
    var manager = new LogManager();
    try {
      manager.addAllowedDirectory(dir);
      // No disk cache: the sync parses and correlates, so it is still running at the unload
      manager.getSyncDiskCache().setEnabled(false);

      manager.getOrLoad(wpilog);
      assertTrue(manager.unloadLog(wpilog));
      assertTrue(manager.awaitSyncExecutorIdle(60_000), "sync did not finish");
      assertNull(manager.getSynchronizedLogs(wpilog),
          "the unloaded log's sync result was put back in the cache");

      // Control: loaded throughout, the result replaces the placeholder
      manager.getOrLoad(wpilog);
      assertTrue(manager.awaitSyncExecutorIdle(60_000), "sync did not finish");
      var synced = manager.getSynchronizedLogs(wpilog);
      assertNotNull(synced);
      assertEquals(1, synced.revlogCount());
    } finally {
      manager.shutdown();
    }
  }
}
