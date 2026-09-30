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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;

/** Numeric tools address struct fields and array elements by path (review issues D1, D2). */
@DisplayName("Field paths in numeric tools")
class FieldPathToolsTest extends FixtureToolTestBase {

  @Nested
  @DisplayName("on the struct fixture")
  class OnFixtures {

    @Test
    @DisplayName("a struct field appended to the name: currents[0] = 10 + t over 1-30 s")
    void appendedPath() {
      var r = call("get_statistics", "struct_custom", "name", "/RealOutputs/Arm/State.currents[0]");
      assertEquals("ok", r.get("status").getAsString(), r.toString());
      assertEquals("/RealOutputs/Arm/State.currents[0]", r.get("name").getAsString());
      assertEquals(".currents[0]", r.get("field").getAsString());
      assertEquals(11.0, r.get("min").getAsDouble(), 1e-9);
      assertEquals(40.0, r.get("max").getAsDouble(), 1e-9);
      assertEquals(25.5, r.get("mean").getAsDouble(), 1e-6);
      assertEquals(1451, r.get("count").getAsInt());
      var inputs = r.getAsJsonObject("inputs");
      assertEquals("/RealOutputs/Arm/State",
          inputs.getAsJsonObject("entries").get("entry").getAsString());
      assertEquals(".currents[0]", inputs.getAsJsonObject("fields").get("entry").getAsString());
      assertFalse(r.has("angle"));
    }

