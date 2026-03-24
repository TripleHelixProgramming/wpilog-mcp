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
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

/**
 * Logic-level unit tests for FrcDomainTools using synthetic log data.
 */
class FrcDomainToolsLogicTest {

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
    FrcDomainTools.registerAll(capturingRegistry);
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

  private void putLogInCache(ParsedLog log) {
    LogManager.getInstance().testPutLog(log.path(), log);
  }

  @Nested
  @DisplayName("analyze_vision Tool")
  class AnalyzeVisionToolTests {
    @Test
    @DisplayName("calculates correct acquisition rate")
    void calculatesCorrectAcquisitionRate() throws Exception {
      var tvValues = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 10; i++) {
        tvValues.add(new TimestampedValue(i * 0.1, i < 7)); // 70% acquisition
      }

      var log = new MockLogBuilder()
          .setPath("/test/vision.wpilog")
          .addEntry("/Limelight/tv", "boolean", tvValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_vision");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var targetAcq = resultObj.getAsJsonArray("target_acquisition");
      assertEquals(1, targetAcq.size());
      assertEquals(0.7, targetAcq.get(0).getAsJsonObject().get("acquisition_rate").getAsDouble(), 0.001);
    }
  }

  @Nested
  @DisplayName("get_ds_timeline Tool")
  class GetDsTimelineToolTests {
    @Test
    @DisplayName("detects enabled/disabled transitions")
    void detectsEnabledTransitions() throws Exception {
      var values = new ArrayList<TimestampedValue>();
      values.add(new TimestampedValue(0.0, false));
      values.add(new TimestampedValue(1.0, true));
      values.add(new TimestampedValue(5.0, false));

      var log = new MockLogBuilder()
          .setPath("/test/ds.wpilog")
          .addEntry("/DriverStation/Enabled", "boolean", values)
          .build();
      putLogInCache(log);

      var tool = findTool("get_ds_timeline");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var events = resultObj.getAsJsonArray("events");

      // Should have 3 events: INITIAL (false), ENABLED, DISABLED
      assertEquals(3, events.size());
      assertEquals("ENABLED", events.get(1).getAsJsonObject().get("type").getAsString());
      assertEquals("DISABLED", events.get(2).getAsJsonObject().get("type").getAsString());
    }
  }

  @Nested
  @DisplayName("get_ds_timeline pre-match auto flag")
  class GetDsTimelinePreMatchAutoTests {
    @Test
    @DisplayName("AUTO_START deferred until robot is enabled")
    void autoStartDeferredUntilEnabled() throws Exception {
      // FMS sets Autonomous=true at t=0 (during countdown) but robot is enabled at t=3
      var enabledValues = new ArrayList<TimestampedValue>();
      enabledValues.add(new TimestampedValue(0.0, false));
      enabledValues.add(new TimestampedValue(3.0, true));
      enabledValues.add(new TimestampedValue(18.0, false));

      var autoValues = new ArrayList<TimestampedValue>();
      autoValues.add(new TimestampedValue(0.0, true));   // FMS sets auto before match
      autoValues.add(new TimestampedValue(18.0, false));  // auto ends

      var log = new MockLogBuilder()
          .setPath("/test/ds_prematch.wpilog")
          .addEntry("/DriverStation/Enabled", "boolean", enabledValues)
          .addEntry("/DriverStation/Autonomous", "boolean", autoValues)
          .build();
      putLogInCache(log);

      var tool = findTool("get_ds_timeline");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      var events = result.getAsJsonArray("events");

      // Find the AUTO_START event — should be at t=3 (enable time), not t=0
      boolean foundAutoStart = false;
      for (var e : events) {
        var ev = e.getAsJsonObject();
        if ("AUTO_START".equals(ev.get("type").getAsString())) {
          assertEquals(3.0, ev.get("timestamp").getAsDouble(), 0.01,
              "AUTO_START should be at enable time, not when FMS set auto flag");
          foundAutoStart = true;
        }
      }
      assertTrue(foundAutoStart, "Should have an AUTO_START event");
    }

    @Test
    @DisplayName("AUTO_START at correct time when auto and enabled are simultaneous")
    void autoStartWhenSimultaneous() throws Exception {
      // Normal case: Autonomous=true and Enabled=true happen at the same time
      var enabledValues = new ArrayList<TimestampedValue>();
      enabledValues.add(new TimestampedValue(0.0, true));
      enabledValues.add(new TimestampedValue(15.0, false));

      var autoValues = new ArrayList<TimestampedValue>();
      autoValues.add(new TimestampedValue(0.0, true));
      autoValues.add(new TimestampedValue(15.0, false));

      var log = new MockLogBuilder()
          .setPath("/test/ds_normal.wpilog")
          .addEntry("/DriverStation/Enabled", "boolean", enabledValues)
          .addEntry("/DriverStation/Autonomous", "boolean", autoValues)
          .build();
      putLogInCache(log);

      var tool = findTool("get_ds_timeline");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args).getAsJsonObject();

