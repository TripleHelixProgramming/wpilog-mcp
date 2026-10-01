/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/** compare_poses and pose_corrections (review section 5.6) on synthetic trajectories. */
@DisplayName("pose tools")
class PoseToolsTest extends ToolTestBase {

  static final String PATH = "/test/poses.wpilog";
  static final double DT = 0.02;
  static final double V = 1.0;
  static final double W = 0.5;

  @Override
  protected void registerTools(ToolRegistry registry) {
    PoseTools.registerAll(registry);
  }

  static Map<String, Object> pose(double x, double y, double heading) {
    return Map.of("translation", Map.of("x", x, "y", y), "rotation", Map.of("value", heading));
  }

  static Map<String, Object> speeds(double vx, double vy, double omega) {
    return Map.of("vx", vx, "vy", vy, "omega", omega);
  }

  static double[] times(int n) {
    var t = new double[n];
    for (int i = 0; i < n; i++) t[i] = 1.0 + i * DT;
    return t;
  }

  /** Odometry on an arc: 1 m/s forward, turning 0.5 rad/s, from the origin at t = 1. */
  static double[] arc(double t) {
    double s = t - 1.0;
    return new double[] {V / W * Math.sin(W * s), V / W * (1 - Math.cos(W * s)), W * s};
  }

  JsonObject run(String tool, Object... keyValues) throws Exception {
    var args = new JsonObject();
    args.addProperty("path", PATH);
    for (int i = 0; i < keyValues.length; i += 2) {
      var value = keyValues[i + 1];
      if (value instanceof Number n) args.addProperty((String) keyValues[i], n);
      else args.addProperty((String) keyValues[i], value.toString());
    }
    return findTool(tool).execute(args).getAsJsonObject();
  }

  @Nested
  @DisplayName("pose_corrections")
  class Corrections {

    /**
     * The estimator's pose: odometry's increments on the arc, plus a vision correction of 0.2 m
     * in x at 3, 5, and 7 s, and a 0.1 rad heading correction at 6 s after which the estimator
     * moves along its corrected heading (odometry's increments rotated by 0.1 rad), as a real
     * estimator does.
     */
    void log(boolean withSpeeds, boolean fieldRelativeSpeeds, boolean withOdometryPose) {
      int n = 450; // 1.0 to 9.98 s
      var t = times(n);
      var estimated = new ArrayList<Map<String, Object>>();
      var odometry = new ArrayList<Map<String, Object>>();
      var chassis = new ArrayList<Map<String, Object>>();
      double ex = 0;
      double ey = 0;
      double[] previous = null;
      double previousOffset = 0;
      for (double time : t) {
        var o = arc(time);
        if (previous != null) {
          // this interval's increment, along the heading the estimator had at its start
          double dx = o[0] - previous[0];
          double dy = o[1] - previous[1];
          ex += dx * Math.cos(previousOffset) - dy * Math.sin(previousOffset);
          ey += dx * Math.sin(previousOffset) + dy * Math.cos(previousOffset);
        }
        for (double at : new double[] {3.0, 5.0, 7.0}) {
          if (Math.abs(time - at) < 1e-9) ex += 0.2;
        }
        double headingOffset = time >= 6.0 - 1e-9 ? 0.1 : 0.0;
        estimated.add(pose(ex, ey, o[2] + headingOffset));
        previous = o;
        previousOffset = headingOffset;
        // Odometry's own frame: rotated 0.3 rad and shifted
        double c = Math.cos(0.3);
        double s = Math.sin(0.3);
        odometry.add(pose(5 + o[0] * c - o[1] * s, -2 + o[0] * s + o[1] * c, o[2] + 0.3));
        double h = o[2] + headingOffset;
        chassis.add(fieldRelativeSpeeds
            ? speeds(V * Math.cos(h), V * Math.sin(h), W) : speeds(V, 0, W));
      }
      var builder = new MockLogBuilder().setPath(PATH)
          .addStructEntry("/RealOutputs/Drive/Pose", "struct:Pose2d", t, estimated);
      if (withSpeeds) {
        builder.addStructEntry("/RealOutputs/SwerveChassisSpeeds/Measured",
            "struct:ChassisSpeeds", t, chassis);
      }
      if (withOdometryPose) {
        builder.addStructEntry("/RealOutputs/Odometry/WheelOnly", "struct:Pose2d", t, odometry);
      }
      putLogInCache(builder.build());
    }

