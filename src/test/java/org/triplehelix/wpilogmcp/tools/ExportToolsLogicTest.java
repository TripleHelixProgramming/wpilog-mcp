package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

class ExportToolsLogicTest {

  private List<Tool> tools;

  @BeforeEach
  void setUp() {
    tools = new ArrayList<>();
    var capturingRegistry = new ToolRegistry() {
      @Override
      public void registerTool(Tool tool) {
        tools.add(tool);
        super.registerTool(tool);
      }
    };
    ExportTools.registerAll(capturingRegistry);
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

  @Nested
  @DisplayName("export_csv Tool")
  class ExportCsvToolTests {

    @Test
    @DisplayName("exports scalar data to CSV in /tmp")
    void exportsScalarDataToCsv() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/export_csv.wpilog")
          .addNumericEntry("/Test/Values",
              new double[]{0.0, 1.0, 2.0, 3.0, 4.0},
              new double[]{10.5, 20.3, 30.1, 40.7, 50.2})
          .build();
      setActiveLog(log);

      var outputPath = Path.of(System.getProperty("java.io.tmpdir"), "test_export_" + System.nanoTime() + ".csv");
      try {
        var tool = findTool("export_csv");
        var args = new JsonObject();
        args.addProperty("name", "/Test/Values");
        args.addProperty("output_path", outputPath.toString());

        var result = tool.execute(args);
        var resultObj = result.getAsJsonObject();

        assertTrue(resultObj.get("success").getAsBoolean());
        assertEquals(5, resultObj.get("rows_exported").getAsInt());
        assertEquals("/Test/Values", resultObj.get("entry").getAsString());

        // Verify file was actually created with correct content
        assertTrue(Files.exists(outputPath), "CSV file should exist");
        var lines = Files.readAllLines(outputPath);
        assertEquals(6, lines.size(), "Should have 1 header + 5 data lines");
        assertEquals("timestamp_sec,value", lines.get(0), "Header should be correct");
        assertTrue(lines.get(1).startsWith("0.0,10.5"), "First data row should match");
      } finally {
        Files.deleteIfExists(outputPath);
      }
    }

    @Test
    @DisplayName("rejects path outside allowed directories")
    void rejectsDisallowedPath() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/export_csv.wpilog")
          .addNumericEntry("/Test/Values", new double[]{0}, new double[]{10.0})
          .build();
      setActiveLog(log);

      var tool = findTool("export_csv");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Values");
      args.addProperty("output_path", "/etc/evil_output.csv");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("not allowed"),
          "Error should indicate path is not allowed");
    }

    @Test
    @DisplayName("returns error when entry not found")
    void returnsErrorWhenEntryNotFound() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/export_csv.wpilog")
          .addNumericEntry("/Test/Values", new double[]{0}, new double[]{10.0})
          .build();
      setActiveLog(log);

      var outputPath = Path.of(System.getProperty("java.io.tmpdir"), "test_export_" + System.nanoTime() + ".csv");
      var tool = findTool("export_csv");
      var args = new JsonObject();
      args.addProperty("name", "/NonExistent/Entry");
      args.addProperty("output_path", outputPath.toString());

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("Entry not found"),
          "Error should indicate entry was not found");
    }

    @Test
    @DisplayName("time range filtering exports only matching rows")
    void timeRangeFilteringExportsMatchingRows() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/export_csv.wpilog")
          .addNumericEntry("/Test/Values",
              new double[]{0.0, 1.0, 2.0, 3.0, 4.0},
              new double[]{10.0, 20.0, 30.0, 40.0, 50.0})
          .build();
      setActiveLog(log);

      var outputPath = Path.of(System.getProperty("java.io.tmpdir"), "test_export_filtered_" + System.nanoTime() + ".csv");
      try {
        var tool = findTool("export_csv");
        var args = new JsonObject();
        args.addProperty("name", "/Test/Values");
        args.addProperty("output_path", outputPath.toString());
        args.addProperty("start_time", 1.0);
        args.addProperty("end_time", 3.0);

        var result = tool.execute(args);
        var resultObj = result.getAsJsonObject();

        assertTrue(resultObj.get("success").getAsBoolean());
        assertEquals(3, resultObj.get("rows_exported").getAsInt(),
            "Should export only rows within time range [1.0, 3.0]");

        var lines = Files.readAllLines(outputPath);
        assertEquals(4, lines.size(), "Should have 1 header + 3 data lines");
      } finally {
        Files.deleteIfExists(outputPath);
      }
    }
  }
}
