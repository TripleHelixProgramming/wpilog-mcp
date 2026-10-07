/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Pair selection uses identity, complete files and clocks, never a successful correlation. */
class RealReplayPairTest {
  @Test void realBootsStaySeparate() throws Exception { run(false); }

  static void run(boolean nativeServer) throws Exception {
    String property = System.getProperty("conformance.logdir");
    Assumptions.assumeTrue(property != null && !property.isBlank(), "Two-log replay skipped; set -PconformanceLogDir=/path/to/logs");
    var root = Path.of(property).toRealPath(); var pair = select(root);
    Assumptions.assumeTrue(pair != null, "Two-log placement skipped: no complete calendar-bearing pair of one robot with a reset clock");
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

  private record First(Path path, long endUs) {}
  private static List<Path> select(Path root) throws Exception {
    var first = new HashMap<String, First>();
    try (var walk = Files.walk(root)) {
      for (var path : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".wpilog"))
          .sorted(java.util.Comparator.comparingLong((Path p) -> { try { return Files.size(p); } catch (Exception e) { throw new IllegalStateException(p.toString()); } })
              .thenComparing(Path::toString)).toList()) {
        try (var source = new ReplaySource(path)) {
          if (source.reader.stopped != null || source.calendar().isEmpty() || source.maxUs - source.minUs < 10_000_000) continue;
          String key = source.kind + ":" + source.serial(); var previous = first.get(key);
          if (previous != null && source.minUs + 5_000_000 < previous.endUs
              && !previous.path.getFileName().equals(path.getFileName())) return List.of(previous.path, path);
          first.putIfAbsent(key, new First(path, source.maxUs));
        } catch (IndependentLog.NotALog invalid) { /* Rejection belongs to the full sweep. */ }
      }
    }
    return null;
  }
}
