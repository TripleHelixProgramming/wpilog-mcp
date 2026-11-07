/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4;

/**
 * A local loop stall is not evidence that the peer died: only an unanswered ping can expire.
 * Receipt is stamped by the network callback. Later pings must not postpone an outstanding
 * deadline. These short state transitions do no I/O, including when a send completes off-loop.
 */
public final class Keepalive {
  private long pingUs = Long.MIN_VALUE;

  public synchronized void sent(long nowUs) {
    if (pingUs == Long.MIN_VALUE) pingUs = nowUs;
  }
  public synchronized void received(long nowUs) {
    if (pingUs != Long.MIN_VALUE && nowUs >= pingUs) pingUs = Long.MIN_VALUE;
  }
  public synchronized boolean expired(long nowUs) {
    return pingUs != Long.MIN_VALUE && nowUs - pingUs >= 1_000_000;
  }
}
