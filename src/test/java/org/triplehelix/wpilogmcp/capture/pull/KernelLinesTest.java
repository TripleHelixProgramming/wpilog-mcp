/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class KernelLinesTest {
  @Test void overlappingBuffersAppendOnlyNewLinesAndFallingUptimeStartsAnotherBoot() {
    var first = KernelLines.following(List.of("[ 1.000] boot", "[ 2.000] warning: sample"), 12, null);
    var next = KernelLines.following(List.of("[ 1.000] boot", "[ 2.000] warning: sample", "[ 3.500] error: sample"), 14, first.cursor());
    assertEquals(List.of("[ 3.500] error: sample"), next.lines()); assertFalse(next.reboot());
    assertEquals(3.5, next.cursor().lastSeconds()); assertEquals(14, next.cursor().uptimeSec());
    var same = KernelLines.following(List.of("[ 2.000] warning: sample", "[ 3.500] error: sample"), 15, next.cursor());
    assertTrue(same.lines().isEmpty());
    var reboot = KernelLines.following(List.of("[ 1.000] boot"), 2, same.cursor());
    assertTrue(reboot.reboot()); assertEquals(List.of("[ 1.000] boot"), reboot.lines());
  }

  @Test void equalTimestampsStillUseTheLastLineAndAWrappedBufferReportsTheGap() {
    var previous = new KernelLines.Cursor(12, "[ 2.000] old", 2.0);
    var next = KernelLines.following(List.of("[ 2.000] old", "[ 2.000] new"), 13, previous);
    assertEquals(List.of("[ 2.000] new"), next.lines());
    var wrapped = KernelLines.following(List.of("[ 5.000] later"), 14, next.cursor());
    assertEquals(List.of("[ 5.000] later"), wrapped.lines());
    assertTrue(wrapped.note().contains("overwritten"));
  }
}
