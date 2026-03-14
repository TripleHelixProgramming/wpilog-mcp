package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.LogManager.ParsedLog;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

class ExportToolsLogicTest {

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
    ExportTools.registerAll(capturingServer);
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

  @Test
  @DisplayName("generate_report returns report")
  void generateReport() throws Exception {
    var log = new MockLogBuilder()
        .setPath("/test/export.wpilog")
        .addNumericEntry("/Robot/BatteryVoltage", new double[]{0}, new double[]{12.5})
        .build();
    setActiveLog(log);

    var tool = findTool("generate_report");
    var result = tool.execute(new JsonObject());
    var resultObj = result.getAsJsonObject();

    assertTrue(resultObj.get("success").getAsBoolean());
    assertTrue(resultObj.has("basic_info"));
    assertTrue(resultObj.has("battery"));
  }
}
