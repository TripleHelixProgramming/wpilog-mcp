/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;

/** Captures the actual scheduled callback, so discovery/admission wiring is still exercised. */
public final class StorePollerProbe implements AutoCloseable {
  private final StoreRegistry registry;
  private final java.util.function.Supplier<ScheduledExecutorService> previous;
  private final java.util.function.LongSupplier clock;
  private long now;
  private Runnable tick;
  public StorePollerProbe(StoreRegistry registry) {
    this.registry = registry; previous = registry.watcherFactory; clock = registry.watchClock;
    registry.watchClock = () -> now;
    registry.watcherFactory = () -> new ScheduledThreadPoolExecutor(1, task -> {
      var thread = new Thread(task, "test-store-poller"); thread.setDaemon(true); return thread;
    }) {
      @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay, long period, TimeUnit unit) {
        assertEquals(0, delay); assertEquals(3, period); assertEquals(TimeUnit.SECONDS, unit);
        tick = task;
        // A completed handle; this probe, not an executor timer, drives the recurring task.
        return super.schedule(() -> {}, 0, TimeUnit.NANOSECONDS);
      }
    };
  }
  public void advance() {
    assertNotNull(tick, "The registry must schedule its watcher"); tick.run(); now += TimeUnit.SECONDS.toNanos(3);
  }
  @Override public void close() { registry.watcherFactory = previous; registry.watchClock = clock; }
}
