/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Read-only admission and lifetime. The local implementation is independent of a daemon,
 * transport, home configuration or store writer; the server adapter shares existing admission.
 * Explicit files inside configured roots also cover files held in stores and mirrors.
 * Callers own snapshots and cursors; closing access retires snapshots but lets admitted cursors finish. */
public interface LogAccess extends AutoCloseable {
  static LogAccess local(List<Path> roots) throws IOException { return local(ReadOptions.defaults(roots)); }
  static LogAccess local(ReadOptions options) throws IOException { return new LocalLogAccess(options); }
  ReadSnapshot open(Path path) throws IOException;
  /** Explicit polling; no background thread, watcher or daemon is installed. Local access only. */
  default LogFollower follow(Path path) throws IOException { return new LogFollower(this, path); }
  @Override void close() throws IOException;
}
