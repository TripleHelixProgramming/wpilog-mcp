package org.triplehelix.wpilogmcp.tools;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;
import org.triplehelix.wpilogmcp.sync.ConfidenceLevel;
import org.triplehelix.wpilogmcp.sync.SyncResult;
import org.triplehelix.wpilogmcp.sync.SynchronizedLogs;
import org.triplehelix.wpilogmcp.sync.SynchronizedLogs.SyncedRevLog;

/**
 * MCP tools for accessing REV log (.revlog) data synchronized with wpilog files.
 *
 * <p>Tools included:
 * <ul>
 *   <li>{@code list_revlog_signals} - List available REV signals with sync status</li>
 *   <li>{@code get_revlog_data} - Query REV signal data with FPGA timestamps</li>
 *   <li>{@code sync_status} - Get synchronization confidence and details</li>
 * </ul>
 *
 * <p>All timestamps returned from these tools are converted to FPGA time using the
 * synchronization offset computed when the revlog was loaded. The accuracy of these
 * timestamps depends on the synchronization confidence level.
 *
 * @since 0.5.0
 */
public final class RevLogTools {

  private RevLogTools() {}

  /**
   * Registers all RevLog tools with the MCP server.
   *
   * @param server The MCP server to register tools with
   */
  public static void registerAll(ToolRegistry registry) {
    registry.registerTool(new ListRevLogSignalsTool());
    registry.registerTool(new GetRevLogDataTool());
    registry.registerTool(new SyncStatusTool());
    registry.registerTool(new SetRevLogOffsetTool());
    registry.registerTool(new WaitForSyncTool());
  }

  /**
   * Lists all available signals from synchronized REV logs.
   */
  static class ListRevLogSignalsTool extends LogRequiringTool {

    @Override
    public String name() {
      return "list_revlog_signals";
    }

    @Override
    public String description() {
      return "List all available signals from synchronized REV log files. "
          + "REV logs contain CAN bus data from SPARK MAX/Flex motor controllers. "
          + "Signals are automatically synchronized with wpilog timestamps when loaded. "
          + "IMPORTANT: Check sync_confidence to understand timestamp accuracy.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty(
              "device_filter",
              "string",
              "Filter signals by device key (e.g., 'SparkMax_1')",
              false)
          .addProperty(
              "signal_filter",
              "string",
              "Filter signals by signal name substring (e.g., 'velocity')",
              false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {
      SynchronizedLogs syncLogs = logManager.getSynchronizedLogs();
      boolean syncInProgress = logManager.isRevLogSyncInProgress();

      if (syncLogs == null || syncLogs.revlogCount() == 0) {
        var response = success()
            .addProperty("signal_count", 0)
            .addData("signals", new JsonArray())
            .addProperty("revlog_count", 0)
            .addProperty("sync_in_progress", syncInProgress);
        if (syncInProgress) {
          response.addWarning("RevLog synchronization is in progress. "
              + "Call list_revlog_signals again in a moment to see available signals.");
        } else {
          response.addWarning("No REV log files are synchronized with the active wpilog. "
              + "Place .revlog files in the same directory as the .wpilog file to enable auto-sync.");
        }
        return response.build();
      }

      String deviceFilter = getOptString(arguments, "device_filter", null);
      String signalFilter = getOptString(arguments, "signal_filter", null);

      JsonArray signals = new JsonArray();
      int totalSignals = 0;

      for (SyncedRevLog synced : syncLogs.revlogs()) {
        String busName = synced.canBusName();
        SyncResult result = synced.syncResult();
        String confidence = result.confidenceLevel().getLabel();
        boolean multipleRevLogs = syncLogs.revlogCount() > 1;

        for (RevLogSignal signal : synced.revlog().signals().values()) {
          // Apply device filter
          if (deviceFilter != null
              && !signal.deviceKey().toLowerCase().contains(deviceFilter.toLowerCase())) {
            continue;
          }

          // Apply signal filter
          if (signalFilter != null
              && !signal.name().toLowerCase().contains(signalFilter.toLowerCase())) {
            continue;
          }

          JsonObject signalObj = new JsonObject();

          // Build signal key based on whether multiple revlogs are present
          String signalKey = multipleRevLogs
              ? "REV/" + busName + "/" + signal.fullKey()
              : "REV/" + signal.fullKey();

          signalObj.addProperty("key", signalKey);
          signalObj.addProperty("device", signal.deviceKey());
          signalObj.addProperty("signal", signal.name());
          signalObj.addProperty("unit", signal.unit());
          signalObj.addProperty("sample_count", signal.values().size());
          signalObj.addProperty("can_bus", busName);
          signalObj.addProperty("sync_confidence", confidence);

          signals.add(signalObj);
          totalSignals++;
        }
      }

      ConfidenceLevel overall = syncLogs.overallConfidence();
      String accuracyEstimate = overall.getAccuracyMs();

      ResponseBuilder response = success()
          .addProperty("signal_count", totalSignals)
          .addData("signals", signals)
          .addProperty("revlog_count", syncLogs.revlogCount())
          .addProperty("overall_sync_confidence", overall.getLabel())
          .addMetadata("timing_accuracy_ms", accuracyEstimate);

      // Add warning for anything below HIGH confidence
      if (overall.ordinal() >= ConfidenceLevel.MEDIUM.ordinal()) {
        response.addWarning(
            "REV log timestamps are synchronized via statistical correlation "
                + "(confidence: " + overall.getLabel() + "). "
                + "Timing accuracy: ~" + accuracyEstimate + "ms. "
                + "Use with caution for precise timing analysis.");
      }

      return response.build();
    }
  }

