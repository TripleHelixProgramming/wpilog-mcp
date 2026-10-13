/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

/** Transport-neutral diagnostics. A malformed payload does not silently erase a sample: the
 * batch identifies its file position and type failure. File/limit failures throw ReadException. */
public record ReadProblem(Code code, String entry, long recordOffset, String message) {
  public enum Code { MALFORMED_RECORD, INVALID_REQUEST, BUSY, SCHEMA_UNAVAILABLE, RESOURCE_LIMIT, FILE_CHANGED, ACCESS_DENIED, CLOSED }
}
