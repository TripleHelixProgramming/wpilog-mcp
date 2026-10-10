/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/** Small independent counterexamples from the release review, without robot telemetry. */
class ReviewNumericsTest extends ToolTestBase {
  @Override protected void registerTools(ToolRegistry registry) { StatisticsTools.registerAll(registry); }

  @Test void outOfOrderRecordsHaveAChronologicalNumericView() throws Exception {
    var log = new MockLogBuilder().setPath("/test/out-of-order.wpilog")
        .addNumericEntry("/x", new double[]{0, 2, 1, 3}, new double[]{0, 20, 10, 30}).build();
    putLogInCache(log);
    var args = new JsonObject(); args.addProperty("path", log.path()); args.addProperty("name", "/x");
    args.addProperty("start_time", 1); args.addProperty("end_time", 1.5);
    var result = findTool("get_statistics").execute(args).getAsJsonObject();
    assertEquals("ok", result.get("status").getAsString(), result.toString());
    assertEquals(1, result.get("count").getAsInt());
    assertEquals(10, result.get("mean").getAsDouble());
  }

  @Test void clippingAtDisableDoesNotMakeThePhaseEndInclusive() {
    var log = new MockLogBuilder()
        .addBooleanEntry("DS:enabled", new double[]{0, 2, 3}, new boolean[]{true, false, false})
        .addNumericEntry("/x", new double[]{0, 1, 2}, new double[]{10, 20, 100}).build();
    var scope = TimeScope.resolve(log, null, "enabled", null, 2.0);
    assertFalse(scope.contains(2), "Disable transition belongs to the next state");
    assertEquals(2, scope.filter(log.values().get("/x")).size());
  }

  @Test void lowVoltageIntervalsStopAtEachWindowEnd() {
    var log = new MockLogBuilder()
        .addBooleanEntry("DS:enabled", new double[]{0, 2, 10, 12}, new boolean[]{true, false, true, false})
        .addNumericEntry("/x", new double[]{0, 1, 2, 10, 11}, new double[]{12, 6, 12, 6, 12}).build();
    var facts = PowerFacts.voltage(log.values().get("/x"),
        TimeScope.resolve(log, null, "enabled", null, null), 6.8).orElseThrow();
    assertEquals(2, facts.crossings().size());
    assertEquals(2, facts.secondsBelow(), "(2-1) + (11-10), never the disabled gap");
  }

  @Test void singletonWindowsCannotInflateAutocorrelationBeyondOne() {
    var windows = List.of(List.of(10.0, 10.0), List.of(-10.0, -10.0),
        List.of(0.0), List.of(0.0), List.of(0.0), List.of(0.0), List.of(0.0), List.of(0.0));
    double r = StatisticsTools.lag1AutocorrelationWithin(windows);
    assertEquals(0.5, r, 1e-12, "200 adjacent products / 400 squared deviations");
    assertEquals(6, StatisticsTools.effectiveSampleSize(10, r, r), 1e-12);
  }

  @Test void tinyLagStepStillHonorsTheSearchBudget() {
    var args = new JsonObject(); args.addProperty("max_lag_sec", 600); args.addProperty("lag_step_sec", 1e-9);
    var lags = StatisticsTools.lagGrid(args, List.of(new TimestampedValue(0, 1)));
    assertEquals(401, lags.length); assertEquals(-600, lags[0]); assertEquals(600, lags[400]);
  }

  @Test void quadraticDerivativeUsesTheActualUnequalIntervals() throws Exception {
    var log = new MockLogBuilder().setPath("/test/quadratic.wpilog")
        .addNumericEntry("/x", new double[]{0, 1, 3}, new double[]{0, 1, 9}).build();
    putLogInCache(log);
    var args = new JsonObject(); args.addProperty("path", log.path()); args.addProperty("name", "/x");
    args.addProperty("window_size", 1);
    var result = findTool("rate_of_change").execute(args).getAsJsonObject();
    assertEquals(2, result.getAsJsonArray("samples").get(1).getAsJsonObject().get("rate").getAsDouble(),
        1e-12, "d(t^2)/dt at t=1 is 2");
  }
}
