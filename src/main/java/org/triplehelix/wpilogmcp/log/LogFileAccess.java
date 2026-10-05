/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Coordinates imports with readers in this process. A shared lazy reader has no deterministic
 * unmap point (calls may retain an evicted log), so its file stays ineligible for moves until
 * restart. Copy imports remain available. Scoped readers release their claims with the mapping.
 */
public final class LogFileAccess {
  private LogFileAccess() {}

  private static final HashMap<Path, Integer> readers = new HashMap<>();
  private static final HashSet<Path> shared = new HashSet<>();
  private static final HashSet<Path> moving = new HashSet<>();

  public interface Lease extends AutoCloseable { @Override void close(); }

  public static Lease read(Path path, boolean sharedLifetime) throws IOException {
    var real = path.toRealPath();
    synchronized (LogFileAccess.class) {
      if (moving.stream().anyMatch(real::startsWith)) throw new IOException("Log is being moved: " + path);
      if (sharedLifetime) shared.add(real);
      else readers.merge(real, 1, Integer::sum);
    }
    return () -> {
      if (!sharedLifetime) synchronized (LogFileAccess.class) {
        readers.computeIfPresent(real, (p, count) -> count == 1 ? null : count - 1);
      }
    };
  }

  public static Lease move(List<Path> paths) throws IOException {
    var real = new java.util.ArrayList<Path>();
    for (var path : paths) real.add(path.toRealPath());
    synchronized (LogFileAccess.class) {
      for (var path : real) {
        if (readers.keySet().stream().anyMatch(p -> p.startsWith(path))
            || shared.stream().anyMatch(p -> p.startsWith(path))
            || moving.stream().anyMatch(p -> p.startsWith(path) || path.startsWith(p))) {
          throw new IOException("Cannot move an open or previously mapped log: " + path
              + ". Import by copy, or restart the server before organizing it by move.");
        }
      }
      moving.addAll(real);
    }
    return () -> { synchronized (LogFileAccess.class) { moving.removeAll(real); } };
  }
}
