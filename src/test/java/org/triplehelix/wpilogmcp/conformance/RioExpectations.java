/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;

/** Assertions independent of the robot program and provider parsers: clocks are measured, not guessed. */
final class RioExpectations {
  private RioExpectations() {}
  static boolean providersFollowing(JsonObject live) {
    if (!live.has("sessions")) return false;
    return live.getAsJsonArray("sessions").asList().stream().map(e -> e.getAsJsonObject())
        .filter(s -> s.get("connected").getAsBoolean()).anyMatch(s -> {
          var states = new java.util.HashMap<String, String>();
          for (var p : s.getAsJsonArray("providers")) {
            var v = p.getAsJsonObject(); states.put(v.get("name").getAsString(), v.get("state").getAsString());
          }
          return "sampling".equals(states.get("roboRIO")) && "sampling".equals(states.get("jvm"))
              && "following".equals(states.get("tail/127.0.0.1/program_console"));
        });
  }
  static void facts(String report, String modules) {
    for (String module : List.of("jdk.management.agent", "jdk.jfr", "jdk.management.jfr")) {
      boolean present = modules.lines().anyMatch(line -> line.equals(module) || line.startsWith(module + "@"));
      assertTrue(report.contains("- module:" + module + ": **" + (present ? "found" : "absent") + "**"), module + " conclusion");
    }
    assertTrue(modules.contains("jdk.management.agent@"), "The image enables the real management agent");
    assertFalse(modules.contains("jdk.jfr@"), "This image deliberately exercises missing Flight Recorder modules");
    assertTrue(report.contains("- proc-environ-readable: **found**"), "Login must see the deployed process's environment permissions");
    assertTrue(report.contains("- which:sha256sum: **found**"));
    assertTrue(report.contains("- which:journalctl: **absent**"));
    assertTrue(report.contains("- ssh-authentication: **found**"));
  }
  static void pairing(double[] times, double[] uptime, double[] offsets, double[] bounds) {
    assertTrue(times.length >= 3, "At least three JMX samples must meet the actual clock pairing");
    assertEquals(times.length, uptime.length); assertEquals(times.length, offsets.length); assertEquals(times.length, bounds.length);
    double lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY;
    for (int i = 0; i < times.length; i++) {
      assertTrue(Double.isFinite(bounds[i]) && bounds[i] >= 0.001, "Bound includes uptime's millisecond quantization");
      assertEquals(times[i] - uptime[i], offsets[i], 0.000001, "Receipt is rounded to a WPILOG microsecond");
      lower = Math.max(lower, offsets[i] - bounds[i]); upper = Math.min(upper, offsets[i] + bounds[i]);
    }
    assertTrue(lower <= upper, "A single monotonic uptime/FPGA offset must fit every sample's stated uncertainty: " + lower + " > " + upper);
  }
  static void pairing(IndependentLog log) {
    var up = log.series.get("/Daemon/JVM/uptime_sec"); var offset = log.series.get("/Daemon/JVM/clock/offset_sec");
    var bound = log.series.get("/Daemon/JVM/clock/round_trip_bound_sec");
    assertEquals(up.n, offset.n); assertEquals(up.n, bound.n);
    // The independent reader grows arrays geometrically; capacity is not a record count.
    double[] times = java.util.Arrays.copyOf(up.times, up.n);
    assertArrayEquals(times, java.util.Arrays.copyOf(offset.times, offset.n));
    assertArrayEquals(times, java.util.Arrays.copyOf(bound.times, bound.n));
    pairing(times, java.util.Arrays.copyOf(up.values, up.n), java.util.Arrays.copyOf(offset.values, offset.n),
        java.util.Arrays.copyOf(bound.values, bound.n));
  }
  static void captures(JsonObject timeline, JsonObject listing, HarnessHttp http) throws Exception {
    var numeric = Set.of("/Daemon/JVM/uptime_sec", "/Daemon/JVM/clock/offset_sec", "/Daemon/JVM/clock/round_trip_bound_sec",
        "/Daemon/roboRIO/program/pid", "/Daemon/roboRIO/uptime_sec");
    String console = "/Daemon/Tail/127.0.0.1/program_console";
    for (var item : listing.getAsJsonArray("logs")) {
      var path = Path.of(item.getAsJsonObject().get("path").getAsString());
      if (!path.getFileName().toString().equals("capture.wpilog")) continue;
      var manifest = JsonParser.parseString(Files.readString(path.getParent().resolve("session.json"))).getAsJsonObject();
      int match = manifest.get("match_number").getAsInt();
      int boot = java.util.stream.IntStream.range(0, timeline.getAsJsonArray("boots").size())
          .filter(i -> timeline.getAsJsonArray("boots").get(i).getAsJsonObject().getAsJsonObject("match").get("number").getAsInt() == match)
          .findFirst().orElseThrow();
      var log = IndependentLog.read(path, numeric, Set.of(console));
      assertNull(log.stopped);
      for (String name : numeric) assertTrue(log.series.containsKey(name) && log.series.get(name).n > 0, name);
      pairing(log);
      var tail = log.series.get(console); assertNotNull(tail, "Timely console entry");
      var lines = tail.payloads.stream().map(p -> new String(p, StandardCharsets.UTF_8)).toList();
      // The startup line may precede tail -n 0. Every later scripted state must be followed.
      for (var phase : timeline.getAsJsonArray("boots").get(boot).getAsJsonObject().getAsJsonArray("phases")) {
        if (phase.getAsJsonObject().get("at_us").getAsLong() == 0) continue;
        String text = "harness boot=" + boot + " state=" + phase.getAsJsonObject().get("state").getAsString();
        assertTrue(lines.contains(text), "Tail missed scripted transition: " + text);
      }
      var providers = manifest.getAsJsonObject("capture_stats").getAsJsonArray("providers");
      for (String name : List.of("roboRIO", "jvm", "tail/127.0.0.1/program_console")) {
        var provider = providers.asList().stream().map(p -> p.getAsJsonObject()).filter(p -> name.equals(p.get("name").getAsString())).findFirst().orElseThrow();
        assertTrue(provider.get("records").getAsLong() > 0, name + " recorded cost");
      }
      var args = new JsonObject(); args.addProperty("path", path.toString()); args.addProperty("source", "program");
      args.addProperty("pattern", "harness boot=" + boot);
      var search = http.call("search_system_logs", args);
      assertEquals("ok", search.get("status").getAsString(), search.toString());
      assertTrue(search.get("total_matches").getAsLong() > 0, "The exact NI console file is pulled too");
    }
  }
}
