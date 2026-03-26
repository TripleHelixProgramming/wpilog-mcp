package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * Tests for DiscoveryTools - tools that help LLM agents discover server capabilities.
 */
class DiscoveryToolsTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    DiscoveryTools.registerAll(registry);
  }

  @Nested
  @DisplayName("get_server_guide Tool")
  class GetServerGuideTests {

    @Test
    @DisplayName("returns comprehensive overview with no arguments")
    void returnsOverviewWithNoArgs() throws Exception {
      var tool = findTool("get_server_guide");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("overview"));
      assertTrue(resultObj.has("critical_guidance"));
      assertTrue(resultObj.has("categories"));
      assertTrue(resultObj.has("common_workflows"));

      // Check overview contains expected fields
      var overview = resultObj.getAsJsonObject("overview");
      assertEquals("wpilog-mcp", overview.get("server_name").getAsString());
      assertTrue(overview.get("total_tools").getAsInt() > 40);
    }

    @Test
    @DisplayName("includes example uses by default")
    void includesExamplesByDefault() throws Exception {
      var tool = findTool("get_server_guide");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      var categories = resultObj.getAsJsonArray("categories");
      assertTrue(categories.size() > 0);

      var firstCategory = categories.get(0).getAsJsonObject();
      var toolsArray = firstCategory.getAsJsonArray("tools");
      assertTrue(toolsArray.size() > 0);

      var firstTool = toolsArray.get(0).getAsJsonObject();
      assertTrue(firstTool.has("example_uses"));
    }

    @Test
    @DisplayName("can filter by category")
    void filtersByCategory() throws Exception {
      var tool = findTool("get_server_guide");
      var args = new JsonObject();
      args.addProperty("category", "statistics");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var categories = resultObj.getAsJsonArray("categories");
      assertEquals(1, categories.size());
      assertEquals("statistics", categories.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    @DisplayName("can disable example uses")
    void disablesExamples() throws Exception {
      var tool = findTool("get_server_guide");
      var args = new JsonObject();
      args.addProperty("include_examples", false);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      var categories = resultObj.getAsJsonArray("categories");
      var firstCategory = categories.get(0).getAsJsonObject();
      var toolsArray = firstCategory.getAsJsonArray("tools");
      var firstTool = toolsArray.get(0).getAsJsonObject();

      assertFalse(firstTool.has("example_uses"));
    }

    @Test
    @DisplayName("critical_guidance contains important anti-patterns")
    void containsCriticalGuidance() throws Exception {
      var tool = findTool("get_server_guide");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      var guidance = resultObj.getAsJsonObject("critical_guidance");
      assertTrue(guidance.has("primary_rule"));
      assertTrue(guidance.has("tba_tip"));
      assertTrue(guidance.has("statistics_tip"));
      assertTrue(guidance.has("match_phases_tip"));

      // Check that the guidance mentions key anti-patterns
      var primaryRule = guidance.get("primary_rule").getAsString();
      assertTrue(primaryRule.toLowerCase().contains("built-in"));
    }

    @Test
    @DisplayName("architecture section describes concurrency support")
    void containsArchitectureInfo() throws Exception {
      var tool = findTool("get_server_guide");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("architecture"), "Should have architecture section");
      var architecture = resultObj.getAsJsonObject("architecture");
      assertTrue(architecture.has("concurrency"));
      assertTrue(architecture.has("transports"));
      assertTrue(architecture.has("log_loading"));

      var concurrency = architecture.get("concurrency").getAsString().toLowerCase();
      assertTrue(concurrency.contains("thread-safe"),
          "Should indicate thread safety");
    }

    @Test
    @DisplayName("all categories have tools")
    void allCategoriesHaveTools() throws Exception {
      var tool = findTool("get_server_guide");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      var categories = resultObj.getAsJsonArray("categories");
      for (var cat : categories) {
        var category = cat.getAsJsonObject();
        assertTrue(category.has("tools"));
        assertTrue(category.getAsJsonArray("tools").size() > 0,
            "Category " + category.get("name") + " has no tools");
        assertTrue(category.has("anti_pattern"),
            "Category " + category.get("name") + " has no anti_pattern");
      }
    }

    @Test
    @DisplayName("includes TBA category with get_tba_match_data")
    void includesTbaCategory() throws Exception {
      var tool = findTool("get_server_guide");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      var categories = resultObj.getAsJsonArray("categories");
      boolean foundTba = false;
      for (var cat : categories) {
        var category = cat.getAsJsonObject();
        if ("tba".equals(category.get("name").getAsString())) {
          foundTba = true;
          var tools = category.getAsJsonArray("tools");
          boolean foundMatchData = false;
          for (var t : tools) {
            if ("get_tba_match_data".equals(t.getAsJsonObject().get("name").getAsString())) {
              foundMatchData = true;
            }
          }
          assertTrue(foundMatchData, "TBA category should contain get_tba_match_data tool");
        }
      }
      assertTrue(foundTba, "Should have TBA category");
    }
  }

  @Nested
  @DisplayName("suggest_tools Tool")
  class SuggestToolsTests {

    @Test
    @DisplayName("suggests power_analysis for brownout questions")
    void suggestsPowerForBrownout() throws Exception {
      var tool = findTool("suggest_tools");
      var args = new JsonObject();
      args.addProperty("task", "Why did we brownout during teleop?");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var suggestions = resultObj.getAsJsonArray("suggestions");
      assertTrue(suggestions.size() > 0);

      // power_analysis should be in the suggestions
      boolean foundPower = false;
      for (var s : suggestions) {
        if ("power_analysis".equals(s.getAsJsonObject().get("tool").getAsString())) {
          foundPower = true;
        }
      }
      assertTrue(foundPower, "Should suggest power_analysis for brownout question");
    }

    @Test
    @DisplayName("suggests get_statistics for statistics questions")
    void suggestsStatisticsForMeanQuestion() throws Exception {
      var tool = findTool("suggest_tools");
      var args = new JsonObject();
      args.addProperty("task", "What was the average battery voltage during the match?");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      var suggestions = resultObj.getAsJsonArray("suggestions");
      boolean foundStats = false;
      for (var s : suggestions) {
        if ("get_statistics".equals(s.getAsJsonObject().get("tool").getAsString())) {
          foundStats = true;
        }
      }
      assertTrue(foundStats, "Should suggest get_statistics for average/mean questions");
    }

    @Test
    @DisplayName("suggests TBA tools for score questions")
    void suggestsTbaForScoreQuestions() throws Exception {
      var tool = findTool("suggest_tools");
      var args = new JsonObject();
      args.addProperty("task", "Did we win the match? What was the score?");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      var suggestions = resultObj.getAsJsonArray("suggestions");
      boolean foundTba = false;
      for (var s : suggestions) {
        var toolName = s.getAsJsonObject().get("tool").getAsString();
        if (toolName.contains("tba") || toolName.equals("list_available_logs")) {
          foundTba = true;
        }
      }
      assertTrue(foundTba, "Should suggest TBA-related tools for score questions");
    }

    @Test
    @DisplayName("suggests analyze_cycles for cycle time questions")
    void suggestsCyclesForCycleQuestions() throws Exception {
      var tool = findTool("suggest_tools");
      var args = new JsonObject();
      args.addProperty("task", "How fast were our cycle times?");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      var suggestions = resultObj.getAsJsonArray("suggestions");
      boolean foundCycles = false;
      for (var s : suggestions) {
        if ("analyze_cycles".equals(s.getAsJsonObject().get("tool").getAsString())) {
          foundCycles = true;
        }
      }
      assertTrue(foundCycles, "Should suggest analyze_cycles for cycle time questions");
    }

    @Test
    @DisplayName("suggests analyze_swerve for swerve questions")
    void suggestsSwerveForSwerveQuestions() throws Exception {
      var tool = findTool("suggest_tools");
      var args = new JsonObject();
      args.addProperty("task", "Was our swerve drive working correctly?");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      var suggestions = resultObj.getAsJsonArray("suggestions");
      boolean foundSwerve = false;
      for (var s : suggestions) {
        if ("analyze_swerve".equals(s.getAsJsonObject().get("tool").getAsString())) {
          foundSwerve = true;
        }
      }
      assertTrue(foundSwerve, "Should suggest analyze_swerve for swerve questions");
    }

    @Test
    @DisplayName("includes anti-patterns for relevant queries")
    void includesAntiPatterns() throws Exception {
      var tool = findTool("suggest_tools");
      var args = new JsonObject();
      args.addProperty("task", "What was the correlation between voltage and current?");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("anti_patterns"));
      var antiPatterns = resultObj.getAsJsonArray("anti_patterns");
      assertTrue(antiPatterns.size() > 0);
    }

    @Test
    @DisplayName("generates suggested workflow")
    void generatesWorkflow() throws Exception {
      var tool = findTool("suggest_tools");
      var args = new JsonObject();
      args.addProperty("task", "Analyze our autonomous routine");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("suggested_workflow"));
      var workflow = resultObj.getAsJsonArray("suggested_workflow");
      assertTrue(workflow.size() > 0, "Should generate a workflow");
    }

    @Test
    @DisplayName("respects max_suggestions parameter")
    void respectsMaxSuggestions() throws Exception {
      var tool = findTool("suggest_tools");
      var args = new JsonObject();
      args.addProperty("task", "Analyze everything about the match");
      args.addProperty("max_suggestions", 3);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      var suggestions = resultObj.getAsJsonArray("suggestions");
      assertTrue(suggestions.size() <= 3, "Should respect max_suggestions");
    }

    @Test
    @DisplayName("suggests get_match_phases for auto/teleop questions")
    void suggestsMatchPhasesForPhaseQuestions() throws Exception {
      var tool = findTool("suggest_tools");
      var args = new JsonObject();
      args.addProperty("task", "When did autonomous end and teleop start?");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      var suggestions = resultObj.getAsJsonArray("suggestions");
      boolean foundMatchPhases = false;
      for (var s : suggestions) {
        if ("get_match_phases".equals(s.getAsJsonObject().get("tool").getAsString())) {
          foundMatchPhases = true;
        }
      }
      assertTrue(foundMatchPhases, "Should suggest get_match_phases for auto/teleop questions");

      // Should also have anti-pattern warning
      if (resultObj.has("anti_patterns")) {
        var antiPatterns = resultObj.getAsJsonArray("anti_patterns");
        boolean foundAntiPattern = false;
        for (var ap : antiPatterns) {
          if (ap.getAsString().toLowerCase().contains("match phases")) {
            foundAntiPattern = true;
          }
        }
        assertTrue(foundAntiPattern, "Should warn against manually parsing match phases");
      }
    }

    @Test
    @DisplayName("suggests time_correlate for correlation questions")
    void suggestsTimeCorrelateForCorrelationQuestions() throws Exception {
      var tool = findTool("suggest_tools");
      var args = new JsonObject();
      args.addProperty("task", "Is there a correlation between motor temperature and current draw?");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      var suggestions = resultObj.getAsJsonArray("suggestions");
      boolean foundCorrelate = false;
      for (var s : suggestions) {
        if ("time_correlate".equals(s.getAsJsonObject().get("tool").getAsString())) {
          foundCorrelate = true;
        }
      }
      assertTrue(foundCorrelate, "Should suggest time_correlate for correlation questions");
    }
  }

  @Nested
  @DisplayName("Tool catalog consistency")
  class ToolCatalogConsistencyTests {

    @Test
    @DisplayName("total_tools matches count of tools listed in categories")
    void testToolCountMatchesCatalogSize() throws Exception {
      var tool = findTool("get_server_guide");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      // Get total_tools from overview
      var overview = resultObj.getAsJsonObject("overview");
      int totalTools = overview.get("total_tools").getAsInt();

      // Count tools across all categories
      var categories = resultObj.getAsJsonArray("categories");
      int countedTools = 0;
      for (var cat : categories) {
        var category = cat.getAsJsonObject();
        countedTools += category.getAsJsonArray("tools").size();
      }

      assertEquals(totalTools, countedTools,
          "total_tools in overview (" + totalTools + ") should match the sum of tools across all categories (" + countedTools + ")");
    }
  }
}
