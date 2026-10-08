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
    ImportFixture.write(Path.of(args[0]), 7);
  }
}
