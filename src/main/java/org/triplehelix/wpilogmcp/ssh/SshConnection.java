/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/** One authenticated host session; each blocking consumer owns a separate channel and worker. */
public interface SshConnection extends AutoCloseable {
  record Item(String name, long size, long mtimeMillis, boolean directory, boolean regular, boolean symlink) {}
  interface Files extends AutoCloseable {
    List<Item> list(String directory) throws IOException;
    byte[] read(String path, long offset, int count) throws IOException;
    @Override void close();
  }
  interface Command extends AutoCloseable {
    InputStream output();
    @Override void close();
  }
  String fingerprint();
  boolean connected();
  Files files() throws IOException;
  Optional<String> exec(String command, long timeoutMs, int maxBytes) throws IOException;
  default Optional<byte[]> execBytes(String command, long timeoutMs, int maxBytes) throws IOException {
    return exec(command, timeoutMs, maxBytes).map(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  Command follow(String command) throws IOException;
  @Override void close();

  static String quote(String value) {
    if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in remote path");
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
}
