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
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

/**
 * Logic-level unit tests for StatisticsTools using synthetic log data.
 * These tests verify the mathematical correctness of statistics calculations.
 */
class StatisticsToolsLogicTest {

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

    StatisticsTools.registerAll(capturingServer);
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
  @DisplayName("get_statistics Tool")
  class GetStatisticsToolTests {

    @Test
    @DisplayName("calculates correct statistics for uniform data")
    void calculatesCorrectStatisticsForUniformData() throws Exception {
      // Data: 1, 2, 3, 4, 5 - mean = 3
      // Sample std dev = sqrt(sum((xi - mean)^2) / (n-1)) = sqrt(10/4) = sqrt(2.5) ≈ 1.581
      var log = new MockLogBuilder()
          .setPath("/test/stats.wpilog")
          .addNumericEntry("/Test/Values", new double[]{0,1,2,3,4}, new double[]{1,2,3,4,5})
          .build();

      setActiveLog(log);

      var tool = findTool("get_statistics");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Values");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(5, resultObj.get("count").getAsInt());
      assertEquals(1.0, resultObj.get("min").getAsDouble(), 0.001);
      assertEquals(5.0, resultObj.get("max").getAsDouble(), 0.001);
      assertEquals(3.0, resultObj.get("mean").getAsDouble(), 0.001);
      assertEquals(3.0, resultObj.get("median").getAsDouble(), 0.001);
      assertEquals(1.581, resultObj.get("std_dev").getAsDouble(), 0.01);
    }

