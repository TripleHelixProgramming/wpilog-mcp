package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * Comprehensive edge case tests for tools migrated to ToolBase/LogRequiringTool.
 *
 * <p>Tests focus on boundary conditions, error handling, and corner cases that might
 * not be covered by standard logic tests.
 */
@DisplayName("Migrated Tools - Edge Cases")
class MigratedToolsEdgeCaseTest {

  private LogManager logManager;
  private MockLogBuilder mockLogBuilder;

  @BeforeEach
  void setUp() {
    logManager = LogManager.getInstance();
    logManager.unloadAllLogs();
    mockLogBuilder = new MockLogBuilder();
  }

  @AfterEach
  void tearDown() {
    logManager.unloadAllLogs();
  }

  @Nested
  @DisplayName("StatisticsTools Edge Cases")
  class StatisticsToolsEdgeCases {

    @Test
    @DisplayName("get_statistics: handles single data point")
    void getStatisticsHandlesSingleDataPoint() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/single", new double[]{0.0}, new double[]{42.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.GetStatisticsTool();
      var args = new JsonObject();
      args.addProperty("name", "/single");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertTrue(obj.get("success").getAsBoolean());
      assertEquals(1, obj.get("count").getAsLong());
      assertEquals(42.0, obj.get("min").getAsDouble());
      assertEquals(42.0, obj.get("max").getAsDouble());
      assertEquals(42.0, obj.get("mean").getAsDouble());
      assertEquals(42.0, obj.get("median").getAsDouble());
      assertEquals(0.0, obj.get("std_dev").getAsDouble()); // Variance is 0 for single point
    }

