/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Pair selection uses identity, complete files and clocks, never a successful correlation. */
class RealReplayPairTest {
  @Test void realBootsStaySeparate() throws Exception { run(false); }

  static void run(boolean nativeServer) throws Exception {
    String property = System.getProperty("conformance.logdir");
    Assumptions.assumeTrue(property != null && !property.isBlank(), "Two-log replay skipped; set -PconformanceLogDir=/path/to/logs");
    var root = Path.of(property).toRealPath();
    var pair = (nativeServer
        ? ConformanceSample.configured(root, "ntcore-pair", System.getProperty("conformance.native", "sample"), false)
        : ConformanceSample.configured(root, "gateway-pair")).pair();
    Assumptions.assumeTrue(!pair.isEmpty(), "Two-log placement skipped: no complete calendar-bearing pair of one robot with a reset clock");
    var report = Files.createDirectories(Path.of("build/reports/replay"))
        .resolve((nativeServer ? "ntcore" : "gateway") + "-pair-" + root.getFileName() + ".json");
    long started = System.nanoTime();
    // Native pairs run under shopHarness, which empties replay-native; test empties replay-pairs.
    var results = replay(pair.get(0), pair.get(1), Path.of(nativeServer ? "build/replay-native" : "build/replay-pairs"), nativeServer);
    Files.writeString(report, new com.google.gson.Gson().toJson(Map.of("logs", results,
        "wall_time_sec", (System.nanoTime() - started) / 1e9)));
  }

  /** Two boots in a new run folder in {@code scratch}, deleted when the pair passes and kept when it fails. */
  static List<Map<String, Object>> replay(Path first, Path second, Path scratch, boolean nativeServer) throws Exception {
    var run = ReplayScratch.create(scratch, "pair-");
    List<Map<String, Object>> results;
    try (var a = new ReplaySource(first); var b = new ReplaySource(second)) {
      results = ReplayClockResetTest.pair(a, b, run, nativeServer);
    } catch (Exception | AssertionError failure) { throw new AssertionError(first + "\n" + second); }
    ReplayScratch.delete(run);
    return results;
  }
}
