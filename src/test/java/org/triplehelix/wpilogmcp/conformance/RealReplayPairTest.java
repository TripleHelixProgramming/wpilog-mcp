/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Pair selection uses identity, complete files and clocks, never a successful correlation. */
class RealReplayPairTest {
  @Test void realBootsStaySeparate() throws Exception { run(false); }

  static void run(boolean nativeServer) throws Exception {
    String property = System.getProperty("conformance.logdir");
    Assumptions.assumeTrue(property != null && !property.isBlank(), "Two-log replay skipped; set -PconformanceLogDir=/path/to/logs");
    var root = Path.of(property).toRealPath();
    var pair = ConformanceSample.configured(root, nativeServer ? "ntcore-pair" : "gateway-pair").pair();
    Assumptions.assumeTrue(!pair.isEmpty(), "Two-log placement skipped: no complete calendar-bearing pair of one robot with a reset clock");
    var scratch = Files.createTempDirectory(Files.createDirectories(Path.of("build/replay-pairs")), "run-");
    var report = Files.createDirectories(Path.of("build/reports/replay"))
        .resolve((nativeServer ? "ntcore" : "gateway") + "-pair-" + root.getFileName() + ".json");
    long started = System.nanoTime();
    try (var first = new ReplaySource(pair.get(0)); var second = new ReplaySource(pair.get(1))) {
      var results = ReplayClockResetTest.pair(first, second, scratch, nativeServer);
      Files.writeString(report, new com.google.gson.Gson().toJson(java.util.Map.of("logs", results,
          "wall_time_sec", (System.nanoTime() - started) / 1e9)));
    } catch (Exception | AssertionError failure) { throw new AssertionError(pair.get(0) + "\n" + pair.get(1)); }
  }

}