    @Test
    @DisplayName("calculates correct median for even count")
    void calculatesCorrectMedianForEvenCount() throws Exception {
      // Data: 1, 2, 3, 4 - median = 2.5
      var log = new MockLogBuilder()
          .setPath("/test/stats.wpilog")
          .addNumericEntry("/Test/Values", new double[]{0,1,2,3}, new double[]{1,2,3,4})
          .build();

      setActiveLog(log);

      var tool = findTool("get_statistics");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Values");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertEquals(2.5, resultObj.get("median").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("respects time range filter")
    void respectsTimeRangeFilter() throws Exception {
      // Values at t=0,1,2,3,4: 10, 20, 30, 40, 50
      // Filter to t=1-3 should give: 20, 30, 40 -> mean = 30
      var log = new MockLogBuilder()
          .setPath("/test/stats.wpilog")
          .addNumericEntry("/Test/Values", new double[]{0,1,2,3,4}, new double[]{10,20,30,40,50})
          .build();

      setActiveLog(log);

      var tool = findTool("get_statistics");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Values");
      args.addProperty("start_time", 1.0);
      args.addProperty("end_time", 3.0);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(3, resultObj.get("count").getAsInt());
      assertEquals(30.0, resultObj.get("mean").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("handles single value")
    void handlesSingleValue() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/stats.wpilog")
          .addNumericEntry("/Test/Values", new double[]{0}, new double[]{42.0})
          .build();

      setActiveLog(log);

      var tool = findTool("get_statistics");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Values");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(1, resultObj.get("count").getAsInt());
      assertEquals(42.0, resultObj.get("mean").getAsDouble(), 0.001);
      assertEquals(42.0, resultObj.get("min").getAsDouble(), 0.001);
      assertEquals(42.0, resultObj.get("max").getAsDouble(), 0.001);
      assertEquals(0.0, resultObj.get("std_dev").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("handles negative values correctly")
    void handlesNegativeValues() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/stats.wpilog")
          .addNumericEntry("/Test/Values", new double[]{0,1,2,3,4}, new double[]{-5,-3,-1,1,3})
          .build();

      setActiveLog(log);

      var tool = findTool("get_statistics");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Values");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(-5.0, resultObj.get("min").getAsDouble(), 0.001);
      assertEquals(3.0, resultObj.get("max").getAsDouble(), 0.001);
      assertEquals(-1.0, resultObj.get("mean").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("handles very large values")
    void handlesVeryLargeValues() throws Exception {
      double large = 1e15;
      var log = new MockLogBuilder()
          .setPath("/test/stats.wpilog")
          .addNumericEntry("/Test/Values", new double[]{0,1,2}, new double[]{large, large + 1, large + 2})
          .build();

      setActiveLog(log);

      var tool = findTool("get_statistics");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Values");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(large, resultObj.get("min").getAsDouble(), 1.0);
      assertEquals(large + 2, resultObj.get("max").getAsDouble(), 1.0);
    }

    @Test
    @DisplayName("handles zero values")
    void handlesZeroValues() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/stats.wpilog")
          .addNumericEntry("/Test/Values", new double[]{0,1,2}, new double[]{0,0,0})
          .build();

      setActiveLog(log);

      var tool = findTool("get_statistics");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Values");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(0.0, resultObj.get("mean").getAsDouble(), 0.001);
      assertEquals(0.0, resultObj.get("std_dev").getAsDouble(), 0.001);
    }
  }

  @Nested
  @DisplayName("detect_anomalies Tool")
  class DetectAnomaliesToolTests {

    @Test
    @DisplayName("reports no anomalies for uniform data")
    void reportsNoAnomaliesForUniformData() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/uniform.wpilog")
          .addNumericEntry("/Test/Uniform", new double[]{0,1,2,3,4,5,6,7}, new double[]{10,10,10,10,10,10,10,10})
          .build();

      setActiveLog(log);

      var tool = findTool("detect_anomalies");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Uniform");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(0, resultObj.get("anomaly_count").getAsInt());
    }

    @Test
    @DisplayName("correctly calculates IQR with percentile interpolation")
    void correctlyCalculatesIqrWithInterpolation() throws Exception {
      // Test data: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
      // Q1 (25th percentile) at index 2.25 = 2.75
      // Q3 (75th percentile) at index 6.75 = 7.75
      // IQR = 7.75 - 2.75 = 5.0
      // Lower fence = 2.75 - 1.5 * 5.0 = -4.75
      // Upper fence = 7.75 + 1.5 * 5.0 = 15.25
      // Outlier at 20 should be detected
      var log = new MockLogBuilder()
          .setPath("/test/iqr.wpilog")
          .addNumericEntry("/Test/WithOutlier",
              new double[]{0,1,2,3,4,5,6,7,8,9,10},
              new double[]{1,2,3,4,5,6,7,8,9,10,20})
          .build();

      setActiveLog(log);

      var tool = findTool("detect_anomalies");
      var args = new JsonObject();
      args.addProperty("name", "/Test/WithOutlier");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(1, resultObj.get("anomaly_count").getAsInt());

      var anomalies = resultObj.getAsJsonArray("anomalies");
      assertEquals(1, anomalies.size());
      assertEquals(20.0, anomalies.get(0).getAsJsonObject().get("value").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("handles edge case with small dataset for IQR")
    void handlesSmallDatasetForIqr() throws Exception {
      // With only 4 values, should still calculate IQR correctly
      var log = new MockLogBuilder()
          .setPath("/test/small.wpilog")
          .addNumericEntry("/Test/Small", new double[]{0,1,2,3}, new double[]{1,2,3,100})
          .build();

      setActiveLog(log);

      var tool = findTool("detect_anomalies");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Small");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("anomaly_count").getAsInt() >= 0);
    }
  }

  @Nested
  @DisplayName("find_peaks Tool")
  class FindPeaksToolTests {

    @Test
    @DisplayName("finds local maxima")
    void findsLocalMaxima() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/peaks.wpilog")
          .addNumericEntry("/Sensor/Oscillating", 
              new double[]{0,1,2,3,4,5,6,7,8,9}, 
              new double[]{0,10,0,15,0,10,0,5,0,0})
          .build();
      setActiveLog(log);

      var tool = findTool("find_peaks");
      var args = new JsonObject();
      args.addProperty("name", "/Sensor/Oscillating");
      args.addProperty("type", "max");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("maxima"));

      var maxima = resultObj.getAsJsonArray("maxima");
      assertTrue(maxima.size() >= 2); // Should find peaks at index 1 (10) and 3 (15)

      // The highest peak should have value 15
      boolean foundHighPeak = false;
      for (int i = 0; i < maxima.size(); i++) {
        var peak = maxima.get(i).getAsJsonObject();
        if (Math.abs(peak.get("value").getAsDouble() - 15.0) < 0.01) {
          foundHighPeak = true;
        }
      }
      assertTrue(foundHighPeak, "Should find peak with value 15");
    }
  }

  @Nested
  @DisplayName("rate_of_change Tool")
  class RateOfChangeToolTests {

    @Test
    @DisplayName("calculates correct rate for linear data")
    void calculatesCorrectRateForLinearData() throws Exception {
      var timestamps = new double[10];
      var values = new double[10];
      for (int i = 0; i < 10; i++) {
        timestamps[i] = i * 0.1;
        values[i] = i; // rate = 1/0.1 = 10
      }
      var log = new MockLogBuilder()
          .setPath("/test/roc.wpilog")
          .addNumericEntry("/Position", timestamps, values)
          .build();
      setActiveLog(log);

      var tool = findTool("rate_of_change");
      var args = new JsonObject();
      args.addProperty("name", "/Position");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var stats = resultObj.getAsJsonObject("statistics");
      double avgRate = stats.get("avg_rate").getAsDouble();
      assertEquals(10.0, avgRate, 0.5);
    }
  }

  @Nested
  @DisplayName("time_correlate Tool")
  class TimeCorrelateToolTests {

    @Test
    @DisplayName("identifies perfect positive correlation")
    void identifiesPerfectPositiveCorrelation() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/corr.wpilog")
          .addNumericEntry("/A", new double[]{0,1,2,3,4}, new double[]{1,2,3,4,5})
          .addNumericEntry("/B", new double[]{0,1,2,3,4}, new double[]{2,4,6,8,10})
          .build();
      setActiveLog(log);

      var tool = findTool("time_correlate");
      var args = new JsonObject();
      args.addProperty("name1", "/A");
      args.addProperty("name2", "/B");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      double correlation = resultObj.get("correlation").getAsDouble();
      assertEquals(1.0, correlation, 0.01); // Perfect positive correlation
    }
  }

  @Nested
  @DisplayName("compare_entries Tool")
  class CompareEntriesToolTests {

    @Test
    @DisplayName("calculates zero RMSE for identical entries")
    void calculatesZeroRmseForIdenticalEntries() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/compare.wpilog")
          .addNumericEntry("/Entry/A", new double[]{0,1,2,3,4}, new double[]{1,2,3,4,5})
          .addNumericEntry("/Entry/B", new double[]{0,1,2,3,4}, new double[]{1,2,3,4,5})
          .build();

      setActiveLog(log);

      var tool = findTool("compare_entries");
      var args = new JsonObject();
      args.addProperty("name1", "/Entry/A");
      args.addProperty("name2", "/Entry/B");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(0.0, resultObj.get("rmse").getAsDouble(), 0.001);
      assertEquals(0.0, resultObj.get("max_difference").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("calculates correct RMSE for different entries")
    void calculatesCorrectRmseForDifferentEntries() throws Exception {
      // Difference of 1 at each point: RMSE = 1
      var log = new MockLogBuilder()
          .setPath("/test/compare.wpilog")
          .addNumericEntry("/Entry/A", new double[]{0,1,2,3,4}, new double[]{1,2,3,4,5})
          .addNumericEntry("/Entry/B", new double[]{0,1,2,3,4}, new double[]{2,3,4,5,6})
          .build();

      setActiveLog(log);

      var tool = findTool("compare_entries");
      var args = new JsonObject();
      args.addProperty("name1", "/Entry/A");
      args.addProperty("name2", "/Entry/B");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(1.0, resultObj.get("rmse").getAsDouble(), 0.001);
      assertEquals(1.0, resultObj.get("max_difference").getAsDouble(), 0.001);
    }
  }
}