      var events = result.getAsJsonArray("events");
      boolean foundAutoStart = false;
      for (var e : events) {
        var ev = e.getAsJsonObject();
        if ("AUTO_START".equals(ev.get("type").getAsString())) {
          assertEquals(0.0, ev.get("timestamp").getAsDouble(), 0.01,
              "AUTO_START should be at t=0 when auto and enabled are simultaneous");
          foundAutoStart = true;
        }
      }
      assertTrue(foundAutoStart, "Should have an AUTO_START event");
    }
  }

  @Nested
  @DisplayName("profile_mechanism Tool")
  class ProfileMechanismToolTests {
    @Test
    @DisplayName("calculates RMSE for mechanism")
    void calculatesRmse() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/mech.wpilog")
          .addNumericEntry("/Elevator/Setpoint", new double[]{0, 1, 2}, new double[]{10, 10, 10})
          .addNumericEntry("/Elevator/Position", new double[]{0, 1, 2}, new double[]{9, 11, 10})
          .build();
      putLogInCache(log);

      var tool = findTool("profile_mechanism");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("mechanism_name", "Elevator");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("following_error"));
      // Error is 1, 1, 0. Mean squared error = (1+1+0)/3 = 0.666. RMSE = sqrt(0.666) = 0.816
      assertEquals(0.816, resultObj.getAsJsonObject("following_error").get("rmse").getAsDouble(), 0.01);
    }

    @Test
    @DisplayName("detects stall events when current exceeds threshold")
    void detectsStallEvents() throws Exception {
      // Simulate a motor stall: high current (> 30A), very low velocity (< 0.01)
      // Requires both velocity and current entries for stall detection
      var log = new MockLogBuilder()
          .setPath("/test/stall.wpilog")
          .addNumericEntry("/Elevator/Setpoint", new double[]{0, 1, 2, 3, 4, 5}, new double[]{10, 10, 10, 10, 10, 10})
          .addNumericEntry("/Elevator/Position", new double[]{0, 1, 2, 3, 4, 5}, new double[]{0, 0.01, 0.02, 0.03, 0.04, 0.05})
          .addNumericEntry("/Elevator/Velocity", new double[]{0, 1, 2, 3, 4, 5}, new double[]{0.1, 0.005, 0.005, 0.005, 0.005, 0.1})
          .addNumericEntry("/Elevator/Current", new double[]{0, 1, 2, 3, 4, 5}, new double[]{5, 35, 40, 35, 35, 5})
          .build();
      putLogInCache(log);

      var tool = findTool("profile_mechanism");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("mechanism_name", "Elevator");
      args.addProperty("stall_current_threshold", 30.0);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("stall_events"), "Should have stall_events field");
      var stallEvents = resultObj.getAsJsonArray("stall_events");
      assertTrue(stallEvents.size() > 0, "Should detect stall events when current > 30A and velocity < 0.01");
    }

    @Test
    @DisplayName("calculates settling time for step response")
    void calculatesSettlingTime() throws Exception {
      // Simulate a step response: setpoint changes, mechanism settles
      // Setpoint: 0 -> 10 at t=1
      // Position: gradually approaches 10, settling within 2% at t=3
      var log = new MockLogBuilder()
          .setPath("/test/settling.wpilog")
          .addNumericEntry("/Arm/Setpoint", new double[]{0, 1, 2, 3, 4, 5}, new double[]{0, 10, 10, 10, 10, 10})
          .addNumericEntry("/Arm/Position", new double[]{0, 1, 2, 3, 4, 5}, new double[]{0, 2, 6, 9.8, 9.9, 10})
          .build();
      putLogInCache(log);

      var tool = findTool("profile_mechanism");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("mechanism_name", "Arm");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("following_error"), "Expected following_error in response");
      var followingError = resultObj.getAsJsonObject("following_error");
      assertTrue(followingError.has("settling_time_sec"), "Expected settling_time_sec in following_error");
      // Settling time should be around 2-3 seconds (from t=1 when setpoint changes to 10, settled at t=3)
      var settlingStats = followingError.getAsJsonObject("settling_time_sec");
      double avgSettling = settlingStats.get("avg").getAsDouble();
      assertTrue(avgSettling >= 1.0 && avgSettling <= 3.0,
          "Average settling time should be ~2s, got: " + avgSettling);
    }

    @Test
    @DisplayName("calculates overshoot for step response")
    void calculatesOvershoot() throws Exception {
      // Simulate overshoot with two setpoint changes (overshoot is flushed on next setpoint change)
      // First step: setpoint 0->100, mechanism overshoots to 130 (overshoot > initial undershoot)
      // Second step: setpoint 100->200, needed to flush the first overshoot calculation
      var log = new MockLogBuilder()
          .setPath("/test/overshoot.wpilog")
          .addNumericEntry("/Shooter/Setpoint",
              new double[]{0, 1, 2, 3, 4, 5, 6, 7},
              new double[]{0, 100, 100, 100, 200, 200, 200, 200})
          .addNumericEntry("/Shooter/Position",
              new double[]{0, 1, 2, 3, 4, 5, 6, 7},
              new double[]{0, 90, 130, 100, 190, 230, 200, 200})
          .build();
      putLogInCache(log);

      var tool = findTool("profile_mechanism");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("mechanism_name", "Shooter");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("following_error"), "Expected following_error in response");
      var followingError = resultObj.getAsJsonObject("following_error");
      assertTrue(followingError.has("overshoot_percent"),
          "Expected overshoot_percent in following_error");
      // First step overshoot: (130-100)/100 * 100 = 30%
      double overshoot = followingError.get("overshoot_percent").getAsDouble();
      assertEquals(30.0, overshoot, 1.0, "Overshoot should be ~30%");
    }
  }

  @Nested
  @DisplayName("analyze_vision Tool")
  class AnalyzeVisionToolTests2 {
    @Test
    @DisplayName("detects pose jumps when distance exceeds threshold")
    void detectsPoseJumps() throws Exception {
      // Simulate Pose2d data with a jump: normal small changes, then a big jump
      var poseTvs = new ArrayList<TimestampedValue>();
      poseTvs.add(new TimestampedValue(0.0, MockLogBuilder.makePose2d(0, 0)));
      poseTvs.add(new TimestampedValue(0.1, MockLogBuilder.makePose2d(0.1, 0)));
      poseTvs.add(new TimestampedValue(0.2, MockLogBuilder.makePose2d(0.2, 0)));
      poseTvs.add(new TimestampedValue(0.3, MockLogBuilder.makePose2d(0.3, 0)));
      poseTvs.add(new TimestampedValue(0.4, MockLogBuilder.makePose2d(5.0, 0))); // 4.7m jump
      poseTvs.add(new TimestampedValue(0.5, MockLogBuilder.makePose2d(5.1, 0)));

      var log = new MockLogBuilder()
          .setPath("/test/vision_jumps.wpilog")
          .addEntry("/Vision/Pose", "struct:Pose2d", poseTvs)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_vision");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("jump_threshold", 1.0); // 1 meter threshold

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      // pose_jumps is at top-level when jumps are detected
      assertTrue(resultObj.has("pose_jumps"), "Expected pose_jumps in response");
      var jumps = resultObj.getAsJsonArray("pose_jumps");
      assertTrue(jumps.size() > 0, "Should detect pose jump exceeding 1m threshold");
      assertTrue(resultObj.get("jump_count").getAsInt() > 0, "jump_count should reflect detected jumps");
    }
  }

  @Nested
  @DisplayName("analyze_auto Tool")
  class AnalyzeAutoToolTests {
    @Test
    @DisplayName("calculates path following error RMSE")
    void calculatesPathFollowingError() throws Exception {
      // Simulate auto period with Pose2d desired and actual entries
      var enabledValues = new ArrayList<TimestampedValue>();
      enabledValues.add(new TimestampedValue(0.0, true));
      enabledValues.add(new TimestampedValue(5.0, false));

      var autoValues = new ArrayList<TimestampedValue>();
      autoValues.add(new TimestampedValue(0.0, true));
      autoValues.add(new TimestampedValue(5.0, false));

      // Create Pose2d data for desired and actual paths
      var desiredPoses = new ArrayList<TimestampedValue>();
      var actualPoses = new ArrayList<TimestampedValue>();
      for (int i = 0; i <= 5; i++) {
        desiredPoses.add(new TimestampedValue(i, MockLogBuilder.makePose2d(i, 0)));
        actualPoses.add(new TimestampedValue(i, MockLogBuilder.makePose2d(i + 0.1, 0.1)));
      }

      var log = new MockLogBuilder()
          .setPath("/test/auto_path.wpilog")
          .addEntry("/DriverStation/Enabled", "boolean", enabledValues)
          .addEntry("/DriverStation/Autonomous", "boolean", autoValues)
          .addEntry("/Auto/DesiredPose", "struct:Pose2d", desiredPoses)
          .addEntry("/Auto/ActualPose", "struct:Pose2d", actualPoses)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_auto");
      var args = new JsonObject();
      args.addProperty("path", log.path());

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("path_following_error"), "Expected path_following_error in response");
      var pathError = resultObj.getAsJsonObject("path_following_error");
      assertTrue(pathError.has("rmse_meters"), "Should calculate RMSE");
      assertTrue(pathError.has("max_error_meters"), "Should calculate max error");
      double rmse = pathError.get("rmse_meters").getAsDouble();
      assertTrue(rmse >= 0, "RMSE should be non-negative");
      assertTrue(rmse < 1.0, "RMSE should be small for near-matching paths");
    }
  }

  @Nested
  @DisplayName("analyze_cycles Tool")
  class AnalyzeCyclesToolTests {
    @Test
    @DisplayName("calculates cycle times and dead time")
    void calculatesCycleTimesAndDeadTime() throws Exception {
      // Simulate state machine: Idle -> Intake -> Shoot -> Idle -> Intake -> Shoot
      var stateValues = new ArrayList<TimestampedValue>();
      stateValues.add(new TimestampedValue(0.0, "Idle"));
      stateValues.add(new TimestampedValue(1.0, "Intake"));
      stateValues.add(new TimestampedValue(3.0, "Shoot"));
      stateValues.add(new TimestampedValue(5.0, "Idle"));
      stateValues.add(new TimestampedValue(7.0, "Intake"));
      stateValues.add(new TimestampedValue(9.0, "Shoot"));
      stateValues.add(new TimestampedValue(11.0, "Idle"));

      var log = new MockLogBuilder()
          .setPath("/test/cycles.wpilog")
          .addEntry("/Superstructure/State", "string", stateValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/Superstructure/State");
      args.addProperty("cycle_start_state", "Intake");
      args.addProperty("idle_state", "Idle");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      if (resultObj.has("cycle_times")) {
        var cycleTimes = resultObj.getAsJsonObject("cycle_times");
        assertTrue(cycleTimes.has("avg_sec"), "Should calculate average cycle time");
        assertTrue(cycleTimes.has("count"), "Should have cycle count");
      }

      if (resultObj.has("dead_time")) {
        var deadTime = resultObj.getAsJsonObject("dead_time");
        assertTrue(deadTime.has("total_sec"), "Should calculate total dead time");
        assertTrue(deadTime.has("period_count"), "Should have dead time period count");
      }
    }

    @Test
    @DisplayName("start_to_start mode with complete cycles")
    void startToStartModeCompleteCycles() throws Exception {
      // Test data: INTAKE -> SHOOT -> INTAKE -> SHOOT -> INTAKE
      var stateValues = new ArrayList<TimestampedValue>();
      stateValues.add(new TimestampedValue(1.0, "INTAKE"));
      stateValues.add(new TimestampedValue(2.0, "SHOOT"));
      stateValues.add(new TimestampedValue(5.0, "INTAKE"));
      stateValues.add(new TimestampedValue(6.0, "SHOOT"));
      stateValues.add(new TimestampedValue(10.0, "INTAKE"));

      var log = new MockLogBuilder()
          .setPath("/test/cycles_start_to_start.wpilog")
          .addEntry("/State", "string", stateValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/State");
      args.addProperty("cycle_mode", "start_to_start");
      args.addProperty("cycle_start_state", "INTAKE");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals("start_to_start", resultObj.get("cycle_mode").getAsString());

      // Should have 2 complete cycles and 1 incomplete
      var cycleTimes = resultObj.getAsJsonObject("cycle_times");
      assertEquals(2, cycleTimes.get("count").getAsInt());

      // Check cycles array includes incomplete flag
      var cycles = resultObj.getAsJsonArray("cycles");
      assertEquals(3, cycles.size()); // 2 complete + 1 incomplete

      // Last cycle should be incomplete
      var lastCycle = cycles.get(2).getAsJsonObject();
      assertTrue(lastCycle.get("incomplete").getAsBoolean());
    }

    @Test
    @DisplayName("start_to_end mode with complete cycles")
    void startToEndModeCompleteCycles() throws Exception {
      // Test data: INTAKE (start) -> TRANSFER -> SCORE (end) -> IDLE -> INTAKE -> SCORE
      var stateValues = new ArrayList<TimestampedValue>();
      stateValues.add(new TimestampedValue(0.0, "IDLE"));
      stateValues.add(new TimestampedValue(1.0, "INTAKE"));
      stateValues.add(new TimestampedValue(2.0, "TRANSFER"));
      stateValues.add(new TimestampedValue(3.0, "SCORE"));
      stateValues.add(new TimestampedValue(4.0, "IDLE"));
      stateValues.add(new TimestampedValue(5.0, "INTAKE"));
      stateValues.add(new TimestampedValue(7.0, "SCORE"));

      var log = new MockLogBuilder()
          .setPath("/test/cycles_start_to_end.wpilog")
          .addEntry("/State", "string", stateValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/State");
      args.addProperty("cycle_mode", "start_to_end");
      args.addProperty("cycle_start_state", "INTAKE");
      args.addProperty("cycle_end_state", "SCORE");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals("start_to_end", resultObj.get("cycle_mode").getAsString());

      // Should have 2 complete cycles
      var cycleTimes = resultObj.getAsJsonObject("cycle_times");
      assertEquals(2, cycleTimes.get("count").getAsInt());

      // First cycle: 1.0 to 3.0 = 2.0s
      // Second cycle: 5.0 to 7.0 = 2.0s
      assertEquals(2.0, cycleTimes.get("avg_sec").getAsDouble(), 0.01);
    }

    @Test
    @DisplayName("incomplete cycle at end of log")
    void incompleteCycleAtEnd() throws Exception {
      // Test data: INTAKE -> (log ends without completing cycle)
      var stateValues = new ArrayList<TimestampedValue>();
      stateValues.add(new TimestampedValue(0.0, "IDLE"));
      stateValues.add(new TimestampedValue(1.0, "INTAKE"));
      stateValues.add(new TimestampedValue(2.0, "TRANSFER"));

      var log = new MockLogBuilder()
          .setPath("/test/incomplete.wpilog")
          .addEntry("/State", "string", stateValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/State");
      args.addProperty("cycle_mode", "start_to_end");
      args.addProperty("cycle_start_state", "INTAKE");
      args.addProperty("cycle_end_state", "SCORE");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      // Should have 1 incomplete cycle
      var cycles = resultObj.getAsJsonArray("cycles");
      assertEquals(1, cycles.size());

      var cycle = cycles.get(0).getAsJsonObject();
      assertTrue(cycle.get("incomplete").getAsBoolean());
    }

    @Test
    @DisplayName("case insensitive matching")
    void caseInsensitiveMatching() throws Exception {
      // Test data: Mixed case states
      var stateValues = new ArrayList<TimestampedValue>();
      stateValues.add(new TimestampedValue(0.0, "idle"));
      stateValues.add(new TimestampedValue(1.0, "INTAKE"));
      stateValues.add(new TimestampedValue(2.0, "Intake"));  // Different case
      stateValues.add(new TimestampedValue(3.0, "intAKE"));  // Different case

      var log = new MockLogBuilder()
          .setPath("/test/case_insensitive.wpilog")
          .addEntry("/State", "string", stateValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/State");
      args.addProperty("cycle_mode", "start_to_start");
      args.addProperty("cycle_start_state", "intake");  // lowercase
      args.addProperty("case_sensitive", false);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      // Should detect all 3 INTAKE variants as cycles
      var cycleTimes = resultObj.getAsJsonObject("cycle_times");
      assertEquals(2, cycleTimes.get("count").getAsInt()); // 2 complete + 1 incomplete
    }

    @Test
    @DisplayName("time range filtering")
    void timeRangeFiltering() throws Exception {
      // Test data: Cycles at different times
      var stateValues = new ArrayList<TimestampedValue>();
      stateValues.add(new TimestampedValue(0.0, "INTAKE"));  // Before start_time
      stateValues.add(new TimestampedValue(5.0, "INTAKE"));  // Before start_time
      stateValues.add(new TimestampedValue(10.0, "INTAKE")); // In range
      stateValues.add(new TimestampedValue(15.0, "INTAKE")); // In range
      stateValues.add(new TimestampedValue(25.0, "INTAKE")); // After end_time

      var log = new MockLogBuilder()
          .setPath("/test/time_filter.wpilog")
          .addEntry("/State", "string", stateValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/State");
      args.addProperty("cycle_mode", "start_to_start");
      args.addProperty("cycle_start_state", "INTAKE");
      args.addProperty("start_time", 10.0);
      args.addProperty("end_time", 20.0);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      // Should only count cycle from 10.0 to 15.0
      var cycleTimes = resultObj.getAsJsonObject("cycle_times");
      assertEquals(1, cycleTimes.get("count").getAsInt());
    }

    @Test
    @DisplayName("data quality warnings for rapid transitions")
    void dataQualityWarningsRapidTransitions() throws Exception {
      // Test data: State bounces rapidly
      var stateValues = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 10; i++) {
        stateValues.add(new TimestampedValue(i * 0.05, i % 2 == 0 ? "A" : "B"));
      }

      var log = new MockLogBuilder()
          .setPath("/test/rapid.wpilog")
          .addEntry("/State", "string", stateValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/State");
      args.addProperty("cycle_mode", "start_to_start");
      args.addProperty("cycle_start_state", "A");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      // Should have warnings about rapid transitions
      assertTrue(resultObj.has("warnings"));
      var warnings = resultObj.getAsJsonArray("warnings");
      assertTrue(warnings.size() > 0);

      boolean hasRapidWarning = false;
      for (var warning : warnings) {
        if (warning.getAsString().contains("rapid state transitions")) {
          hasRapidWarning = true;
          break;
        }
      }
      assertTrue(hasRapidWarning, "Should warn about rapid state transitions");
    }

    @Test
    @DisplayName("data quality warnings for unknown states")
    void dataQualityWarningsUnknownStates() throws Exception {
      // Test data: Includes unexpected states
      var stateValues = new ArrayList<TimestampedValue>();
      stateValues.add(new TimestampedValue(0.0, "INTAKE"));
      stateValues.add(new TimestampedValue(1.0, "ERROR_STATE"));
      stateValues.add(new TimestampedValue(2.0, "UNKNOWN"));
      stateValues.add(new TimestampedValue(3.0, "INTAKE"));

      var log = new MockLogBuilder()
          .setPath("/test/unknown.wpilog")
          .addEntry("/State", "string", stateValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/State");
      args.addProperty("cycle_mode", "start_to_start");
      args.addProperty("cycle_start_state", "INTAKE");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      // Should have warnings about unknown states
      assertTrue(resultObj.has("warnings"));
      var warnings = resultObj.getAsJsonArray("warnings");
      assertTrue(warnings.size() > 0);

      boolean hasUnknownWarning = false;
      for (var warning : warnings) {
        if (warning.getAsString().contains("unknown states")) {
          hasUnknownWarning = true;
          break;
        }
      }
      assertTrue(hasUnknownWarning, "Should warn about unknown states");
    }

    @Test
    @DisplayName("configurable output limit")
    void configurableOutputLimit() throws Exception {
      // Test data: Many cycles
      var stateValues = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 20; i++) {
        stateValues.add(new TimestampedValue(i * 1.0, "INTAKE"));
      }

      var log = new MockLogBuilder()
          .setPath("/test/many_cycles.wpilog")
          .addEntry("/State", "string", stateValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/State");
      args.addProperty("cycle_mode", "start_to_start");
      args.addProperty("cycle_start_state", "INTAKE");
      args.addProperty("limit", 5);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      // Should return only 5 cycles
      var cycles = resultObj.getAsJsonArray("cycles");
      assertEquals(5, cycles.size());

      // Should indicate truncation
      assertTrue(resultObj.get("cycles_truncated").getAsBoolean());
      assertTrue(resultObj.get("total_cycles").getAsInt() > 5);
    }

    @Test
    @DisplayName("validates cycle_mode parameter")
    void validatesCycleModeParameter() throws Exception {
      var stateValues = new ArrayList<TimestampedValue>();
      stateValues.add(new TimestampedValue(0.0, "INTAKE"));

      var log = new MockLogBuilder()
          .setPath("/test/invalid.wpilog")
          .addEntry("/State", "string", stateValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/State");
      args.addProperty("cycle_mode", "invalid_mode");
      args.addProperty("cycle_start_state", "INTAKE");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("cycle_mode"));
    }

    @Test
    @DisplayName("requires cycle_end_state for start_to_end mode")
    void requiresCycleEndStateForStartToEnd() throws Exception {
      var stateValues = new ArrayList<TimestampedValue>();
      stateValues.add(new TimestampedValue(0.0, "INTAKE"));

      var log = new MockLogBuilder()
          .setPath("/test/missing_end.wpilog")
          .addEntry("/State", "string", stateValues)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/State");
      args.addProperty("cycle_mode", "start_to_end");
      args.addProperty("cycle_start_state", "INTAKE");
      // Missing cycle_end_state

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("cycle_end_state"));
    }
  }

  @Nested
  @DisplayName("analyze_loop_timing Tool")
  class AnalyzeLoopTimingToolTests {
    @Test
    @DisplayName("detects loop overruns exceeding 20ms")
    void detectsLoopOverruns() throws Exception {
      // Simulate loop timing: mostly good (< 20ms), with some overruns
      var log = new MockLogBuilder()
          .setPath("/test/loop_timing.wpilog")
          .addNumericEntry("/RobotCode/LoopTime",
              new double[]{0, 0.02, 0.04, 0.06, 0.08, 0.10},
              new double[]{15, 18, 25, 19, 30, 16}) // ms
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_loop_timing");
      var args = new JsonObject();
      args.addProperty("path", log.path());

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("statistics"), "Should have loop time statistics");
      var stats = resultObj.getAsJsonObject("statistics");
      assertTrue(stats.has("avg_ms"), "Statistics should have avg_ms");

      assertTrue(resultObj.has("violations"), "Should have violations array");
      var violations = resultObj.getAsJsonArray("violations");
      // Should detect at least the 25ms and 30ms values
      assertTrue(violations.size() >= 2, "Should detect loop violations > 20ms");
    }

    @Test
    @DisplayName("normal 20ms loop timing gives health score near 100")
    void normalLoopTimingGivesHighHealthScore() throws Exception {
      // All loop times well under 20ms threshold
      var log = new MockLogBuilder()
          .setPath("/test/normal_loop.wpilog")
          .addNumericEntry("/RobotCode/LoopTime",
              new double[]{0, 0.02, 0.04, 0.06, 0.08, 0.10, 0.12, 0.14, 0.16, 0.18},
              new double[]{18, 19, 17, 18, 19, 18, 17, 19, 18, 17}) // ms, all < 20ms
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_loop_timing");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(100, resultObj.get("health_score").getAsInt(),
          "All-normal loop timing should give health score of 100");
      assertEquals(0, resultObj.get("violation_count").getAsInt(),
          "No violations expected for normal loop timing");

      var stats = resultObj.getAsJsonObject("statistics");
      double avgMs = stats.get("avg_ms").getAsDouble();
      assertTrue(avgMs > 16 && avgMs < 20,
          "Average loop time should be between 16-20ms, got: " + avgMs);
    }

    @Test
    @DisplayName("auto-detects seconds format and converts to ms")
    void autoDetectsSecondsFormat() throws Exception {
      // Data in seconds (0.02s = 20ms) - should auto-detect and convert
      var log = new MockLogBuilder()
          .setPath("/test/loop_seconds.wpilog")
          .addNumericEntry("/RobotCode/LoopTime",
              new double[]{0, 0.02, 0.04, 0.06, 0.08, 0.10},
              new double[]{0.018, 0.019, 0.025, 0.017, 0.030, 0.016}) // seconds
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_loop_timing");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      // Auto-detect should convert seconds to ms
      var stats = resultObj.getAsJsonObject("statistics");
      double avgMs = stats.get("avg_ms").getAsDouble();
      assertTrue(avgMs > 15 && avgMs < 30,
          "Auto-detected seconds should convert to ms range (15-30), got: " + avgMs);

      // Should detect violations for 0.025s (25ms) and 0.030s (30ms)
      assertEquals(2, resultObj.get("violation_count").getAsInt(),
          "Should detect 2 violations > 20ms after conversion from seconds");
    }

    @Test
    @DisplayName("loop with overruns reports correct violation count and rate")
    void loopWithOverrunsReportsViolations() throws Exception {
      // 3 out of 8 samples exceed 20ms threshold
      var log = new MockLogBuilder()
          .setPath("/test/loop_overruns.wpilog")
          .addNumericEntry("/RobotCode/LoopTime",
              new double[]{0, 0.02, 0.04, 0.06, 0.08, 0.10, 0.12, 0.14},
              new double[]{15, 18, 25, 19, 30, 16, 22, 17}) // 25, 30, 22 are violations
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_loop_timing");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(3, resultObj.get("violation_count").getAsInt(),
          "Should detect exactly 3 violations (25ms, 30ms, 22ms)");
      assertEquals(8, resultObj.get("total_samples").getAsInt());
      assertEquals(3.0 / 8.0, resultObj.get("violation_rate").getAsDouble(), 0.001);

      // Health score should reflect 37.5% violation rate
      int healthScore = resultObj.get("health_score").getAsInt();
      assertTrue(healthScore > 50 && healthScore < 70,
          "37.5% violation rate should give score ~62, got: " + healthScore);
    }

    @Test
    @DisplayName("empty loop timing data returns error")
    void emptyLoopTimingDataReturnsError() throws Exception {
      // Log with no loop time entries
      var log = new MockLogBuilder()
          .setPath("/test/no_loop_time.wpilog")
          .addNumericEntry("/Motor/Velocity", new double[]{0, 1, 2}, new double[]{10, 20, 30})
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_loop_timing");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("loop time"),
          "Error message should mention loop time entry not found");
    }
  }

  @Nested
  @DisplayName("analyze_can_bus Tool")
  class AnalyzeCanBusToolTests {
    @Test
    @DisplayName("analyzes CAN bus utilization")
    void analyzesCanBusUtilization() throws Exception {
      // Simulate CAN bus utilization percentage
      var log = new MockLogBuilder()
          .setPath("/test/can_bus.wpilog")
          .addNumericEntry("/CANBus/Utilization",
              new double[]{0, 1, 2, 3, 4, 5},
              new double[]{20, 35, 50, 45, 60, 30})
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_can_bus");
      var args = new JsonObject();
      args.addProperty("path", log.path());

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      if (resultObj.has("utilization")) {
        var utilization = resultObj.getAsJsonArray("utilization");
        assertTrue(utilization.size() > 0, "Should have utilization analysis");

        var firstUtil = utilization.get(0).getAsJsonObject();
        assertTrue(firstUtil.has("avg_percent"), "Should have avg_percent");
        assertTrue(firstUtil.has("max_percent"), "Should have max_percent");
      }
    }

    @Test
    @DisplayName("detects CAN errors")
    void detectsCanErrors() throws Exception {
      // Simulate CAN transmit/receive errors
      var log = new MockLogBuilder()
          .setPath("/test/can_errors.wpilog")
          .addNumericEntry("/CANBus/Error/Tx", new double[]{0, 1, 2, 3}, new double[]{0, 0, 5, 5})
          .addNumericEntry("/CANBus/Error/Rx", new double[]{0, 1, 2, 3}, new double[]{0, 2, 2, 10})
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_can_bus");
      var args = new JsonObject();
      args.addProperty("path", log.path());

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      if (resultObj.has("errors")) {
        var errors = resultObj.getAsJsonArray("errors");
        assertTrue(errors.size() > 0, "Should detect CAN errors");

        for (var error : errors) {
          var errorObj = error.getAsJsonObject();
          assertTrue(errorObj.has("error_count"), "Should have error_count");
          int count = errorObj.get("error_count").getAsInt();
          assertTrue(count >= 0, "Error count should be non-negative");
        }
      }
    }
  }

  // ==================== Code Review Fix Tests ====================

  @Nested
  @DisplayName("Loop Timing Health Score (§1.1 fix)")
  class LoopTimingHealthScoreTests {

    @Test
    @DisplayName("health score is linear: 0% violations = 100, 100% violations = 0")
    void linearHealthScore() throws Exception {
      // All values are violations (> 20ms)
      var log = new MockLogBuilder()
          .setPath("/test/all_violations.wpilog")
          .addNumericEntry("/RobotCode/LoopTime",
              new double[]{0, 0.02, 0.04, 0.06, 0.08},
              new double[]{25, 30, 35, 40, 50}) // all > 20ms
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_loop_timing");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      int healthScore = resultObj.get("health_score").getAsInt();
      // 100% violation rate → score should be 0
      assertEquals(0, healthScore, "100% violations should give score 0");
    }

    @Test
    @DisplayName("50% violations gives score ~50 (not 0 as with old 200x multiplier)")
    void fiftyPercentViolationsGivesFifty() throws Exception {
      // Half good, half violations
      var log = new MockLogBuilder()
          .setPath("/test/half_violations.wpilog")
          .addNumericEntry("/RobotCode/LoopTime",
              new double[]{0, 0.02, 0.04, 0.06, 0.08, 0.10},
              new double[]{15, 25, 18, 30, 19, 35}) // 3 of 6 are violations
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_loop_timing");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      int healthScore = resultObj.get("health_score").getAsInt();
      // 50% violation rate → score should be ~50
      assertTrue(healthScore >= 40 && healthScore <= 60,
          "50% violations should give score ~50, got: " + healthScore);
    }

    @Test
    @DisplayName("no violations gives score 100")
    void noViolationsGivesHundred() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/clean_loops.wpilog")
          .addNumericEntry("/RobotCode/LoopTime",
              new double[]{0, 0.02, 0.04, 0.06, 0.08},
              new double[]{15, 18, 19, 17, 16}) // all < 20ms
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_loop_timing");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(100, resultObj.get("health_score").getAsInt());
    }
  }

  @Nested
  @DisplayName("Battery Health Scoring (§3.1 fix)")
  class BatteryHealthScoringTests {

    @Test
    @DisplayName("healthy battery under normal load gets high score")
    void healthyBatteryUnderLoad() throws Exception {
      // Average ~11.5V under load is normal — should not be penalized heavily
      var log = new MockLogBuilder()
          .setPath("/test/healthy_battery.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
              new double[]{12.5, 11.8, 11.5, 11.2, 11.5, 11.8, 12.0, 11.5, 11.3, 11.6})
          .build();
      putLogInCache(log);

      var tool = findTool("predict_battery_health");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      int score = resultObj.get("health_score").getAsInt();
      // Average ~11.7V is well above the 88% threshold (11.1V), should be healthy
      assertTrue(score >= 70,
          "Healthy battery under normal load should score >= 70, got: " + score);
    }

    @Test
    @DisplayName("battery near brownout gets low score")
    void batteryNearBrownout() throws Exception {
      // Voltage dropping near brownout territory
      var log = new MockLogBuilder()
          .setPath("/test/bad_battery.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
              new double[]{10.5, 9.5, 8.0, 7.5, 6.5, 7.0, 8.5, 9.0, 10.0, 10.5})
          .build();
      putLogInCache(log);

      var tool = findTool("predict_battery_health");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      int score = resultObj.get("health_score").getAsInt();
      assertTrue(score < 50,
          "Battery near brownout should score < 50, got: " + score);
    }

    @Test
    @DisplayName("returns health score and risk level")
    void returnsHealthScoreAndRisk() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/battery.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2, 3, 4},
              new double[]{12.5, 12.3, 12.1, 12.4, 12.2})
          .build();
      putLogInCache(log);

      var tool = findTool("predict_battery_health");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("health_score"));
      assertTrue(resultObj.has("risk_level"));
      assertTrue(resultObj.has("voltage_stats"));
      assertTrue(resultObj.has("recommendations"));
    }

    @Test
    @DisplayName("brownout hysteresis in battery health: oscillating voltage = single event")
    void batteryHealthHysteresis() throws Exception {
      // Voltage oscillates around 6.8V threshold — should count as one brownout, not many
      var tvs = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 200; i++) {
        double t = i * 0.02;
        double v;
        if (t < 1.0 || t > 3.0) {
          v = 12.0; // normal
        } else {
          // Oscillate around threshold: 6.7 → 6.85 → 6.7 → 6.85 ...
          v = 6.75 + 0.1 * Math.sin(t * 20);
        }
        tvs.add(new TimestampedValue(t, v));
      }

      var log = new MockLogBuilder()
          .setPath("/test/battery_hysteresis.wpilog")
          .addEntry("/Robot/BatteryVoltage", "double", tvs)
          .build();
      putLogInCache(log);

      var tool = findTool("predict_battery_health");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      // Hysteresis should prevent multiple brownout events from oscillation
      int brownoutEvents = resultObj.get("brownout_events").getAsInt();
      assertTrue(brownoutEvents <= 2,
          "Hysteresis should collapse oscillating voltage into few events, got: " + brownoutEvents);
    }

    @Test
    @DisplayName("health score is clamped to 0-100 range")
    void healthScoreClamped() throws Exception {
      // Extreme brownout scenario — score should be 0, not negative
      var tvs = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 100; i++) {
        tvs.add(new TimestampedValue(i * 0.02, 5.0)); // way below brownout
      }

      var log = new MockLogBuilder()
          .setPath("/test/extreme_brownout.wpilog")
          .addEntry("/Robot/BatteryVoltage", "double", tvs)
          .build();
      putLogInCache(log);

      var tool = findTool("predict_battery_health");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      int score = resultObj.get("health_score").getAsInt();
      assertTrue(score >= 0 && score <= 100, "Score should be clamped to 0-100, got: " + score);
    }

    @Test
    @DisplayName("empty voltage data returns error")
    void emptyVoltageDataReturnsError() throws Exception {
      // Log with no battery voltage entries at all
      var log = new MockLogBuilder()
          .setPath("/test/no_voltage.wpilog")
          .addNumericEntry("/Motor/Velocity", new double[]{0, 1, 2}, new double[]{10, 20, 30})
          .build();
      putLogInCache(log);

      var tool = findTool("predict_battery_health");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("voltage"),
          "Error message should mention voltage");
    }

    @Test
    @DisplayName("constant voltage (no sag) gives high health score")
    void constantVoltageGivesHighScore() throws Exception {
      // Perfectly steady voltage with no sag at all
      var log = new MockLogBuilder()
          .setPath("/test/constant_voltage.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
              new double[]{12.5, 12.5, 12.5, 12.5, 12.5, 12.5, 12.5, 12.5, 12.5, 12.5})
          .build();
      putLogInCache(log);

      var tool = findTool("predict_battery_health");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      int score = resultObj.get("health_score").getAsInt();
      assertEquals(100, score, "Constant healthy voltage should give perfect score");
      assertEquals("MINIMAL", resultObj.get("risk_level").getAsString(),
          "No sag should give MINIMAL risk level");
      assertEquals(0, resultObj.get("brownout_events").getAsInt(),
          "No brownout events expected");

      var voltageStats = resultObj.getAsJsonObject("voltage_stats");
      assertEquals(12.5, voltageStats.get("min_volts").getAsDouble(), 0.001);
      assertEquals(12.5, voltageStats.get("max_volts").getAsDouble(), 0.001);
      assertEquals(12.5, voltageStats.get("avg_volts").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("severely degraded battery with frequent drops below 8V")
    void severelyDegradedBattery() throws Exception {
      // Voltage frequently dropping below 8V
      var log = new MockLogBuilder()
          .setPath("/test/degraded_battery.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
              new double[]{10.0, 7.5, 6.0, 7.0, 6.5, 7.8, 6.2, 7.2, 6.8, 8.0})
          .build();
      putLogInCache(log);

      var tool = findTool("predict_battery_health");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      int score = resultObj.get("health_score").getAsInt();
      assertTrue(score < 30,
          "Severely degraded battery should score < 30, got: " + score);

      var voltageStats = resultObj.getAsJsonObject("voltage_stats");
      assertEquals(6.0, voltageStats.get("min_volts").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("includes data quality metadata")
    void includesDataQuality() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/battery_dq.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2, 3, 4},
              new double[]{12.5, 12.3, 12.1, 12.4, 12.2})
          .build();
      putLogInCache(log);

      var tool = findTool("predict_battery_health");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("data_quality"));
      assertTrue(resultObj.has("server_analysis_directives"));
    }
  }

  @Nested
  @DisplayName("Incomplete Cycle Warning (§3.3 fix)")
  class IncompleteCycleWarningTests {

    @Test
    @DisplayName("warns when log ends mid-cycle")
    void warnsOnIncompleteCycle() throws Exception {
      // Create a state entry that starts a cycle but never finishes it
      var values = new ArrayList<TimestampedValue>();
      values.add(new TimestampedValue(0.0, "IDLE"));
      values.add(new TimestampedValue(1.0, "INTAKING"));
      values.add(new TimestampedValue(3.0, "IDLE"));
      values.add(new TimestampedValue(4.0, "INTAKING"));
      // Log ends while INTAKING — incomplete cycle

      var log = new MockLogBuilder()
          .setPath("/test/incomplete_cycle.wpilog")
          .addEntry("/Robot/State", "string", values)
          .build();
      putLogInCache(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("state_entry", "/Robot/State");
      args.addProperty("cycle_start_state", "INTAKING");
      args.addProperty("cycle_mode", "start_to_start");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("warnings"), "Should have warnings");
      var warnings = resultObj.getAsJsonArray("warnings");
      boolean foundIncompleteWarning = false;
      for (int i = 0; i < warnings.size(); i++) {
        if (warnings.get(i).getAsString().contains("incomplete")) {
          foundIncompleteWarning = true;
        }
      }
      assertTrue(foundIncompleteWarning,
          "Should warn about incomplete cycle(s)");
    }
  }

  // ==================== Brownout Hysteresis Tests (§2.3 fix) ====================

  @Nested
  @DisplayName("Brownout Hysteresis")
  class BrownoutHysteresisTests {

    @Test
    @DisplayName("voltage oscillating around threshold counts as one brownout, not many")
    void hysteresisPreventsOscillationInflation() throws Exception {
      // Simulate voltage oscillating around 6.8V threshold:
      // 6.7 → 6.85 → 6.7 → 6.85 → 6.7 → 7.5 (recovery)
      // Without hysteresis: 3 brownout events
      // With hysteresis (exit at threshold + 0.2 = 7.0V): 1 brownout event
      var values = new ArrayList<TimestampedValue>();
      values.add(new TimestampedValue(0.0, 12.0));
      values.add(new TimestampedValue(1.0, 6.7));  // below 6.8 → brownout start
      values.add(new TimestampedValue(2.0, 6.85)); // above 6.8 but below 7.0 → still in brownout
      values.add(new TimestampedValue(3.0, 6.7));  // back below → still in brownout
      values.add(new TimestampedValue(4.0, 6.85)); // still below 7.0
      values.add(new TimestampedValue(5.0, 7.5));  // above 7.0 → brownout end
      values.add(new TimestampedValue(6.0, 12.0));

      var log = new MockLogBuilder()
          .setPath("/test/hysteresis.wpilog")
          .addEntry("/Robot/BatteryVoltage", "double", values)
          .addEntry("/DriverStation/Enabled", "boolean",
              java.util.List.of(new TimestampedValue(0.0, true), new TimestampedValue(6.0, false)))
          .build();
      putLogInCache(log);

      var tool = findTool("get_ds_timeline");
      var args = new JsonObject();
      args.addProperty("path", log.path());
      args.addProperty("brownout_threshold", 6.8);
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var events = resultObj.getAsJsonArray("events");

      // Count BROWNOUT_START events
      int brownoutStarts = 0;
      for (int i = 0; i < events.size(); i++) {
        if ("BROWNOUT_START".equals(events.get(i).getAsJsonObject().get("type").getAsString())) {
          brownoutStarts++;
        }
      }
      assertEquals(1, brownoutStarts,
          "Hysteresis should collapse oscillating voltage into 1 brownout event, got: " + brownoutStarts);
    }
  }
}
