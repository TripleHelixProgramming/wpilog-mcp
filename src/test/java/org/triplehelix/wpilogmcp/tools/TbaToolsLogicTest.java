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

class TbaToolsLogicTest {

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
    TbaTools.registerAll(capturingServer);
  }

  private Tool findTool(String name) {
    return tools.stream()
        .filter(t -> t.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Tool not found: " + name));
  }

  @Nested
  @DisplayName("get_tba_status Tool")
  class GetTbaStatusTests {
    @Test
    @DisplayName("get_tba_status returns status")
    void getTbaStatus() throws Exception {
      var tool = findTool("get_tba_status");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("status"));
    }
  }

  @Nested
  @DisplayName("get_tba_match_data Tool")
  class GetTbaMatchDataTests {

    @Test
    @DisplayName("tool is registered")
    void toolIsRegistered() {
      var tool = findTool("get_tba_match_data");
      assertNotNull(tool);
      assertEquals("get_tba_match_data", tool.name());
    }

    @Test
    @DisplayName("has helpful description for LLMs")
    void hasHelpfulDescription() {
      var tool = findTool("get_tba_match_data");
      var desc = tool.description().toLowerCase();

      // Should mention key use cases
      assertTrue(desc.contains("score"), "Description should mention scores");
      assertTrue(desc.contains("win") || desc.contains("won"), "Description should mention win/loss");
      assertTrue(desc.contains("autonomous"), "Description should mention autonomous");

      // Should discourage guessing
      assertTrue(desc.contains("don't guess") || desc.contains("query tba directly"),
          "Description should discourage guessing match results");
    }

    @Test
    @DisplayName("schema requires year, event_code, match_type, match_number")
    void hasRequiredParameters() {
      var tool = findTool("get_tba_match_data");
      var schema = tool.inputSchema();

      assertTrue(schema.has("required"));
      var required = schema.getAsJsonArray("required");
      var requiredList = new ArrayList<String>();
      for (var r : required) {
        requiredList.add(r.getAsString());
      }

      assertTrue(requiredList.contains("year"));
      assertTrue(requiredList.contains("event_code"));
      assertTrue(requiredList.contains("match_type"));
      assertTrue(requiredList.contains("match_number"));
    }

    @Test
    @DisplayName("returns appropriate response based on TBA configuration")
    void returnsAppropriateResponse() throws Exception {
      // TBA may or may not be configured in the test environment
      var tool = findTool("get_tba_match_data");
      var args = new JsonObject();
      args.addProperty("year", 2024);
      args.addProperty("event_code", "caph");
      args.addProperty("match_type", "Qualification");
      args.addProperty("match_number", 1);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      // Two valid outcomes:
      // 1. TBA not configured: success=false with error message
      // 2. TBA configured but match not found: success=true with match_found=false
      if (resultObj.get("success").getAsBoolean()) {
        // TBA is configured, check for match_found
        assertTrue(resultObj.has("match_found"), "Should have match_found field when TBA is configured");
        // match_found can be true or false depending on whether the match exists in TBA
      } else {
        // TBA not configured
        assertTrue(resultObj.has("error"));
        var error = resultObj.get("error").getAsString().toLowerCase();
        assertTrue(error.contains("not configured") || error.contains("api key"),
            "Error should mention TBA is not configured");
      }
    }

    @Test
    @DisplayName("schema includes team_number as optional")
    void hasOptionalTeamNumber() {
      var tool = findTool("get_tba_match_data");
      var schema = tool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("team_number"));

      // team_number should NOT be in required
      var required = schema.getAsJsonArray("required");
      var requiredList = new ArrayList<String>();
      for (var r : required) {
        requiredList.add(r.getAsString());
      }
      assertFalse(requiredList.contains("team_number"),
          "team_number should be optional, not required");
    }
  }
}
