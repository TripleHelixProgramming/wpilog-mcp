/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** resolve_signals: one mapping of roles to entries, the same one the tools use (review 5.3). */
@DisplayName("resolve_signals")
class ResolveSignalsTest extends FixtureToolTestBase {

  static JsonObject role(JsonObject result, String role) {
    return result.getAsJsonObject("roles").getAsJsonObject(role);
  }

  static List<String> strings(JsonArray array) {
    var out = new ArrayList<String>();
    array.forEach(e -> out.add(e.getAsString()));
    return out;
  }

  /** The entries a role resolved to, whether one ("entry") or several ("entries"). */
  static List<String> entries(JsonObject role) {
    if (role.has("entries")) return strings(role.getAsJsonArray("entries"));
    return role.get("entry").isJsonNull() ? List.of() : List.of(role.get("entry").getAsString());
  }

  @Test
  @DisplayName("an AdvantageKit match log: every role, with basis and candidates")
  void akitMatch() {
    var r = call("resolve_signals", "akit_match");
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    assertEquals("/DriverStation/Enabled", role(r, "robot_enabled").get("entry").getAsString());
    assertEquals("/DriverStation/Autonomous", role(r, "autonomous").get("entry").getAsString());
    var battery = role(r, "battery_voltage");
    assertEquals("/SystemStats/BatteryVoltage", battery.get("entry").getAsString());
    assertEquals("convention", battery.get("match").getAsString());
    assertTrue(strings(battery.getAsJsonArray("candidates")).contains(
        "/PowerDistribution/Voltage"), battery.toString());
    assertFalse(strings(battery.getAsJsonArray("candidates")).contains(
        "/SystemStats/5vRail/Voltage"), "a rail is never a battery-voltage candidate");
    assertEquals("/RealOutputs/LoggedRobot/FullCycleMS",
        role(r, "loop_time_full").get("entry").getAsString());
    assertEquals("/RealOutputs/LoggedRobot/UserCodeMS",
        role(r, "loop_time_user").get("entry").getAsString());
    assertEquals("/RealOutputs/Drive/Pose", role(r, "robot_pose").get("entry").getAsString());
    assertEquals("/RealOutputs/SwerveStates/Measured",
        role(r, "module_states_measured").get("entry").getAsString());
    assertEquals("/RealOutputs/SwerveStates/Setpoints",
        role(r, "module_states_setpoint").get("entry").getAsString());
    assertEquals("/RealOutputs/SwerveChassisSpeeds/Measured",
        role(r, "chassis_speeds_measured").get("entry").getAsString());
    assertEquals("/Drive/Gyro/YawPosition", role(r, "gyro_yaw").get("entry").getAsString());
    assertTrue(role(r, "gyro_yaw").get("basis").getAsString()
        .contains("/Drive/Gyro/YawPosition.value"));
    assertTrue(entries(role(r, "console_text")).contains("/RealOutputs/Console"));
    assertTrue(entries(role(r, "alerts")).contains("/RealOutputs/Alerts/errors"));
    var threshold = role(r, "brownout_threshold");
    assertTrue(threshold.has("value"), threshold.toString());
    assertTrue(strings(r.getAsJsonArray("unresolved")).contains("chassis_speeds_setpoint"));
    for (var name : r.getAsJsonObject("roles").keySet()) {
      assertTrue(role(r, name).has("basis") && role(r, name).has("used_by"), name);
    }
  }

