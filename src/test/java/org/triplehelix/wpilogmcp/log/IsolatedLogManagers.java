/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.nio.file.Path;
import java.util.UUID;

/**
 * A log manager of a test's own, for tests outside this package: its disk cache is a new folder
 * in the test task's cache, so nothing it writes reaches another test, and it admits one folder.
 */
public final class IsolatedLogManagers {
  private IsolatedLogManagers() {}

  public static LogManager create(Path allowed, String cacheName) {
    String taskCache = System.getenv("WPILOG_DISK_CACHE_DIR");
    if (taskCache == null) throw new IllegalStateException("The test task names WPILOG_DISK_CACHE_DIR");
    var manager = new LogManager();
    manager.getCacheDirectory().setOverride(Path.of(taskCache).resolve(cacheName + "-" + UUID.randomUUID()).toString());
    manager.getDiskCache().setEnabled(true);
    manager.getSyncDiskCache().setEnabled(false);
    manager.addAllowedDirectory(allowed);
    return manager;
  }
}
