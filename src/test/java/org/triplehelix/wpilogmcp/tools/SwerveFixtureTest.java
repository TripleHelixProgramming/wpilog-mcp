/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** analyze_swerve on the fixture corpus (review issues B5, A4). */
@DisplayName("analyze_swerve on fixture logs")
class SwerveFixtureTest extends FixtureToolTestBase {

  /** Mean |speed| of module m in the fixtures: speed = amplitude * sin(0.5 t + 0.7 m). */
  static double expectedMeanAbsSpeed(double amplitude, int m, double start, double end) {
    double sum = 0;
    int n = 0;
    for (int i = 0; ; i++) {
      double t = Math.round((start + i * 0.02) * 1e6) / 1e6;
      if (t >= end) break;
      sum += Math.abs(amplitude * Math.sin(0.5 * t + m * 0.7));
      n++;
    }
    return sum / n;
  }

  @Test
  @DisplayName("SwerveModuleState[4]: four modules, magnitudes not signed averages (B5)")
  void arrayModules() {
    var r = call("analyze_swerve", "swerve_array", "scope", "enabled");
    assertEquals("array", r.get("layout").getAsString());
    assertEquals(4, r.get("module_count").getAsInt());
    assertEquals("/RealOutputs/SwerveStates/Measured",
        r.getAsJsonObject("inputs").getAsJsonObject("entries").get("measured").getAsString());
    // SetpointsOptimized is preferred over Setpoints
    assertEquals("/RealOutputs/SwerveStates/SetpointsOptimized",
        r.getAsJsonObject("inputs").getAsJsonObject("entries").get("setpoint").getAsString());
    var modules = objects(r.getAsJsonArray("modules"));
    for (int m = 0; m < 4; m++) {
      var module = modules.get(m);
      assertEquals("module[" + m + "]", module.get("module").getAsString());
      assertEquals(m, module.get("index").getAsInt());
      double expected = expectedMeanAbsSpeed(2.0, m, 5.0, 65.0);
      assertEquals(expected, module.get("mean_abs_speed_mps").getAsDouble(), 1e-9, "module " + m);
      assertTrue(expected > 1.0, "the signed mean would be near zero; the magnitude is not");
      // measured equals SetpointsOptimized in this fixture: zero tracking error
      assertEquals(0.0, module.getAsJsonObject("speed_tracking_error").get("max_mps")
          .getAsDouble(), 1e-12);
    }
    assertEquals("front_left", modules.get(0).get("assumed_position").getAsString());
    assertTrue(r.get("module_order_note").getAsString().contains("assumption"));
  }

  @Test
  @DisplayName("drift discovery skips the one-sample trajectory and the vision pose array (A4)")
  void driftSkippedWithReason() {
    var r = call("analyze_swerve", "swerve_array");
    assertEquals("partial", r.get("status").getAsString());
    var skipped = r.getAsJsonArray("skipped").toString();
    assertTrue(skipped.contains("odometry_drift"), skipped);
    assertTrue(skipped.contains("/RealOutputs/Odometry/Robot"), "odometry candidate named");
    assertFalse(skipped.contains("Trajectory"), "a one-sample Pose2d[] is not odometry");
    assertFalse(r.has("odometry_drift"));
  }

  @Test
  @DisplayName("per-module entries: candidates until passed; module 2 slips, odometry drifts")
  void perModule() {
    // One entry per module under the team's own names is no published layout: nothing says
    // which entries are measured, so they are listed, and analyzed once passed
    var unresolved = call("analyze_swerve", "swerve_per_module", "scope", "enabled");
    assertEquals("no_match", unresolved.get("status").getAsString(), unresolved.toString());
    assertTrue(unresolved.get("needs_confirmation").getAsBoolean());
    assertEquals(8, unresolved.getAsJsonArray("candidates").size());

    var r = call("analyze_swerve", "swerve_per_module", "scope", "enabled",
        "measured_entry", "/Drive/Module2/Measured", "setpoint_entry", "/Drive/Module2/Setpoint");
    assertEquals("per_module", r.get("layout").getAsString());
    var modules = objects(r.getAsJsonArray("modules"));
    assertEquals(1, modules.size());
    var module2 = modules.stream().filter(m -> m.get("module").getAsString().equals("Module2"))
        .findFirst().orElseThrow();
    // measured = 0.7 * setpoint, so the tracking error is 0.3 * |setpoint|
    double expected = 0.3 * expectedMeanAbsSpeed(2.0, 2, 5.0, 65.0);
    assertEquals(expected,
        module2.getAsJsonObject("speed_tracking_error").get("mean_mps").getAsDouble(), 1e-9);
    var drift = r.getAsJsonObject("odometry_drift");
    assertEquals("/Odometry/Robot", drift.get("odometry_entry").getAsString());
    assertEquals("/Vision/EstimatedPose", drift.get("vision_entry").getAsString());
    assertTrue(drift.get("max_error_m").getAsDouble() > 0.5);
  }

