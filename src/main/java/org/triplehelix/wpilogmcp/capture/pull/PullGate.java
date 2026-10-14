/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.util.function.LongSupplier;

/** Unknown, enabled, or disconnected state is closed. Every connection must settle anew. */
public final class PullGate {
  /** The gate owns admission and its explanation, so a paused transfer cannot invent a cause. */
  public enum ClosedReason {
    DISCONNECTED("NT4 is disconnected"),
    ENABLED_OR_UNKNOWN("Robot is enabled or its enabled state is unknown"),
    SETTLING("Waiting for disabled-state settling");
    private final String description;
    ClosedReason(String description) { this.description = description; }
    public String description() { return description; }
  }
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
  public boolean open() { return closedReason() == null; }
  /** One state snapshot decides both admission and its reason; null means the gate is open. */
  public ClosedReason closedReason() {
    var current = state;
    if (current.address() == null) return ClosedReason.DISCONNECTED;
    if (!current.disabled()) return ClosedReason.ENABLED_OR_UNKNOWN;
    return clock.getAsLong() - current.disabledAtUs() >= settleUs ? null : ClosedReason.SETTLING;
  }
  public String address() { return state.address(); }
  public long connection() { return state.connection(); }
}
