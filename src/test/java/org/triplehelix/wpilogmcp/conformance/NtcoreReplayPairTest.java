/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two real ntcore processes share one reconnecting capture client and one store. */
@Tag("shop-harness")
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "conformance.native", matches = "sample|full")
class NtcoreReplayPairTest {
  @Test void rebootSeparatesSessionsAndPullMatches(@TempDir Path directory) throws Exception {
    try (var first = new ReplaySource(ReplayPullTest.fixture(directory.resolve("first"), ReplaySource.Kind.ADVANTAGEKIT, 0));
         var second = new ReplaySource(ReplayPullTest.fixture(directory.resolve("second"), ReplaySource.Kind.ADVANTAGEKIT, 1))) {
      ReplayClockResetTest.pair(first, second, directory, true);
    }
  }

  // Java covers sampled real boot pairs; native already proves reset/reconnect on a generated pair.
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "conformance.native", matches = "full")
  @Test void realBootsStaySeparate() throws Exception { RealReplayPairTest.run(true); }
}
