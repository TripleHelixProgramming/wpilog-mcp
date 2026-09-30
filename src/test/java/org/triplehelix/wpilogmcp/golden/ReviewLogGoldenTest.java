/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * Golden values from {@code doc/ROBUSTNESS_REVIEW.md} Appendix A, asserted through the tools
 * against the real log the review used ({@code akit_26-09-30_00-10-26.wpilog}, team 2363).
 *
 * <p>Opt-in: {@code ./gradlew test --tests '*ReviewLogGoldenTest' -PgoldenLog=/path/to/log}. The
 * log is not in the repository (116 MB). Every value was recomputed independently with
 * {@code wpiutil.log.DataLogReader} and numpy on 2026-09-29; two differ from the review's text
 * (CANHD TEC peaks at 215 at 650.86 s, not 85 at 205.76 s; the camera 3 alert is a warning
 * that clears at 783.804 s, while {@code Connected} stays false until 783.858 s).
 *
 * <p>Checks that the remediation plan ({@code doc/ROBUSTNESS_PLAN.md}) has not delivered yet are
 * wrapped in {@link #pending}: while they fail they are reported as skipped, and once they pass
 * they fail with a request to remove the marker, so the list only moves forward.
 */
@DisplayName("Golden values on the review log")
class ReviewLogGoldenTest {

  static Path logPath;
  static Map<String, Tool> tools;

  @BeforeAll
  static void setUp() {
    String property = System.getProperty("golden.log");
    Assumptions.assumeTrue(property != null && !property.isBlank(),
        "golden.log not set; run with -PgoldenLog=/path/to/akit_26-09-30_00-10-26.wpilog");
    logPath = Path.of(property).toAbsolutePath().normalize();
    Assumptions.assumeTrue(Files.exists(logPath), "golden log not found: " + logPath);
    LogManager.getInstance().addAllowedDirectory(logPath.getParent());
    var captured = new java.util.TreeMap<String, Tool>();
    WpilogTools.registerAll(new ToolRegistry() {
      @Override
      public void registerTool(Tool tool) {
        captured.put(tool.name(), tool);
        super.registerTool(tool);
      }
    });
    tools = captured;
  }

  @AfterAll
  static void tearDown() {
    LogManager.getInstance().unloadAllLogs();
  }

  // ==================== helpers ====================

  static JsonObject call(String tool, Object... keyValues) throws Exception {
    var args = new JsonObject();
    args.addProperty("path", logPath.toString());
    for (int i = 0; i < keyValues.length; i += 2) {
      var key = (String) keyValues[i];
      var value = keyValues[i + 1];
      if (value instanceof Number n) args.addProperty(key, n);
      else if (value instanceof Boolean b) args.addProperty(key, b);
      else args.addProperty(key, value.toString());
    }
    var result = tools.get(tool).execute(args).getAsJsonObject();
    assertTrue(result.get("success").getAsBoolean(), tool + " failed: " + result);
    return result;
  }

  /** Runs a check that a later phase delivers: skipped while it fails, a failure once it passes. */
  static void pending(String phase, Executable check) {
    try {
      check.execute();
    } catch (AssertionFailedError | RuntimeException e) {
      throw new TestAbortedException("pending " + phase + ": " + e.getMessage());
    } catch (Throwable t) {
      throw new TestAbortedException("pending " + phase + ": " + t);
    }
    fail("This check now passes: remove its pending(\"" + phase + "\") marker.");
  }

  /** Finds the first number under {@code key} anywhere in the tree (depth-first). */
  static Optional<Double> findNumber(JsonElement e, String key) {
    if (e == null || e.isJsonNull() || e.isJsonPrimitive()) return Optional.empty();
    if (e.isJsonArray()) {
      for (var item : e.getAsJsonArray()) {
        var found = findNumber(item, key);
        if (found.isPresent()) return found;
      }
      return Optional.empty();
    }
    var obj = e.getAsJsonObject();
    if (obj.has(key) && obj.get(key).isJsonPrimitive() && obj.get(key).getAsJsonPrimitive().isNumber()) {
      return Optional.of(obj.get(key).getAsDouble());
    }
    for (var entry : obj.entrySet()) {
      var found = findNumber(entry.getValue(), key);
      if (found.isPresent()) return found;
    }
    return Optional.empty();
  }

  static void near(double expected, double actual, double tolerance, String what) {
    assertEquals(expected, actual, tolerance, what);
  }

  // ==================== timeline ====================

  static final double[][] ENABLED_SEGMENTS = {
      {40.207, 359.162}, {395.210, 744.929}, {791.534, 840.133}, {995.211, Double.NaN}};

  @Test
  @DisplayName("get_match_phases reports the four enabled segments, the last ending at log end")
  void enabledSegments() throws Exception {
    var result = call("get_match_phases");
    {
      assertTrue(result.has("segments"), "no segments in " + result);
      var segments = new ArrayList<JsonObject>();
      for (var s : result.getAsJsonArray("segments")) {
        var seg = s.getAsJsonObject();
        if ("enabled".equals(seg.get("state").getAsString())) segments.add(seg);
      }
      assertEquals(4, segments.size(), "enabled segments");
      for (int i = 0; i < 4; i++) {
        near(ENABLED_SEGMENTS[i][0], segments.get(i).get("start").getAsDouble(), 0.001,
            "segment " + i + " start");
        if (i < 3) {
          near(ENABLED_SEGMENTS[i][1], segments.get(i).get("end").getAsDouble(), 0.001,
              "segment " + i + " end");
          assertEquals("disabled", segments.get(i).get("end_reason").getAsString());
        } else {
          assertEquals("log_end", segments.get(i).get("end_reason").getAsString());
        }
      }
    }
    // Autonomous is logged once (false) and held: every segment is teleop, and no match
    assertEquals(0, result.getAsJsonArray("matches").size());
    assertTrue(result.getAsJsonArray("notes").toString().contains("Autonomous was never true"));
  }

  @Test
  @DisplayName("get_ds_timeline reports the two roboRIO brownouts from the logged flag")
  void rioBrownouts() throws Exception {
    var result = call("get_ds_timeline");
    var starts = new ArrayList<Double>();
    for (var e : result.getAsJsonArray("events")) {
      var event = e.getAsJsonObject();
      if ("RIO_BROWNOUT_START".equals(event.get("type").getAsString())) {
        starts.add(event.get("timestamp").getAsDouble());
      }
    }
    assertEquals(2, starts.size(), "RIO brownout starts: " + starts);
    near(655.434, starts.get(0), 0.001, "first brownout");
    near(708.435, starts.get(1), 0.001, "second brownout");
  }

  @Test
  @DisplayName("power tools take the brownout threshold from /SystemStats/BrownoutVoltage (6.75 V)")
  void loggedBrownoutThreshold() throws Exception {
    var result = call("power_analysis");
    var voltage = result.getAsJsonObject("voltage_analysis");
    near(6.75, voltage.get("brownout_threshold").getAsDouble(), 1e-9, "threshold");
    assertEquals("logged", voltage.get("brownout_threshold_basis").getAsString());
    assertEquals("/SystemStats/BrownoutVoltage",
        voltage.get("brownout_threshold_entry").getAsString());
    near(6.618, voltage.get("min_voltage").getAsDouble(), 0.001, "battery minimum");
    var rio = result.getAsJsonObject("rio_brownouts");
    assertEquals(2, rio.get("count").getAsInt());
    var first = rio.getAsJsonArray("events").get(0).getAsJsonObject();
    near(655.434, first.get("start").getAsDouble(), 0.001, "first brownout start");
    near(0.143, first.get("duration_sec").getAsDouble(), 0.002, "first brownout duration");
  }

  // ==================== loop timing ====================

  @Test
  @DisplayName("get_statistics on FullCycleMS over the first enabled segment matches numpy")
  void loopTimingFirstSegment() throws Exception {
    var result = call("get_statistics", "name", "/RealOutputs/LoggedRobot/FullCycleMS",
        "start_time", 40.207, "end_time", 359.162);
    assertEquals(11952, result.get("count").getAsInt());
    near(18.03, result.get("median").getAsDouble(), 0.005, "median");
    near(51.39, result.get("p95").getAsDouble(), 0.005, "p95");
  }

  @Test
  @DisplayName("analyze_loop_timing finds FullCycleMS and reports enabled-time percentiles")
  void loopTimingEnabled() throws Exception {
    var result = call("analyze_loop_timing", "scope", "enabled", "threshold_ms", 25);
    assertEquals("/RealOutputs/LoggedRobot/FullCycleMS", result.get("loop_time_entry").getAsString());
    var stats = result.getAsJsonObject("statistics");
    near(17.60, stats.get("median_ms").getAsDouble(), 0.01, "enabled median");
    near(38.62, stats.get("p90_ms").getAsDouble(), 0.01, "enabled p90");
    near(53.11, stats.get("p95_ms").getAsDouble(), 0.01, "enabled p95");
    near(91.92, stats.get("p99_ms").getAsDouble(), 0.01, "enabled p99");
    near(18.49, result.get("percent_over_threshold").getAsDouble(), 0.01,
        "percent of loops over 25 ms");
    assertEquals(48596, result.get("total_samples").getAsInt());
    assertEquals("/RealOutputs/LoggedRobot/UserCodeMS",
        result.getAsJsonObject("user_code").get("entry").getAsString());
    // Whole log: the 9.6 s boot cycle is excluded and reported, not counted as an overrun
    var all = call("analyze_loop_timing");
    near(9603.5, all.getAsJsonObject("excluded_boot_cycle").get("loop_time_ms").getAsDouble(),
        0.1, "boot cycle");
  }

  // ==================== CAN ====================

  @Test
  @DisplayName("analyze_can_bus reports the CANHD transmit error counter peak (215 at 650.86 s)")
  void canTecPeak() throws Exception {
    var result = call("analyze_can_bus", "bus_name", "CANHD");
    var tec = result.getAsJsonArray("buses").get(0).getAsJsonObject().getAsJsonObject("tec");
    near(215, tec.get("max").getAsDouble(), 0.5, "TEC max");
    near(650.86, tec.get("max_time_sec").getAsDouble(), 0.01, "TEC max time");
    near(68, tec.get("samples").getAsInt(), 0, "TEC samples");
    // Nine rises past 128 (error-passive): 284.81, 628.90, 647.16, 650.86, 678.58, 678.68,
    // 737.98, 797.26, and 820.05 s
    near(9, tec.get("error_passive_excursions").getAsInt(), 0, "error-passive excursions");
  }

  // ==================== swerve ====================

  @Test
  @DisplayName("analyze_swerve reports mean |speed| per module while enabled")
  void swerveModuleSpeeds() throws Exception {
    var result = call("analyze_swerve", "scope", "enabled");
    var modules = result.getAsJsonArray("modules");
    double[] expected = {0.971, 0.950, 0.987, 0.975};
    for (int m = 0; m < 4; m++) {
      near(expected[m], modules.get(m).getAsJsonObject().get("mean_abs_speed_mps").getAsDouble(),
          0.0005, "module " + m);
    }
  }

  // ==================== vision ====================

  @Test
  @DisplayName("ObservationScore statistics match numpy")
  void observationScore() throws Exception {
    var result = call("get_statistics", "name", "/RealOutputs/Vision/Summary/ObservationScore");
    assertEquals(5741, result.get("count").getAsInt());
    near(0.7205, result.get("median").getAsDouble(), 0.00005, "median");
    near(0.6794, result.get("p5").getAsDouble(), 0.00005, "p5");
    near(1.0, result.get("p95").getAsDouble(), 1e-9, "p95");
  }

  @Test
  @DisplayName("read_entry decodes camera 3's PoseObservation at 370 s byte-for-byte")
  void poseObservationDecode() throws Exception {
    var result = call("read_entry", "name", "/Vision/Camera3/PoseObservations",
        "start_time", 370, "limit", 1);
    var sample = result.getAsJsonArray("samples").get(0).getAsJsonObject();
    near(370.047375, sample.get("timestamp_sec").getAsDouble(), 1e-6, "record time");
    var value = sample.get("value");
    near(369.970913, findNumber(value, "timestamp").orElseThrow(), 1e-6, "embedded timestamp");
    near(2.857566, findNumber(value, "averageTagDistance").orElseThrow(), 1e-6, "distance");
    near(1, findNumber(value, "tagCount").orElseThrow(), 0, "tagCount");
    // decoded by the log's own schema: nested exactly as the schema declares
    var observation = value.getAsJsonArray().get(0).getAsJsonObject();
    var translation = observation.getAsJsonObject("pose").getAsJsonObject("translation");
    near(3.1805, translation.get("x").getAsDouble(), 5e-5, "pose.translation.x");
    near(4.5455, translation.get("y").getAsDouble(), 5e-5, "pose.translation.y");
    near(0.3667, translation.get("z").getAsDouble(), 5e-5, "pose.translation.z");
    near(0.0, observation.get("ambiguity").getAsDouble(), 0, "ambiguity");
    var type = observation.getAsJsonObject("type");
    assertEquals(2, type.get("value").getAsInt());
    assertEquals("PHOTONVISION", type.get("label").getAsString());
  }

  @Test
  @DisplayName("analyze_vision counts PoseObservations per camera")
  void visionObservationCounts() throws Exception {
    var result = call("analyze_vision");
    var streams = result.getAsJsonArray("observation_streams");
    long[] expected = {4943, 5433, 4132, 2377};
    for (int c = 0; c < 4; c++) {
      boolean found = false;
      for (var s : streams) {
        var stream = s.getAsJsonObject();
        if (stream.get("entry").getAsString().equals("/Vision/Camera" + c + "/PoseObservations")) {
          assertEquals(expected[c], stream.get("observation_count").getAsLong(), "camera " + c);
          // every observation used exactly one tag
          assertEquals(expected[c], stream.getAsJsonObject("tag_count_distribution").get("1")
              .getAsLong(), "camera " + c + " tag counts");
          found = true;
        }
      }
      assertTrue(found, "no stream for camera " + c);
    }
    assertEquals("/RealOutputs/Drive/Pose",
        result.getAsJsonObject("inputs").getAsJsonObject("entries").get("robot_pose").getAsString());
  }

  // ==================== pose while disabled ====================

  @Test
  @DisplayName("get_statistics with field paths measures pose wander over 362-394 s")
  void poseWander() throws Exception {
    pending("Phase 2 (D1)", () -> {
      var x = call("get_statistics", "name", "/RealOutputs/Drive/Pose.translation.x",
          "start_time", 362, "end_time", 394);
      var y = call("get_statistics", "name", "/RealOutputs/Drive/Pose.translation.y",
          "start_time", 362, "end_time", 394);
      near(0.290, x.get("max").getAsDouble() - x.get("min").getAsDouble(), 0.0005, "x range");
      near(0.599, y.get("max").getAsDouble() - y.get("min").getAsDouble(), 0.0005, "y range");
    });
  }

  // ==================== strings ====================

  @Test
  @DisplayName("search_strings finds the camera 3 alert in the string[] warnings entry")
  void cameraAlert() throws Exception {
    pending("Phase 4 (F1)", () -> {
      var result = call("search_strings", "pattern", "Vision camera 3 is disconnected");
      boolean found = false;
      for (var m : result.getAsJsonArray("matches")) {
        var match = m.getAsJsonObject();
        if (match.get("entry").getAsString().equals("/RealOutputs/Alerts/warnings")
            && Math.abs(match.get("timestamp_sec").getAsDouble() - 737.678) < 0.001) {
          found = true;
        }
      }
      assertTrue(found, "alert not found: " + result);
    });
  }

  static List<String> names(JsonElement array) {
    var out = new ArrayList<String>();
    array.getAsJsonArray().forEach(e -> out.add(e.getAsString()));
    return out;
  }
}
