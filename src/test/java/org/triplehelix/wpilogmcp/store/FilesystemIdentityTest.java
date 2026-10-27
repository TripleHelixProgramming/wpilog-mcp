/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One disk is one filesystem, and has one budget, however long the path that reaches it. */
class FilesystemIdentityTest {
  @TempDir Path temp;

  /** A folder past 247 characters, beyond which Windows paths reach Java's file store with a prefix. */
  private static Path deep(Path base) throws IOException {
    var path = base;
    while (path.toString().length() <= 270) path = path.resolve("a-folder-twenty-long");
    return Files.createDirectories(path);
  }

  @Test void aLongPathIsOnTheSameFilesystemAsAShortOne() throws Exception {
    var near = temp.toRealPath();
    var far = deep(near);
    assertTrue(near.toString().length() < 248 && far.toString().length() > 247, near + " / " + far);
    assertEquals(FilesystemIdentity.of(near), FilesystemIdentity.of(far));
    assertTrue(FilesystemIdentity.same(near, far));
  }

  @Test void oneDiskHasOneBudgetHoweverItsStoresAreReached() throws Exception {
    var near = Files.createDirectory(temp.resolve("near"));
    var far = deep(Files.createDirectory(temp.resolve("far")));
    try (var budgets = new DiskSpaceBudgets()) {
      budgets.store(near);
      budgets.store(far);
      assertEquals(1, budgets.snapshots().size(), "a copy admitted through either store spends one disk's reserve");
    }
  }
}
