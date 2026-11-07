/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Read a bounded JSON body and let its endpoint own the size refusal. Leaving excess bytes in
 * flight can reset the connection and erase that refusal, so discard at most another 4 MiB
 * first, within ten seconds (or the endpoint's earlier deadline). At that bound, or on a stall,
 * disconnect before sending headers so response close cannot start the JDK's own drain. Such senders
 * may lose the reply. This does not change binary upload limits or allocate a surplus buffer.
 */
final class RequestBodies {
  private static final int MAX_DISCARD_BYTES = 4 << 20;
  private static final Duration DISCARD_TIMEOUT = Duration.ofSeconds(10);
  private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
  private RequestBodies() {}

  /** Call before sending response headers, while exchange.close can close the socket directly. */
  static byte[] read(HttpExchange exchange, int limit) throws IOException {
    try { return read(exchange.getRequestBody(), limit); }
    catch (IOException failure) { exchange.close(); throw failure; }
  }
  static byte[] read(InputStream input, int limit) throws IOException {
    return read(input, limit, DISCARD_TIMEOUT);
  }
  static byte[] read(InputStream input, int limit, Duration timeout) throws IOException {
    byte[] prefix = input.readNBytes(limit + 1);
    if (prefix.length <= limit) return prefix;
    try (var deadline = new Deadline(timeout)) {
      byte[] discard = new byte[8192];
      int remaining = MAX_DISCARD_BYTES;
      while (remaining > 0) {
        int count = input.read(discard, 0, Math.min(discard.length, remaining));
        if (count < 0) break;
        remaining -= count;
      }
      if (deadline.expired()) throw new InterruptedIOException("Request refusal drain deadline exceeded");
      if (remaining == 0) throw new IOException("Request refusal discard limit reached");
    }
    return prefix;
  }
  private static ScheduledThreadPoolExecutor deadlines() {
    var executor = new ScheduledThreadPoolExecutor(1, runnable -> {
      var thread = new Thread(runnable, "http-refusal-deadline"); thread.setDaemon(true); return thread;
    });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }
  private static final class Deadline implements AutoCloseable {
    private final Thread reader = Thread.currentThread();
    private final ScheduledFuture<?> task;
    private boolean finished, expired;
    Deadline(Duration timeout) { task = DEADLINES.schedule(this::expire, timeout.toNanos(), TimeUnit.NANOSECONDS); }
    private synchronized void expire() {
      if (finished) return;
      expired = true;
      // Closing an HttpExchange here can itself block on the unread body. Interrupt its worker
      // instead; the JDK socket read closes its channel. Never do I/O on the deadline thread.
      reader.interrupt();
    }
    synchronized boolean expired() { return expired; }
    @Override public synchronized void close() {
      finished = true; task.cancel(false);
      if (expired) Thread.interrupted(); // Do not leak our deadline into the next pooled request.
    }
  }
}
