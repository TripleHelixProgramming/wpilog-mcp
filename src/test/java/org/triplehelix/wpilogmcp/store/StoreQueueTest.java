/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** A callback stuck on the store owner cannot be responsible for noticing its own stall. */
class StoreQueueTest {
  @Test void outsideObserverReportsTheBlockedTurnOnceWithoutInterruptingIt() throws Exception {
    var notices = new CopyOnWriteArrayList<StoreQueue.OverBudget>();
    var notice = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var daemon = new AtomicBoolean();
    var queue = new StoreQueue(value -> { notices.add(value); notice.countDown(); });
    try {
      queue.importTurn("synthetic-inspection", () -> {
        daemon.set(Thread.currentThread().isDaemon());
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException failure) { interrupted.set(true); }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(notice.await(3, TimeUnit.SECONDS),
          "The outside observer must report the over-budget turn before the blocked owner returns");
      assertEquals(1, queue.metrics().overBudgetTurns());
      assertTrue(queue.metrics().longestTurnUs() > 1_000_000,
          "The active turn has exceeded the independently specified one-second budget");
      assertEquals(List.of("synthetic-inspection"), notices.stream().map(StoreQueue.OverBudget::kind).toList());
      assertTrue(notices.get(0).durationUs() > 1_000_000);
      Thread.sleep(250);
      assertEquals(1, notices.size(), "Repeated outside ticks log the same turn only once");
      assertEquals(1, queue.metrics().overBudgetTurns());
      assertFalse(interrupted.get(), "The turn budget must never interrupt store work");
      assertTrue(daemon.get());
    } finally {
      release.countDown();
      queue.shutdown();
      assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
    }
    assertEquals(1, queue.metrics().overBudgetTurns(), "Completion must not count an already reported turn twice");
    assertEquals(1, notices.size(), "Completion must not log an already reported turn twice");
  }
}