  /**
   * Gets data from a REV log signal with timestamps converted to FPGA time.
   */
  static class GetRevLogDataTool extends LogRequiringTool {

    @Override
    public String name() {
      return "get_revlog_data";
    }

    @Override
    public String description() {
      return "Get data from a REV log signal with timestamps converted to FPGA time. "
          + "Use list_revlog_signals first to discover available signal keys. "
          + "IMPORTANT: Timestamps are synchronized via statistical correlation and "
          + "may have limited accuracy depending on sync confidence level.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty(
              "signal_key",
              "string",
              "Signal key (e.g., 'REV/SparkMax_1/appliedOutput' or 'REV/rio/SparkMax_1/velocity')",
              true)
          .addNumberProperty(
              "start_time",
              "Start timestamp in seconds (FPGA time)",
              false,
              null)
          .addNumberProperty(
              "end_time",
              "End timestamp in seconds (FPGA time)",
              false,
              null)
          .addIntegerProperty(
              "limit",
              "Maximum number of samples to return",
              false,
              1000)
          .addProperty(
              "include_stats",
              "boolean",
              "Include basic statistics (min, max, mean)",
              false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {
      SynchronizedLogs syncLogs = logManager.getSynchronizedLogs();

      if (syncLogs == null || syncLogs.revlogCount() == 0) {
        throw new IllegalArgumentException(
            "No REV log files are synchronized. Place .revlog files in the same directory "
                + "as the .wpilog file to enable auto-sync.");
      }

      String signalKey = getRequiredString(arguments, "signal_key");
      Double startTime = getOptDouble(arguments, "start_time");
      Double endTime = getOptDouble(arguments, "end_time");
      int limit = getOptInt(arguments, "limit", 1000);
      boolean includeStats = arguments.has("include_stats")
          && arguments.get("include_stats").getAsBoolean();

      List<TimestampedValue> values = syncLogs.getValues(signalKey);
      if (values == null) {
        throw new IllegalArgumentException(
            "Signal not found: " + signalKey + ". Use list_revlog_signals to see available signals.");
      }

      // Filter by time range
      List<TimestampedValue> filtered = filterTimeRange(values, startTime, endTime);

      // Apply limit
      int totalCount = filtered.size();
      if (filtered.size() > limit) {
        filtered = filtered.subList(0, limit);
      }

      // Build data array
      JsonArray dataArray = new JsonArray();
      for (TimestampedValue tv : filtered) {
        JsonObject point = new JsonObject();
        point.addProperty("timestamp", tv.timestamp());
        if (tv.value() instanceof Number) {
          point.addProperty("value", ((Number) tv.value()).doubleValue());
        } else {
          point.addProperty("value", String.valueOf(tv.value()));
        }
        dataArray.add(point);
      }

      // Get sync confidence for this signal
      ConfidenceLevel confidence = syncLogs.overallConfidence();
      String accuracyEstimate = confidence.getAccuracyMs();

      ResponseBuilder response = success()
          .addProperty("signal_key", signalKey)
          .addProperty("sample_count", dataArray.size())
          .addProperty("total_samples", totalCount)
          .addData("data", dataArray)
          .addProperty("sync_confidence", confidence.getLabel())
          .addMetadata("timing_accuracy_ms", accuracyEstimate);

      // Calculate statistics if requested
      if (includeStats && !filtered.isEmpty()) {
        double[] numericData = extractNumericData(filtered);
        if (numericData.length > 0) {
          double sum = 0, min = Double.MAX_VALUE, max = Double.NEGATIVE_INFINITY;
          for (double d : numericData) {
            sum += d;
            min = Math.min(min, d);
            max = Math.max(max, d);
          }
          double mean = sum / numericData.length;

          JsonObject stats = new JsonObject();
          stats.addProperty("min", min);
          stats.addProperty("max", max);
          stats.addProperty("mean", mean);
          stats.addProperty("count", numericData.length);
          response.addData("statistics", stats);
        }
      }

      // Add warning for anything below HIGH confidence
      if (confidence.ordinal() >= ConfidenceLevel.MEDIUM.ordinal()) {
        response.addWarning(
            "Timestamps synchronized via correlation (confidence: " + confidence.getLabel() + "). "
                + "Timing accuracy: ~" + accuracyEstimate + "ms.");
      }

      return response.build();
    }
  }

