/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * profile_mechanism roles (review issues A5, B6): only entries passed explicitly are analyzed;
 * a mechanism name lists the candidates.
 */
@DisplayName("profile_mechanism roles and step response")
class MechanismFixtureTest extends FixtureToolTestBase {

  @Test
  @DisplayName("fixture elevator: candidates across subtrees by name, analyzed once passed")
  void elevator() {
    var byName = call("profile_mechanism", "akit_match", "mechanism_name", "Elevator");
    assertEquals("no_match", byName.get("status").getAsString(), byName.toString());
    assertTrue(byName.get("needs_confirmation").getAsBoolean());
    var candidates = byName.getAsJsonObject("candidates");
    assertEquals("[\"/RealOutputs/Elevator/GoalMeters\"]", candidates.get("setpoint").toString());
    assertEquals("[\"/Elevator/PositionMeters\"]", candidates.get("measurement").toString());
    assertEquals("[\"/Elevator/VelocityMetersPerSec\"]", candidates.get("velocity").toString());
    assertEquals("[\"/Elevator/CurrentAmps\"]", candidates.get("current").toString());
    assertFalse(candidates.has("temperature"));

    var r = call("profile_mechanism", "akit_match", "mechanism_name", "Elevator",
        "setpoint_entry", "/RealOutputs/Elevator/GoalMeters",
        "measurement_entry", "/Elevator/PositionMeters",
        "velocity_entry", "/Elevator/VelocityMetersPerSec",
        "current_entry", "/Elevator/CurrentAmps");
    var roles = r.getAsJsonObject("roles");
    assertEquals("/RealOutputs/Elevator/GoalMeters", roles.get("setpoint").getAsString());
    assertEquals("/Elevator/PositionMeters", roles.get("measurement").getAsString());
    assertEquals("/Elevator/VelocityMetersPerSec", roles.get("velocity").getAsString());
    assertEquals("/Elevator/CurrentAmps", roles.get("current").getAsString());
    assertTrue(roles.get("temperature").isJsonNull());
    assertTrue(r.getAsJsonArray("skipped").toString().contains("temperature"));
    var fe = r.getAsJsonObject("following_error");
    assertTrue(fe.get("steps").getAsInt() > 0);
    assertTrue(fe.has("rmse"));
  }

  @Test
  @DisplayName("AdvantageKit module IO: Drive and Turn stems are not mixed (B6)")
  void stems() {
    var t = new double[] {0, 1, 2, 3};
    var log = new MockLogBuilder()
        .setPath("/test/module_io.wpilog")
        .addNumericEntry("/Drive/ModuleFrontLeft/TurnVelocityRadPerSec", t, new double[] {0, 1, 0, 1})
        .addNumericEntry("/Drive/ModuleFrontLeft/DriveVelocityRadPerSec", t, new double[] {0, 0, 0, 0})
        .addNumericEntry("/Drive/ModuleFrontLeft/TurnCurrentAmps", t, new double[] {1, 1, 1, 1})
        .addNumericEntry("/Drive/ModuleFrontLeft/DriveCurrentAmps", t, new double[] {50, 50, 50, 50})
        .addNumericEntry("/Drive/ModuleFrontLeft/DrivePositionRad", t, new double[] {0, 0, 0, 0})
        .build();
    LogManager.getInstance().testPutLog(log.path(), log);
    var args = new JsonObject();
    args.addProperty("path", log.path());
    args.addProperty("mechanism_name", "ModuleFrontLeft");
    JsonObject ambiguous;
    JsonObject r;
    try {
      ambiguous = tools.get("profile_mechanism").execute(args).getAsJsonObject();
      // A name is not decided by the server (review 6 section 3.3): both motors' entries are
      // candidates for a role
      assertEquals("no_match", ambiguous.get("status").getAsString(), ambiguous.toString());
      assertTrue(ambiguous.get("needs_confirmation").getAsBoolean());
      var candidates = ambiguous.getAsJsonObject("candidates");
      assertEquals("[\"/Drive/ModuleFrontLeft/TurnVelocityRadPerSec\","
          + "\"/Drive/ModuleFrontLeft/DriveVelocityRadPerSec\"]",
          candidates.get("velocity").toString());
      assertEquals(2, candidates.getAsJsonArray("current").size());
      assertTrue(ambiguous.get("hint").getAsString().contains("velocity_entry"));
      // A narrower name narrows the candidates; it is still not analyzed
      args.addProperty("mechanism_name", "ModuleFrontLeft/Drive");
      var narrower = tools.get("profile_mechanism").execute(args).getAsJsonObject();
      assertEquals("no_match", narrower.get("status").getAsString(), narrower.toString());
      assertEquals("[\"/Drive/ModuleFrontLeft/DriveVelocityRadPerSec\"]",
          narrower.getAsJsonObject("candidates").get("velocity").toString());
      // The drive motor's entries, passed
      args.addProperty("velocity_entry", "/Drive/ModuleFrontLeft/DriveVelocityRadPerSec");
      args.addProperty("current_entry", "/Drive/ModuleFrontLeft/DriveCurrentAmps");
      r = tools.get("profile_mechanism").execute(args).getAsJsonObject();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    assertFalse(r.has("stem"));
    var roles = r.getAsJsonObject("roles");
    assertEquals("/Drive/ModuleFrontLeft/DriveVelocityRadPerSec", roles.get("velocity").getAsString());
    assertEquals("/Drive/ModuleFrontLeft/DriveCurrentAmps", roles.get("current").getAsString());
    assertTrue(roles.get("measurement").isJsonNull(), "DrivePositionRad stays a candidate");
    assertEquals("[\"/Drive/ModuleFrontLeft/DrivePositionRad\"]",
        r.getAsJsonObject("candidates").get("measurement").toString());
    // drive current 50 A while stopped: one stall, open at the end of the data
    assertEquals(1, r.get("stall_count").getAsInt());
    var stall = r.getAsJsonArray("stall_events").get(0).getAsJsonObject();
    assertTrue(stall.get("open_at_end").getAsBoolean());
  }

  @Test
  @DisplayName("nothing matching the mechanism name: no_match")
  void noMatch() {
    var r = call("profile_mechanism", "akit_match", "mechanism_name", "Climber");
    assertEquals("no_match", r.get("status").getAsString());
  }

  @Test
  @DisplayName("a following-error window excludes samples outside it (RMSE used to ignore it)")
  void windowedRmse() {
    var t = new double[] {0, 1, 2, 3, 4};
    var log = new MockLogBuilder()
        .setPath("/test/mech_window.wpilog")
        .addNumericEntry("/Arm/Goal", t, new double[] {1, 1, 1, 1, 1})
        .addNumericEntry("/Arm/Position", t, new double[] {1, 1, 1, 9, 9})
        .build();
    LogManager.getInstance().testPutLog(log.path(), log);
    var args = new JsonObject();
    args.addProperty("path", log.path());
    args.addProperty("setpoint_entry", "/Arm/Goal");
    args.addProperty("measurement_entry", "/Arm/Position");
    args.addProperty("end_time", 2.5);
    JsonObject r;
    try {
      r = tools.get("profile_mechanism").execute(args).getAsJsonObject();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    assertEquals(0.0, r.getAsJsonObject("following_error").get("rmse").getAsDouble(), 1e-12);
  }
}
