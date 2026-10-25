/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Constant-space public health facts, separate from private recording identity and error text.
 * The listener publishes changes; HTTP reads them without joining that listener or a store.
 * Last-write time uses completion on the local monotonic clock, not a buffered event's receipt.
 */
public final class CaptureProgress {
  public enum State { WAITING_ADMISSION, RECORDING, STOPPED }
  public enum Reason {
    NOT_STARTED, WAITING_FOR_STORE, WAITING_FOR_CLOCK, WAITING_FOR_TOPIC, DISCONNECTED, ADMISSION_FAILED,
    WRITE_FAILED, STARTUP_FAILED, LOW_DISK, CLOSED, SHUTDOWN
  }
  /** The cause is a fixed code, here only why startup waits for its store; never error text. */
  private record Status(State state, Reason reason, String cause) {}
  public record Probe(String state, String reason, String cause, Double lastWriteAgeMs,
      long longestListenerLoopStallUs, long longestStoreQueueTurnUs) {
    public com.google.gson.JsonObject json() {
      var result = new com.google.gson.JsonObject();
      result.addProperty("state", state);
      result.addProperty("reason", reason);
      result.addProperty("cause", cause);
      result.addProperty("last_write_age_ms", lastWriteAgeMs);
      result.addProperty("longest_listener_loop_stall_us", longestListenerLoopStallUs);
      result.addProperty("longest_store_queue_turn_us", longestStoreQueueTurnUs);
      return result;
    }
  }
  private final LongSupplier clockUs;
  private final java.util.concurrent.atomic.AtomicReference<Status> status =
      new java.util.concurrent.atomic.AtomicReference<>(new Status(State.STOPPED, Reason.NOT_STARTED, null));
  private volatile long lastWriteUs = Long.MIN_VALUE;

  CaptureProgress(LongSupplier clockUs) { this.clockUs = java.util.Objects.requireNonNull(clockUs); }

  void waiting() { status.set(new Status(State.WAITING_ADMISSION, null, null)); }
  void recording() { status.set(new Status(State.RECORDING, null, null)); }
  void stopped(Reason reason) { status.set(new Status(State.STOPPED, java.util.Objects.requireNonNull(reason), null)); }
  void written() { lastWriteUs = clockUs.getAsLong(); }
  void writeFailed() {
    if (status.get().reason() != Reason.ADMISSION_FAILED) stopped(Reason.WRITE_FAILED);
  }
  /** Startup's wait replaces only its own state or none: a stop, or the writer, has the last word. */
  void waitingForStore(String cause) {
    status.updateAndGet(current -> current.reason() == Reason.NOT_STARTED || current.reason() == Reason.WAITING_FOR_STORE
        ? new Status(State.STOPPED, Reason.WAITING_FOR_STORE, java.util.Objects.requireNonNull(cause)) : current);
  }
  /** The store settled: capture is about to start, as it is when startup never waited. */
  void storeSettled() {
    status.updateAndGet(current -> current.reason() == Reason.WAITING_FOR_STORE
        ? new Status(State.STOPPED, Reason.NOT_STARTED, null) : current);
  }

  /** Maxima are supplied by independent atomic interval counters, never by a queued callback. */
  Probe probe(long loopStallUs, long storeTurnUs) {
    var value = status.get();
    long write = lastWriteUs;
    return new Probe(value.state().name().toLowerCase(Locale.ROOT),
        value.reason() == null ? null : value.reason().name().toLowerCase(Locale.ROOT), value.cause(),
        write == Long.MIN_VALUE ? null : Math.max(0, clockUs.getAsLong() - write) / 1000.0,
        Math.max(0, loopStallUs), Math.max(0, storeTurnUs));
  }
}
