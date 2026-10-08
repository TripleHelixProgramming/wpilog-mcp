/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Additional clock placements use Java NT4; native publishers prove transport fidelity once. */
@Tag("shop-harness")
class ReplayMatrixTest {
  @TempDir Path directory;
  @ParameterizedTest @EnumSource(ReplaySource.Kind.class)
  void fullPlacementMatrix(ReplaySource.Kind kind) throws Exception {
    try (var source = new ReplaySource(ReplayPullTest.fixture(directory, kind))) {
      for (long shift : ConformanceSample.SHIFTS) {
        var report = ReplayPullTest.gateway(source, directory.resolve("shift-" + shift), shift);
        var fidelity = new java.util.LinkedHashMap<String, Object>((Map<String, Object>) report.get("fidelity"));
        fidelity.put("pull", report.get("pull"));
        RealNtcoreReplayTest.checkResult(source.path, shift, fidelity);
        var pull = (Map<?, ?>) report.get("pull");
        if (Math.abs(shift) <= 250_000) org.junit.jupiter.api.Assertions.assertEquals(shift,
            ((Number) pull.get("offset_us")).longValue(), 5_000, source.path.toString());
      }
    }
  }
}
