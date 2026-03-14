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
  }
}
