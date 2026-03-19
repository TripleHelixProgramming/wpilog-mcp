package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

@DisplayName("ToolBase")
class ToolBaseTest {

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

  // ===== TEST TOOL IMPLEMENTATIONS =====

  /** Simple test tool that doesn't require a log. */
  static class NoLogTool extends ToolBase {
    @Override
    public String name() {
      return "no_log_tool";
    }

    @Override
    public String description() {
      return "Test tool that doesn't need a log";
    }

    @Override
    public JsonObject inputSchema() {
      return new JsonObject();
    }

    @Override
    protected JsonElement executeInternal(JsonObject arguments) {
      return success().addProperty("result", "ok").build();
    }
  }

  /** Test tool that throws IllegalArgumentException. */
  static class ThrowingTool extends ToolBase {
    @Override
    public String name() {
      return "throwing_tool";
    }

    @Override
    public String description() {
      return "Test tool that throws";
    }

    @Override
    public JsonObject inputSchema() {
      return new JsonObject();
    }

    @Override
    protected JsonElement executeInternal(JsonObject arguments) {
      throw new IllegalArgumentException("Test error message");
    }
  }

  // ===== TEMPLATE METHOD PATTERN TESTS =====

  @Nested
  @DisplayName("Template Method Pattern")
  class TemplateMethodTests {

    @Test
    @DisplayName("executeInternal result is returned")
    void executeInternalResultIsReturned() throws Exception {
      var tool = new NoLogTool();
      var result = tool.execute(new JsonObject());

      assertTrue(result.isJsonObject());
      var obj = result.getAsJsonObject();
      assertTrue(obj.get("success").getAsBoolean());
      assertEquals("ok", obj.get("result").getAsString());
    }

    @Test
    @DisplayName("IllegalArgumentException is converted to error response")
    void illegalArgumentExceptionConvertedToError() throws Exception {
      var tool = new ThrowingTool();
      var result = tool.execute(new JsonObject());

      assertTrue(result.isJsonObject());
      var obj = result.getAsJsonObject();
      assertFalse(obj.get("success").getAsBoolean());
      assertEquals("Test error message", obj.get("error").getAsString());
    }
  }

  // ===== LOG ACQUISITION TESTS =====

  @Nested
  @DisplayName("Log Acquisition")
  class LogAcquisitionTests {

    @Test
    @DisplayName("requireActiveLog throws when no log loaded")
    void requireActiveLogThrowsWhenNoLog() {
      var tool = new NoLogTool();

      var exception = assertThrows(IllegalArgumentException.class, tool::requireActiveLog);
      assertTrue(exception.getMessage().contains("No log file is currently loaded"));
      assertTrue(exception.getMessage().contains("Use load_log first"));
    }

    @Test
    @DisplayName("requireActiveLog returns log when loaded")
    void requireActiveLogReturnsLogWhenLoaded() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test/log.wpilog")
          .addNumericEntry("/test/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test/log.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test/log.wpilog");

      var tool = new NoLogTool();
      var log = tool.requireActiveLog();