  @Test
  @DisplayName("scope 'enabled' excludes disabled samples; a log without DS data rejects it")
  void scopes() {
    var all = call("analyze_swerve", "swerve_array");
    var enabled = call("analyze_swerve", "swerve_array", "scope", "enabled");
    int allSamples = objects(all.getAsJsonArray("modules")).get(0).get("samples").getAsInt();
    int enabledSamples = objects(enabled.getAsJsonArray("modules")).get(0).get("samples")
        .getAsInt();
    assertEquals(3000, enabledSamples); // 5 s to 65 s at 50 Hz
    assertEquals(3001, allSamples); // plus the single disabled sample logged at 1 s
    var noDs = call("analyze_swerve", "no_ds", "scope", "enabled");
    assertEquals("error", noDs.get("status").getAsString());
  }

  @Test
  @DisplayName("an explicit entry of the wrong type is an error naming its type")
  void wrongExplicitType() {
    var r = call("analyze_swerve", "swerve_array", "measured_entry",
        "/RealOutputs/Odometry/Robot");
    assertEquals("error", r.get("status").getAsString());
    assertTrue(r.get("error").getAsString().contains("struct:Pose2d"));
  }

  @Test
  @DisplayName("a wrong odometry_entry or vision_entry is an error naming that parameter")
  void wrongPoseEntryIsError() {
    // Whoever names an entry wants the drift, so a wrong one is not just a skipped section, as
    // with measured_entry. The robot pose resolver used to name the parameter pose_entry.
    for (var param : java.util.List.of("odometry_entry", "vision_entry")) {
      for (var bad : java.util.List.of("/No/Such/Pose", "/RealOutputs/SwerveStates/Measured")) {
        var r = call("analyze_swerve", "swerve_per_module", param, bad);
        assertEquals("error", r.get("status").getAsString(), param + " " + bad + ": " + r);
        var error = r.get("error").getAsString();
        assertTrue(error.contains(param + " " + bad), error);
        assertFalse(error.contains("pose_entry"), error);
      }
    }
  }

  @Test
  @DisplayName("right odometry_entry and vision_entry give the drift; none found is still a skip")
  void explicitPoseEntries() {
    var r = call("analyze_swerve", "swerve_per_module", "odometry_entry", "/Odometry/Robot",
        "vision_entry", "/Vision/EstimatedPose", "measured_entry", "/Drive/Module0/Measured");
    var drift = r.getAsJsonObject("odometry_drift");
    assertEquals("/Odometry/Robot", drift.get("odometry_entry").getAsString(), r.toString());
    assertEquals("/Vision/EstimatedPose", drift.get("vision_entry").getAsString());
    // Without them, a log with no usable vision pose skips the drift (driftSkippedWithReason)
    assertEquals("partial", call("analyze_swerve", "swerve_array").get("status").getAsString());
  }

  @Test
  @DisplayName("without module states, looked_for names every published naming and setpoint word")
  void lookedForNamesWhatIsRecognized() {
    var r = call("analyze_swerve", "wpilib_dlm");
    assertEquals("no_match", r.get("status").getAsString(), r.toString());
    assertFalse(r.has("needs_confirmation"), "nothing to confirm: " + r);
    var lookedFor = r.get("looked_for").toString();
    for (var convention : SignalResolver.MODULE_STATE_CONVENTIONS) {
      assertTrue(lookedFor.contains(convention.measured()), convention + ": " + lookedFor);
      assertTrue(lookedFor.contains(convention.setpoints().get(0)), convention + ": " + lookedFor);
    }
    // The words that keep a lone entry from being taken for the measured states
    for (var word : RobotAnalysisTools.AnalyzeSwerveTool.SETPOINT_WORD_LIST) {
      assertTrue(lookedFor.contains(word), word + " missing from " + lookedFor);
    }
  }
}
