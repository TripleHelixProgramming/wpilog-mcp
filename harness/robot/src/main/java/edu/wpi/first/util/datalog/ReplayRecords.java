/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package edu.wpi.first.util.datalog;

/**
 * Delegate record boundaries to WPILib, including a short final record its iterator omits.
 * This harness has no dependency on the server or its differential reader.
 */
public final class ReplayRecords {
  private ReplayRecords() {}
  public static DataLogRecord at(DataLogReader reader, int offset) { return reader.getRecord(offset); }
  public static int end(DataLogReader reader, int offset) {
    try { int end = reader.getNextRecord(offset); return end > offset && end <= reader.size() ? end : -1; }
    catch (IndexOutOfBoundsException invalid) { return -1; }
  }
}
