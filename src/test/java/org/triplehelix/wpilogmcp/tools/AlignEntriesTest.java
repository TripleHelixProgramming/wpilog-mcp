/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/** align_entries and lag search (review section 5.6). */
@DisplayName("align_entries and lag search")
class AlignEntriesTest extends FixtureToolTestBase {

  static JsonArray names(String... names) {
    var array = new JsonArray();
    for (var n : names) array.add(n);
    return array;
  }

  @Nested
  @DisplayName("on fixtures")
  class OnFixtures {

    @Test
    @DisplayName("two struct fields side by side, and the statistics of their difference")
    void difference() {
      var r = call("align_entries", "struct_custom", "names",
          names("/RealOutputs/Arm/State.currents[0]", "/RealOutputs/Arm/State.currents[1]"),
          "difference", true, "limit", 3);
      assertEquals("ok", r.get("status").getAsString(), r.toString());
      assertEquals(1451, r.get("total_rows").getAsInt());
      assertEquals(3, r.getAsJsonArray("rows").size());
      var row = r.getAsJsonArray("rows").get(0).getAsJsonArray();
      assertEquals(1.0, row.get(0).getAsDouble(), 1e-9);
      assertEquals(11.0, row.get(1).getAsDouble(), 1e-9);
      assertEquals(12.0, row.get(2).getAsDouble(), 1e-9);
      var stats = r.getAsJsonObject("difference_statistics");
      assertEquals(1451, stats.get("count").getAsInt());
      assertEquals(-1.0, stats.get("mean").getAsDouble(), 1e-9);
      assertEquals(0.0, stats.get("std_dev").getAsDouble(), 1e-9);
      assertEquals(1.0, stats.get("rmse").getAsDouble(), 1e-9);
      assertEquals(1451, r.getAsJsonObject("limits").getAsJsonObject("rows").get("total")
          .getAsInt());
    }

    @Test
    @DisplayName("the pose heading and the gyro agree exactly (angles, shortest difference)")
    void headingVersusGyro() {
      var r = call("align_entries", "akit_match", "names",
          names("/RealOutputs/Drive/Pose.rotation.value", "/Drive/Gyro/YawPosition.value"),
          "difference", true, "scope", "enabled");
      var stats = r.getAsJsonObject("difference_statistics");
      assertEquals("radians", stats.get("angle_unit").getAsString());
      assertEquals(0.0, stats.get("max").getAsDouble(), 1e-12);
      assertEquals(0.0, stats.get("min").getAsDouble(), 1e-12);
      assertTrue(stats.get("count").getAsInt() > 7000, stats.toString());
    }

    @Test
    @DisplayName("sample the robot pose at each vision observation's own timestamp")
    void atEmbeddedTimestamps() {
      var r = call("align_entries", "vision_photon_akit", "names",
          names("/RealOutputs/Drive/Pose.translation.x"),
          "at", "/Vision/Camera0/PoseObservations", "time_field", "[*].timestamp",
          "interpolation", "linear", "limit", 5);
      assertEquals("values of /Vision/Camera0/PoseObservations[*].timestamp",
          r.get("time_source").getAsString());
      for (var element : r.getAsJsonArray("rows")) {
        var row = element.getAsJsonArray();
        double t = row.get(0).getAsDouble();
        if (row.get(1).isJsonNull()) continue; // before the first pose
        // the fixture's pose: x = 3.0 + 0.5 sin(0.1 t), interpolated linearly between loops
        assertEquals(3.0 + 0.5 * Math.sin(0.1 * t), row.get(1).getAsDouble(), 1e-4);
      }
      // the observation times are 60 ms before the records; the first two (0.94, 0.98 s)
      // precede the log's first record at 1.0 s, outside the default scope
      var first = r.getAsJsonArray("rows").get(0).getAsJsonArray();
      assertEquals(1.02, first.get(0).getAsDouble(), 1e-6);
      assertFalse(first.get(1).isJsonNull());
    }

    @Test
    @DisplayName("errors: names, interpolation, difference, and [*]")
    void errors() {
      var none = call("align_entries", "struct_custom", "names", new JsonArray());
      assertTrue(none.get("error").getAsString().contains("1-8 signals"), none.toString());
      var bad = call("align_entries", "struct_custom", "names",
          names("/RealOutputs/Arm/State.currents[0]"), "interpolation", "cubic");
      assertTrue(bad.get("error").getAsString().contains("interpolation"), bad.toString());
      var diff = call("align_entries", "struct_custom", "names",
          names("/RealOutputs/Arm/State.currents[0]"), "difference", true);
      assertTrue(diff.get("error").getAsString().contains("exactly two"), diff.toString());
      var wildcard = call("align_entries", "struct_custom", "names",
          names("/RealOutputs/Arm/States[*].currents[0]"));
      assertTrue(wildcard.get("error").getAsString().contains("align_entries needs one value"),
          wildcard.toString());
      var struct = call("align_entries", "struct_custom", "names",
          names("/RealOutputs/Arm/State"));
      assertTrue(struct.get("error").getAsString().contains("numeric fields are"),
          struct.toString());
    }
  }

