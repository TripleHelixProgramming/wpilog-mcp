/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package edu.wpi.first.util.datalog;

/** Counts actual record access, including an accidental second scan, on the calling thread. */
public final class RecordReads implements AutoCloseable {
  private long bytes;
  public RecordReads() { DataLogAccess.READ_BYTES.set(n -> bytes += n); }
  public long bytes() { return bytes; }
  @Override public void close() { DataLogAccess.READ_BYTES.remove(); }
}
