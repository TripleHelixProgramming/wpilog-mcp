/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.sun.net.httpserver.HttpExchange;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Every request from its admission until its response is complete, and each hand-off of it to
 * another worker (rule R2: refuse, drain, release). Once a stop closes the gate, a new request is
 * refused, while an admitted one handed on still joins, so the drain sees work queued for a stream
 * worker. The drain once counted a streaming request only when a worker ran it, and admission
 * stayed open while it waited, so new MCP calls and data requests began during the stop and met
 * owners already closing.
 */
final class Admissions {
  /** One admitted request, or one hand-off of it. Closing it ends that part; closing twice is harmless. */
  final class Ticket implements AutoCloseable {
    private final String what;
    private final long since = System.nanoTime();
    private Ticket(String what) { this.what = what; }
    @Override public void close() {
      synchronized (Admissions.this) { if (active.remove(this)) Admissions.this.notifyAll(); }
    }
  }

  private final Set<Ticket> active = new HashSet<>();
  private boolean closed;

  /** A new request from the network, or null once the stop has begun: the caller refuses it. */
  synchronized Ticket admit(HttpExchange exchange) { return closed ? null : add(exchange); }
  /** An admitted request handed to another worker: counted even while the stop drains. */
  synchronized Ticket join(HttpExchange exchange) { return add(exchange); }
  synchronized void close() { closed = true; }

  /** Waits until nothing admitted remains or the deadline passes; names what still runs. */
  synchronized List<String> awaitIdle(long deadlineNanos) throws InterruptedException {
    long remaining;
    while (!active.isEmpty() && (remaining = deadlineNanos - System.nanoTime()) > 0)
      TimeUnit.NANOSECONDS.timedWait(this, remaining);
    var now = System.nanoTime();
    var names = new ArrayList<String>();
    for (var ticket : active) names.add(ticket.what + " (" + TimeUnit.NANOSECONDS.toSeconds(now - ticket.since) + " s)");
    names.sort(null);
    return names;
  }

  private Ticket add(HttpExchange exchange) {
    var ticket = new Ticket(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
    active.add(ticket);
    return ticket;
  }
}
