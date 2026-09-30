/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** analyze_auto on the fixture corpus (review issues A2, B7). */
@DisplayName("analyze_auto on fixture logs")
class AnalyzeAutoFixtureTest extends FixtureToolTestBase {

  @Test
  @DisplayName("practice session: not_applicable, naming the held-false Autonomous entry (A2)")
  void practiceIsNotApplicable() {
    var r = call("analyze_auto", "akit_practice");
    assertFalse(r.get("success").getAsBoolean());
    assertEquals("not_applicable", r.get("status").getAsString());
    var reason = r.get("reason").getAsString();
    assertTrue(reason.contains("/DriverStation/Autonomous"), reason);
    assertTrue(reason.contains("1 sample(s), all false"), reason);
  }

  @Test
  @DisplayName("FMS match: the auto period, and the routine selected at its start (B7)")
  void matchAuto() {
    var r = call("analyze_auto", "akit_match");
    assertTrue(r.get("success").getAsBoolean(), r.toString());
    assertEquals(20.0, r.get("auto_start_time").getAsDouble(), 1e-9);
    assertEquals(40.0, r.get("auto_end_time").getAsDouble(), 1e-9);
    assertEquals(20.0, r.get("auto_duration").getAsDouble(), 1e-9);
    assertEquals(1, r.getAsJsonArray("auto_periods").size());
    // The routine is logged under a team's own name, not a chooser: a candidate, not a guess
    assertFalse(r.has("selected_routine"));
    var skipped = r.getAsJsonArray("skipped").toString();
    assertTrue(skipped.contains("/RealOutputs/AutoSelector/SelectedAutoMode")
        && skipped.contains("chooser_entry"), skipped);
    // no setpoint pose in this log: the section is reported as skipped, and the result partial
    assertEquals("partial", r.get("status").getAsString());
    assertTrue(skipped.contains("path_following_error"));
    assertFalse(r.has("path_following_error"));

    // Confirmed and passed: "Do Nothing" at 6 s, changed to "Two Piece Center" at 12 s; the value
    // at auto start counts
    var named = call("analyze_auto", "akit_match", "chooser_entry",
        "/RealOutputs/AutoSelector/SelectedAutoMode");
    assertEquals("Two Piece Center", named.get("selected_routine").getAsString());
    var inputs = named.getAsJsonObject("inputs").getAsJsonObject("entries");
    assertEquals("/RealOutputs/AutoSelector/SelectedAutoMode",
        inputs.get("selected_routine").getAsString());
  }

  @Test
  @DisplayName("no DriverStation data: no_match")
  void noDs() {
    var r = call("analyze_auto", "no_ds");
    assertEquals("no_match", r.get("status").getAsString());
  }

  @Test
  @DisplayName("chooser ranking: /active beats selected-mode names beats 'chooser'; metadata skipped")
  void chooserRanking() {
    assertEquals(0, FrcDomainTools.AnalyzeAutoTool.chooserRank("nt:/smartdashboard/auto chooser/active"));
    assertEquals(1, FrcDomainTools.AnalyzeAutoTool.chooserRank("/realoutputs/autoselector/selectedautomode"));
    assertEquals(2, FrcDomainTools.AnalyzeAutoTool.chooserRank("/dashboardinputs/chooser"));
    assertEquals(Integer.MAX_VALUE,
        FrcDomainTools.AnalyzeAutoTool.chooserRank("nt:/smartdashboard/auto chooser/.type"));
    assertEquals(Integer.MAX_VALUE,
        FrcDomainTools.AnalyzeAutoTool.chooserRank("nt:/smartdashboard/auto chooser/default"));
    assertEquals(Integer.MAX_VALUE, FrcDomainTools.AnalyzeAutoTool.chooserRank("/drive/pose"));
  }
}
