/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.cache.DiskCache;

/**
 * The server's column files as readers find them. A log whose content has a file opens it from
 * the disk cache; a file that cannot be read, or proves damaged while it is read, is removed and
 * built again, and its readers read their log in the meantime. A file of another format or
 * recipe is removed the same way, as the startup sweep would.
 */
public final class ColumnFiles {
  private static final Logger logger = LoggerFactory.getLogger(ColumnFiles.class);
  private final DiskCache cache;
  private final java.util.function.BiConsumer<String, String> rebuild;

  /** @param rebuild asks for a removed file's build again, by log path and content hash */
  public ColumnFiles(DiskCache cache, java.util.function.BiConsumer<String, String> rebuild) {
    this.cache = cache;
    this.rebuild = rebuild;
  }

  /** Where this content's column file is or would be. */
  public Optional<Path> path(String contentSha256) {
    return cache.columnFilePath(contentSha256);
  }

  /** The column file of this content, open; empty when none is built yet or it cannot be read. */
  public Optional<ColumnFile> open(String contentSha256, String logPath) {
    var target = cache.columnFilePath(contentSha256);
    if (target.isEmpty() || !Files.exists(target.get())) return Optional.empty();
    try {
      return Optional.of(ColumnFile.open(target.get(), "sha256:" + contentSha256));
    } catch (IOException refused) {
      logger.debug("Column file of {} refused: {}", logPath, refused.getMessage());
      discard(target.get(), contentSha256, logPath);
      return Optional.empty();
    }
  }

  /** A file found damaged while it was read: removed, and built again. */
  public void damaged(String contentSha256, String logPath) {
    cache.columnFilePath(contentSha256).ifPresent(path -> discard(path, contentSha256, logPath));
  }

  private void discard(Path path, String contentSha256, String logPath) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException held) {
      // Mapped by another reader on Windows; a later build or the startup sweep replaces it.
    }
    rebuild.accept(logPath, contentSha256);
  }
}
