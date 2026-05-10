/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

/**
 * Exception thrown for configuration errors (missing file, invalid format, unknown server name).
 *
 * @since 0.8.0
 */
public class ConfigException extends Exception {
  public ConfigException(String message) {
    super(message);
  }

  public ConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