  /**
   * Gets detailed synchronization status for all synchronized REV logs.
   */
  static class SyncStatusTool extends LogRequiringTool {

    @Override
    public String name() {
      return "sync_status";
    }

    @Override
    public String description() {
      return "Get detailed synchronization status for all synchronized REV log files. "
          + "Shows confidence levels, timing offsets, and the signal pairs used for "
          + "synchronization. Use this to understand the accuracy of REV log timestamps.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty(
              "include_signal_pairs",
              "boolean",
              "Include details about which signal pairs were used for correlation",
              false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {
      boolean syncInProgress = logManager.isRevLogSyncInProgress();
      SynchronizedLogs syncLogs = logManager.getSynchronizedLogs();

      if (syncLogs == null) {
        return success()
            .addProperty("synchronized", false)
            .addProperty("revlog_count", 0)
            .addProperty("sync_in_progress", syncInProgress)
            .addWarning("No synchronization data available. "
                + "Load a wpilog file with .revlog files in the same directory.")
            .build();
      }

      boolean includeSignalPairs = arguments.has("include_signal_pairs")
          && arguments.get("include_signal_pairs").getAsBoolean();

      JsonArray revlogsArray = new JsonArray();

      for (SyncedRevLog synced : syncLogs.revlogs()) {
        SyncResult result = synced.syncResult();

        JsonObject revlogInfo = new JsonObject();
        revlogInfo.addProperty("can_bus", synced.canBusName());
        revlogInfo.addProperty("path", synced.revlog().path());
        revlogInfo.addProperty("device_count", synced.revlog().devices().size());
        revlogInfo.addProperty("signal_count", synced.revlog().signals().size());

        // Sync details
        JsonObject syncInfo = new JsonObject();
        syncInfo.addProperty("method", result.method().name());
        syncInfo.addProperty("confidence", result.confidence());
        syncInfo.addProperty("confidence_level", result.confidenceLevel().getLabel());
        syncInfo.addProperty("offset_microseconds", result.offsetMicros());
        syncInfo.addProperty("offset_milliseconds", result.offsetMillis());
        syncInfo.addProperty("offset_seconds", result.offsetSeconds());
        syncInfo.addProperty("explanation", result.explanation());
        syncInfo.addProperty("successful", result.isSuccessful());
        if (result.driftRateNanosPerSec() != 0.0) {
          syncInfo.addProperty("drift_rate_ns_per_sec", result.driftRateNanosPerSec());
          syncInfo.addProperty("drift_rate_ms_per_hour",
              result.driftRateNanosPerSec() * 3.6);
          syncInfo.addProperty("reference_time_sec", result.referenceTimeSec());
        }

        revlogInfo.add("sync", syncInfo);

        // Include signal pairs if requested
        if (includeSignalPairs && !result.signalPairs().isEmpty()) {
          JsonArray pairs = new JsonArray();
          for (var pair : result.signalPairs()) {
            JsonObject pairObj = new JsonObject();
            pairObj.addProperty("wpilog_entry", pair.wpilogEntry());
            pairObj.addProperty("revlog_signal", pair.revlogSignal());
            pairObj.addProperty("correlation", pair.correlation());
            pairObj.addProperty("estimated_offset_us", pair.estimatedOffsetMicros());
            pairObj.addProperty("samples_used", pair.samplesUsed());
            pairs.add(pairObj);
          }
          revlogInfo.add("signal_pairs", pairs);
        }

        revlogsArray.add(revlogInfo);
      }

      ConfidenceLevel overall = syncLogs.overallConfidence();
      String accuracyEstimate = overall.getAccuracyMs();

      ResponseBuilder response = success()
          .addProperty("synchronized", syncLogs.hasAnySynchronized())
          .addProperty("revlog_count", syncLogs.revlogCount())
          .addProperty("sync_in_progress", syncInProgress)
          .addProperty("overall_confidence", overall.getLabel())
          .addProperty("overall_confidence_value", overall.getNumericValue())
          .addData("revlogs", revlogsArray)
          .addMetadata("timing_accuracy_ms", accuracyEstimate)
          .addMetadata("confidence_description", overall.getDescription());

      if (syncInProgress) {
        response.addWarning(
            "RevLog synchronization is still in progress. Results may be incomplete. "
            + "Call sync_status again in a moment, or use wait_for_sync to block.");
      }

      // Add appropriate warnings based on confidence
      if (overall == ConfidenceLevel.FAILED) {
        response.addWarning(
            "Synchronization failed. REV log timestamps cannot be reliably correlated "
                + "with wpilog timestamps. Consider providing CAN ID hints or checking "
                + "that both logs were recorded during the same time period.");
      } else if (overall == ConfidenceLevel.LOW) {
        response.addWarning(
            "Low synchronization confidence. Timestamps may be inaccurate by several "
                + "hundred milliseconds. Use with caution for timing-sensitive analysis.");
      } else if (overall == ConfidenceLevel.MEDIUM) {
        response.addWarning(
            "Medium synchronization confidence. Timestamps are approximate "
                + "(accuracy: ~" + accuracyEstimate + "ms).");
      }

      return response.build();
    }
  }

