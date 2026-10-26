/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Frees what an admitted request holds the moment its reply's last byte has been written: after the write that
 * completes a fixed-length body, the close that ends a chunked one, or the headers of a reply with no body. That is
 * before the exchange closes and the worker's thread returns, with no network I/O in between, so a client that asks
 * again as soon as the reply arrives finds the slot free. The worker closes the reply when it is done, which frees
 * whatever a failed or unfinished reply still holds.
 *
 * <p>Other holders of the request, such as inventory reply reservations, find the reply through {@link #current()} on
 * the worker's thread: a JDK 17 exchange keeps its attributes on the shared context, not on the request.
 */
final class SettledReply implements AutoCloseable {
  private static final ThreadLocal<SettledReply> CURRENT = new ThreadLocal<>();
  private static final long UNKNOWN = Long.MIN_VALUE;
  private final HttpExchange exchange;
  private final ReplyCapacity capacity;
  private final SettledReply previous;
  private final HttpExchange view;
  private final List<Runnable> releases = new ArrayList<>();
  private long declared = UNKNOWN, written;
  private boolean finishing, settled;

  private SettledReply(HttpExchange exchange, ReplyCapacity capacity) {
    this.exchange = exchange; this.capacity = capacity; previous = CURRENT.get();
    exchange.setStreams(null, new Counting(exchange.getResponseBody()));
    view = new View();
  }

  /** Wraps an admitted request: its slot in {@code capacity} is freed when its reply completes. */
  static SettledReply open(HttpExchange exchange, ReplyCapacity capacity) {
    var reply = new SettledReply(exchange, capacity);
    CURRENT.set(reply);
    return reply;
  }

  /** The reply this thread's request is writing, or null outside an admitted request. */
  static SettledReply current() { return CURRENT.get(); }

  /** The exchange the request's handler writes to. */
  HttpExchange exchange() { return view; }

  /** Runs {@code release} when the reply completes, or now if it already has. */
  void onSettled(Runnable release) {
    synchronized (this) { if (!settled) { releases.add(release); return; } }
    release.run();
  }

  private synchronized void finishing() {
    if (!finishing && !settled) { finishing = true; capacity.finishing(); }
  }

  /** Frees everything the reply holds, once. */
  void settle() {
    List<Runnable> run; boolean wasFinishing;
    synchronized (this) {
      if (settled) return;
      settled = true; run = List.copyOf(releases); releases.clear(); wasFinishing = finishing;
    }
    try { for (var release : run) release.run(); }
    finally {
      capacity.release();
      if (wasFinishing) capacity.finished();
    }
  }

  @Override public void close() {
    try { settle(); }
    finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
  }

  private final class Counting extends FilterOutputStream {
    Counting(OutputStream out) { super(out); }
    @Override public void write(int value) throws IOException {
      boolean last = completes(1);
      out.write(value); written(1, last);
    }
    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
      java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
      boolean last = completes(length);
      out.write(bytes, offset, length); written(length, last);
    }
    @Override public void close() throws IOException {
      finishing();
      try { out.close(); } finally { settle(); }
    }
    private boolean completes(long length) {
      boolean last = declared > 0 && length > 0 && written + length >= declared;
      if (last) finishing();
      return last;
    }
    private void written(long length, boolean last) {
      written += length;
      if (last) settle();
    }
  }

  /** Delegates every call to the exchange; only a reply's headers are watched. */
  private final class View extends HttpExchange {
    @Override public Headers getRequestHeaders() { return exchange.getRequestHeaders(); }
    @Override public Headers getResponseHeaders() { return exchange.getResponseHeaders(); }
    @Override public URI getRequestURI() { return exchange.getRequestURI(); }
    @Override public String getRequestMethod() { return exchange.getRequestMethod(); }
    @Override public HttpContext getHttpContext() { return exchange.getHttpContext(); }
    @Override public void close() { exchange.close(); }
    @Override public InputStream getRequestBody() { return exchange.getRequestBody(); }
    @Override public OutputStream getResponseBody() { return exchange.getResponseBody(); }
    @Override public void sendResponseHeaders(int code, long length) throws IOException {
      // 0 announces a chunked body and -1 none: a reply with no body is complete once its headers are written.
      declared = length;
      if (length < 0) finishing();
      exchange.sendResponseHeaders(code, length);
      if (length < 0) settle();
    }
    @Override public InetSocketAddress getRemoteAddress() { return exchange.getRemoteAddress(); }
    @Override public int getResponseCode() { return exchange.getResponseCode(); }
    @Override public InetSocketAddress getLocalAddress() { return exchange.getLocalAddress(); }
    @Override public String getProtocol() { return exchange.getProtocol(); }
    @Override public Object getAttribute(String name) { return exchange.getAttribute(name); }
    @Override public void setAttribute(String name, Object value) { exchange.setAttribute(name, value); }
    @Override public void setStreams(InputStream input, OutputStream output) { exchange.setStreams(input, output); }
    @Override public HttpPrincipal getPrincipal() { return exchange.getPrincipal(); }
  }
}
