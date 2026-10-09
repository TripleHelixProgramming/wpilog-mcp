/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.log.*;

class WindowedImportTest {
  @TempDir Path temp;
  @Test void importReleasesEverySmallWindowBeforeMovingTheSource() throws Exception {
    var fixture = FixtureLogs.generateAll(FixtureLogs.defaultDirectory()).get(0);
    var source = temp.resolve("source.wpilog"); Files.copy(fixture.path(), source);
    try (var small = MappedLogBytes.withWindowBytes(4096)) {
      var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
      try (var registry = new StoreRegistry(manager.testGetSecurityValidator(), manager)) {
        var loaded = manager.getOrLoad(source.toString()); assertTrue(loaded.sampleCount(loaded.entries().keySet().iterator().next()) >= 0);
        var outcome = registry.store(temp.resolve("store")).importPaths(new LogStore.Request(List.of(source), true, "synthetic"), progress -> {
          assertEquals(4096, MappedLogBytes.windowBytes(), "The store's inspection uses the injected window too");
        }).get(30, TimeUnit.SECONDS).files().get(0);
        assertNotEquals("refused", outcome.status(), outcome::toString);
        assertFalse(Files.exists(source)); assertEquals(-1, Files.mismatch(fixture.path(), outcome.path()));
      } finally { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
    }
  }
}
