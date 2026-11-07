/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PullGateTest {
  @Test void enabledBitAndContinuousSettleRequireTheCurrentNt4Connection() {
    var clock = new AtomicLong(); var gate = new PullGate(clock::get, 5_000_000);
    gate.control(0L); clock.set(10_000_000); assertFalse(gate.open());
    gate.connected("127.0.0.1"); assertEquals("127.0.0.1", gate.address()); assertFalse(gate.open());
    gate.control(0b111110L); clock.addAndGet(4_999_999); assertFalse(gate.open());
    gate.control(0b10L); clock.incrementAndGet(); assertTrue(gate.open());
    gate.control(0b11L); assertFalse(gate.open()); gate.control(0L); assertFalse(gate.open());
    clock.addAndGet(5_000_000); assertTrue(gate.open());
    long connection = gate.connection(); gate.disconnected(); assertFalse(gate.open()); assertNull(gate.address());
    gate.connected("127.0.0.2"); assertTrue(gate.connection() > connection); assertFalse(gate.open());
    gate.control(0L); clock.addAndGet(5_000_000); assertTrue(gate.open()); gate.unknown(); assertFalse(gate.open());
  }
  @Test void malformedWordsNeverAuthorizePulling() {
    var gate = new PullGate(() -> 0, 0); gate.connected("127.0.0.1");
    for (Object value : new Object[] {null, true, "0", 0.5, Double.NaN, Double.POSITIVE_INFINITY, -2L, 1L << 32}) {
      gate.control(0L); assertTrue(gate.open()); gate.control(value); assertFalse(gate.open(), String.valueOf(value));
    }
  }
}
