/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.integration;

import java.util.List;
import org.triplehelix.wpilogmcp.log.LogManager;

/** What the two stress tests share: their disk cache, and a log that has a REV log. */
final class StressSupport {

  /** How long to wait for one log's REV log synchronization. */
  static final long SYNC_TIMEOUT_MS = 300_000;

  private StressSupport() {}

  /**
   * Points the server's disk cache at the folder the build gives the stress tests, never the
   * user's. Called after the configuration is applied, so a {@code diskcachedir} there does not
   * win.
   *
   * @return The folder, or null when the test was not started by the build's stress tasks
   */
  static String useTestCache() {
    var dir = System.getProperty("stress.cachedir");
    if (dir == null || dir.isBlank()) return null;
    LogManager.getInstance().getCacheDirectory().setOverride(dir);
    return dir;
  }

  /**
   * The first log, in the order given, with a REV log the server synchronized with it, or null.
   * Each log is loaded and its synchronization awaited; a log without a REV log has none to wait
   * for, and a log that cannot be loaded is passed over.
   */
  static String firstLogWithRevLog(List<String> paths) {
    var manager = LogManager.getInstance();
    for (var path : paths) {
      try {
        manager.getOrLoad(path);
      } catch (Exception e) {
        continue;
      }
      manager.waitForRevLogSync(path, SYNC_TIMEOUT_MS);
      var synced = manager.getSynchronizedLogs(path);
      if (synced != null && synced.hasAnySynchronized()) return path;
    }
    return null;
  }
}
