/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;

/** The inbox and enrolled folders share one settled-file rule, not two racing guesses. */
final class FileStability {
  static final Duration SETTLE_TIME = Duration.ofSeconds(3);
  private FileStability() {}

  record Stamp(long size, String modified, String identity) {
    static Stamp of(BasicFileAttributes attrs) {
      return new Stamp(attrs.size(), attrs.lastModifiedTime().toString(),
          attrs.fileKey() == null ? attrs.creationTime().toString() : attrs.fileKey().toString());
    }
    static Stamp read(Path path) throws IOException {
      var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attrs.isRegularFile()) throw new IOException("Source must be a regular file, not a link");
      return of(attrs);
    }
  }
  static boolean settled(long now, long observedAt) {
    return now - observedAt >= SETTLE_TIME.toNanos();
  }
}
