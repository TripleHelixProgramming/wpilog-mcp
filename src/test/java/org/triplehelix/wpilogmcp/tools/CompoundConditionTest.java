/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** find_condition across entries: all/any, values held between samples (plan Phase 4 item 2). */
@DisplayName("find_condition compound conditions")
class CompoundConditionTest extends FixtureToolTestBase {

  static final String DISABLED_AND_STILL = """
      {"all": [
        {"name": "/DriverStation/Enabled", "operator": "eq", "threshold": 0},
        {"name": "/RealOutputs/SwerveChassisSpeeds/Measured.vx", "operator": "abs_lt",
         "threshold": 0.05}
      ]}""";

  @Test
  @DisplayName("disabled AND stationary: only before the match, when the held speed is zero")
  void disabledAndStationary() {
    // chassis speeds are logged only while enabled (and once at 6 s, zero), so each value holds:
    // before 20 s the held speed is 0; after an enabled segment it is the last moving value
    var r = call("find_condition", "akit_match", "conditions",
        JsonParser.parseString(DISABLED_AND_STILL));
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    assertEquals("all", r.get("combine").getAsString());
    assertEquals(1, r.get("interval_count").getAsInt(), r.toString());
    var interval = r.getAsJsonArray("intervals").get(0).getAsJsonObject();
    assertEquals(6.0, interval.get("start").getAsDouble(), 1e-9);
    assertEquals(20.0, interval.get("end").getAsDouble(), 1e-9);
    assertEquals(14.0, r.get("total_true_sec").getAsDouble(), 1e-9);
    assertTrue(r.get("condition").getAsString().contains(" AND "));
    var transition = r.getAsJsonArray("transitions").get(0).getAsJsonObject();
    assertEquals(2, transition.getAsJsonArray("values").size());
    var inputs = r.getAsJsonObject("inputs");
    assertEquals("/RealOutputs/SwerveChassisSpeeds/Measured",
        inputs.getAsJsonObject("entries").get("condition1").getAsString());
    assertEquals(".vx", inputs.getAsJsonObject("fields").get("condition1").getAsString());
  }

  @Test
  @DisplayName("any: enabled OR stationary")
  void anyCondition() {
    var r = call("find_condition", "akit_match", "conditions", JsonParser.parseString("""
        {"any": [
          {"name": "/DriverStation/Enabled", "operator": "eq", "threshold": 1},
          {"name": "/RealOutputs/SwerveChassisSpeeds/Measured", "field": "vx",
           "operator": "abs_lt", "threshold": 0.05}
        ]}"""));
    assertEquals(2, r.get("interval_count").getAsInt(), r.toString());
    var first = r.getAsJsonArray("intervals").get(0).getAsJsonObject();
    assertEquals(6.0, first.get("start").getAsDouble(), 1e-9);
    assertEquals(40.0, first.get("end").getAsDouble(), 1e-9);
    var second = r.getAsJsonArray("intervals").get(1).getAsJsonObject();
    assertEquals(43.0, second.get("start").getAsDouble(), 1e-9);
    assertEquals(183.0, second.get("end").getAsDouble(), 1e-9);
  }

  @Test
  @DisplayName("its intervals feed get_statistics as windows")
  void intervalsAsWindows() {
    var r = call("find_condition", "akit_match", "conditions",
        JsonParser.parseString(DISABLED_AND_STILL));
    var battery = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "windows", r.getAsJsonArray("intervals"));
    assertEquals(12.6, battery.get("min").getAsDouble(), 1e-9);
    assertEquals(12.6, battery.get("max").getAsDouble(), 1e-9);
  }

  @Test
  @DisplayName("a single condition in conditions behaves like name/operator/threshold")
  void singleInConditions() {
    var plain = call("find_condition", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "operator", "lt", "threshold", 7.5);
    var wrapped = call("find_condition", "akit_match", "conditions", JsonParser.parseString(
        "{\"all\": [{\"name\": \"/SystemStats/BatteryVoltage\", \"operator\": \"lt\", "
            + "\"threshold\": 7.5}]}"));
    assertEquals(plain.getAsJsonArray("intervals"), wrapped.getAsJsonArray("intervals"));
    assertEquals(plain.get("total_true_sec").getAsDouble(),
        wrapped.get("total_true_sec").getAsDouble());
  }

  @Test
  @DisplayName("malformed conditions are errors that say what to pass")
  void errors() {
    var both = call("find_condition", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "conditions", JsonParser.parseString(DISABLED_AND_STILL));
    assertTrue(both.get("error").getAsString().contains("either name/operator/threshold or "
        + "conditions"), both.toString());
    var shape = call("find_condition", "akit_match", "conditions",
        JsonParser.parseString("{\"some\": []}"));
    assertTrue(shape.get("error").getAsString().contains("{\"all\": [...]}"), shape.toString());
    var empty = call("find_condition", "akit_match", "conditions",
        JsonParser.parseString("{\"all\": []}"));
    assertTrue(empty.get("error").getAsString().contains("non-empty array"), empty.toString());
    var badItem = call("find_condition", "akit_match", "conditions",
        JsonParser.parseString("{\"any\": [{\"name\": \"/SystemStats/BatteryVoltage\"}]}"));
    assertTrue(badItem.get("error").getAsString().contains("operator"), badItem.toString());
    var none = call("find_condition", "akit_match", "operator", "lt", "threshold", 1);
    assertTrue(none.get("error").getAsString().contains("name (or pass conditions)"),
        none.toString());
  }
}
