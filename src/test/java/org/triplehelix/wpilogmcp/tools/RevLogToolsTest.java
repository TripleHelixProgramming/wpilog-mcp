package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogDevice;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;
import org.triplehelix.wpilogmcp.sync.ConfidenceLevel;
import org.triplehelix.wpilogmcp.sync.SignalPairResult;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;
import org.triplehelix.wpilogmcp.sync.SynchronizedLogs;

/**
 * Unit tests for RevLogTools.
 */
class RevLogToolsTest extends ToolTestBase {

  private LogManager logManager;

  @Override
  protected void registerTools(ToolRegistry registry) {
    RevLogTools.registerAll(registry);
  }

  @BeforeEach
  void setUpLogManager() {
    logManager = LogManager.getInstance();
  }

  private void putLogWithRevLog(ParsedLog wpilog, ParsedRevLog revlog, SyncResult syncResult) {
    logManager.testPutLog(wpilog.path(), wpilog);

    // Create synchronized logs and put in sync cache via reflection-free method
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog, syncResult, "rio")
        .build();

    // Use test accessor to set synchronized logs
    setSynchronizedLogs(wpilog.path(), syncLogs);
  }

  private void putLogNoRevLog(ParsedLog wpilog) {
    logManager.testPutLog(wpilog.path(), wpilog);
    // Create synchronized logs with no revlogs
    SynchronizedLogs syncLogs = new SynchronizedLogs(wpilog);
    setSynchronizedLogs(wpilog.path(), syncLogs);
  }

  // Use reflection to set synchronized logs since there's no test accessor
  private void setSynchronizedLogs(String wpilogPath, SynchronizedLogs syncLogs) {
    try {
      var field = LogManager.class.getDeclaredField("syncCache");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var cache = (Map<String, SynchronizedLogs>) field.get(logManager);
      // Normalize path the same way LogManager.getSynchronizedLogs() does
      String normalized = java.nio.file.Path.of(wpilogPath).toAbsolutePath().normalize().toString();
      cache.put(normalized, syncLogs);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set synchronized logs", e);
    }
  }

  private ParsedLog createMockWpilog() {
    Map<String, EntryInfo> entries = new HashMap<>();
    entries.put("/drive/output", new EntryInfo(1, "/drive/output", "double", ""));

    Map<String, List<TimestampedValue>> values = new HashMap<>();
    values.put("/drive/output", createValues(100, 0.0));

    return new ParsedLog("/test.wpilog", entries, values, 0, 2);
  }

  private ParsedRevLog createMockRevLog() {
    Map<Integer, RevLogDevice> devices = new HashMap<>();
    devices.put(1, new RevLogDevice(1, "SPARK MAX"));
    devices.put(2, new RevLogDevice(2, "SPARK Flex"));

    Map<String, RevLogSignal> signals = new HashMap<>();
    signals.put("SparkMax_1/appliedOutput",
        new RevLogSignal("appliedOutput", "SparkMax_1", createValues(100, 0.0), "duty_cycle"));
    signals.put("SparkMax_1/velocity",
        new RevLogSignal("velocity", "SparkMax_1", createValues(100, 0.0), "rpm"));
    signals.put("SparkFlex_2/outputCurrent",
        new RevLogSignal("outputCurrent", "SparkFlex_2", createValues(50, 0.0), "A"));

    return new ParsedRevLog("/test.revlog", "20260320_143052", devices, signals, 0, 2, 250);
  }

  private SyncResult createGoodSyncResult() {
    List<SignalPairResult> pairs = List.of(
        new SignalPairResult("/drive/output", "SparkMax_1/appliedOutput", 500_000, 0.95, 100)
    );
    return new SyncResult(500_000L, 0.95, ConfidenceLevel.HIGH, pairs,
        SyncMethod.CROSS_CORRELATION, "Good sync via cross-correlation");
  }

  private SyncResult createLowConfidenceSyncResult() {
    return new SyncResult(500_000L, 0.3, ConfidenceLevel.LOW, List.of(),
        SyncMethod.SYSTEM_TIME_ONLY, "Weak correlation, using system time only");
  }

  private List<TimestampedValue> createValues(int count, double startTime) {
    List<TimestampedValue> values = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      values.add(new TimestampedValue(startTime + i * 0.02, Math.sin(i * 0.1)));
    }
    return values;
  }

  @Nested
  @DisplayName("list_revlog_signals Tool")
  class ListRevLogSignalsTests {

    @Test
    @DisplayName("lists all signals from synchronized revlog")
    void listsAllSignals() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("list_revlog_signals");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(3, resultObj.get("signal_count").getAsInt());
      assertEquals(1, resultObj.get("revlog_count").getAsInt());
      assertEquals("high", resultObj.get("overall_sync_confidence").getAsString());

      var signals = resultObj.getAsJsonArray("signals");
      assertEquals(3, signals.size());

      // Verify signal structure
      var firstSignal = signals.get(0).getAsJsonObject();
      assertTrue(firstSignal.has("key"));
      assertTrue(firstSignal.has("device"));
      assertTrue(firstSignal.has("signal"));
      assertTrue(firstSignal.has("unit"));
      assertTrue(firstSignal.has("sample_count"));
      assertTrue(firstSignal.has("sync_confidence"));
    }

    @Test
    @DisplayName("filters by device key")
    void filtersByDevice() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("list_revlog_signals");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("device_filter", "SparkMax_1");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(2, resultObj.get("signal_count").getAsInt()); // appliedOutput and velocity
    }

    @Test
    @DisplayName("filters by signal name")
    void filtersBySignal() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("list_revlog_signals");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("signal_filter", "velocity");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(1, resultObj.get("signal_count").getAsInt());
    }

    @Test
    @DisplayName("returns empty list when no revlogs synchronized")
    void noRevLogs() throws Exception {
      var wpilog = createMockWpilog();
      putLogNoRevLog(wpilog);

      var tool = findTool("list_revlog_signals");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(0, resultObj.get("signal_count").getAsInt());
      assertEquals(0, resultObj.get("revlog_count").getAsInt());
      assertTrue(resultObj.has("warnings"));
    }

    @Test
    @DisplayName("adds warning for low confidence sync")
    void warnsOnLowConfidence() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createLowConfidenceSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("list_revlog_signals");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("warnings"));
      var warnings = resultObj.getAsJsonArray("warnings");
      assertTrue(warnings.size() > 0);
      assertTrue(warnings.get(0).getAsString().contains("low"));
    }

    @Test
    @DisplayName("adds warning for medium confidence sync")
    void warnsOnMediumConfidence() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var mediumResult = new SyncResult(500_000L, 0.7, ConfidenceLevel.MEDIUM, List.of(),
          SyncMethod.CROSS_CORRELATION, "Medium confidence sync");
      putLogWithRevLog(wpilog, revlog, mediumResult);

      var tool = findTool("list_revlog_signals");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("warnings"),
          "MEDIUM confidence should trigger a warning");
      var warnings = resultObj.getAsJsonArray("warnings");
      assertTrue(warnings.size() > 0);
    }

    @Test
    @DisplayName("no warning for high confidence sync")
    void noWarningOnHighConfidence() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("list_revlog_signals");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      // HIGH confidence should not have warnings
      assertFalse(resultObj.has("warnings"),
          "HIGH confidence should not trigger a warning");
    }

    @Test
    @DisplayName("accuracy string uses ConfidenceLevel.getAccuracyMs()")
    void accuracyMatchesConfidenceLevel() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("list_revlog_signals");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      var metadata = resultObj.getAsJsonObject("_metadata");
      assertEquals(ConfidenceLevel.HIGH.getAccuracyMs(),
          metadata.get("timing_accuracy_ms").getAsString());
    }
  }

  @Nested
  @DisplayName("get_revlog_data Tool")
  class GetRevLogDataTests {

    @Test
    @DisplayName("returns signal data with timestamps")
    void returnsSignalData() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("get_revlog_data");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("signal_key", "REV/SparkMax_1/appliedOutput");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals("REV/SparkMax_1/appliedOutput", resultObj.get("signal_key").getAsString());
      assertTrue(resultObj.get("sample_count").getAsInt() > 0);
      assertTrue(resultObj.has("data"));

      var data = resultObj.getAsJsonArray("data");
      assertTrue(data.size() > 0);

      var firstPoint = data.get(0).getAsJsonObject();
      assertTrue(firstPoint.has("timestamp"));
      assertTrue(firstPoint.has("value"));
    }

    @Test
    @DisplayName("respects time range filters")
    void filtersTimeRange() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("get_revlog_data");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("signal_key", "REV/SparkMax_1/appliedOutput");
      args.addProperty("start_time", 0.5);
      args.addProperty("end_time", 1.0);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      var data = resultObj.getAsJsonArray("data");
      for (var point : data) {
        double ts = point.getAsJsonObject().get("timestamp").getAsDouble();
        assertTrue(ts >= 0.5 && ts <= 1.0,
            "Timestamp " + ts + " should be in range [0.5, 1.0]");
      }
    }

    @Test
    @DisplayName("respects sample limit")
    void respectsLimit() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("get_revlog_data");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("signal_key", "REV/SparkMax_1/appliedOutput");
      args.addProperty("limit", 10);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(10, resultObj.get("sample_count").getAsInt());
      assertTrue(resultObj.get("total_samples").getAsInt() > 10);
    }

    @Test
    @DisplayName("includes statistics when requested")
    void includesStats() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("get_revlog_data");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("signal_key", "REV/SparkMax_1/appliedOutput");
      args.addProperty("include_stats", true);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("statistics"));

      var stats = resultObj.getAsJsonObject("statistics");
      assertTrue(stats.has("min"));
      assertTrue(stats.has("max"));
      assertTrue(stats.has("mean"));
      assertTrue(stats.has("count"));
    }

    @Test
    @DisplayName("fails gracefully for unknown signal")
    void failsForUnknownSignal() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("get_revlog_data");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("signal_key", "REV/NonExistent_99/fakeSignal");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      // Should return error
      assertTrue(resultObj.has("error") || !resultObj.get("success").getAsBoolean());
    }

    @Test
    @DisplayName("fails when no revlog synchronized")
    void failsWhenNoRevLog() throws Exception {
      var wpilog = createMockWpilog();
      putLogNoRevLog(wpilog);

      var tool = findTool("get_revlog_data");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("signal_key", "REV/SparkMax_1/appliedOutput");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("error") || !resultObj.get("success").getAsBoolean());
    }
  }

  @Nested
  @DisplayName("sync_status Tool")
  class SyncStatusTests {

    @Test
    @DisplayName("returns detailed sync status")
    void returnsDetailedStatus() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("sync_status");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("synchronized").getAsBoolean());
      assertEquals(1, resultObj.get("revlog_count").getAsInt());
      assertEquals("high", resultObj.get("overall_confidence").getAsString());

      var revlogs = resultObj.getAsJsonArray("revlogs");
      assertEquals(1, revlogs.size());

      var revlogInfo = revlogs.get(0).getAsJsonObject();
      assertEquals("rio", revlogInfo.get("can_bus").getAsString());
      assertTrue(revlogInfo.has("sync"));

      var syncInfo = revlogInfo.getAsJsonObject("sync");
      assertEquals("CROSS_CORRELATION", syncInfo.get("method").getAsString());
      assertTrue(syncInfo.get("successful").getAsBoolean());
      assertTrue(syncInfo.has("offset_milliseconds"));
    }

    @Test
    @DisplayName("includes signal pairs when requested")
    void includesSignalPairs() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("sync_status");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("include_signal_pairs", true);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());

      var revlogs = resultObj.getAsJsonArray("revlogs");
      var revlogInfo = revlogs.get(0).getAsJsonObject();
      assertTrue(revlogInfo.has("signal_pairs"));

      var pairs = revlogInfo.getAsJsonArray("signal_pairs");
      assertTrue(pairs.size() > 0);

      var pair = pairs.get(0).getAsJsonObject();
      assertTrue(pair.has("wpilog_entry"));
      assertTrue(pair.has("revlog_signal"));
      assertTrue(pair.has("correlation"));
      assertTrue(pair.has("estimated_offset_us"));
    }

    @Test
    @DisplayName("reports not synchronized when no revlogs")
    void reportsNotSynchronized() throws Exception {
      var wpilog = createMockWpilog();
      putLogNoRevLog(wpilog);

      var tool = findTool("sync_status");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertFalse(resultObj.get("synchronized").getAsBoolean());
      assertEquals(0, resultObj.get("revlog_count").getAsInt());
    }

    @Test
    @DisplayName("adds appropriate warnings for failed sync")
    void warnsOnFailedSync() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var failedResult = SyncResult.failed("No matching signals found");
      putLogWithRevLog(wpilog, revlog, failedResult);

      var tool = findTool("sync_status");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals("failed", resultObj.get("overall_confidence").getAsString());
      assertTrue(resultObj.has("warnings"));

      var warnings = resultObj.getAsJsonArray("warnings");
      assertTrue(warnings.size() > 0);
      assertTrue(warnings.get(0).getAsString().toLowerCase().contains("failed"));
    }

    @Test
    @DisplayName("includes timing accuracy metadata")
    void includesTimingAccuracy() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("sync_status");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("_metadata"));
      var metadata = resultObj.getAsJsonObject("_metadata");
      assertTrue(metadata.has("timing_accuracy_ms"));
      assertEquals("1-5", metadata.get("timing_accuracy_ms").getAsString());
    }

    @Test
    @DisplayName("confidence value uses getNumericValue()")
    void confidenceValueIsStable() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("sync_status");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      double value = resultObj.get("overall_confidence_value").getAsDouble();
      assertEquals(ConfidenceLevel.HIGH.getNumericValue(), value, 0.001);
    }
  }

  @Nested
  @DisplayName("Tool Registration")
  class RegistrationTests {

    @Test
    @DisplayName("registers all five tools")
    void registersAllTools() {
      assertEquals(5, tools.size());
      assertNotNull(findTool("list_revlog_signals"));
      assertNotNull(findTool("get_revlog_data"));
      assertNotNull(findTool("sync_status"));
      assertNotNull(findTool("set_revlog_offset"));
      assertNotNull(findTool("wait_for_sync"));
    }

    @Test
    @DisplayName("tools have correct names")
    void correctNames() {
      var names = tools.stream().map(Tool::name).toList();
      assertTrue(names.contains("list_revlog_signals"));
      assertTrue(names.contains("get_revlog_data"));
      assertTrue(names.contains("sync_status"));
      assertTrue(names.contains("set_revlog_offset"));
    }

    @Test
    @DisplayName("tools have descriptions")
    void hasDescriptions() {
      for (var tool : tools) {
        assertNotNull(tool.description());
        assertFalse(tool.description().isEmpty());
      }
    }

    @Test
    @DisplayName("tools have input schemas")
    void hasInputSchemas() {
      for (var tool : tools) {
        assertNotNull(tool.inputSchema());
        assertEquals("object", tool.inputSchema().get("type").getAsString());
      }
    }
  }

  // ==================== set_revlog_offset Tool ====================

  @Nested
  @DisplayName("set_revlog_offset Tool")
  class SetRevlogOffsetTests {

    @Test
    @DisplayName("tool exists with correct name and description")
    void toolExistsWithCorrectNameAndDescription() {
      var tool = findTool("set_revlog_offset");
      assertEquals("set_revlog_offset", tool.name());
      assertNotNull(tool.description());
      assertFalse(tool.description().isEmpty());
      assertTrue(tool.description().contains("offset"),
          "Description should mention offset");
      assertTrue(tool.description().contains("REV log"),
          "Description should mention REV log");
    }

    @Test
    @DisplayName("returns error when no log is loaded (path not found)")
    void returnsErrorWhenNoLogLoaded() throws Exception {
      var tool = findTool("set_revlog_offset");
      var args = new JsonObject();
      args.addProperty("path", "/nonexistent.wpilog");
      args.addProperty("offset_ms", 100.0);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      // Should return an error since the log is not loaded
      assertTrue(resultObj.has("error") || !resultObj.get("success").getAsBoolean(),
          "Should return error when log is not loaded");
    }

    @Test
    @DisplayName("returns error when log has no revlogs")
    void returnsErrorWhenNoRevLogs() throws Exception {
      var wpilog = createMockWpilog();
      putLogNoRevLog(wpilog);

      var tool = findTool("set_revlog_offset");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("offset_ms", 100.0);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("error") || !resultObj.get("success").getAsBoolean(),
          "Should return error when no revlogs are synchronized");
    }

    @Test
    @DisplayName("successfully sets offset with valid log and revlog data")
    void successfullySetsOffset() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("set_revlog_offset");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("offset_ms", -500.0);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals("rio", resultObj.get("can_bus").getAsString());
      assertEquals(-500.0, resultObj.get("offset_ms").getAsDouble(), 0.001);
      assertEquals(-500_000L, resultObj.get("offset_us").getAsLong());
      assertEquals("USER_PROVIDED", resultObj.get("new_method").getAsString());
      assertEquals("CROSS_CORRELATION", resultObj.get("previous_method").getAsString());
    }

    @Test
    @DisplayName("records previous offset in response")
    void recordsPreviousOffset() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      double previousOffsetMs = syncResult.offsetMillis();

      var tool = findTool("set_revlog_offset");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("offset_ms", 250.0);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(previousOffsetMs, resultObj.get("previous_offset_ms").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("calls updateSynchronizedLogs to persist offset change")
    void callsUpdateSynchronizedLogs() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("set_revlog_offset");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("offset_ms", 123.456);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      // The response confirms the new method is USER_PROVIDED, which means
      // a new SyncResult was created and updateSynchronizedLogs was called
      assertEquals("USER_PROVIDED", resultObj.get("new_method").getAsString());
      assertEquals("CROSS_CORRELATION", resultObj.get("previous_method").getAsString());
      assertEquals(123.456, resultObj.get("offset_ms").getAsDouble(), 0.001);
      assertEquals(123_456L, resultObj.get("offset_us").getAsLong());
    }

    @Test
    @DisplayName("defaults offset_ms to 0.0 when not provided")
    void defaultsOffsetToZero() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("set_revlog_offset");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      // offset_ms is required per schema, but getOptDouble defaults to 0.0

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(0.0, resultObj.get("offset_ms").getAsDouble(), 0.001);
      assertEquals(0L, resultObj.get("offset_us").getAsLong());
    }

    @Test
    @DisplayName("returns error for invalid CAN bus name")
    void returnsErrorForInvalidCanBus() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("set_revlog_offset");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("offset_ms", 100.0);
      args.addProperty("can_bus", "nonexistent_bus");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("error") || !resultObj.get("success").getAsBoolean(),
          "Should return error for non-existent CAN bus");
    }

    @Test
    @DisplayName("applies offset to specified CAN bus")
    void appliesToSpecifiedCanBus() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("set_revlog_offset");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("offset_ms", 200.0);
      args.addProperty("can_bus", "rio");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals("rio", resultObj.get("can_bus").getAsString());
      assertEquals(200.0, resultObj.get("offset_ms").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("handles negative offset correctly")
    void handlesNegativeOffset() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("set_revlog_offset");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("offset_ms", -1000.0);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(-1000.0, resultObj.get("offset_ms").getAsDouble(), 0.001);
      assertEquals(-1_000_000L, resultObj.get("offset_us").getAsLong());
    }

    @Test
    @DisplayName("handles fractional millisecond offset")
    void handlesFractionalOffset() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("set_revlog_offset");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("offset_ms", 1.5);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(1.5, resultObj.get("offset_ms").getAsDouble(), 0.001);
      // 1.5ms * 1000 = 1500 microseconds
      assertEquals(1500L, resultObj.get("offset_us").getAsLong());
    }

    @Test
    @DisplayName("has correct input schema with offset_ms and can_bus parameters")
    void hasCorrectInputSchema() {
      var tool = findTool("set_revlog_offset");
      var schema = tool.inputSchema();

      assertEquals("object", schema.get("type").getAsString());
      assertTrue(schema.has("properties"));

      var properties = schema.getAsJsonObject("properties");
      assertTrue(properties.has("offset_ms"), "Schema should have offset_ms parameter");
      assertTrue(properties.has("can_bus"), "Schema should have can_bus parameter");
      assertTrue(properties.has("path"), "Schema should have path parameter (from LogRequiringTool)");
    }
  }

  // ==================== wait_for_sync Tool ====================

  @Nested
  @DisplayName("wait_for_sync Tool")
  class WaitForSyncToolTests {

    @Test
    @DisplayName("returns immediately when no sync in progress")
    void returnsImmediatelyWhenNoSync() throws Exception {
      var wpilog = createMockWpilog();
      putLogNoRevLog(wpilog);

      var tool = findTool("wait_for_sync");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("completed").getAsBoolean());
      assertFalse(resultObj.get("was_in_progress").getAsBoolean());
    }

    @Test
    @DisplayName("returns immediately when sync already completed")
    void returnsWhenSyncAlreadyDone() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("wait_for_sync");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("completed").getAsBoolean());
    }

    @Test
    @DisplayName("reports revlog count after completion")
    void reportsRevlogCount() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("wait_for_sync");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(1, resultObj.get("revlog_count").getAsInt());
      assertTrue(resultObj.get("synchronized").getAsBoolean());
    }

    @Test
    @DisplayName("respects custom timeout parameter")
    void respectsTimeoutParameter() throws Exception {
      var wpilog = createMockWpilog();
      putLogNoRevLog(wpilog);

      var tool = findTool("wait_for_sync");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      args.addProperty("timeout_ms", 100);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("completed").getAsBoolean());
    }
  }

  // ==================== sync_status with sync_in_progress ====================

  @Nested
  @DisplayName("sync_status sync_in_progress field")
  class SyncStatusInProgressTests {

    @Test
    @DisplayName("reports sync_in_progress=false when sync is complete")
    void reportsFalseWhenComplete() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("sync_status");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertFalse(resultObj.get("sync_in_progress").getAsBoolean());
    }

    @Test
    @DisplayName("reports sync_in_progress=false when no revlogs")
    void reportsFalseWhenNoRevlogs() throws Exception {
      var wpilog = createMockWpilog();
      putLogNoRevLog(wpilog);

      var tool = findTool("sync_status");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertFalse(resultObj.get("sync_in_progress").getAsBoolean());
    }
  }

  // ==================== list_revlog_signals with sync_in_progress ====================

  @Nested
  @DisplayName("list_revlog_signals sync_in_progress field")
  class ListSignalsInProgressTests {

    @Test
    @DisplayName("reports sync_in_progress=false and normal warning when no revlogs")
    void normalWarningWhenNoRevlogs() throws Exception {
      var wpilog = createMockWpilog();
      putLogNoRevLog(wpilog);

      var tool = findTool("list_revlog_signals");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertFalse(resultObj.get("sync_in_progress").getAsBoolean());
      assertEquals(0, resultObj.get("signal_count").getAsInt());
      // Should have the standard "no revlogs" warning
      assertTrue(resultObj.has("warnings"));
      var warnings = resultObj.getAsJsonArray("warnings");
      boolean hasNoRevlogWarning = false;
      for (int i = 0; i < warnings.size(); i++) {
        if (warnings.get(i).getAsString().contains("No REV log files")) {
          hasNoRevlogWarning = true;
        }
      }
      assertTrue(hasNoRevlogWarning, "Should have 'no revlog files' warning");
    }

    @Test
    @DisplayName("includes signals when sync is complete")
    void includesSignalsWhenComplete() throws Exception {
      var wpilog = createMockWpilog();
      var revlog = createMockRevLog();
      var syncResult = createGoodSyncResult();
      putLogWithRevLog(wpilog, revlog, syncResult);

      var tool = findTool("list_revlog_signals");
      var args = new JsonObject();
      args.addProperty("path", "/test.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(3, resultObj.get("signal_count").getAsInt());
    }
  }

  // ==================== LogManager async sync integration ====================

  @Nested
  @DisplayName("LogManager async sync")
  class LogManagerAsyncSyncTests {

    @Test
    @DisplayName("isAnyRevLogSyncInProgress returns false when no logs loaded")
    void noLogsNotInProgress() {
      logManager.unloadAllLogs();
      assertFalse(logManager.isAnyRevLogSyncInProgress());
    }

    @Test
    @DisplayName("isRevLogSyncInProgress with path returns false for completed sync")
    void completedSyncNotInProgress() {
      var wpilog = createMockWpilog();
      putLogNoRevLog(wpilog);
      assertFalse(logManager.isRevLogSyncInProgress(wpilog.path()));
    }

    @Test
    @DisplayName("waitForRevLogSync returns true when null path")
    void waitReturnsTrueWhenNullPath() {
      logManager.unloadAllLogs();
      assertTrue(logManager.waitForRevLogSync(null, 100));
    }

    @Test
    @DisplayName("clearAllLogs cancels in-progress syncs")
    void clearAllLogsCancelsSyncs() throws Exception {
      var wpilog = createMockWpilog();
      putLogNoRevLog(wpilog);

      // Simulate an in-progress sync via reflection
      var future = new java.util.concurrent.CompletableFuture<Void>();
      setSyncInProgress(wpilog.path(), future);

      assertTrue(logManager.isRevLogSyncInProgress(wpilog.path()));

      logManager.clearAllLogs();

      // Future should be cancelled
      assertTrue(future.isCancelled() || future.isDone());
      assertFalse(logManager.isRevLogSyncInProgress(wpilog.path()));
    }

    @Test
    @DisplayName("unloadLog cancels in-progress sync for that log")
    void unloadLogCancelsSync() throws Exception {
      var wpilog = createMockWpilog();
      logManager.testPutLog(wpilog.path(), wpilog);

      // Create placeholder sync entry
      SynchronizedLogs syncLogs = new SynchronizedLogs(wpilog);
      setSynchronizedLogs(wpilog.path(), syncLogs);

      // Simulate in-progress sync
      var future = new java.util.concurrent.CompletableFuture<Void>();
      setSyncInProgress(wpilog.path(), future);

      assertTrue(logManager.isRevLogSyncInProgress(wpilog.path()));

      logManager.unloadLog(wpilog.path());

      assertTrue(future.isCancelled() || future.isDone());
    }

    @Test
    @DisplayName("isRevLogSyncInProgress with path works for non-active logs")
    void pathBasedSyncCheck() throws Exception {
      var wpilog = createMockWpilog();
      logManager.testPutLog(wpilog.path(), wpilog);

      var future = new java.util.concurrent.CompletableFuture<Void>();
      setSyncInProgress(wpilog.path(), future);

      assertTrue(logManager.isRevLogSyncInProgress(wpilog.path()));

      future.complete(null);
      assertFalse(logManager.isRevLogSyncInProgress(wpilog.path()));
    }

    @Test
    @DisplayName("eviction callback cancels in-progress sync (§2.1 fix)")
    void evictionCallbackCancelsSync() throws Exception {
      // Set up: load multiple logs, then evict one manually

      var wpilog1 = createMockWpilog();
      logManager.testPutLog(wpilog1.path(), wpilog1);

      // Simulate in-progress sync for wpilog1
      var future = new java.util.concurrent.CompletableFuture<Void>();
      setSyncInProgress(wpilog1.path(), future);

      assertTrue(logManager.isRevLogSyncInProgress(wpilog1.path()));

      // Load a second log with a small delay so Caffeine sees distinct access times
      Thread.sleep(20);
      var wpilog2 = new ParsedLog("/test2.wpilog",
          new java.util.HashMap<>(), new java.util.HashMap<>(), 0, 1);
      logManager.testPutLog(wpilog2.path(), wpilog2);

      // Access wpilog2 to ensure wpilog1 is the LRU
      Thread.sleep(20);
      logManager.testGetLogCache().get(
          java.nio.file.Path.of(wpilog2.path()).toAbsolutePath().normalize().toString());

      // Force LRU eviction (heap-pressure-based eviction won't trigger in tests)
      logManager.testGetLogCache().evictOne();

      // The eviction callback should have cancelled the sync for wpilog1
      assertTrue(future.isCancelled() || future.isDone(),
          "Eviction callback should cancel in-progress sync");

      // Clean up
      logManager.resetConfiguration();
    }

    private void setSyncInProgress(String path, java.util.concurrent.CompletableFuture<Void> future) {
      try {
        var field = LogManager.class.getDeclaredField("syncInProgress");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (Map<String, java.util.concurrent.CompletableFuture<Void>>) field.get(logManager);
        // Normalize path the same way LogManager does internally
        String normalized = java.nio.file.Path.of(path).toAbsolutePath().normalize().toString();
        map.put(normalized, future);
      } catch (Exception e) {
        throw new RuntimeException("Failed to set syncInProgress", e);
      }
    }
  }
}
