/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;

/**
 * One owner keeps store mutations ordered, but capture need not wait for admitted import turns.
 * The monitor protects queue state only; the owner runs all filesystem work outside it. An
 * independent observer notices an over-budget turn even when that turn never returns.
 */
final class StoreQueue extends AbstractExecutorService {
  static final long TURN_BUDGET_US = 1_000_000;
  record Metrics(long overBudgetTurns, long longestTurnUs) {}
  record OverBudget(String kind, long durationUs) {}
  private record Turn(String kind, Runnable action) {}
  private static final class Active {
    final Turn turn;
    final long started = System.nanoTime();
    volatile long ended;
    final AtomicBoolean reported = new AtomicBoolean();
    Active(Turn turn) { this.turn = turn; }
  }
  private final ArrayDeque<Turn> capture = new ArrayDeque<>(), imports = new ArrayDeque<>();
  private final AtomicLong overBudget = new AtomicLong(), longestUs = new AtomicLong();
  private record ProbeInterval(long maximumUs, Active active) {}
  private final java.util.concurrent.atomic.AtomicReference<ProbeInterval> probeInterval =
      new java.util.concurrent.atomic.AtomicReference<>(new ProbeInterval(0, null));
  private final java.util.concurrent.ScheduledExecutorService observer =
      Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "store-turn-watchdog");
        thread.setDaemon(true);
        return thread;
      });
  private final Thread owner;
  private final java.util.function.Consumer<OverBudget> report;
  private volatile Active active;
  private boolean shutdown, terminated;

  StoreQueue() {
    this(observation -> LoggerFactory.getLogger(StoreQueue.class).warn(
        "Store {} turn exceeded its budget: duration_us={}, budget_us={}",
        observation.kind(), observation.durationUs(), TURN_BUDGET_US));
  }

  StoreQueue(java.util.function.Consumer<OverBudget> report) { this(report, true); }
  /** The test mode lets a probe race completion deterministically, without a periodic observer. */
  StoreQueue(java.util.function.Consumer<OverBudget> report, boolean automaticObservation) {
    this.report = java.util.Objects.requireNonNull(report);
    owner = new Thread(this::drain, "store-owner");
    owner.setDaemon(true);
    owner.start();
    if (automaticObservation) observer.scheduleAtFixedRate(this::observe, 100, 100, TimeUnit.MILLISECONDS);
  }

  @Override public void execute(Runnable command) { execute("import", command, false); }
  void capture(String kind, Runnable command) { execute(kind, command, true); }
  void importTurn(String kind, Runnable command) { execute(kind, command, false); }
  private void execute(String kind, Runnable command, boolean priority) {
    java.util.Objects.requireNonNull(command);
    synchronized (this) {
      if (shutdown) throw new RejectedExecutionException("Store queue is closed");
      (priority ? capture : imports).addLast(new Turn(kind, command));
      notifyAll();
    }
  }

  private void drain() {
    try {
      for (;;) {
        Turn next;
        synchronized (this) {
          while (capture.isEmpty() && imports.isEmpty() && !shutdown) {
            try { wait(); }
            catch (InterruptedException interrupted) { /* Shutdown never interrupts a turn. */ }
          }
          if (capture.isEmpty() && imports.isEmpty() && shutdown) return;
          next = !capture.isEmpty() ? capture.removeFirst() : imports.removeFirst();
        }
        // A completed caller may restore its interrupt status. As with ExecutorService workers,
        // that cancellation must not make the next caller's FileChannel operations fail.
        Thread.interrupted();
        var running = new Active(next);
        probeInterval.updateAndGet(value -> new ProbeInterval(value.maximumUs(), running));
        active = running;
        try { next.action().run(); }
        catch (Throwable failure) { LoggerFactory.getLogger(StoreQueue.class).error("Store {} turn failed", next.kind(), failure); }
        finally {
          running.ended = System.nanoTime();
          record(running);
          active = null;
        }
      }
    } finally {
      observer.shutdown();
      try { afterLastTurn.run(); }
      finally { synchronized (this) { terminated = true; notifyAll(); } }
    }
  }
  private volatile Runnable afterLastTurn = () -> {};
  /** Runs on the owner thread after its last turn, before termination is reported: a store gives its folder back here. */
  void afterLastTurn(Runnable hook) { afterLastTurn = java.util.Objects.requireNonNull(hook); }

  private void observe() { observe(() -> {}); }
  /** The observer seam lets completion retire a sample before its interval publication. */
  void observe(Runnable beforeInterval) { var running = active; if (running != null) record(running, beforeInterval); }
  private void record(Active running) { record(running, () -> {}); }
  private void record(Active running, Runnable beforeInterval) {
    long observed = System.nanoTime();
    long ended = running.ended;
    // Read the clock before the frozen end, so a delayed observer cannot charge time after
    // completion to a turn it saw active before being descheduled.
    long elapsedUs = Math.max(0, ((ended == 0 ? observed : Math.min(ended, observed)) - running.started) / 1_000);
    longestUs.accumulateAndGet(elapsedUs, Math::max);
    beforeInterval.run();
    boolean completed = ended != 0 && observed >= ended;
    // A delayed observation cannot republish a retired turn into the next probe interval.
    probeInterval.updateAndGet(value -> value.active() != running ? value
        : new ProbeInterval(Math.max(value.maximumUs(), elapsedUs), completed ? null : running));
    if (elapsedUs > TURN_BUDGET_US && running.reported.compareAndSet(false, true)) {
      overBudget.incrementAndGet();
      report.accept(new OverBudget(running.turn.kind(), elapsedUs));
    }
  }
  Metrics metrics() { return new Metrics(overBudget.get(), longestUs.get()); }
  /** Health consumes only its interval counter. Inspecting an active turn never calls a logger. */
  long takeLongestTurnUs() { return takeLongestTurnUs(() -> {}); }
  /** The callback is outside atomic publication and exposes the completed-turn/probe race. */
  long takeLongestTurnUs(Runnable afterInterval) {
    var interval = probeInterval.getAndUpdate(value -> new ProbeInterval(0, value.active()));
    long now = System.nanoTime();
    afterInterval.run();
    var running = interval.active();
    if (running == null) return interval.maximumUs();
    long ended = running.ended;
    long age = Math.max(0, ((ended == 0 ? now : Math.min(ended, now)) - running.started) / 1_000);
    return Math.max(interval.maximumUs(), age);
  }
  boolean ownsCurrentThread() { return Thread.currentThread() == owner; }

  @Override public synchronized void shutdown() { shutdown = true; notifyAll(); }
  @Override public List<Runnable> shutdownNow() {
    var abandoned = new ArrayList<Runnable>();
    synchronized (this) {
      shutdown = true;
      capture.forEach(turn -> abandoned.add(turn.action()));
      imports.forEach(turn -> abandoned.add(turn.action()));
      capture.clear(); imports.clear();
      notifyAll();
    }
    return abandoned;
  }
  @Override public synchronized boolean isShutdown() { return shutdown; }
  @Override public synchronized boolean isTerminated() { return terminated; }
  @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    synchronized (this) {
      while (!terminated) {
        long left = deadline - System.nanoTime();
        if (left <= 0) return false;
        TimeUnit.NANOSECONDS.timedWait(this, left);
      }
      return true;
    }
  }
}
