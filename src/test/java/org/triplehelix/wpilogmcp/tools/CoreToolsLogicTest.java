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
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

/**
 * Logic-level unit tests for CoreTools using synthetic log data.
 */
class CoreToolsLogicTest {

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
    CoreTools.registerAll(capturingServer);
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
  @DisplayName("list_entries Tool")
  class ListEntriesToolTests {
    @Test
    @DisplayName("lists all entries in active log")
    void listsAllEntries() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/core.wpilog")
          .addNumericEntry("/Drive/Speed", new double[]{0}, new double[]{0})
          .addNumericEntry("/Arm/Angle", new double[]{0}, new double[]{0})
          .build();
      setActiveLog(log);

      var tool = findTool("list_entries");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var entries = resultObj.getAsJsonArray("entries");
      assertEquals(2, entries.size());
    }
  }

  @Nested
  @DisplayName("read_entry Tool")
  class ReadEntryToolTests {
    @Test
    @DisplayName("reads values with pagination")
    void readsPagedValues() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/core.wpilog")
          .addNumericEntry("/Test/Data", new double[]{0, 1, 2, 3, 4}, new double[]{10, 20, 30, 40, 50})
          .build();
      setActiveLog(log);

      var tool = findTool("read_entry");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Data");
      args.addProperty("limit", 2);
      args.addProperty("offset", 1);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var samples = resultObj.getAsJsonArray("samples");
      assertEquals(2, samples.size());
      assertEquals(20.0, samples.get(0).getAsJsonObject().get("value").getAsDouble(), 0.001);
      assertEquals(30.0, samples.get(1).getAsJsonObject().get("value").getAsDouble(), 0.001);
    }
  }
}
