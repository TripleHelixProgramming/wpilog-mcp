/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** compare_matches: scope per log, percentiles, boot transients (review issue E4). */
@DisplayName("compare_matches on fixture logs")
class CompareMatchesFixtureTest extends FixtureToolTestBase {

  @Test
  @DisplayName("the boot loop dominates a whole-log max, and is flagged")
  void bootTransient() {
    var r = call("compare_matches", "akit_match", "compare_path",
        fixturePath("akit_practice").toString(), "name", "/RealOutputs/LoggedRobot/FullCycleMS");
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    for (var c : r.getAsJsonArray("comparisons")) {
      var comparison = c.getAsJsonObject();
      assertEquals(9603.5, comparison.getAsJsonObject("statistics").get("max").getAsDouble());
      assertTrue(comparison.get("max_likely_boot_transient").getAsBoolean(), c.toString());
    }
    assertTrue(r.getAsJsonArray("warnings").toString().contains("likely a boot transient"));
    assertTrue(r.has("differences"));
  }

  @Test
  @DisplayName("scope enabled is resolved in each log's own timeline; percentiles are robust")
  void enabledScope() {
    var r = call("compare_matches", "akit_match", "compare_path",
        fixturePath("akit_practice").toString(), "name", "/RealOutputs/LoggedRobot/FullCycleMS",
        "scope", "enabled");
    assertFalse(r.has("warnings"), r.toString());
    for (var c : r.getAsJsonArray("comparisons")) {
      var comparison = c.getAsJsonObject();
      var stats = comparison.getAsJsonObject("statistics");
      assertEquals(33.0, stats.get("max").getAsDouble()); // the largest enabled overrun
      assertTrue(stats.get("median").getAsDouble() < 20);
      assertTrue(stats.has("p95") && stats.has("p25") && stats.has("std_dev"));
      assertEquals("enabled", comparison.getAsJsonObject("scope").get("scope").getAsString());
      assertTrue(comparison.has("data_quality"));
    }
    var differences = r.getAsJsonObject("differences");
    assertTrue(differences.has("mean") && differences.has("median") && differences.has("p95"));
  }

  @Test
  @DisplayName("the description names every per-log statistic by its key")
  void descriptionNamesStatistics() {
    // It said "count" for sample_count, and "with when" for min_at_sec and max_at_sec
    var r = call("compare_matches", "akit_match", "compare_path",
        fixturePath("akit_practice").toString(), "name", "/RealOutputs/LoggedRobot/FullCycleMS");
    var description = tools.get("compare_matches").description();
    var keys = new java.util.TreeSet<String>();
    for (var c : r.getAsJsonArray("comparisons")) {
      var comparison = c.getAsJsonObject();
      assertTrue(comparison.has("sample_count"), c.toString());
      keys.add("sample_count");
      keys.addAll(comparison.getAsJsonObject("statistics").keySet());
    }
    for (var key : keys) {
      assertTrue(java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(key) + "\\b")
          .matcher(description).find(), key + " is not named in the description");
    }
  }

  @Test
  @DisplayName("a field path; a log without the entry makes the result partial")
  void fieldPathAndPartial() {
    // the practice fixture logs no PowerDistribution channels
    var r = call("compare_matches", "akit_match", "compare_path",
        fixturePath("akit_practice").toString(), "name", "/PowerDistribution/ChannelCurrent[3]",
        "scope", "enabled");
    assertEquals("partial", r.get("status").getAsString(), r.toString());
    assertTrue(r.get("success").getAsBoolean());
    var first = r.getAsJsonArray("comparisons").get(0).getAsJsonObject();
    assertEquals("/PowerDistribution/ChannelCurrent[3]", first.get("signal").getAsString());
    assertEquals(6.0, first.getAsJsonObject("statistics").get("max").getAsDouble(), 1e-5);
    var second = r.getAsJsonArray("comparisons").get(1).getAsJsonObject();
    assertFalse(second.get("entry_found").getAsBoolean());
    assertFalse(r.has("differences"));
    assertEquals("differences", r.getAsJsonArray("skipped").get(0).getAsJsonObject()
        .get("section").getAsString());
  }

  @Test
  @DisplayName("a log that cannot be scoped is reported; the other is still described")
  void unscopableLog() {
    // no_ds has no DriverStation entries, so scope 'enabled' cannot apply to it
    var r = call("compare_matches", "akit_match", "compare_path",
        fixturePath("no_ds").toString(), "name", "/SystemStats/BatteryVoltage", "scope",
        "enabled");
    assertEquals("partial", r.get("status").getAsString(), r.toString());
    var second = r.getAsJsonArray("comparisons").get(1).getAsJsonObject();
    assertTrue(second.get("entry_found").getAsBoolean());
    assertTrue(second.get("reason").getAsString().contains("no DriverStation state entries"),
        second.toString());
    assertTrue(r.getAsJsonArray("comparisons").get(0).getAsJsonObject().has("statistics"));
  }

  @Test
  @DisplayName("missing in both logs: no_match with the reason")
  void missingEverywhere() {
    var r = call("compare_matches", "akit_match", "compare_path",
        fixturePath("akit_practice").toString(), "name", "/No/Such/Entry");
    assertEquals("no_match", r.get("status").getAsString(), r.toString());
    assertFalse(r.get("success").getAsBoolean());
    for (var c : r.getAsJsonArray("comparisons")) {
      assertFalse(c.getAsJsonObject().get("entry_found").getAsBoolean());
    }
  }
}
