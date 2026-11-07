/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Path;

/** Resolve the physical destination before writing; a nested mount has its own shared budget. */
@FunctionalInterface
interface CopySpace {
  DiskSpaceBudgets.Reservation reserve(Path existingDirectory, long bytes) throws IOException;
}
