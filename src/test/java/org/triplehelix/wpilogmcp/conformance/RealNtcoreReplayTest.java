/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Shared sample or full zero-shift coverage, without copying expected telemetry into code. */
@Tag("shop-harness")
class RealNtcoreReplayTest {
  @Test void sampleEveryLoggerKindThroughNtcoreAndPuller() throws Exception {
    String directory = System.getProperty("conformance.logdir");
    Assumptions.assumeTrue(directory != null && !directory.isBlank(), "Native real-log replay skipped; set -PconformanceLogDir=/path/to/logs");
    var root = Path.of(directory).toRealPath();
    var report = Files.createDirectories(Path.of("build/reports/replay")).resolve("ntcore-" + root.getFileName() + ".jsonl");
    try (var out = Files.newBufferedWriter(report)) {
      var selected = ConformanceSample.configured(root, "ntcore");
      for (var path : selected.paths()) for (long shift : selected.shifts(path)) {
        long started = System.nanoTime(); Map<String, Object> result;
        try (var source = new ReplaySource(path)) { result = NtcoreReplayTest.replay(source, shift, true); }
        catch (Exception | AssertionError failure) {
          out.write(new Gson().toJson(Map.of("path", path.toString(), "shift_us", shift, "failure", failure.getClass().getSimpleName()))); out.newLine(); out.flush();
          throw new AssertionError(path.toString());
        }
        result.put("wall_time_sec", (System.nanoTime() - started) / 1e9);
        out.write(new Gson().toJson(result)); out.newLine(); out.flush();
        checkResult(path, shift, result);
      }
    }
  }

  static List<Path> samples(Path root) throws Exception {
    return ConformanceSample.configured(root, "native-selection").paths();
  }

  static void checkResult(Path path, long shift, Map<String, Object> result) {
        assertTrue(((Map<?, ?>) result.get("mismatches")).isEmpty(), path.toString());
        if (result.containsKey("pull")) {
          var pull = (Map<?, ?>) result.get("pull");
          if (pull.get("revlogs") instanceof List<?> buses) for (var bus : buses) {
            var facts = (Map<?, ?>) bus;
            assertTrue(Boolean.TRUE.equals(facts.get("matches")) && Boolean.TRUE.equals(facts.get("copy_equal"))
                && Boolean.TRUE.equals(facts.get("http_visible")) && Boolean.TRUE.equals(facts.get("verification_matches")), path.toString());
          }
          if (Boolean.TRUE.equals(pull.get("rejected_incomplete_source"))) {
            assertEquals(1, ((Number) pull.get("retries")).intValue(), path.toString());
            System.out.println(path + ": capture verified; original incomplete file correctly refused by pull verification");
            return;
          }
          assertTrue(Boolean.TRUE.equals(pull.get("verified")) && Boolean.TRUE.equals(pull.get("copy_equal"))
              && Boolean.TRUE.equals(pull.get("retrievable")) && Boolean.TRUE.equals(pull.get("same_serial")), path.toString());
          assertTrue(Boolean.valueOf(Math.abs(shift) <= 250_000).equals(pull.get("placed")), path.toString());
          if (Math.abs(shift) <= 250_000) assertTrue(Math.abs(((Number) pull.get("offset_us")).longValue() - shift) <= 20_000, path.toString());
          else assertTrue(Boolean.TRUE.equals(pull.get("refusal_recorded")) && Boolean.TRUE.equals(pull.get("refusal_listed")), path.toString());
        } else System.out.println(path + ": pull placement skipped: no calendar evidence");
  }
}
