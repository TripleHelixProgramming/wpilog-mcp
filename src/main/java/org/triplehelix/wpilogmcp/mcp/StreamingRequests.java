/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Streaming IO never occupies dispatch workers needed by health and registration. Direct
 * handoff bounds admission, while an IO-progress deadline releases mappings held by silent peers.
 * Interrupting the owning worker closes HttpServer's blocked SocketChannel; closing a chunked
 * response from the timer instead can itself block flushing into that same full socket. */
final class StreamingRequests implements AutoCloseable {
  private final ThreadPoolExecutor workers;
  private final java.util.concurrent.ScheduledExecutorService timer;
  private final java.util.Set<Transfer> active = ConcurrentHashMap.newKeySet();
  private final long timeoutNanos;

  StreamingRequests(int maximum, Duration timeout) {
    timeoutNanos = timeout.toNanos();
    if (maximum < 1 || timeoutNanos <= 0) throw new IllegalArgumentException("Positive streaming limits required");
    workers = new ThreadPoolExecutor(0, maximum, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
      var thread = new Thread(r, "http-stream"); thread.setDaemon(true); return thread;
    });
    timer = Executors.newSingleThreadScheduledExecutor(r -> {
      var thread = new Thread(r, "http-stream-progress"); thread.setDaemon(true); return thread;
    });
    long tick = Math.max(1, Math.min(250, timeout.toMillis() / 4));
    timer.scheduleWithFixedDelay(() -> active.forEach(Transfer::expire), tick, tick, TimeUnit.MILLISECONDS);
  }
  int activeCount() { return active.size(); }
  HttpHandler handle(HttpHandler handler) {
    return exchange -> {
      try { workers.execute(() -> run(exchange, handler)); }
      catch (RejectedExecutionException full) {
        byte[] body = "{\"status\":\"error\",\"code\":\"BUSY\",\"message\":\"Streaming capacity is in use; retry after another transfer finishes\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Retry-After", "1");
        exchange.sendResponseHeaders(503, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
        finally { exchange.close(); }
      }
    };
  }
  private void run(HttpExchange exchange, HttpHandler handler) {
    var transfer = new Transfer(Thread.currentThread()); active.add(transfer);
    try {
      exchange.setStreams(new FilterInputStream(exchange.getRequestBody()) {
        @Override public int read() throws IOException {
          transfer.begin(); try { return in.read(); } finally { transfer.end(); }
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
          transfer.begin(); try { return in.read(bytes, offset, Math.min(length, 65536)); } finally { transfer.end(); }
        }
      }, new FilterOutputStream(exchange.getResponseBody()) {
        @Override public void write(int value) throws IOException {
          transfer.begin(); try { out.write(value); } finally { transfer.end(); }
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
          java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
          while (length > 0) {
            int n = Math.min(length, 65536);
            transfer.begin(); try { out.write(bytes, offset, n); } finally { transfer.end(); }
            offset += n; length -= n;
          }
        }
        @Override public void flush() throws IOException {
          transfer.begin(); try { out.flush(); } finally { transfer.end(); }
        }
        @Override public void close() throws IOException {
          transfer.begin(); try { out.close(); } finally { transfer.end(); }
        }
      });
      handler.handle(exchange);
    } catch (IOException disconnected) {
      org.slf4j.LoggerFactory.getLogger(StreamingRequests.class).debug("HTTP transfer ended: {}", disconnected.toString());
    } finally {
      // close may flush, too: leave the watchdog active until it returns.
      try { transfer.begin(); exchange.close(); }
      catch (IOException expired) { /* Interrupted SocketChannel is already closed. */ }
      finally { transfer.finish(); active.remove(transfer); Thread.interrupted(); }
    }
  }
  private final class Transfer {
    private final Thread worker;
    private long since;
    private boolean io, ended, expired;
    Transfer(Thread worker) { this.worker = worker; }
    synchronized void begin() throws IOException {
      if (expired) throw new IOException("HTTP transfer made no IO progress before its deadline");
      since = System.nanoTime(); io = true;
    }
    synchronized void end() { io = false; }
    synchronized void finish() { ended = true; io = false; }
    synchronized void expire() {
      if (!ended && io && System.nanoTime() - since >= timeoutNanos) {
        expired = true; worker.interrupt();
      }
    }
  }
  @Override public void close() { timer.shutdownNow(); workers.shutdownNow(); }
}
