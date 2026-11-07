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
import java.util.Map;
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
    var failures = new ArrayList<String>();
    long started = System.nanoTime();
    try (var out = Files.newBufferedWriter(report)) {
      for (var path : ConformanceSample.configured(root, "gateway").paths()) {
        System.out.println(path);
        var counts = replay(path, Path.of("build/replay-work"));
        if (!passed(counts)) failures.add(path.toString());
        out.write(new Gson().toJson(counts)); out.newLine(); out.flush();
      }
      out.write(new Gson().toJson(Map.of("wall_time_sec", (System.nanoTime() - started) / 1e9))); out.newLine();
    }
    org.junit.jupiter.api.Assertions.assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  /**
   * One log through the gateway, in a new run folder in {@code scratch} that holds the capture
   * and, when REV files sit beside the log, the fake roboRIO. A log that passes deletes the
   * folder; a failure keeps it for diagnosis. The fake roboRIO used to be written beside the run
   * folder, where the delete never reached it, and it held most of what the replays left behind.
   */
  static Map<String, Object> replay(Path path, Path scratch) throws Exception {
    Map<String, Object> counts = new LinkedHashMap<>(); counts.put("path", path.toString());
    long started = System.nanoTime();
    var run = ReplayScratch.create(scratch, "gateway-");
    try (var source = new ReplaySource(path)) {
      counts.put("kind", source.kind); counts.put("entries", source.entries.size());
      counts.put("records", source.records); counts.put("bytes", source.bytes);
      counts = ReplayAudit.gateway(source, run.resolve("capture"), 0, Clock.systemUTC(), true);
    } catch (ReplaySource.InvalidUtf8 invalid) {
      long invalidRecords = ReplayInputCheck.invalidUtf8(path);
      counts.put("rejected_invalid_utf8", invalidRecords > 0); counts.put("invalid_utf8_records", invalidRecords);
      counts.put("mismatches", invalidRecords > 0 ? Map.of() : Map.of("unexplained_utf8_refusal", 1));
    } catch (IndependentLog.NotALog invalid) {
      // Check rejection through the normal reader too; a zero-filled or empty file with a
      // .wpilog suffix is reported separately, never counted as a successful replay.
      boolean rejected = false;
      try (var reader = new org.triplehelix.wpilogmcp.log.ScopedLogReader(path)) {
        rejected = !reader.reader().isValid();
      } catch (java.io.IOException expected) { rejected = true; }
      counts.put("rejected_invalid_header", rejected); counts.put("entries", 0); counts.put("records", 0); counts.put("bytes", Files.size(path));
      counts.put("mismatches", rejected ? Map.of() : Map.of("invalid_header_accepted", 1));
    } catch (Exception | AssertionError error) {
      counts.put("failure", error.getClass().getSimpleName());
    }
    counts.put("wall_time_sec", (System.nanoTime() - started) / 1e9);
    if (passed(counts)) ReplayScratch.delete(run);
    return counts;
  }

  /** No failure and no mismatch: the audit's own, a refusal it could not explain, or a REV check. */
  static boolean passed(Map<String, Object> counts) {
    return !counts.containsKey("failure") && ((Map<?, ?>) counts.get("mismatches")).isEmpty();
  }
}
