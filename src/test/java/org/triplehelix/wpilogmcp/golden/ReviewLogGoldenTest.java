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
 * that clears at 783.804 s, while {@code Connected} stays false until 783.858 s), and pose steps
 * over 362-394 s arrive a median 110.5 ms apart (the review says "about every 80-110 ms").
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
      if (value instanceof JsonElement json) args.add(key, json);
      else if (value instanceof Number n) args.addProperty(key, n);
      else if (value instanceof Boolean b) args.addProperty(key, b);
      else args.addProperty(key, value.toString());
    }
    var result = tools.get(tool).execute(args).getAsJsonObject();
    assertTrue(result.get("success").getAsBoolean(), tool + " failed: " + result);
    return result;
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
    // the same segment by scope: half-open, so the loop logged at the disable timestamp
    // (359.162 s) belongs to the disabled state
    var segment = call("get_statistics", "name", "/RealOutputs/LoggedRobot/FullCycleMS",
        "scope", "segment:0");
    assertEquals(11951, segment.get("count").getAsInt());
    near(18.03, segment.get("median").getAsDouble(), 0.005, "segment:0 median");
  }

  @Test
  @DisplayName("data quality: good full-match data reads high; long holds read medium, with reasons")
  void dataQualityCalibration() throws Exception {
    // G1: FullCycleMS scored 0.50 ("low") before; the score now weighs gaps by time
    var loop = call("get_statistics", "name", "/RealOutputs/LoggedRobot/FullCycleMS");
    var quality = loop.getAsJsonObject("data_quality");
    assertEquals("periodic", quality.get("sampling").getAsString());
    assertTrue(quality.get("quality_score").getAsDouble() >= 0.85, quality.toString());
    assertEquals("high", loop.getAsJsonObject("server_analysis_directives")
        .get("confidence_level").getAsString());
    // chassis speeds are not logged while unchanged (zero while disabled): 28% of the time in holds
    var vx = call("get_statistics", "name", "/RealOutputs/SwerveChassisSpeeds/Measured.vx");
    assertEquals("medium", vx.getAsJsonObject("server_analysis_directives")
        .get("confidence_level").getAsString());
    assertTrue(vx.getAsJsonObject("data_quality").getAsJsonArray("reasons").get(0).getAsString()
        .startsWith("28.0% of the time span"), vx.toString());
  }

  @Test
  @DisplayName("get_statistics with scope enabled matches numpy over all four segments")
  void loopTimingEnabledScope() throws Exception {
    var result = call("get_statistics", "name", "/RealOutputs/LoggedRobot/FullCycleMS",
        "scope", "enabled");
    assertEquals(48596, result.get("count").getAsInt());
    near(17.60, result.get("median").getAsDouble(), 0.01, "enabled median");
    near(53.11, result.get("p95").getAsDouble(), 0.01, "enabled p95");
    assertEquals(4, result.getAsJsonObject("inputs").getAsJsonObject("scope")
        .get("window_count").getAsInt());
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
    var x = call("get_statistics", "name", "/RealOutputs/Drive/Pose.translation.x",
        "start_time", 362, "end_time", 394);
    var y = call("get_statistics", "name", "/RealOutputs/Drive/Pose.translation.y",
        "start_time", 362, "end_time", 394);
    near(0.28993, x.get("max").getAsDouble() - x.get("min").getAsDouble(), 0.00001, "x range");
    near(0.59916, y.get("max").getAsDouble() - y.get("min").getAsDouble(), 0.00001, "y range");
    assertEquals(1324, x.get("count").getAsInt());
    // heading in degrees: how far it turned, and its circular mean (numpy)
    var heading = call("get_statistics", "name",
        "/RealOutputs/Drive/Pose.rotation._derived.degrees", "start_time", 362, "end_time", 394);
    near(7.3339, heading.get("max").getAsDouble() - heading.get("min").getAsDouble(), 0.0001,
        "heading range");
    var angle = heading.getAsJsonObject("angle");
    near(91.3069, angle.get("circular_mean").getAsDouble(), 0.0001, "circular mean");
    near(1.4984, angle.get("circular_std").getAsDouble(), 0.0001, "circular std");
    assertEquals(0, angle.get("wraps").getAsInt());
  }

  @Test
  @DisplayName("the pose heading wraps 189 times over the log; statistics unwrap it")
  void headingWraps() throws Exception {
    var heading = call("get_statistics", "name", "/RealOutputs/Drive/Pose", "field",
        "rotation.value");
    var angle = heading.getAsJsonObject("angle");
    assertEquals("radians", angle.get("unit").getAsString());
    assertEquals(189, angle.get("wraps").getAsInt());
    // unwrapped: the heading turned through 6646 degrees in all
    near(6646.07, Math.toDegrees(heading.get("max").getAsDouble() - heading.get("min")
        .getAsDouble()), 0.01, "unwrapped range");
  }

  @Test
  @DisplayName("disabled and stationary is one compound find_condition (Python: four intervals)")
  void disabledAndStationary() throws Exception {
    var conditions = com.google.gson.JsonParser.parseString("""
        {"all": [
          {"name": "/DriverStation/Enabled", "operator": "eq", "threshold": 0},
          {"name": "/RealOutputs/SwerveChassisSpeeds/Measured.vx", "operator": "abs_lt", "threshold": 0.05},
          {"name": "/RealOutputs/SwerveChassisSpeeds/Measured.vy", "operator": "abs_lt", "threshold": 0.05},
          {"name": "/RealOutputs/SwerveChassisSpeeds/Measured.omega", "operator": "abs_lt", "threshold": 0.05}
        ]}""");
    var args = new JsonObject();
    args.addProperty("path", logPath.toString());
    args.add("conditions", conditions);
    var result = tools.get("find_condition").execute(args).getAsJsonObject();
    double[][] expected = {{10.827, 40.207}, {359.162, 395.210}, {744.929, 791.534},
        {840.133, 995.211}};
    var intervals = result.getAsJsonArray("intervals");
    assertEquals(expected.length, intervals.size(), result.toString());
    for (int i = 0; i < expected.length; i++) {
      var interval = intervals.get(i).getAsJsonObject();
      near(expected[i][0], interval.get("start").getAsDouble(), 0.001, "start " + i);
      near(expected[i][1], interval.get("end").getAsDouble(), 0.001, "end " + i);
    }
  }

  @Test
  @DisplayName("compare_matches flags the 9.6 s boot loop; scope enabled compares the match")
  void compareMatchesBootTransient() throws Exception {
    var other = logPath.resolveSibling("akit_26-09-29_23-11-20.wpilog");
    Assumptions.assumeTrue(Files.exists(other), "second log not found: " + other);
    LogManager.getInstance().addAllowedDirectory(other.getParent());
    var all = call("compare_matches", "compare_path", other.toString(),
        "name", "/RealOutputs/LoggedRobot/FullCycleMS");
    var first = all.getAsJsonArray("comparisons").get(0).getAsJsonObject();
    near(9603.512, first.getAsJsonObject("statistics").get("max").getAsDouble(), 0.001,
        "boot loop");
    assertTrue(first.get("max_likely_boot_transient").getAsBoolean());
    var enabled = call("compare_matches", "compare_path", other.toString(),
        "name", "/RealOutputs/LoggedRobot/FullCycleMS", "scope", "enabled");
    var stats = enabled.getAsJsonArray("comparisons").get(0).getAsJsonObject()
        .getAsJsonObject("statistics");
    near(53.11, stats.get("p95").getAsDouble(), 0.01, "enabled p95 (numpy)");
    near(17.60, stats.get("median").getAsDouble(), 0.01, "enabled median (numpy)");
    assertTrue(enabled.has("differences"));
  }

  // ==================== strings ====================

  @Test
  @DisplayName("search_strings finds the camera 3 alert in the string[] warnings entry")
  void cameraAlert() throws Exception {
    var result = call("search_strings", "pattern", "Vision camera 3 is disconnected");
    // one match per appearance (Python: raised 10.827 and 737.678, cleared 26.719 and 783.804),
    // not one per record of the alert array
    var alerts = new ArrayList<JsonObject>();
    for (var m : result.getAsJsonArray("matches")) {
      var match = m.getAsJsonObject();
      if (match.get("entry").getAsString().equals("/RealOutputs/Alerts/warnings")) {
        alerts.add(match);
      }
    }
    assertEquals(2, alerts.size(), result.toString());
    near(10.827, alerts.get(0).get("timestamp_sec").getAsDouble(), 0.001, "boot alert raised");
    near(26.719, alerts.get(0).get("end_sec").getAsDouble(), 0.001, "boot alert cleared");
    var alert = alerts.get(1);
    assertEquals("alert", alert.get("source").getAsString());
    assertEquals("warning", alert.get("level").getAsString());
    near(737.678, alert.get("timestamp_sec").getAsDouble(), 0.001, "alert raised");
    near(783.804, alert.get("end_sec").getAsDouble(), 0.001, "alert cleared");
    near(46.126, alert.get("duration_sec").getAsDouble(), 0.002, "alert duration");

    // and on the timeline
    var timeline = call("get_ds_timeline");
    boolean onTimeline = false;
    for (var e : timeline.getAsJsonArray("events")) {
      var event = e.getAsJsonObject();
      if (event.get("type").getAsString().equals("ALERT_RAISED")
          && event.get("message").getAsString().contains("Vision camera 3 is disconnected")) {
        if (event.get("timestamp").getAsDouble() > 700) {
          near(783.804, event.get("cleared_at").getAsDouble(), 0.001, "timeline cleared_at");
          onTimeline = true;
        }
      }
    }
    assertTrue(onTimeline, "no ALERT_RAISED for camera 3");
  }

  // ==================== Appendix A values added after the plan ====================

  @Test
  @DisplayName("camera 3 LatencyMs: median 61.2 ms, and one reconnect glitch of 5,870,507 ms")
  void camera3Latency() throws Exception {
    var result = call("get_statistics", "name", "/Vision/Camera3/LatencyMs");
    assertEquals(22325, result.get("count").getAsInt());
    near(61.174, result.get("median").getAsDouble(), 0.0005, "median");
    near(5870507.267, result.get("max").getAsDouble(), 0.001, "max");
  }

  @Test
  @DisplayName("the gyro yaw moves 0.034 degrees while the pose wanders (362-394 s)")
  void gyroYawRange() throws Exception {
    var result = call("get_statistics", "name", "/Drive/Gyro/YawPosition", "field", "value",
        "start_time", 362, "end_time", 394);
    near(0.03431, Math.toDegrees(result.get("max").getAsDouble()
        - result.get("min").getAsDouble()), 0.00001, "yaw range (degrees)");
  }

  @Test
  @DisplayName("analyze_swerve over the whole log: mean |speed| 0.93 m/s, maximum 4.75 m/s")
  void swerveWholeLog() throws Exception {
    var modules = call("analyze_swerve").getAsJsonArray("modules");
    assertEquals(4, modules.size());
    double meanOfMeans = 0.0;
    double max = 0.0;
    for (var m : modules) {
      // every module has the same sample count, so the mean of the means is the overall mean
      meanOfMeans += m.getAsJsonObject().get("mean_abs_speed_mps").getAsDouble() / 4.0;
      max = Math.max(max, m.getAsJsonObject().get("max_abs_speed_mps").getAsDouble());
    }
    near(0.92957, meanOfMeans, 0.00001, "mean |speed|");
    near(4.74976, max, 0.00001, "max |speed|");
  }

  @Test
  @DisplayName("loop time while disabled after boot (t > 30 s): p50 16.0 ms, p95 39.4 ms")
  void loopTimingDisabled() throws Exception {
    var windows = new com.google.gson.JsonArray();
    for (double[] w : new double[][] {{30.0, 40.207135}, {359.161586, 395.209541},
        {744.929426, 791.534465}, {840.133016, 995.211072}}) {
      var pair = new com.google.gson.JsonArray();
      pair.add(w[0]);
      pair.add(w[1]);
      windows.add(pair);
    }
    var result = call("get_statistics", "name", "/RealOutputs/LoggedRobot/FullCycleMS",
        "windows", windows);
    near(10526, result.get("count").getAsInt(), 2, "count");
    near(15.99, result.get("median").getAsDouble(), 0.01, "median");
    near(39.41, result.get("p95").getAsDouble(), 0.05, "p95");
  }

  @Test
  @DisplayName("pose x steps over 1 cm (362-394 s): 218 of them, a median 114.6 ms apart")
  void poseStepCadence() throws Exception {
    var result = call("detect_anomalies", "name", "/RealOutputs/Drive/Pose.translation.x",
        "start_time", 362, "end_time", 394, "spike_threshold", 0.01);
    assertEquals(218, result.get("spike_count").getAsInt());
    var intervals = result.getAsJsonObject("spike_interval_sec");
    near(0.1146, intervals.get("median").getAsDouble(), 0.0005, "median interval");
  }
}
