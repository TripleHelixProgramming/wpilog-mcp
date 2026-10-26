/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A limit on concurrent HTTP requests that counts requests, not threads. A request's slot is free the moment its
 * reply's last byte has been written ({@link SettledReply}), before the exchange closes and its thread returns. A pool
 * of N threads counted that thread instead, so a client that sent its next request as soon as the reply arrived found
 * every thread still finishing and was refused although nothing was running.
 *
 * <p>Between the start of a reply's last write and the release of its slot there is an instant in which the client
 * may already hold the reply. A request that finds no slot while such a reply is finishing waits up to
 * {@link #FINISHING_WAIT_MS} for it; one that finds no slot otherwise is refused at once, so a refusal under real
 * overload never holds the dispatcher thread that asked.
 */
final class ReplyCapacity {
  static final long FINISHING_WAIT_MS = 50;
  private final Semaphore permits;
  private final AtomicInteger finishing = new AtomicInteger();
  private final AtomicInteger waited = new AtomicInteger();

  ReplyCapacity(int limit) {
    if (limit < 1) throw new IllegalArgumentException("A reply capacity needs at least one slot");
    permits = new Semaphore(limit);
  }

  /** Takes a slot, waiting briefly only while a finishing reply is about to free one. */
  boolean tryAcquire() {
    if (permits.tryAcquire()) return true;
    betweenLooks.run();
    // A reply that finished since the first look freed its slot before it stopped finishing: look once more.
    if (finishing.get() == 0) return permits.tryAcquire();
    waited.incrementAndGet();
    try { return permits.tryAcquire(FINISHING_WAIT_MS, TimeUnit.MILLISECONDS); }
    catch (InterruptedException stopping) { Thread.currentThread().interrupt(); return false; }
  }

  /** Runs when admission has found no slot, before it looks for a finishing reply; tests finish a reply there. */
  private volatile Runnable betweenLooks = () -> {};
  void betweenLooksProbe(Runnable probe) { betweenLooks = probe; }

  void release() { permits.release(); }
  /** A reply's last write has begun: its slot will be free within moments. */
  void finishing() { finishing.incrementAndGet(); }
  /** The finishing reply's slot is free; release first, so no waiter misses it. */
  void finished() { finishing.decrementAndGet(); }
  /** Runs on a worker right after its reply's last write, before the slot is free; tests hold the worker there. */
  private volatile Runnable lastWriteProbe = () -> {};
  void lastWriteProbe(Runnable probe) { lastWriteProbe = probe; }
  void afterLastWrite() { lastWriteProbe.run(); }
  int available() { return permits.availablePermits(); }
  boolean waiting() { return permits.hasQueuedThreads(); }
  /** How many admissions have waited for a finishing reply. */
  int waited() { return waited.get(); }
}