    void assertThreeCorrections(JsonObject r) {
      assertEquals("ok", r.get("status").getAsString(), r.toString());
      assertEquals(3, r.get("correction_count").getAsInt(), r.toString());
      var list = r.getAsJsonArray("corrections");
      double[] expected = {3.0, 5.0, 7.0};
      for (int i = 0; i < 3; i++) {
        var c = list.get(i).getAsJsonObject();
        assertEquals(expected[i], c.get("timestamp_sec").getAsDouble(), 1e-9);
        assertEquals(0.2, c.get("translation_m").getAsDouble(), 1e-6);
        assertEquals(0.2, c.get("dx_m").getAsDouble(), 1e-6);
        assertEquals(0.0, c.get("dy_m").getAsDouble(), 1e-6);
        // a chord over the arc, or the mean of rotating velocity vectors: short by ~1e-5
        assertEquals(V, c.get("speed_mps").getAsDouble(), 1e-4);
      }
      assertEquals(0.6, r.get("total_translation_m").getAsDouble(), 1e-5);
      var cadence = r.getAsJsonObject("correction_interval_sec");
      assertEquals(2, cadence.get("n").getAsInt());
      assertEquals(2.0, cadence.get("median").getAsDouble(), 1e-9);
      // Every other interval: odometry predicts the change exactly (to integration error)
      var residual = r.getAsJsonObject("residual_translation_m");
      assertEquals(449, residual.get("count").getAsInt());
      assertEquals(0.0, residual.get("median").getAsDouble(), 1e-6);
      assertEquals(0.2, residual.get("max").getAsDouble(), 1e-6);
    }

    @Test
    @DisplayName("robot-relative chassis speeds integrated along the heading find each "
        + "correction, its size, and their cadence")
    void robotRelativeSpeeds() throws Exception {
      log(true, false, false);
      var r = run("pose_corrections");
      assertThreeCorrections(r);
      var odometry = r.getAsJsonObject("odometry");
      assertEquals("chassis_speeds", odometry.get("source").getAsString());
      assertEquals("/RealOutputs/SwerveChassisSpeeds/Measured",
          odometry.get("entry").getAsString());
      assertEquals("robot", odometry.get("speeds_frame").getAsString());
      // The heading correction shows in the heading residual, not as a translation
      assertEquals(0.1, r.getAsJsonObject("residual_heading_rad").get("max").getAsDouble(),
          1e-6);
      assertEquals("/RealOutputs/Drive/Pose",
          r.getAsJsonObject("inputs").getAsJsonObject("entries").get("pose").getAsString());
    }

    @Test
    @DisplayName("field-relative speeds are not rotated")
    void fieldRelativeSpeeds() throws Exception {
      log(true, true, false);
      assertThreeCorrections(run("pose_corrections", "speeds_frame", "field"));
      // Read as robot-relative, field-relative speeds predict the wrong direction in every
      // interval (by up to 4 cm, under the threshold): the residual is no longer near zero
      var wrong = run("pose_corrections");
      assertTrue(wrong.getAsJsonObject("residual_translation_m").get("median").getAsDouble()
          > 0.005, wrong.toString());
      // ...and the frame check says so, with both medians
      var check = wrong.getAsJsonObject("odometry").getAsJsonObject("frame_check");
      assertTrue(check.get("field_median_m").getAsDouble() < 1e-6, check.toString());
      assertTrue(wrong.getAsJsonArray("warnings").toString()
          .contains("fit the pose better as field-relative"), wrong.toString());
      var right = run("pose_corrections", "speeds_frame", "field");
      assertFalse(right.has("warnings") && right.getAsJsonArray("warnings").toString()
          .contains("fit the pose better"), right.toString());
    }