  @Nested
  @DisplayName("interpolation and lags")
  class Synthetic extends ToolTestBase {

    @Override
    protected void registerTools(ToolRegistry registry) {
      StatisticsTools.registerAll(registry);
    }

    JsonObject run(String tool, JsonObject args) throws Exception {
      args.addProperty("path", "/test/align.wpilog");
      return findTool(tool).execute(args).getAsJsonObject();
    }

    @Test
    @DisplayName("previous, linear, and nearest at times between samples")
    void modes() throws Exception {
      putLogInCache(new MockLogBuilder().setPath("/test/align.wpilog")
          .addNumericEntry("/A", new double[] {0, 1, 2}, new double[] {0, 10, 20})
          .addNumericEntry("/Times", new double[] {-0.5, 0.3, 0.7, 2.5},
              new double[] {0, 0, 0, 0})
          .build());
      for (var mode : List.of("previous", "linear", "nearest")) {
        var args = new JsonObject();
        args.add("names", names("/A"));
        args.addProperty("at", "/Times");
        args.addProperty("interpolation", mode);
        var rows = run("align_entries", args).getAsJsonArray("rows");
        var values = new ArrayList<Object>();
        rows.forEach(r -> {
          var v = r.getAsJsonArray().get(1);
          values.add(v.isJsonNull() ? null : v.getAsDouble());
        });
        switch (mode) {
          case "previous" -> assertEquals(java.util.Arrays.asList(null, 0.0, 0.0, 20.0), values);
          case "linear" -> assertEquals(java.util.Arrays.asList(null, 3.0, 7.0, null), values);
          default -> assertEquals(List.of(0.0, 0.0, 10.0, 20.0), values);
        }
      }
    }

    @Test
    @DisplayName("an angle interpolates along the shortest arc across +-pi")
    void angleInterpolation() throws Exception {
      var rotations = new ArrayList<Map<String, Object>>();
      for (double v : new double[] {3.1, -3.1}) {
        var m = new LinkedHashMap<String, Object>();
        m.put("value", v);
        rotations.add(m);
      }
      putLogInCache(new MockLogBuilder().setPath("/test/align.wpilog")
          .addStructEntry("/Gyro", "struct:Rotation2d", new double[] {0, 1}, rotations)
          .addNumericEntry("/Times", new double[] {0.5}, new double[] {0})
          .build());
      var args = new JsonObject();
      args.add("names", names("/Gyro.value"));
      args.addProperty("at", "/Times");
      args.addProperty("interpolation", "linear");
      var row = run("align_entries", args).getAsJsonArray("rows").get(0).getAsJsonArray();
      assertEquals(Math.PI, row.get(1).getAsDouble(), 1e-3);
    }

    /** y(t) = x(t - 0.1): the second signal follows the first by 100 ms. */
    void putLagged() {
      putLagged(1.0);
    }

    /** y(t) = sign * x(t - 0.1): with sign -1 the second signal mirrors the first. */
    void putLagged(double sign) {
      int n = 1000;
      var ts = new double[n];
      var x = new double[n];
      var y = new double[n];
      for (int i = 0; i < n; i++) {
        ts[i] = i * 0.01;
        x[i] = Math.sin(2 * Math.PI * 0.7 * ts[i]) + 0.3 * Math.sin(2 * Math.PI * 1.9 * ts[i]);
        double shifted = ts[i] - 0.1;
        y[i] = sign * (Math.sin(2 * Math.PI * 0.7 * shifted)
            + 0.3 * Math.sin(2 * Math.PI * 1.9 * shifted));
      }
      putLogInCache(new MockLogBuilder().setPath("/test/align.wpilog")
          .addNumericEntry("/X", ts, x).addNumericEntry("/Y", ts, y).build());
    }

    @Test
    @DisplayName("time_correlate finds the 100 ms lag")
    void correlationLag() throws Exception {
      putLagged();
      var args = new JsonObject();
      args.addProperty("name1", "/X");
      args.addProperty("name2", "/Y");
      args.addProperty("max_lag_sec", 0.5);
      var lag = run("time_correlate", args).getAsJsonObject("lag_search");
      assertEquals(0.1, lag.get("best_lag_sec").getAsDouble(), 1e-9, lag.toString());
      assertEquals(1.0, lag.get("correlation_at_best_lag").getAsDouble(), 1e-9);
      assertTrue(lag.get("correlation_at_zero_lag").getAsDouble() < 0.95);
      assertEquals(101, lag.get("lags_evaluated").getAsInt());
    }

