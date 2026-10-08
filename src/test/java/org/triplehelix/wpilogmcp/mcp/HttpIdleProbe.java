/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

/** Drives the production idle decision without waiting out its timer. */
public final class HttpIdleProbe {
  private final HttpTransport transport;
  private final java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
  public HttpIdleProbe(HttpTransport transport) { this.transport = transport; transport.idleClock(nanos::get); }
  public void advance(java.time.Duration time) { nanos.addAndGet(time.toNanos()); transport.exitIfIdle(); }
}
