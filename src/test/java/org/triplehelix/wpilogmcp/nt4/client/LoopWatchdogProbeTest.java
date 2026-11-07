/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Consuming health's interval cannot erase cumulative evidence of an earlier stall. */
class LoopWatchdogProbeTest {
  @Test void probeSeesAnOutstandingLoopAndDoesNotResetTheAllTimeMaximum() {
    var loop = new ManualScheduler();
    var outside = new ManualScheduler();
    try (var watchdog = new LoopWatchdog(loop, outside, ignored -> {})) {
      watchdog.start();
      outside.drain();
      outside.advance(1_250_000);
      assertEquals(1_250_000, watchdog.takeLongestStallUs());
      assertEquals(1_250_000, watchdog.longestStallUs());
      outside.elapse(250_000);
      assertEquals(1_500_000, watchdog.takeLongestStallUs(),
          "An active stall remains visible even before the observer's next scheduled tick");
      loop.drain();
      assertEquals(1_500_000, watchdog.takeLongestStallUs());
      assertEquals(0, watchdog.takeLongestStallUs());
      assertEquals(1_500_000, watchdog.longestStallUs());
    }
  }
  @Test void completingAfterTheIntervalDrainCannotDisappearFromThatProbe() {
    var loop = new ManualScheduler(); var outside = new ManualScheduler();
    try (var watchdog = new LoopWatchdog(loop, outside, ignored -> {})) {
      watchdog.start(); outside.drain();
      outside.elapse(1_500_000); // Advance the independent clock without observing or acknowledging.
      long observed = watchdog.takeLongestStallUs(loop::drain);
      assertEquals(1_500_000, observed, "The active heartbeat at the drain belongs to this probe even if it completes during the read");
      assertEquals(1_500_000, watchdog.takeLongestStallUs(), "Completion after the drain also belongs to the following interval");
      assertEquals(0, watchdog.takeLongestStallUs());
      assertEquals(1_500_000, watchdog.longestStallUs());
    }
  }

  @Test void aDelayedObserverCannotRepublishACompletedHeartbeat() {
    var loop = new ManualScheduler(); var outside = new ManualScheduler();
    try (var watchdog = new LoopWatchdog(loop, outside, ignored -> {})) {
      watchdog.start(); outside.drain(); outside.elapse(1_500_000);
      watchdog.observe(() -> {
        loop.drain();
        assertEquals(1_500_000, watchdog.takeLongestStallUs());
        assertEquals(0, watchdog.takeLongestStallUs());
      });
      assertEquals(0, watchdog.takeLongestStallUs(), "A retired heartbeat cannot reappear after its completed interval was consumed");
      assertEquals(1_500_000, watchdog.longestStallUs());
    }
  }

}
