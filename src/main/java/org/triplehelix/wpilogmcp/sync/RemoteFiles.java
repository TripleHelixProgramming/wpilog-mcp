/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Read-only transport shared by robot SFTP and the later store mirror. There is no delete API. */
public interface RemoteFiles extends AutoCloseable {
  record File(String name, long size, long mtimeMillis) {
    public File {
      if (name == null || name.isBlank() || size < 0) throw new IllegalArgumentException("Invalid remote file");
    }
  }
  List<File> list() throws IOException;
  byte[] read(String name, long offset, int count) throws IOException;
  /** SHA-256 of exactly the first length bytes; empty means exec/hash support is unavailable. */
  Optional<String> prefixHash(String name, long length) throws IOException;
  @Override default void close() throws IOException {}
}
