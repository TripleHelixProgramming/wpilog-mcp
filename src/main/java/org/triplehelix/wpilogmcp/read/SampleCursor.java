/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;

/** A sequential, closeable read. Closing cancels the remaining work and releases its mapping use;
 * different cursors may run concurrently. No decoded sample retains a mapped buffer. */
public interface SampleCursor extends AutoCloseable {
  SampleBatch next() throws IOException;
  @Override void close() throws IOException;
}
