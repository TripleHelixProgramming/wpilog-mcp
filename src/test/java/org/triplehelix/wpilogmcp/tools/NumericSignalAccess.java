/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.util.List;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/** The signal resolver's answer, for tests in other packages that compare another read with it. */
public final class NumericSignalAccess {
  private NumericSignalAccess() {}

  /** The values the numeric tools read, the entry's decoded records, and those with no value. */
  public record Resolved(List<TimestampedValue> values, int recordCount, int recordsWithoutValue) {}

  /** Resolves as the tools do, with {@code field} null for the entry itself. */
  public static Resolved resolve(LogData log, String entry, String field) {
    var signal = NumericSignal.resolve(log, entry, field);
    return new Resolved(signal.values(), signal.recordCount(), signal.recordsWithoutValue());
  }
}
