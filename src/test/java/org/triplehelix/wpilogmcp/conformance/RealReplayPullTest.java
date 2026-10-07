/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The same deterministic logger samples use the gateway as well as native publishers. */
class RealReplayPullTest {
  @Test void gatewaySamplesKeepMeasuredOffsetsAndPlacementRefusals() throws Exception {
    String property = System.getProperty("conformance.logdir");
    Assumptions.assumeTrue(property != null && !property.isBlank(),
        "Gateway pull replay skipped; set -PconformanceLogDir=/path/to/logs");
    var root = Path.of(property).toRealPath();
    var report = Files.createDirectories(Path.of("build/reports/replay")).resolve("gateway-pull-" + root.getFileName() + ".jsonl");
    var scratch = Files.createDirectories(Path.of("build/replay-pull"));
    try (var out = Files.newBufferedWriter(report)) {
      for (var path : RealNtcoreReplayTest.samples(root)) for (long shift : new long[] {0, 40_000, 120_000, 200_000, -120_000, 240_000, 260_000, 6_000_000}) {
        long started = System.nanoTime(); Map<String, Object> result;
        try (var source = new ReplaySource(path)) {
          var run = Files.createTempDirectory(scratch, "run-");
          if (source.calendar().isPresent()) {
            var parts = ReplayPullTest.gateway(source, run, shift);
            result = new LinkedHashMap<>((Map<String, Object>) parts.get("fidelity")); result.put("pull", parts.get("pull"));
          } else {
            result = ReplayAudit.gateway(source, run, shift, Clock.systemUTC());
            result.put("pull_skipped", "No recorded calendar clock or dated DataLogManager filename");
          }
        } catch (Exception | AssertionError failure) {
          out.write(new Gson().toJson(Map.of("path", path.toString(), "shift_us", shift, "failure", failure.getClass().getSimpleName())));
          out.newLine(); out.flush(); throw new AssertionError(path.toString());
        }
        result.put("wall_time_sec", (System.nanoTime() - started) / 1e9);
        out.write(new Gson().toJson(result)); out.newLine(); out.flush();
        RealNtcoreReplayTest.checkResult(path, shift, result);
      }
    }
  }
}
