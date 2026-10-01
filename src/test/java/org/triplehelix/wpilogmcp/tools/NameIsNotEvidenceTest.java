/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * A word in an entry's name is not evidence of what the entry holds. analyze_swerve,
 * profile_mechanism, and analyze_vision used to pick entries by such words ("desired" and
 * "target" for setpoints, "position" and "current" for a mechanism's roles, "hastarget" and
 * "latency" for vision): they now use an entry only when it is passed explicitly, follows a
 * published convention, or is the only entry of its type, and list the others as candidates.
 *
 * <p>The names here are made up. The patterns are from real logs: PhotonVision's "targetYaw" (a
 * camera reading, which the mechanism tool took for a setpoint), names where "current" means
 * present, and module-state arrays under a team's own names, sometimes two target arrays beside the
 * measured states.
 */
class NameIsNotEvidenceTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    WpilogTools.registerAll(registry);
  }

  private JsonObject call(String tool, String path, Object... keyValues) throws Exception {
    var args = new JsonObject();
    args.addProperty("path", path);
    for (int i = 0; i < keyValues.length; i += 2) {
      var value = keyValues[i + 1];
      if (value instanceof JsonArray a) args.add((String) keyValues[i], a);
      else if (value instanceof Number n) args.addProperty((String) keyValues[i], n);
      else args.addProperty((String) keyValues[i], value.toString());
    }
    return findTool(tool).execute(args).getAsJsonObject();
  }

  private static String entry(JsonObject result, String role) {
    var entries = result.getAsJsonObject("inputs").getAsJsonObject("entries");
    return entries.has(role) ? entries.get(role).getAsString() : null;
  }

  private static String text(JsonObject result, String key) {
    return result.has(key) ? result.get(key).toString() : "";
  }

  // ==================== swerve ====================

  private static Map<String, Object> state(double speed) {
    var a = new LinkedHashMap<String, Object>();
    a.put("value", 0.0);
    var s = new LinkedHashMap<String, Object>();
    s.put("speed", speed);
    s.put("angle", a);
    return s;
  }

  /** 200 records of four module states at 50 Hz, every module at {@code speed}. */
  private static List<TimestampedValue> states(double speed) {
    var out = new ArrayList<TimestampedValue>();
    for (int i = 0; i < 200; i++) {
      out.add(new TimestampedValue(i * 0.02,
          List.of(state(speed), state(speed), state(speed), state(speed))));
    }
    return out;
  }

  /** One module's state per record. */
  private static List<TimestampedValue> oneModule(double speed) {
    var out = new ArrayList<TimestampedValue>();
    for (int i = 0; i < 200; i++) out.add(new TimestampedValue(i * 0.02, state(speed)));
    return out;
  }

  private static final String STATES = "struct:SwerveModuleState[]";

  @Nested
  @DisplayName("analyze_swerve: measured and setpoint module states")
  class Swerve {

    @Test
    @DisplayName("the published conventions resolve, each paired within its own table")
    void conventions() throws Exception {
      record Case(String source, String measured, String setpoint, List<String> others) {}
      var cases = List.of(
          new Case("AdvantageKit", "/RealOutputs/SwerveStates/Measured",
              "/RealOutputs/SwerveStates/SetpointsOptimized",
              List.of("/RealOutputs/SwerveStates/Setpoints")),
          new Case("AdvantageKit", "/RealOutputs/SwerveStates/Measured",
              "/RealOutputs/SwerveStates/Setpoints", List.of()),
          new Case("CTRE", "NT:/DriveState/ModuleStates", "NT:/DriveState/ModuleTargets",
              List.of()),
          new Case("YAGSL", "NT:/SmartDashboard/swerve/advantagescope/currentStates",
              "NT:/SmartDashboard/swerve/advantagescope/desiredStates", List.of()));
      int k = 0;
      for (var c : cases) {
        var b = new MockLogBuilder().setPath("/mock/convention_" + (k++) + ".wpilog");
        // Setpoints declared first: nothing depends on the order
        for (var other : c.others()) b.addEntry(other, STATES, states(1.5));
        b.addEntry(c.setpoint(), STATES, states(1.25)).addEntry(c.measured(), STATES, states(1.0));
        var log = b.build();
        putLogInCache(log);
        var r = call("analyze_swerve", log.path());
        assertTrue(r.get("success").getAsBoolean(), r.toString());
        assertEquals(c.measured(), entry(r, "measured"), c.toString());
        assertEquals(c.setpoint(), entry(r, "setpoint"), c.toString());
        assertTrue(r.get("measured_basis").getAsString().contains(c.source()), r.toString());
        assertTrue(r.get("setpoint_basis").getAsString().contains(c.source()), r.toString());
        var module = r.getAsJsonArray("modules").get(0).getAsJsonObject();
        assertEquals(0.25, module.getAsJsonObject("speed_tracking_error").get("mean_mps")
            .getAsDouble(), 1e-12);
        var roles = call("resolve_signals", log.path()).getAsJsonObject("roles");
        assertEquals("convention",
            roles.getAsJsonObject("module_states_measured").get("match").getAsString());
        assertEquals(c.setpoint(),
            roles.getAsJsonObject("module_states_setpoint").get("entry").getAsString());
      }
    }

    @Test
    @DisplayName("arrays under a team's own names are candidates, however they are worded")
    void unconventionalNames() throws Exception {
      var log = new MockLogBuilder().setPath("/mock/own_names.wpilog")
          .addEntry("/Chassis/Modules/Actual", STATES, states(1.0))
          .addEntry("/Chassis/Modules/TargetBefore", STATES, states(1.5))
          .addEntry("/Chassis/Modules/TargetAfter", STATES, states(2.0))
          .build();
      putLogInCache(log);
      var r = call("analyze_swerve", log.path());
      assertEquals("no_match", r.get("status").getAsString(), r.toString());
      assertTrue(r.get("needs_confirmation").getAsBoolean(), r.toString());
      var candidates = r.getAsJsonArray("candidates").toString();
      for (var name : List.of("/Chassis/Modules/Actual", "/Chassis/Modules/TargetBefore",
          "/Chassis/Modules/TargetAfter")) {
        assertTrue(candidates.contains(name), candidates);
      }
      assertFalse(r.has("modules"), "nothing is analyzed from a guess");
      var hint = r.get("hint").getAsString();
      assertTrue(hint.contains("measured_entry") && hint.contains("setpoint_entry"), hint);
      assertTrue(hint.contains("source code"), hint);

      // Passed explicitly, they are used: which target is the caller's decision
      var explicit = call("analyze_swerve", log.path(), "measured_entry",
          "/Chassis/Modules/Actual", "setpoint_entry", "/Chassis/Modules/TargetAfter");
      assertEquals("/Chassis/Modules/TargetAfter", entry(explicit, "setpoint"));
      assertEquals(1.0, explicit.getAsJsonArray("modules").get(0).getAsJsonObject()
          .getAsJsonObject("speed_tracking_error").get("mean_mps").getAsDouble(), 1e-12);

      // The measured entry alone: the setpoint is not picked by its name
      var measuredOnly = call("analyze_swerve", log.path(), "measured_entry",
          "/Chassis/Modules/Actual");
      assertNull(entry(measuredOnly, "setpoint"), measuredOnly.toString());
      var skipped = text(measuredOnly, "skipped");
      assertTrue(skipped.contains("/Chassis/Modules/TargetBefore")
          && skipped.contains("/Chassis/Modules/TargetAfter"), skipped);
      assertTrue(skipped.contains("setpoint_entry") && skipped.contains("does not guess"),
          skipped);

      var roles = call("resolve_signals", log.path()).getAsJsonObject("roles");
      var measured = roles.getAsJsonObject("module_states_measured");
      assertEquals("heuristic", measured.get("match").getAsString(), measured.toString());
      assertTrue(measured.get("needs_confirmation").getAsBoolean());
      assertTrue(measured.get("entry").isJsonNull());
    }

    @Test
    @DisplayName("a setpoint outside the measured entry's table is a candidate, not a choice")
    void setpointElsewhere() throws Exception {
      var log = new MockLogBuilder().setPath("/mock/setpoint_elsewhere.wpilog")
          .addEntry("/RealOutputs/Drive/DesiredStates", STATES, states(1.5))
          .addEntry("/RealOutputs/SwerveStates/Measured", STATES, states(1.0))
          .build();
      putLogInCache(log);
      var r = call("analyze_swerve", log.path());
      assertEquals("/RealOutputs/SwerveStates/Measured", entry(r, "measured"));
      assertNull(entry(r, "setpoint"), r.toString());
      assertFalse(r.getAsJsonArray("modules").get(0).getAsJsonObject()
          .has("speed_tracking_error"));
      var skipped = text(r, "skipped");
      assertTrue(skipped.contains("/RealOutputs/Drive/DesiredStates"), skipped);
      assertTrue(skipped.contains("setpoint_entry"), skipped);

      var explicit = call("analyze_swerve", log.path(), "setpoint_entry",
          "/RealOutputs/Drive/DesiredStates");
      assertEquals(0.5, explicit.getAsJsonArray("modules").get(0).getAsJsonObject()
          .getAsJsonObject("speed_tracking_error").get("mean_mps").getAsDouble(), 1e-12);
      assertTrue(explicit.get("setpoint_basis").getAsString().contains("setpoint_entry"));
    }

    @Test
    @DisplayName("the only module-state entry is used, with what the log does not say stated")
    void loneEntry() throws Exception {
      var log = new MockLogBuilder().setPath("/mock/lone_states.wpilog")
          .addEntry("NT:ModuleData", STATES, states(1.0)).build();
      putLogInCache(log);
      var r = call("analyze_swerve", log.path());
      assertTrue(r.get("success").getAsBoolean(), r.toString());
      assertEquals("NT:ModuleData", entry(r, "measured"));
      assertTrue(r.get("measured_basis").getAsString().contains("only"), r.toString());
      var warnings = text(r, "warnings");
      assertTrue(warnings.contains("NT:ModuleData") && warnings.contains("measured or commanded"),
          "the log does not say which it holds: " + r);
      assertEquals("type", call("resolve_signals", log.path()).getAsJsonObject("roles")
          .getAsJsonObject("module_states_measured").get("match").getAsString());

      // Named like a setpoint, it is not taken for the measured states
      var targets = new MockLogBuilder().setPath("/mock/lone_targets.wpilog")
          .addEntry("NT:Swerve/GoalModules", STATES, states(1.0)).build();
      putLogInCache(targets);
      var t = call("analyze_swerve", targets.path());
      assertEquals("no_match", t.get("status").getAsString(), t.toString());
      assertTrue(t.get("needs_confirmation").getAsBoolean());
      assertTrue(t.getAsJsonArray("candidates").toString().contains("NT:Swerve/GoalModules"));
    }

    @Test
    @DisplayName("two conventional tables: the first is used and the choice is flagged")
    void twoTables() throws Exception {
      var b = new MockLogBuilder().setPath("/mock/real_and_replay.wpilog");
      for (var table : List.of("/RealOutputs", "/ReplayOutputs")) {
        boolean real = table.equals("/RealOutputs");
        b.addEntry(table + "/SwerveStates/Measured", STATES, states(real ? 1.0 : 2.0))
            .addEntry(table + "/SwerveStates/SetpointsOptimized", STATES,
                states(real ? 1.25 : 2.5));
      }
      var log = b.build();
      putLogInCache(log);
      var r = call("analyze_swerve", log.path());
      assertEquals("/RealOutputs/SwerveStates/Measured", entry(r, "measured"));
      assertEquals("/RealOutputs/SwerveStates/SetpointsOptimized", entry(r, "setpoint"));
      var warnings = text(r, "warnings");
      assertTrue(warnings.contains("/ReplayOutputs/SwerveStates/Measured")
          && warnings.contains("module_prefix"), "the other table is named: " + r);

      // The other table, chosen by its measured entry: its own setpoints, not the first table's
      var replay = call("analyze_swerve", log.path(), "measured_entry",
          "/ReplayOutputs/SwerveStates/Measured");
      assertEquals("/ReplayOutputs/SwerveStates/SetpointsOptimized", entry(replay, "setpoint"),
          replay.toString());
      assertEquals(0.5, replay.getAsJsonArray("modules").get(0).getAsJsonObject()
          .getAsJsonObject("speed_tracking_error").get("mean_mps").getAsDouble(), 1e-12);
      // or by prefix, which leaves one table and nothing to flag
      var prefixed = call("analyze_swerve", log.path(), "module_prefix", "/ReplayOutputs");
      assertEquals("/ReplayOutputs/SwerveStates/Measured", entry(prefixed, "measured"));
      assertFalse(text(prefixed, "warnings").contains("module_prefix"), prefixed.toString());
    }

    @Test
    @DisplayName("one entry per module: candidates until passed, then one module per call")
    void perModuleEntries() throws Exception {
      var b = new MockLogBuilder().setPath("/mock/per_module.wpilog");
      for (int m = 0; m < 2; m++) {
        b.addEntry("/Drive/Module" + m + "/Measured", "struct:SwerveModuleState", oneModule(1.0))
            .addEntry("/Drive/Module" + m + "/Setpoint", "struct:SwerveModuleState",
                oneModule(1.5));
      }
      var log = b.build();
      putLogInCache(log);
      var r = call("analyze_swerve", log.path());
      assertEquals("no_match", r.get("status").getAsString(), r.toString());
      assertTrue(r.get("needs_confirmation").getAsBoolean());
      assertEquals(4, r.getAsJsonArray("candidates").size());

      var one = call("analyze_swerve", log.path(), "measured_entry", "/Drive/Module1/Measured",
          "setpoint_entry", "/Drive/Module1/Setpoint");
      assertEquals("per_module", one.get("layout").getAsString(), one.toString());
      assertEquals(1, one.get("module_count").getAsInt());
      assertEquals(0.5, one.getAsJsonArray("modules").get(0).getAsJsonObject()
          .getAsJsonObject("speed_tracking_error").get("mean_mps").getAsDouble(), 1e-12);
    }

    @Test
    @DisplayName("an array measured entry with a single-module setpoint entry is an error")
    void mismatchedShapes() throws Exception {
      var log = new MockLogBuilder().setPath("/mock/mismatched_shapes.wpilog")
          .addEntry("/Chassis/States", STATES, states(1.0))
          .addEntry("/Chassis/Module0/Goal", "struct:SwerveModuleState", oneModule(1.0))
          .build();
      putLogInCache(log);
      var r = call("analyze_swerve", log.path(), "measured_entry", "/Chassis/States",
          "setpoint_entry", "/Chassis/Module0/Goal");
      assertEquals("error", r.get("status").getAsString(), r.toString());
      var error = r.get("error").getAsString();
      assertTrue(error.contains("setpoint_entry") && error.contains("struct:SwerveModuleState[]"),
          error);
    }
  }

  // ==================== mechanism ====================

  private static double[] times(int n) {
    var t = new double[n];
    for (int i = 0; i < n; i++) t[i] = i * 0.02;
    return t;
  }

  private static double[] constant(int n, double v) {
    var out = new double[n];
    java.util.Arrays.fill(out, v);
    return out;
  }

  @Nested
  @DisplayName("profile_mechanism: roles")
  class Mechanism {

    private MockLogBuilder lift(String path) {
      int n = 200;
      return new MockLogBuilder().setPath(path)
          .addNumericEntry("/RealOutputs/Lift/GoalMeters", times(n), constant(n, 1.0))
          .addNumericEntry("/Lift/PositionMeters", times(n), constant(n, 0.9))
          .addNumericEntry("/Lift/VelocityMetersPerSec", times(n), constant(n, 0.0))
          .addNumericEntry("/Lift/CurrentAmps", times(n), constant(n, 45.0));
    }

    @Test
    @DisplayName("a mechanism name alone analyzes nothing: its matches are candidates by role")
    void nameAlone() throws Exception {
      var log = lift("/mock/lift_by_name.wpilog").build();
      putLogInCache(log);
      var r = call("profile_mechanism", log.path(), "mechanism_name", "Lift");
      assertEquals("no_match", r.get("status").getAsString(), r.toString());
      assertTrue(r.get("needs_confirmation").getAsBoolean(), r.toString());
      for (var section : List.of("following_error", "stall_events", "stall_count", "roles")) {
        assertFalse(r.has(section), section + " computed from names: " + r);
      }
      var candidates = r.getAsJsonObject("candidates");
      assertEquals("[\"/RealOutputs/Lift/GoalMeters\"]", candidates.get("setpoint").toString());
      assertEquals("[\"/Lift/PositionMeters\"]", candidates.get("measurement").toString());
      assertEquals("[\"/Lift/VelocityMetersPerSec\"]", candidates.get("velocity").toString());
      assertEquals("[\"/Lift/CurrentAmps\"]", candidates.get("current").toString());
      var hint = r.get("hint").getAsString();
      assertTrue(hint.contains("source code"), hint);
      for (var param : List.of("setpoint_entry", "measurement_entry", "velocity_entry",
          "current_entry", "temperature_entry")) {
        assertTrue(hint.contains(param), hint);
      }
    }

    @Test
    @DisplayName("entries passed explicitly are analyzed; the rest stay candidates")
    void explicitRoles() throws Exception {
      var log = lift("/mock/lift_explicit.wpilog").build();
      putLogInCache(log);
      var r = call("profile_mechanism", log.path(), "mechanism_name", "Lift",
          "velocity_entry", "/Lift/VelocityMetersPerSec", "current_entry", "/Lift/CurrentAmps");
      assertTrue(r.get("success").getAsBoolean(), r.toString());
      assertEquals(1, r.get("stall_count").getAsInt());
      var roles = r.getAsJsonObject("roles");
      assertEquals("/Lift/CurrentAmps", roles.get("current").getAsString());
      assertTrue(roles.get("setpoint").isJsonNull() && roles.get("measurement").isJsonNull(),
          "roles not passed stay empty: " + roles);
      assertFalse(r.has("following_error"));
      var candidates = r.getAsJsonObject("candidates");
      assertEquals("[\"/RealOutputs/Lift/GoalMeters\"]", candidates.get("setpoint").toString());
      assertFalse(candidates.has("current"), "a role that was passed has no candidates");
      var skipped = text(r, "skipped");
      assertTrue(skipped.contains("/RealOutputs/Lift/GoalMeters")
          && skipped.contains("setpoint_entry"), skipped);

      var all = call("profile_mechanism", log.path(),
          "setpoint_entry", "/RealOutputs/Lift/GoalMeters",
          "measurement_entry", "/Lift/PositionMeters");
      assertEquals(-0.1, all.getAsJsonObject("following_error").get("mean_error").getAsDouble(),
          1e-12);
      assertFalse(all.has("candidates"), "no mechanism_name, no candidates: " + all);
    }

    @Test
    @DisplayName("'current' in a name is not an electrical current, 'target' not a setpoint")
    void misleadingWords() throws Exception {
      int n = 200;
      // The present height in millimeters, and a camera reading named as PhotonVision names it
      var log = new MockLogBuilder().setPath("/mock/arm_words.wpilog")
          .addNumericEntry("/Arm/currentHeight", times(n), constant(n, 450.0))
          .addNumericEntry("/Arm/Velocity", times(n), constant(n, 0.0))
          .addNumericEntry("/Arm/targetYaw", times(n), constant(n, 12.0))
          .addNumericEntry("/Arm/AngleDegrees", times(n), constant(n, 30.0))
          .build();
      putLogInCache(log);
      var r = call("profile_mechanism", log.path(), "mechanism_name", "Arm");
      // It used to report a following error of 18 degrees: the arm's angle against a camera's
      // yaw. (The height was never taken for a current: the amperage rule wants "Current" at
      // the end of a name. It is here as a candidate that must stay one.)
      assertEquals("no_match", r.get("status").getAsString(), r.toString());
      assertFalse(r.has("stall_count") || r.has("following_error"), r.toString());
      assertTrue(r.get("needs_confirmation").getAsBoolean());
    }

    @Test
    @DisplayName("a name that matches nothing, and no arguments at all")
    void nothingToOffer() throws Exception {
      var log = lift("/mock/lift_nothing.wpilog").build();
      putLogInCache(log);
      var none = call("profile_mechanism", log.path(), "mechanism_name", "Wrist");
      assertEquals("no_match", none.get("status").getAsString(), none.toString());
      assertFalse(none.has("needs_confirmation"), "nothing to confirm: " + none);
      assertFalse(none.has("candidates"));
      var empty = call("profile_mechanism", log.path());
      assertEquals("error", empty.get("status").getAsString(), empty.toString());
    }
  }

  // ==================== vision ====================

  private static boolean[] alternating(int n) {
    var out = new boolean[n];
    for (int i = 0; i < n; i++) out[i] = (i / 10) % 2 == 0;
    return out;
  }

  private static Map<String, Object> observation(double timestamp, double x) {
    var t = new LinkedHashMap<String, Object>();
    t.put("x", x);
    t.put("y", 1.0);
    var pose = new LinkedHashMap<String, Object>();
    pose.put("translation", t);
    var o = new LinkedHashMap<String, Object>();
    o.put("timestamp", timestamp);
    o.put("pose", pose);
    return o;
  }

  @Nested
  @DisplayName("analyze_vision: has-target and latency entries")
  class Vision {

    @Test
    @DisplayName("Limelight tv and PhotonVision hasTarget are has-target entries by convention")
    void conventions() throws Exception {
      int n = 200;
      var tv = new double[n];
      for (int i = 0; i < n; i++) tv[i] = (i / 10) % 2;
      var log = new MockLogBuilder().setPath("/mock/vision_conventions.wpilog")
          .addNumericEntry("NT:/limelight-side/tv", times(n), tv)
          .addBooleanEntry("NT:/photonvision/Cam A/hasTarget", times(n), alternating(n))
          .build();
      putLogInCache(log);
      var r = call("analyze_vision", log.path());
      var analyzed = r.getAsJsonArray("target_acquisition").toString();
      assertTrue(analyzed.contains("NT:/limelight-side/tv")
          && analyzed.contains("NT:/photonvision/Cam A/hasTarget"), r.toString());
      assertFalse(r.has("candidates"), r.toString());
    }

    @Test
    @DisplayName("a team's own flag named like one is a candidate until it is passed")
    void ownFlags() throws Exception {
      int n = 200;
      var log = new MockLogBuilder().setPath("/mock/vision_own_flags.wpilog")
          .addBooleanEntry("/Shooter/HasTargetLock", times(n), alternating(n))
          .addBooleanEntry("/Intake/TargetValid", times(n), alternating(n))
          .addEntry("/Shooter/TargetValidReason", "string",
              List.of(new TimestampedValue(0.0, "no tag")))
          .build();
      putLogInCache(log);
      var r = call("analyze_vision", log.path());
      assertEquals(0, r.has("target_acquisition") ? r.getAsJsonArray("target_acquisition").size()
          : 0, "not analyzed as has-target entries: " + r);
      var candidates = r.getAsJsonObject("candidates").get("has_target").toString();
      assertTrue(candidates.contains("/Shooter/HasTargetLock")
          && candidates.contains("/Intake/TargetValid"), r.toString());
      assertFalse(candidates.contains("TargetValidReason"), "a string is not a flag: " + r);
      assertTrue(r.get("needs_confirmation").getAsBoolean(), r.toString());
      assertTrue(text(r, "hint").contains("vision_entries")
          && text(r, "hint").contains("source code"), r.toString());

      var passed = new JsonArray();
      passed.add("/Shooter/HasTargetLock");
      var explicit = call("analyze_vision", log.path(), "vision_entries", passed);
      var analyzed = explicit.getAsJsonArray("target_acquisition");
      assertEquals(1, analyzed.size(), explicit.toString());
      assertEquals("/Shooter/HasTargetLock",
          analyzed.get(0).getAsJsonObject().get("entry").getAsString());
      assertEquals(0.5, analyzed.get(0).getAsJsonObject().get("acquisition_rate").getAsDouble(),
          1e-12);

      for (var bad : List.of("/No/Such/Flag", "/Shooter/TargetValidReason")) {
        var wrong = new JsonArray();
        wrong.add(bad);
        var e = call("analyze_vision", log.path(), "vision_entries", wrong);
        assertEquals("error", e.get("status").getAsString(), bad + ": " + e);
        assertTrue(e.get("error").getAsString().contains("vision_entries"), e.toString());
      }
    }

    @Test
    @DisplayName("an entry named latency beside a stream is listed, not reported as its latency")
    void siblingLatency() throws Exception {
      int n = 100;
      var records = new ArrayList<TimestampedValue>();
      for (int i = 0; i < n; i++) {
        double t = 1.0 + i * 0.02;
        records.add(new TimestampedValue(t, List.of(observation(t - 0.05, 2.0))));
      }
      var log = new MockLogBuilder().setPath("/mock/vision_latency.wpilog")
          .addEntry("/Vision/Camera0/PoseObservations", "struct:PoseObservation[]", records)
          .addNumericEntry("/Vision/Camera0/LatencyMs", times(n), constant(n, 61.0))
          .build();
      putLogInCache(log);
      var r = call("analyze_vision", log.path());
      var stream = r.getAsJsonArray("observation_streams").get(0).getAsJsonObject();
      // The latency the tool computes itself: log time minus the observation's own timestamp
      assertEquals(50.0, stream.getAsJsonObject("latency").get("median_ms").getAsDouble(), 1e-6);
      assertFalse(stream.has("logged_latency"), "a name is not what the entry measures: " + r);
      assertEquals("[\"/Vision/Camera0/LatencyMs\"]",
          stream.get("latency_candidates").toString());
    }

    @Test
    @DisplayName("resolve_signals lists name-only has-target entries as candidates")
    void resolveSignals() throws Exception {
      int n = 200;
      var tv = new double[n];
      var log = new MockLogBuilder().setPath("/mock/vision_roles.wpilog")
          .addNumericEntry("NT:/limelight/tv", times(n), tv)
          .addBooleanEntry("/Shooter/HasTargetLock", times(n), alternating(n))
          .build();
      putLogInCache(log);
      var role = call("resolve_signals", log.path()).getAsJsonObject("roles")
          .getAsJsonObject("vision_targets");
      assertEquals("NT:/limelight/tv", role.get("entry").getAsString(), role.toString());
      assertTrue(role.getAsJsonArray("candidates").toString().contains("/Shooter/HasTargetLock"));

      var own = new MockLogBuilder().setPath("/mock/vision_roles_own.wpilog")
          .addBooleanEntry("/Shooter/HasTargetLock", times(n), alternating(n)).build();
      putLogInCache(own);
      var ownRole = call("resolve_signals", own.path()).getAsJsonObject("roles")
          .getAsJsonObject("vision_targets");
      assertEquals("heuristic", ownRole.get("match").getAsString(), ownRole.toString());
      assertTrue(ownRole.get("needs_confirmation").getAsBoolean());
    }

    @Test
    @DisplayName("a long list of candidates is capped, with the full count beside it")
    void manyCandidates() throws Exception {
      int n = 50;
      var builder = new MockLogBuilder().setPath("/mock/vision_many_flags.wpilog");
      for (int i = 0; i < 25; i++) {
        builder.addBooleanEntry(String.format("/Station%02d/HasTarget", i), times(n),
            alternating(n));
      }
      // A kind within the limit gets no count
      builder.addBooleanEntry("/Intake/TargetValid", times(n), alternating(n));
      var log = builder.build();
      putLogInCache(log);
      var r = call("analyze_vision", log.path());
      var listed = r.getAsJsonObject("candidates").getAsJsonArray("has_target");
      assertEquals(FrcDomainTools.AnalyzeVisionTool.CANDIDATE_LIMIT, listed.size(), r.toString());
      assertEquals(26, r.getAsJsonObject("candidate_counts").get("has_target").getAsInt(),
          r.toString());
      assertEquals(1, r.getAsJsonObject("candidate_counts").size(), r.toString());

      var few = new MockLogBuilder().setPath("/mock/vision_few_flags.wpilog")
          .addBooleanEntry("/Intake/TargetValid", times(n), alternating(n)).build();
      putLogInCache(few);
      var fewResult = call("analyze_vision", few.path());
      assertEquals(1, fewResult.getAsJsonObject("candidates").getAsJsonArray("has_target").size());
      assertFalse(fewResult.has("candidate_counts"), fewResult.toString());
    }

    @Test
    @DisplayName("the robot pose in use is not listed as a candidate vision pose")
    void robotPoseIsNotItsOwnCandidate() throws Exception {
      var fused = new ArrayList<TimestampedValue>();
      var estimate = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 100; i++) {
        double t = 1.0 + i * 0.02;
        fused.add(new TimestampedValue(t, MockLogBuilder.makePose2d(1.0 + 0.01 * i, 2.0)));
        estimate.add(new TimestampedValue(t, MockLogBuilder.makePose2d(1.1 + 0.01 * i, 2.0)));
      }
      var log = new MockLogBuilder().setPath("/mock/vision_pose_in_use.wpilog")
          .addEntry("/Vision/FusedPose", "struct:Pose2d", fused)
          .addEntry("/Vision/Camera0/Estimate", "struct:Pose2d", estimate)
          .build();
      putLogInCache(log);

      // Two scalar poses under a vision path, neither chosen: both are candidates
      var neither = call("analyze_vision", log.path());
      var both = neither.getAsJsonObject("candidates").getAsJsonArray("pose_estimates").toString();
      assertTrue(both.contains("/Vision/FusedPose") && both.contains("/Vision/Camera0/Estimate"),
          neither.toString());

      // One passed as the robot pose: it is analyzed, so it is not "a candidate, not analyzed"
      var r = call("analyze_vision", log.path(), "pose_entry", "/Vision/FusedPose");
      assertEquals("[\"/Vision/Camera0/Estimate\"]",
          r.getAsJsonObject("candidates").get("pose_estimates").toString(), r.toString());
      var skipped = r.getAsJsonArray("skipped").toString();
      assertFalse(skipped.contains("Candidates, not analyzed: /Vision/FusedPose"), skipped);
    }
  }
}
