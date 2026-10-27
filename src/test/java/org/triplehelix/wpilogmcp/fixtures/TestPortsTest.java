/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** The ports tests name before anything binds them come from a slice no other fork or connection uses. */
class TestPortsTest {
  @Test void aPortIsFreeBelowEveryEphemeralRangeAndNotHandedOutTwice() throws Exception {
    var seen = new HashSet<Integer>();
    for (int i = 0; i < 50; i++) {
      int port = TestPorts.unused();
      // Linux's ephemeral range starts at 32768, macOS's and Windows' at 49152.
      assertTrue(port >= TestPorts.FIRST && port < TestPorts.FIRST + TestPorts.SLICES * TestPorts.SLICE, "port " + port);
      assertTrue(seen.add(port), "handed out twice: " + port);
      try (var bound = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
        assertEquals(port, bound.getLocalPort(), "free when handed out");
      }
    }
  }

  @Test void concurrentForksHaveSlicesOfTheirOwn() {
    // Gradle numbers forks as it starts them, so the forks running together have nearby numbers.
    var bases = new HashSet<Integer>();
    for (long worker = 37; worker < 37 + TestPorts.SLICES; worker++) assertTrue(bases.add(TestPorts.base(worker)), "worker " + worker);
    assertTrue(bases.stream().allMatch(base -> base + TestPorts.SLICE <= 30_000));
  }
}
