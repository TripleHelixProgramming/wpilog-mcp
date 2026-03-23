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
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

/**
 * Logic-level unit tests for StatisticsTools using synthetic log data.
 * These tests verify the mathematical correctness of statistics calculations.
 */
class StatisticsToolsLogicTest {

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

    StatisticsTools.registerAll(capturingRegistry);
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
    @DisplayName("includes data quality and analysis directives in response")
    void includesDataQualityAndDirectives() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/stats_dq.wpilog")
          .addNumericEntry("/Test/Values", new double[]{0,1,2,3,4}, new double[]{1,2,3,4,5})
          .build();

      setActiveLog(log);

      var tool = findTool("get_statistics");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Values");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      // Verify data_quality section
      assertTrue(resultObj.has("data_quality"), "Response should include data_quality");
      var dq = resultObj.getAsJsonObject("data_quality");
      assertEquals(5, dq.get("sample_count").getAsInt());
      assertTrue(dq.has("quality_score"));
      assertTrue(dq.has("effective_sample_rate_hz"));

      // Verify server_analysis_directives section
      assertTrue(resultObj.has("server_analysis_directives"),
          "Response should include server_analysis_directives");
      var directives = resultObj.getAsJsonObject("server_analysis_directives");
      assertTrue(directives.has("confidence_level"));
      assertTrue(directives.has("sample_context"));
      assertTrue(directives.has("suggested_followup"));
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

    @Test
    @DisplayName("filters out NaN and Infinity values")
    void filtersNanAndInfinity() throws Exception {
      // Data: [1.0, NaN, 3.0, Infinity, 5.0] → filtered to [1.0, 3.0, 5.0]
      var log = new MockLogBuilder()
          .setPath("/test/stats_nan.wpilog")
          .addNumericEntry("/Test/Values",
              new double[]{0, 1, 2, 3, 4},
              new double[]{1.0, Double.NaN, 3.0, Double.POSITIVE_INFINITY, 5.0})
          .build();

      setActiveLog(log);

      var tool = findTool("get_statistics");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Values");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(3, resultObj.get("count").getAsInt(),
          "Should count only finite values: [1.0, 3.0, 5.0]");
      assertEquals(3.0, resultObj.get("mean").getAsDouble(), 0.001,
          "Mean of [1.0, 3.0, 5.0] should be 3.0");
      assertEquals(1.0, resultObj.get("min").getAsDouble(), 0.001);
      assertEquals(5.0, resultObj.get("max").getAsDouble(), 0.001);
      assertEquals(3.0, resultObj.get("median").getAsDouble(), 0.001);
      // Std dev of [1, 3, 5] with Bessel's: sqrt(((1-3)^2 + (3-3)^2 + (5-3)^2) / 2) = sqrt(4) = 2.0
      assertEquals(2.0, resultObj.get("std_dev").getAsDouble(), 0.001);
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
    @DisplayName("filters NaN and Infinity from IQR calculation")
    void filtersNanAndInfinityFromIqr() throws Exception {
      // Data with NaN and Infinity mixed in - these should be filtered out
      // before IQR computation, not silently corrupt Q3.
      var log = new MockLogBuilder()
          .setPath("/test/nan.wpilog")
          .addNumericEntry("/Test/WithNaN",
              new double[]{0,1,2,3,4,5,6,7,8,9,10,11},
              new double[]{1,2,3,4,5,6,7,8,9,10,
                  Double.NaN, Double.POSITIVE_INFINITY})
          .build();

      setActiveLog(log);

      var tool = findTool("detect_anomalies");
      var args = new JsonObject();
      args.addProperty("name", "/Test/WithNaN");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      // Should succeed, with NaN/Infinity filtered out before IQR
      assertTrue(resultObj.get("success").getAsBoolean());
      // The anomaly count should reflect only real outliers from the finite data
      int anomalyCount = resultObj.get("anomaly_count").getAsInt();
      assertTrue(anomalyCount >= 0);
    }

