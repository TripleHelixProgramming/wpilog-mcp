/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.nio.ByteBuffer;

/** Absolute long addresses; a view belongs to the lifetime of its byte source. */
public interface LogBytes {
  long size();
  byte get(long offset);
  ByteBuffer view(long offset, int length);
}
