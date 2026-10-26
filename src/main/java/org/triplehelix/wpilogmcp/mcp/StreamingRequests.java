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
 * response from the timer instead can itself block flushing into that same full socket. A request
 * joins the transport's admissions before its hand-off and leaves when its worker has finished, so
 * a stop's drain never misses one waiting for a worker. The limits count requests, not threads:
 * a request's slot is free once its reply's last byte is written ({@link ReplyCapacity}), and each
 * pool may hold twice its limit in threads, so a request with a slot never waits for a finished
 * request's thread to return. */
final class StreamingRequests implements AutoCloseable {
  private final ReplyCapacity work;
  private final ReplyCapacity refusing = new ReplyCapacity(2);
  private final ThreadPoolExecutor workers;
  private final ThreadPoolExecutor refusals;
  private final java.util.concurrent.ScheduledExecutorService timer;
  private final java.util.Set<Transfer> active = ConcurrentHashMap.newKeySet();
  private final long timeoutNanos;
  private final Admissions admissions;
  private volatile boolean ending;

  StreamingRequests(int maximum, Duration timeout) { this(maximum, timeout, new Admissions()); }
  StreamingRequests(int maximum, Duration timeout, Admissions admissions) {
    this.admissions = admissions;
    timeoutNanos = timeout.toNanos();
    if (maximum < 1 || timeoutNanos <= 0) throw new IllegalArgumentException("Positive streaming limits required");
    work = new ReplyCapacity(maximum);
    workers = new ThreadPoolExecutor(0, 2 * maximum, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
      var thread = new Thread(r, "http-stream"); thread.setDaemon(true); return thread;
    });
    refusals = new ThreadPoolExecutor(0, 4, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
      var thread = new Thread(r, "http-refusal"); thread.setDaemon(true); return thread;
    });
    timer = Executors.newSingleThreadScheduledExecutor(r -> {
      var thread = new Thread(r, "http-stream-progress"); thread.setDaemon(true); return thread;
    });
    long tick = Math.max(1, Math.min(250, timeout.toMillis() / 4));
    timer.scheduleWithFixedDelay(() -> active.forEach(Transfer::expire), tick, tick, TimeUnit.MILLISECONDS);
  }
  int activeCount() { return active.size(); }
  /** Slots no admitted request holds: a slot is free once its reply's last byte is written, before its thread is. */
  int freeSlots() { return work.available(); }
  HttpHandler handle(HttpHandler handler) { return handle(handler, null); }
  HttpHandler handle(HttpHandler handler, HttpHandler refusal) {
    return exchange -> {
      var ticket = admissions.join(exchange);
      if (!work.tryAcquire()) {
        ticket.close();
        if (refusal != null) { refusal.handle(exchange); return; }
        refuse(exchange); return;
      }
      try { workers.execute(() -> { try (ticket) { run(exchange, handler, work); } }); }
      catch (RejectedExecutionException full) {
        work.release(); ticket.close();
        if (refusal != null) { refusal.handle(exchange); return; }
        refuse(exchange);
      }
    };
  }
  /** Final refusals have their own bounded IO ownership. With no slot left, abort before
   * response headers: closing a committed response can make the JDK drain an unread body. */
  void refuse(HttpExchange exchange) {
    refuse(exchange, StreamingRequests::busy);
  }
  void refuse(HttpExchange exchange, HttpHandler refusal) {
    var ticket = admissions.join(exchange);
    if (!refusing.tryAcquire()) { ticket.close(); exchange.close(); return; }
    try { refusals.execute(() -> { try (ticket) { run(exchange, refusal, refusing); } }); }
    catch (RejectedExecutionException full) { refusing.release(); ticket.close(); exchange.close(); }
  }
  /**
   * A stop past its grace ends each stream at its next network read or write: a byte stream can
   * be asked for again. A worker in file I/O is never interrupted, since that would close a file
   * channel other readers share; it ends at its next write.
   */
  void endTransfers() {
    ending = true;
    active.forEach(Transfer::endTransfer);
  }
  private static void busy(HttpExchange exchange) throws IOException {
    RequestBodies.read(exchange, 64 << 10);
    byte[] body = "{\"status\":\"error\",\"code\":\"BUSY\",\"message\":\"Streaming capacity is in use; retry after another transfer finishes\"}".getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Retry-After", "1");
    exchange.sendResponseHeaders(503, body.length);
    try (var out = exchange.getResponseBody()) { out.write(body); }
  }
  private void run(HttpExchange exchange, HttpHandler handler, ReplyCapacity capacity) {
    var transfer = new Transfer(Thread.currentThread()); active.add(transfer);
    SettledReply reply = null;
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
      reply = SettledReply.open(exchange, capacity);
      handler.handle(reply.exchange());
    } catch (IOException disconnected) {
      org.slf4j.LoggerFactory.getLogger(StreamingRequests.class).debug("HTTP transfer ended: {}", disconnected.toString());
    } finally {
      // close may flush, too: leave the watchdog active until it returns.
      try { transfer.begin(); exchange.close(); }
      catch (IOException expired) { /* Interrupted SocketChannel is already closed. */ }
      finally {
        // A reply that never completed, or a chunked one the exchange's close just ended, frees its slot here.
        if (reply != null) reply.close(); else capacity.release();
        transfer.finish(); active.remove(transfer); Thread.interrupted();
      }
    }
  }
  private final class Transfer {
    private final Thread worker;
    private long since;
    private boolean io, ended, expired;
    Transfer(Thread worker) { this.worker = worker; }
    synchronized void begin() throws IOException {
      if (expired) throw new IOException("HTTP transfer made no IO progress before its deadline");
      if (ending) { expired = true; throw new IOException("HTTP transfer ended: the server is stopping"); }
      since = System.nanoTime(); io = true;
    }
    synchronized void end() { io = false; }
    /** Called by a stop: interrupt only a worker blocked on the network, as a silent peer's deadline does. */
    synchronized void endTransfer() {
      if (!ended && io && !expired) { expired = true; worker.interrupt(); }
    }
    synchronized void finish() { ended = true; io = false; }
    synchronized void expire() {
      if (!ended && io && System.nanoTime() - since >= timeoutNanos) {
        expired = true; worker.interrupt();
      }
    }
  }
  @Override public void close() { workers.shutdownNow(); refusals.shutdownNow(); timer.shutdownNow(); }
}