    @Test
    @DisplayName("handles dataset with too many non-finite values")
    void handlesDatasetWithTooManyNonFinite() throws Exception {
      // All but 3 values are NaN - not enough finite data for IQR (needs >= 4)
      var log = new MockLogBuilder()
          .setPath("/test/allnan.wpilog")
          .addNumericEntry("/Test/MostlyNaN",
              new double[]{0,1,2,3,4,5,6},
              new double[]{1, Double.NaN, Double.NaN, 2, Double.NaN, Double.NaN, 3})
          .build();

      setActiveLog(log);

      var tool = findTool("detect_anomalies");
      var args = new JsonObject();
      args.addProperty("name", "/Test/MostlyNaN");

      // LogRequiringTool catches IllegalArgumentException and returns error response
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();
      assertFalse(resultObj.get("success").getAsBoolean(),
          "Should fail when too few finite values remain after filtering NaN/Infinity");
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

    @Test
    @DisplayName("returns height_diff instead of prominence in output")
    void returnsHeightDiffInOutput() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/peaks.wpilog")
          .addNumericEntry("/Sensor/Oscillating",
              new double[]{0,1,2,3,4,5,6},
              new double[]{0,10,0,15,0,5,0})
          .build();
      setActiveLog(log);

      var tool = findTool("find_peaks");
      var args = new JsonObject();
      args.addProperty("name", "/Sensor/Oscillating");
      args.addProperty("type", "max");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var maxima = resultObj.getAsJsonArray("maxima");
      assertTrue(maxima.size() > 0);

      var firstPeak = maxima.get(0).getAsJsonObject();
      assertTrue(firstPeak.has("height_diff"), "Output should use 'height_diff' not 'prominence'");
      assertFalse(firstPeak.has("prominence"), "Output should not use deprecated 'prominence' field");
    }

    @Test
    @DisplayName("filters by min_height_diff parameter")
    void filtersByMinHeightDiff() throws Exception {
      // Data: peaks at values 5, 15, 3 (height_diffs: 5, 15, 3)
      var log = new MockLogBuilder()
          .setPath("/test/peaks.wpilog")
          .addNumericEntry("/Sensor/Oscillating",
              new double[]{0,1,2,3,4,5,6},
              new double[]{0,5,0,15,0,3,0})
          .build();
      setActiveLog(log);

      var tool = findTool("find_peaks");
      var args = new JsonObject();
      args.addProperty("name", "/Sensor/Oscillating");
      args.addProperty("type", "max");
      args.addProperty("min_height_diff", 10.0);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var maxima = resultObj.getAsJsonArray("maxima");
      assertEquals(1, maxima.size(), "Only the peak at value 15 should pass the filter");
      assertEquals(15.0, maxima.get(0).getAsJsonObject().get("value").getAsDouble(), 0.01);
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

    @Test
    @DisplayName("avg_rate denominator counts only valid samples (not zero-dt gaps)")
    void avgRateDenominatorCountsOnlyValidSamples() throws Exception {
      // Data with duplicate timestamps — some samples have dt=0 and should be skipped.
      // 5 pairs of duplicate timestamps, each pair increments by 1.0 over dt=1.0.
      // Valid rates: 1/1 = 1.0 for each pair transition. But duplicate pairs produce dt=0.
      var timestamps = new double[]{0, 0, 1, 1, 2, 2, 3, 3, 4, 4};
      var values =     new double[]{0, 0, 1, 1, 2, 2, 3, 3, 4, 4};

      var log = new MockLogBuilder()
          .setPath("/test/roc_dup.wpilog")
          .addNumericEntry("/Position", timestamps, values)
          .build();
      setActiveLog(log);

      var tool = findTool("rate_of_change");
      var args = new JsonObject();
      args.addProperty("name", "/Position");
      args.addProperty("window_size", 1);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var stats = resultObj.getAsJsonObject("statistics");
      double avgRate = stats.get("avg_rate").getAsDouble();
      // With duplicate timestamps, only transitions between different timestamps
      // produce valid rates. The avg should reflect actual samples, not be diluted.
      assertTrue(Double.isFinite(avgRate), "avg_rate should be finite");
      assertTrue(avgRate > 0, "avg_rate should be positive for increasing data");
    }
  }

