/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.columns.ColumnFileFormat.Kind;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * Every column of every fixture against the log it was built from, through {@link ColumnOracle}:
 * the fixtures cover each logging convention, struct arrays, nested and custom structs, records
 * that fail to decode, and a log that ends mid-record.
 */
class ColumnFileDifferentialTest {

  @Test
  void everyFixtureReadsFromItsColumnsAsFromTheLog(@TempDir Path scratch) throws Exception {
    var dir = FixtureLogs.defaultDirectory();
    var fixtures = FixtureLogs.generateAll(dir);
    var manager = LogManager.getInstance();
    // An empty grant list allows every path, which tests that never add a grant rely on.
    var allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(dir);
    var total = new ColumnOracle.Counts();
    try {
      compareAll(fixtures, manager, scratch, total);
    } finally {
      manager.clearAllowedDirectories();
      allowed.forEach(manager::addAllowedDirectory);
    }
    System.out.println("Column files compared with the fixtures' logs: " + total);
    assertTrue(total.structColumns > 0, "no struct leaves were compared: " + total);
    assertTrue(total.pooled > 0, "no pooled paths were compared: " + total);
    assertTrue(total.kinds.containsAll(EnumSet.of(Kind.F64, Kind.I64, Kind.BOOL)), "kinds compared: " + total.kinds);
  }

  private static void compareAll(java.util.List<FixtureLogs.Fixture> fixtures, LogManager manager, Path scratch,
      ColumnOracle.Counts total) throws Exception {
    for (var fixture : fixtures) {
      var source = "test:" + fixture.id();
      var file = scratch.resolve(fixture.id() + ".columns");
      try (var use = manager.acquire(fixture.path().toString())) {
        var written = ColumnFileWriter.write(use.log(), source, file);
        assertEquals(Files.size(file), written.bytes(), fixture.id() + ": bytes written");
        try (var columns = ColumnFile.open(file, source)) {
          var counts = ColumnOracle.compare(use.log(), columns, fixture.id());
          assertEquals(written.entries(), counts.entries, fixture.id() + ": entries held");
          assertEquals(written.columns(), counts.columns, fixture.id() + ": columns held");
          total.add(counts);
        }
      } finally {
        manager.unloadLog(fixture.path().toString());
      }
    }
  }
}
