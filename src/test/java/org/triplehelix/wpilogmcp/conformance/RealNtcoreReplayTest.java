/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A deterministic sample of every logger kind, without ever copying expected telemetry into code. */
@Tag("shop-harness")
class RealNtcoreReplayTest {
  @Test void sampleEveryLoggerKindThroughNtcoreAndPuller() throws Exception {
    String directory = System.getProperty("conformance.logdir");
    Assumptions.assumeTrue(directory != null && !directory.isBlank(), "Native real-log replay skipped; set -PconformanceLogDir=/path/to/logs");
    var root = Path.of(directory).toRealPath();
    var report = Files.createDirectories(Path.of("build/reports/replay")).resolve("ntcore-" + root.getFileName() + ".jsonl");
    try (var out = Files.newBufferedWriter(report)) {
      var selected = samples(root);
      for (var path : selected) for (long shift : new long[] {0, 40_000, 120_000, 200_000, -120_000, 240_000, 260_000, 6_000_000}) {
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
    var samples = new LinkedHashMap<ReplaySource.Kind, Path>();
    var calendarSamples = new LinkedHashMap<ReplaySource.Kind, Path>();
    var completeSamples = new LinkedHashMap<ReplaySource.Kind, Path>();
    // Use the smallest recording spanning the correlator's ten-second minimum, chosen without
    // looking at a correlation result. All files still run in the independent gateway sweep.
    List<Path> paths;
    try (var walk = Files.walk(root)) {
      paths = walk.filter(Files::isRegularFile).filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".wpilog"))
          .sorted(java.util.Comparator.comparingLong((Path p) -> { try { return Files.size(p); } catch (Exception e) { throw new IllegalStateException(p.toString()); } })
              .thenComparing(Path::toString)).toList();
    }
    for (var path : paths) {
      try (var source = new ReplaySource(path)) {
        if (source.records > 0 && source.maxUs - source.minUs >= 10_000_000) {
          samples.putIfAbsent(source.kind, path);
          if (source.calendar().isPresent()) calendarSamples.putIfAbsent(source.kind, path);
          if (source.calendar().isPresent() && source.reader.stopped == null) completeSamples.putIfAbsent(source.kind, path);
        }
      } catch (IndependentLog.NotALog unreadable) { /* The full sweep checks rejection; this is no logger kind. */ }
      if (completeSamples.size() == ReplaySource.Kind.values().length) break;
    }
    var selected = new java.util.LinkedHashSet<>(samples.values()); selected.addAll(calendarSamples.values());
    selected.addAll(completeSamples.values()); return List.copyOf(selected);
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
