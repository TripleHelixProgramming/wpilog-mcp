/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.harness;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Timeout(5)
class HarnessProgressTest {
  @Test void aHealthyReplayCanExceedFiveMinutesWithoutWideningItsStallBound() throws Exception {
    var clock = new AtomicLong(); var received = new AtomicLong();
    HarnessHttp.awaitProgress("synthetic replay", 30, received::get, clock::get, () -> {
      clock.addAndGet(TimeUnit.SECONDS.toNanos(20)); received.incrementAndGet();
      return clock.get() >= TimeUnit.MINUTES.toNanos(10);
    });
    assertEquals(30, received.get());
  }

  @Test void anUnchangedOrRewoundReceiptCounterStillFailsAtThirtySeconds() {
    for (long change : new long[] {0, -1}) {
      var clock = new AtomicLong(); var received = new AtomicLong(100);
      var failure = assertThrows(ExecutionException.class, () -> HarnessHttp.awaitProgress(
          "synthetic stalled replay", 30, received::get, clock::get, () -> {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(10)); received.addAndGet(change);
            if (clock.get() >= TimeUnit.SECONDS.toNanos(40)) throw new AssertionError("Receipt counter hid the stall");
            return false;
          }));
      assertInstanceOf(AssertionError.class, failure.getCause());
      assertEquals("Timed out waiting for synthetic stalled replay", failure.getCause().getMessage());
      assertEquals(TimeUnit.SECONDS.toNanos(30), clock.get());
    }
  }

  @Test void repeatedReadFailuresCannotHideAStalledReplay() {
    var clock = new AtomicLong();
    var failure = assertThrows(ExecutionException.class, () -> HarnessHttp.awaitProgress(
        "synthetic unreadable handshake", 30, () -> 0, clock::get, () -> {
          clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
          if (clock.get() >= TimeUnit.SECONDS.toNanos(40)) throw new AssertionError("Read failures hid the stall");
          throw new java.io.IOException("synthetic read failure");
        }));
    assertEquals(TimeUnit.SECONDS.toNanos(30), clock.get());
    assertInstanceOf(java.io.IOException.class, failure.getCause().getCause());
  }
}
