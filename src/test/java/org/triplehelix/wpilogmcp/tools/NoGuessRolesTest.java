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
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * The remaining places that chose entries by name alone now go through the resolver, list
 * candidates, and never guess (review 6, sections 3.3, 3.4, 3.6).
 */
class NoGuessRolesTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    WpilogTools.registerAll(registry);
  }

  private JsonObject call(String tool, String path, Object... keyValues) throws Exception {
    var args = new JsonObject();
    args.addProperty("path", path);
    for (int i = 0; i < keyValues.length; i += 2) {
      args.addProperty((String) keyValues[i], keyValues[i + 1].toString());
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

  private static Map<String, Object> state(double speed, double angle) {
    var a = new LinkedHashMap<String, Object>();
    a.put("value", angle);
    var s = new LinkedHashMap<String, Object>();
    s.put("speed", speed);
    s.put("angle", a);
    return s;
  }

  /** A swerve log whose first Pose2d is a trajectory setpoint 5 m from the robot. */
  private MockLogBuilder swerveWithSetpointFirst(String path) {
    var b = new MockLogBuilder().setPath(path);
    int n = 500;
    var ts = new double[n];
    var setpoint = new ArrayList<Map<String, Object>>();
    var robot = new ArrayList<Map<String, Object>>();
    var vision = new ArrayList<Map<String, Object>>();
    var states = new ArrayList<TimestampedValue>();
    for (int i = 0; i < n; i++) {
      ts[i] = i * 0.02;
      setpoint.add(pose(i * 0.02 + 5.0, 3.0, 0));
      robot.add(pose(i * 0.02, 0.0, 0));
      vision.add(pose(i * 0.02 + 0.1, 0.0, 0));
      states.add(new TimestampedValue(ts[i], List.of(state(1.0, 0), state(1.0, 0),
          state(1.0, 0), state(1.0, 0))));
    }
    b.addStructEntry("/RealOutputs/Odometry/TrajectorySetpoint", "struct:Pose2d", ts, setpoint);
    b.addStructEntry("/RealOutputs/Odometry/Robot", "struct:Pose2d", ts, robot);
    b.addStructEntry("/RealOutputs/Vision/Pose", "struct:Pose2d", ts, vision);
    b.addEntry("/RealOutputs/SwerveStates/Measured", "struct:SwerveModuleState[]", states);
    return b;
  }

  @Test
  @DisplayName("swerve drift uses the robot_pose role, not the first name containing 'odometry'")
  void driftUsesTheRobotPoseRole() throws Exception {
    var log = swerveWithSetpointFirst("/mock/drift_roles.wpilog").build();
    putLogInCache(log);
    var r = call("analyze_swerve", log.path());
    var drift = r.getAsJsonObject("odometry_drift");
    assertNotNull(drift, r.toString());
    assertEquals("/RealOutputs/Odometry/Robot", drift.get("odometry_entry").getAsString());
    assertTrue(drift.get("odometry_basis").getAsString().contains("convention"));
    assertEquals("/RealOutputs/Vision/Pose", drift.get("vision_entry").getAsString());
    assertEquals(0.1, drift.get("avg_error_m").getAsDouble(), 1e-9,
        "the drift is against the robot pose, not the 5 m distant setpoint");
  }

  @Test
  @DisplayName("several vision poses are candidates to confirm, and drift is skipped")
  void severalVisionPosesAreNotGuessed() throws Exception {
    var b = swerveWithSetpointFirst("/mock/drift_two_vision.wpilog");
    var ts = new double[500];
    var other = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 500; i++) {
      ts[i] = i * 0.02;
      other.add(pose(i * 0.02 + 0.2, 0.0, 0));
    }
    b.addStructEntry("/RealOutputs/Vision/Rejected", "struct:Pose2d", ts, other);
    var log = b.build();
    putLogInCache(log);
    var r = call("analyze_swerve", log.path());
    assertFalse(r.has("odometry_drift"));
    var skipped = r.getAsJsonArray("skipped").toString();
    assertTrue(skipped.contains("/RealOutputs/Vision/Pose") && skipped.contains("/RealOutputs/Vision/Rejected"), skipped);
    assertTrue(skipped.contains("vision_entry") && skipped.contains("does not guess"), skipped);
    // Passing one resolves it
    var explicit = call("analyze_swerve", log.path(), "vision_entry", "/RealOutputs/Vision/Rejected");
    assertEquals("/RealOutputs/Vision/Rejected",
        explicit.getAsJsonObject("odometry_drift").get("vision_entry").getAsString());
    var roles = call("resolve_signals", log.path()).getAsJsonObject("roles");
    var visionRole = roles.getAsJsonObject("vision_pose");
    assertEquals("heuristic", visionRole.get("match").getAsString());
    assertTrue(visionRole.get("needs_confirmation").getAsBoolean());
    assertEquals("convention", roles.getAsJsonObject("robot_pose").get("match").getAsString());
  }

  @Test
  @DisplayName("with explicit role entries, the name's other matches are listed, not used")
  void explicitEntriesAndOtherMatches() throws Exception {
    var ts = new double[] {0, 1, 2, 3};
    var log = new MockLogBuilder().setPath("/mock/explicit_stems.wpilog")
        .addNumericEntry("/Drive/ModuleFrontLeft/TurnVelocityRadPerSec", ts, new double[] {0, 1, 0, 1})
        .addNumericEntry("/Drive/ModuleFrontLeft/DriveVelocityRadPerSec", ts, new double[] {0, 0, 0, 0})
        .addNumericEntry("/Drive/ModuleFrontLeft/TurnCurrentAmps", ts, new double[] {1, 1, 1, 1})
        .addNumericEntry("/Drive/ModuleFrontLeft/DriveCurrentAmps", ts, new double[] {50, 50, 50, 50})
        .build();
    putLogInCache(log);
    var r = call("profile_mechanism", log.path(), "mechanism_name", "ModuleFrontLeft",
        "velocity_entry", "/Drive/ModuleFrontLeft/DriveVelocityRadPerSec",
        "current_entry", "/Drive/ModuleFrontLeft/DriveCurrentAmps");
    assertTrue(r.get("success").getAsBoolean(), r.toString());
    var roles = r.getAsJsonObject("roles");
    assertEquals("/Drive/ModuleFrontLeft/DriveCurrentAmps", roles.get("current").getAsString());
    assertTrue(roles.get("setpoint").isJsonNull(), "no role is filled from a name");
    assertFalse(r.has("stem") || r.has("other_stems"));
    assertFalse(r.has("candidates"), "both roles the name matches were passed: " + r);
    assertEquals(1, r.get("stall_count").getAsInt());

    // One role passed: the other's matches are candidates, and its section is skipped
    var partial = call("profile_mechanism", log.path(), "mechanism_name", "ModuleFrontLeft",
        "velocity_entry", "/Drive/ModuleFrontLeft/DriveVelocityRadPerSec");
    assertTrue(partial.getAsJsonObject("roles").get("current").isJsonNull(), partial.toString());
    assertEquals(2, partial.getAsJsonObject("candidates").getAsJsonArray("current").size());
    assertFalse(partial.has("stall_count"));
    var skipped = partial.getAsJsonArray("skipped").toString();
    assertTrue(skipped.contains("current_entry was not passed")
        && skipped.contains("/Drive/ModuleFrontLeft/TurnCurrentAmps"), skipped);
  }

  @Test
  @DisplayName("a team number in the file name is not a season")
  void teamNumberIsNotASeason() throws Exception {
    var log = new MockLogBuilder().setPath("/mock/team2056_practice.wpilog")
        .addNumericEntry("/A", new double[]{0, 1}, new double[]{1, 2}).build();
    var season = MatchTimeline.seasonOf(log);
    assertEquals("current_year", season.basis());
    assertEquals(java.time.Year.now().getValue(), season.year());
    var dated = new MockLogBuilder().setPath("/mock/team2056_2025_practice.wpilog")
        .addNumericEntry("/A", new double[]{0, 1}, new double[]{1, 2}).build();
    assertEquals(2025, MatchTimeline.seasonOf(dated).year());
    assertEquals("file_name", MatchTimeline.seasonOf(dated).basis());
  }

  @Test
  @DisplayName("an explicit /Timestamp entry still derives loop periods")
  void explicitTimestampDerivesPeriods() throws Exception {
    var b = new MockLogBuilder().setPath("/mock/explicit_timestamp.wpilog");
    var ts = new double[200];
    var micros = new ArrayList<TimestampedValue>();
    for (int i = 0; i < 200; i++) {
      ts[i] = 100.0 + i * 0.02;
      micros.add(new TimestampedValue(ts[i], (long) (ts[i] * 1_000_000)));
    }
    b.addEntry("/Timestamp", "int64", micros);
    var log = b.build();
    putLogInCache(log);
    var r = call("analyze_loop_timing", log.path(), "entry", "/Timestamp");
    assertTrue(r.get("success").getAsBoolean(), r.toString());
    assertTrue(r.getAsJsonObject("unit").get("basis").getAsString().startsWith("derived"), r.toString());
    assertEquals(20.0, r.getAsJsonObject("statistics").get("median_ms").getAsDouble(), 1e-6);
  }
}