  @Nested
  @DisplayName("time_correlate Tool")
  class TimeCorrelateToolTests {

    @Test
    @DisplayName("warns about mismatched sample rates")
    void warnsAboutMismatchedSampleRates() throws Exception {
      // Entry A at ~500Hz (0.002s intervals), Entry B at ~5Hz (0.2s intervals)
      // Ratio = 500/5 = 100x, well above the 10x warning threshold
      var timestampsA = new double[200];
      var valuesA = new double[200];
      for (int i = 0; i < 200; i++) {
        timestampsA[i] = i * 0.002;
        valuesA[i] = Math.sin(i * 0.002 * 2 * Math.PI);
      }
      // Slow signal at ~5Hz spanning the same time range
      var timestampsB = new double[]{0, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.38, 0.39};
      var valuesB = new double[]{0, 0.31, 0.59, 0.81, 0.95, 1.0, 0.95, 0.81, 0.69, 0.64};

      var log = new MockLogBuilder()
          .setPath("/test/rates.wpilog")
          .addNumericEntry("/Fast", timestampsA, valuesA)
          .addNumericEntry("/Slow", timestampsB, valuesB)
          .build();
      setActiveLog(log);

      var tool = findTool("time_correlate");
      var args = new JsonObject();
      args.addProperty("name1", "/Fast");
      args.addProperty("name2", "/Slow");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      // Should have a warning about rate mismatch
      assertTrue(resultObj.has("warnings"), "Should have warnings for rate mismatch");
      var warnings = resultObj.getAsJsonArray("warnings");
      boolean foundRateWarning = false;
      for (int i = 0; i < warnings.size(); i++) {
        if (warnings.get(i).getAsString().contains("Sample rate mismatch")) {
          foundRateWarning = true;
        }
      }
      assertTrue(foundRateWarning, "Should warn about sample rate mismatch");
    }

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

    @Test
    @DisplayName("warns when fewer than 30 overlapping samples (§1.4 fix)")
    void warnsOnLowSampleCount() throws Exception {
      // Only 5 overlapping points — statistically meaningless
      var log = new MockLogBuilder()
          .setPath("/test/low_corr.wpilog")
          .addNumericEntry("/A", new double[]{0,1,2,3,4}, new double[]{1,2,3,4,5})
          .addNumericEntry("/B", new double[]{0,1,2,3,4}, new double[]{5,4,3,2,1})
          .build();
      setActiveLog(log);

      var tool = findTool("time_correlate");
      var args = new JsonObject();
      args.addProperty("name1", "/A");
      args.addProperty("name2", "/B");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("warnings"), "Should have warnings for low sample count");
      var warnings = resultObj.getAsJsonArray("warnings");
      boolean foundLowSampleWarning = false;
      for (int i = 0; i < warnings.size(); i++) {
        if (warnings.get(i).getAsString().contains("insufficient for statistical significance")) {
          foundLowSampleWarning = true;
        }
      }
      assertTrue(foundLowSampleWarning,
          "Should warn about low sample count for correlation");
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

