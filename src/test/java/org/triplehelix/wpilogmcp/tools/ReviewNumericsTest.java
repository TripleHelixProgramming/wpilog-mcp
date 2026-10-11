/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.log.ParsedLog;
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

  @Test void lowVoltageIntervalsStopAtEachWindowEnd() throws Exception {
    var log = windowedVoltageLog();
    var facts = PowerFacts.voltage(log.values().get("/SystemStats/BatteryVoltage"),
        TimeScope.resolve(log, null, "enabled", null, null), 6.8).orElseThrow();
    assertEquals(2, facts.crossings().size());
    assertEquals(2, facts.secondsBelow(), "(2-1) + (11-10), never the disabled gap");
    assertEquals(2, facts.crossings().get(0).end());
    var json = new JsonObject(); facts.addTo(json);
    assertTrue(json.has("crossing_details"), "Each held interval must state its boundary basis");
    var first = json.getAsJsonArray("crossing_details").get(0).getAsJsonObject();
    assertEquals(2, first.get("end_time").getAsDouble());
    assertCrossingBoundary(first, true, false);
    assertCrossingBoundary(json.getAsJsonArray("crossing_details").get(1).getAsJsonObject(),
        false, false);

    var wholeLog = new MockLogBuilder()
        .addNumericEntry("/voltage", new double[]{0, 1}, new double[]{12, 6})
        .addNumericEntry("/other", new double[]{0, 12}, new double[]{0, 1}).build();
    var held = PowerFacts.voltage(wholeLog.values().get("/voltage"),
        TimeScope.resolve(wholeLog, null, "all", null, null), 6.8).orElseThrow();
    assertEquals(12, held.crossings().get(0).end(), "Whole-log end, not the last voltage at 1 s");
    assertCrossingBoundary(held.crossings().get(0).toJson(), true, false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"power_analysis", "predict_battery_health", "generate_report"})
  void powerToolsNameScopeBoundariesAndRetainTheirAliases(String toolName) throws Exception {
    LogRequiringTool tool = switch (toolName) {
      case "power_analysis" -> new RobotAnalysisTools.PowerAnalysisTool();
      case "predict_battery_health" -> new FrcDomainTools.PredictBatteryHealthTool();
      case "generate_report" -> new ExportTools.GenerateReportTool();
      default -> throw new AssertionError(toolName);
    };
    var args = new JsonObject();
    if (!toolName.equals("generate_report")) args.addProperty("scope", "enabled");
    var result = tool.executeWithLog(windowedVoltageLog(), args).getAsJsonObject();
    var details = switch (toolName) {
      case "power_analysis" -> result.getAsJsonObject("voltage_analysis").getAsJsonArray("crossing_details");
      case "predict_battery_health" -> result.getAsJsonArray("brownout_details");
      case "generate_report" -> result.getAsJsonObject("battery").getAsJsonArray("crossing_details");
      default -> throw new AssertionError(toolName);
    };
    assertEquals(2, details.size(), toolName);
    var open = details.get(0).getAsJsonObject();
    var recovered = details.get(1).getAsJsonObject();
    assertEquals(2, open.get("end_time").getAsDouble(), "The enabled window ends before the log");
    assertEquals(11, recovered.get("end_time").getAsDouble(), "The second crossing recovers inside its window");
    boolean hasOlderAlias = toolName.equals("predict_battery_health");
    assertCrossingBoundary(open, true, hasOlderAlias);
    assertCrossingBoundary(recovered, false, hasOlderAlias);
  }

  @Test void loggedBrownoutBoundaryKeepsItsOldAlias() {
    var result = PowerFacts.brownoutsJson("/SystemStats/BrownedOut", List.of(
        new PowerFacts.Brownout(1, 2, false), new PowerFacts.Brownout(3, 4, true)));
    var closed = result.getAsJsonArray("events").get(0).getAsJsonObject();
    var open = result.getAsJsonArray("events").get(1).getAsJsonObject();
    assertFalse(closed.has("open_at_scope_end"));
    assertFalse(closed.has("open_at_log_end"));
    assertTrue(open.has("open_at_scope_end"), "A logged boundary uses the canonical scope field too");
    assertTrue(open.get("open_at_scope_end").getAsBoolean());
    assertTrue(open.has("open_at_log_end"), "The logged-boundary compatibility alias must remain");
    assertEquals(open.get("open_at_scope_end"), open.get("open_at_log_end"));
  }

  private static ParsedLog windowedVoltageLog() {
    return new MockLogBuilder()
        .addBooleanEntry("DS:enabled", new double[]{0, 2, 10, 12}, new boolean[]{true, false, true, false})
        .addNumericEntry("/SystemStats/BatteryVoltage", new double[]{0, 1, 2, 10, 11}, new double[]{12, 6, 12, 6, 12}).build();
  }

  private static void assertCrossingBoundary(JsonObject crossing, boolean open, boolean hasOlderAlias) {
    assertTrue(crossing.has("open_at_scope_end"), "A crossing must name its scope boundary, not only the log end");
    assertEquals(open, crossing.get("open_at_scope_end").getAsBoolean());
    assertTrue(crossing.has("open_at_log_end"), "The open_at_log_end compatibility alias must remain");
    assertEquals(crossing.get("open_at_scope_end"), crossing.get("open_at_log_end"),
        "The log-end alias must have exactly the canonical field's value");
    if (hasOlderAlias) {
      assertTrue(crossing.has("open_at_end"), "The battery-health open_at_end compatibility alias must remain");
      assertEquals(crossing.get("open_at_scope_end"), crossing.get("open_at_end"),
          "The older battery-health alias must have exactly the canonical field's value");
    }
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
