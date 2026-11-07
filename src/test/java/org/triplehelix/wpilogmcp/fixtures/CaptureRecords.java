/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.triplehelix.wpilogmcp.log.WallClock;

/**
 * A capture file's records as the robot sent them. A capture also records its server's own
 * clock beside the robot's values; a test of what the robot sent leaves those readings out.
 *
 * <p>WPILib's iterator ends a for-each loop once fewer than 16 bytes remain, which skips a short
 * last record, such as an integer value in a file that ends without finish records; the records
 * are read with {@code forEachRemaining}, which reads to the end.
 */
public final class CaptureRecords {
  private CaptureRecords() {}

  /** The data records of a capture file in file order, without the recorder's clock readings. */
  public static List<DataLogRecord> robot(Path file) throws IOException {
    var clock = new HashSet<Integer>();
    var out = new ArrayList<DataLogRecord>();
    var all = new ArrayList<DataLogRecord>();
    new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file))).iterator().forEachRemaining(all::add);
    for (var record : all) {
      if (record.isStart()) {
        var start = record.getStartData();
        if (start.name.equals(WallClock.PIT_CLOCK_ENTRY)) clock.add(start.entry); else clock.remove(start.entry);
        continue;
      }
      if (!record.isControl() && !clock.contains(record.getEntry())) out.add(record);
    }
    return out;
  }

  /** The robot's integer values, in file order. */
  public static List<Long> integers(Path file) throws IOException {
    return robot(file).stream().map(DataLogRecord::getInteger).toList();
  }
}
