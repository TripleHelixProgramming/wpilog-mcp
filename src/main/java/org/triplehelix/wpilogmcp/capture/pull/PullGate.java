/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.util.function.LongSupplier;

/** Unknown, enabled, or disconnected state is closed. Every connection must settle anew. */
public final class PullGate {
  private record State(String address, long connection, boolean disabled, long disabledAtUs) {}
  private final LongSupplier clock;
  private final long settleUs;
  private volatile State state = new State(null, 0, false, 0);
  public PullGate(LongSupplier clock, long settleUs) { this.clock = clock; this.settleUs = settleUs; }
  /** NT4 listener methods call these on its one ordered thread. */
  public void connected(String address) { state = new State(address, state.connection() + 1, false, 0); }
  public void disconnected() { state = new State(null, state.connection() + 1, false, 0); }
  public void control(Object value) {
    var before = state;
    boolean disabled = value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() == n.longValue()
        && n.longValue() >= 0 && n.longValue() <= 0xffff_ffffL && (n.longValue() & 1) == 0;
    state = new State(before.address(), before.connection(), disabled,
        disabled && before.disabled() ? before.disabledAtUs() : clock.getAsLong());
  }
  public void unknown() { control(null); }
  public boolean open() {
    var current = state; long elapsed = clock.getAsLong() - current.disabledAtUs();
    return current.address() != null && current.disabled() && elapsed >= settleUs;
  }
  public String address() { return state.address(); }
  public long connection() { return state.connection(); }
}
