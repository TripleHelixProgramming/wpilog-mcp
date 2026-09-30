/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * {@code data_quality} describes the samples a result was computed from: every sample in scope,
 * scored before non-finite values are dropped, within the scope's windows and never across the
 * time between two; results made only of observed events carry none (review 6, sections 1.3,
 * 1.4, 1.5, 9.1, 9.4).
 */
class DataQualityScopingTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    WpilogTools.registerAll(registry);
  }

  private JsonObject call(String tool, String path, Object... keyValues) throws Exception {
    var args = new JsonObject();
    args.addProperty("path", path);
    for (int i = 0; i < keyValues.length; i += 2) {
      var key = (String) keyValues[i];
      var value = keyValues[i + 1];
      if (value instanceof Number n) args.addProperty(key, n);
      else if (value instanceof Boolean b) args.addProperty(key, b);
      else args.addProperty(key, value.toString());
    }
    return findTool(tool).execute(args).getAsJsonObject();
  }

  private static Map<String, Object> pose(double x, double y, double heading) {
    var t = new LinkedHashMap<String, Object>();
    t.put("x", x);
    t.put("y", y);
    var r = new LinkedHashMap<String, Object>();
    r.put("value", heading);
    var p = new LinkedHashMap<String, Object>();
    p.put("translation", t);
    p.put("rotation", r);
    return p;
  }

  private static ParsedLog quarterNanLog() {
    var ts = new double[200];
    var vs = new double[200];
    for (int i = 0; i < 200; i++) {
      ts[i] = i * 0.02;
      vs[i] = i % 4 == 0 ? Double.NaN : Math.sin(i);
    }
    return new MockLogBuilder().setPath("/mock/quarter_nan.wpilog")
        .addNumericEntry("/A", ts, vs).build();
  }

  @Test
  @DisplayName("NaN samples are counted in data_quality, not hidden, and the series stays periodic")
  void nanSamplesAreCountedNotHidden() throws Exception {
    var log = quarterNanLog();
    putLogInCache(log);
    for (var tool : List.of("get_statistics", "find_peaks", "rate_of_change", "detect_anomalies")) {
      var r = call(tool, log.path(), "name", "/A");
      assertTrue(r.get("success").getAsBoolean(), tool + ": " + r);
      var q = r.getAsJsonObject("data_quality");
      assertEquals(200, q.get("sample_count").getAsInt(), tool);
      assertEquals(50, q.get("nan_filtered").getAsInt(), tool + " must count the NaN samples");
      assertEquals("periodic", q.get("sampling").getAsString(),
          tool + ": dropping NaN samples must not make a 50 Hz series look change-only");
      assertTrue(q.getAsJsonArray("reasons").toString().contains("NaN"), tool);
      var guidance = r.getAsJsonObject("server_analysis_directives").toString();
      assertFalse(guidance.contains("logged only when they change"), tool + ": " + guidance);
    }
  }

  @Test
  @DisplayName("loop timing quality describes the scope, and the health score states its basis")
  void loopTimingQualityIsScoped() throws Exception {
    var b = new MockLogBuilder().setPath("/mock/loop_scope.wpilog");
    b.addBooleanEntry("/DriverStation/Enabled", new double[]{0, 10, 100, 110},
        new boolean[]{true, false, true, false});
    b.addPeriodicEntry("/RealOutputs/LoggedRobot/FullCycleMS", 0, 120, 0.02, t -> 18.0);
    var log = b.build();
    putLogInCache(log);
    var r = call("analyze_loop_timing", log.path(), "scope", "segment:0");
    assertTrue(r.get("success").getAsBoolean(), r.toString());
    var q = r.getAsJsonObject("data_quality");
    assertEquals(r.get("total_samples").getAsInt(), q.get("sample_count").getAsInt(),
        "quality must describe the samples analyzed, not the whole entry");
    assertEquals(10.0, q.get("time_span_seconds").getAsDouble(), 0.1);
    assertTrue(r.get("health_score_basis").getAsString().contains("heuristic"));
  }

  @Test
  @DisplayName("the time between two windows of a scope is not a gap for compare_poses")
  void windowsAreNotGapsInPoseTools() throws Exception {
    var b = new MockLogBuilder().setPath("/mock/pose_windows.wpilog");
    b.addBooleanEntry("/DriverStation/Enabled", new double[]{0, 10, 20, 30},
        new boolean[]{true, false, true, false});
    int n = 1500;
    var ts = new double[n];
    var poses = new ArrayList<Map<String, Object>>();
    var refs = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < n; i++) {
      ts[i] = i * 0.02;
      poses.add(pose(i * 0.01, 0, 0.1));
      refs.add(pose(i * 0.01 + 0.02, 0.01, 0.1));
    }
    b.addStructEntry("/Odometry/Robot", "struct:Pose2d", ts, poses);
    b.addStructEntry("/Odometry/TrajectorySetpoint", "struct:Pose2d", ts, refs);
    var log = b.build();
    putLogInCache(log);
    var r = call("compare_poses", log.path(), "reference_entry", "/Odometry/TrajectorySetpoint",
        "scope", "enabled");
    assertTrue(r.get("success").getAsBoolean(), r.toString());
    var q = r.getAsJsonObject("data_quality");
    assertEquals(0, q.get("gap_count").getAsInt(), q.toString());
    assertEquals(1.0, q.get("quality_score").getAsDouble(), 1e-9);
    assertEquals(20.0, q.get("time_span_seconds").getAsDouble(), 0.1);
  }

  @Test
  @DisplayName("a timeline of events carries no quality block and no 'preliminary' warning")
  void eventTimelineCarriesNoQualityWarning() throws Exception {
    var log = MockLogBuilder.createCleanMatchLog();
    putLogInCache(log);
    var r = call("get_ds_timeline", log.path());
    assertTrue(r.get("success").getAsBoolean());
    assertFalse(r.has("data_quality"));
    assertFalse(r.has("server_analysis_directives"));
    if (r.has("warnings")) {
      assertFalse(r.get("warnings").toString().contains("preliminary"), r.get("warnings").toString());
    }
  }

  @Test
  @DisplayName("find_condition and analyze_can_bus carry the quality of the entries they read")
  void findConditionAndCanBusCarryQuality() throws Exception {
    var b = new MockLogBuilder().setPath("/mock/quality_presence.wpilog");
    b.addPeriodicEntry("/A", 0, 10, 0.02, t -> Math.sin(t));
    b.addPeriodicEntry("/SystemStats/CANBus/Utilization", 0, 10, 0.1, t -> 0.3);
    b.addPeriodicEntry("/SystemStats/CANBus/TEC", 0, 10, 0.1, t -> 0.0);
    var log = b.build();
    putLogInCache(log);
    var fc = call("find_condition", log.path(), "name", "/A", "operator", "gt", "threshold", 0.0);
    assertTrue(fc.get("success").getAsBoolean(), fc.toString());
    assertTrue(fc.has("data_quality") && fc.has("server_analysis_directives"), fc.toString());
    assertEquals(501, fc.getAsJsonObject("data_quality").get("sample_count").getAsInt());
    var can = call("analyze_can_bus", log.path());
    assertTrue(can.get("success").getAsBoolean(), can.toString());
    assertTrue(can.has("data_quality") && can.has("server_analysis_directives"), can.toString());
  }

  @Test
  @DisplayName("undefined statistics are null and not a success, never zero")
  void undefinedStatisticsAreNullNotZero() throws Exception {
    var dup = new MockLogBuilder().setPath("/mock/dup_ts.wpilog")
        .addNumericEntry("/A", new double[]{1, 1, 1}, new double[]{1, 2, 3}).build();
    putLogInCache(dup);
    var r = call("rate_of_change", dup.path(), "name", "/A");
    assertEquals("no_match", r.get("status").getAsString(), r.toString());
    assertTrue(r.getAsJsonObject("statistics").get("avg_rate").isJsonNull());
    assertEquals(0, r.getAsJsonObject("statistics").get("rate_count").getAsInt());

    var b = new MockLogBuilder().setPath("/mock/constant.wpilog");
    b.addPeriodicEntry("/X", 0, 10, 0.02, t -> Math.sin(t));
    b.addPeriodicEntry("/Y", 0, 10, 0.02, t -> 5.0);
    var log = b.build();
    putLogInCache(log);
    var c = call("time_correlate", log.path(), "name1", "/X", "name2", "/Y");
    assertTrue(c.get("correlation").isJsonNull(), c.toString());
    assertTrue(c.get("p_value").isJsonNull(), "no p-value for an undefined correlation");
  }

  @Test
  @DisplayName("battery crossings and recovery stay within each window of the scope")
  void batteryCrossingsStayWithinWindows() throws Exception {
    var b = new MockLogBuilder().setPath("/mock/battery_windows.wpilog");
    b.addBooleanEntry("/DriverStation/Enabled", new double[]{0, 10, 20, 30},
        new boolean[]{true, false, true, false});
    // 6 V for the last 2 s of the first enabled segment and the first 2 s of the second: two
    // crossings of about 2 s each, not one 14 s crossing spanning the disabled gap
    b.addPeriodicEntry("/SystemStats/BatteryVoltage", 0, 30, 0.02,
        t -> (t >= 8 && t < 10) || (t >= 20 && t < 22) ? 6.0 : 12.0);
    var log = b.build();
    putLogInCache(log);
    var r = call("predict_battery_health", log.path());
    assertTrue(r.get("success").getAsBoolean(), r.toString());
    assertEquals(2, r.get("threshold_crossings").getAsInt(), r.toString());
    for (var e : r.getAsJsonArray("brownout_details")) {
      assertTrue(e.getAsJsonObject().get("duration").getAsDouble() <= 2.1, e.toString());
    }
    assertEquals(0, r.getAsJsonObject("data_quality").get("gap_count").getAsInt());
    assertTrue(r.get("risk_level_basis").getAsString().contains("CRITICAL"));
  }

  @Test
  @DisplayName("the low-quality warning bounds statistics, not observed events")
  void lowQualityWarningBoundsStatisticsOnly() throws Exception {
    // 40 samples, a 100 s gap, 40 samples: periodic with 92% of the span in one gap and fewer
    // than 100 finite samples, so the score is below 0.5
    var ts = new double[80];
    var vs = new double[80];
    for (int i = 0; i < 80; i++) {
      ts[i] = (i < 40 ? i : i + 5000) * 0.02;
      vs[i] = Math.sin(i);
    }
    var log = new MockLogBuilder().setPath("/mock/low_quality.wpilog")
        .addNumericEntry("/A", ts, vs).build();
    putLogInCache(log);
    var r = call("get_statistics", log.path(), "name", "/A");
    assertTrue(r.getAsJsonObject("data_quality").get("quality_score").getAsDouble() < 0.5,
        r.getAsJsonObject("data_quality").toString());
    var warnings = r.getAsJsonArray("warnings").toString();
    assertTrue(warnings.contains("statistics in this result should be treated as preliminary"),
        warnings);
    assertTrue(warnings.contains("directly observed events"), warnings);
    assertFalse(warnings.contains("Results should be treated as preliminary"), warnings);
  }
}
