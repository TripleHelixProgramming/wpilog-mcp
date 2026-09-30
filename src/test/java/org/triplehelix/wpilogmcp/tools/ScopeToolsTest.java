/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/** scope and windows on the numeric tools (plan Phase 3 item 3). */
@DisplayName("Scopes and windows in numeric tools")
class ScopeToolsTest extends FixtureToolTestBase {

  static JsonArray windows(double... bounds) {
    var array = new JsonArray();
    for (int i = 0; i < bounds.length; i += 2) {
      var w = new com.google.gson.JsonObject();
      w.addProperty("start", bounds[i]);
      w.addProperty("end", bounds[i + 1]);
      array.add(w);
    }
    return array;
  }

  @Nested
  @DisplayName("get_statistics")
  class Statistics {

    @Test
    @DisplayName("scope disabled reads only disabled time; enabled includes the teleop dip")
    void enabledAndDisabled() {
      var disabled = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "scope", "disabled");
      assertEquals(12.6, disabled.get("min").getAsDouble(), 1e-9);
      assertEquals(12.6, disabled.get("max").getAsDouble(), 1e-9);
      var enabled = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "scope", "enabled");
      assertEquals(7.1, enabled.get("min").getAsDouble(), 1e-9);
      assertTrue(enabled.get("max").getAsDouble() <= 12.2 + 1e-9);
      var scope = enabled.getAsJsonObject("inputs").getAsJsonObject("scope");
      assertEquals("enabled", scope.get("scope").getAsString());
      assertEquals(2, scope.get("window_count").getAsInt());
      assertEquals(160.0, scope.get("total_sec").getAsDouble(), 1e-6);
      // 20 s of auto and 140 s of teleop at 50 Hz
      assertEquals(8000, enabled.get("count").getAsInt(), 2);
    }

    @Test
    @DisplayName("the time between windows is not a data gap")
    void noGapBetweenWindows() {
      var enabled = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "scope", "enabled");
      assertEquals(0, enabled.getAsJsonObject("data_quality").get("gap_count").getAsInt(),
          enabled.toString());
      // two explicit windows 35 s apart: still no gap
      var two = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "windows", windows(20, 25, 60, 65));
      assertEquals(0, two.getAsJsonObject("data_quality").get("gap_count").getAsInt());
      assertEquals(10.0, two.getAsJsonObject("data_quality").get("time_span_seconds")
          .getAsDouble(), 0.05);
      assertEquals("windows", two.getAsJsonObject("inputs").getAsJsonObject("scope")
          .get("scope").getAsString());
    }

    @Test
    @DisplayName("scope, windows, and start/end intersect")
    void intersect() {
      var r = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "scope", "auto", "windows", windows(10, 30, 35, 100), "start_time", 25);
      var scope = r.getAsJsonObject("inputs").getAsJsonObject("scope");
      assertEquals("auto+windows", scope.get("scope").getAsString());
      // auto is 20-40; windows 10-30 and 35-100; start 25: 25-30 and 35-40
      assertEquals(10.0, scope.get("total_sec").getAsDouble(), 1e-6);
      assertEquals(2, scope.get("window_count").getAsInt());
    }

    @Test
    @DisplayName("plain start/end still report inputs.window; no bounds report nothing")
    void legacyWindow() {
      var r = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "start_time", 50, "end_time", 60);
      var inputs = r.getAsJsonObject("inputs");
      assertEquals(50.0, inputs.getAsJsonObject("window").get("start").getAsDouble());
      assertFalse(inputs.has("scope"));
      var all = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage");
      assertFalse(all.getAsJsonObject("inputs").has("window"));
      assertFalse(all.getAsJsonObject("inputs").has("scope"));
    }

    @Test
    @DisplayName("an angle is unwrapped within each window, starting from its logged value")
    void anglePerWindow() {
      var r = call("get_statistics", "struct_custom", "name", "/RealOutputs/Arm/State.angle.value",
          "windows", windows(2, 4, 20, 22));
      assertEquals(0, r.getAsJsonObject("angle").get("wraps").getAsInt());
      assertEquals(2 * 100, r.get("count").getAsInt()); // half-open: 2.00-3.98, 20.00-21.98
    }
  }

  @Nested
  @DisplayName("differences stay within a window")
  class WithinWindows {

    @Test
    @DisplayName("rate_of_change: a step between two windows is not a rate")
    void rateAcrossStep() {
      // mode steps from 0 to 1 at t = 6.0 (loop 250)
      var across = call("rate_of_change", "struct_custom", "name", "/RealOutputs/Arm/State.mode",
          "windows", windows(1, 10), "limit", 1000);
      assertTrue(maxAbsRate(across) > 1, across.toString());
      var split = call("rate_of_change", "struct_custom", "name", "/RealOutputs/Arm/State.mode",
          "windows", windows(1, 5.9, 6.1, 10), "limit", 1000);
      assertEquals(0.0, maxAbsRate(split), 1e-12, split.toString());
    }

    double maxAbsRate(com.google.gson.JsonObject r) {
      double max = 0;
      for (var s : r.getAsJsonArray("samples")) {
        max = Math.max(max, Math.abs(s.getAsJsonObject().get("rate").getAsDouble()));
      }
      return max;
    }

    @Test
    @DisplayName("detect_anomalies: a jump across the gap between windows is not a spike")
    void spikesWithinWindows() {
      var across = call("detect_anomalies", "struct_custom", "name", "/RealOutputs/Arm/State.mode",
          "spike_threshold", 0.5, "windows", windows(1, 10));
      assertEquals(1, across.get("spike_count").getAsInt(), across.toString());
      var split = call("detect_anomalies", "struct_custom", "name", "/RealOutputs/Arm/State.mode",
          "spike_threshold", 0.5, "windows", windows(1, 5.9, 6.1, 10));
      assertEquals(0, split.get("spike_count").getAsInt(), split.toString());
    }

    @Test
    @DisplayName("find_peaks: a peak's neighbors are in its own window")
    void peaksWithinWindows() {
      // angle.value = 0.5 sin(0.2 t) peaks once, near t = 7.85
      var whole = call("find_peaks", "struct_custom", "name", "/RealOutputs/Arm/State.angle.value",
          "windows", windows(1, 12));
      assertEquals(1, whole.get("maxima_count").getAsInt(), whole.toString());
      var cut = call("find_peaks", "struct_custom", "name", "/RealOutputs/Arm/State.angle.value",
          "windows", windows(1, 7, 8.5, 12));
      assertEquals(0, cut.get("maxima_count").getAsInt(), cut.toString());
    }
  }

  @Nested
  @DisplayName("two-signal tools and find_condition")
  class Others {

    @Test
    @DisplayName("compare_entries and time_correlate count only reference samples in scope")
    void twoSignals() {
      var cmp = call("compare_entries", "struct_custom", "name1", "/RealOutputs/Arm/State.currents[1]",
          "name2", "/RealOutputs/Arm/State.currents[0]", "windows", windows(2, 4, 20, 22));
      assertEquals(200, cmp.get("samples_compared").getAsInt());
      var corr = call("time_correlate", "struct_custom",
          "name1", "/RealOutputs/Arm/State.currents[1]",
          "name2", "/RealOutputs/Arm/State.temperature", "scope", "enabled");
      // enabled 2-28 s: 1300 loops
      assertEquals(1300, corr.get("sample_count").getAsInt(), 1);
      assertEquals("enabled", corr.getAsJsonObject("inputs").getAsJsonObject("scope")
          .get("scope").getAsString());
    }

    @Test
    @DisplayName("find_condition searches each window; its intervals feed other tools")
    void conditionWindows() {
      var dip = call("find_condition", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "operator", "lt", "threshold", 7.5, "scope", "enabled");
      assertEquals(1, dip.get("interval_count").getAsInt(), dip.toString());
      assertEquals(160.0, dip.get("window_sec").getAsDouble(), 1e-6);
      var interval = dip.getAsJsonArray("intervals").get(0).getAsJsonObject();
      assertEquals(150.0, interval.get("start").getAsDouble(), 1e-9);
      assertEquals(0.06, interval.get("duration").getAsDouble(), 1e-9);

      // pass the intervals straight back as windows
      var during = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "windows", dip.getAsJsonArray("intervals"));
      assertEquals(7.1, during.get("max").getAsDouble(), 1e-9, during.toString());

      // disabled time holds 12.6 V: never below 7.5
      var none = call("find_condition", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "operator", "lt", "threshold", 7.5, "scope", "disabled");
      assertEquals(0, none.get("interval_count").getAsInt());
    }

    @Test
    @DisplayName("an interval open at a window's end closes there; the next window starts afresh")
    void conditionOpenAtWindowEnd() {
      // currents[0] = 10 + t > 12 from t = 2: windows 1-5 and 7-9 give two intervals
      var r = call("find_condition", "struct_custom", "name", "/RealOutputs/Arm/State.currents[0]",
          "operator", "gt", "threshold", 12, "windows", windows(1, 5, 7, 9));
      assertEquals(2, r.get("interval_count").getAsInt(), r.toString());
      var second = r.getAsJsonArray("intervals").get(1).getAsJsonObject();
      assertEquals(7.0, second.get("start").getAsDouble(), 1e-9);
      assertEquals("window_end", second.get("end_reason").getAsString());
      assertTrue(r.getAsJsonArray("transitions").get(1).getAsJsonObject()
          .get("at_window_start").getAsBoolean());
    }
  }

  @Nested
  @DisplayName("errors")
  class Errors {

    @Test
    @DisplayName("a state scope on a log without DriverStation data says why")
    void noDriverStation() {
      var error = call("get_statistics", "no_ds", "name", "/SystemStats/BatteryVoltage",
          "scope", "enabled").get("error").getAsString();
      assertTrue(error.contains("no DriverStation state entries"), error);
    }

    @Test
    @DisplayName("an unknown scope and a malformed window are errors")
    void badArguments() {
      var unknown = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "scope", "halftime").get("error").getAsString();
      assertTrue(unknown.contains("Unknown scope 'halftime'"), unknown);
      var backwards = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "windows", windows(30, 20)).get("error").getAsString();
      assertTrue(backwards.contains("finite start <= end"), backwards);
      var shape = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "windows", JsonParser.parseString("[\"soon\"]")).get("error").getAsString();
      assertTrue(shape.contains("Each window must be {start, end}"), shape);
    }

    @Test
    @DisplayName("no samples in scope: the error names the scope")
    void emptyScope() {
      var error = call("get_statistics", "akit_match", "name", "/SystemStats/BatteryVoltage",
          "windows", windows(500, 600)).get("error").getAsString();
      assertTrue(error.startsWith("No numeric data in range"), error);
      assertTrue(error.contains("scope windows"), error);
    }
  }

  @Nested
  @DisplayName("TimeScope and DataQuality units")
  class Units {

    @Test
    @DisplayName("windows are sorted and merged; intersections keep inclusive ends")
    void parseAndIntersect() {
      var parsed = TimeScope.parseWindows(JsonParser.parseString(
          "[{\"start\": 5, \"end\": 8}, [1, 3], {\"start\": 2, \"end\": 4}]"));
      assertEquals(List.of(new TimeScope.Window(1, 4, false), new TimeScope.Window(5, 8, false)),
          parsed);
      // a zero-length window is one instant, inclusive
      assertEquals(List.of(new TimeScope.Window(7, 7, true)),
          TimeScope.parseWindows(JsonParser.parseString("[[7, 7]]")));
      var halfOpen = List.of(new TimeScope.Window(0, 10, false));
      var cut = TimeScope.intersect(halfOpen, List.of(new TimeScope.Window(3, 10, true)));
      assertEquals(List.of(new TimeScope.Window(3, 10, false)), cut);
      assertEquals(List.of(), TimeScope.intersect(halfOpen,
          List.of(new TimeScope.Window(10, 12, true))));
    }

    @Test
    @DisplayName("DataQuality over segments ignores the time between them")
    void qualitySegments() {
      var a = new ArrayList<TimestampedValue>();
      var b = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 300; i++) {
        a.add(new TimestampedValue(i * 0.02, 1.0));
        b.add(new TimestampedValue(100 + i * 0.02, 1.0));
      }
      var joined = new ArrayList<>(a);
      joined.addAll(b);
      assertEquals(1, DataQuality.fromValues(joined).gapCount());
      var segmented = DataQuality.fromSegments(List.of(a, b));
      assertEquals(0, segmented.gapCount());
      assertEquals(600, segmented.sampleCount());
      assertEquals(2 * 299 * 0.02, segmented.timeSpanSeconds(), 1e-9);
      assertEquals("high", segmented.confidenceLevel());
      assertEquals(0, DataQuality.fromSegments(List.of(List.of(), List.of())).sampleCount());
    }
  }
}
