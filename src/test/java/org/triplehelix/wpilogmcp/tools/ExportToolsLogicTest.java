package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

class ExportToolsLogicTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    ExportTools.registerAll(registry);
  }

  @Nested
  @DisplayName("generate_report Brownout Threshold")
  class GenerateReportBrownoutTests {

    @Test
    @DisplayName("voltage 6.9V is NOT high brownout risk (above 6.8V threshold)")
    void testBrownoutNotHighAt6_9V() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/brownout_safe.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2, 3, 4},
              new double[]{12.0, 11.5, 6.9, 10.0, 12.0})
          .build();
      putLogInCache(log);

      var tool = findTool("generate_report");
      var args = new JsonObject();
      args.addProperty("path", "/test/brownout_safe.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("battery"), "Should have battery section");
      var battery = resultObj.getAsJsonObject("battery");
      assertNotEquals("HIGH", battery.get("brownout_risk").getAsString(),
          "6.9V min should NOT be HIGH brownout risk (threshold is 6.8V)");
    }

    @Test
    @DisplayName("voltage 6.7V IS high brownout risk (below 6.8V threshold)")
    void testBrownoutHighAt6_7V() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/brownout_danger.wpilog")
          .addNumericEntry("/Robot/BatteryVoltage",
              new double[]{0, 1, 2, 3, 4},
              new double[]{12.0, 11.5, 6.7, 10.0, 12.0})
          .build();
      putLogInCache(log);

      var tool = findTool("generate_report");
      var args = new JsonObject();
      args.addProperty("path", "/test/brownout_danger.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("battery"), "Should have battery section");
      var battery = resultObj.getAsJsonObject("battery");
      assertEquals("HIGH", battery.get("brownout_risk").getAsString(),
          "6.7V min should be HIGH brownout risk (below 6.8V threshold)");
    }
  }

  @Test
  @DisplayName("generate_report returns report")
  void generateReport() throws Exception {
    var log = new MockLogBuilder()
        .setPath("/test/export.wpilog")
        .addNumericEntry("/Robot/BatteryVoltage", new double[]{0}, new double[]{12.5})
        .build();
    putLogInCache(log);

    var tool = findTool("generate_report");
    var args = new JsonObject();
    args.addProperty("path", "/test/export.wpilog");
    var result = tool.execute(args);
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
      putLogInCache(log);

      var outputPath = Path.of(System.getProperty("java.io.tmpdir"), "wpilog-export", "test_export_" + System.nanoTime() + ".csv");
      try {
        var tool = findTool("export_csv");
        var args = new JsonObject();
        args.addProperty("path", "/test/export_csv.wpilog");
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
      putLogInCache(log);

      var tool = findTool("export_csv");
      var args = new JsonObject();
      args.addProperty("path", "/test/export_csv.wpilog");
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
      putLogInCache(log);

      var outputPath = Path.of(System.getProperty("java.io.tmpdir"), "wpilog-export", "test_export_" + System.nanoTime() + ".csv");
      var tool = findTool("export_csv");
      var args = new JsonObject();
      args.addProperty("path", "/test/export_csv.wpilog");
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
      putLogInCache(log);

      var outputPath = Path.of(System.getProperty("java.io.tmpdir"), "wpilog-export", "test_export_filtered_" + System.nanoTime() + ".csv");
      try {
        var tool = findTool("export_csv");
        var args = new JsonObject();
        args.addProperty("path", "/test/export_csv.wpilog");
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

    @Test
    @DisplayName("exports double[] array entries with indexed rows")
    void exportsDoubleArrayEntries() throws Exception {
      var tvs = List.of(
          new TimestampedValue(0.0, new double[]{1.1, 2.2, 3.3}),
          new TimestampedValue(1.0, new double[]{4.4, 5.5, 6.6}));
      var log = new MockLogBuilder()
          .setPath("/test/export_csv.wpilog")
          .addEntry("/Swerve/DrivePositions", "double[]", tvs)
          .build();
      putLogInCache(log);

      var outputPath = Path.of(System.getProperty("java.io.tmpdir"), "wpilog-export", "test_export_darr_" + System.nanoTime() + ".csv");
      try {
        var tool = findTool("export_csv");
        var args = new JsonObject();
        args.addProperty("path", "/test/export_csv.wpilog");
        args.addProperty("name", "/Swerve/DrivePositions");
        args.addProperty("output_path", outputPath.toString());

        var result = tool.execute(args);
        var resultObj = result.getAsJsonObject();

        assertTrue(resultObj.get("success").getAsBoolean());
        assertEquals(6, resultObj.get("rows_exported").getAsInt(), "2 timestamps × 3 elements = 6 rows");

        var lines = Files.readAllLines(outputPath);
        assertEquals(7, lines.size(), "Should have 1 header + 6 data lines");
        assertEquals("timestamp_sec,index,value", lines.get(0), "Header should be indexed");
        assertTrue(lines.get(1).contains("0.0,0,1.1"), "First element should have actual numeric value");
        assertTrue(lines.get(2).contains("0.0,1,2.2"), "Second element should have actual numeric value");
        // Verify no Java object reference strings like [D@...
        for (int i = 1; i < lines.size(); i++) {
          assertFalse(lines.get(i).contains("[D@"), "Should not contain Java array toString: " + lines.get(i));
          assertFalse(lines.get(i).contains("[I@"), "Should not contain Java array toString: " + lines.get(i));
        }
      } finally {
        Files.deleteIfExists(outputPath);
      }
    }

    @Test
    @DisplayName("rejects symlink in export directory")
    void rejectsSymlinkInExportDir(@TempDir Path exportDir) throws Exception {
      assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"),
          "Symlink tests require Unix-like OS");

      // Point export directory to our temp dir
      var savedExportDir = ExportTools.getExportDirectory();
      ExportTools.setExportDirectory(exportDir.toString());
      try {
        // Create a symlink inside the export dir pointing to /tmp/outside
        var outsideDir = Files.createTempDirectory("outside");
        var symlinkPath = exportDir.resolve("escape_link");
        Files.createSymbolicLink(symlinkPath, outsideDir);

        var log = new MockLogBuilder()
            .setPath("/test/export_csv.wpilog")
            .addNumericEntry("/Test/Values", new double[]{0}, new double[]{10.0})
            .build();
        putLogInCache(log);

        var tool = findTool("export_csv");
        var args = new JsonObject();
        args.addProperty("path", "/test/export_csv.wpilog");
        args.addProperty("name", "/Test/Values");
        args.addProperty("output_path", symlinkPath.resolve("evil.csv").toString());

        var result = tool.execute(args);
        var resultObj = result.getAsJsonObject();

        assertFalse(resultObj.get("success").getAsBoolean(),
            "Should reject export through symlink that escapes export dir");
        assertTrue(resultObj.get("error").getAsString().contains("not allowed"));
      } finally {
        ExportTools.setExportDirectory(savedExportDir.toString());
      }
    }

    @Test
    @DisplayName("rejects dangling symlink file in export directory")
    void rejectsNonExistentSymlinkFile(@TempDir Path exportDir) throws Exception {
      assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"),
          "Symlink tests require Unix-like OS");

      var savedExportDir = ExportTools.getExportDirectory();
      ExportTools.setExportDirectory(exportDir.toString());
      try {
        // Create a dangling symlink (target does not exist)
        var danglingTarget = Path.of("/tmp/nonexistent_target_" + System.nanoTime());
        var symlinkPath = exportDir.resolve("dangling.csv");
        Files.createSymbolicLink(symlinkPath, danglingTarget);

        var log = new MockLogBuilder()
            .setPath("/test/export_csv.wpilog")
            .addNumericEntry("/Test/Values", new double[]{0}, new double[]{10.0})
            .build();
        putLogInCache(log);

        var tool = findTool("export_csv");
        var args = new JsonObject();
        args.addProperty("path", "/test/export_csv.wpilog");
        args.addProperty("name", "/Test/Values");
        args.addProperty("output_path", symlinkPath.toString());

        var result = tool.execute(args);
        var resultObj = result.getAsJsonObject();

        assertFalse(resultObj.get("success").getAsBoolean(),
            "Should reject dangling symlink file");
        assertTrue(resultObj.get("error").getAsString().contains("not allowed"));
      } finally {
        ExportTools.setExportDirectory(savedExportDir.toString());
      }
    }

    @Test
    @DisplayName("allows normal file in export directory")
    void allowsNormalFileInExportDir(@TempDir Path exportDir) throws Exception {
      var savedExportDir = ExportTools.getExportDirectory();
      ExportTools.setExportDirectory(exportDir.toString());
      try {
        var log = new MockLogBuilder()
            .setPath("/test/export_csv.wpilog")
            .addNumericEntry("/Test/Values",
                new double[]{0.0, 1.0},
                new double[]{10.0, 20.0})
            .build();
        putLogInCache(log);

        var outputPath = exportDir.resolve("normal_output.csv");
        var tool = findTool("export_csv");
        var args = new JsonObject();
        args.addProperty("path", "/test/export_csv.wpilog");
        args.addProperty("name", "/Test/Values");
        args.addProperty("output_path", outputPath.toString());

        var result = tool.execute(args);
        var resultObj = result.getAsJsonObject();

        assertTrue(resultObj.get("success").getAsBoolean(),
            "Should accept normal file in export dir");
        assertEquals(2, resultObj.get("rows_exported").getAsInt());
        assertTrue(Files.exists(outputPath), "CSV file should be created");
      } finally {
        ExportTools.setExportDirectory(savedExportDir.toString());
        Files.deleteIfExists(exportDir.resolve("normal_output.csv"));
      }
    }

    @Test
    @DisplayName("struct Map entries have deterministic alphabetically sorted column order")
    void testCsvExportStructDeterministicColumnOrder() throws Exception {
      // Create a Map with keys in non-alphabetical insertion order: z, a, m
      var maps = List.of(
          java.util.Map.of("z", (Object) 30.0, "a", (Object) 10.0, "m", (Object) 20.0),
          java.util.Map.of("z", (Object) 60.0, "a", (Object) 40.0, "m", (Object) 50.0));
      var log = new MockLogBuilder()
          .setPath("/test/export_csv.wpilog")
          .addStructEntry("/Test/Struct", "struct:TestStruct",
              new double[]{0.0, 1.0}, maps)
          .build();
      putLogInCache(log);

      var outputPath = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
          "wpilog-export", "test_export_struct_order_" + System.nanoTime() + ".csv");
      try {
        var tool = findTool("export_csv");
        var args = new JsonObject();
        args.addProperty("path", "/test/export_csv.wpilog");
        args.addProperty("name", "/Test/Struct");
        args.addProperty("output_path", outputPath.toString());

        var result = tool.execute(args);
        var resultObj = result.getAsJsonObject();

        assertTrue(resultObj.get("success").getAsBoolean());
        assertEquals(2, resultObj.get("rows_exported").getAsInt());

        var lines = java.nio.file.Files.readAllLines(outputPath);
        assertEquals(3, lines.size(), "Should have 1 header + 2 data rows");

        // Data rows should have values in alphabetically sorted key order: a, m, z
        // Format: timestamp,a_value,m_value,z_value
        var row1Parts = lines.get(1).split(",");
        assertEquals(4, row1Parts.length, "Row should have timestamp + 3 value columns");
        assertEquals("10.0", row1Parts[1], "First value column should be 'a' (alphabetically first)");
        assertEquals("20.0", row1Parts[2], "Second value column should be 'm'");
        assertEquals("30.0", row1Parts[3], "Third value column should be 'z' (alphabetically last)");

        var row2Parts = lines.get(2).split(",");
        assertEquals("40.0", row2Parts[1], "Second row: 'a' column");
        assertEquals("50.0", row2Parts[2], "Second row: 'm' column");
        assertEquals("60.0", row2Parts[3], "Second row: 'z' column");
      } finally {
        java.nio.file.Files.deleteIfExists(outputPath);
      }
    }

    @Test
    @DisplayName("exports long[] and boolean[] array entries correctly")
    void exportsOtherPrimitiveArrayEntries() throws Exception {
      var longTvs = List.of(new TimestampedValue(0.0, new long[]{100L, 200L}));
      var boolTvs = List.of(new TimestampedValue(0.0, new boolean[]{true, false, true}));
      var log = new MockLogBuilder()
          .setPath("/test/export_csv.wpilog")
          .addEntry("/Test/LongArray", "int64[]", longTvs)
          .addEntry("/Test/BoolArray", "boolean[]", boolTvs)
          .build();
      putLogInCache(log);

      // Test long[]
      var outputPath = Path.of(System.getProperty("java.io.tmpdir"), "wpilog-export", "test_export_larr_" + System.nanoTime() + ".csv");
      try {
        var tool = findTool("export_csv");
        var args = new JsonObject();
        args.addProperty("path", "/test/export_csv.wpilog");
        args.addProperty("name", "/Test/LongArray");
        args.addProperty("output_path", outputPath.toString());

        var result = tool.execute(args);
        var resultObj = result.getAsJsonObject();

        assertTrue(resultObj.get("success").getAsBoolean());
        assertEquals(2, resultObj.get("rows_exported").getAsInt());

        var lines = Files.readAllLines(outputPath);
        assertEquals("timestamp_sec,index,value", lines.get(0));
        assertTrue(lines.get(1).contains("0.0,0,100"), "Long value should be numeric");
      } finally {
        Files.deleteIfExists(outputPath);
      }

      // Test boolean[]
      var outputPath2 = Path.of(System.getProperty("java.io.tmpdir"), "wpilog-export", "test_export_barr_" + System.nanoTime() + ".csv");
      try {
        var tool = findTool("export_csv");
        var args = new JsonObject();
        args.addProperty("path", "/test/export_csv.wpilog");
        args.addProperty("name", "/Test/BoolArray");
        args.addProperty("output_path", outputPath2.toString());

        var result = tool.execute(args);
        var resultObj = result.getAsJsonObject();

        assertTrue(resultObj.get("success").getAsBoolean());
        assertEquals(3, resultObj.get("rows_exported").getAsInt());

        var lines = Files.readAllLines(outputPath2);
        assertTrue(lines.get(1).contains("0.0,0,true"), "Boolean value should be 'true'");
        assertTrue(lines.get(2).contains("0.0,1,false"), "Boolean value should be 'false'");
      } finally {
        Files.deleteIfExists(outputPath2);
      }
    }
  }
}
