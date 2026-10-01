/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** analyze_replay_drift on the fixture corpus (review issues A3, B10). */
@DisplayName("analyze_replay_drift on fixture logs")
class ReplayFixtureTest extends FixtureToolTestBase {

  @Test
  @DisplayName("identical replay: every pair compared, no divergence (arrays equal by value)")
  void identical() {
    var r = call("analyze_replay_drift", "replay_identical");
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    assertEquals(3, r.get("pairs_compared").getAsInt());
    assertEquals(0, r.get("divergent_count").getAsInt(),
        "double[] entries compare by value, not by array identity");
    assertEquals(1, r.get("real_only_count").getAsInt()); // /RealOutputs/Only/InReal
  }

  @Test
  @DisplayName("divergent replay: the real divergence is found; 1e-12 pose noise is not one")
  void divergent() {
    var r = call("analyze_replay_drift", "replay_divergent");
    assertEquals(1, r.get("divergent_count").getAsInt(), r.toString());
    var d = objects(r.getAsJsonArray("divergences")).get(0);
    assertEquals("/RealOutputs/Shooter/SpeedRPS", d.get("entry").getAsString());
    assertEquals(30.0, d.get("first_divergence_time").getAsDouble(), 1e-9);
    assertEquals(0.5, d.get("max_abs_difference").getAsDouble(), 1e-9);
    assertEquals(501, d.get("divergent_samples").getAsInt()); // 30.00 s to 40.00 s at 50 Hz
  }

  @Test
  @DisplayName("a real-robot log is not_applicable, not '0 divergences' (A3)")
  void realRobotLog() {
    var r = call("analyze_replay_drift", "akit_match");
    assertFalse(r.get("success").getAsBoolean());
    assertEquals("not_applicable", r.get("status").getAsString());
    assertTrue(r.get("reason").getAsString().contains("/ReplayOutputs/"));
    assertTrue(r.get("hint").getAsString().contains("_sim"));
  }

  @Test
  @DisplayName("the divergence list reports its true total when limited")
  void limited() {
    var r = call("analyze_replay_drift", "replay_divergent", "limit", 1);
    var limits = r.getAsJsonObject("limits").getAsJsonObject("divergences");
    assertEquals(1, limits.get("total").getAsInt());
    assertEquals(1, limits.get("returned").getAsInt());
  }
}
