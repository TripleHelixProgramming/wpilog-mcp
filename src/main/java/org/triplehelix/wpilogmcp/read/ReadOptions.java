/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.nio.file.Path;
import java.util.List;

/** Explicit local limits. The sort budget includes permutations and temporary timestamp arrays
 * across every snapshot of this access instance; no disk cache or process-global settings are used. */
public record ReadOptions(List<Path> roots, long sortMemoryBytes, int maxRecordBytes, int windowBytes) {
  public ReadOptions {
    roots = List.copyOf(roots);
    if (roots.isEmpty()) throw new IllegalArgumentException("At least one read root is required");
    if (sortMemoryBytes < 0) throw new IllegalArgumentException("sortMemoryBytes must be nonnegative");
    if (maxRecordBytes < 1 || maxRecordBytes > Integer.MAX_VALUE - 32)
      throw new IllegalArgumentException("maxRecordBytes must fit one record");
    if (windowBytes < 1 || windowBytes > 1 << 30)
      throw new IllegalArgumentException("windowBytes must be between 1 and 1 GiB");
  }
  public static ReadOptions defaults(List<Path> roots) {
    return new ReadOptions(roots, 64L << 20, 64 << 20, 1 << 30);
  }
}
