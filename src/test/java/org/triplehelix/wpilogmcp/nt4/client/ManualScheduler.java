/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The test thread is the client event loop. Network callbacks enqueue work; time advances by hand. */
public final class ManualScheduler implements ClientScheduler {
  private record Timer(long due, long order, Runnable action) {}
  private final BlockingQueue<Runnable> ready = new LinkedBlockingQueue<>();
  private final PriorityQueue<Timer> timers = new PriorityQueue<>(Comparator.comparingLong(Timer::due).thenComparingLong(Timer::order));
  final List<Long> delays = new ArrayList<>();
  private volatile long now;
  private long order;
  private long callbacks, beforeAdvance;
  @Override public long nowUs() { return now; }
  @Override public void execute(Runnable action) { ready.add(action); }
  @Override public void schedule(Runnable action, long delayUs) {
    delays.add(delayUs); timers.add(new Timer(now + delayUs, order++, action));
  }
  @Override public void close() { timers.clear(); }

  public void advance(long deltaUs) {
    beforeAdvance = callbacks;
    elapse(deltaUs);
    while (!timers.isEmpty() && timers.peek().due() <= now) timers.remove().action().run();
    drain();
  }
  /** The loop is occupied by a listener; network callbacks can still observe elapsed time. */
  public void elapse(long deltaUs) { now += deltaUs; }
  public void drain() { Runnable next; while ((next = ready.poll()) != null) { next.run(); callbacks++; } }
  /** A fast reply may already have run in advance's drain; never demand a second reply. */
  public void receive() throws InterruptedException {
    if (callbacks > beforeAdvance) return;
    var action = ready.poll(30, TimeUnit.SECONDS);
    assertTrue(action != null, "Client callback missing"); action.run(); callbacks++; drain();
  }
  public void until(BooleanSupplier condition) throws InterruptedException {
    until(condition, java.time.Duration.ofSeconds(30));
  }
  /** A wall-clock guard for network delivery and callback I/O; it never advances the NT4 clock. */
  public void until(BooleanSupplier condition, java.time.Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      long left = deadline - System.nanoTime();
      assertTrue(left > 0, "Client event deadline exceeded");
      var action = ready.poll(left, TimeUnit.NANOSECONDS);
      assertTrue(action != null, "Client callback missing"); action.run(); callbacks++;
    }
  }
}