    @Test
    @DisplayName("the field parameter is the same as an appended path")
    void fieldParameter() {
      var appended = call("get_statistics", "struct_custom",
          "name", "/RealOutputs/Arm/State.currents[1]");
      var param = call("get_statistics", "struct_custom",
          "name", "/RealOutputs/Arm/State", "field", "currents[1]");
      for (var key : List.of("min", "max", "mean", "median", "std_dev", "count")) {
        assertEquals(appended.get(key).getAsDouble(), param.get(key).getAsDouble(), key);
      }
      assertEquals(12.0, param.get("min").getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("enum fields read as their number, booleans as 1/0")
    void enumsAndBooleans() {
      var mode = call("get_statistics", "struct_custom", "name", "/RealOutputs/Arm/State.mode");
      assertEquals(0.0, mode.get("min").getAsDouble());
      assertEquals(2.0, mode.get("max").getAsDouble());
      var homed = call("get_statistics", "struct_custom", "name", "/RealOutputs/Arm/State.homed");
      // homed is true for i > 100 of 1451 loops
      assertEquals(1350.0 / 1451.0, homed.get("mean").getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("[*] pools every element of a struct array")
    void wildcardPools() {
      var r = call("get_statistics", "struct_custom",
          "name", "/RealOutputs/Arm/States[*].currents[0]");
      assertEquals("ok", r.get("status").getAsString(), r.toString());
      int records = r.get("records_in_window").getAsInt();
      assertEquals(146, records);
      assertEquals(2 * records, r.get("count").getAsInt());
      assertEquals(1.0, r.get("min").getAsDouble(), 1e-9); // the second arm's current
    }

    @Test
    @DisplayName("numeric array elements: ChannelCurrent[23] is six times ChannelCurrent[3]")
    void arrayElements() {
      var c3 = call("get_statistics", "akit_match", "name", "/PowerDistribution/ChannelCurrent[3]");
      var c23 = call("get_statistics", "akit_match", "name",
          "/PowerDistribution/ChannelCurrent[23]");
      assertEquals("ok", c3.get("status").getAsString(), c3.toString());
      assertEquals(6.0 * c3.get("max").getAsDouble(), c23.get("max").getAsDouble(), 1e-9);
      assertTrue(c3.get("max").getAsDouble() > 5.9 && c3.get("max").getAsDouble() <= 6.0);
      var all = call("get_statistics", "akit_match", "name",
          "/PowerDistribution/ChannelCurrent[*]");
      assertEquals(24 * all.get("records_in_window").getAsInt(), all.get("count").getAsInt());
    }

    @Test
    @DisplayName("a Rotation2d field is an angle: unit and circular statistics are reported")
    void angleField() {
      var r = call("get_statistics", "struct_custom", "name", "/RealOutputs/Arm/State.angle.value",
          "start_time", 2, "end_time", 20);
      var angle = r.getAsJsonObject("angle");
      assertEquals("radians", angle.get("unit").getAsString());
      assertTrue(angle.get("unwrapped").getAsBoolean());
      assertEquals(0, angle.get("wraps").getAsInt());
      assertTrue(angle.has("circular_mean") && angle.has("circular_std"));
      var degrees = call("get_statistics", "struct_custom",
          "name", "/RealOutputs/Arm/State.angle._derived.degrees", "start_time", 2, "end_time", 20);
      assertEquals("degrees", degrees.getAsJsonObject("angle").get("unit").getAsString());
      assertEquals(Math.toDegrees(r.get("max").getAsDouble()), degrees.get("max").getAsDouble(),
          1e-9);
    }

    @Test
    @DisplayName("find_condition, rate_of_change, find_peaks, detect_anomalies take paths too")
    void otherTools() {
      var condition = call("find_condition", "struct_custom",
          "name", "/RealOutputs/Arm/State.currents[0]", "operator", "gt", "threshold", 30);
      assertEquals(1, condition.get("interval_count").getAsInt(), condition.toString());
      var interval = condition.getAsJsonArray("intervals").get(0).getAsJsonObject();
      assertEquals(20.0, interval.get("start").getAsDouble(), 0.021);
      assertEquals(".currents[0]", condition.getAsJsonObject("inputs").getAsJsonObject("fields")
          .get("entry").getAsString());

      var rate = call("rate_of_change", "struct_custom",
          "name", "/RealOutputs/Arm/State.currents[0]", "start_time", 5, "end_time", 10);
      assertEquals(1.0, rate.getAsJsonObject("statistics").get("avg_rate").getAsDouble(), 1e-6);

      var peaks = call("find_peaks", "struct_custom", "name", "/RealOutputs/Arm/State.angle.value");
      assertEquals("ok", peaks.get("status").getAsString(), peaks.toString());
      assertTrue(peaks.get("maxima_count").getAsInt() > 0);
      assertEquals("radians", peaks.get("angle_unit").getAsString());

      var anomalies = call("detect_anomalies", "struct_custom",
          "name", "/RealOutputs/Arm/State", "field", "temperature");
      assertEquals("ok", anomalies.get("status").getAsString(), anomalies.toString());
      assertEquals(0, anomalies.get("outlier_count").getAsInt());
    }

    @Test
    @DisplayName("compare_entries and time_correlate take a field per side")
    void twoSided() {
      var cmp = call("compare_entries", "struct_custom",
          "name1", "/RealOutputs/Arm/State.currents[1]",
          "name2", "/RealOutputs/Arm/State", "field2", "currents[0]");
      assertEquals(1.0, cmp.get("rmse").getAsDouble(), 1e-9);
      assertEquals(1.0, cmp.get("max_difference").getAsDouble(), 1e-9);
      var corr = call("time_correlate", "struct_custom",
          "name1", "/RealOutputs/Arm/State.currents[0]",
          "name2", "/RealOutputs/Arm/State.temperature");
      assertEquals(1.0, corr.get("correlation").getAsDouble(), 1e-6);
    }
  }

  @Nested
  @DisplayName("errors name the type and the numeric fields")
  class Errors {

    @Test
    @DisplayName("a struct entry without a path lists its numeric fields")
    void structWithoutPath() {
      var r = call("get_statistics", "struct_custom", "name", "/RealOutputs/Arm/State");
      assertEquals("error", r.get("status").getAsString());
      var error = r.get("error").getAsString();
      assertTrue(error.contains("is struct:ArmState, not a number"), error);
      assertTrue(error.contains(".angle.value") && error.contains(".currents[1]"), error);
      assertTrue(error.contains("/RealOutputs/Arm/State.angle.value"), error);
    }

    @Test
    @DisplayName("a struct array without a path lists element paths")
    void structArrayWithoutPath() {
      var error = call("rate_of_change", "struct_custom", "name", "/RealOutputs/Arm/States")
          .get("error").getAsString();
      assertTrue(error.contains("[*].angle.value"), error);
    }

    @Test
    @DisplayName("a numeric array without an index says how to select elements")
    void arrayWithoutIndex() {
      var error = call("get_statistics", "akit_match", "name", "/PowerDistribution/ChannelCurrent")
          .get("error").getAsString();
      assertTrue(error.contains("is double[]"), error);
      assertTrue(error.contains("/PowerDistribution/ChannelCurrent[0]"), error);
      assertTrue(error.contains("[*]"), error);
    }

    @Test
    @DisplayName("a missing field, a struct-valued field, and an index past every array")
    void badPaths() {
      var missing = call("get_statistics", "struct_custom", "name", "/RealOutputs/Arm/State.nope")
          .get("error").getAsString();
      assertTrue(missing.contains("Field .nope of /RealOutputs/Arm/State (struct:ArmState) does "
          + "not exist"), missing);
      assertTrue(missing.contains(".currents[0]"), missing);
      var struct = call("get_statistics", "struct_custom", "name", "/RealOutputs/Arm/State.angle")
          .get("error").getAsString();
      assertTrue(struct.contains("is a struct, not a number"), struct);
      var past = call("get_statistics", "akit_match", "name",
          "/PowerDistribution/ChannelCurrent[24]").get("error").getAsString();
      assertTrue(past.contains("matches no element in any sample"), past);
      var malformed = call("get_statistics", "struct_custom", "name",
          "/RealOutputs/Arm/State.currents[x]").get("error").getAsString();
      assertTrue(malformed.contains("Array index must be"), malformed);
    }

    @Test
    @DisplayName("a scalar entry has no fields; a string is not numeric")
    void scalarsAndStrings() {
      var scalar = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage.x")
          .get("error").getAsString();
      assertTrue(scalar.contains("is double, a single number: it has no field .x"), scalar);
      var field = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "field", "x").get("error").getAsString();
      assertEquals(scalar, field);
    }

    @Test
    @DisplayName("tools that need one value per sample refuse [*], naming the element form")
    void wildcardRefused() {
      var error = call("compare_entries", "struct_custom",
          "name1", "/RealOutputs/Arm/States[*].currents[0]",
          "name2", "/RealOutputs/Arm/State.currents[0]").get("error").getAsString();
      assertTrue(error.contains("compare_entries needs one value per sample"), error);
      assertTrue(error.contains("/RealOutputs/Arm/States[0].currents[0]"), error);
    }

    @Test
    @DisplayName("an unknown entry is still 'Entry not found', with suggestions")
    void unknownEntry() {
      var error = call("get_statistics", "struct_custom", "name", "/RealOutputs/Arm/Stat.x")
          .get("error").getAsString();
      assertTrue(error.startsWith("Entry not found: /RealOutputs/Arm/Stat.x"), error);
    }
  }

  @Nested
  @DisplayName("angles across +-180 degrees")
  class Angles extends ToolTestBase {

    @Override
    protected void registerTools(org.triplehelix.wpilogmcp.mcp.ToolRegistry registry) {
      StatisticsTools.registerAll(registry);
      QueryTools.registerAll(registry);
    }

    @AfterEach
    void unload() {
      LogManager.getInstance().unloadAllLogs();
    }

    static Map<String, Object> pose(double x, double heading) {
      var translation = new LinkedHashMap<String, Object>();
      translation.put("x", x);
      translation.put("y", 0.0);
      var rotation = new LinkedHashMap<String, Object>();
      rotation.put("value", heading);
      var pose = new LinkedHashMap<String, Object>();
      pose.put("translation", translation);
      pose.put("rotation", rotation);
      return pose;
    }

    /** A robot turning steadily through +-pi: heading 3.0 rad rising by 0.05 rad per 20 ms. */
    void putTurningLog() {
      var ts = new double[40];
      var poses = new ArrayList<Map<String, Object>>();
      var gyro = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < 40; i++) {
        ts[i] = i * 0.02;
        double raw = 3.0 + 0.05 * i;
        double wrapped = Math.atan2(Math.sin(raw), Math.cos(raw));
        poses.add(pose(i * 0.01, wrapped));
        // the gyro reads 0.01 rad ahead
        double g = raw + 0.01;
        gyro.add(new LinkedHashMap<>(Map.of("value", Math.atan2(Math.sin(g), Math.cos(g)))));
      }
      putLogInCache(new MockLogBuilder().setPath("/test/turning.wpilog")
          .addStructEntry("/Drive/Pose", "struct:Pose2d", ts, poses)
          .addStructEntry("/Drive/Gyro", "struct:Rotation2d", ts, gyro)
          .build());
    }

    JsonObject run(String tool, Object... kv) throws Exception {
      var args = new JsonObject();
      args.addProperty("path", "/test/turning.wpilog");
      for (int i = 0; i < kv.length; i += 2) {
        if (kv[i + 1] instanceof Number n) args.addProperty((String) kv[i], n);
        else args.addProperty((String) kv[i], kv[i + 1].toString());
      }
      return findTool(tool).execute(args).getAsJsonObject();
    }

    @Test
    @DisplayName("get_statistics measures how far the heading turned, not the wrap")
    void statisticsUnwrap() throws Exception {
      putTurningLog();
      var r = run("get_statistics", "name", "/Drive/Pose.rotation.value");
      assertEquals("ok", r.get("status").getAsString(), r.toString());
      assertEquals(39 * 0.05, r.get("max").getAsDouble() - r.get("min").getAsDouble(), 1e-9);
      var angle = r.getAsJsonObject("angle");
      assertEquals(1, angle.get("wraps").getAsInt());
      // circular mean of 3.0 .. 4.95 rad is 3.975 rad, i.e. -2.308 wrapped
      assertEquals(Math.atan2(Math.sin(3.975), Math.cos(3.975)),
          angle.get("circular_mean").getAsDouble(), 1e-3);
    }

    @Test
    @DisplayName("rate_of_change has no spike at the wrap")
    void rateUnwrap() throws Exception {
      putTurningLog();
      var r = run("rate_of_change", "name", "/Drive/Pose.rotation.value");
      for (var sample : r.getAsJsonArray("samples")) {
        assertEquals(2.5, sample.getAsJsonObject().get("rate").getAsDouble(), 1e-6);
      }
    }

    @Test
    @DisplayName("find_peaks sees no peak at the wrap")
    void peaksUnwrap() throws Exception {
      putTurningLog();
      var r = run("find_peaks", "name", "/Drive/Pose.rotation.value");
      assertEquals(0, r.get("maxima_count").getAsInt(), r.toString());
      assertEquals(0, r.get("minima_count").getAsInt(), r.toString());
    }

    @Test
    @DisplayName("compare_entries takes the shortest angular difference, across the wrap")
    void compareAngles() throws Exception {
      putTurningLog();
      var r = run("compare_entries", "name1", "/Drive/Pose.rotation.value",
          "name2", "/Drive/Gyro.value");
      assertEquals(0.01, r.get("rmse").getAsDouble(), 1e-9);
      assertEquals(0.01, r.get("max_difference").getAsDouble(), 1e-9);
      assertEquals("radians", r.get("angle_unit").getAsString());
    }

    @Test
    @DisplayName("a heading and a translation are compared as numbers, with a warning")
    void mixedAngleWarning() throws Exception {
      putTurningLog();
      var r = run("compare_entries", "name1", "/Drive/Pose.rotation.value",
          "name2", "/Drive/Pose.translation.x");
      assertTrue(r.getAsJsonArray("warnings").toString().contains("compared as plain numbers"),
          r.toString());
    }

    @Test
    @DisplayName("find_condition applies thresholds to the heading as logged")
    void conditionRaw() throws Exception {
      putTurningLog();
      var r = run("find_condition", "name", "/Drive/Pose.rotation.value", "operator", "lt",
          "threshold", 0);
      // negative once past +pi: from sample 3 (3.15 rad wraps to -3.13) on
      assertEquals(1, r.get("interval_count").getAsInt(), r.toString());
      assertEquals(0.06, r.getAsJsonArray("intervals").get(0).getAsJsonObject().get("start")
          .getAsDouble(), 1e-9);
    }
  }
}