    @Test
    @DisplayName("an odometry-only pose in its own rotated frame predicts the same changes")
    void odometryPose() throws Exception {
      log(false, false, true);
      var r = run("pose_corrections", "odometry_pose_entry", "/RealOutputs/Odometry/WheelOnly");
      assertThreeCorrections(r);
      assertEquals("odometry_pose", r.getAsJsonObject("odometry").get("source").getAsString());
    }

    @Test
    @DisplayName("heading_threshold_rad counts a heading correction")
    void headingCorrection() throws Exception {
      log(true, false, false);
      var r = run("pose_corrections", "heading_threshold_rad", 0.05);
      assertEquals(4, r.get("correction_count").getAsInt(), r.toString());
      var heading = r.getAsJsonArray("corrections").get(2).getAsJsonObject(); // 3, 5, 6, 7 s
      assertEquals(6.0, heading.get("timestamp_sec").getAsDouble(), 1e-9);
      assertEquals(0.1, heading.get("heading_rad").getAsDouble(), 1e-6);
      assertEquals(0.0, heading.get("translation_m").getAsDouble(), 1e-6);
    }

    @Test
    @DisplayName("scope, a threshold above every step, and limit")
    void scopeThresholdLimit() throws Exception {
      log(true, false, false);
      var window = run("pose_corrections", "start_time", 4.0, "end_time", 8.0);
      assertEquals(2, window.get("correction_count").getAsInt(), window.toString());
      var none = run("pose_corrections", "threshold_m", 0.5);
      assertEquals("ok", none.get("status").getAsString());
      assertEquals(0, none.get("correction_count").getAsInt());
      assertFalse(none.has("correction_interval_sec"));
      var limited = run("pose_corrections", "limit", 1);
      assertEquals(1, limited.getAsJsonArray("corrections").size());
      assertEquals(3, limited.getAsJsonObject("limits").getAsJsonObject("corrections")
          .get("total").getAsInt());
    }

    @Test
    @DisplayName("no odometry: no_match naming the parameters; bad arguments are errors")
    void noOdometry() throws Exception {
      log(false, false, false);
      var r = run("pose_corrections");
      assertEquals("no_match", r.get("status").getAsString(), r.toString());
      assertTrue(r.get("reason").getAsString().contains("chassis_speeds_entry"), r.toString());
      assertTrue(r.get("reason").getAsString().contains("odometry_pose_entry"), r.toString());
      var bad = run("pose_corrections", "odometry_pose_entry", "/RealOutputs/Nope");
      assertTrue(bad.get("error").getAsString().contains("not in this log"), bad.toString());
      var frame = run("pose_corrections", "odometry_pose_entry", "/RealOutputs/Drive/Pose",
          "speeds_frame", "sideways");
      assertTrue(frame.has("error"), frame.toString());
      var threshold = run("pose_corrections", "odometry_pose_entry", "/RealOutputs/Drive/Pose",
          "threshold_m", -1);
      assertTrue(threshold.get("error").getAsString().contains("positive"), threshold.toString());
    }

