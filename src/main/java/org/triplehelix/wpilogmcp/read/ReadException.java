/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;

/** An explained read refusal, without HTTP status codes or MCP result objects. */
public final class ReadException extends IOException {
  private final ReadProblem.Code code;
  public ReadException(ReadProblem.Code code, String message) { super(message == null || message.isBlank() ? "Read failed without a message" : message); this.code = code; }
  public ReadException(ReadProblem.Code code, String message, Throwable cause) { super(message == null || message.isBlank() ? "Read failed: " + (cause == null ? "unknown cause" : cause.getClass().getSimpleName()) : message, cause); this.code = code; }
  public ReadProblem.Code code() { return code; }
}
