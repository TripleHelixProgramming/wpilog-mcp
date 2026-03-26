package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

/**
 * Basic tests for WPILOG analysis tools.
 */
class WpilogToolsTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    WpilogTools.registerAll(registry);
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
              "list_entries",
              "get_entry_info",
              "read_entry",
              "search_entries",
              "search_strings",
              "get_types",
              "get_statistics",
              "find_condition",
              "list_loaded_logs",
              "compare_entries");

      var actualTools = tools.stream().map(Tool::name).toList();

      for (var expected : expectedTools) {
        assertTrue(actualTools.contains(expected), "Missing tool: " + expected);
      }
    }
  }

  // load_log, unload_log, unload_all_logs, set_active_log tests removed
  // — lifecycle tools removed in path-per-call refactor

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

}