    @Test
    @DisplayName("time_correlate finds the lag of a signal that moves oppositely")
    void inverseCorrelationLag() throws Exception {
      // Every correlation here is negative, as battery voltage against a motor's current is:
      // the highest one is the weakest match, and the strongest is at the true 100 ms
      putLagged(-1.0);
      var args = new JsonObject();
      args.addProperty("name1", "/X");
      args.addProperty("name2", "/Y");
      args.addProperty("max_lag_sec", 0.3);
      var result = run("time_correlate", args);
      var lag = result.getAsJsonObject("lag_search");
      assertEquals(0.1, lag.get("best_lag_sec").getAsDouble(), 1e-9, lag.toString());
      assertEquals(-1.0, lag.get("correlation_at_best_lag").getAsDouble(), 1e-9,
          "the sign is kept: " + lag);
      double zero = lag.get("correlation_at_zero_lag").getAsDouble();
      assertTrue(zero < 0 && zero > -0.95, "weaker at zero lag: " + lag);
      assertEquals(zero, result.get("correlation").getAsDouble(), 1e-9);
      assertTrue(lag.get("note").getAsString().contains("strongest correlation, positive or "
          + "negative"), lag.toString());
    }

    @Test
    @DisplayName("among equally strong lags, time_correlate reports the one nearest zero")
    void equallyStrongLags() throws Exception {
      // A signal against itself, repeating every 4 samples on a grid of exact binary
      // fractions: r is exactly +1 a whole period either side of zero and exactly -1 half a
      // period away. No shift is needed, so the best lag is zero.
      int n = 64;
      var ts = new double[n];
      var x = new double[n];
      double[] pattern = {0, 1, 0, -1};
      for (int i = 0; i < n; i++) {
        ts[i] = i / 64.0;
        x[i] = pattern[i % 4];
      }
      putLogInCache(new MockLogBuilder().setPath("/test/align.wpilog")
          .addNumericEntry("/X", ts, x).addNumericEntry("/Y", ts, x).build());
      var args = new JsonObject();
      args.addProperty("name1", "/X");
      args.addProperty("name2", "/Y");
      args.addProperty("max_lag_sec", 4 / 64.0);
      var lag = run("time_correlate", args).getAsJsonObject("lag_search");
      assertEquals(9, lag.get("lags_evaluated").getAsInt(), lag.toString());
      assertEquals(0.0, lag.get("best_lag_sec").getAsDouble(), 0.0, lag.toString());
      assertEquals(1.0, lag.get("correlation_at_best_lag").getAsDouble(), 0.0);

      // The mirror image: exactly -1 at zero and +1 half a period away; still no shift
      var mirrored = new double[n];
      for (int i = 0; i < n; i++) mirrored[i] = -x[i];
      putLogInCache(new MockLogBuilder().setPath("/test/align.wpilog")
          .addNumericEntry("/X", ts, x).addNumericEntry("/Y", ts, mirrored).build());
      var inverse = run("time_correlate", args).getAsJsonObject("lag_search");
      assertEquals(0.0, inverse.get("best_lag_sec").getAsDouble(), 0.0, inverse.toString());
      assertEquals(-1.0, inverse.get("correlation_at_best_lag").getAsDouble(), 0.0);
    }

    @Test
    @DisplayName("compare_entries finds the lag that minimizes RMSE")
    void rmseLag() throws Exception {
      putLagged();
      var args = new JsonObject();
      args.addProperty("name1", "/X");
      args.addProperty("name2", "/Y");
      args.addProperty("max_lag_sec", 0.3);
      args.addProperty("lag_step_sec", 0.05);
      var lag = run("compare_entries", args).getAsJsonObject("lag_search");
      assertEquals(0.1, lag.get("best_lag_sec").getAsDouble(), 1e-9, lag.toString());
      assertEquals(0.0, lag.get("rmse_at_best_lag").getAsDouble(), 1e-9);
      assertEquals(13, lag.get("lags_evaluated").getAsInt());
    }

    @Test
    @DisplayName("a lag range is required to be positive; the grid is capped at 401 lags")
    void lagArguments() throws Exception {
      putLagged();
      var bad = new JsonObject();
      bad.addProperty("name1", "/X");
      bad.addProperty("name2", "/Y");
      bad.addProperty("max_lag_sec", -1);
      assertTrue(run("time_correlate", bad).get("error").getAsString()
          .contains("max_lag_sec must be a positive"));
      var wide = new JsonObject();
      wide.addProperty("name1", "/X");
      wide.addProperty("name2", "/Y");
      wide.addProperty("max_lag_sec", 5);
      var lag = run("time_correlate", wide).getAsJsonObject("lag_search");
      assertEquals(401, lag.get("lags_evaluated").getAsInt());
    }
  }
}