  @Test
  @DisplayName("the module-state roles are analyze_swerve's entries, with the convention named")
  void moduleStatesAreTheToolsChoice() {
    var r = call("resolve_signals", "swerve_array");
    var swerve = call("analyze_swerve", "swerve_array")
        .getAsJsonObject("inputs").getAsJsonObject("entries");
    var measured = role(r, "module_states_measured");
    var setpoint = role(r, "module_states_setpoint");
    assertEquals(swerve.get("measured").getAsString(), measured.get("entry").getAsString());
    assertEquals(swerve.get("setpoint").getAsString(), setpoint.get("entry").getAsString());
    assertEquals("/RealOutputs/SwerveStates/SetpointsOptimized",
        setpoint.get("entry").getAsString());
    for (var role : List.of(measured, setpoint)) {
      assertEquals("convention", role.get("match").getAsString(), role.toString());
      assertTrue(role.get("basis").getAsString().contains("AdvantageKit"), role.toString());
    }
    // A layout no library publishes: one entry per module, named by the team
    var perModule = role(call("resolve_signals", "swerve_per_module"), "module_states_measured");
    assertEquals("heuristic", perModule.get("match").getAsString(), perModule.toString());
    assertTrue(perModule.get("needs_confirmation").getAsBoolean());
  }

  @Test
  @DisplayName("the mapping is the tools' own choice")
  void matchesTools() {
    var roles = call("resolve_signals", "akit_match");
    var power = call("power_analysis", "akit_match");
    assertEquals(role(roles, "battery_voltage").get("entry").getAsString(),
        power.getAsJsonObject("inputs").getAsJsonObject("entries").get("voltage").getAsString(),
        power.toString());
    var loop = call("analyze_loop_timing", "akit_match");
    assertEquals(role(roles, "loop_time_full").get("entry").getAsString(),
        loop.get("loop_time_entry").getAsString());
    var vision = call("resolve_signals", "vision_photon_akit");
    var analyzed = call("analyze_vision", "vision_photon_akit");
    assertEquals(role(vision, "robot_pose").get("entry").getAsString(),
        analyzed.getAsJsonObject("inputs").getAsJsonObject("entries").get("robot_pose")
            .getAsString());
    var streams = new ArrayList<String>();
    analyzed.getAsJsonArray("observation_streams")
        .forEach(s -> streams.add(s.getAsJsonObject().get("entry").getAsString()));
    assertEquals(streams, strings(role(vision, "vision_pose_observations")
        .getAsJsonArray("entries")));
  }

  @Test
  @DisplayName("plain WPILib logs resolve DS: entries; two DriverStation sources are ambiguous")
  void driverStationSources() {
    var dlm = call("resolve_signals", "wpilib_dlm", "roles", strings("robot_enabled"));
    assertEquals("DS:enabled", role(dlm, "robot_enabled").get("entry").getAsString());
    assertEquals(1, dlm.getAsJsonObject("roles").size());
    var dual = call("resolve_signals", "dual_ds", "roles", strings("robot_enabled"));
    assertTrue(role(dual, "robot_enabled").get("ambiguous").getAsBoolean(), dual.toString());
    assertTrue(dual.getAsJsonArray("warnings").get(0).getAsString().startsWith("robot_enabled:"));
  }

  @Test
  @DisplayName("CAN buses, vision targets, and a log without roles")
  void otherRoles() {
    var can = call("resolve_signals", "canivore", "roles", strings("can_bus"));
    assertFalse(entries(role(can, "can_bus")).isEmpty(), can.toString());
    var vision = call("resolve_signals", "vision_photon_akit", "roles",
        strings("vision_targets", "vision_pose_observations"));
    assertEquals(2, role(vision, "vision_targets").getAsJsonArray("entries").size());
    var empty = call("resolve_signals", "no_ds", "roles", strings("robot_enabled"));
    assertTrue(role(empty, "robot_enabled").get("entry").isJsonNull());
    assertEquals("robot_enabled", empty.getAsJsonArray("unresolved").get(0).getAsString());
  }

  @Test
  @DisplayName("an unknown role is an error listing the roles")
  void unknownRole() {
    var r = call("resolve_signals", "akit_match", "roles", strings("shooter_speed"));
    assertEquals("error", r.get("status").getAsString());
    assertTrue(r.get("error").getAsString().contains("battery_voltage"), r.toString());
  }

  static JsonArray strings(String... values) {
    var array = new JsonArray();
    for (var v : values) array.add(v);
    return array;
  }
}
