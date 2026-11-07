/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.nio.file.Path;
import java.util.Set;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.ParsedLog;

/** Synthetic paths need admission too; cached-path exceptions depend on transport state. */
final class MockLogAdmission implements AutoCloseable {
  private final LogManager manager = LogManager.getInstance();
  private final Set<Path> previous = manager.getConfiguredDirectories();
  void put(ParsedLog log) {
    manager.addAllowedDirectory(Path.of(log.path()).toAbsolutePath().getParent());
    manager.testPutLog(log.path(), log);
  }
  @Override public void close() {
    manager.unloadAllLogs();
    manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
}
