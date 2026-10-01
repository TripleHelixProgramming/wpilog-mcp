/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** analyze_can_bus and can_health on the fixture corpus (review issues B3, F3). */
@DisplayName("CAN analysis on fixture logs")
class CanBusFixtureTest extends FixtureToolTestBase {

  static JsonObject bus(JsonObject result, String name) {
    return objects(result.getAsJsonArray("buses")).stream()
        .filter(b -> b.get("bus").getAsString().equals(name)).findFirst()
        .orElseThrow(() -> new AssertionError("no bus " + name + " in " + result));
  }

  @Test
  @DisplayName("finds the rio bus and both CANivores by field name, not by 'can' substrings")
  void discoversBuses() {
    var r = call("analyze_can_bus", "canivore");
    assertEquals("ok", r.get("status").getAsString());
    var names = objects(r.getAsJsonArray("buses")).stream()
        .map(b -> b.get("bus").getAsString()).toList();
    assertEquals(List.of("rio", "CANHD", "CAN2"), names);
    var text = r.toString();
    assertFalse(text.contains("Canandgyro"), "Canandgyro is not CAN bus data");
    assertFalse(text.contains("ScanRate"), "scan is not CAN");
    assertFalse(text.contains("CanSeeGamePiece"), "'can see' is not CAN");
    assertEquals("/RealOutputs/CANBus/CANHD/TEC",
        bus(r, "CANHD").getAsJsonObject("entries").get("tec").getAsString());
  }

  @Test
  @DisplayName("TEC: maximum 85 (first reached at 34.98 s) while enabled, no excursion (B3)")
  void tecExcursion() {
    var r = call("analyze_can_bus", "canivore", "bus_name", "CANHD");
    assertEquals(1, r.getAsJsonArray("buses").size(), "bus_name selects one bus");
    var tec = bus(r, "CANHD").getAsJsonObject("tec");
    assertEquals(85.0, tec.get("max").getAsDouble(), 1e-9);
    // 17 * (34.98 - 30) rounds to 85; the value is then held (logged on change only)
    assertEquals(34.98, tec.get("max_time_sec").getAsDouble(), 1e-6);
    assertEquals(0, tec.get("error_passive_excursions").getAsInt());
    assertEquals(85.0, tec.getAsJsonObject("while_enabled").get("max").getAsDouble(), 1e-9);
  }

  @Test
  @DisplayName("utilization reported as 0-1 fractions is converted to percent")
  void utilizationUnits() {
    var r = call("analyze_can_bus", "canivore", "bus_name", "rio");
    var util = bus(r, "rio").getAsJsonObject("utilization");
    assertEquals(35.0, util.get("mean_percent").getAsDouble(), 1e-4);
    assertEquals(35.0, util.get("max_percent").getAsDouble(), 1e-4);
    assertTrue(util.get("unit_detected").getAsString().startsWith("fraction"));
    var compat = objects(r.getAsJsonArray("utilization")).get(0);
    assertEquals(35.0, compat.get("avg_percent").getAsDouble(), 1e-4);
  }

  @Test
  @DisplayName("an unknown bus_name is no_match with the available buses")
  void unknownBus() {
    var r = call("analyze_can_bus", "canivore", "bus_name", "CAN9");
    assertEquals("no_match", r.get("status").getAsString());
    assertEquals(List.of("rio", "CANHD", "CAN2"), strings(r.getAsJsonArray("available_buses")));
  }

  @Test
  @DisplayName("a log with no CAN data is no_match, not an empty success")
  void noCanData() {
    var r = call("analyze_can_bus", "swerve_array");
    assertFalse(r.get("success").getAsBoolean());
    assertEquals("no_match", r.get("status").getAsString());
    assertTrue(r.getAsJsonArray("looked_for").size() > 0);
  }

  @Test
  @DisplayName("can_health: a disabled-state CAN timeout does not count; counters are included")
  void canHealth() {
    var r = call("can_health", "canivore");
    assertEquals(0, r.get("errors_while_enabled").getAsLong());
    assertEquals(1, r.get("errors_while_disabled").getAsLong());
    assertEquals("GOOD", r.get("health_assessment").getAsString(), r.toString());
    var counters = objects(r.getAsJsonArray("bus_counters"));
    var canhd = counters.stream().filter(c -> c.get("bus").getAsString().equals("CANHD"))
        .findFirst().orElseThrow();
    assertEquals(85.0, canhd.get("tec_max_while_enabled").getAsDouble(), 1e-9);
  }

  @Test
  @DisplayName("can_health: CAN text lines are words, not substrings; 'default' is not a fault")
  void canTextClassification() {
    assertTrue(CanBusAnalysis.isCanErrorLine("CAN frame timeout: device 12"));
    assertTrue(CanBusAnalysis.isCanErrorLine("ERROR -3 CAN: Message not found"));
    assertTrue(CanBusAnalysis.isCanErrorLine("CANivore 'CANHD' bus error"));
    assertTrue(CanBusAnalysis.isCanErrorLine("CANcoder 21 fault"));
    assertFalse(CanBusAnalysis.isCanErrorLine("Cannot open file: error 2"));
    assertFalse(CanBusAnalysis.isCanErrorLine("Vision scan error"));
    assertFalse(CanBusAnalysis.isCanErrorLine("CAN device using default config"));
    assertFalse(CanBusAnalysis.isCanErrorLine("Canandgyro calibrated"));
    assertFalse(CanBusAnalysis.isCanErrorLine("Cancel command: timeout"));
  }

  @Test
  @DisplayName("can_health: an error before the first DriverStation sample is state_unknown")
  void unknownStateError() {
    var r = call("can_health", "no_ds");
    assertEquals(1, r.get("errors_state_unknown").getAsLong());
    assertEquals(0, r.get("errors_while_enabled").getAsLong());
    assertEquals("UNKNOWN", r.get("health_assessment").getAsString());
    assertTrue(r.getAsJsonArray("warnings").toString().contains("No DriverStation"));
  }

  @Test
  @DisplayName("other CAN error entries: increases, not samples above zero")
  void otherErrorEntryNames() {
    assertTrue(FrcDomainTools.AnalyzeCanBusTool.isOtherCanErrorEntry("/CANBus/Error/Tx"));
    assertTrue(FrcDomainTools.AnalyzeCanBusTool.isOtherCanErrorEntry("/CAN/TimeoutCount"));
    assertFalse(FrcDomainTools.AnalyzeCanBusTool.isOtherCanErrorEntry("/Drive/Canandgyro/Fault"));
    assertFalse(FrcDomainTools.AnalyzeCanBusTool.isOtherCanErrorEntry("/Vision/ScanError"));
    assertFalse(FrcDomainTools.AnalyzeCanBusTool.isOtherCanErrorEntry("/CAN/DefaultConfig"));
  }
}
