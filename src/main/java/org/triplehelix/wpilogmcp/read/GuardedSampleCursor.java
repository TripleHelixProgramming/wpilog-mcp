/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A source remains retained through an active batch, even if another thread closes its cursor. */
public final class GuardedSampleCursor implements SampleCursor {
  @FunctionalInterface public interface IoAction { void run() throws IOException; }

  private final SampleCursor cursor;
  private final IoAction check;
  private final IoAction release;
  private final String path;
  private final AtomicBoolean active = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean released = new AtomicBoolean();

  public GuardedSampleCursor(SampleCursor cursor, IoAction check, IoAction release, String path) {
    this.cursor = Objects.requireNonNull(cursor);
    this.check = Objects.requireNonNull(check);
    this.release = Objects.requireNonNull(release);
    this.path = Objects.requireNonNull(path);
  }

  @Override public SampleBatch next() throws IOException {
    if (!active.compareAndSet(false, true))
      throw new IllegalStateException("One cursor cannot be read concurrently");
    try {
      if (closed.get()) throw new ReadException(ReadProblem.Code.CLOSED, "Sample cursor is closed");
      check.run();
      var batch = cursor.next();
      check.run();
      return batch;
    } catch (InternalError failure) {
      throw new ReadException(ReadProblem.Code.FILE_CHANGED,
          "Mapped log could not be read; the file may have changed: " + path + ". Reopen it.", failure);
    } finally {
      active.set(false);
      if (closed.get()) release();
    }
  }

  private void release() throws IOException {
    if (released.compareAndSet(false, true)) release.run();
  }

  @Override public void close() throws IOException {
    try {
      if (closed.compareAndSet(false, true)) cursor.close();
    } finally {
      if (!active.get()) release();
    }
  }
}
