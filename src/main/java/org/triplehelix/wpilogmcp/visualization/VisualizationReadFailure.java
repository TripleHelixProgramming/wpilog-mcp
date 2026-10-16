/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;

/** HTTP and MCP retain native admission's distinction between moving inventory and changed
 * recording history. A transport must not turn either into a malformed-request diagnosis. */
public record VisualizationReadFailure(int status, String code, String message, boolean retryable) {
  /** The same one-second backoff used by inventory pressure; permanent refusals give no delay. */
  public int retryAfterMs() { return retryable ? 1000 : 0; }
  public static VisualizationReadFailure of(ReadException failure) {
    int status = switch (failure.code()) {
      case ACCESS_DENIED -> 403;
      case FILE_CHANGED -> 409;
      case CLOSED, FOLLOW_ENDED -> 410;
      case BUSY -> 503;
      case RESOURCE_LIMIT -> 413;
      default -> 400;
    };
    String code = failure.code() == ReadProblem.Code.FILE_CHANGED && !failure.retryable() ? "HISTORY_UNAVAILABLE" : failure.code().name();
    return new VisualizationReadFailure(status, code, failure.getMessage(), failure.retryable());
  }
}
