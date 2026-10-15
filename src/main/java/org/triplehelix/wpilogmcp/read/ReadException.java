/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;

/** An explained read refusal, without HTTP status codes or MCP result objects. */
public final class ReadException extends IOException {
  private final ReadProblem.Code code;
  private final boolean retryable;
  public ReadException(ReadProblem.Code code, String message) {
    this(code, message == null || message.isBlank() ? "Read failed without a message" : message, null, false);
  }
  public ReadException(ReadProblem.Code code, String message, Throwable cause) { this(code, message, cause, false); }
  /** A temporary resource refusal preserves the caller's cursor; structural limits do not. */
  public ReadException(ReadProblem.Code code, String message, boolean retryable) { this(code, message, null, retryable); }
  private ReadException(ReadProblem.Code code, String message, Throwable cause, boolean retryable) {
    super(message == null || message.isBlank() ? "Read failed: " + (cause == null ? "unknown cause" : cause.getClass().getSimpleName()) : message, cause);
    this.code = code; this.retryable = retryable;
  }
  public ReadProblem.Code code() { return code; }
  public boolean retryable() { return retryable; }
}
