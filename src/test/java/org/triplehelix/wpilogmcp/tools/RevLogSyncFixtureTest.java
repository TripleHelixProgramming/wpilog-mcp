/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;

/**
 * The revlog tools end to end on a real file pair: a wpilog and the REV log recorded beside it
 * (WPILOG format, SPARK MAX Periodic Status 0 frames), discovered by recording time, synchronized
 * by cross-correlating the motor's applied output, and read back on the wpilog's clock.
 */
@DisplayName("revlog discovery, sync, and data on the revlog_pair fixture")
class RevLogSyncFixtureTest extends FixtureToolTestBase {

  static final String KEY = "REV/SparkMax_3/AppliedOutput";

  @BeforeEach
  void synced() {
    // A fresh load and sync for each test (set_revlog_offset changes the cached sync)
    org.triplehelix.wpilogmcp.log.LogManager.getInstance().unloadAllLogs();
    var r = call("wait_for_sync", "revlog_pair", "timeout_ms", 30_000);
    assertTrue(r.has("completed") && r.get("completed").getAsBoolean(), r.toString());
  }

  @Test
  @DisplayName("the revlog is found by recording time and synchronized to the known offset")
  void syncStatus() {
    var r = call("sync_status", "revlog_pair", "include_signal_pairs", true);
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    assertEquals(1, r.get("revlog_count").getAsInt());
    // The fixture's name carries no time, so the REV log's name is read as the roboRIO's UTC
    assertTrue(r.get("revlog_filename_zone").getAsString().startsWith("UTC, the roboRIO's"),
        r.toString());
    var sync = r.getAsJsonArray("revlogs").get(0).getAsJsonObject().getAsJsonObject("sync");
    assertEquals("CROSS_CORRELATION", sync.get("method").getAsString(), sync.toString());
    assertEquals(FixtureLogs.REVLOG_PAIR_OFFSET_SEC, sync.get("offset_seconds").getAsDouble(),
        0.02, "cross-correlation recovers the true offset, 0.3 s from the coarse estimate");
    var pairs = r.getAsJsonArray("revlogs").get(0).getAsJsonObject()
        .getAsJsonArray("signal_pairs").toString();
    assertTrue(pairs.contains("/Drive/FrontLeft/AppliedOutput"), pairs);
  }

  @Test
  @DisplayName("signals are listed, and data comes back on the wpilog's clock")
  void signalsAndData() {
    var list = call("list_revlog_signals", "revlog_pair");
    assertTrue(list.getAsJsonArray("signals").toString().contains(KEY), list.toString());

    var data = call("get_revlog_data", "revlog_pair", "signal_key", KEY, "start_time", 30.0,
        "end_time", 31.0, "include_stats", true);
    assertEquals("ok", data.get("status").getAsString(), data.toString());
    var points = data.getAsJsonArray("data");
    assertTrue(points.size() >= 90, "100 Hz for one second: " + points.size());
    for (var p : points) {
      double t = p.getAsJsonObject().get("timestamp").getAsDouble();
      double v = p.getAsJsonObject().get("value").getAsDouble();
      assertTrue(t >= 30.0 && t <= 31.0, "FPGA time " + t);
      // The same output the wpilog logged at that FPGA time (0.0001 quantization; the sync
      // error times the signal's slope, at most a few hundredths)
      assertEquals(FixtureLogs.revlogPairOutput(t), v, 0.05, "at " + t);
    }
  }

