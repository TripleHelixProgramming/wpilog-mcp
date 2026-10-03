/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
/*
 * The values below were taken from Team 4065's sample log, used under its license:
 *
 * MIT License
 *
 * Copyright (c) 2026 Team 4065
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.triplehelix.wpilogmcp.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * Golden values from a real elimination match of another team: Team 4065's sample of the 2026
 * World Championship's Elimination 4 ({@code akit_cmptx_e4_sample.wpilog}, from
 * Team4065/NOP-power-analysis, MIT), trimmed by the team to the match and 25 power entries.
 * It holds what the review log does not: a full FMS match with the disabled gap between auto and
 * teleop, a brownout threshold the robot code changes at the enable, and a roboRIO brownout with
 * the logged voltage still above 7 V.
 *
 * <p>Opt-in: {@code ./gradlew test --tests '*MatchSampleGoldenTest' -PgoldenMatchLog=/path/to/log}.
 * The log is not in the repository. Every value was computed independently with WPILib's
 * {@code wpiutil.log.DataLogReader} and numpy on 2026-10-02 (numpy's default percentiles; sample
 * standard deviation), over the windows {@code /DriverStation/Enabled} gives, half-open.
 */
@DisplayName("Golden values on another team's match")
class MatchSampleGoldenTest {

  static final double AUTO_START = 234.926208;
  static final double AUTO_END = 255.147693;
  static final double TELEOP_START = 259.26084;
  static final double TELEOP_END = 399.88251;
  static final double LOG_START = 229.926208;
  static final double LOG_END = 402.856854;
  static final double TIME = 1e-6;

  static Path logPath;
  static Map<String, Tool> tools;

  @BeforeAll
  static void setUp() {
    String property = System.getProperty("golden.matchlog");
    Assumptions.assumeTrue(property != null && !property.isBlank(),
        "golden.matchlog not set; run with -PgoldenMatchLog=/path/to/akit_cmptx_e4_sample.wpilog");
    logPath = Path.of(property).toAbsolutePath().normalize();
    Assumptions.assumeTrue(Files.exists(logPath), "golden log not found: " + logPath);
    LogManager.getInstance().addAllowedDirectory(logPath.getParent());
    var captured = new java.util.TreeMap<String, Tool>();
    WpilogTools.registerAll(new ToolRegistry() {
      @Override
      public void registerTool(Tool tool) {
        captured.put(tool.name(), tool);
        super.registerTool(tool);
      }
    });
    tools = captured;
  }

  @AfterAll
  static void tearDown() {
    LogManager.getInstance().unloadAllLogs();
  }

  static JsonObject call(String tool, Object... keyValues) throws Exception {
    var args = new JsonObject();
    args.addProperty("path", logPath.toString());
    for (int i = 0; i < keyValues.length; i += 2) {
      var key = (String) keyValues[i];
      var value = keyValues[i + 1];
      if (value instanceof JsonElement json) args.add(key, json);
      else if (value instanceof Number n) args.addProperty(key, n);
      else args.addProperty(key, value.toString());
    }
    var result = tools.get(tool).execute(args).getAsJsonObject();
    assertTrue(result.get("success").getAsBoolean(), tool + " failed: " + result);
    return result;
  }

  static double num(JsonObject o, String key) {
    assertTrue(o.has(key) && !o.get(key).isJsonNull(), "no " + key + " in " + o);
    return o.get(key).getAsDouble();
  }

  /** {@code expected} to within one part in 10^9 (the order in which sums are added). */
  static void same(double expected, JsonObject o, String key) {
    assertEquals(expected, num(o, key), 1e-9 * Math.max(1, Math.abs(expected)), key + " in " + o);
  }

  @Test
  @DisplayName("list_entries: 25 entries from 229.926 to 402.857 s, read to the end")
  void inventory() throws Exception {
    var r = call("list_entries");
    assertEquals(25, r.get("entry_count").getAsInt());
    var range = r.getAsJsonObject("time_range_sec");
    assertEquals(LOG_START, num(range, "start"), TIME);
    assertEquals(LOG_END, num(range, "end"), TIME);
    assertFalse(r.has("truncated") && r.get("truncated").getAsBoolean(), r.toString());
  }

  @Test
  @DisplayName("get_match_phases: auto, the disabled gap, teleop, held from the logged changes")
  void segments() throws Exception {
    var r = call("get_match_phases");
    // Enabled false/true/false/true/false; Autonomous true until 259.26084, the teleop enable
    var expected = List.of(
        new Object[] {LOG_START, AUTO_START, "disabled", null, "enabled"},
        new Object[] {AUTO_START, AUTO_END, "enabled", "auto", "disabled"},
        new Object[] {AUTO_END, TELEOP_START, "disabled", null, "enabled"},
        new Object[] {TELEOP_START, TELEOP_END, "enabled", "teleop", "disabled"},
        new Object[] {TELEOP_END, LOG_END, "disabled", null, "log_end"});
    var segments = r.getAsJsonArray("segments");
    assertEquals(expected.size(), segments.size(), segments.toString());
    for (int i = 0; i < expected.size(); i++) {
      var s = segments.get(i).getAsJsonObject();
      var e = expected.get(i);
      assertEquals((double) e[0], num(s, "start"), TIME, "segment " + i);
      assertEquals((double) e[1], num(s, "end"), TIME, "segment " + i);
      assertEquals(e[2], s.get("state").getAsString(), "segment " + i);
      if (e[3] != null) assertEquals(e[3], s.get("mode").getAsString(), "segment " + i);
      assertEquals(e[4], s.get("end_reason").getAsString(), "segment " + i);
    }
    assertEquals(2, r.get("enabled_segment_count").getAsInt());
  }

