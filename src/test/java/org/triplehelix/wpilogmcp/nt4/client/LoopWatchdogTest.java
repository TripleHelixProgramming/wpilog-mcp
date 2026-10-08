/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class LoopWatchdogTest {
  @Test void outsideClockReportsAStallOnceThenRecoveryWithoutNeedingTheBlockedLoop() {
    var loop = new ManualScheduler(); var outside = new ManualScheduler(); var messages = new ArrayList<String>();
    try (var monitor = new LoopWatchdog(loop, outside, messages::add)) {
      monitor.start(); outside.drain();
      outside.advance(999_999); assertTrue(messages.isEmpty());
      outside.advance(250_000); assertEquals(1, messages.size()); assertTrue(messages.get(0).contains("event loop stalled"));
      outside.advance(30_000_000); assertEquals(1, messages.size());
      loop.drain(); outside.advance(250_000); assertEquals(2, messages.size()); assertTrue(messages.get(1).contains("resumed"));
      for (int i = 0; i < 20; i++) { loop.drain(); outside.advance(250_000); }
      assertEquals(2, messages.size());
    }
    outside.advance(30_000_000); assertEquals(2, messages.size());
  }
}
