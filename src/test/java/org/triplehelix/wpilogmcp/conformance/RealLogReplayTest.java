/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** A directory is external input; neither values nor entry names belong in failure messages. */
class RealLogReplayTest {
  @Test void replayDirectory() throws Exception {
    String property = System.getProperty("conformance.logdir");
    Assumptions.assumeTrue(property != null && !property.isBlank(),
        "Replay skipped; set -PconformanceLogDir=/path/to/logs");
    var root = Path.of(property).toRealPath();
    var report = Files.createDirectories(Path.of("build/reports/replay"))
        .resolve("gateway-" + root.getFileName() + ".jsonl");
    var scratch = Files.createDirectories(Path.of("build/replay-work"));
    var failures = new ArrayList<String>();
    long started = System.nanoTime();
    try (var out = Files.newBufferedWriter(report)) {
      for (var path : ConformanceSample.configured(root, "gateway").paths()) {
        java.util.Map<String, Object> counts = new LinkedHashMap<>(); counts.put("path", path.toString());
        System.out.println(path);
        long logStarted = System.nanoTime();
        var run = Files.createTempDirectory(scratch, "gateway-");
        try (var source = new ReplaySource(path)) {
          counts.put("kind", source.kind); counts.put("entries", source.entries.size());
          counts.put("records", source.records); counts.put("bytes", source.bytes);
          counts = ReplayAudit.gateway(source, run, 0, Clock.systemUTC(), true);
          if (!((java.util.Map<?, ?>) counts.get("mismatches")).isEmpty()) failures.add(path.toString());
        } catch (ReplaySource.InvalidUtf8 invalid) {
          long invalidRecords = ReplayInputCheck.invalidUtf8(path);
          counts.put("rejected_invalid_utf8", invalidRecords > 0); counts.put("invalid_utf8_records", invalidRecords);
          counts.put("mismatches", invalidRecords > 0 ? java.util.Map.of() : java.util.Map.of("unexplained_utf8_refusal", 1));
          if (invalidRecords == 0) failures.add(path.toString());
        } catch (IndependentLog.NotALog invalid) {
          // Check rejection through the normal reader too; a zero-filled or empty file with a
          // .wpilog suffix is reported separately, never counted as a successful replay.
          boolean rejected = false;
          try (var reader = new org.triplehelix.wpilogmcp.log.ScopedLogReader(path)) {
            rejected = !reader.reader().isValid();
          } catch (java.io.IOException expected) { rejected = true; }
          counts.put("rejected_invalid_header", rejected); counts.put("entries", 0); counts.put("records", 0); counts.put("bytes", Files.size(path));
          counts.put("mismatches", rejected ? java.util.Map.of() : java.util.Map.of("invalid_header_accepted", 1));
          if (!rejected) failures.add(path.toString());
        } catch (Exception | AssertionError error) {
          counts.put("failure", error.getClass().getSimpleName()); failures.add(path.toString());
        }
        counts.put("wall_time_sec", (System.nanoTime() - logStarted) / 1e9);
        out.write(new Gson().toJson(counts)); out.newLine(); out.flush();
        if (!counts.containsKey("failure") && ((java.util.Map<?, ?>) counts.get("mismatches")).isEmpty()) {
          try (var paths = Files.walk(run)) {
            for (var item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(item);
          }
        }
      }
      out.write(new Gson().toJson(java.util.Map.of("wall_time_sec", (System.nanoTime() - started) / 1e9))); out.newLine();
    }
    org.junit.jupiter.api.Assertions.assertTrue(failures.isEmpty(), String.join("\n", failures));
  }
}
