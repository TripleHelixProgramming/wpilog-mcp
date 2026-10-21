/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.store.DiskSpaceBudgets;

/**
 * Space queries can hang in a filesystem provider. A dedicated daemon publishes their results
 * without borrowing the NT4 listener or the store queue, and publication retains at most one
 * pending sample. Its reserve belongs to the configured capture, including waiting and pauses.
 */
final class CaptureDiskMonitor {
  private final ClientScheduler loop;
  private final CaptureWriter writer;
  private final DiskSpaceBudgets.StoreBudget budget;
  private final DiskSpaceBudgets.Policy policy;
  private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(task -> {
    var thread = new Thread(task, "capture-disk-space"); thread.setDaemon(true); return thread;
  });
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final AtomicBoolean queued = new AtomicBoolean();
  private final AtomicReference<DiskSpaceBudgets.Snapshot> latest = new AtomicReference<>();
  private final CompletableFuture<Void> initial = new CompletableFuture<>();
  private boolean started;

  CaptureDiskMonitor(ClientScheduler loop, CaptureWriter writer, DiskSpaceBudgets.StoreBudget budget,
      DiskSpaceBudgets.Policy policy) throws IOException {
    this.loop = loop; this.writer = writer; this.budget = budget; this.policy = policy;
    budget.configureCapture(policy);
  }

  /** The first published snapshot, explicitly unknown before measurement, precedes the first NT4 admission. */
  CompletableFuture<Void> start() {
    if (!started) {
      started = true;
      // A hung filesystem must not hold NT4 startup. An initially unknown publication is
      // explicit and leaves capture state unchanged, just like a later failed measurement.
      latest.set(budget.snapshot());
      if (queued.compareAndSet(false, true)) loop.execute(this::publish);
      sampler.scheduleWithFixedDelay(this::sample, 0, 250, TimeUnit.MILLISECONDS);
    }
    return initial;
  }

  private void sample() {
    if (stopped.get()) return;
    DiskSpaceBudgets.Snapshot snapshot;
    try { snapshot = budget.refresh(); }
    catch (IOException e) { snapshot = budget.snapshot(); }
    latest.set(snapshot);
    if (queued.compareAndSet(false, true)) {
      try { loop.execute(this::publish); }
      catch (java.util.concurrent.RejectedExecutionException e) {
        queued.set(false); initial.completeExceptionally(e);
      }
    }
  }

  private void publish() {
    var snapshot = latest.getAndSet(null);
    if (snapshot != null && !stopped.get()) writer.diskSpace(snapshot, policy);
    initial.complete(null);
    queued.set(false);
    if (latest.get() != null && queued.compareAndSet(false, true)) loop.execute(this::publish);
  }

  /** Stop admission of probes, but never pretend a timed-out writer has finished using its disk. */
  void stopSampling() {
    stopped.set(true); sampler.shutdown();
  }

}
