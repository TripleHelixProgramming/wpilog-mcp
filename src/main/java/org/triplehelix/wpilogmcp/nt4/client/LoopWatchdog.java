/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** An outside observer reports a stalled capture loop; a probe never accuses its network peer. */
public final class LoopWatchdog implements AutoCloseable {
  public static final long STALL_US = 1_000_000;
  private final ClientScheduler loop, observer;
  private final Consumer<String> report;
  private final AtomicBoolean pending = new AtomicBoolean(), closed = new AtomicBoolean();
  private volatile long sent;
  private final AtomicLong longestUs = new AtomicLong();
  private static final class Heartbeat {
    final long startedUs;
    final AtomicLong endedUs = new AtomicLong(Long.MIN_VALUE);
    Heartbeat(long startedUs) { this.startedUs = startedUs; }
  }
  private record ProbeInterval(long maximumUs, Heartbeat active) {}
  private final java.util.concurrent.atomic.AtomicReference<ProbeInterval> probeInterval =
      new java.util.concurrent.atomic.AtomicReference<>(new ProbeInterval(0, null));
  private volatile Heartbeat heartbeat;
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
      var current = heartbeat;
      record(current, false);
      if (!stalled && current.endedUs.get() == Long.MIN_VALUE && observer.nowUs() - sent >= STALL_US) {
        stalled = true; report.accept("Capture event loop stalled for at least one second; inspect local listener or disk work, not the NT4 peer");
      }
    } else {
      if (stalled) { stalled = false; report.accept("Capture event loop resumed"); }
      sent = observer.nowUs();
      var current = new Heartbeat(sent); heartbeat = current;
      probeInterval.updateAndGet(value -> new ProbeInterval(value.maximumUs(), current));
      pending.set(true);
      loop.execute(() -> {
        record(current, true);
        pending.set(false);
      });
    }
    if (!closed.get()) observer.schedule(this::tick, 250_000);
  }
  /** The observer seam lets acknowledgement retire a sample before publication. */
  void observe(Runnable beforeInterval) { record(heartbeat, false, beforeInterval); }
  private void record(Heartbeat current, boolean complete) { record(current, complete, () -> {}); }
  private void record(Heartbeat current, boolean complete, Runnable beforeInterval) {
    if (current == null) return;
    long observed = observer.nowUs();
    if (complete) current.endedUs.compareAndSet(Long.MIN_VALUE, observed);
    long ended = current.endedUs.get();
    long durationUs = Math.max(0, (ended == Long.MIN_VALUE ? observed : Math.min(observed, ended)) - current.startedUs);
    longestUs.accumulateAndGet(durationUs, Math::max);
    beforeInterval.run();
    boolean completed = ended != Long.MIN_VALUE && observed >= ended;
    // Completion and its maximum publish together; a late observer cannot resurrect it.
    probeInterval.updateAndGet(value -> value.active() != current ? value
        : new ProbeInterval(Math.max(value.maximumUs(), durationUs), completed ? null : current));
  }
  public long longestStallUs() { return longestUs.get(); }
  /** A public probe consumes a separate interval; cumulative metrics retain their maximum. */
  public long takeLongestStallUs() { return takeLongestStallUs(() -> {}); }
  /** The callback is outside atomic publication and exposes completion at the probe boundary. */
  long takeLongestStallUs(Runnable afterInterval) {
    var interval = probeInterval.getAndUpdate(value -> new ProbeInterval(0, value.active()));
    long observed = observer.nowUs();
    afterInterval.run();
    var current = interval.active();
    if (current == null) return interval.maximumUs();
    long ended = current.endedUs.get();
    long age = Math.max(0, (ended == Long.MIN_VALUE ? observed : Math.min(observed, ended)) - current.startedUs);
    return Math.max(interval.maximumUs(), age);
  }
  @Override public void close() {
    if (closed.compareAndSet(false, true)) { record(heartbeat, true); observer.close(); }
  }
}