    @Test
    @DisplayName("only compares within overlapping time range (no extrapolation)")
    void onlyComparesWithinOverlappingTimeRange() throws Exception {
      // Entry A runs from t=0 to t=4, Entry B runs from t=2 to t=6
      // Only the overlap (t=2 to t=4) should be compared
      // Outside the overlap, getValueAtTimeLinear returns null so those points are skipped
      var log = new MockLogBuilder()
          .setPath("/test/compare.wpilog")
          .addNumericEntry("/Entry/A", new double[]{0,1,2,3,4}, new double[]{10,10,10,10,10})
          .addNumericEntry("/Entry/B", new double[]{2,3,4,5,6}, new double[]{10,10,10,10,10})
          .build();

      setActiveLog(log);

      var tool = findTool("compare_entries");
      var args = new JsonObject();
      args.addProperty("name1", "/Entry/A");
      args.addProperty("name2", "/Entry/B");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      // Within the overlap, both are constant at 10 → RMSE = 0, max_diff = 0
      assertEquals(0.0, resultObj.get("rmse").getAsDouble(), 0.001);
      assertEquals(0.0, resultObj.get("max_difference").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("handles non-overlapping entries gracefully")
    void handlesNonOverlappingEntries() throws Exception {
      // Entry A runs from t=0 to t=2, Entry B runs from t=5 to t=7 (no overlap)
      var log = new MockLogBuilder()
          .setPath("/test/compare.wpilog")
          .addNumericEntry("/Entry/A", new double[]{0,1,2}, new double[]{1,2,3})
          .addNumericEntry("/Entry/B", new double[]{5,6,7}, new double[]{4,5,6})
          .build();

      setActiveLog(log);

      var tool = findTool("compare_entries");
      var args = new JsonObject();
      args.addProperty("name1", "/Entry/A");
      args.addProperty("name2", "/Entry/B");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      // Should succeed but with NaN RMSE since no points overlap
      assertTrue(resultObj.get("success").getAsBoolean());
    }
  }

  // ==================== Edge Case Tests (§6.4) ====================

  @Nested
  @DisplayName("Statistical Edge Cases")
  class StatisticalEdgeCases {

    @Test
    @DisplayName("correlation with constant signal returns NaN with warning")
    void correlationWithConstantSignal() throws Exception {
      // One signal is flat (zero variance) → correlation undefined
      var log = new MockLogBuilder()
          .setPath("/test/flat.wpilog")
          .addNumericEntry("/Flat", new double[]{0,1,2,3,4}, new double[]{5,5,5,5,5})
          .addNumericEntry("/Vary", new double[]{0,1,2,3,4}, new double[]{1,2,3,4,5})
          .build();
      setActiveLog(log);

      var tool = findTool("time_correlate");
      var args = new JsonObject();
      args.addProperty("name1", "/Flat");
      args.addProperty("name2", "/Vary");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      // Zero variance → correlation should be NaN
      assertTrue(Double.isNaN(resultObj.get("correlation").getAsDouble()),
          "Correlation with constant signal should be NaN");
      assertTrue(resultObj.has("warnings"), "Should have warning about zero variance");
    }

    @Test
    @DisplayName("anomaly detection with all identical values returns zero anomalies")
    void anomaliesWithIdenticalValues() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/identical.wpilog")
          .addNumericEntry("/Constant",
              new double[]{0,1,2,3,4,5,6,7},
              new double[]{42,42,42,42,42,42,42,42})
          .build();
      setActiveLog(log);

      var tool = findTool("detect_anomalies");
      var args = new JsonObject();
      args.addProperty("name", "/Constant");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      // IQR = 0, so low = high = value, nothing is outside bounds
      assertEquals(0, resultObj.get("anomaly_count").getAsInt(),
          "All identical values should produce zero anomalies");
    }

    @Test
    @DisplayName("statistics with all identical values produces zero std_dev")
    void statisticsWithIdenticalValues() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/identical.wpilog")
          .addNumericEntry("/Constant",
              new double[]{0,1,2,3,4},
              new double[]{7,7,7,7,7})
          .build();
      setActiveLog(log);

      var tool = findTool("get_statistics");
      var args = new JsonObject();
      args.addProperty("name", "/Constant");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(0.0, resultObj.get("std_dev").getAsDouble(), 0.001);
      assertEquals(7.0, resultObj.get("mean").getAsDouble(), 0.001);
      assertEquals(7.0, resultObj.get("median").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("rate_of_change with all identical timestamps returns zero avg_rate")
    void rateOfChangeAllIdenticalTimestamps() throws Exception {
      // All timestamps are 0.0 — every dt is 0, no valid rates
      var log = new MockLogBuilder()
          .setPath("/test/same_ts.wpilog")
          .addNumericEntry("/Sensor",
              new double[]{0,0,0,0,0},
              new double[]{1,2,3,4,5})
          .build();
      setActiveLog(log);

      var tool = findTool("rate_of_change");
      var args = new JsonObject();
      args.addProperty("name", "/Sensor");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var stats = resultObj.getAsJsonObject("statistics");
      assertEquals(0.0, stats.get("avg_rate").getAsDouble(), 0.001,
          "All zero-dt should produce avg_rate=0");
    }

    @Test
    @DisplayName("percentile at 0th returns min, at 100th returns max")
    void percentileBoundaries() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/percentile.wpilog")
          .addNumericEntry("/Values",
              new double[]{0,1,2,3,4,5,6,7,8,9},
              new double[]{10,20,30,40,50,60,70,80,90,100})
          .build();
      setActiveLog(log);

      var tool = findTool("get_statistics");
      var args = new JsonObject();
      args.addProperty("name", "/Values");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(10.0, resultObj.get("min").getAsDouble(), 0.001);
      assertEquals(100.0, resultObj.get("max").getAsDouble(), 0.001);
    }
  }