  @Test
  @DisplayName("status 0 decodes per REV's spec: bus voltage, current, temperature, inversion")
  void status0Fields() {
    var cases = java.util.Map.of(
        "BusVoltage", new double[] {FixtureLogs.REVLOG_PAIR_BUS_VOLTS, 30.0 / 4095},
        "OutputCurrent", new double[] {FixtureLogs.REVLOG_PAIR_AMPS, 150.0 / 4095},
        "MotorTemperature", new double[] {FixtureLogs.REVLOG_PAIR_TEMP_C, 0},
        "IsInverted", new double[] {0, 0});
    for (var c : cases.entrySet()) {
      var data = call("get_revlog_data", "revlog_pair", "signal_key",
          "REV/SparkMax_3/" + c.getKey(), "start_time", 30.0, "end_time", 30.5);
      var points = data.getAsJsonArray("data");
      assertFalse(points.isEmpty(), data.toString());
      for (var p : points) {
        assertEquals(c.getValue()[0], p.getAsJsonObject().get("value").getAsDouble(),
            c.getValue()[1] / 2 + 1e-9, c.getKey());
      }
    }
    var list = call("list_revlog_signals", "revlog_pair", "signal_filter", "Voltage");
    assertTrue(list.toString().contains("\"unit\":\"V\""), list.toString());
  }

  @Test
  @DisplayName("a REV log named by a desktop's local clock is read in the zone the wpilog shows")
  void desktopZone(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    // Simulation on a desktop at UTC-05:00: AdvantageKit names the wpilog and REVLib the REV log
    // by the local clock; EpochTimeMicros is true epoch time
    var zone = java.time.ZoneOffset.ofHours(-5);
    var named = FixtureLogs.namedAt(FixtureLogs.REVLOG_PAIR_WALL.plusSeconds(3), zone);
    var wpilogName = "akit_" + named.format(
        java.time.format.DateTimeFormatter.ofPattern("yy-MM-dd_HH-mm-ss")) + ".wpilog";
    var wpilog = FixtureLogs.writeRevlogPair(dir, wpilogName, zone,
        "/SystemStats/EpochTimeMicros");
    // A decoy named for the same instant in UTC: 5 hours off in the desktop's zone
    var decoy = "REV_" + FixtureLogs.REVLOG_PAIR_WALL.plusSeconds(5).format(
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".revlog";
    FixtureLogs.writeRevlog(dir.resolve(decoy));
    org.triplehelix.wpilogmcp.log.LogManager.getInstance().addAllowedDirectory(dir);

    var wait = callPath("wait_for_sync", wpilog, "timeout_ms", 30_000);
    assertTrue(wait.get("completed").getAsBoolean(), wait.toString());
    var r = callPath("sync_status", wpilog);
    assertEquals(1, r.get("revlog_count").getAsInt(), r.toString());
    var revlog = r.getAsJsonArray("revlogs").get(0).getAsJsonObject();
    assertFalse(revlog.get("path").getAsString().endsWith(decoy), revlog.toString());
    assertTrue(r.get("revlog_filename_zone").getAsString().startsWith("UTC-05:00"),
        r.toString());
    assertEquals(FixtureLogs.REVLOG_PAIR_OFFSET_SEC,
        revlog.getAsJsonObject("sync").get("offset_seconds").getAsDouble(), 0.02,
        revlog.toString());
  }

  static com.google.gson.JsonObject callPath(String tool, java.nio.file.Path path,
      Object... keyValues) throws Exception {
    var args = new com.google.gson.JsonObject();
    args.addProperty("path", path.toString());
    for (int i = 0; i < keyValues.length; i += 2) {
      args.addProperty((String) keyValues[i], (Number) keyValues[i + 1]);
    }
    return tools.get(tool).execute(args).getAsJsonObject();
  }

  @Test
  @DisplayName("set_revlog_offset replaces the automatic offset")
  void userOffset() {
    var r = call("set_revlog_offset", "revlog_pair", "offset_ms", 15_300.0);
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    assertEquals("USER_PROVIDED", r.get("new_method").getAsString());
    var status = call("sync_status", "revlog_pair");
    var sync = status.getAsJsonArray("revlogs").get(0).getAsJsonObject().getAsJsonObject("sync");
    assertEquals(15.3, sync.get("offset_seconds").getAsDouble(), 1e-9);
  }
}
