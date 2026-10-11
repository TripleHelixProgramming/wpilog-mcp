/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

/** resolve_signals on a log that follows no convention: candidates to confirm, no guesses. */
@DisplayName("resolve_signals does not guess")
class ResolveSignalsNoGuessTest {
  private MockLogAdmission logs;

  @Test void unrelatedParentsDoNotTurnSuggestiveLeavesIntoConventions() {
    var log = new MockLogBuilder()
        .addNumericEntry("/Arm/TotalCurrent", new double[]{0, 1}, new double[]{1, 2})
        .addNumericEntry("/Camera/FullCycleMS", new double[]{0, 1}, new double[]{1, 2})
        .addNumericEntry("/Camera/UserCodeMS", new double[]{0, 1}, new double[]{1, 2}).build();
    assertTrue(SignalResolver.totalCurrent(log, null).entries().isEmpty());
    assertTrue(SignalResolver.loopTime(log, SignalResolver.Role.LOOP_TIME_FULL, null).entries().isEmpty());
    assertTrue(SignalResolver.loopTime(log, SignalResolver.Role.LOOP_TIME_USER, null).entries().isEmpty());
  }

  @Test void aMeasuredWordDoesNotSelectAmongChassisStreams() {
    var log = new MockLogBuilder()
        .addEntry("/Camera/Measured", "struct:ChassisSpeeds",
            java.util.List.of(new org.triplehelix.wpilogmcp.log.TimestampedValue(0, java.util.Map.of())))
        .addEntry("/Other/Velocity", "struct:ChassisSpeeds",
            java.util.List.of(new org.triplehelix.wpilogmcp.log.TimestampedValue(0, java.util.Map.of())))
        .build();
    assertTrue(SignalResolver.resolve(log, SignalResolver.Role.CHASSIS_SPEEDS_MEASURED).entries().isEmpty());
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "CHASSIS_SPEEDS_MEASURED, /ReplayOutputs/SwerveChassisSpeeds/Measured",
      "CHASSIS_SPEEDS_SETPOINT, /ReplayOutputs/SwerveChassisSpeeds/Setpoints",
      "CHASSIS_SPEEDS_MEASURED, /SmartDashboard/swerve/advantagescope/measuredChassisSpeeds",
      "CHASSIS_SPEEDS_SETPOINT, /SmartDashboard/swerve/advantagescope/desiredChassisSpeeds"
  })
  void publishedReplayAndYagslPathsRetainTheirRoles(SignalResolver.Role role, String path) {
    for (String wrapper : java.util.List.of("", "NT:", "/AdvantageKit", "NT:/AdvantageKit")) {
      String name = wrapper + path;
      var builder = new MockLogBuilder();
      if (role.name().startsWith("LOOP")) {
        builder.addNumericEntry(name, new double[]{0, 1}, new double[]{1, 2});
      } else {
        var values = java.util.List.of(new org.triplehelix.wpilogmcp.log.TimestampedValue(
            0, java.util.Map.of("vx", 1.0, "vy", 0.0, "omega", 0.0)));
        builder.addEntry(name, "struct:ChassisSpeeds", values);
        // A second stream prevents the type-only fallback from disguising a missing convention.
        builder.addEntry("/Unassigned/Velocity", "struct:ChassisSpeeds", values);
      }
      var resolved = SignalResolver.resolve(builder.build(), role);
      assertEquals(SignalResolver.Tier.CONVENTION, resolved.tier(), name);
      assertEquals(java.util.List.of(name), resolved.entries(), name);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "LOOP_TIME_FULL, FullCycleMS",
      "LOOP_TIME_USER, UserCodeMS"
  })
  void robotLoopTimingIsUnambiguousBesideReplayHostTiming(SignalResolver.Role role, String leaf) {
    for (String wrapper : java.util.List.of("", "NT:", "/AdvantageKit", "NT:/AdvantageKit")) {
      String robot = wrapper + "/RealOutputs/LoggedRobot/" + leaf;
      String replay = wrapper + "/ReplayOutputs/LoggedRobot/" + leaf;
      for (var order : java.util.List.of(java.util.List.of(replay, robot), java.util.List.of(robot, replay))) {
        var builder = new MockLogBuilder();
        for (String name : order) builder.addNumericEntry(name, new double[]{0, 1}, new double[]{1, 2});
        var resolved = SignalResolver.resolve(builder.build(), role);
        assertFalse(resolved.ambiguous(), "Replay-host timing must not tie the robot's timing: " + order);
        assertEquals(java.util.List.of(robot), resolved.entries());
        assertEquals(SignalResolver.Tier.CONVENTION, resolved.tier());
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "LOOP_TIME_FULL, FullCycleMS",
      "LOOP_TIME_USER, UserCodeMS"
  })
  void replayHostTimingAloneNeedsConfirmation(SignalResolver.Role role, String leaf) {
    for (String wrapper : java.util.List.of("", "NT:", "/AdvantageKit", "NT:/AdvantageKit")) {
      String replay = wrapper + "/ReplayOutputs/LoggedRobot/" + leaf;
      var log = new MockLogBuilder()
          .addNumericEntry(replay, new double[]{0, 1}, new double[]{1, 2}).build();
      var resolved = SignalResolver.resolve(log, role);
      assertTrue(resolved.entries().isEmpty(), "Replay-host timing is not the robot's loop time");
      assertEquals(SignalResolver.Tier.HEURISTIC, resolved.tier());
      assertTrue(resolved.needsConfirmation());
      assertEquals(java.util.List.of(replay), resolved.candidates());
      assertEquals(java.util.List.of(replay), SignalResolver.loopTime(log, role, replay).entries(),
          "An explicit choice can still analyze replay-host timing");
    }
  }

  @BeforeEach void admitMocks() { logs = new MockLogAdmission(); }

  @AfterEach
  void unload() {
    logs.close();
  }

  @Test
  @DisplayName("name-only matches: match heuristic, no entry, needs_confirmation, candidates")
  void heuristicRoles() throws Exception {
    var log = new MockLogBuilder()
        .setPath("/test/no_conventions.wpilog")
        .addNumericEntry("/Power/InputVoltage", new double[]{0, 1}, new double[]{12.4, 12.2})
        .addNumericEntry("/Robot/LoopTimeSec", new double[]{0, 1}, new double[]{0.02, 0.021})
        .build();
    logs.put(log);

    var args = new JsonObject();
    args.addProperty("path", log.path());
    var r = new CoreTools.ResolveSignalsTool().execute(args).getAsJsonObject();

    var battery = r.getAsJsonObject("roles").getAsJsonObject("battery_voltage");
    assertTrue(battery.get("entry").isJsonNull(), battery.toString());
    assertEquals("heuristic", battery.get("match").getAsString());
    assertTrue(battery.get("needs_confirmation").getAsBoolean());
    assertEquals("/Power/InputVoltage", battery.getAsJsonArray("candidates").get(0).getAsString());
    var loop = r.getAsJsonObject("roles").getAsJsonObject("loop_time_full");
    assertEquals("/Robot/LoopTimeSec", loop.getAsJsonArray("candidates").get(0).getAsString());
    var confirm = r.getAsJsonArray("needs_confirmation").toString();
    assertTrue(confirm.contains("battery_voltage") && confirm.contains("loop_time_full"), confirm);
  }
}
