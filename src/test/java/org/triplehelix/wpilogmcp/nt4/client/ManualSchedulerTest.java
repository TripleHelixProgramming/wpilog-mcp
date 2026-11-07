/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManualSchedulerTest {
  @Test void receiveAcceptsTheReplyDrainedWhileAdvancingTheClock() throws Exception {
    var loop = new ManualScheduler(); var replies = new AtomicInteger();
    loop.schedule(() -> loop.execute(replies::incrementAndGet), 200_000);
    loop.advance(200_000);
    assertEquals(1, replies.get());
    assertTimeoutPreemptively(Duration.ofSeconds(1), loop::receive,
        "The scheduled reply already ran; do not wait for an unrequested second callback");
  }
}