      assertNotNull(log);
      assertEquals("/test/log.wpilog", log.path());
    }
  }

  // ===== ENTRY RETRIEVAL TESTS =====

  @Nested
  @DisplayName("Entry Retrieval")
  class EntryRetrievalTests {

    private ParsedLog testLog;

    @BeforeEach
    void setUpLog() throws Exception {
      testLog = mockLogBuilder
          .setPath("/test/log.wpilog")
          .addNumericEntry("/Robot/Speed", new double[]{0, 1, 2}, new double[]{1.0, 2.0, 3.0})
          .addNumericEntry("/Robot/Voltage", new double[]{0, 1, 2}, new double[]{12.0, 11.8, 11.6})
          .addNumericEntry("/Drive/LeftSpeed", new double[]{0, 1}, new double[]{0.5, 1.5})
          .build();
      logManager.testPutLog("/test/log.wpilog", testLog);
      logManager.testSetActiveLogPath("/test/log.wpilog");
    }

    @Test
    @DisplayName("requireEntry returns values when entry exists")
    void requireEntryReturnsValuesWhenExists() {
      var tool = new NoLogTool();
      var values = tool.requireEntry(testLog, "/Robot/Speed");

      assertEquals(3, values.size());
      assertEquals(1.0, ((Number) values.get(0).value()).doubleValue());
      assertEquals(2.0, ((Number) values.get(1).value()).doubleValue());
      assertEquals(3.0, ((Number) values.get(2).value()).doubleValue());
    }

    @Test
    @DisplayName("requireEntry throws with suggestions when entry not found")
    void requireEntryThrowsWithSuggestions() {
      var tool = new NoLogTool();

      var exception = assertThrows(
          IllegalArgumentException.class,
          () -> tool.requireEntry(testLog, "/Robot/Spee")); // Typo

      assertTrue(exception.getMessage().contains("Entry not found: /Robot/Spee"));
      assertTrue(exception.getMessage().contains("Did you mean"));
      assertTrue(exception.getMessage().contains("/Robot/Speed"));
    }

    @Test
    @DisplayName("requireEntry suggestions are case-insensitive")
    void requireEntrySuggestionsAreCaseInsensitive() {
      var tool = new NoLogTool();

      var exception = assertThrows(
          IllegalArgumentException.class,
          () -> tool.requireEntry(testLog, "speed")); // Lowercase

      assertTrue(exception.getMessage().contains("Did you mean"));
      // Should suggest entries containing "speed" (case-insensitive)
    }

    @Test
    @DisplayName("requireEntry limits suggestions to 5")
    void requireEntryLimitsSuggestions() {
      // Create log with many matching entries
      var builder = new MockLogBuilder().setPath("/test/log.wpilog");
      for (int i = 0; i < 10; i++) {
        builder.addNumericEntry("/Entry" + i, new double[]{0}, new double[]{1.0});
      }
      var log = builder.build();

      var tool = new NoLogTool();
      var exception = assertThrows(
          IllegalArgumentException.class,
          () -> tool.requireEntry(log, "nonexistent"));

      // Should suggest up to 5 entries, not all 10
      var message = exception.getMessage();
      int commaCount = message.length() - message.replace(",", "").length();
      assertTrue(commaCount <= 4); // 5 entries = 4 commas
    }
  }

  // ===== TIME RANGE FILTERING TESTS =====

  @Nested
  @DisplayName("Time Range Filtering")
  class TimeRangeFilteringTests {

    private List<TimestampedValue> testValues;

    @BeforeEach
    void setUpValues() {
      testValues = List.of(
          new TimestampedValue(0.0, 10.0),
          new TimestampedValue(1.0, 20.0),
          new TimestampedValue(2.0, 30.0),
          new TimestampedValue(3.0, 40.0),
          new TimestampedValue(4.0, 50.0),
          new TimestampedValue(5.0, 60.0)
      );
    }

    @Test
    @DisplayName("inTimeRange returns true when both bounds null")
    void inTimeRangeAllowsAllWhenBothNull() {
      var tool = new NoLogTool();
      assertTrue(tool.inTimeRange(2.5, null, null));
    }

    @Test
    @DisplayName("inTimeRange respects start bound")
    void inTimeRangeRespectsStartBound() {
      var tool = new NoLogTool();
      assertFalse(tool.inTimeRange(1.0, 2.0, null));
      assertTrue(tool.inTimeRange(2.0, 2.0, null));
      assertTrue(tool.inTimeRange(3.0, 2.0, null));
    }

    @Test
    @DisplayName("inTimeRange respects end bound")
    void inTimeRangeRespectsEndBound() {
      var tool = new NoLogTool();
      assertTrue(tool.inTimeRange(1.0, null, 2.0));
      assertTrue(tool.inTimeRange(2.0, null, 2.0));
      assertFalse(tool.inTimeRange(3.0, null, 2.0));
    }

    @Test
    @DisplayName("inTimeRange respects both bounds")
    void inTimeRangeRespectsBothBounds() {
      var tool = new NoLogTool();
      assertFalse(tool.inTimeRange(1.0, 2.0, 4.0));
      assertTrue(tool.inTimeRange(2.0, 2.0, 4.0));
      assertTrue(tool.inTimeRange(3.0, 2.0, 4.0));
      assertTrue(tool.inTimeRange(4.0, 2.0, 4.0));
      assertFalse(tool.inTimeRange(5.0, 2.0, 4.0));
    }

    @Test
    @DisplayName("filterTimeRange with no bounds returns all")
    void filterTimeRangeNoBoundsReturnsAll() {
      var tool = new NoLogTool();
      var filtered = tool.filterTimeRange(testValues, null, null);
      assertEquals(6, filtered.size());
    }

    @Test
    @DisplayName("filterTimeRange with start bound")
    void filterTimeRangeWithStartBound() {
      var tool = new NoLogTool();
      var filtered = tool.filterTimeRange(testValues, 2.0, null);
      assertEquals(4, filtered.size());
      assertEquals(2.0, filtered.get(0).timestamp());
      assertEquals(5.0, filtered.get(3).timestamp());
    }

    @Test
    @DisplayName("filterTimeRange with end bound")
    void filterTimeRangeWithEndBound() {
      var tool = new NoLogTool();
      var filtered = tool.filterTimeRange(testValues, null, 3.0);
      assertEquals(4, filtered.size());
      assertEquals(0.0, filtered.get(0).timestamp());
      assertEquals(3.0, filtered.get(3).timestamp());
    }

    @Test
    @DisplayName("filterTimeRange with both bounds")
    void filterTimeRangeWithBothBounds() {
      var tool = new NoLogTool();
      var filtered = tool.filterTimeRange(testValues, 1.5, 3.5);
      assertEquals(2, filtered.size());
      assertEquals(2.0, filtered.get(0).timestamp());
      assertEquals(3.0, filtered.get(1).timestamp());
    }
  }

  // ===== DATA EXTRACTION TESTS =====

  @Nested
  @DisplayName("Data Extraction")
  class DataExtractionTests {

    @Test
    @DisplayName("extractNumericData filters non-numeric values")
    void extractNumericDataFiltersNonNumeric() {
      var values = List.of(
          new TimestampedValue(0.0, 10.0),
          new TimestampedValue(1.0, "not a number"),
          new TimestampedValue(2.0, 20.0),
          new TimestampedValue(3.0, true),
          new TimestampedValue(4.0, 30.0)
      );

      var tool = new NoLogTool();
      var data = tool.extractNumericData(values);

      assertEquals(3, data.length);
      assertEquals(10.0, data[0]);
      assertEquals(20.0, data[1]);
      assertEquals(30.0, data[2]);
    }

    @Test
    @DisplayName("extractNumericData with time range")
    void extractNumericDataWithTimeRange() {
      var values = List.of(
          new TimestampedValue(0.0, 10.0),
          new TimestampedValue(1.0, 20.0),
          new TimestampedValue(2.0, 30.0),
          new TimestampedValue(3.0, 40.0),
          new TimestampedValue(4.0, 50.0)
      );

      var tool = new NoLogTool();
      var data = tool.extractNumericData(values, 1.5, 3.5);

      assertEquals(2, data.length);
      assertEquals(30.0, data[0]);
      assertEquals(40.0, data[1]);
    }

    @Test
    @DisplayName("extractNumericData handles different Number types")
    void extractNumericDataHandlesDifferentTypes() {
      var values = List.of(
          new TimestampedValue(0.0, 10),       // Integer
          new TimestampedValue(1.0, 20.5),     // Double
          new TimestampedValue(2.0, 30L),      // Long
          new TimestampedValue(3.0, 40.0f)     // Float
      );

      var tool = new NoLogTool();
      var data = tool.extractNumericData(values);

      assertEquals(4, data.length);
      assertEquals(10.0, data[0]);
      assertEquals(20.5, data[1]);
      assertEquals(30.0, data[2]);
      assertEquals(40.0, data[3], 0.001);
    }
  }

  // ===== ENTRY SEARCH TESTS =====

  @Nested
  @DisplayName("Entry Search")
  class EntrySearchTests {

    private ParsedLog testLog;

    @BeforeEach
    void setUpLog() throws Exception {
      testLog = mockLogBuilder
          .setPath("/test/log.wpilog")
          .addNumericEntry("/Robot/Speed", new double[]{0}, new double[]{1.0})
          .addNumericEntry("/Robot/Voltage", new double[]{0}, new double[]{12.0})
          .addNumericEntry("/Drive/LeftSpeed", new double[]{0}, new double[]{0.5})
          .addNumericEntry("/Drive/RightSpeed", new double[]{0}, new double[]{0.5})
          .addNumericEntry("/Sensors/Temperature", new double[]{0}, new double[]{25.0})
          .build();
    }

    @Test
    @DisplayName("findEntryByPattern finds first match case-insensitive")
    void findEntryByPatternFindsCaseInsensitive() {
      var tool = new NoLogTool();
      var entry = tool.findEntryByPattern(testLog, "speed");

      assertNotNull(entry);
      assertTrue(entry.toLowerCase().contains("speed"));
    }

    @Test
    @DisplayName("findEntryByPattern returns null when no match")
    void findEntryByPatternReturnsNullWhenNoMatch() {
      var tool = new NoLogTool();
      var entry = tool.findEntryByPattern(testLog, "nonexistent");

      assertNull(entry);
    }

    @Test
    @DisplayName("findEntriesByPattern finds all matches")
    void findEntriesByPatternFindsAllMatches() {
      var tool = new NoLogTool();
      var entries = tool.findEntriesByPattern(testLog, "speed");

      // Should find /Robot/Speed, /Drive/LeftSpeed, /Drive/RightSpeed
      assertEquals(3, entries.size());
    }

    @Test
    @DisplayName("findEntriesByPattern is case-insensitive")
    void findEntriesByPatternIsCaseInsensitive() {
      var tool = new NoLogTool();
      var entries = tool.findEntriesByPattern(testLog, "ROBOT");

      // Should find /Robot/Speed and /Robot/Voltage
      assertTrue(entries.size() >= 2);
    }

    @Test
    @DisplayName("findEntriesByPattern returns empty list when no match")
    void findEntriesByPatternReturnsEmptyWhenNoMatch() {
      var tool = new NoLogTool();
      var entries = tool.findEntriesByPattern(testLog, "nonexistent");

      assertTrue(entries.isEmpty());
    }
  }

  // ===== RESPONSE BUILDING TESTS =====

  @Nested
  @DisplayName("Response Building")
  class ResponseBuildingTests {

    @Test
    @DisplayName("success() returns ResponseBuilder")
    void successReturnsResponseBuilder() {
      var tool = new NoLogTool();
      var builder = tool.success();

      assertNotNull(builder);
      var response = builder.build();
      assertTrue(response.get("success").getAsBoolean());
    }

    @Test
    @DisplayName("error() returns ResponseBuilder with error")
    void errorReturnsResponseBuilderWithError() {
      var tool = new NoLogTool();
      var builder = tool.error("Test error");

      assertNotNull(builder);
      var response = builder.build();
      assertFalse(response.get("success").getAsBoolean());
      assertEquals("Test error", response.get("error").getAsString());
    }
  }
}
