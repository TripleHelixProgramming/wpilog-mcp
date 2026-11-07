/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Probe intervals must not join the blocked owner or erase cumulative turn evidence. */
class StoreQueueProbeTest {
  @Test void aProbeSeesAnActiveTurnAndOnlyResetsItsOwnInterval() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var queue = new StoreQueue(ignored -> {});
    long during;
    try {
      queue.capture("probe-fixture", () -> {
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var probe = java.util.concurrent.CompletableFuture.supplyAsync(queue::takeLongestTurnUs);
      during = probe.get(500, TimeUnit.MILLISECONDS);
      assertTrue(during > 0, "The current turn is visible without waiting for it to end");
      assertTrue(queue.takeLongestTurnUs() >= during, "An unfinished turn remains visible in the next interval");
    } finally { release.countDown(); queue.shutdown(); assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS)); }
    long allTime = queue.metrics().longestTurnUs();
    assertTrue(allTime >= during);
    assertTrue(queue.takeLongestTurnUs() >= during);
    assertEquals(0, queue.takeLongestTurnUs(), "The finished interval is consumed once");
    assertEquals(allTime, queue.metrics().longestTurnUs(), "Health never resets the cumulative maximum");
  }
  @Test void completingAfterTheIntervalDrainCannotDisappearFromThatProbe() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var queue = new StoreQueue(ignored -> {}, false);
    try {
      queue.capture("probe-race", () -> {
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      long observed = queue.takeLongestTurnUs(() -> {
        release.countDown(); queue.shutdown();
        try { assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
      });
      assertTrue(observed > 0, "The owner active at the drain must not disappear when it completes during the read");
      long completed = queue.takeLongestTurnUs();
      assertTrue(completed >= observed, "The later completion also belongs to the following interval");
      assertEquals(0, queue.takeLongestTurnUs());
      assertEquals(completed, queue.metrics().longestTurnUs());
    } finally { release.countDown(); queue.shutdown(); assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS)); }
  }

  @Test void aDelayedObserverCannotRepublishACompletedTurn() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var queue = new StoreQueue(ignored -> {}, false);
    try {
      queue.capture("late-observer", () -> {
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      queue.observe(() -> {
        release.countDown(); queue.shutdown();
        try { assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        assertTrue(queue.takeLongestTurnUs() > 0);
        assertEquals(0, queue.takeLongestTurnUs());
      });
      assertEquals(0, queue.takeLongestTurnUs(), "A retired turn cannot reappear after its completed interval was consumed");
      assertTrue(queue.metrics().longestTurnUs() > 0);
    } finally { release.countDown(); queue.shutdown(); assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS)); }
  }

}
