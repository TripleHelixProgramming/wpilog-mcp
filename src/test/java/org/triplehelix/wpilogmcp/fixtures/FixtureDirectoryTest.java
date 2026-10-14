/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogFileException;
import org.triplehelix.wpilogmcp.log.LogManager;

/** A fixture creator admits its own cleanup root even after another test configured containment. */
class FixtureDirectoryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freshOwnedRootWorksWithExistingRestrictionsWithoutAdmittingItsSiblings(boolean exists) throws Exception {
    var manager = LogManager.getInstance();
    var security = manager.testGetSecurityValidator();
    var saved = security.getConfiguredDirectories();
    var unrelated = Files.createDirectory(directory.resolve("already-allowed"));
    var owned = directory.resolve("owned-fixture");
    var outside = Files.createDirectory(directory.resolve("outside"));
    if (exists) {
      Files.createDirectories(owned.resolve("stale"));
      Files.writeString(owned.resolve("stale/old.txt"), "previous fixture");
    }
    manager.clearAllowedDirectories(); manager.addAllowedDirectory(unrelated);
    try {
      assertEquals(owned, FixtureLogs.freshDirectory(owned));
      assertTrue(Files.isDirectory(owned));
      try (var children = Files.list(owned)) { assertEquals(0, children.count(), "Prior fixture files must be cleared"); }
      assertDoesNotThrow(() -> security.validate(owned.resolve("new.wpilog")));
      assertDoesNotThrow(() -> security.validate(unrelated.resolve("retained.wpilog")));
      assertThrows(LogFileException.class, () -> security.validate(outside.resolve("private.wpilog")),
          "Admitting a fixture root must not authorize a sibling");
      assertEquals(Set.of(unrelated.toRealPath(), owned.toRealPath()), security.getConfiguredDirectories());
    } finally {
      manager.clearAllowedDirectories(); saved.forEach(manager::addAllowedDirectory);
    }
  }
}
