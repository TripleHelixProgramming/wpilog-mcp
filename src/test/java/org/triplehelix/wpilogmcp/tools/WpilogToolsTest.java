package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

/**
 * Basic tests for WPILOG analysis tools.
 */
class WpilogToolsTest {

  private List<Tool> registeredTools;

  @BeforeEach
  void setUp() {
    registeredTools = new ArrayList<>();

    // Use a special McpServer that captures registered tools
    var capturingServer =
        new McpServer() {
          @Override
          public void registerTool(Tool tool) {
            registeredTools.add(tool);
            super.registerTool(tool);
          }
        };

    WpilogTools.registerAll(capturingServer);
  }

  private Tool findTool(String name) {
    return registeredTools.stream()
        .filter(t -> t.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Tool not found: " + name));
  }

  @Nested
  @DisplayName("Tool Registration")
  class ToolRegistration {

    @Test
    @DisplayName("registers all original tools")
    void registersAllOriginalTools() {
      var expectedTools =
          List.of(
              "list_available_logs",
              "load_log",
              "list_entries",
              "get_entry_info",
              "read_entry",
              "search_entries",
              "search_strings",
              "get_types",
              "get_statistics",
              "find_condition",
              "set_active_log",
              "list_loaded_logs",
              "unload_log",
              "unload_all_logs",
              "compare_entries");

      var actualTools = registeredTools.stream().map(Tool::name).toList();

      for (var expected : expectedTools) {
        assertTrue(actualTools.contains(expected), "Missing tool: " + expected);
      }
    }
  }

  @Nested
  @DisplayName("load_log Tool")
  class LoadLogTool {

    private Tool loadLogTool;

    @BeforeEach
    void setUp() {
      loadLogTool = findTool("load_log");
    }

    @Test
    @DisplayName("has required path parameter")
    void hasRequiredPathParameter() {
      var schema = loadLogTool.inputSchema();
      var required = schema.getAsJsonArray("required");

      assertNotNull(required);
      assertTrue(required.toString().contains("path"));
    }

    @Test
    @DisplayName("returns error for missing file")
    void returnsErrorForMissingFile() throws Exception {
      var args = new JsonObject();
      args.addProperty("path", "/non/existent/file.wpilog");

      var result = loadLogTool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("Failed to load") 
          || resultObj.get("error").getAsString().contains("No such file")
          || resultObj.get("error").getAsString().contains("not found"));
    }
  }

  @Nested
  @DisplayName("read_entry Tool")
  class ReadEntryTool {

    private Tool readEntryTool;

    @BeforeEach
    void setUp() {
      readEntryTool = findTool("read_entry");
    }

    @Test
    @DisplayName("has required name parameter")
    void hasRequiredNameParameter() {
      var schema = readEntryTool.inputSchema();
      var required = schema.getAsJsonArray("required");

      assertNotNull(required);
      assertTrue(required.toString().contains("name"));
    }

    @Test
    @DisplayName("has optional limit and time parameters")
    void hasOptionalParameters() {
      var schema = readEntryTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("limit"));
      assertTrue(properties.has("start_time"));
      assertTrue(properties.has("end_time"));
    }
  }

  @Nested
  @DisplayName("compare_entries Tool")
  class CompareEntriesTool {

    private Tool compareEntriesTool;

    @BeforeEach
    void setUp() {
      compareEntriesTool = findTool("compare_entries");
    }

    @Test
    @DisplayName("has required name1 and name2 parameters")
    void hasRequiredParameters() {
      var schema = compareEntriesTool.inputSchema();
      var required = schema.getAsJsonArray("required");

      assertNotNull(required);
      assertTrue(required.toString().contains("name1"));
      assertTrue(required.toString().contains("name2"));
    }
  }

  @Nested
  @DisplayName("get_statistics Tool")
  class GetStatisticsTool {

    private Tool getStatisticsTool;

    @BeforeEach
    void setUp() {
      getStatisticsTool = findTool("get_statistics");
    }

    @Test
    @DisplayName("has required name parameter")
    void hasRequiredNameParameter() {
      var schema = getStatisticsTool.inputSchema();
      var required = schema.getAsJsonArray("required");

      assertNotNull(required);
      assertTrue(required.toString().contains("name"));
    }
  }

  @Nested
  @DisplayName("get_types Tool")
  class GetTypesTool {

    @Test
    @DisplayName("returns correct tool name")
    void returnsCorrectName() {
      assertEquals("get_types", findTool("get_types").name());
    }
  }

  @Nested
  @DisplayName("list_loaded_logs Tool")
  class ListLoadedLogsTool {

    @Test
    @DisplayName("returns success even with no logs")
    void returnsSuccessWithNoLogs() throws Exception {
      var tool = findTool("list_loaded_logs");
      var result = tool.execute(new JsonObject());

      assertTrue(result.getAsJsonObject().get("success").getAsBoolean());
      assertEquals(0, result.getAsJsonObject().get("loaded_count").getAsInt());
    }
  }

  @Nested
  @DisplayName("unload_all_logs Tool")
  class UnloadAllLogsTool {

    @Test
    @DisplayName("returns unloaded count")
    void returnsUnloadedCount() throws Exception {
      var tool = findTool("unload_all_logs");
      var result = tool.execute(new JsonObject());

      assertTrue(result.getAsJsonObject().get("success").getAsBoolean());
      assertTrue(result.getAsJsonObject().has("unloaded_count"));
    }
  }
}
