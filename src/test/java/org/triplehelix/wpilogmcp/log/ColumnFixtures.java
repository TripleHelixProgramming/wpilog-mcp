/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.triplehelix.wpilogmcp.columns.ColumnFileWriter;

/**
 * Column files written for tests, as the server's background build writes them, so a test can
 * read a log through its column file without waiting for one; see {@link
 * LogManager#testReadColumnFiles}.
 */
public final class ColumnFixtures {
  private ColumnFixtures() {}

  /**
   * Writes the column file of the log at {@code log} into the manager's disk cache, named by the
   * content its scan index proved, and unloads the log so its next load opens the file.
   */
  public static Path build(LogManager manager, Path log) throws IOException {
    Path target;
    try (var use = manager.acquireFromFile(log.toString())) {
      var lazy = (LazyParsedLog) use.log();
      var hash = lazy.contentSha256().orElseThrow(() -> new IllegalStateException(log + " has no proven content"));
      target = manager.getDiskCache().columnFilePath(hash).orElseThrow();
      if (!Files.exists(target)) {
        var temporary = target.resolveSibling(target.getFileName() + ".tmp." + UUID.randomUUID());
        try {
          ColumnFileWriter.write(lazy, "sha256:" + hash, temporary);
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
          Files.deleteIfExists(temporary);
        }
      }
    }
    manager.unloadLog(log.toString());
    return target;
  }
}