  @Test
  @DisplayName("the match: no FMS entry, so found by its mode sequence; complete, with the endgame")
  void match() throws Exception {
    var r = call("get_match_phases");
    // The trimmed log records no year, so the season is the current one; the timing below is
    // the 2026 game's, which the log's own match clock confirms: it counts auto down from 20 s
    // and teleop from 140 s, and reads 30 s left at 370.44 s
    int season = r.getAsJsonObject("season").get("year").getAsInt();
    Assumptions.assumeTrue(season == 2026,
        "the season is taken from the current year (" + season + "), not the match's 2026");
    var matches = r.getAsJsonArray("matches");
    assertEquals(1, matches.size(), matches.toString());
    var m = matches.get(0).getAsJsonObject();
    assertEquals("mode_sequence", m.get("basis").getAsString());
    assertTrue(m.get("complete").getAsBoolean());
    assertEquals(AUTO_START, num(m.getAsJsonObject("autonomous"), "start"), TIME);
    assertEquals(AUTO_END, num(m.getAsJsonObject("autonomous"), "end"), TIME);
    assertEquals(TELEOP_START, num(m.getAsJsonObject("teleop"), "start"), TIME);
    assertEquals(TELEOP_END, num(m.getAsJsonObject("teleop"), "end"), TIME);
    var endgame = m.getAsJsonObject("endgame");
    assertEquals(TELEOP_END - 30, num(endgame, "start"), TIME);
    assertEquals(TELEOP_END, num(endgame, "end"), TIME);
    var timing = m.getAsJsonObject("expected_timing");
    assertEquals(20.0, num(timing, "auto_sec"));
    assertEquals(140.0, num(timing, "teleop_sec"));
  }

  @Test
  @DisplayName("one roboRIO brownout, 259.4889 to 259.5268 s, the same in three tools")
  void brownout() throws Exception {
    // /SystemStats/BrownedOut: false, true at 259.488912, false at 259.52681
    var timeline = call("get_ds_timeline");
    var events = new ArrayList<JsonObject>();
    for (var e : timeline.getAsJsonArray("events")) {
      var o = e.getAsJsonObject();
      if (o.get("type").getAsString().startsWith("RIO_BROWNOUT")) events.add(o);
    }
    assertEquals(2, events.size(), events.toString());
    assertEquals("RIO_BROWNOUT_START", events.get(0).get("type").getAsString());
    assertEquals(259.488912, num(events.get(0), "timestamp"), TIME);
    assertEquals("RIO_BROWNOUT_END", events.get(1).get("type").getAsString());
    assertEquals(259.52681, num(events.get(1), "timestamp"), TIME);

    var rio = call("power_analysis").getAsJsonObject("rio_brownouts");
    assertEquals("/SystemStats/BrownedOut", rio.get("flag_entry").getAsString());
    assertEquals(1, rio.get("count").getAsInt());
    var event = rio.getAsJsonArray("events").get(0).getAsJsonObject();
    assertEquals(259.488912, num(event, "start"), TIME);
    assertEquals(259.52681, num(event, "end"), TIME);
    assertEquals(0.037898, num(event, "duration_sec"), TIME);

    var battery = call("predict_battery_health");
    assertEquals(1, battery.get("brownout_events").getAsInt());
    assertTrue(battery.get("brownout_basis").getAsString().startsWith("rio_flag"),
        battery.toString());
  }

  @Test
  @DisplayName("the threshold is the logged 6.0 V in effect during the match, not the boot 6.75 V")
  void threshold() throws Exception {
    // /SystemStats/BrownoutVoltage: 6.75 at 229.926208, then 6.0 from the enable at 234.926208
    for (var tool : List.of("power_analysis", "get_ds_timeline", "predict_battery_health")) {
      var r = call(tool);
      var o = tool.equals("power_analysis") ? r.getAsJsonObject("voltage_analysis") : r;
      assertEquals(6.0, num(o, "brownout_threshold"), 1e-12, tool);
      assertEquals("logged", o.get("brownout_threshold_basis").getAsString(), tool);
    }
  }

