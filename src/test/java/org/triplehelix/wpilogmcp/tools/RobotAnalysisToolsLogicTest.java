/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * Logic-level unit tests for RobotAnalysisTools using synthetic log data.
 */
class RobotAnalysisToolsLogicTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    RobotAnalysisTools.registerAll(registry);
  }

  @Nested
  @DisplayName("get_match_phases Tool")
  class GetMatchPhasesToolTests {

    @Test
    @DisplayName("detects phases from DriverStation mode transitions")
    void detectsPhasesFromDsData() throws Exception {
      // Simulate a match with auto (0-15s) and teleop (15-150s) from DS entries
      var enabledValues = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, true));
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(150.0, false));

      var autoValues = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      autoValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, true));
      autoValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(15.0, false));

      var log = new MockLogBuilder()
          .setPath("/test/match_phases.wpilog")
          .addEntry("/DriverStation/Enabled", "boolean", enabledValues)
          .addEntry("/DriverStation/Autonomous", "boolean", autoValues)
          .build();
      putLogInCache(log);

      var tool = findTool("get_match_phases");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var phases = resultObj.getAsJsonObject("phases");
      assertTrue(phases.has("autonomous"), "Should detect autonomous phase");
      assertTrue(phases.has("teleop"), "Should detect teleop phase");

      // Auto should be 0-15s
      var auto = phases.getAsJsonObject("autonomous");
      assertEquals(0.0, auto.get("start").getAsDouble(), 0.01);
      assertEquals(15.0, auto.get("end").getAsDouble(), 0.01);

      // Teleop should be 15-150s (not hardcoded 135s)
      var teleop = phases.getAsJsonObject("teleop");
      assertEquals(15.0, teleop.get("start").getAsDouble(), 0.01);
      assertEquals(150.0, teleop.get("end").getAsDouble(), 0.01);
    }

    @Test
    @DisplayName("does not assume phases when DS data is missing")
    void doesNotAssumePhasesWhenDsMissing() throws Exception {
      // Log with no DriverStation entries at all
      var log = new MockLogBuilder()
          .setPath("/test/no_ds.wpilog")
          .addNumericEntry("/Motor/Velocity", new double[]{0, 1, 2}, new double[]{10, 20, 30})
          .build();
      putLogInCache(log);

      var tool = findTool("get_match_phases");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      // Nothing to analyze: no_match (not a success), saying what was searched for
      assertFalse(resultObj.get("success").getAsBoolean());
      assertEquals("no_match", resultObj.get("status").getAsString());
      assertTrue(resultObj.get("reason").getAsString().contains("DriverStation"));
      assertTrue(resultObj.getAsJsonArray("looked_for").size() > 0);
      assertFalse(resultObj.has("phases"), "no phases may be assumed");
      assertEquals("none", resultObj.get("source").getAsString(),
          "Should report source as 'none' when no DS data found");
    }

    @Test
    @DisplayName("teleop start accounts for FMS disabled gap between auto and teleop")
    void teleopAccountsForFmsDisabledGap() throws Exception {
      // Simulate FMS behavior: enabled for auto, disabled for ~2s, re-enabled for teleop
      var enabledValues = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, true));   // auto enable
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(15.0, false)); // auto disable
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(17.0, true));  // teleop enable
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(152.0, false)); // match end

      var autoValues = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      autoValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, true));
      autoValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(15.0, false));

      var log = new MockLogBuilder()
          .setPath("/test/fms_gap.wpilog")
          .addEntry("/DriverStation/Enabled", "boolean", enabledValues)
          .addEntry("/DriverStation/Autonomous", "boolean", autoValues)
          .build();
      putLogInCache(log);

      var tool = findTool("get_match_phases");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var phases = resultObj.getAsJsonObject("phases");

      // Auto: 0-15s
      var auto = phases.getAsJsonObject("autonomous");
      assertEquals(0.0, auto.get("start").getAsDouble(), 0.01);
      assertEquals(15.0, auto.get("end").getAsDouble(), 0.01);

      // Teleop should start at 17s (when robot re-enables), not 15s
      var teleop = phases.getAsJsonObject("teleop");
      assertEquals(17.0, teleop.get("start").getAsDouble(), 0.01,
          "Teleop should start at re-enable time (17s), not at auto-end (15s)");
      assertEquals(152.0, teleop.get("end").getAsDouble(), 0.01);
    }

    @Test
    @DisplayName("works with different game year phase durations")
    void worksWithDifferentGameYearDurations() throws Exception {
      // Simulate a hypothetical game with 20s auto and 130s teleop (total 150s)
      var enabledValues = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(5.0, true));
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(155.0, false));

      var autoValues = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      autoValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(5.0, true));
      autoValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(25.0, false));

      var log = new MockLogBuilder()
          .setPath("/test/different_game.wpilog")
          .addEntry("/DriverStation/Enabled", "boolean", enabledValues)
          .addEntry("/DriverStation/Autonomous", "boolean", autoValues)
          .build();
      putLogInCache(log);

      var tool = findTool("get_match_phases");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var phases = resultObj.getAsJsonObject("phases");

      // Auto should be 5-25s (20 seconds of auto)
      var auto = phases.getAsJsonObject("autonomous");
      assertEquals(5.0, auto.get("start").getAsDouble(), 0.01);
      assertEquals(25.0, auto.get("end").getAsDouble(), 0.01);
      assertEquals(20.0, auto.get("duration").getAsDouble(), 0.01);

      // Teleop should be 25-155s (130 seconds of teleop)
      var teleop = phases.getAsJsonObject("teleop");
      assertEquals(25.0, teleop.get("start").getAsDouble(), 0.01);
      assertEquals(155.0, teleop.get("end").getAsDouble(), 0.01);
    }
  }

  @Nested
  @DisplayName("moi_regression Tool")
  class MoiRegressionToolTests {

    @Test
    @DisplayName("skips samples with missing current instead of inserting zero")
    void skipsMissingCurrentInsteadOfInsertingZero() throws Exception {
      // Create velocity data spanning 0-2s at 100Hz
      int n = 200;
      double[] velTimestamps = new double[n];
      double[] velValues = new double[n];
      for (int i = 0; i < n; i++) {
        velTimestamps[i] = i * 0.01;
        // Sinusoidal velocity (rad/s) to get non-zero alpha
        velValues[i] = 10.0 * Math.sin(2 * Math.PI * velTimestamps[i]);
      }

      // Current data starts at t=1.0 (missing for first half of velocity data).
      // Previously, missing current was filled with 0.0 which corrupted the OLS fit.
      // Now it should be skipped (NaN), so the regression only uses t=1.0-2.0.
      int currStart = 100; // starts at t=1.0
      double[] currTimestamps = new double[n - currStart];
      double[] currValues = new double[n - currStart];
      for (int i = 0; i < currTimestamps.length; i++) {
        currTimestamps[i] = (i + currStart) * 0.01;
        // Proportional current for a simple motor model
        currValues[i] = 5.0 + 2.0 * Math.abs(Math.sin(2 * Math.PI * currTimestamps[i]));
      }

      var log = new MockLogBuilder()
          .setPath("/test/moi.wpilog")
          .addNumericEntry("/Motor/Velocity", velTimestamps, velValues)
          .addNumericEntry("/Motor/Current", currTimestamps, currValues)
          .build();

      putLogInCache(log);

      var tool = findTool("moi_regression");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("velocity_entry", "/Motor/Velocity");
      args.addProperty("current_entry", "/Motor/Current");
      args.addProperty("kt", 0.0194);
      args.addProperty("gear_ratio", 10.0);
      args.addProperty("alpha_threshold", 0.1);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      if (resultObj.get("success").getAsBoolean()) {
        // The key assertion: n_samples_used should be significantly less than
        // n_samples_total because the first ~100 samples had no current data
        // and should have been skipped (not filled with 0.0).
        int samplesUsed = resultObj.get("n_samples_used").getAsInt();
        int samplesTotal = resultObj.get("n_samples_total").getAsInt();
        assertTrue(samplesUsed < samplesTotal,
            "Should skip samples where current interpolation returned null. "
            + "Used: " + samplesUsed + ", Total: " + samplesTotal);
      }
      // If the regression fails due to insufficient samples after filtering,
      // that's also acceptable — the important thing is it doesn't silently
      // use 0.0 for missing current.
    }

    @Test
    @DisplayName("skips samples with missing voltage sign instead of using zero sign")
    void skipsMissingVoltageSign() throws Exception {
      // Create data where voltage starts later than velocity/current
      int n = 200;
      double[] velTimestamps = new double[n];
      double[] velValues = new double[n];
      double[] currTimestamps = new double[n];
      double[] currValues = new double[n];
      for (int i = 0; i < n; i++) {
        velTimestamps[i] = i * 0.01;
        velValues[i] = 10.0 * Math.sin(2 * Math.PI * velTimestamps[i]);
        currTimestamps[i] = i * 0.01;
        currValues[i] = 5.0 + Math.abs(velValues[i]) * 0.3;
      }

      // Voltage only available from t=1.0 onward
      int voltStart = 100;
      double[] voltTimestamps = new double[n - voltStart];
      double[] voltValues = new double[n - voltStart];
      for (int i = 0; i < voltTimestamps.length; i++) {
        voltTimestamps[i] = (i + voltStart) * 0.01;
        voltValues[i] = 12.0 * Math.sin(2 * Math.PI * voltTimestamps[i]);
      }

      var log = new MockLogBuilder()
          .setPath("/test/moi_volts.wpilog")
          .addNumericEntry("/Motor/Velocity", velTimestamps, velValues)
          .addNumericEntry("/Motor/Current", currTimestamps, currValues)
          .addNumericEntry("/Motor/AppliedVolts", voltTimestamps, voltValues)
          .build();

      putLogInCache(log);

      var tool = findTool("moi_regression");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("velocity_entry", "/Motor/Velocity");
      args.addProperty("current_entry", "/Motor/Current");
      args.addProperty("applied_volts_entry", "/Motor/AppliedVolts");
      args.addProperty("kt", 0.0194);
      args.addProperty("gear_ratio", 10.0);
      args.addProperty("alpha_threshold", 0.1);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      // The regression should either succeed with fewer samples (skipping missing voltage)
      // or fail gracefully — but should never silently use sign=0 for missing voltage.
      if (resultObj.get("success").getAsBoolean()) {
        int samplesUsed = resultObj.get("n_samples_used").getAsInt();
        int samplesTotal = resultObj.get("n_samples_total").getAsInt();
        assertTrue(samplesUsed < samplesTotal,
            "Should skip samples where voltage interpolation returned null");
      }
    }

    @Test
    @DisplayName("uses all samples when current fully overlaps velocity")
    void usesAllSamplesWhenDataFullyOverlaps() throws Exception {
      // When current data spans the same time range as velocity, all samples
      // should be available (none skipped due to null interpolation).
      int n = 200;
      double dt = 0.01;
      double[] velTs = new double[n];
      double[] velVals = new double[n];
      double[] currTs = new double[n];
      double[] currVals = new double[n];

      for (int i = 0; i < n; i++) {
        double t = i * dt;
        velTs[i] = t;
        currTs[i] = t;
        velVals[i] = 20.0 * Math.sin(2 * Math.PI * 2.0 * t); // fast oscillation
        currVals[i] = 5.0 + 3.0 * Math.abs(Math.sin(2 * Math.PI * 2.0 * t));
      }

      var log = new MockLogBuilder()
          .setPath("/test/moi_complete.wpilog")
          .addNumericEntry("/Motor/Velocity", velTs, velVals)
          .addNumericEntry("/Motor/Current", currTs, currVals)
          .build();

      putLogInCache(log);

      var tool = findTool("moi_regression");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("velocity_entry", "/Motor/Velocity");
      args.addProperty("current_entry", "/Motor/Current");
      args.addProperty("kt", 0.0194);
      args.addProperty("gear_ratio", 10.0);
      args.addProperty("alpha_threshold", 0.1);
      args.addProperty("smooth_window", 1);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean(),
          "Regression should succeed with complete overlapping data: " + resultObj);
      // With full overlap, the number of samples used (after alpha filtering)
      // should be a large fraction of total
      int samplesUsed = resultObj.get("n_samples_used").getAsInt();
      int samplesTotal = resultObj.get("n_samples_total").getAsInt();
      assertTrue(samplesUsed > samplesTotal / 4,
          "Should use many samples when data fully overlaps. "
          + "Used: " + samplesUsed + ", Total: " + samplesTotal);
    }

    @Test
    @DisplayName("handles duplicate timestamps without division by zero")
    void handlesDuplicateTimestamps() throws Exception {
      // Simulate data with duplicate timestamps (common in WPILib when multiple
      // values are logged in the same robot loop iteration)
      int n = 100;
      double[] velTs = new double[n];
      double[] velVals = new double[n];
      double[] currTs = new double[n];
      double[] currVals = new double[n];

      for (int i = 0; i < n; i++) {
        // Every pair of samples shares the same timestamp
        velTs[i] = (i / 2) * 0.02;
        currTs[i] = (i / 2) * 0.02;
        velVals[i] = 10.0 * Math.sin(2 * Math.PI * velTs[i]);
        currVals[i] = 5.0 + Math.abs(velVals[i]) * 0.3;
      }

      var log = new MockLogBuilder()
          .setPath("/test/moi_dup.wpilog")
          .addNumericEntry("/Motor/Velocity", velTs, velVals)
          .addNumericEntry("/Motor/Current", currTs, currVals)
          .build();

      putLogInCache(log);

      var tool = findTool("moi_regression");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("velocity_entry", "/Motor/Velocity");
      args.addProperty("current_entry", "/Motor/Current");
      args.addProperty("kt", 0.0194);
      args.addProperty("gear_ratio", 10.0);
      args.addProperty("alpha_threshold", 0.01);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      // Should not produce NaN or Infinity in output
      if (resultObj.get("success").getAsBoolean()) {
        double J = resultObj.get("J_kg_m2").getAsDouble();
        double B = resultObj.get("B_Nm_s_per_rad").getAsDouble();
        assertTrue(Double.isFinite(J), "J should be finite, got: " + J);
        assertTrue(Double.isFinite(B), "B should be finite, got: " + B);
      }
      // If it fails due to insufficient samples, that's acceptable —
      // the important thing is no NaN/Infinity/crash from zero-dt division
    }
  }

  // ==================== ToolBase/LogRequiringTool Migration Tests ====================

  @Nested
  @DisplayName("ToolBase Migration")
  class ToolBaseMigrationTests {

    @Test
    @DisplayName("all log-requiring tools return error when path not provided")
    void allToolsReturnErrorWhenNoPath() throws Exception {
      String[] logRequiringTools = {
          "get_match_phases", "analyze_swerve", "power_analysis",
          "can_health", "moi_regression", "get_code_metadata"
      };

      for (String toolName : logRequiringTools) {
        var tool = findTool(toolName);
        var result = tool.execute(new JsonObject());
        var resultObj = result.getAsJsonObject();

        assertFalse(resultObj.get("success").getAsBoolean(),
            toolName + " should return error when path not provided");
        assertTrue(resultObj.has("error"),
            toolName + " should have error message");
        assertTrue(resultObj.get("error").getAsString().toLowerCase().contains("path"),
            toolName + " error should mention no log: " + resultObj.get("error").getAsString());
      }
    }

    @Test
    @DisplayName("compare_matches returns error for missing name parameter")
    void compareMatchesMissingNameParam() throws Exception {
      // Load 2 logs so the "need 2 logs" check passes, then the missing name is caught
      var log1 = new MockLogBuilder()
          .setPath("/test/log1.wpilog")
          .addNumericEntry("/Test", new double[]{0}, new double[]{1})
          .build();
      var log2 = new MockLogBuilder()
          .setPath("/test/log2.wpilog")
          .addNumericEntry("/Test", new double[]{0}, new double[]{2})
          .build();
      var manager = LogManager.getInstance();
      manager.testPutLog(log1.path(), log1);
      manager.testPutLog(log2.path(), log2);

      var tool = findTool("compare_matches");
      // No name parameter provided
      var args = new JsonObject();
      args.addProperty("path", log1.path());
      args.addProperty("compare_path", log2.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("name"),
          "Should report missing 'name' parameter: " + resultObj.get("error").getAsString());
    }

    @Test
    @DisplayName("compare_matches reports per-log presence, sample counts, and warnings")
    void compareMatchesPerLogFields() throws Exception {
      var log1 = new MockLogBuilder()
          .setPath("/test/cmp_q10.wpilog")
          .addNumericEntry("/SystemStats/BatteryVoltage",
              new double[]{0, 1, 2}, new double[]{12.0, 11.0, Double.NaN})
          .build();
      var log2 = new MockLogBuilder()
          .setPath("/test/cmp_q13.wpilog")
          .addNumericEntry("/Other", new double[]{0}, new double[]{1})
          .build();
      var manager = LogManager.getInstance();
      manager.testPutLog(log1.path(), log1);
      manager.testPutLog(log2.path(), log2);

      var tool = findTool("compare_matches");
      var args = new JsonObject();
      args.addProperty("path", log1.path());
      args.addProperty("compare_path", log2.path());
      args.addProperty("name", "/SystemStats/BatteryVoltage");
      var resultObj = tool.execute(args).getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals("/SystemStats/BatteryVoltage", resultObj.get("entry").getAsString());
      assertEquals(2, resultObj.get("logs_compared").getAsInt());
      var comparisons = resultObj.getAsJsonArray("comparisons");
      assertEquals(2, comparisons.size());

      var first = comparisons.get(0).getAsJsonObject();
      assertEquals("/test/cmp_q10.wpilog", first.get("log_path").getAsString());
      assertEquals("cmp_q10.wpilog", first.get("log_filename").getAsString());
      assertTrue(first.get("entry_found").getAsBoolean());
      assertEquals(2, first.get("sample_count").getAsLong()); // NaN excluded
      assertEquals(11.0, first.getAsJsonObject("statistics").get("min").getAsDouble(), 1e-9);

      var second = comparisons.get(1).getAsJsonObject();
      assertFalse(second.get("entry_found").getAsBoolean());
      assertFalse(second.has("sample_count"));
      assertFalse(second.has("statistics"));
      assertTrue(resultObj.getAsJsonArray("warnings").toString()
          .contains("cmp_q13.wpilog: Entry not found: /SystemStats/BatteryVoltage"));
      assertEquals("partial", resultObj.get("status").getAsString());
    }

    @Test
    @DisplayName("compare_matches reports found-but-not-scalar entries without statistics")
    void compareMatchesArrayEntry() throws Exception {
      var arr = new ArrayList<TimestampedValue>();
      arr.add(new TimestampedValue(0.0, new double[]{1.0, 2.0}));
      var log1 = new MockLogBuilder()
          .setPath("/test/cmp_arr1.wpilog")
          .addEntry("/PowerDistribution/ChannelCurrent", "double[]", arr)
          .build();
      var log2 = new MockLogBuilder()
          .setPath("/test/cmp_arr2.wpilog")
          .addNumericEntry("/PowerDistribution/ChannelCurrent", new double[]{0, 1}, new double[]{3.0, 4.0})
          .build();
      var manager = LogManager.getInstance();
      manager.testPutLog(log1.path(), log1);
      manager.testPutLog(log2.path(), log2);

      var args = new JsonObject();
      args.addProperty("path", log1.path());
      args.addProperty("compare_path", log2.path());
      args.addProperty("name", "/PowerDistribution/ChannelCurrent");
      var resultObj = findTool("compare_matches").execute(args).getAsJsonObject();

      var first = resultObj.getAsJsonArray("comparisons").get(0).getAsJsonObject();
      assertTrue(first.get("entry_found").getAsBoolean());
      assertFalse(first.has("statistics"));
      assertTrue(first.get("reason").getAsString().contains("is double[], one value per element"),
          first.toString());
      var second = resultObj.getAsJsonArray("comparisons").get(1).getAsJsonObject();
      assertEquals(2, second.get("sample_count").getAsLong());
      assertTrue(second.has("statistics"));
      assertTrue(resultObj.getAsJsonArray("warnings").toString()
          .contains("/PowerDistribution/ChannelCurrent[0]"), resultObj.toString());
    }

    @Test
    @DisplayName("compare_matches returns error when fewer than 2 logs loaded")
    void compareMatchesNeedsTwoLogs() throws Exception {
      // Load one log
      var log = new MockLogBuilder()
          .setPath("/test/single.wpilog")
          .addNumericEntry("/Test", new double[]{0}, new double[]{1})
          .build();
      putLogInCache(log);

      var tool = findTool("compare_matches");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("compare_path", "/test/nonexistent.wpilog");
      args.addProperty("name", "/Test");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
    }

    @Test
    @DisplayName("get_code_metadata uses ResponseBuilder success pattern")
    void getCodeMetadataUsesResponseBuilder() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/meta.wpilog")
          .addEntry("/Metadata/GitSHA", "string",
              java.util.List.of(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, "abc123")))
          .build();
      putLogInCache(log);

      var tool = findTool("get_code_metadata");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("metadata"));
    }

    @Test
    @DisplayName("get_match_phases and can_health recognize WPILib DataLogManager DS: entries")
    void plainWpilibDsEntries() throws Exception {
      var console = new ArrayList<TimestampedValue>();
      console.add(new TimestampedValue(0.5, "CAN timeout while disabled"));
      console.add(new TimestampedValue(5.0, "CAN timeout while enabled"));
      var log = new MockLogBuilder()
          .setPath("/test/plain_ds.wpilog")
          .addBooleanEntry("DS:enabled", new double[]{0, 1, 16, 18, 153},
              new boolean[]{false, true, false, true, false})
          .addBooleanEntry("DS:autonomous", new double[]{0, 16}, new boolean[]{true, false})
          .addEntry("messages", "string", console)
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", log.path());

      var phases = findTool("get_match_phases").execute(args).getAsJsonObject();
      assertTrue(phases.get("success").getAsBoolean());
      assertEquals("DriverStation", phases.get("source").getAsString());
      assertTrue(phases.getAsJsonObject("phases").has("autonomous"), phases.toString());
      assertTrue(phases.getAsJsonObject("phases").has("teleop"), phases.toString());

      var can = findTool("can_health").execute(args).getAsJsonObject();
      assertTrue(can.get("success").getAsBoolean());
      assertEquals(1, can.get("errors_while_enabled").getAsLong());
      assertEquals(1, can.get("errors_while_disabled").getAsLong());
    }

    @Test
    @DisplayName("get_match_phases warns when DS entries exist but the robot was never enabled")
    void neverEnabledLog() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/never_enabled.wpilog")
          .addBooleanEntry("DS:enabled", new double[]{0}, new boolean[]{false})
          .addBooleanEntry("DS:autonomous", new double[]{0}, new boolean[]{false})
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", log.path());
      var phases = findTool("get_match_phases").execute(args).getAsJsonObject();

      assertTrue(phases.get("success").getAsBoolean());
      assertEquals("DriverStation", phases.get("source").getAsString());
      assertEquals(0, phases.getAsJsonObject("phases").size());
      assertTrue(phases.getAsJsonArray("warnings").toString().contains("never enabled"),
          phases.toString());
    }

    @Test
    @DisplayName("power_analysis expands per-channel current arrays and ranks by peak")
    void powerAnalysisChannelArrays() throws Exception {
      var channelCurrent = new ArrayList<TimestampedValue>();
      channelCurrent.add(new TimestampedValue(0.0, new double[]{1.0, 50.0, 0.0}));
      channelCurrent.add(new TimestampedValue(1.0, new double[]{2.0, 60.0, 0.0}));
      channelCurrent.add(new TimestampedValue(2.0, new double[]{1.0, 40.0, Double.NaN}));
      var log = new MockLogBuilder()
          .setPath("/test/power_channels.wpilog")
          .addNumericEntry("/SystemStats/BatteryVoltage",
              new double[]{0, 1, 2}, new double[]{12.5, 11.0, 12.4})
          .addEntry("/PowerDistribution/ChannelCurrent", "double[]", channelCurrent)
          .addNumericEntry("/PowerDistribution/TotalCurrent",
              new double[]{0, 1, 2}, new double[]{55.0, 62.0, 41.0})
          .build();
      putLogInCache(log);

      var tool = findTool("power_analysis");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var resultObj = tool.execute(args).getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals("/SystemStats/BatteryVoltage",
          resultObj.getAsJsonObject("voltage_analysis").get("entry").getAsString());
      assertEquals(6.8, resultObj.getAsJsonObject("voltage_analysis").get("brownout_threshold").getAsDouble(), 1e-9);
      assertEquals(4, resultObj.get("current_entries_analyzed").getAsInt());

      var channels = resultObj.getAsJsonArray("channel_analysis");
      assertEquals(4, channels.size());
      var top = channels.get(0).getAsJsonObject();
      assertEquals("/PowerDistribution/TotalCurrent", top.get("entry").getAsString());
      assertEquals(62.0, top.get("peak_current_A").getAsDouble(), 1e-9);
      assertEquals(1.0, top.get("peak_current_time_sec").getAsDouble(), 1e-9);
      assertEquals(62.0, top.get("max_current_A").getAsDouble(), 1e-9);
      assertEquals(41.0, top.get("min_current_A").getAsDouble(), 1e-9);
      assertFalse(top.has("channel"));

      var second = channels.get(1).getAsJsonObject();
      assertEquals("/PowerDistribution/ChannelCurrent[1]", second.get("entry").getAsString());
      assertEquals("/PowerDistribution/ChannelCurrent", second.get("source_entry").getAsString());
      assertEquals(1, second.get("channel").getAsInt());
      assertEquals(60.0, second.get("peak_current_A").getAsDouble(), 1e-9);
      assertEquals(50.0, second.get("avg_current_A").getAsDouble(), 1e-9);
      assertEquals(3, second.get("sample_count").getAsLong());

      // NaN sample in channel 2 is ignored: 2 finite samples, both zero
      var last = channels.get(3).getAsJsonObject();
      assertEquals("/PowerDistribution/ChannelCurrent[2]", last.get("entry").getAsString());
      assertEquals(2, last.get("sample_count").getAsLong());
      assertEquals(0.0, last.get("peak_current_A").getAsDouble(), 1e-9);
      assertFalse(resultObj.has("warnings"));

      // channel_limit truncates and warns
      args.addProperty("channel_limit", 2);
      var limited = tool.execute(args).getAsJsonObject();
      assertEquals(2, limited.getAsJsonArray("channel_analysis").size());
      assertEquals(4, limited.get("current_entries_analyzed").getAsInt());
      assertTrue(limited.getAsJsonArray("warnings").toString().contains("channel_limit"));
    }

    @Test
    @DisplayName("power_analysis only treats amperage entries as currents")
    void powerAnalysisCurrentNameFilter() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/power_names.wpilog")
          .addNumericEntry("NT:/SmartDashboard/Algae Wrist/Current Angle Degrees",
              new double[]{0, 1}, new double[]{98.9, 97.0})
          .addNumericEntry("/Drive/CurrentLimit", new double[]{0, 1}, new double[]{40.0, 40.0})
          .addNumericEntry("/Elevator/CurrentState", new double[]{0, 1}, new double[]{2.0, 3.0})
          .addNumericEntry("/Elevator/StatorAmps", new double[]{0, 1}, new double[]{12.0, 30.0})
          .addNumericEntry("NT:/SmartDashboard/FrontLeft/OutputCurrent",
              new double[]{0, 1}, new double[]{5.0, 7.0})
          .addNumericEntry("/Intake/Current/Stator", new double[]{0, 1}, new double[]{9.0, 11.0})
          .addNumericEntry("/Intake/Current/Setpoint", new double[]{0, 1}, new double[]{9.0, 11.0})
          .addEntry("/Drive/Module0/OdometryTimestamps", "double[]",
              List.of(new TimestampedValue(0.0, new double[]{100.0, 100.02})))
          .addNumericEntry("/Drive/SlewRamps", new double[]{0, 1}, new double[]{3.0, 3.0})
          .addNumericEntry("NT:/SmartDashboard/PowerDistribution[1]/Chan3", new double[]{0, 1}, new double[]{4.0, 8.0})
          .build();
      putLogInCache(log);

      var tool = findTool("power_analysis");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var resultObj = tool.execute(args).getAsJsonObject();

      var names = new ArrayList<String>();
      for (var c : resultObj.getAsJsonArray("channel_analysis")) {
        names.add(c.getAsJsonObject().get("entry").getAsString());
      }
      assertEquals(
          java.util.List.of("/Elevator/StatorAmps", "/Intake/Current/Stator",
              "NT:/SmartDashboard/PowerDistribution[1]/Chan3",
              "NT:/SmartDashboard/FrontLeft/OutputCurrent"),
          names);
      assertEquals(4, resultObj.get("current_entries_analyzed").getAsInt());
    }

    @Test
    @DisplayName("power_analysis prefers the battery voltage entry over rails")
    void powerAnalysisPrefersBatteryVoltage() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/power_rails.wpilog")
          .addNumericEntry("/SystemStats/5vRail/Voltage", new double[]{0, 1}, new double[]{5.0, 5.0})
          .addNumericEntry("/SystemStats/3v3Rail/Voltage", new double[]{0, 1}, new double[]{3.3, 3.3})
          .addNumericEntry("/SystemStats/BatteryVoltage", new double[]{0, 1}, new double[]{12.6, 12.4})
          .addNumericEntry("/Elevator/AppliedVoltage", new double[]{0, 1}, new double[]{6.0, 6.0})
          .build();
      putLogInCache(log);

      var tool = findTool("power_analysis");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var resultObj = tool.execute(args).getAsJsonObject();
      assertEquals("/SystemStats/BatteryVoltage",
          resultObj.getAsJsonObject("voltage_analysis").get("entry").getAsString());

      // Without a battery entry, a generic voltage beats a rail
      var log2 = new MockLogBuilder()
          .setPath("/test/power_rails2.wpilog")
          .addNumericEntry("/SystemStats/5vRail/Voltage", new double[]{0, 1}, new double[]{5.0, 5.0})
          .addNumericEntry("NT:/SmartDashboard/PowerDistribution[0]/Voltage",
              new double[]{0, 1}, new double[]{12.6, 12.4})
          .build();
      putLogInCache(log2);
      args.addProperty("path", log2.path());
      var resultObj2 = tool.execute(args).getAsJsonObject();
      assertEquals("NT:/SmartDashboard/PowerDistribution[0]/Voltage",
          resultObj2.getAsJsonObject("voltage_analysis").get("entry").getAsString());
    }

    @Test
    @DisplayName("power_analysis reports peaks by magnitude for signed currents")
    void powerAnalysisSignedCurrents() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/power_signed.wpilog")
          .addNumericEntry("/SystemStats/BatteryVoltage", new double[]{0, 1, 2}, new double[]{12, 12, 12})
          .addNumericEntry("/Climber/TorqueCurrentAmps", new double[]{0, 1, 2}, new double[]{-150.0, -20.0, 5.0})
          .addNumericEntry("/Intake/CurrentAmps", new double[]{0, 1, 2}, new double[]{10.0, 40.0, 12.0})
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", log.path());
      var channels = findTool("power_analysis").execute(args).getAsJsonObject().getAsJsonArray("channel_analysis");

      var first = channels.get(0).getAsJsonObject();
      assertEquals("/Climber/TorqueCurrentAmps", first.get("entry").getAsString());
      assertEquals(-150.0, first.get("peak_current_A").getAsDouble(), 1e-9);
      assertEquals(0.0, first.get("peak_current_time_sec").getAsDouble(), 1e-9);
      assertEquals(5.0, first.get("max_current_A").getAsDouble(), 1e-9);
      assertEquals(-150.0, first.get("min_current_A").getAsDouble(), 1e-9);
      assertEquals("/Intake/CurrentAmps", channels.get(1).getAsJsonObject().get("entry").getAsString());
    }

    @Test
    @DisplayName("power_analysis handles float arrays, ragged arrays, and non-finite thresholds")
    void powerAnalysisArrayEdgeCases() throws Exception {
      var ragged = new ArrayList<TimestampedValue>();
      ragged.add(new TimestampedValue(0.0, new float[]{1.5f, 20.0f}));
      ragged.add(new TimestampedValue(1.0, new float[]{2.5f, 30.0f, 7.0f}));
      var voltage = new ArrayList<TimestampedValue>();
      voltage.add(new TimestampedValue(0.0, 12.0));
      voltage.add(new TimestampedValue(1.0, Double.NEGATIVE_INFINITY));
      voltage.add(new TimestampedValue(2.0, 11.5));
      var log = new MockLogBuilder()
          .setPath("/test/power_arrays.wpilog")
          .addEntry("/SystemStats/BatteryVoltage", "double", voltage)
          .addEntry("/PDP/ChannelCurrent", "float[]", ragged)
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", log.path());
      var resultObj = findTool("power_analysis").execute(args).getAsJsonObject();

      // -Infinity is neither in the stats nor counted below threshold
      var va = resultObj.getAsJsonObject("voltage_analysis");
      assertEquals(11.5, va.get("min_voltage").getAsDouble(), 1e-9);
      assertEquals(0, va.get("samples_below_threshold").getAsLong());

      assertEquals(3, resultObj.get("current_entries_analyzed").getAsInt());
      var channels = resultObj.getAsJsonArray("channel_analysis");
      var counts = new java.util.HashMap<String, Long>();
      for (var c : channels) {
        counts.put(c.getAsJsonObject().get("entry").getAsString(), c.getAsJsonObject().get("sample_count").getAsLong());
      }
      assertEquals(2L, counts.get("/PDP/ChannelCurrent[0]"));
      assertEquals(2L, counts.get("/PDP/ChannelCurrent[1]"));
      assertEquals(1L, counts.get("/PDP/ChannelCurrent[2]"));
      assertEquals(30.0, channels.get(0).getAsJsonObject().get("peak_current_A").getAsDouble(), 1e-6);
    }

    @Test
    @DisplayName("power_analysis expands int64 arrays and echoes a custom brownout threshold")
    void powerAnalysisInt64ArraysAndThreshold() throws Exception {
      var longs = new ArrayList<TimestampedValue>();
      longs.add(new TimestampedValue(0.0, new long[]{3L, 40L}));
      longs.add(new TimestampedValue(1.0, new long[]{5L, 20L}));
      var log = new MockLogBuilder()
          .setPath("/test/power_int64.wpilog")
          .addNumericEntry("/SystemStats/BatteryVoltage", new double[]{0, 1, 2, 3}, new double[]{12.0, 6.5, 6.2, 12.0})
          .addEntry("/PDP/ChannelCurrent", "int64[]", longs)
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("brownout_threshold", 6.3);
      var resultObj = findTool("power_analysis").execute(args).getAsJsonObject();

      var va = resultObj.getAsJsonObject("voltage_analysis");
      assertEquals(6.3, va.get("brownout_threshold").getAsDouble(), 1e-9);
      assertEquals(1, va.get("samples_below_threshold").getAsLong()); // only 6.2 V is below 6.3
      assertEquals("HIGH", va.get("brownout_risk").getAsString());

      assertEquals(2, resultObj.get("current_entries_analyzed").getAsInt());
      var top = resultObj.getAsJsonArray("channel_analysis").get(0).getAsJsonObject();
      assertEquals("/PDP/ChannelCurrent[1]", top.get("entry").getAsString());
      assertEquals(40.0, top.get("peak_current_A").getAsDouble(), 1e-9);
      assertEquals(2, top.get("sample_count").getAsLong());
    }

    @Test
    @DisplayName("power_analysis clamps channel_limit and honors power_prefix")
    void powerAnalysisLimitAndPrefix() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/power_prefix.wpilog")
          .addNumericEntry("/SystemStats/BatteryVoltage", new double[]{0, 1}, new double[]{12.6, 12.4})
          .addNumericEntry("/PDH/InputVoltage", new double[]{0, 1}, new double[]{12.5, 12.3})
          .addNumericEntry("/PDH/TotalCurrent", new double[]{0, 1}, new double[]{30.0, 45.0})
          .addNumericEntry("/Drive/CurrentAmps", new double[]{0, 1}, new double[]{60.0, 70.0})
          .build();
      putLogInCache(log);

      var tool = findTool("power_analysis");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("power_prefix", "/PDH");
      var guessed = tool.execute(args).getAsJsonObject();
      // InputVoltage is not a battery-voltage convention: listed to confirm, not used
      assertFalse(guessed.has("voltage_analysis"));
      assertTrue(guessed.getAsJsonArray("skipped").toString().contains("/PDH/InputVoltage"));
      args.addProperty("voltage_entry", "/PDH/InputVoltage");
      var resultObj = tool.execute(args).getAsJsonObject();
      assertEquals("/PDH/InputVoltage", resultObj.getAsJsonObject("voltage_analysis").get("entry").getAsString());
      assertEquals(1, resultObj.get("current_entries_analyzed").getAsInt());
      assertEquals("/PDH/TotalCurrent",
          resultObj.getAsJsonArray("channel_analysis").get(0).getAsJsonObject().get("entry").getAsString());

      args.remove("power_prefix");
      args.remove("voltage_entry");
      args.addProperty("channel_limit", 0);
      var clamped = tool.execute(args).getAsJsonObject();
      assertEquals(1, clamped.getAsJsonArray("channel_analysis").size());
      assertEquals(2, clamped.get("current_entries_analyzed").getAsInt());
      assertTrue(clamped.getAsJsonArray("warnings").toString().contains("top 1 of 2"));
    }

    @Test
    @DisplayName("power_analysis falls back to a current entry for data quality and warns per missing kind")
    void powerAnalysisFallbacks() throws Exception {
      var currentOnly = new MockLogBuilder()
          .setPath("/test/power_current_only.wpilog")
          .addNumericEntry("/PowerDistribution/TotalCurrent", new double[]{0, 1, 2}, new double[]{30.0, 45.0, 20.0})
          .build();
      putLogInCache(currentOnly);
      var args = new JsonObject();
      args.addProperty("path", currentOnly.path());
      var r1 = findTool("power_analysis").execute(args).getAsJsonObject();
      assertFalse(r1.has("voltage_analysis"));
      assertTrue(r1.has("data_quality"), "quality should come from the scalar current entry");
      assertTrue(r1.getAsJsonArray("warnings").toString().contains("No battery voltage entry found"));

      var arrayOnly = new ArrayList<TimestampedValue>();
      arrayOnly.add(new TimestampedValue(0.0, new double[]{1.0, 2.0}));
      var arrayLog = new MockLogBuilder()
          .setPath("/test/power_array_only.wpilog")
          .addEntry("/PowerDistribution/ChannelCurrent", "double[]", arrayOnly)
          .build();
      putLogInCache(arrayLog);
      args.addProperty("path", arrayLog.path());
      var r2 = findTool("power_analysis").execute(args).getAsJsonObject();
      assertEquals(2, r2.get("current_entries_analyzed").getAsInt());
      assertFalse(r2.has("data_quality"), "no scalar entry to measure quality from");

      var nanVoltage = new ArrayList<TimestampedValue>();
      nanVoltage.add(new TimestampedValue(0.0, Double.NaN));
      var nanLog = new MockLogBuilder()
          .setPath("/test/power_nan_voltage.wpilog")
          .addEntry("/SystemStats/BatteryVoltage", "double", nanVoltage)
          .addNumericEntry("/Drive/CurrentAmps", new double[]{0}, new double[]{10.0})
          .build();
      putLogInCache(nanLog);
      args.addProperty("path", nanLog.path());
      var r3 = findTool("power_analysis").execute(args).getAsJsonObject();
      assertFalse(r3.has("voltage_analysis"));
      assertTrue(r3.getAsJsonArray("warnings").toString().contains("with no finite samples: /SystemStats/BatteryVoltage"),
          r3.toString());

      var voltageOnly = new MockLogBuilder()
          .setPath("/test/power_voltage_only.wpilog")
          .addNumericEntry("/SystemStats/BatteryVoltage", new double[]{0, 1}, new double[]{12.0, 11.9})
          .addNumericEntry("NT:/SmartDashboard/Algae Wrist/Current Angle Degrees", new double[]{0}, new double[]{90})
          .build();
      putLogInCache(voltageOnly);
      args.addProperty("path", voltageOnly.path());
      var r4 = findTool("power_analysis").execute(args).getAsJsonObject();
      assertEquals(0, r4.get("current_entries_analyzed").getAsInt());
      assertFalse(r4.has("channel_analysis"));
      assertTrue(r4.getAsJsonArray("warnings").toString().contains("No current entries found"));
    }

    @Test
    @DisplayName("power_analysis warns when no power entries exist")
    void powerAnalysisNoData() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/power_none.wpilog")
          .addNumericEntry("/Drive/Speed", new double[]{0, 1}, new double[]{1.0, 2.0})
          .build();
      putLogInCache(log);

      var tool = findTool("power_analysis");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var resultObj = tool.execute(args).getAsJsonObject();

      // Nothing to analyze: no_match with what was searched for, not an empty success
      assertFalse(resultObj.get("success").getAsBoolean());
      assertEquals("no_match", resultObj.get("status").getAsString());
      assertFalse(resultObj.has("voltage_analysis"));
      assertFalse(resultObj.has("channel_analysis"));
      assertEquals(3, resultObj.getAsJsonArray("looked_for").size());
      assertFalse(resultObj.has("data_quality"));
    }

    @Test
    @DisplayName("power_analysis without current entries is partial, naming the skipped section")
    void powerAnalysisVoltageOnly() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/power_voltage_only.wpilog")
          .addNumericEntry("/SystemStats/BatteryVoltage", new double[]{0, 1}, new double[]{12.4, 12.1})
          .build();
      putLogInCache(log);
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var resultObj = findTool("power_analysis").execute(args).getAsJsonObject();
      assertEquals("partial", resultObj.get("status").getAsString());
      assertTrue(resultObj.getAsJsonArray("skipped").toString().contains("channel_analysis"));
      var v = resultObj.getAsJsonObject("voltage_analysis");
      assertEquals(6.8, v.get("brownout_threshold").getAsDouble());
      assertTrue(v.get("brownout_threshold_basis").getAsString().startsWith("default"));
    }

    @Test
    @DisplayName("power_analysis works with loaded log")
    void powerAnalysisWithLog() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/power.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2, 3, 4},
              new double[]{12.5, 12.3, 11.8, 12.1, 12.4})
          .build();
      putLogInCache(log);

      var tool = findTool("power_analysis");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("voltage_analysis"));
    }

    @Test
    @DisplayName("can_health works with loaded log")
    void canHealthWithLog() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/can.wpilog")
          .addNumericEntry("/Test/Value", new double[]{0, 1}, new double[]{1, 2})
          .build();
      putLogInCache(log);

      var tool = findTool("can_health");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      // A log with neither text nor CAN counters gives no health level (absence of evidence
      // is not GOOD): not_applicable, with what was looked for
      assertEquals("not_applicable", resultObj.get("status").getAsString(), resultObj.toString());
      assertFalse(resultObj.has("health_assessment"));
      assertTrue(resultObj.has("looked_for"));
    }

    @Test
    @DisplayName("analyze_swerve works with loaded log")
    void analyzeSwerveWithLog() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/swerve.wpilog")
          .addNumericEntry("/Drive/Speed", new double[]{0, 1}, new double[]{1, 2})
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      // No SwerveModuleState entries: no_match (not an empty success)
      assertFalse(resultObj.get("success").getAsBoolean());
      assertEquals("no_match", resultObj.get("status").getAsString());
      assertTrue(resultObj.getAsJsonArray("looked_for").size() > 0);
    }

    @Test
    @DisplayName("tools catch unexpected exceptions and return error responses")
    void toolsCatchUnexpectedExceptions() throws Exception {
      // Load a minimal log that will cause tools to fail in unexpected ways
      // (e.g., get_code_metadata with entries that have empty value lists)
      var entries = new java.util.HashMap<String, org.triplehelix.wpilogmcp.log.EntryInfo>();
      entries.put("/Metadata/GitSHA",
          new org.triplehelix.wpilogmcp.log.EntryInfo(1, "/Metadata/GitSHA", "string", ""));
      var values = new java.util.HashMap<String, java.util.List<org.triplehelix.wpilogmcp.log.TimestampedValue>>();
      values.put("/Metadata/GitSHA", new java.util.ArrayList<>()); // empty list — get(0) would throw

      var log = new org.triplehelix.wpilogmcp.log.ParsedLog(
          "/test/empty_meta.wpilog", entries, values, 0.0, 0.0);
      putLogInCache(log);

      var tool = findTool("get_code_metadata");
      // Empty value lists should be handled gracefully — returns "unknown" instead of throwing
      var args = new JsonObject();
      args.addProperty("path", "/test/empty_meta.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean(),
          "Should succeed gracefully with empty value lists");
      assertTrue(resultObj.has("metadata"),
          "Should have metadata field");
      // The value for GitSHA should be "unknown" since the values list was empty
      var metadata = resultObj.getAsJsonObject("metadata");
      assertEquals("unknown", metadata.get("GitSHA").getAsString(),
          "Empty value list should produce 'unknown'");
    }
  }

  // ==================== Comprehensive Swerve Analysis (§3.1) ====================

  @Nested
  @DisplayName("Comprehensive Swerve Analysis (§3.1)")
  class ComprehensiveSwerveTests {

    @Test
    @DisplayName("detects wheel slip between setpoint and measured")
    void detectsWheelSlip() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      putLogInCache(log);

      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals("per_module", resultObj.get("layout").getAsString());
      var modules = resultObj.getAsJsonArray("modules");
      assertEquals(4, modules.size());
      // Module 2's measured speed is 70% of its setpoint: the largest tracking error
      double worst = -1;
      String worstModule = null;
      for (var m : modules) {
        var o = m.getAsJsonObject();
        assertTrue(o.has("speed_tracking_error"), o.toString());
        double err = o.getAsJsonObject("speed_tracking_error").get("mean_mps").getAsDouble();
        if (err > worst) {
          worst = err;
          worstModule = o.get("module").getAsString();
        }
      }
      assertEquals("Module2", worstModule);
    }

    @Test
    @DisplayName("detects module sync issues")
    void detectsModuleSyncIssues() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      putLogInCache(log);

      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("module_sync"),
          "Should analyze steering against setpoints");
      var sync = resultObj.getAsJsonObject("module_sync");
      assertTrue(sync.get("samples_analyzed").getAsInt() > 0);
      // Module 3 has 0.2 rad offset in the test data
      assertTrue(sync.get("max_deviation_rad").getAsDouble() > 0.1,
          "Should detect the intentional angle deviation in module 3");
      assertEquals("Module3", sync.get("worst_module").getAsString());
    }

    @Test
    @DisplayName("detects odometry drift")
    void detectsOdometryDrift() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      putLogInCache(log);

      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("odometry_drift"),
          "Should detect odometry drift when odom and vision poses differ");
      var drift = resultObj.getAsJsonObject("odometry_drift");
      assertTrue(drift.get("max_error_m").getAsDouble() > 0,
          "Should measure positive drift");
    }

    @Test
    @DisplayName("gracefully handles log without swerve data")
    void gracefulWithoutSwerveData() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/no_swerve.wpilog")
          .addNumericEntry("/Motor/Speed", new double[]{0, 1}, new double[]{1, 2})
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      // Nothing to analyze: no_match, never an empty success
      assertEquals("no_match", resultObj.get("status").getAsString());
      assertFalse(resultObj.has("module_sync"));
      assertFalse(resultObj.has("odometry_drift"));
    }

    @Test
    @DisplayName("includes data quality and directives")
    void includesDataQuality() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      putLogInCache(log);

      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("data_quality"),
          "Should include data quality metadata");
      assertTrue(resultObj.has("server_analysis_directives"),
          "Should include analysis directives");
    }
  }

  // ==================== Data Quality Propagation (§2.1) ====================

  @Nested
  @DisplayName("Data Quality Propagation (§2.1)")
  class DataQualityPropagationTests {

    @Test
    @DisplayName("power_analysis includes data quality")
    void powerAnalysisQuality() throws Exception {
      var log = MockLogBuilder.createBrownoutMatchLog();
      putLogInCache(log);

      var tool = findTool("power_analysis");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("data_quality"));
      assertTrue(resultObj.has("server_analysis_directives"));
    }

    @Test
    @DisplayName("moi_regression includes data quality")
    void moiRegressionQuality() throws Exception {
      int n = 200;
      double[] velTs = new double[n], velVals = new double[n];
      double[] currTs = new double[n], currVals = new double[n];
      for (int i = 0; i < n; i++) {
        velTs[i] = i * 0.01; currTs[i] = i * 0.01;
        velVals[i] = 20 * Math.sin(2 * Math.PI * 2.0 * velTs[i]);
        currVals[i] = 5 + 3 * Math.abs(Math.sin(2 * Math.PI * 2.0 * currTs[i]));
      }

      var log = new MockLogBuilder()
          .setPath("/test/moi_quality.wpilog")
          .addNumericEntry("/Motor/Velocity", velTs, velVals)
          .addNumericEntry("/Motor/Current", currTs, currVals)
          .build();
      putLogInCache(log);

      var tool = findTool("moi_regression");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("velocity_entry", "/Motor/Velocity");
      args.addProperty("current_entry", "/Motor/Current");
      args.addProperty("kt", 0.0194);
      args.addProperty("gear_ratio", 10.0);
      args.addProperty("alpha_threshold", 0.1);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      if (resultObj.get("success").getAsBoolean()) {
        assertTrue(resultObj.has("data_quality"));
        assertTrue(resultObj.has("server_analysis_directives"));
      }
    }
  }

  // ==================== Swerve Edge Cases ====================

  @Nested
  @DisplayName("Swerve Analysis Edge Cases")
  class SwerveEdgeCases {

    @Test
    @DisplayName("slip threshold parameter is respected")
    void slipThresholdRespected() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      putLogInCache(log);

      // Very high threshold — no slip events should be detected
      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("slip_threshold", 100.0); // impossibly high
      var result = tool.execute(args).getAsJsonObject();

      var modules = result.getAsJsonArray("modules");
      assertEquals(4, modules.size());
      for (int i = 0; i < modules.size(); i++) {
        var tracking = modules.get(i).getAsJsonObject().getAsJsonObject("speed_tracking_error");
        assertEquals(0, tracking.get("events_over_threshold").getAsInt(),
            "No tracking error should exceed a 100 m/s threshold");
      }
    }

    @Test
    @DisplayName("sync threshold parameter is respected")
    void syncThresholdRespected() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      putLogInCache(log);

      // Very high threshold — no desync events
      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("sync_threshold_rad", 100.0);
      var result = tool.execute(args).getAsJsonObject();

      assertEquals(0, result.getAsJsonObject("module_sync").get("desync_events").getAsInt());
    }

    @Test
    @DisplayName("explicit odometry/vision entry names work")
    void explicitOdomVisionEntries() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      putLogInCache(log);

      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("odometry_entry", "/Odometry/Pose");
      args.addProperty("vision_entry", "/Vision/Pose");
      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.has("odometry_drift"),
          "Should use explicitly specified entries for drift analysis");
    }

    @Test
    @DisplayName("drift analysis reports correct entry names")
    void driftReportsEntryNames() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      putLogInCache(log);

      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args).getAsJsonObject();

      if (result.has("odometry_drift")) {
        var drift = result.getAsJsonObject("odometry_drift");
        assertNotNull(drift.get("odometry_entry").getAsString());
        assertNotNull(drift.get("vision_entry").getAsString());
        assertTrue(drift.get("comparisons").getAsInt() > 0);
      }
    }
  }

  // ==================== Match Phase Edge Cases ====================

  @Nested
  @DisplayName("Match Phase Edge Cases")
  class MatchPhaseEdgeCases {

    @Test
    @DisplayName("continuous enable (no FMS gap) sets teleop at autoEnd")
    void continuousEnable() throws Exception {
      // Robot stays enabled throughout (practice mode)
      var enabledValues = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, true));
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(160.0, false));

      var autoValues = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      autoValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, true));
      autoValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(20.0, false));

      var log = new MockLogBuilder()
          .setPath("/test/continuous.wpilog")
          .addEntry("/DriverStation/Enabled", "boolean", enabledValues)
          .addEntry("/DriverStation/Autonomous", "boolean", autoValues)
          .build();
      putLogInCache(log);

      var tool = findTool("get_match_phases");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      var phases = result.getAsJsonObject("phases");
      var teleop = phases.getAsJsonObject("teleop");
      assertEquals(20.0, teleop.get("start").getAsDouble(), 0.01,
          "Teleop should start at autoEnd when robot stays enabled");
    }

    @Test
    @DisplayName("auto start deferred until robot is enabled (pre-match FMS setup)")
    void autoStartDeferredUntilEnabled() throws Exception {
      // FMS sets Autonomous=true at t=0 during countdown, but robot isn't enabled until t=3
      var enabledValues = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, false));  // pre-match disabled
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(3.0, true));   // match start
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(18.0, false)); // auto end
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(20.0, true));  // teleop start
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(155.0, false)); // match end

      var autoValues = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      autoValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, true));   // FMS sets auto during countdown
      autoValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(18.0, false)); // auto mode ends

      var log = new MockLogBuilder()
          .setPath("/test/prematch_auto.wpilog")
          .addEntry("/DriverStation/Enabled", "boolean", enabledValues)
          .addEntry("/DriverStation/Autonomous", "boolean", autoValues)
          .build();
      putLogInCache(log);

      var tool = findTool("get_match_phases");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      var phases = result.getAsJsonObject("phases");
      var auto = phases.getAsJsonObject("autonomous");
      assertEquals(3.0, auto.get("start").getAsDouble(), 0.01,
          "Auto should start when robot is enabled, not when FMS sets auto flag");
      assertEquals(18.0, auto.get("end").getAsDouble(), 0.01);

      // Match duration should be from first enable to last disable
      assertEquals(152.0, result.get("match_duration").getAsDouble(), 0.01);
    }

    @Test
    @DisplayName("only enabled data (no auto entry) reports enabled phase with warning")
    void onlyEnabledNoAuto() throws Exception {
      var enabledValues = new ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, true));
      enabledValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(100.0, false));

      var log = new MockLogBuilder()
          .setPath("/test/no_auto.wpilog")
          .addEntry("/DriverStation/Enabled", "boolean", enabledValues)
          .build();
      putLogInCache(log);

      var tool = findTool("get_match_phases");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      var phases = result.getAsJsonObject("phases");
      assertTrue(phases.has("enabled"), "Should have generic 'enabled' phase");
      assertTrue(result.has("warnings"), "Should warn about missing auto/teleop distinction");
    }
  }

  @Nested
  @DisplayName("compare_matches Tool")
  class CompareMatchesToolTests {

    @Test
    @DisplayName("returns error when fewer than 2 logs loaded")
    void returnsErrorWithFewerThanTwoLogs() throws Exception {
      // Load only one log
      var log = new MockLogBuilder()
          .setPath("/test/single_match.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2}, new double[]{12.5, 12.3, 12.1})
          .build();
      putLogInCache(log);

      var tool = findTool("compare_matches");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("compare_path", "/test/nonexistent.wpilog");
      args.addProperty("name", "/Robot/BatteryVoltage");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
    }

    @Test
    @DisplayName("compares same entry across two loaded logs")
    void comparesSameEntryAcrossTwoLogs() throws Exception {
      var manager = LogManager.getInstance();

      // Load two logs with the same entry but different values
      var log1 = new MockLogBuilder()
          .setPath("/test/match1.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2, 3, 4},
              new double[]{12.5, 12.3, 12.1, 12.4, 12.2})
          .build();

      var log2 = new MockLogBuilder()
          .setPath("/test/match2.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2, 3, 4},
              new double[]{11.5, 11.0, 10.5, 11.2, 10.8})
          .build();

      manager.testPutLog(log1.path(), log1);
      manager.testPutLog(log2.path(), log2);

      var tool = findTool("compare_matches");
      var args = new JsonObject();
      args.addProperty("path", log1.path());
      args.addProperty("compare_path", log2.path());
      args.addProperty("name", "/Robot/BatteryVoltage");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("comparisons"), "Should have comparisons array");
      var comparisons = resultObj.getAsJsonArray("comparisons");
      assertEquals(2, comparisons.size(), "Should have 2 comparison entries");

      // Both should have statistics
      for (int i = 0; i < comparisons.size(); i++) {
        var comp = comparisons.get(i).getAsJsonObject();
        assertTrue(comp.has("log_filename"), "Each comparison should have log_filename");
        assertTrue(comp.has("statistics"), "Each comparison should have statistics");
        var stats = comp.getAsJsonObject("statistics");
        assertTrue(stats.has("min"), "Statistics should have min");
        assertTrue(stats.has("max"), "Statistics should have max");
        assertTrue(stats.has("mean"), "Statistics should have mean");
      }

      // Verify the statistics are different between the two logs
      var stats1 = comparisons.get(0).getAsJsonObject().getAsJsonObject("statistics");
      var stats2 = comparisons.get(1).getAsJsonObject().getAsJsonObject("statistics");
      assertNotEquals(
          stats1.get("mean").getAsDouble(),
          stats2.get("mean").getAsDouble(),
          0.001,
          "The two logs should have different mean voltages");
    }
  }

  @Nested
  @DisplayName("moi_regression extreme value warnings")
  class MoiRegressionExtremeValueTests {

    @Test
    @DisplayName("warns for extreme J values from near-collinear data")
    void testMoiRegressionWarnsForExtremeValues() throws Exception {
      // Create velocity/current data that produces extreme J (|J| > 1000).
      // The physics model: tau = J*alpha + B*omega, where tau = torqueScale * current.
      // If we simulate current as proportional to alpha (acceleration-dominated mechanism),
      // the regression should recover J ≈ torqueScale * currentScale / 1.
      // By making torqueScale (= G * kt) large and current large, we get |J| > 1000.
      int n = 200;
      double dt = 0.01;
      double[] velTs = new double[n];
      double[] velVals = new double[n];
      double[] currTs = new double[n];
      double[] currVals = new double[n];
      double w = 2 * Math.PI; // 1 Hz oscillation

      double[] voltTs = new double[n];
      double[] voltVals = new double[n];

      for (int i = 0; i < n; i++) {
        velTs[i] = i * dt;
        double t = velTs[i];
        // Sinusoidal velocity
        velVals[i] = 10.0 * Math.sin(w * t);
        currTs[i] = t;
        voltTs[i] = t;
        // Current proportional to |acceleration|, always positive (like TalonFX)
        // true alpha = 10*w*cos(w*t)
        currVals[i] = 100.0 * Math.abs(Math.cos(w * t)) + 5.0;
        // Voltage sign matches acceleration direction
        voltVals[i] = 12.0 * Math.signum(Math.cos(w * t) + 0.001);
      }

      var log = new MockLogBuilder()
          .setPath("/test/moi_extreme.wpilog")
          .addNumericEntry("/Motor/Velocity", velTs, velVals)
          .addNumericEntry("/Motor/Current", currTs, currVals)
          .addNumericEntry("/Motor/Voltage", voltTs, voltVals)
          .build();
      putLogInCache(log);

      var tool = findTool("moi_regression");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("velocity_entry", "/Motor/Velocity");
      args.addProperty("current_entry", "/Motor/Current");
      args.addProperty("applied_volts_entry", "/Motor/Voltage");
      // Extreme physical parameters: torqueScale = G * motors * kt = 1000 * 1 * 1.0 = 1000
      // With current ~10 and signed correctly, tau ~ 10000 for each sample.
      // The regression recovers J ≈ torqueScale * I / alpha which will be > 1000.
      args.addProperty("kt", 1.0);
      args.addProperty("gear_ratio", 1000.0);
      args.addProperty("alpha_threshold", 0.01);
      args.addProperty("smooth_window", 0);

      var result = tool.execute(args).getAsJsonObject();

      // The result should either:
      // 1. Succeed with extreme values (|J| > 1000 or |B| > 100) and include a warning
      // 2. Fail due to singular matrix (near-collinear data)
      // Both outcomes validate that the tool handles near-singular data appropriately.
      if (result.get("success").getAsBoolean()) {
        assertTrue(result.has("warnings"), "Should have warnings for extreme values");
        var warnings = result.getAsJsonArray("warnings");
        boolean foundExtremeWarning = false;
        for (var warn : warnings) {
          if (warn.getAsString().toLowerCase().contains("extreme")) {
            foundExtremeWarning = true;
          }
        }
        assertTrue(foundExtremeWarning,
            "Should warn about extreme values when |J| > 1000 or |B| > 100");
      } else {
        // Singular matrix or insufficient samples — both acceptable
        var error = result.get("error").getAsString();
        assertTrue(error.toLowerCase().contains("singular") || error.toLowerCase().contains("insufficient"),
            "If regression fails, it should be due to singularity or insufficient samples");
      }
    }
  }
}
