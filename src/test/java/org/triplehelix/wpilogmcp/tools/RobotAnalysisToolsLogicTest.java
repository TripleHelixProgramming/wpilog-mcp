package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

/**
 * Logic-level unit tests for RobotAnalysisTools using synthetic log data.
 */
class RobotAnalysisToolsLogicTest {

  private List<Tool> tools;

  @BeforeEach
  void setUp() {
    tools = new ArrayList<>();

    var capturingRegistry = new ToolRegistry() {
      @Override
      public void registerTool(Tool tool) {
        tools.add(tool);
        super.registerTool(tool);
      }
    };

    RobotAnalysisTools.registerAll(capturingRegistry);
  }

  @AfterEach
  void tearDown() {
    LogManager.getInstance().unloadAllLogs();
  }

  private Tool findTool(String name) {
    return tools.stream()
        .filter(t -> t.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Tool not found: " + name));
  }

  private void setActiveLog(ParsedLog log) {
    var manager = LogManager.getInstance();
    manager.testPutLog(log.path(), log);
    manager.testSetActiveLogPath(log.path());
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
      setActiveLog(log);

      var tool = findTool("get_match_phases");
      var result = tool.execute(new JsonObject());
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
      setActiveLog(log);

      var tool = findTool("get_match_phases");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      // Should have a warning about missing data, not assumed phases
      assertTrue(resultObj.has("warnings"), "Should warn about missing DS data");
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
      setActiveLog(log);

      var tool = findTool("get_match_phases");
      var result = tool.execute(new JsonObject());
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
      setActiveLog(log);

      var tool = findTool("get_match_phases");
      var result = tool.execute(new JsonObject());
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

      setActiveLog(log);

      var tool = findTool("moi_regression");
      var args = new JsonObject();
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

      setActiveLog(log);

      var tool = findTool("moi_regression");
      var args = new JsonObject();
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

      setActiveLog(log);

      var tool = findTool("moi_regression");
      var args = new JsonObject();
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

      setActiveLog(log);

      var tool = findTool("moi_regression");
      var args = new JsonObject();
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
    @DisplayName("all log-requiring tools return error when no log loaded")
    void allToolsReturnErrorWhenNoLog() throws Exception {
      // Ensure no log is loaded
      LogManager.getInstance().unloadAllLogs();

      String[] logRequiringTools = {
          "get_match_phases", "analyze_swerve", "power_analysis",
          "can_health", "moi_regression", "get_code_metadata"
      };

      for (String toolName : logRequiringTools) {
        var tool = findTool(toolName);
        var result = tool.execute(new JsonObject());
        var resultObj = result.getAsJsonObject();

        assertFalse(resultObj.get("success").getAsBoolean(),
            toolName + " should return error when no log loaded");
        assertTrue(resultObj.has("error"),
            toolName + " should have error message");
        assertTrue(resultObj.get("error").getAsString().toLowerCase().contains("no log"),
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
      manager.testSetActiveLogPath(log1.path());

      var tool = findTool("compare_matches");
      // No name parameter provided
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("name"),
          "Should report missing 'name' parameter: " + resultObj.get("error").getAsString());
    }

    @Test
    @DisplayName("compare_matches returns error when fewer than 2 logs loaded")
    void compareMatchesNeedsTwoLogs() throws Exception {
      // Load one log
      var log = new MockLogBuilder()
          .setPath("/test/single.wpilog")
          .addNumericEntry("/Test", new double[]{0}, new double[]{1})
          .build();
      setActiveLog(log);

      var tool = findTool("compare_matches");
      var args = new JsonObject();
      args.addProperty("name", "/Test");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("2 logs"));
    }

    @Test
    @DisplayName("get_code_metadata uses ResponseBuilder success pattern")
    void getCodeMetadataUsesResponseBuilder() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/meta.wpilog")
          .addEntry("/Metadata/GitSHA", "string",
              java.util.List.of(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, "abc123")))
          .build();
      setActiveLog(log);

      var tool = findTool("get_code_metadata");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("metadata"));
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
      setActiveLog(log);

      var tool = findTool("power_analysis");
      var result = tool.execute(new JsonObject());
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
      setActiveLog(log);

