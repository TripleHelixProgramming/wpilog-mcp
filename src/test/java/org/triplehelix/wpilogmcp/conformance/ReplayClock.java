/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** Capture's calendar clock, not just NT's FPGA clock, follows the source's recorded epoch. */
final class ReplayClock extends Clock {
  private record Basis(Instant start, long first, AtomicLong latest) {}
  private volatile Basis basis;
  ReplayClock(Instant start, long first) { reset(start, first); }
  void reset(Instant start, long first) { basis = new Basis(start, first, new AtomicLong(first)); }
  void advance(long timeUs) { basis.latest.accumulateAndGet(timeUs, Math::max); }
  @Override public ZoneId getZone() { return ZoneOffset.UTC; }
  @Override public Clock withZone(ZoneId zone) { if (!zone.equals(getZone())) throw new IllegalArgumentException(); return this; }
  @Override public Instant instant() {
    var current = basis;
    return current.start.plusNanos(Math.multiplyExact(current.latest.get() - current.first, 1000));
  }
}
