/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.harness;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteLogInventoryTest {
  @TempDir Path temp;

  @Test void fakeInventoryReportsTheActualRenamedFilesWithRemotePaths() throws Exception {
    try (var rio = new FakeRoboRio(temp.resolve("rio"), "synthetic-inventory", "synthetic")) {
      Files.writeString(rio.logs().resolve("boot-1.wpilog"), "synthetic");
      Files.writeString(rio.logs().resolve("boot-0.wpilog"), "synthetic");
      Files.writeString(rio.logs().resolve("console.log"), "synthetic");
      Files.createDirectory(rio.logs().resolve("directory.wpilog"));
      assertEquals(List.of("/home/lvuser/logs/boot-0.wpilog", "/home/lvuser/logs/boot-1.wpilog"),
          rio.logNames());
      Files.move(rio.logs().resolve("boot-0.wpilog"), rio.logs().resolve("renamed log.wpilog"));
      assertEquals(List.of("/home/lvuser/logs/boot-1.wpilog", "/home/lvuser/logs/renamed log.wpilog"),
          rio.logNames());
    }
  }

  @Test void containerInventoryUsesTheFinalUsbListingWithoutAShell() throws Exception {
    var calls = new ArrayList<List<String>>();
    var rio = new RioContainer("synthetic-container", temp, arguments -> {
      calls.add(List.of(arguments));
      return "renamed log.wpilog\r\nconsole.log\r\nboot-1.wpilog\r\n";
    });
    assertEquals(List.of("/u/logs/boot-1.wpilog", "/u/logs/renamed log.wpilog"), rio.logNames());
    assertEquals(List.of(List.of("exec", "synthetic-container", "ls", "-1", "/u/logs")), calls);
  }

  @Test void unreadableContainerInventoryDoesNotBecomeAnEmptySuccessfulListing() {
    var failure = new java.io.IOException("synthetic Docker listing failed");
    var rio = new RioContainer("synthetic-container", temp, arguments -> { throw failure; });
    assertSame(failure, assertThrows(java.io.IOException.class, rio::logNames));
  }
}