    @Test
    @DisplayName("intervals longer than max_interval_sec, and speeds that do not cover the "
        + "interval, are counted, not compared")
    void gapsAndCoverage() throws Exception {
      var t = new double[] {1.0, 1.02, 1.04, 1.5, 1.52, 1.54, 1.56};
      var p = new ArrayList<Map<String, Object>>();
      for (double time : t) p.add(pose(time - 1.0, 0, 0));
      var st = new double[] {1.0, 1.02, 1.04, 1.5, 1.52};
      var s = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < st.length; i++) s.add(speeds(1.0, 0, 0));
      putLogInCache(new MockLogBuilder().setPath(PATH)
          .addStructEntry("/RealOutputs/Drive/Pose", "struct:Pose2d", t, p)
          .addStructEntry("/Drive/ChassisSpeeds/Measured", "struct:ChassisSpeeds", st, s)
          .build());
      var r = run("pose_corrections");
      var intervals = r.getAsJsonObject("intervals");
      assertEquals(3, intervals.get("analyzed").getAsInt(), r.toString());
      assertEquals(1, intervals.get("longer_than_max").getAsInt());
      assertEquals(2, intervals.get("without_odometry").getAsInt());
      assertEquals(0, r.get("correction_count").getAsInt());
    }

    @Test
    @DisplayName("a pose that is never readable is no_match; two unconventional poses are "
        + "candidates to confirm")
    void poseResolution() throws Exception {
      var t = times(20);
      var a = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < t.length; i++) a.add(pose(i * 0.02, 0, 0));
      putLogInCache(new MockLogBuilder().setPath(PATH)
          .addStructEntry("/Foo/A", "struct:Pose2d", t, a)
          .addStructEntry("/Foo/B", "struct:Pose2d", t, a)
          .build());
      var r = run("pose_corrections");
      assertEquals("no_match", r.get("status").getAsString(), r.toString());
      assertTrue(r.get("reason").getAsString().contains("pass it as pose_entry"), r.toString());
      assertTrue(r.getAsJsonObject("robot_pose").get("needs_confirmation").getAsBoolean());
    }
  }

  @Nested
  @DisplayName("compare_poses")
  class Compare {

    /** A setpoint along a line at 30 degrees; the pose 0.1 m behind it and 0.05 m left. */
    void log() {
      int n = 200;
      var t = times(n);
      double h = Math.toRadians(30);
      double c = Math.cos(h);
      double s = Math.sin(h);
      var setpoint = new ArrayList<Map<String, Object>>();
      var actual = new ArrayList<Map<String, Object>>();
      for (double time : t) {
        double d = 2.0 * (time - 1.0);
        double x = d * c;
        double y = d * s;
        setpoint.add(pose(x, y, h));
        // behind by 0.1 along the heading, left by 0.05 across it, heading 0.02 rad more
        actual.add(pose(x - 0.1 * c - 0.05 * s, y - 0.1 * s + 0.05 * c, h + 0.02));
      }
      putLogInCache(new MockLogBuilder().setPath(PATH)
          .addStructEntry("/RealOutputs/Drive/Pose", "struct:Pose2d", t, actual)
          .addStructEntry("/PathPlanner/targetPose", "struct:Pose2d", t, setpoint)
          .build());
    }

    @Test
    @DisplayName("in the reference's frame: along and cross errors of a path follower")
    void referenceFrame() throws Exception {
      log();
      var r = run("compare_poses", "reference_entry", "/PathPlanner/targetPose",
          "frame", "reference");
      assertEquals("ok", r.get("status").getAsString(), r.toString());
      assertEquals(200, r.get("count").getAsInt());
      assertEquals(-0.1, r.getAsJsonObject("along_m").get("mean").getAsDouble(), 1e-9);
      assertEquals(0.05, r.getAsJsonObject("cross_m").get("mean").getAsDouble(), 1e-9);
      assertEquals(0.0, r.getAsJsonObject("cross_m").get("std_dev").getAsDouble(), 1e-9);
      assertEquals(Math.hypot(0.1, 0.05),
          r.getAsJsonObject("distance_m").get("rmse").getAsDouble(), 1e-9);
      assertEquals(0.02, r.getAsJsonObject("heading_difference_rad").get("mean_signed")
          .getAsDouble(), 1e-9);
      assertEquals(5, r.getAsJsonArray("largest").size());
      assertEquals("/RealOutputs/Drive/Pose", r.get("pose_entry").getAsString());
    }

    @Test
    @DisplayName("in the field frame: dx and dy")
    void fieldFrame() throws Exception {
      log();
      var r = run("compare_poses", "reference_entry", "/PathPlanner/targetPose");
      double h = Math.toRadians(30);
      assertEquals(-0.1 * Math.cos(h) - 0.05 * Math.sin(h),
          r.getAsJsonObject("dx_m").get("mean").getAsDouble(), 1e-9);
      assertEquals(-0.1 * Math.sin(h) + 0.05 * Math.cos(h),
          r.getAsJsonObject("dy_m").get("mean").getAsDouble(), 1e-9);
      assertFalse(r.has("along_m"));
    }

    @Test
    @DisplayName("headings across the +-pi seam differ by the short way")
    void headingSeam() throws Exception {
      var t = times(10);
      var a = new ArrayList<Map<String, Object>>();
      var b = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < t.length; i++) {
        a.add(pose(0, 0, -Math.PI + 0.01));
        b.add(pose(0, 0, Math.PI - 0.01));
      }
      putLogInCache(new MockLogBuilder().setPath(PATH)
          .addStructEntry("/RealOutputs/Drive/Pose", "struct:Pose2d", t, a)
          .addStructEntry("/Ref", "struct:Pose2d", t, b)
          .build());
      var r = run("compare_poses", "reference_entry", "/Ref");
      assertEquals(0.02, r.getAsJsonObject("heading_difference_rad").get("mean_signed")
          .getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("a held reference: 'previous' within max_gap_sec, unaligned beyond it")
    void heldReference() throws Exception {
      var t = times(100); // 1.0 to 2.98 s
      var a = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < t.length; i++) a.add(pose(1.0, 0, 0));
      putLogInCache(new MockLogBuilder().setPath(PATH)
          .addStructEntry("/RealOutputs/Drive/Pose", "struct:Pose2d", t, a)
          .addStructEntry("/Target", "struct:Pose2d", new double[] {1.0},
              List.of(pose(0, 0, 0)))
          .build());
      // Linear: only the record at the reference's one sample time aligns
      var linear = run("compare_poses", "reference_entry", "/Target");
      assertEquals(1, linear.get("count").getAsInt(), linear.toString());
      assertEquals(99, linear.get("unaligned").getAsInt());
      var held = run("compare_poses", "reference_entry", "/Target", "interpolation", "previous",
          "max_gap_sec", 1.0);
      assertEquals("ok", held.get("status").getAsString(), held.toString());
      assertEquals(51, held.get("count").getAsInt());
      assertEquals(49, held.get("unaligned").getAsInt());
      assertEquals(1.0, held.getAsJsonObject("distance_m").get("max").getAsDouble(), 1e-12);
    }

    @Test
    @DisplayName("errors: reference missing, not a pose, bad frame")
    void errors() throws Exception {
      log();
      assertTrue(run("compare_poses").get("error").getAsString().contains("reference_entry"));
      var missing = run("compare_poses", "reference_entry", "/Nope");
      assertTrue(missing.get("error").getAsString().contains("not in this log"));
      putLogInCache(new MockLogBuilder().setPath(PATH)
          .addStructEntry("/RealOutputs/Drive/Pose", "struct:Pose2d", times(3),
              List.of(pose(0, 0, 0), pose(0, 0, 0), pose(0, 0, 0)))
          .addNumericEntry("/Num", times(3), new double[] {1, 2, 3})
          .build());
      var notPose = run("compare_poses", "reference_entry", "/Num");
      assertTrue(notPose.get("error").getAsString().contains("struct:Pose2d"), notPose.toString());
      var frame = run("compare_poses", "reference_entry", "/RealOutputs/Drive/Pose",
          "frame", "robot");
      assertTrue(frame.get("error").getAsString().contains("frame"), frame.toString());
    }
  }
}
