/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** analyze_loop_timing on the fixture corpus (review issue B1). */
@DisplayName("analyze_loop_timing on fixture logs")
class LoopTimingFixtureTest extends FixtureToolTestBase {

  @Test
  @DisplayName("AdvantageKit FullCycleMS is found, its unit comes from the name, boot cycle excluded")
  void advantageKit() {
    var r = call("analyze_loop_timing", "akit_match", "scope", "enabled", "threshold_ms", 25);
    assertEquals("/RealOutputs/LoggedRobot/FullCycleMS", r.get("loop_time_entry").getAsString());
    assertEquals("ms", r.getAsJsonObject("unit").get("value").getAsString());
    assertTrue(r.getAsJsonObject("unit").get("basis").getAsString().startsWith("name"));
    // Enabled loops: every 25th is a 31 ms overrun, i.e. 4 %
    assertEquals(4.0, r.get("percent_over_threshold").getAsDouble(), 0.05);
    assertEquals("/RealOutputs/LoggedRobot/UserCodeMS",
        r.getAsJsonObject("user_code").get("entry").getAsString());
    var all = call("analyze_loop_timing", "akit_match");
    assertEquals(9603.5, all.getAsJsonObject("excluded_boot_cycle").get("loop_time_ms")
        .getAsDouble(), 1e-9);
    assertTrue(all.getAsJsonObject("statistics").get("max_ms").getAsDouble() < 100);
  }

  @Test
  @DisplayName("explicit entry: UserCodeMS instead of the full cycle")
  void explicitEntry() {
    var r = call("analyze_loop_timing", "akit_match", "entry",
        "/RealOutputs/LoggedRobot/UserCodeMS");
    assertEquals("/RealOutputs/LoggedRobot/UserCodeMS", r.get("loop_time_entry").getAsString());
    assertFalse(r.has("user_code"));
  }

  @Test
  @DisplayName("no loop entry: no_match, counting the WPILib overrun messages it could search")
  void wpilibWithoutEntry() {
    var r = call("analyze_loop_timing", "wpilib_dlm");
    assertEquals("no_match", r.get("status").getAsString());
    assertEquals(2, r.get("overrun_messages").getAsInt());
    assertTrue(r.get("hint").getAsString().contains("search_strings"));
  }

  @Test
  @DisplayName("an invalid unit is an error")
  void invalidUnit() {
    var r = call("analyze_loop_timing", "akit_match", "unit", "minutes");
    assertEquals("error", r.get("status").getAsString());
  }
}
