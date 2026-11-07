/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import java.nio.file.Path;

/** Gives the real editor the same specification-written, synthetic input as the store tests. */
public final class ExtensionSmokeFixture {
  private ExtensionSmokeFixture() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected the output fixture path");
    var path = Path.of(args[0]);
    ImportFixture.write(path, 7);
    var later = path.resolveSibling("append-record.tmp");
    try (var writer = new WpilogWriter(later, "")) {
      writer.append(2, 2_000_000, WpilogWriter.encodeDouble(9));
    }
    var bytes = java.nio.file.Files.readAllBytes(later);
    java.nio.file.Files.write(path.resolveSibling("append.bin"), java.util.Arrays.copyOfRange(bytes, 12, bytes.length));
    java.nio.file.Files.delete(later);
  }
}
