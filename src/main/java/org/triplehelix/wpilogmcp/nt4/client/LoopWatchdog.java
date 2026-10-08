/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** An outside observer reports a stalled capture loop; a probe never accuses its network peer. */
public final class LoopWatchdog implements AutoCloseable {
  public static final long STALL_US = 1_000_000;
  private final ClientScheduler loop, observer;
  private final Consumer<String> report;
  private final AtomicBoolean pending = new AtomicBoolean(), closed = new AtomicBoolean();
  private long sent;
  private boolean stalled;
  public LoopWatchdog(ClientScheduler loop) {
    this(loop, ClientScheduler.daemon("capture-loop-watchdog"), message -> LoggerFactory.getLogger(LoopWatchdog.class).warn(message));
  }
  public LoopWatchdog(ClientScheduler loop, ClientScheduler observer, Consumer<String> report) {
    this.loop = loop; this.observer = observer; this.report = report;
  }
  public void start() { observer.execute(this::tick); }
  private void tick() {
    if (closed.get()) return;
    if (pending.get()) {
      if (!stalled && observer.nowUs() - sent >= STALL_US) {
        stalled = true; report.accept("Capture event loop stalled for at least one second; inspect local listener or disk work, not the NT4 peer");
      }
    } else {
      if (stalled) { stalled = false; report.accept("Capture event loop resumed"); }
      sent = observer.nowUs(); pending.set(true);
      loop.execute(() -> pending.set(false));
    }
    if (!closed.get()) observer.schedule(this::tick, 250_000);
  }
  @Override public void close() { closed.set(true); observer.close(); }
}
