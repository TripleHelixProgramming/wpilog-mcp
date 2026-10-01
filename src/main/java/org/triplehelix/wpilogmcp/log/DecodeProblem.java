/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

/**
 * Records of an entry that could not be decoded, so tools can say why an entry has fewer values
 * than records instead of silently returning what remains.
 *
 * @param message Why the first failing record failed (e.g. a struct size mismatch)
 * @param failedRecords How many records failed
 * @param totalRecords How many records the entry has
 * @since 0.9.0
 */
public record DecodeProblem(String message, int failedRecords, int totalRecords) {

  /** Whether no record of the entry could be decoded. */
  public boolean allFailed() {
    return failedRecords >= totalRecords;
  }

  /** A one-line description for warnings. */
  public String describe() {
    return failedRecords + " of " + totalRecords + " records could not be decoded: " + message;
  }
}
