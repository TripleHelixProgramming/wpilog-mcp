/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** Even an in-store alias is not the persistent lock file every writer must share. */
class StoreLockTest {
  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lockRejectsSymlinksEvenWhenTheirTargetsAreInsideTheStore(boolean targetExists) throws Exception {
    root = root.toRealPath();
    var security = new SecurityValidator();
    security.addAllowedDirectory(root);
    var target = root.resolve("target");
    if (targetExists) Files.writeString(target, "untouched");
    var link = root.resolve("store.lock");
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("Symlinks unavailable: " + e.getMessage());
    }
    var error = assertThrows(IOException.class, () -> {
      try (var ignored = StoreLock.acquire(root, security)) {
        // Close even an incorrectly accepted lock, so the failed test leaves no open handle.
      }
    });
    assertTrue(error.getMessage().contains("symbolic link"), error.getMessage());
    assertTrue(error.getMessage().contains(link.toString()), error.getMessage());
    assertEquals(targetExists, Files.exists(target));
    if (targetExists) assertEquals("untouched", Files.readString(target));
  }
}
