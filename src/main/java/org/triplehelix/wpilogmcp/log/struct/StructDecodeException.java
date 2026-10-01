/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log.struct;

/**
 * A struct record that cannot be decoded: no schema for its type, an invalid schema, or a record
 * whose length does not fit the schema. The message says which, in terms of the log's own data.
 *
 * @since 0.9.0
 */
public class StructDecodeException extends RuntimeException {
  public StructDecodeException(String message) {
    super(message);
  }
}
