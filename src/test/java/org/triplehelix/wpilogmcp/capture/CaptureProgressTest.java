/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Public health uses a finite vocabulary and completed-write time, never private exception text. */
class CaptureProgressTest {
  @Test void stateAndWriteAgeHaveIndependentKnownBases() {
    var now = new AtomicLong(1_000_000);
    var progress = new CaptureProgress(now::get);
    var before = progress.probe(0, 0);
    assertEquals("stopped", before.state());
    assertEquals("not_started", before.reason());
    assertNull(before.lastWriteAgeMs());
    progress.waiting();
    assertEquals("waiting_admission", progress.probe(0, 0).state());
    assertNull(progress.probe(0, 0).reason());
    progress.recording();
    progress.written();
    now.addAndGet(125_000);
    var active = progress.probe(1_250_000, 75_000);
    assertEquals("recording", active.state());
    assertEquals(125.0, active.lastWriteAgeMs());
    assertEquals(1_250_000, active.longestListenerLoopStallUs());
    assertEquals(75_000, active.longestStoreQueueTurnUs());
    progress.stopped(CaptureProgress.Reason.WRITE_FAILED);
    now.addAndGet(75_000);
    var stopped = progress.probe(0, 0);
    assertEquals("stopped", stopped.state());
    assertEquals("write_failed", stopped.reason());
    assertEquals(200.0, stopped.lastWriteAgeMs());
  }
}