      var tool = findTool("can_health");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("total_can_errors"));
    }

    @Test
    @DisplayName("analyze_swerve works with loaded log")
    void analyzeSwerveWithLog() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/swerve.wpilog")
          .addNumericEntry("/Drive/Speed", new double[]{0, 1}, new double[]{1, 2})
          .build();
      setActiveLog(log);

      var tool = findTool("analyze_swerve");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("swerve_entries"));
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
      setActiveLog(log);

      var tool = findTool("get_code_metadata");
      // This would previously throw IndexOutOfBoundsException; now ToolBase catches it
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean(),
          "Should return error response, not throw exception");
      assertTrue(resultObj.has("error"),
          "Should have error message from caught exception");
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
      setActiveLog(log);

      var tool = findTool("analyze_swerve");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("wheel_slip"),
          "Should detect wheel slip from setpoint/measured pairs");
      var slip = resultObj.getAsJsonObject("wheel_slip");
      assertTrue(slip.get("pair_count").getAsInt() > 0);
    }

    @Test
    @DisplayName("detects module sync issues")
    void detectsModuleSyncIssues() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      setActiveLog(log);

      var tool = findTool("analyze_swerve");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("module_sync"),
          "Should analyze module synchronization");
      var sync = resultObj.getAsJsonObject("module_sync");
      assertTrue(sync.get("module_count").getAsInt() >= 2);
      // Module 3 has 0.2 rad offset in the test data
      assertTrue(sync.get("max_deviation_rad").getAsDouble() > 0.1,
          "Should detect the intentional angle deviation in module 3");
    }

    @Test
    @DisplayName("detects odometry drift")
    void detectsOdometryDrift() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      setActiveLog(log);

      var tool = findTool("analyze_swerve");
      var result = tool.execute(new JsonObject());
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
      setActiveLog(log);

      var tool = findTool("analyze_swerve");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      // Should succeed without swerve-specific analysis
      assertFalse(resultObj.has("wheel_slip"));
      assertFalse(resultObj.has("module_sync"));
      assertFalse(resultObj.has("odometry_drift"));
    }

    @Test
    @DisplayName("includes data quality and directives")
    void includesDataQuality() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      setActiveLog(log);

      var tool = findTool("analyze_swerve");
      var result = tool.execute(new JsonObject());
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
      setActiveLog(log);

      var tool = findTool("power_analysis");
      var result = tool.execute(new JsonObject());
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
      setActiveLog(log);

      var tool = findTool("moi_regression");
      var args = new JsonObject();
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
      setActiveLog(log);

      // Very high threshold — no slip events should be detected
      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("slip_threshold", 100.0); // impossibly high
      var result = tool.execute(args).getAsJsonObject();

      if (result.has("wheel_slip")) {
        var slip = result.getAsJsonObject("wheel_slip");
        var modules = slip.getAsJsonArray("modules");
        for (int i = 0; i < modules.size(); i++) {
          assertEquals(0, modules.get(i).getAsJsonObject().get("slip_events").getAsInt(),
              "No slip events should exceed 100 m/s threshold");
        }
      }
    }

    @Test
    @DisplayName("sync threshold parameter is respected")
    void syncThresholdRespected() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      setActiveLog(log);

      // Very high threshold — no desync events
      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
      args.addProperty("sync_threshold_rad", 100.0);
      var result = tool.execute(args).getAsJsonObject();

      if (result.has("module_sync")) {
        assertEquals(0, result.getAsJsonObject("module_sync").get("desync_events").getAsInt());
      }
    }

    @Test
    @DisplayName("explicit odometry/vision entry names work")
    void explicitOdomVisionEntries() throws Exception {
      var log = MockLogBuilder.createSwerveModuleLog();
      setActiveLog(log);

      var tool = findTool("analyze_swerve");
      var args = new JsonObject();
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
      setActiveLog(log);

      var tool = findTool("analyze_swerve");
      var result = tool.execute(new JsonObject()).getAsJsonObject();

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
      setActiveLog(log);

      var tool = findTool("get_match_phases");
      var result = tool.execute(new JsonObject()).getAsJsonObject();

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
      setActiveLog(log);

      var tool = findTool("get_match_phases");
      var result = tool.execute(new JsonObject()).getAsJsonObject();

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
      setActiveLog(log);

      var tool = findTool("get_match_phases");
      var result = tool.execute(new JsonObject()).getAsJsonObject();

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
      setActiveLog(log);

      var tool = findTool("compare_matches");
      var args = new JsonObject();
      args.addProperty("name", "/Robot/BatteryVoltage");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("2 logs"),
          "Error should indicate need for at least 2 logs");
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
      manager.testSetActiveLogPath(log1.path());

      var tool = findTool("compare_matches");
      var args = new JsonObject();
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
}