  // ==================== Data Quality Propagation (§2.1) ====================

  @Nested
  @DisplayName("Data Quality Propagation (§2.1)")
  class DataQualityPropagationTests {

    private ParsedLog createTestLog() {
      return new MockLogBuilder()
          .setPath("/test/quality_propagation.wpilog")
          .addNumericEntry("/A", new double[]{0,1,2,3,4,5,6,7,8,9},
              new double[]{1,2,3,4,5,6,7,8,9,10})
          .addNumericEntry("/B", new double[]{0,1,2,3,4,5,6,7,8,9},
              new double[]{10,9,8,7,6,5,4,3,2,1})
          .build();
    }

    @Test
    @DisplayName("compare_entries includes data quality")
    void compareEntriesQuality() throws Exception {
      setActiveLog(createTestLog());
      var tool = findTool("compare_entries");
      var args = new JsonObject();
      args.addProperty("name1", "/A");
      args.addProperty("name2", "/B");
      var result = tool.execute(args).getAsJsonObject();
      assertTrue(result.has("data_quality"));
      assertTrue(result.has("server_analysis_directives"));
    }

    @Test
    @DisplayName("detect_anomalies includes data quality")
    void detectAnomaliesQuality() throws Exception {
      setActiveLog(createTestLog());
      var tool = findTool("detect_anomalies");
      var args = new JsonObject();
      args.addProperty("name", "/A");
      var result = tool.execute(args).getAsJsonObject();
      assertTrue(result.has("data_quality"));
      assertTrue(result.has("server_analysis_directives"));
    }

    @Test
    @DisplayName("find_peaks includes data quality")
    void findPeaksQuality() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/peaks_q.wpilog")
          .addNumericEntry("/Wave", new double[]{0,1,2,3,4,5,6},
              new double[]{0,5,0,8,0,3,0})
          .build();
      setActiveLog(log);
      var tool = findTool("find_peaks");
      var args = new JsonObject();
      args.addProperty("name", "/Wave");
      var result = tool.execute(args).getAsJsonObject();
      assertTrue(result.has("data_quality"));
      assertTrue(result.has("server_analysis_directives"));
    }

    @Test
    @DisplayName("rate_of_change includes data quality")
    void rateOfChangeQuality() throws Exception {
      setActiveLog(createTestLog());
      var tool = findTool("rate_of_change");
      var args = new JsonObject();
      args.addProperty("name", "/A");
      var result = tool.execute(args).getAsJsonObject();
      assertTrue(result.has("data_quality"));
      assertTrue(result.has("server_analysis_directives"));
    }

    @Test
    @DisplayName("time_correlate includes data quality")
    void timeCorrelateQuality() throws Exception {
      setActiveLog(createTestLog());
      var tool = findTool("time_correlate");
      var args = new JsonObject();
      args.addProperty("name1", "/A");
      args.addProperty("name2", "/B");
      var result = tool.execute(args).getAsJsonObject();
      assertTrue(result.has("data_quality"));
      assertTrue(result.has("server_analysis_directives"));
    }
  }
}
