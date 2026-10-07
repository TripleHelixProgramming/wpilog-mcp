/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;

/** The live tool oracle reads the source independently; calls use the real HTTP MCP endpoint. */
public class LiveReplayTest {
  @TempDir Path directory;
  @TestFactory Stream<DynamicTest> everyFixture() throws Exception {
    return FixtureLogs.generateAll(directory.resolve("sources")).stream().map(f ->
        DynamicTest.dynamicTest(f.id(), () -> check(f.path(), directory.resolve(f.id()), false)));
  }

  @Test void realLogs() throws Exception {
    String folder = System.getProperty("conformance.logdir");
    org.junit.jupiter.api.Assumptions.assumeTrue(folder != null, "Live replay skipped; set -PconformanceLogDir=/path/to/logs");
    var failures = new ArrayList<String>();
    try (var paths = Files.walk(Path.of(folder))) {
      for (var path : paths.filter(Files::isRegularFile).filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".wpilog")).sorted().toList()) {
        try { check(path, Files.createTempDirectory(directory, "live-"), false); }
        catch (IndependentLog.NotALog | ReplaySource.InvalidUtf8 invalid) {
          // The ordinary replay suite separately checks these input refusals with both readers.
          System.out.println("Live replay input refused: " + path);
        } catch (Exception | AssertionError error) { failures.add(path.toString()); }
      }
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  @Test void concurrentLiveCallsDuringReplay() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("stress.enabled"), "Live stress runs with stressTest");
    for (var fixture : FixtureLogs.generateAll(directory.resolve("stress-sources"))) {
      check(fixture.path(), directory.resolve("stress-" + fixture.id()), true);
    }
  }

  @Test void stressTasksSelectLiveQueriesAndTheDirectoryFlagFeedsRealReplay() throws Exception {
    String build = Files.readString(Path.of("build.gradle"));
    assertEquals(2, build.split("LiveReplayTest#concurrentLiveCallsDuringReplay", -1).length - 1);
    assertEquals(2, build.split(java.util.regex.Pattern.quote(
        "systemProperty 'conformance.logdir', project.property('conformanceLogDir')"), -1).length - 1);
  }

  static void check(Path file, Path work, boolean concurrent) throws Exception {
    try (var source = new ReplaySource(file); var rig = new LiveToolRig(work, CapturePolicy.ALL)) {
      var replay = new LogReplayer(source, rig.gateway, rig.robot::set); replay.announce();
      rig.pump(() -> rig.service.live().topics().size() == replay.topicCount());
      var results = new ArrayList<Map.Entry<String, JsonElement>>();
      var arguments = new JsonObject(); var requested = new JsonArray();
      source.entries.values().stream().limit(2000).forEach(e -> requested.add(source.topic(e)));
      if (requested.isEmpty()) requested.add("/missing"); arguments.add("entries", requested);
      results.add(Map.entry("get_latest_values", rig.call("get_latest_values", arguments.toString())));
      var first = source.entries.values().stream().filter(e -> e.count > 0)
          .min(java.util.Comparator.comparingInt(e -> e.offset(0))).orElse(null);
      CompletableFuture<JsonObject> wait = null;
      if (first != null) {
        var args = new JsonObject(); args.addProperty("entry", source.topic(first));
        wait = CompletableFuture.supplyAsync(() -> {
          try { return rig.http.call("wait_for_change", args); }
          catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        });
        rig.pump(() -> rig.service.live().pendingWaits() == 1);
      }
      var peers = new ArrayList<org.triplehelix.wpilogmcp.harness.HarnessHttp>();
      if (concurrent) for (int i = 0; i < 4; i++) {
        var peer = new org.triplehelix.wpilogmcp.harness.HarnessHttp(rig.transport.getPort()); peer.initialize(); peers.add(peer);
      }
      long[] flushedThrough = {0};
      replay.replay(0, 0, ignored -> {}, count -> {
        rig.pump(() -> rig.service.live().receivedValues() >= count);
        // Fast replay advances the flush clock too: a season file must not accumulate its
        // entire hot value set merely because this test held the client clock still.
        if (count - flushedThrough[0] >= 65_536) { rig.flush(); flushedThrough[0] = count; }
        if (concurrent) {
          try {
            var reads = java.util.stream.IntStream.range(0, peers.size()).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
              try {
                String name = i == 1 ? "list_sessions" : i == 2 ? "wait_for_change" : "get_latest_values";
                var args = name.equals("get_latest_values") ? arguments : new JsonObject();
                if (name.equals("wait_for_change")) {
                  args.addProperty("entry", first == null ? "/missing" : source.topic(first)); args.addProperty("timeout_ms", 0);
                }
                return Map.entry(name, peers.get(i).call(name, args));
              } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            })).toList();
            rig.pump(() -> reads.stream().allMatch(CompletableFuture::isDone));
            for (var read : reads) {
              var result = read.get(10, TimeUnit.SECONDS);
              assertEquals(List.of(), ConformanceChecks.check(result.getValue(), null, false, result.getKey(), new JsonObject()));
            }
          } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }
      });
      rig.flush();
      if (wait != null) {
        var value = wait.get(10, TimeUnit.SECONDS); assertTrue(value.get("changed").getAsBoolean());
        var record = source.reader.at(first.offset(0));
        assertEquals(record.timestampUs() / 1e6, value.get("timestamp_sec").getAsDouble());
        assertEquals(json(ReplaySource.value(first.type, source.reader.payload(record))).toString(), value.get("value").toString());
        results.add(Map.entry("wait_for_change", value));
      }
      // Chunk only the request, never the expected values or the checked record set.
      var entries = new ArrayList<>(source.entries.values());
      for (int from = 0; from < entries.size(); from += 2000) {
        var selected = entries.subList(from, Math.min(entries.size(), from + 2000));
        var names = new JsonArray(); selected.forEach(e -> names.add(source.topic(e)));
        var args = new JsonObject(); args.add("entries", names);
        var answer = rig.call("get_latest_values", args.toString()); results.add(Map.entry("get_latest_values", answer));
        var rows = new java.util.HashMap<String, JsonObject>();
        answer.getAsJsonArray("values").forEach(v -> rows.put(v.getAsJsonObject().get("name").getAsString(), v.getAsJsonObject()));
        for (var entry : selected) {
          if (entry.count == 0) { assertFalse(rows.containsKey(source.topic(entry))); continue; }
          IndependentLog.Records.Record latest = source.reader.at(entry.offset(0));
          for (int i = 1; i < entry.count; i++) {
            var next = source.reader.at(entry.offset(i)); if (next.timestampUs() >= latest.timestampUs()) latest = next;
          }
          var row = rows.get(source.topic(entry)); assertNotNull(row);
          assertEquals(ReplaySource.ntType(entry.type), row.get("type").getAsString());
          assertEquals(latest.timestampUs() / 1e6, row.get("timestamp_sec").getAsDouble());
          assertEquals(json(ReplaySource.value(entry.type, source.reader.payload(latest))).toString(), row.get("value").toString());
        }
        assertEquals(ConformanceChecks.normalize(answer), ConformanceChecks.normalize(rig.call("get_latest_values", args.toString())));
      }
      if (!entries.isEmpty()) {
        var args = new JsonObject(); args.addProperty("entry", source.topic(entries.get(0))); args.addProperty("timeout_ms", 0);
        results.add(Map.entry("wait_for_change", rig.call("wait_for_change", args.toString())));
      }
      var capturePath = rig.service.live().current() == null ? null : rig.service.live().current().path();
      try (var use = capturePath == null ? null : rig.manager.acquire(capturePath.toString())) {
        var fixture = new FixtureLogs.Fixture("live", capturePath == null ? file : capturePath, "live replay", List.of());
        for (String name : List.of("list_sessions", "get_latest_values", "wait_for_change")) {
          var tool = rig.tools.getTool(name);
          for (var variant : ToolArguments.variants(tool, fixture, use == null ? null : use.log(), List.of(fixture), work)) {
            var args = variant.args(); args.remove("path");
            var answer = rig.call(name, args.toString());
            Integer limit = args.has("limit") ? args.get("limit").getAsInt() : null;
            assertEquals(List.of(), ConformanceChecks.check(answer, limit, false, name, args), name);
            results.add(Map.entry(name, answer));
          }
        }
      }
      var sessions = rig.call("list_sessions", "{}"); results.add(Map.entry("list_sessions", sessions));
      assertEquals(ConformanceChecks.normalize(sessions), ConformanceChecks.normalize(rig.call("list_sessions", "{}")));
      if (rig.service.live().current() != null) {
        var row = sessions.getAsJsonArray("sessions").get(0).getAsJsonObject();
        long bytes = 0, records = 0;
        try (var files = Files.list(rig.service.live().current().path().getParent())) {
          for (var captured : files.filter(p -> p.getFileName().toString().matches("capture(?:-[0-9]+)?\\.wpilog")).toList()) {
            try (var recorded = new IndependentLog.Records(captured)) {
              var seeds = new java.util.HashSet<Long>();
              for (int at = recorded.first; ; ) {
                var record = recorded.at(at); if (record == null) break; at = record.end();
                if (record.id() == 0 && recorded.control(record) == 0) {
                  var start = recorded.start(record);
                  var metadata = com.google.gson.JsonParser.parseString(start.metadata()).getAsJsonObject();
                  if (metadata.has("capture_schema_seed")) seeds.add(recorded.controlId(record));
                } else if (record.id() != 0 && !seeds.remove(record.id())) { records++; bytes += record.end() - record.offset(); }
              }
            }
          }
        }
        assertEquals(records, row.get("records").getAsLong()); assertEquals(bytes, row.get("bytes").getAsLong());
      }
      for (var result : results) assertEquals(List.of(), ConformanceChecks.check(result.getValue(), null, false, result.getKey(), new JsonObject()), result.getKey());
      // Edge-state output keys are checked in LiveToolsTest; these calls cover all ordinary publications.
      var report = Files.createDirectories(Path.of("build/reports/live-tools"));
      Files.writeString(report.resolve(work.getFileName() + ".txt"), "path=" + file + "\nentries=" + entries.size() + " records=" + replay.sent() + " calls=" + results.size() + "\n");
    }
  }
  private static JsonElement json(Object value) {
    if (value instanceof Number n && !Double.isFinite(n.doubleValue())) return new com.google.gson.JsonPrimitive(n.toString());
    if (value != null && value.getClass().isArray()) {
      var a = new JsonArray(); for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) a.add(json(java.lang.reflect.Array.get(value, i))); return a;
    }
    if (value instanceof List<?> list) { var a = new JsonArray(); list.forEach(v -> a.add(json(v))); return a; }
    return com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(value));
  }
}
