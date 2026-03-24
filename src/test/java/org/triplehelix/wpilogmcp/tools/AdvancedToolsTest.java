package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

/**
 * Tests for advanced WPILOG analysis tools.
 */
class AdvancedToolsTest {

  private List<Tool> registeredTools;

  @BeforeEach
  void setUp() {
    registeredTools = new ArrayList<>();

    // Capture registered tools
    var capturingRegistry =
        new ToolRegistry() {
          @Override
          public void registerTool(Tool tool) {
            registeredTools.add(tool);
            super.registerTool(tool);
          }
        };

    WpilogTools.registerAll(capturingRegistry);
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
    @DisplayName("registers all new tools")
    void registersAllNewTools() {
      var newTools =
          List.of(
              "detect_anomalies",
              "get_match_phases",
              "find_peaks",
              "rate_of_change",
              "time_correlate",
              "analyze_swerve",
              "power_analysis",
              "can_health",
              "compare_matches",
              "get_code_metadata",
              "export_csv",
              "generate_report");

      var actualTools = registeredTools.stream().map(Tool::name).toList();

      for (var expected : newTools) {
        assertTrue(actualTools.contains(expected), "Missing tool: " + expected);
      }
    }

    @Test
    @DisplayName("total tool count is correct")
    void totalToolCountIsCorrect() {
      // Core(8) + Query(4) + Statistics(6) + RobotAnalysis(7) + FrcDomain(9) + Export(2) + TBA(2) + RevLog(5) + Discovery(2) = 45 total
      // Core tools: list_available_logs, list_entries, get_entry_info, read_entry,
      //   list_loaded_logs, list_struct_types, health_check, get_game_info
      // (load_log, set_active_log, unload_log, unload_all_logs removed in path-per-call refactor)
      assertEquals(45, registeredTools.size());
    }
  }

  @Nested
  @DisplayName("detect_anomalies Tool")
  class DetectAnomaliesTool {

    private Tool detectAnomaliesTool;

    @BeforeEach
    void setUp() {
      detectAnomaliesTool = findTool("detect_anomalies");
    }

    @Test
    @DisplayName("has required name parameter")
    void hasRequiredNameParameter() {
      var schema = detectAnomaliesTool.inputSchema();
      var required = schema.getAsJsonArray("required");

      assertNotNull(required);
      assertTrue(required.toString().contains("name"));
    }

    @Test
    @DisplayName("has optional iqr_multiplier parameter")
    void hasOptionalIqrMultiplierParameter() {
      var schema = detectAnomaliesTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("iqr_multiplier"));
      assertEquals(1.5, properties.getAsJsonObject("iqr_multiplier").get("default").getAsDouble());
    }

