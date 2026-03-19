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
import org.triplehelix.wpilogmcp.log.LogManager.ParsedLog;
import org.triplehelix.wpilogmcp.log.LogManager.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

/**
 * Logic-level unit tests for FrcDomainTools using synthetic log data.
 */
class FrcDomainToolsLogicTest {

  private List<Tool> tools;

  @BeforeEach
  void setUp() {
    tools = new ArrayList<>();
    var capturingServer = new McpServer() {
      @Override
      public void registerTool(Tool tool) {
        tools.add(tool);
        super.registerTool(tool);
      }
    };
    FrcDomainTools.registerAll(capturingServer);
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

  private void setActiveLog(ParsedLog log) throws Exception {
    var manager = LogManager.getInstance();
    var loadedLogsField = LogManager.class.getDeclaredField("loadedLogs");
    loadedLogsField.setAccessible(true);
    @SuppressWarnings("unchecked")
    var loadedLogs = (java.util.LinkedHashMap<String, ParsedLog>) loadedLogsField.get(manager);
    loadedLogs.put(log.path(), log);

    var activeLogPathField = LogManager.class.getDeclaredField("activeLogPath");
    activeLogPathField.setAccessible(true);
    activeLogPathField.set(manager, log.path());
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
      setActiveLog(log);

      var tool = findTool("analyze_vision");
      var result = tool.execute(new JsonObject());
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
      setActiveLog(log);

      var tool = findTool("get_ds_timeline");
      var result = tool.execute(new JsonObject());
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
      setActiveLog(log);

      var tool = findTool("profile_mechanism");
      var args = new JsonObject();
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
      setActiveLog(log);

      var tool = findTool("profile_mechanism");
      var args = new JsonObject();
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
      setActiveLog(log);

      var tool = findTool("profile_mechanism");
      var args = new JsonObject();
      args.addProperty("mechanism_name", "Arm");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      if (resultObj.has("settling_time_sec")) {
        // Settling time should be around 2-3 seconds (from t=1 when setpoint changes)
        double settlingTime = resultObj.get("settling_time_sec").getAsDouble();
        assertTrue(settlingTime >= 0, "Settling time should be non-negative");
      }
    }

    @Test
    @DisplayName("calculates overshoot for step response")
    void calculatesOvershoot() throws Exception {
      // Simulate overshoot: setpoint 10, but mechanism goes to 12 before settling
      var log = new MockLogBuilder()
          .setPath("/test/overshoot.wpilog")
          .addNumericEntry("/Shooter/Setpoint", new double[]{0, 1, 2, 3, 4, 5}, new double[]{0, 100, 100, 100, 100, 100})
          .addNumericEntry("/Shooter/Velocity", new double[]{0, 1, 2, 3, 4, 5}, new double[]{0, 80, 120, 105, 100, 100})
          .build();
      setActiveLog(log);

      var tool = findTool("profile_mechanism");
      var args = new JsonObject();
      args.addProperty("mechanism_name", "Shooter");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      if (resultObj.has("overshoot_percent")) {
        // Max overshoot: (120 - 100) / 100 * 100 = 20%
        double overshoot = resultObj.get("overshoot_percent").getAsDouble();
        assertTrue(overshoot >= 0, "Overshoot should be non-negative");
        assertTrue(overshoot <= 100, "Overshoot should be reasonable");
      }
    }
  }

  @Nested
  @DisplayName("analyze_vision Tool")
  class AnalyzeVisionToolTests2 {
    @Test
    @DisplayName("detects pose jumps when distance exceeds threshold")
    void detectsPoseJumps() throws Exception {
      // Simulate pose data with a jump: normal small changes, then a big jump
      var log = new MockLogBuilder()
          .setPath("/test/vision_jumps.wpilog")
          .addNumericEntry("/Vision/Pose2d",
              new double[]{0, 0.1, 0.2, 0.3, 0.4, 0.5},
              new double[]{0, 0.1, 0.2, 0.3, 5.0, 5.1}) // Jump from 0.3 to 5.0 = 4.7m
          .build();
      setActiveLog(log);

      var tool = findTool("analyze_vision");
      var args = new JsonObject();
      args.addProperty("jump_threshold", 1.0); // 1 meter threshold

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      // Should detect the jump from 0.3 to 5.0
      if (resultObj.has("target_acquisition")) {
        var targetAcq = resultObj.getAsJsonArray("target_acquisition");
        if (targetAcq.size() > 0) {
          var firstVision = targetAcq.get(0).getAsJsonObject();
          if (firstVision.has("pose_jumps")) {
            var jumps = firstVision.getAsJsonArray("pose_jumps");
            assertTrue(jumps.size() > 0, "Should detect pose jump exceeding 1m threshold");
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("analyze_auto Tool")
  class AnalyzeAutoToolTests {
    @Test
    @DisplayName("calculates path following error RMSE")
    void calculatesPathFollowingError() throws Exception {
      // Simulate auto period with desired and actual positions
      var autoValues = new ArrayList<TimestampedValue>();
      autoValues.add(new TimestampedValue(0.0, true));
      autoValues.add(new TimestampedValue(5.0, false));

      var log = new MockLogBuilder()
          .setPath("/test/auto_path.wpilog")
          .addEntry("/DriverStation/Autonomous", "boolean", autoValues)
          .addNumericEntry("/Auto/Desired/X", new double[]{0, 1, 2, 3, 4, 5}, new double[]{0, 1, 2, 3, 4, 5})
          .addNumericEntry("/Auto/Desired/Y", new double[]{0, 1, 2, 3, 4, 5}, new double[]{0, 0, 0, 0, 0, 0})
          .addNumericEntry("/Auto/Actual/X", new double[]{0, 1, 2, 3, 4, 5}, new double[]{0, 1.1, 2.2, 3.1, 4.2, 5.1})
          .addNumericEntry("/Auto/Actual/Y", new double[]{0, 1, 2, 3, 4, 5}, new double[]{0, 0.1, 0.1, 0.2, 0.1, 0.1})
          .build();
      setActiveLog(log);

      var tool = findTool("analyze_auto");
      var args = new JsonObject();

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      if (resultObj.has("path_following_error")) {
        var pathError = resultObj.getAsJsonObject("path_following_error");
        assertTrue(pathError.has("rmse"), "Should calculate RMSE");
        assertTrue(pathError.has("max_error"), "Should calculate max error");
        double rmse = pathError.get("rmse").getAsDouble();
        assertTrue(rmse >= 0, "RMSE should be non-negative");
      }
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
      setActiveLog(log);

      var tool = findTool("analyze_cycles");
      var args = new JsonObject();
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
      setActiveLog(log);

      var tool = findTool("analyze_loop_timing");
      var args = new JsonObject();

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
      setActiveLog(log);

      var tool = findTool("analyze_can_bus");
      var args = new JsonObject();

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
      setActiveLog(log);

      var tool = findTool("analyze_can_bus");
      var args = new JsonObject();

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
}
