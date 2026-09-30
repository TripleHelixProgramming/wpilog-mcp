/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Brownout facts across the power tools (review issues E3, G2, B8). */
@DisplayName("Power tools on fixture logs")
class PowerFixtureTest extends FixtureToolTestBase {

  @Test
  @DisplayName("roboRIO 2: the logged 6.3 V threshold is used; a 6.5 V dip is not a brownout (E3)")
  void rio2LoggedThreshold() {
    for (var tool : List.of("power_analysis", "predict_battery_health", "get_ds_timeline")) {
      var r = call(tool, "brownout_rio2");
      var text = r.toString();
      assertTrue(text.contains("\"brownout_threshold\":6.3"), tool + ": " + text);
      assertTrue(text.contains("\"brownout_threshold_basis\":\"logged\""), tool + ": " + text);
    }
    var battery = call("predict_battery_health", "brownout_rio2");
    assertEquals(0, battery.get("brownout_events").getAsInt());
    assertNotEquals("CRITICAL", battery.get("risk_level").getAsString());
    var timeline = call("get_ds_timeline", "brownout_rio2");
    assertEquals(0, objects(timeline.getAsJsonArray("events")).stream()
        .filter(e -> e.get("type").getAsString().startsWith("BROWNOUT")).count());
    // An explicit argument still wins
    var explicit = call("predict_battery_health", "brownout_rio2", "brownout_threshold", 6.8);
    assertEquals("argument", explicit.get("brownout_threshold_basis").getAsString());
    // The 6.5 V dip crosses 6.8 V, but the logged flag says the roboRIO never browned out,
    // and the flag decides what counts as a brownout
    assertEquals(1, explicit.get("threshold_crossings").getAsInt());
    assertEquals(0, explicit.get("brownout_events").getAsInt());
    assertTrue(explicit.get("brownout_basis").getAsString().startsWith("rio_flag"));
  }

  @Test
  @DisplayName("roboRIO 1 without a threshold entry: 6.8 V stated as an assumption; the flag counts")
  void rio1FlagCounts() {
    var power = call("power_analysis", "brownout_rio1");
    var v = power.getAsJsonObject("voltage_analysis");
    assertEquals(6.8, v.get("brownout_threshold").getAsDouble());
    assertTrue(v.get("brownout_threshold_basis").getAsString().contains("roboRIO 2"));
    var rio = power.getAsJsonObject("rio_brownouts");
    assertEquals(1, rio.get("count").getAsInt());
    var event = rio.getAsJsonArray("events").get(0).getAsJsonObject();
    assertEquals(30.0, event.get("start").getAsDouble(), 1e-9);
    assertEquals(0.1, event.get("duration_sec").getAsDouble(), 1e-9);

    var battery = call("predict_battery_health", "brownout_rio1");
    assertEquals(1, battery.get("brownout_events").getAsInt());
    assertTrue(battery.get("brownout_basis").getAsString().startsWith("rio_flag"));
    assertEquals("CRITICAL", battery.get("risk_level").getAsString());
  }

  @Test
  @DisplayName("battery health gives evidence and candidate causes, never replacement advice (G2)")
  void noReplacementAdvice() {
    for (var id : List.of("akit_practice", "brownout_rio1", "brownout_rio2", "akit_match")) {
      var r = call("predict_battery_health", id);
      var text = (r.getAsJsonArray("observations").toString() + r.get("warnings")).toLowerCase();
      assertFalse(text.contains("replace"), id + ": " + text);
      assertFalse(text.contains("urgent"), id + ": " + text);
    }
    var practice = call("predict_battery_health", "akit_practice");
    assertEquals("enabled", practice.getAsJsonObject("scope").get("scope").getAsString(),
        "defaults to enabled time when the log records it");
    assertEquals(2, practice.get("brownout_events").getAsInt());
    var observations = practice.getAsJsonArray("observations").toString();
    assertTrue(observations.contains("100.40 s for 0.140 s"), observations);
    assertTrue(observations.contains("Candidate causes"), observations);
  }

  @Test
  @DisplayName("load line: voltage regressed on total current in the FMS match fixture")
  void loadLine() {
    var r = call("predict_battery_health", "akit_match");
    var line = r.getAsJsonObject("load_line");
    assertEquals("/PowerDistribution/TotalCurrent", line.get("current_entry").getAsString());
    // Fixture: V = 12.2 - load, total current 300 load  =>  dV/dI = -1/300 ohm. Not
    // /SystemStats/BatteryCurrent: that is AdvantageKit's RobotController.getInputCurrent(), the
    // roboRIO's own input current, which is not the robot's load
    assertEquals(1.0 / 300.0, line.get("resistance_ohm").getAsDouble(), 1e-4);
    assertTrue(line.get("r_squared").getAsDouble() > 0.9);
  }

  @Test
  @DisplayName("generate_report: shared voltage choice, logged threshold, flag brownouts, peaks")
  void report() {
    var r = call("generate_report", "akit_practice");
    var battery = r.getAsJsonObject("battery");
    assertEquals("/SystemStats/BatteryVoltage", battery.get("entry").getAsString());
    assertEquals(6.75, battery.get("brownout_threshold").getAsDouble());
    assertEquals(2, battery.getAsJsonObject("rio_brownouts").get("count").getAsInt());
    assertEquals(4, r.getAsJsonObject("timeline").get("enabled_segments").getAsInt());
    // The boot banner mentions a "Default error handler": one ERROR sample, the line not the batch
    var errors = r.getAsJsonObject("errors");
    var first = errors.getAsJsonArray("samples").get(0).getAsJsonObject();
    assertEquals("Default error handler registered", first.get("line").getAsString());
    var match = call("generate_report", "akit_match");
    var peaks = match.getAsJsonArray("peak_currents");
    assertEquals(3, peaks.size());
    assertTrue(peaks.get(0).getAsJsonObject().get("entry").getAsString()
        .startsWith("/SystemStats/BatteryCurrent") || peaks.get(0).getAsJsonObject().get("entry")
        .getAsString().startsWith("/PowerDistribution/TotalCurrent"));
    assertEquals("abc1234", match.getAsJsonObject("code_info").get("git_sha").getAsString());
  }

  @Test
  @DisplayName("get_code_metadata: sources named; a log without metadata is no_match")
  void codeMetadata() {
    var r = call("get_code_metadata", "akit_match");
    assertEquals("abc1234", r.getAsJsonObject("metadata").get("GitSHA").getAsString());
    assertEquals("/RealMetadata/GitSHA", r.getAsJsonObject("sources").get("GitSHA").getAsString());
    var none = call("get_code_metadata", "canivore");
    assertEquals("no_match", none.get("status").getAsString());
  }
}
