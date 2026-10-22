/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.read.FieldPath;

/**
 * A loaded log holds its content's column file while it is read: the cache's trim keeps the file
 * while a loaded log holds it, and the file is closed with the log's mapping, after the last
 * reader, as a mapped log is. A file never closed would stay mapped for the life of the process,
 * and on Windows could never be removed.
 */
class ColumnFileLifetimeTest {
  @TempDir Path directory;
  private LogManager manager;
  private Path log;

  @BeforeEach
  void setup() throws Exception {
    directory = directory.toRealPath();
    log = directory.resolve("held.wpilog");
    try (var w = new FixtureWriter(log, "")) {
      for (int i = 0; i < 50; i++) w.dbl("/v", 1 + i * 0.02, i * 0.5);
    }
    manager = IsolatedLogManagers.create(directory, "column-lifetime");
  }

  @AfterEach
  void stop() {
    manager.testReadColumnFiles(false);
    manager.shutdown();
  }

  /** The mapping of the column file a log holds, read without opening one. */
  private static MappedLogBytes mapping(LogData log) throws Exception {
    var held = LazyParsedLog.class.getDeclaredField("columnFile");
    held.setAccessible(true);
    var file = held.get(log);
    var bytes = file.getClass().getDeclaredField("bytes");
    bytes.setAccessible(true);
    return (MappedLogBytes) bytes.get(file);
  }

  @Test
  void aHeldFileIsInUseAndClosesWithTheLogsMapping() throws Exception {
    var file = ColumnFixtures.build(manager, log);
    manager.testReadColumnFiles(true);
    MappedLogBytes mapped;
    try (var use = manager.acquire(log.toString())) {
      assertFalse(manager.columnFileInUse(file), "no read has opened the file yet");
      var samples = use.log().numericColumn("/v", FieldPath.ROOT).orElseThrow().samples();
      assertEquals(0.5, samples.number(1));
      mapped = mapping(use.log());
      assertTrue(mapped.openWindows() > 0);
      assertTrue(manager.columnFileInUse(file), "a file a loaded log holds is in use");
      manager.unloadLog(log.toString());
      assertTrue(mapped.openWindows() > 0, "a reader still holds the log, so its file stays mapped");
    }
    assertFalse(manager.columnFileInUse(file), "an unloaded log holds no file");
    assertEquals(0, mapped.openWindows(), "the file closed with the log's mapping");
  }
}
