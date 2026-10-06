/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One ordered event loop and a monotonic clock; tests advance it without sleeping through retries. */
public interface ClientScheduler extends AutoCloseable {
  long nowUs();
  void execute(Runnable action);
  void schedule(Runnable action, long delayUs);
  @Override void close();

  static ClientScheduler daemon() {
    return new ClientScheduler() {
      private final long epoch = System.nanoTime();
      private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
        var thread = new Thread(r, "nt4-client");
        thread.setDaemon(true);
        return thread;
      });
      { executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false); }
      @Override public long nowUs() { return (System.nanoTime() - epoch) / 1000; }
      @Override public void execute(Runnable action) { executor.execute(action); }
      @Override public void schedule(Runnable action, long delayUs) {
        executor.schedule(action, delayUs, TimeUnit.MICROSECONDS);
      }
      @Override public void close() { executor.shutdown(); }
    };
  }
}