  /**
   * Manually sets the synchronization offset for a REV log, overriding automatic sync.
   * Use this when automatic synchronization fails or produces incorrect results.
   */
  static class SetRevLogOffsetTool extends LogRequiringTool {

    @Override
    public String name() {
      return "set_revlog_offset";
    }

    @Override
    public String description() {
      return "Manually set the synchronization offset for a REV log file. "
          + "Use this when automatic synchronization fails or when you know the exact "
          + "offset between revlog and wpilog timestamps. The offset is added to revlog "
          + "timestamps to convert them to FPGA time. "
          + "Example: if a revlog event appears 0.5s after the same event in wpilog, "
          + "set offset_ms to -500.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addNumberProperty(
              "offset_ms",
              "Time offset in milliseconds to add to revlog timestamps to get FPGA time",
              true,
              null)
          .addProperty(
              "can_bus",
              "string",
              "CAN bus name to apply offset to (e.g., 'rio'). "
                  + "If omitted, applies to the first/only revlog.",
              false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {
      SynchronizedLogs syncLogs = logManager.getSynchronizedLogs();

      if (syncLogs == null || syncLogs.revlogCount() == 0) {
        throw new IllegalArgumentException(
            "No REV log files are loaded. Place .revlog files in the same directory "
                + "as the .wpilog file and reload, or use load_log to trigger auto-sync.");
      }

      double offsetMs = getOptDouble(arguments, "offset_ms", 0.0);
      long offsetMicros = Math.round(offsetMs * 1000.0);
      String canBus = getOptString(arguments, "can_bus", null);

      // Find the target revlog
      SyncedRevLog target = null;
      for (SyncedRevLog synced : syncLogs.revlogs()) {
        if (canBus == null || synced.canBusName().equals(canBus)) {
          target = synced;
          break;
        }
      }

      if (target == null) {
        throw new IllegalArgumentException(
            "CAN bus not found: " + canBus + ". Available buses: "
                + syncLogs.revlogs().stream()
                    .map(SyncedRevLog::canBusName)
                    .toList());
      }

      // Create new SyncResult with user-provided offset
      SyncResult userResult = SyncResult.fromUserOffset(offsetMicros);

      // Rebuild SynchronizedLogs with the updated offset
      SynchronizedLogs.Builder builder = new SynchronizedLogs.Builder().wpilog(syncLogs.wpilog());
      for (SyncedRevLog synced : syncLogs.revlogs()) {
        if (synced == target) {
          builder.addRevLog(synced.revlog(), userResult, synced.canBusName());
        } else {
          builder.addRevLog(synced.revlog(), synced.syncResult(), synced.canBusName());
        }
      }

      // Update the sync cache via LogManager
      // The syncCache in LogManager is a ConcurrentHashMap keyed by wpilog path
      logManager.updateSynchronizedLogs(log.path(), builder.build());

      return success()
          .addProperty("can_bus", target.canBusName())
          .addProperty("offset_ms", offsetMs)
          .addProperty("offset_us", offsetMicros)
          .addProperty("previous_offset_ms", target.syncResult().offsetMillis())
          .addProperty("previous_method", target.syncResult().method().name())
          .addProperty("new_method", "USER_PROVIDED")
          .build();
    }
  }

