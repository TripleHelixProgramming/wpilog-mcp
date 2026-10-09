/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogManager;

class SystemConformanceFixtureTest {
  @TempDir Path temp;
  @Test void repeatingTheFirstPullOnTheSameFixtureRootStartsFresh() throws Exception {
    var root = temp.resolve("system-store");
    var manager = LogManager.getInstance();
    var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    try {
      var first = ToolConformanceTest.createSystemFixture(root);
      var second = ToolConformanceTest.createSystemFixture(root);
      assertEquals(first, second);
      assertTrue(java.nio.file.Files.exists(second));
    } finally { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
}
