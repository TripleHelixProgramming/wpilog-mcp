/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tba;

/**
 * The Blue Alliance could not answer: an HTTP status other than 200 or 404 (a rejected API key, a
 * server error), a network failure, or a response that is not JSON. Distinct from "not found"
 * (404), so a tool never reports an outage as a missing match.
 *
 * @since 0.9.0
 */
public class TbaUnavailableException extends RuntimeException {

  public TbaUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
