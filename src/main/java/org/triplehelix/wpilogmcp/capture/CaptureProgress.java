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
    NOT_STARTED, WAITING_FOR_CLOCK, WAITING_FOR_TOPIC, DISCONNECTED, ADMISSION_FAILED,
    WRITE_FAILED, STARTUP_FAILED, LOW_DISK, CLOSED, SHUTDOWN
  }
  private record Status(State state, Reason reason) {}
  public record Probe(String state, String reason, Double lastWriteAgeMs,
      long longestListenerLoopStallUs, long longestStoreQueueTurnUs) {
    public com.google.gson.JsonObject json() {
      var result = new com.google.gson.JsonObject();
      result.addProperty("state", state);
      result.addProperty("reason", reason);
      result.addProperty("last_write_age_ms", lastWriteAgeMs);
      result.addProperty("longest_listener_loop_stall_us", longestListenerLoopStallUs);
      result.addProperty("longest_store_queue_turn_us", longestStoreQueueTurnUs);
      return result;
    }
  }
  private final LongSupplier clockUs;
  private volatile Status status = new Status(State.STOPPED, Reason.NOT_STARTED);
  private volatile long lastWriteUs = Long.MIN_VALUE;

  CaptureProgress(LongSupplier clockUs) { this.clockUs = java.util.Objects.requireNonNull(clockUs); }

  void waiting() { status = new Status(State.WAITING_ADMISSION, null); }
  void recording() { status = new Status(State.RECORDING, null); }
  void stopped(Reason reason) { status = new Status(State.STOPPED, java.util.Objects.requireNonNull(reason)); }
  void written() { lastWriteUs = clockUs.getAsLong(); }
  void writeFailed() {
    if (status.reason() != Reason.ADMISSION_FAILED) stopped(Reason.WRITE_FAILED);
  }

  /** Maxima are supplied by independent atomic interval counters, never by a queued callback. */
  Probe probe(long loopStallUs, long storeTurnUs) {
    var value = status;
    long write = lastWriteUs;
    return new Probe(value.state().name().toLowerCase(Locale.ROOT),
        value.reason() == null ? null : value.reason().name().toLowerCase(Locale.ROOT),
        write == Long.MIN_VALUE ? null : Math.max(0, clockUs.getAsLong() - write) / 1000.0,
        Math.max(0, loopStallUs), Math.max(0, storeTurnUs));
  }
}
