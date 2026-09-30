/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** get_match_phases and get_ds_timeline on the fixture corpus (review issues E1, E2). */
@DisplayName("get_match_phases and get_ds_timeline on fixture logs")
class MatchPhasesFixtureTest extends FixtureToolTestBase {

  @Test
  @DisplayName("practice session: four enabled segments, no invented match (E1, E2)")
  void practiceSession() {
    var r = call("get_match_phases", "akit_practice");
    assertTrue(r.get("success").getAsBoolean());
    assertEquals("ok", r.get("status").getAsString());
    assertEquals(4, r.get("enabled_segment_count").getAsInt());
    var enabled = objects(r.getAsJsonArray("segments")).stream()
        .filter(s -> s.get("state").getAsString().equals("enabled")).toList();
    assertEquals(4, enabled.size());
    assertEquals("log_end", enabled.get(3).get("end_reason").getAsString());
    assertEquals("disabled", enabled.get(0).get("end_reason").getAsString());
    enabled.forEach(s -> assertEquals("teleop", s.get("mode").getAsString()));
    assertEquals(0, r.getAsJsonArray("matches").size());
    assertEquals(0, r.getAsJsonObject("phases").size(), "no span across disabled gaps");
    var notes = r.getAsJsonArray("notes").toString();
    assertTrue(notes.contains("Autonomous was never true"), notes);
    assertTrue(notes.contains("1 sample(s)"), notes);
    assertTrue(r.getAsJsonArray("warnings").toString().contains("ends while the robot is enabled"));
    var inputs = r.getAsJsonObject("inputs").getAsJsonObject("entries");
    assertEquals("/DriverStation/Enabled", inputs.get("enabled").getAsString());
    assertEquals("/DriverStation/Autonomous", inputs.get("autonomous").getAsString());
    assertFalse(r.has("data_quality"), "a transition timeline is not a statistic");
  }

  @Test
  @DisplayName("FMS match: phases, matches, durations, and the season's endgame")
  void fmsMatch() {
    var r = call("get_match_phases", "akit_match");
    var phases = r.getAsJsonObject("phases");
    assertEquals(20.0, phases.getAsJsonObject("autonomous").get("start").getAsDouble(), 1e-9);
    assertEquals(40.0, phases.getAsJsonObject("autonomous").get("end").getAsDouble(), 1e-9);
    assertEquals(43.0, phases.getAsJsonObject("teleop").get("start").getAsDouble(), 1e-9);
    assertEquals(183.0, phases.getAsJsonObject("teleop").get("end").getAsDouble(), 1e-9);
    var endgame = phases.getAsJsonObject("endgame");
    assertEquals(153.0, endgame.get("start").getAsDouble(), 1e-9);
    assertEquals("game_timing", endgame.get("basis").getAsString());
    assertEquals(163.0, r.get("match_duration").getAsDouble(), 1e-9);
    assertEquals(20.0, r.get("auto_duration").getAsDouble(), 1e-9);
    assertEquals(140.0, r.get("teleop_duration").getAsDouble(), 1e-9);
    var match = r.getAsJsonArray("matches").get(0).getAsJsonObject();
    assertEquals("fms_attached", match.get("basis").getAsString());
    assertTrue(match.get("complete").getAsBoolean());
    assertEquals(2026, match.getAsJsonObject("expected_timing").get("season").getAsInt());
    assertEquals(2026, r.getAsJsonObject("season").get("year").getAsInt());
    assertFalse(r.has("warnings"), r.toString());
  }

  @Test
  @DisplayName("WPILib DS: entries with FMS known only from the NetworkTables control word")
  void wpilibMatch() {
    var r = call("get_match_phases", "wpilib_dlm");
    var match = r.getAsJsonArray("matches").get(0).getAsJsonObject();
    assertEquals("fms_attached", match.get("basis").getAsString());
    assertEquals(10.0, match.getAsJsonObject("autonomous").get("start").getAsDouble(), 1e-9);
    assertEquals(2025, r.getAsJsonObject("season").get("year").getAsInt());
    assertEquals("NT:/FMSInfo/FMSControlData",
        r.getAsJsonObject("inputs").getAsJsonObject("entries").get("control_word").getAsString());
  }

  @Test
  @DisplayName("no DriverStation data: no_match with what was searched")
  void noDriverStation() {
    var r = call("get_match_phases", "no_ds");
    assertFalse(r.get("success").getAsBoolean());
    assertEquals("no_match", r.get("status").getAsString());
    assertTrue(r.getAsJsonArray("looked_for").size() >= 2);
    assertTrue(r.has("hint"));
  }

  @Test
  @DisplayName("dual DS naming: one set of events, and a warning naming the ignored entries")
  void dualDsTimeline() {
    var r = call("get_ds_timeline", "dual_ds");
    long enables = objects(r.getAsJsonArray("events")).stream()
        .filter(e -> e.get("type").getAsString().equals("ENABLED")).count();
    assertEquals(2, enables, "no duplicated events");
    assertTrue(r.getAsJsonArray("warnings").toString().contains("DS:enabled"));
    var phases = call("get_match_phases", "dual_ds");
    assertTrue(phases.getAsJsonArray("notes").toString().contains("DS:enabled"));
  }

  @Test
  @DisplayName("practice timeline: a TELEOP_START for each teleop enable, no AUTO_START")
  void practiceTimeline() {
    var r = call("get_ds_timeline", "akit_practice");
    var types = objects(r.getAsJsonArray("events")).stream()
        .filter(e -> e.get("category").getAsString().equals("match_phase"))
        .map(e -> e.get("type").getAsString()).toList();
    assertEquals(List.of("TELEOP_START", "TELEOP_START", "TELEOP_START", "TELEOP_START"), types);
  }

  @Test
  @DisplayName("the timeline and get_match_phases agree on every enable")
  void timelineAgreesWithPhases() {
    for (var id : List.of("akit_match", "akit_practice", "wpilib_dlm", "dual_ds")) {
      var phases = call("get_match_phases", id);
      var enableStarts = objects(phases.getAsJsonArray("segments")).stream()
          .filter(s -> s.get("state").getAsString().equals("enabled"))
          .map(s -> s.get("start").getAsDouble()).distinct().toList();
      var timeline = call("get_ds_timeline", id);
      var enableEvents = objects(timeline.getAsJsonArray("events")).stream()
          .filter(e -> e.get("type").getAsString().equals("ENABLED"))
          .map(e -> e.get("timestamp").getAsDouble()).toList();
      // every ENABLED event starts an enabled segment (mode changes add segments, not events)
      assertTrue(enableStarts.containsAll(enableEvents), id + ": " + enableEvents);
    }
  }
}