  /**
   * Waits for background revlog synchronization to complete.
   *
   * <p>RevLog synchronization runs asynchronously after loading a wpilog file.
   * This tool blocks until synchronization is finished, so subsequent calls to
   * {@code list_revlog_signals} or {@code get_revlog_data} return complete data.
   */
  static class WaitForSyncTool extends LogRequiringTool {

    @Override
    public String name() {
      return "wait_for_sync";
    }

    @Override
    public String description() {
      return "Wait for background RevLog synchronization to complete. "
          + "Call this after load_log if you need revlog data immediately. "
          + "Returns instantly if sync is already done or no revlogs are present.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addIntegerProperty("timeout_ms",
              "Maximum time to wait in milliseconds (default: 30000)", false, 30000)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {
      int timeoutMs = getOptInt(arguments, "timeout_ms", 30000);

      boolean wasInProgress = logManager.isRevLogSyncInProgress();
      boolean completed = logManager.waitForRevLogSync(timeoutMs);

      var response = success()
          .addProperty("completed", completed)
          .addProperty("was_in_progress", wasInProgress);

      if (!completed) {
        response.addWarning("RevLog synchronization did not complete within "
            + timeoutMs + "ms. Try again with a longer timeout.");
      }

      // Include current sync status summary
      var syncLogs = logManager.getSynchronizedLogs();
      if (syncLogs != null) {
        response.addProperty("revlog_count", syncLogs.revlogCount());
        response.addProperty("synchronized", syncLogs.hasAnySynchronized());
      }

      return response.build();
    }
  }
}
