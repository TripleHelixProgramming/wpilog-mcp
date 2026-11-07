/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;

/**
 * Decoded values may use 60% of the heap, shared equally by the loaded logs and at least 128 MiB
 * each. A share fixed when a log loaded let the first two logs each claim 60% of the heap, and kept
 * what an unload freed.
 */
class ValueBudgetTest {
  @TempDir Path temp;

  /** The rule, worked from the heap: 60% of it divided among the loaded logs, at least 128 MiB. */
  static long share(int logs) {
    long total = (long) (Runtime.getRuntime().maxMemory() * 0.6);
    return Math.max(128L * 1024 * 1024, total / logs);
  }

  @Test void loadedLogsShareTheBudgetAndAnUnloadReturnsItsShare() throws Exception {
    var manager = new LogManager();
    try {
      manager.addAllowedDirectory(temp);
      var logs = new ArrayList<LazyParsedLog>();
      for (int i = 0; i < 3; i++) {
        var path = temp.resolve("2026-budget-" + i + ".wpilog");
        try (var w = new FixtureWriter(path, "")) { w.dbl("/Value", 0, i).dbl("/Value", 1, i + 1); }
        logs.add((LazyParsedLog) manager.loadLog(path.toString()));
        for (var log : logs) assertEquals(share(logs.size()), log.valueBudget(), logs.size() + " loaded");
      }
      assertTrue(manager.unloadLog(logs.get(0).path()));
      for (var log : List.of(logs.get(1), logs.get(2))) assertEquals(share(2), log.valueBudget(), "after an unload");
    } finally { manager.shutdown(); }
  }
}
