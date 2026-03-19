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
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

/**
 * Logic-level unit tests for QueryTools using synthetic log data.
 */
class QueryToolsLogicTest {

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

    QueryTools.registerAll(capturingServer);
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
  @DisplayName("search_entries Tool")
  class SearchEntriesToolTests {

    @Test
    @DisplayName("finds entries matching substring")
    void findsEntriesMatchingSubstring() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/query.wpilog")
          .addNumericEntry("/Drive/FrontLeft/Speed", new double[]{0}, new double[]{0})
          .addNumericEntry("/Drive/FrontRight/Speed", new double[]{0}, new double[]{0})
          .addNumericEntry("/Elevator/Height", new double[]{0}, new double[]{0})
          .build();

      setActiveLog(log);

      var tool = findTool("search_entries");
      var args = new JsonObject();
      args.addProperty("pattern", "Drive");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(2, resultObj.get("match_count").getAsInt());
      
      var matches = resultObj.getAsJsonArray("matches");
      var matchStrings = new ArrayList<String>();
      matches.forEach(m -> matchStrings.add(m.getAsString()));
      
      assertTrue(matchStrings.contains("/Drive/FrontLeft/Speed"));
      assertTrue(matchStrings.contains("/Drive/FrontRight/Speed"));
      assertFalse(matchStrings.contains("/Elevator/Height"));
    }

    @Test
    @DisplayName("case insensitive by default")
    void caseInsensitiveByDefault() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/query.wpilog")
          .addNumericEntry("/Drive/Speed", new double[]{0}, new double[]{0})
          .build();

      setActiveLog(log);

      var tool = findTool("search_entries");
      var args = new JsonObject();
      args.addProperty("pattern", "drive"); // lowercase

      var result = tool.execute(args);
      assertTrue(result.getAsJsonObject().get("match_count").getAsInt() > 0);
    }
  }

  @Nested
  @DisplayName("list_entries Tool")
  class ListEntriesToolTests {

    @Test
    @DisplayName("lists all entries with types")
    void listsAllEntriesWithTypes() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/query.wpilog")
          .addNumericEntry("/A", new double[]{0}, new double[]{0})
          .addEntry("/B", "boolean", List.of(new TimestampedValue(0, true)))
          .build();

      setActiveLog(log);

      // CoreTools now has list_entries, not QueryTools.
      // But for this test, we care about what list_entries returns.
      // Let's verify what the tool actually returns.
      
      var capturingServer = new McpServer() {
        @Override
        public void registerTool(Tool tool) {
          if (tool.name().equals("list_entries")) {
            QueryToolsLogicTest.this.tools.add(tool);
          }
        }
      };
      CoreTools.registerAll(capturingServer);
      
      var tool = findTool("list_entries");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var entries = resultObj.getAsJsonArray("entries");
      
      boolean foundA = false;
      boolean foundB = false;
      for (var e : entries) {
        var obj = e.getAsJsonObject();
        if (obj.get("name").getAsString().equals("/A")) {
          assertEquals("double", obj.get("type").getAsString());
          foundA = true;
        } else if (obj.get("name").getAsString().equals("/B")) {
          assertEquals("boolean", obj.get("type").getAsString());
          foundB = true;
        }
      }
      assertTrue(foundA && foundB);
    }
  }
}