  @Test
  @DisplayName("battery voltage while enabled: 7825 samples, 7.208 V at 359.32 s, mean 10.43 V")
  void voltage() throws Exception {
    var r = call("power_analysis");
    assertEquals("enabled", r.getAsJsonObject("scope").get("scope").getAsString());
    var v = r.getAsJsonObject("voltage_analysis");
    assertEquals("/SystemStats/BatteryVoltage", v.get("entry").getAsString());
    assertEquals(7825, v.get("samples").getAsInt());
    same(7.2084062499999995, v, "min_voltage");
    assertEquals(359.320133, num(v, "min_voltage_time_sec"), TIME);
    same(13.761023193359375, v, "max_voltage");
    same(10.428780660630492, v, "avg_voltage");
    assertEquals(0, v.get("samples_below_threshold").getAsInt());
    assertEquals(0, v.get("threshold_crossings").getAsInt());
    // The logged voltage never reached 6.0 V, yet the roboRIO browned out: the flag decides
    assertEquals("HIGH", v.get("brownout_risk").getAsString());
    assertTrue(v.get("brownout_risk_basis").getAsString().contains("/SystemStats/BrownedOut"));

    var stats = call("predict_battery_health").getAsJsonObject("voltage_stats");
    assertEquals(7825, stats.get("samples").getAsInt());
    same(7.2084062499999995, stats, "min_volts");
    same(10.428780660630492, stats, "avg_volts");
    same(12.6 - 7.2084062499999995, stats, "voltage_sag"); // below the 12.6 V nominal
  }

  @Test
  @DisplayName("power_analysis: 14 of the 17 amperage entries have samples while enabled; drive peaks")
  void currents() throws Exception {
    var r = call("power_analysis");
    // The intake pivot and roller and the shooter's top roller hold one sample, before the enable
    assertEquals(14, r.get("current_entries_analyzed").getAsInt());
    var channels = r.getAsJsonArray("channel_analysis");
    Object[][] expected = {
        {"/Drive/Module2/DriveCurrentAmps", 154.1, 292.028747, -129.36, 44.58857287321451, 7911},
        {"/Drive/Module1/DriveCurrentAmps", 153.88, 374.294083, -108.26, 42.51910206146452, 7907},
        {"/Drive/Module0/DriveCurrentAmps", 149.54, 333.193516, -107.16, 41.61736449778902, 7915},
        {"/Drive/Module3/DriveCurrentAmps", 148.82, 322.91318, -127.18, 39.853265771472, 7894}};
    for (int i = 0; i < expected.length; i++) {
      var c = channels.get(i).getAsJsonObject();
      var e = expected[i];
      assertEquals(e[0], c.get("entry").getAsString(), "rank " + i);
      same((double) e[1], c, "peak_current_A");
      assertEquals((double) e[2], num(c, "peak_current_time_sec"), TIME, "rank " + i);
      same((double) e[1], c, "max_current_A");
      same((double) e[3], c, "min_current_A");
      same((double) e[4], c, "avg_current_A");
      assertEquals((int) e[5], c.get("sample_count").getAsInt(), "rank " + i);
    }
  }

  @Test
  @DisplayName("get_statistics in auto and teleop matches numpy")
  void scopedStatistics() throws Exception {
    // count, mean, min, max, median, std_dev, q1, q3, p5, p95
    Object[][] cases = {
        {"/SystemStats/BatteryVoltage", "auto", new double[] {951, 11.074893718988235,
            8.772741455078124, 13.270990478515625, 11.335989501953124, 1.05348895905098,
            10.13603759765625, 11.838587158203124, 9.388423583984375, 12.8123701171875}},
        {"/SystemStats/BatteryVoltage", "teleop", new double[] {6874, 10.339392601494875,
            7.2084062499999995, 13.761023193359375, 9.92243359375, 1.1732621593470929,
            9.507790527343749, 11.342271972656249, 8.835566162109375, 12.416574462890624}},
        {"/Drive/Module0/DriveCurrentAmps", "teleop", new double[] {6963, 44.747827086026135,
            -107.16, 149.54, 46.2, 39.507607201001285, 12.9, 71.02, -15.738,
            114.01799999999999}}};
    var keys = List.of("count", "mean", "min", "max", "median", "std_dev", "q1", "q3", "p5", "p95");
    for (var c : cases) {
      var r = call("get_statistics", "name", c[0], "scope", c[1]);
      var expected = (double[]) c[2];
      for (int i = 0; i < keys.size(); i++) same(expected[i], r, keys.get(i));
    }
  }

  @Test
  @DisplayName("find_condition: voltage below 8 V 43 times, 0.98 s in all, first at the brownout")
  void sagsBelowEightVolts() throws Exception {
    var r = call("find_condition", "name", "/SystemStats/BatteryVoltage", "operator", "lt",
        "threshold", 8.0);
    assertEquals(43, r.get("interval_count").getAsInt());
    assertEquals(43, r.get("transition_count").getAsInt());
    assertEquals(0.98, num(r, "total_true_sec"), TIME);
    assertEquals(LOG_END - LOG_START, num(r, "window_sec"), TIME);
    // The first sag starts at the sample where the roboRIO raised its brownout flag
    var first = r.getAsJsonArray("intervals").get(0).getAsJsonObject();
    assertEquals(259.488912, num(first, "start"), TIME);
    assertEquals(259.505904, num(first, "end"), TIME);
    assertEquals("condition_false", first.get("end_reason").getAsString());
  }
}
