/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class KeepaliveTest {
  @Test void aPongBeforeThePingCannotAnswerItEvenInTheSameClockMicrosecond() {
    var heartbeat = new Keepalive();
    assertFalse(heartbeat.expired(5_000_000), "No ping was sent");
    heartbeat.received(5_000_000); heartbeat.sent(5_000_000);
    assertFalse(heartbeat.expired(5_999_999)); assertTrue(heartbeat.expired(6_000_000));
    heartbeat.received(6_000_000); assertFalse(heartbeat.expired(7_500_000));
  }
}