    @Test
    @DisplayName("get_statistics: handles two data points")
    void getStatisticsHandlesTwoDataPoints() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/pair", new double[]{0.0, 1.0}, new double[]{10.0, 20.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.GetStatisticsTool();
      var args = new JsonObject();
      args.addProperty("name", "/pair");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertTrue(obj.get("success").getAsBoolean());
      assertEquals(2, obj.get("count").getAsLong());
      assertEquals(10.0, obj.get("min").getAsDouble());
      assertEquals(20.0, obj.get("max").getAsDouble());
      assertEquals(15.0, obj.get("mean").getAsDouble());
      assertEquals(15.0, obj.get("median").getAsDouble());
      // std_dev should be calculated with Bessel's correction (n-1)
      assertTrue(obj.get("std_dev").getAsDouble() > 0);
    }

    @Test
    @DisplayName("get_statistics: handles very large dataset")
    void getStatisticsHandlesVeryLargeDataset() throws Exception {
      // Create 10,000 data points
      double[] timestamps = new double[10000];
      double[] values = new double[10000];
      for (int i = 0; i < 10000; i++) {
        timestamps[i] = i * 0.02; // 20ms intervals
        values[i] = Math.sin(i * 0.1) * 100; // Oscillating values
      }

      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/large", timestamps, values)
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.GetStatisticsTool();
      var args = new JsonObject();
      args.addProperty("name", "/large");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertTrue(obj.get("success").getAsBoolean());
      assertEquals(10000, obj.get("count").getAsLong());
      assertTrue(obj.has("min"));
      assertTrue(obj.has("max"));
      assertTrue(obj.has("mean"));
      assertTrue(obj.has("median"));
      assertTrue(obj.has("std_dev"));
    }

    @Test
    @DisplayName("get_statistics: handles identical values")
    void getStatisticsHandlesIdenticalValues() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/constant", new double[]{0, 1, 2, 3, 4}, new double[]{5.0, 5.0, 5.0, 5.0, 5.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.GetStatisticsTool();
      var args = new JsonObject();
      args.addProperty("name", "/constant");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertTrue(obj.get("success").getAsBoolean());
      assertEquals(5.0, obj.get("min").getAsDouble());
      assertEquals(5.0, obj.get("max").getAsDouble());
      assertEquals(5.0, obj.get("mean").getAsDouble());
      assertEquals(5.0, obj.get("median").getAsDouble());
      assertEquals(0.0, obj.get("std_dev").getAsDouble());
    }

    @Test
    @DisplayName("get_statistics: handles extreme values")
    void getStatisticsHandlesExtremeValues() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/extreme", new double[]{0, 1, 2}, new double[]{Double.MAX_VALUE, 0.0, Double.MIN_VALUE})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.GetStatisticsTool();
      var args = new JsonObject();
      args.addProperty("name", "/extreme");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertTrue(obj.get("success").getAsBoolean());
      assertFalse(Double.isNaN(obj.get("mean").getAsDouble()));
      assertFalse(Double.isNaN(obj.get("median").getAsDouble()));
    }

    @Test
    @DisplayName("detect_anomalies: handles fewer than 4 data points")
    void detectAnomaliesHandlesFewerThanFourPoints() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/small", new double[]{0, 1, 2}, new double[]{1.0, 2.0, 3.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.DetectAnomaliesTool();
      var args = new JsonObject();
      args.addProperty("name", "/small");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertFalse(obj.get("success").getAsBoolean());
      assertTrue(obj.get("error").getAsString().contains("Not enough data"));
    }

    @Test
    @DisplayName("find_peaks: handles monotonically increasing data")
    void findPeaksHandlesMonotonicallyIncreasingData() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/increasing", new double[]{0, 1, 2, 3, 4}, new double[]{1.0, 2.0, 3.0, 4.0, 5.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.FindPeaksTool();
      var args = new JsonObject();
      args.addProperty("name", "/increasing");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertTrue(obj.get("success").getAsBoolean());
      // Should have no peaks in monotonically increasing data
      assertEquals(0, obj.get("maxima").getAsJsonArray().size());
      assertEquals(0, obj.get("minima").getAsJsonArray().size());
    }

    @Test
    @DisplayName("time_correlate: handles zero variance edge case")
    void timeCorrelateHandlesZeroVariance() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/const1", new double[]{0, 1, 2}, new double[]{5.0, 5.0, 5.0})
          .addNumericEntry("/const2", new double[]{0, 1, 2}, new double[]{10.0, 10.0, 10.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.TimeCorrelateTool();
      var args = new JsonObject();
      args.addProperty("name1", "/const1");
      args.addProperty("name2", "/const2");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertTrue(obj.get("success").getAsBoolean());
      assertTrue(Double.isNaN(obj.get("correlation").getAsDouble()));
      assertTrue(obj.has("warnings"));
      assertTrue(obj.get("warnings").getAsJsonArray().size() > 0);
    }
  }

  @Nested
  @DisplayName("QueryTools Edge Cases")
  class QueryToolsEdgeCases {

    @Test
    @DisplayName("search_entries: handles empty log")
    void searchEntriesHandlesEmptyLog() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/empty.wpilog")
          .build(); // No entries
      logManager.testPutLog("/empty.wpilog", mockLog);
      logManager.testSetActiveLogPath("/empty.wpilog");

      var tool = new QueryTools.SearchEntriesTool();
      var args = new JsonObject();

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertTrue(obj.get("success").getAsBoolean());
      assertEquals(0, obj.get("match_count").getAsInt());
      assertEquals(0, obj.get("matches").getAsJsonArray().size());
    }

    @Test
    @DisplayName("search_entries: handles very restrictive filters")
    void searchEntriesHandlesVeryRestrictiveFilters() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry1", new double[]{0}, new double[]{1.0})
          .addNumericEntry("/entry2", new double[]{0}, new double[]{2.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new QueryTools.SearchEntriesTool();
      var args = new JsonObject();
      args.addProperty("pattern", "nonexistent");
      args.addProperty("type", "UltraRareType");
      args.addProperty("min_samples", 1000000);

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertTrue(obj.get("success").getAsBoolean());
      assertEquals(0, obj.get("match_count").getAsInt());
    }

    @Test
    @DisplayName("find_condition: handles all transitions within limit")
    void findConditionHandlesAllTransitionsWithinLimit() throws Exception {
      // Create data with exactly 5 transitions
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/voltage",
              new double[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
              new double[]{12.0, 11.0, 12.0, 11.0, 12.0, 11.0, 12.0, 11.0, 12.0, 11.0, 12.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new QueryTools.FindConditionTool();
      var args = new JsonObject();
      args.addProperty("name", "/voltage");
      args.addProperty("operator", "lt");
      args.addProperty("threshold", 11.5);
      args.addProperty("limit", 10); // Limit higher than actual count

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertTrue(obj.get("success").getAsBoolean());
      assertEquals(5, obj.get("transition_count").getAsInt());
      assertEquals(5, obj.get("transitions").getAsJsonArray().size());
    }

    @Test
    @DisplayName("search_strings: handles special characters in pattern")
    void searchStringsHandlesSpecialCharactersInPattern() throws Exception {
      var stringValues = new java.util.ArrayList<org.triplehelix.wpilogmcp.log.TimestampedValue>();
      stringValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(0.0, "Error: Invalid (parameter)"));
      stringValues.add(new org.triplehelix.wpilogmcp.log.TimestampedValue(1.0, "Warning: Check [brackets]"));

      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addEntry("/console", "string", stringValues)
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new QueryTools.SearchStringsTool();
      var args = new JsonObject();
      args.addProperty("pattern", "(parameter)");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertTrue(obj.get("success").getAsBoolean());
      assertTrue(obj.get("match_count").getAsInt() > 0);
    }
  }

  @Nested
  @DisplayName("Error Message Quality")
  class ErrorMessageQualityTests {

    @Test
    @DisplayName("provides helpful error when entry not found")
    void providesHelpfulErrorWhenEntryNotFound() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/Robot/Speed", new double[]{0}, new double[]{1.0})
          .addNumericEntry("/Robot/Voltage", new double[]{0}, new double[]{12.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.GetStatisticsTool();
      var args = new JsonObject();
      args.addProperty("name", "/Robot/Spee"); // Typo

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertFalse(obj.get("success").getAsBoolean());
      var error = obj.get("error").getAsString();
      assertTrue(error.contains("Entry not found"));
      assertTrue(error.contains("Did you mean"));
      assertTrue(error.contains("/Robot/Speed"));
    }

    @Test
    @DisplayName("provides clear error for no log loaded")
    void providesClearErrorForNoLogLoaded() throws Exception {
      // Ensure no log is loaded
      logManager.unloadAllLogs();

      var tool = new StatisticsTools.GetStatisticsTool();
      var args = new JsonObject();
      args.addProperty("name", "/any");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertFalse(obj.get("success").getAsBoolean());
      var error = obj.get("error").getAsString();
      assertTrue(error.contains("No log file is currently loaded"));
      assertTrue(error.contains("Use load_log first"));
    }

    @Test
    @DisplayName("provides clear error for empty time range")
    void providesClearErrorForEmptyTimeRange() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0, 1, 2}, new double[]{1.0, 2.0, 3.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.GetStatisticsTool();
      var args = new JsonObject();
      args.addProperty("name", "/entry");
      args.addProperty("start_time", 10.0);
      args.addProperty("end_time", 20.0);

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      assertFalse(obj.get("success").getAsBoolean());
      assertTrue(obj.get("error").getAsString().contains("No numeric data in range"));
    }
  }

  @Nested
  @DisplayName("Performance and Stress Tests")
  class PerformanceTests {

    @Test
    @DisplayName("handles rapid successive tool calls")
    void handlesRapidSuccessiveToolCalls() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0, 1, 2}, new double[]{1.0, 2.0, 3.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.GetStatisticsTool();
      var args = new JsonObject();
      args.addProperty("name", "/entry");

      // Call 100 times rapidly
      for (int i = 0; i < 100; i++) {
        var result = tool.execute(args);
        assertTrue(result.getAsJsonObject().get("success").getAsBoolean());
      }
    }

    @Test
    @DisplayName("handles multiple tools on same log")
    void handlesMultipleToolsOnSameLog() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry1", new double[]{0, 1, 2}, new double[]{1.0, 2.0, 3.0})
          .addNumericEntry("/entry2", new double[]{0, 1, 2}, new double[]{4.0, 5.0, 6.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      // Create multiple different tools
      var statsTool = new StatisticsTools.GetStatisticsTool();
      var compareTool = new StatisticsTools.CompareEntriesTool();
      var searchTool = new QueryTools.SearchEntriesTool();

      // All should work correctly
      var args1 = new JsonObject();
      args1.addProperty("name", "/entry1");
      assertTrue(statsTool.execute(args1).getAsJsonObject().get("success").getAsBoolean());

      var args2 = new JsonObject();
      args2.addProperty("name1", "/entry1");
      args2.addProperty("name2", "/entry2");
      assertTrue(compareTool.execute(args2).getAsJsonObject().get("success").getAsBoolean());

      var args3 = new JsonObject();
      assertTrue(searchTool.execute(args3).getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("handles tools created from different constructors")
    void handlesToolsCreatedFromDifferentConstructors() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      // Create tool using no-arg constructor (default singleton-based)
      var tool1 = new StatisticsTools.GetStatisticsTool();

      // Create another tool instance (also uses singleton-based)
      var tool2 = new StatisticsTools.GetStatisticsTool();

      var args = new JsonObject();
      args.addProperty("name", "/entry");

      // Both should work identically
      var result1 = tool1.execute(args);
      var result2 = tool2.execute(args);

      assertTrue(result1.getAsJsonObject().get("success").getAsBoolean());
      assertTrue(result2.getAsJsonObject().get("success").getAsBoolean());

      // Verify both tools share the same underlying LogManager instance
      assertSame(tool1.logManager, tool2.logManager);
    }
  }

  @Nested
  @DisplayName("Backwards Compatibility")
  class BackwardsCompatibilityTests {

    @Test
    @DisplayName("migrated tools maintain exact same response format")
    void migratedToolsMaintainSameResponseFormat() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/test", new double[]{0, 1, 2}, new double[]{1.0, 2.0, 3.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new StatisticsTools.GetStatisticsTool();
      var args = new JsonObject();
      args.addProperty("name", "/test");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      // Verify all expected fields are present
      assertTrue(obj.has("success"));
      assertTrue(obj.has("name"));
      assertTrue(obj.has("count"));
      assertTrue(obj.has("min"));
      assertTrue(obj.has("max"));
      assertTrue(obj.has("mean"));
      assertTrue(obj.has("median"));
      assertTrue(obj.has("std_dev"));
    }

    @Test
    @DisplayName("error responses maintain consistent format")
    void errorResponsesMaintainConsistentFormat() throws Exception {
      logManager.unloadAllLogs();

      var tool = new StatisticsTools.GetStatisticsTool();
      var args = new JsonObject();
      args.addProperty("name", "/test");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();

      // Verify error response format
      assertTrue(obj.has("success"));
      assertFalse(obj.get("success").getAsBoolean());
      assertTrue(obj.has("error"));
      assertTrue(obj.get("error").isJsonPrimitive());
    }
  }
}