    @Test
    @DisplayName("has optional spike_threshold parameter")
    void hasOptionalSpikeThresholdParameter() {
      var schema = detectAnomaliesTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("spike_threshold"));
    }

    @Test
    @DisplayName("returns error when path not provided")
    void returnsErrorWhenPathNotProvided() throws Exception {
      var args = new JsonObject();
      args.addProperty("name", "/Test/Entry");
      var result = detectAnomaliesTool.execute(args);

      assertFalse(result.getAsJsonObject().get("success").getAsBoolean());
      assertTrue(result.getAsJsonObject().get("error").getAsString().contains("path"),
          "Should report missing path parameter: " + result.getAsJsonObject().get("error").getAsString());
    }

    @Test
    @DisplayName("description mentions IQR method")
    void descriptionMentionsIqrMethod() {
      var description = detectAnomaliesTool.description();
      assertTrue(description.toLowerCase().contains("iqr"));
    }
  }

  @Nested
  @DisplayName("get_match_phases Tool")
  class GetMatchPhasesTool {

    private Tool getMatchPhasesTool;

    @BeforeEach
    void setUp() {
      getMatchPhasesTool = findTool("get_match_phases");
    }

    @Test
    @DisplayName("has path as only tool-independent required parameter")
    void hasPathRequired() {
      var schema = getMatchPhasesTool.inputSchema();
      assertTrue(schema.has("required"));
      assertTrue(schema.getAsJsonArray("required").toString().contains("path"));
    }

    @Test
    @DisplayName("returns error when path not provided")
    void returnsErrorWhenPathNotProvided() throws Exception {
      var result = getMatchPhasesTool.execute(new JsonObject());

      assertFalse(result.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("description mentions autonomous and teleop")
    void descriptionMentionsMatchPhases() {
      var description = getMatchPhasesTool.description();
      assertTrue(description.toLowerCase().contains("autonomous"));
      assertTrue(description.toLowerCase().contains("teleop"));
    }
  }

  @Nested
  @DisplayName("find_peaks Tool")
  class FindPeaksTool {

    private Tool findPeaksTool;

    @BeforeEach
    void setUp() {
      findPeaksTool = findTool("find_peaks");
    }

    @Test
    @DisplayName("has required name parameter")
    void hasRequiredNameParameter() {
      var schema = findPeaksTool.inputSchema();
      var required = schema.getAsJsonArray("required");

      assertNotNull(required);
      assertTrue(required.toString().contains("name"));
    }

    @Test
    @DisplayName("has optional type parameter for max/min/both")
    void hasOptionalTypeParameter() {
      var schema = findPeaksTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("type"));
    }

    @Test
    @DisplayName("has optional min_height_diff parameter")
    void hasOptionalMinHeightDiffParameter() {
      var schema = findPeaksTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("min_height_diff"));
    }

    @Test
    @DisplayName("returns error when path not provided")
    void returnsErrorWhenPathNotProvided() throws Exception {
      var args = new JsonObject();
      args.addProperty("name", "/Test/Entry");
      var result = findPeaksTool.execute(args);

      assertFalse(result.getAsJsonObject().get("success").getAsBoolean());
    }
  }

  @Nested
  @DisplayName("rate_of_change Tool")
  class RateOfChangeTool {

    private Tool rateOfChangeTool;

    @BeforeEach
    void setUp() {
      rateOfChangeTool = findTool("rate_of_change");
    }

    @Test
    @DisplayName("has required name parameter")
    void hasRequiredNameParameter() {
      var schema = rateOfChangeTool.inputSchema();
      var required = schema.getAsJsonArray("required");

      assertNotNull(required);
      assertTrue(required.toString().contains("name"));
    }

    @Test
    @DisplayName("has optional time range parameters")
    void hasOptionalTimeRangeParameters() {
      var schema = rateOfChangeTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("start_time"));
      assertTrue(properties.has("end_time"));
    }

    @Test
    @DisplayName("has window_size parameter with default 1")
    void hasWindowSizeParameter() {
      var schema = rateOfChangeTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("window_size"));
      assertEquals(1, properties.getAsJsonObject("window_size").get("default").getAsInt());
    }

    @Test
    @DisplayName("description mentions derivative")
    void descriptionMentionsDerivative() {
      var description = rateOfChangeTool.description();
      assertTrue(description.toLowerCase().contains("derivative") || description.toLowerCase().contains("rate"));
    }
  }

  @Nested
  @DisplayName("time_correlate Tool")
  class TimeCorrelateTool {

    private Tool timeCorrelateTool;

    @BeforeEach
    void setUp() {
      timeCorrelateTool = findTool("time_correlate");
    }

    @Test
    @DisplayName("has two required name parameters")
    void hasTwoRequiredNameParameters() {
      var schema = timeCorrelateTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");
      var required = schema.getAsJsonArray("required");

      assertTrue(properties.has("name1"));
      assertTrue(properties.has("name2"));
      assertEquals(3, required.size()); // name1, name2, path
    }

    @Test
    @DisplayName("description mentions Pearson correlation")
    void descriptionMentionsPearsonCorrelation() {
      var description = timeCorrelateTool.description();
      assertTrue(description.toLowerCase().contains("pearson") || description.toLowerCase().contains("correlation"));
    }

    @Test
    @DisplayName("returns error when path not provided")
    void returnsErrorWhenPathNotProvided() throws Exception {
      var args = new JsonObject();
      args.addProperty("name1", "/Entry1");
      args.addProperty("name2", "/Entry2");
      var result = timeCorrelateTool.execute(args);

      assertFalse(result.getAsJsonObject().get("success").getAsBoolean());
    }
  }

  @Nested
  @DisplayName("analyze_swerve Tool")
  class AnalyzeSwerveTool {

    private Tool analyzeSwerveTool;

    @BeforeEach
    void setUp() {
      analyzeSwerveTool = findTool("analyze_swerve");
    }

    @Test
    @DisplayName("has optional module_prefix parameter")
    void hasOptionalModulePrefixParameter() {
      var schema = analyzeSwerveTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("module_prefix"));
    }

    @Test
    @DisplayName("has path as only tool-independent required parameter")
    void hasPathRequired() {
      var schema = analyzeSwerveTool.inputSchema();
      assertTrue(schema.has("required"));
      assertTrue(schema.getAsJsonArray("required").toString().contains("path"));
    }

    @Test
    @DisplayName("description mentions swerve modules")
    void descriptionMentionsSwerve() {
      var description = analyzeSwerveTool.description();
      assertTrue(description.toLowerCase().contains("swerve"));
    }
  }

  @Nested
  @DisplayName("power_analysis Tool")
  class PowerAnalysisTool {

    private Tool powerAnalysisTool;

    @BeforeEach
    void setUp() {
      powerAnalysisTool = findTool("power_analysis");
    }

    @Test
    @DisplayName("has optional power_prefix parameter")
    void hasOptionalPowerPrefixParameter() {
      var schema = powerAnalysisTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("power_prefix"));
    }

    @Test
    @DisplayName("has brownout_threshold parameter with default 6.8")
    void hasBrownoutThresholdParameter() {
      var schema = powerAnalysisTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("brownout_threshold"));
      assertEquals(6.8, properties.getAsJsonObject("brownout_threshold").get("default").getAsDouble());
    }

    @Test
    @DisplayName("description mentions brownout")
    void descriptionMentionsBrownout() {
      var description = powerAnalysisTool.description();
      assertTrue(description.toLowerCase().contains("brownout") || description.toLowerCase().contains("power"));
    }
  }

  @Nested
  @DisplayName("can_health Tool")
  class CanHealthTool {

    private Tool canHealthTool;

    @BeforeEach
    void setUp() {
      canHealthTool = findTool("can_health");
    }

    @Test
    @DisplayName("has path as only tool-independent required parameter")
    void hasPathRequired() {
      var schema = canHealthTool.inputSchema();
      assertTrue(schema.has("required"));
      assertTrue(schema.getAsJsonArray("required").toString().contains("path"));
    }

    @Test
    @DisplayName("returns error when path not provided")
    void returnsErrorWhenPathNotProvided() throws Exception {
      var result = canHealthTool.execute(new JsonObject());

      assertFalse(result.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("description mentions CAN bus")
    void descriptionMentionsCan() {
      var description = canHealthTool.description();
      assertTrue(description.toLowerCase().contains("can"));
    }
  }

  @Nested
  @DisplayName("compare_matches Tool")
  class CompareMatchesTool {

    private Tool compareMatchesTool;

    @BeforeEach
    void setUp() {
      compareMatchesTool = findTool("compare_matches");
    }

    @Test
    @DisplayName("has required name parameter")
    void hasRequiredNameParameter() {
      var schema = compareMatchesTool.inputSchema();
      var required = schema.getAsJsonArray("required");

      assertNotNull(required);
      assertTrue(required.toString().contains("name"));
    }

    @Test
    @DisplayName("returns error when missing required parameters")
    void returnsErrorWhenMissingParams() throws Exception {
      var args = new JsonObject();
      args.addProperty("name", "/Test/Entry");
      // Missing path and compare_path
      var result = compareMatchesTool.execute(args);

      assertFalse(result.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("description mentions comparing across logs")
    void descriptionMentionsComparison() {
      var description = compareMatchesTool.description();
      assertTrue(description.toLowerCase().contains("compare") || description.toLowerCase().contains("multiple"));
    }
  }

  @Nested
  @DisplayName("get_code_metadata Tool")
  class GetCodeMetadataTool {

    private Tool getCodeMetadataTool;

    @BeforeEach
    void setUp() {
      getCodeMetadataTool = findTool("get_code_metadata");
    }

    @Test
    @DisplayName("has path as only tool-independent required parameter")
    void hasPathRequired() {
      var schema = getCodeMetadataTool.inputSchema();
      assertTrue(schema.has("required"));
      assertTrue(schema.getAsJsonArray("required").toString().contains("path"));
    }

    @Test
    @DisplayName("returns error when path not provided")
    void returnsErrorWhenPathNotProvided() throws Exception {
      var result = getCodeMetadataTool.execute(new JsonObject());

      assertFalse(result.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("description mentions Git SHA")
    void descriptionMentionsGitSha() {
      var description = getCodeMetadataTool.description();
      assertTrue(description.toLowerCase().contains("git") || description.toLowerCase().contains("metadata"));
    }
  }

  @Nested
  @DisplayName("export_csv Tool")
  class ExportCsvTool {

    private Tool exportCsvTool;

    @BeforeEach
    void setUp() {
      exportCsvTool = findTool("export_csv");
    }

    @Test
    @DisplayName("has required name and output_path parameters")
    void hasRequiredParameters() {
      var schema = exportCsvTool.inputSchema();
      var required = schema.getAsJsonArray("required");

      assertNotNull(required);
      assertTrue(required.toString().contains("name"));
      assertTrue(required.toString().contains("output_path"));
    }

    @Test
    @DisplayName("has optional time range parameters")
    void hasOptionalTimeRangeParameters() {
      var schema = exportCsvTool.inputSchema();
      var properties = schema.getAsJsonObject("properties");

      assertTrue(properties.has("start_time"));
      assertTrue(properties.has("end_time"));
    }

    @Test
    @DisplayName("returns error when no log loaded")
    void returnsErrorWhenNoLogLoaded(@TempDir Path tempDir) throws Exception {
      var args = new JsonObject();
      args.addProperty("name", "/Test/Entry");
      args.addProperty("output_path", tempDir.resolve("test.csv").toString());
      var result = exportCsvTool.execute(args);

      assertFalse(result.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("description mentions CSV")
    void descriptionMentionsCsv() {
      var description = exportCsvTool.description();
      assertTrue(description.toLowerCase().contains("csv"));
    }
  }

  @Nested
  @DisplayName("generate_report Tool")
  class GenerateReportTool {

    private Tool generateReportTool;

    @BeforeEach
    void setUp() {
      generateReportTool = findTool("generate_report");
    }

    @Test
    @DisplayName("has path as only tool-independent required parameter")
    void hasPathRequired() {
      var schema = generateReportTool.inputSchema();
      assertTrue(schema.has("required"));
      assertTrue(schema.getAsJsonArray("required").toString().contains("path"));
    }

    @Test
    @DisplayName("returns error when path not provided")
    void returnsErrorWhenPathNotProvided() throws Exception {
      var result = generateReportTool.execute(new JsonObject());

      assertFalse(result.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("description mentions report or summary")
    void descriptionMentionsReport() {
      var description = generateReportTool.description();
      assertTrue(description.toLowerCase().contains("report") || description.toLowerCase().contains("summary"));
    }
  }

  @Nested
  @DisplayName("Schema Validation")
  class SchemaValidation {

    @Test
    @DisplayName("all tools have valid input schemas")
    void allToolsHaveValidInputSchemas() {
      for (var tool : registeredTools) {
        var schema = tool.inputSchema();
        assertNotNull(schema, tool.name() + " has null schema");
        assertEquals("object", schema.get("type").getAsString(), tool.name() + " schema type is not 'object'");
        assertTrue(schema.has("properties"), tool.name() + " schema missing 'properties'");
      }
    }

    @Test
    @DisplayName("all tools have non-empty descriptions")
    void allToolsHaveDescriptions() {
      for (var tool : registeredTools) {
        assertNotNull(tool.description(), tool.name() + " has null description");
        assertFalse(tool.description().isEmpty(), tool.name() + " has empty description");
        assertTrue(tool.description().length() > 10, tool.name() + " description is too short");
      }
    }

    @Test
    @DisplayName("all tool names follow convention")
    void allToolNamesFollowConvention() {
      for (var tool : registeredTools) {
        var name = tool.name();
        assertTrue(name.matches("[a-z_]+"), tool.name() + " name should be lowercase with underscores");
        assertFalse(name.startsWith("_"), tool.name() + " name should not start with underscore");
        assertFalse(name.endsWith("_"), tool.name() + " name should not end with underscore");
      }
    }
  }

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandling {

    @Test
    @DisplayName("tools return consistent error format")
    void toolsReturnConsistentErrorFormat() throws Exception {
      // Test several tools that require a loaded log
      var toolsRequiringLog = List.of(
        "detect_anomalies", "find_peaks", "rate_of_change", "can_health", "generate_report"
      );

      for (var toolName : toolsRequiringLog) {
        var tool = findTool(toolName);
        var args = new JsonObject();

        // Add required args for tools that need them
        if (toolName.equals("detect_anomalies") || toolName.equals("find_peaks") || toolName.equals("rate_of_change")) {
          args.addProperty("name", "/Test/Entry");
        }

        var result = tool.execute(args);
        var resultObj = result.getAsJsonObject();

        assertTrue(resultObj.has("success"), toolName + " result missing 'success' field");
        assertFalse(resultObj.get("success").getAsBoolean(), toolName + " should fail when no log loaded");
        assertTrue(resultObj.has("error"), toolName + " result missing 'error' field");
        assertFalse(resultObj.get("error").getAsString().isEmpty(), toolName + " error message is empty");
      }
    }
  }
}
