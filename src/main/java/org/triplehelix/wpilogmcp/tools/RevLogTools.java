/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;
import org.triplehelix.wpilogmcp.sync.ConfidenceLevel;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
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
 *   <li>{@code set_revlog_offset} - Manually set revlog timestamp offset</li>
 *   <li>{@code wait_for_sync} - Wait for background sync to complete</li>
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

  /** Longest wait_for_sync accepts, so a call cannot hold a worker thread indefinitely. */
  static final int MAX_WAIT_MS = 120_000;

  /**
   * The result for a wpilog with no synchronized revlog: not applicable, with the same reason from
   * every revlog tool (or, while synchronization is still running, a pointer to wait_for_sync).
   */
  static ResponseBuilder noRevlogs(LogData log, boolean syncInProgress) {
    if (syncInProgress) {
      return ResponseBuilder.notApplicable("REV log synchronization for this wpilog is still in "
          + "progress, so no revlog signals are available yet.")
          .hint("Call wait_for_sync, then try again.");
    }
    var unconfirmed = org.triplehelix.wpilogmcp.log.WallClock.unconfirmedReason(log);
    if (unconfirmed.isPresent()) {
      return ResponseBuilder.notApplicable(unconfirmed.get());
    }
    return ResponseBuilder.notApplicable("No REV log (.revlog) files were found for this wpilog.")
        .hint("Revlogs are discovered in the configured log directory tree by recording time: a "
            + ".revlog whose time range overlaps this wpilog's is synchronized with it when the "
            + "wpilog is loaded.");
  }

  /**
   * The timing accuracy a sync result can claim: the confidence level's range for a
   * correlation or a wall-clock estimate; unknown for a user offset (its accuracy is the user's)
   * and for a failed sync.
   */
  static String accuracyOf(SyncResult result) {
    return switch (result.method()) {
      case USER_PROVIDED, FAILED -> "unknown";
      default -> result.confidenceLevel().getAccuracyMs();
    };
  }

  /**
   * How a revlog's timestamps were aligned, as a warning when that bounds their accuracy: null
   * for a high-confidence cross-correlation, otherwise a sentence that names the method.
   */
  static String alignmentWarning(SyncedRevLog synced) {
    var result = synced.syncResult();
    var bus = synced.canBusName();
    return switch (result.method()) {
      case CROSS_CORRELATION -> result.confidenceLevel() == ConfidenceLevel.HIGH ? null
          : String.format("REV log '%s': timestamps aligned by cross-correlation at %s "
              + "confidence (accuracy about %s ms); sync_status has the signal pairs.", bus,
              result.confidenceLevel().getLabel(), result.confidenceLevel().getAccuracyMs());
      case SYSTEM_TIME_ONLY -> String.format("REV log '%s': timestamps aligned by the wall-clock "
          + "estimate only (the REV log's filename time against the wpilog's wall clock; no "
          + "signal pair correlated), which can be off by seconds or more. set_revlog_offset "
          + "sets a known offset.", bus);
      case USER_PROVIDED -> String.format("REV log '%s': timestamps aligned by a user-provided "
          + "offset of %.1f ms; the server cannot judge its accuracy.", bus,
          result.offsetMillis());
      case FAILED -> String.format("REV log '%s' was not synchronized (%s): its timestamps are "
          + "on the REV log's own clock, and get_revlog_data returns not_applicable for its "
          + "signals until set_revlog_offset provides an offset.", bus, result.explanation());
    };
  }

  /** The buses and paths of the revlogs, for results that cannot use them. */
  static JsonArray revlogsJson(SynchronizedLogs syncLogs) {
    var array = new JsonArray();
    for (SyncedRevLog synced : syncLogs.revlogs()) {
      var o = new JsonObject();
      o.addProperty("can_bus", synced.canBusName());
      o.addProperty("path", synced.revlog().path());
      o.addProperty("sync_method", synced.syncResult().method().name());
      array.add(o);
    }
    return array;
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
          + "REV logs contain CAN bus data from SPARK MAX/Flex motor controllers "
          + "(firmware 25+ status frames, decoded per REV's published specification): "
          + "AppliedOutput (duty cycle), BusVoltage, OutputCurrent, MotorTemperature, limit "
          + "and IsInverted flags; faults, warnings, and their sticky versions as 0/1 signals "
          + "(e.g. BrownoutWarning, StallStickyWarning); Velocity and Position (RPM and "
          + "rotations unless a conversion factor is configured on the SPARK: compare with "
          + "the robot code's own entries before assuming a unit); and other sensors when "
          + "their frames were logged. Signals are automatically synchronized with wpilog "
          + "timestamps when loaded; each carries sync_method (CROSS_CORRELATION, "
          + "SYSTEM_TIME_ONLY, USER_PROVIDED, or FAILED), timestamps_aligned, offset_seconds, "
          + "and sync_confidence, and a warning says how a bus was aligned when that bounds its "
          + "accuracy. Returns not_applicable when no REV log could be synchronized (with the "
          + "buses, for set_revlog_offset) and no_match when the filters match no signal.";
    }

    @Override
    protected JsonObject toolSchema() {
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
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      SynchronizedLogs syncLogs = logManager.getSynchronizedLogs(log.path());
      boolean syncInProgress = logManager.isRevLogSyncInProgress(log.path());

      if (syncLogs == null || syncLogs.revlogCount() == 0) {
        return noRevlogs(log, syncInProgress).build();
      }

      String deviceFilter = getOptString(arguments, "device_filter", null);
      String signalFilter = getOptString(arguments, "signal_filter", null);

      JsonArray signals = new JsonArray();
      int totalSignals = 0;
      int unfiltered = 0;
      int failedRevlogs = 0;
      var warnings = new java.util.ArrayList<String>();

      for (SyncedRevLog synced : syncLogs.revlogs()) {
        String busName = synced.canBusName();
        SyncResult result = synced.syncResult();
        String confidence = result.confidenceLevel().getLabel();
        boolean multipleRevLogs = syncLogs.revlogCount() > 1;
        if (result.method() == SyncMethod.FAILED) failedRevlogs++;
        var warning = alignmentWarning(synced);
        if (warning != null) warnings.add(warning);

        for (RevLogSignal signal : synced.revlog().signals().values()) {
          unfiltered++;
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
          signalObj.addProperty("sync_method", result.method().name());
          signalObj.addProperty("timestamps_aligned", result.method() != SyncMethod.FAILED);
          if (result.method() != SyncMethod.FAILED) {
            signalObj.addProperty("offset_seconds", result.offsetSeconds());
          }
          signalObj.addProperty("sync_confidence", confidence);

          signals.add(signalObj);
          totalSignals++;
        }
      }

      if (failedRevlogs == syncLogs.revlogCount()) {
        return ResponseBuilder.notApplicable("None of the " + syncLogs.revlogCount() + " REV "
                + "log(s) found for this wpilog could be synchronized, so their timestamps cannot "
                + "be converted to FPGA time.")
            .hint("sync_status explains each; set_revlog_offset provides a known offset per bus, "
                + "after which the signals can be read.")
            .addData("revlogs", revlogsJson(syncLogs))
            .build();
      }
      if (totalSignals == 0 && unfiltered > 0) {
        var criteria = new java.util.ArrayList<String>();
        if (deviceFilter != null) criteria.add("device containing '" + deviceFilter + "'");
        if (signalFilter != null) criteria.add("signal name containing '" + signalFilter + "'");
        return ResponseBuilder.noMatch("No signal matches " + String.join(" and ", criteria)
                + " (" + unfiltered + " signals in " + syncLogs.revlogCount() + " REV log(s)).")
            .hint("Call list_revlog_signals without filters to see every signal key.")
            .build();
      }

      ConfidenceLevel overall = syncLogs.overallConfidence();
      boolean accuracyKnown = syncLogs.revlogs().stream().allMatch(s ->
          s.syncResult().method() == SyncMethod.CROSS_CORRELATION
              || s.syncResult().method() == SyncMethod.SYSTEM_TIME_ONLY);

      ResponseBuilder response = success()
          .addProperty("signal_count", totalSignals)
          .addData("signals", signals)
          .addProperty("revlog_count", syncLogs.revlogCount())
          .addProperty("overall_sync_confidence", overall.getLabel())
          .addMetadata("timing_accuracy_ms", accuracyKnown ? overall.getAccuracyMs() : "unknown");
      warnings.forEach(response::addWarning);

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
          + "Use list_revlog_signals first to discover available signal keys. sync_method, "
          + "offset_seconds, and sync_confidence say how the timestamps were aligned (a "
          + "cross-correlation of signals both logs record, the wall-clock estimate alone, or a "
          + "user-provided offset, whose accuracy the server cannot judge: timing_accuracy_ms "
          + "is then unknown), and a warning says so when the method bounds the accuracy. A "
          + "signal whose REV log could not be synchronized returns not_applicable until "
          + "set_revlog_offset provides an offset: its timestamps are on the REV log's own clock.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty(
              "signal_key",
              "string",
              "Signal key (e.g., 'REV/SparkMax_1/AppliedOutput' or 'REV/rio/SparkMax_1/Velocity')",
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
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      SynchronizedLogs syncLogs = logManager.getSynchronizedLogs(log.path());

      if (syncLogs == null || syncLogs.revlogCount() == 0) {
        return noRevlogs(log, logManager.isRevLogSyncInProgress(log.path())).build();
      }

      String signalKey = getRequiredString(arguments, "signal_key");
      Double startTime = getOptDouble(arguments, "start_time");
      Double endTime = getOptDouble(arguments, "end_time");
      int limit = getOptInt(arguments, "limit", 1000);
      validatePositive(limit, "limit");
      boolean includeStats = arguments.has("include_stats")
          && arguments.get("include_stats").getAsBoolean();

      SyncedRevLog synced = syncLogs.revlogFor(signalKey);
      List<TimestampedValue> values = synced == null ? null : syncLogs.getValues(signalKey);
      if (values == null) {
        throw new IllegalArgumentException(
            "Signal not found: " + signalKey + ". Use list_revlog_signals to see available signals.");
      }
      SyncResult sync = synced.syncResult();
      if (sync.method() == SyncMethod.FAILED) {
        return ResponseBuilder.notApplicable("The REV log holding " + signalKey + " (bus '"
                + synced.canBusName() + "', " + synced.revlog().path() + ") was not synchronized: "
                + sync.explanation() + " Its timestamps are on the REV log's own clock and cannot "
                + "be converted to FPGA time.")
            .hint("sync_status has the details; if you know the offset, set it with "
                + "set_revlog_offset (can_bus '" + synced.canBusName() + "') and call "
                + "get_revlog_data again.")
            .addProperty("can_bus", synced.canBusName())
            .addProperty("sync_method", "FAILED")
            .addProperty("timestamps_aligned", false)
            .build();
      }

      // Filter by time range
      List<TimestampedValue> filtered = filterTimeRange(values, startTime, endTime);

      // Apply limit (statistics, when requested, cover every sample in range)
      int totalCount = filtered.size();
      var inRange = filtered;
      if (filtered.size() > limit) {
        filtered = filtered.subList(0, limit);
      }

      // Build data array
      JsonArray dataArray = new JsonArray();
      for (TimestampedValue tv : filtered) {
        JsonObject point = new JsonObject();
        point.addProperty("timestamp", tv.timestamp());
        if (tv.value() instanceof Number n) {
          point.addProperty("value", n.doubleValue());
        } else {
          point.addProperty("value", String.valueOf(tv.value()));
        }
        dataArray.add(point);
      }

      // How this signal's timestamps were aligned: its own revlog's sync, not the overall level
      ConfidenceLevel confidence = sync.confidenceLevel();
      String accuracyEstimate = accuracyOf(sync);

      ResponseBuilder response = success()
          .addProperty("signal_key", signalKey)
          .addProperty("can_bus", synced.canBusName())
          .addProperty("sample_count", dataArray.size())
          .addProperty("total_samples", totalCount)
          .addLimitedList("data", dataArray, totalCount, limit)
          .addProperty("sync_method", sync.method().name())
          .addProperty("timestamps_aligned", true)
          .addProperty("offset_seconds", sync.offsetSeconds())
          .addProperty("sync_confidence", confidence.getLabel())
          .addMetadata("timing_accuracy_ms", accuracyEstimate);

      // Calculate statistics if requested
      if (includeStats && !inRange.isEmpty()) {
        double[] numericData = extractNumericData(inRange);
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

          // Attach data quality and analysis directives when returning statistics
          var quality = DataQuality.fromValues(inRange);
          var directives = AnalysisDirectives.fromQuality(quality)
              .addSingleMatchCaveat()
              .addGuidance("Revlog timestamps were aligned by " + sync.method().getDescription()
                  .toLowerCase(java.util.Locale.ROOT) + " (confidence: " + confidence.getLabel()
                  + ", accuracy: " + accuracyEstimate + " ms); sync_status has the details.");
          response.addDataQuality(quality).addDirectives(directives);
        }
      }

      var warning = alignmentWarning(synced);
      if (warning != null) response.addWarning(warning);

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
          + "synchronization. Use this to understand the accuracy of REV log timestamps. "
          + "revlog_filename_zone says how REV log filename times were read to find the REV "
          + "logs and estimate the coarse offset: in the zone the wpilog's own filename shows "
          + "against its wall clock, else UTC (the roboRIO's default). A REV log named by "
          + "another clock (the REV Hardware Client uses the laptop's local time) may be "
          + "missed or mis-aligned; set_revlog_offset corrects an offset.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty(
              "include_signal_pairs",
              "boolean",
              "Include details about which signal pairs were used for correlation",
              false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      boolean syncInProgress = logManager.isRevLogSyncInProgress(log.path());
      SynchronizedLogs syncLogs = logManager.getSynchronizedLogs(log.path());

      if (syncLogs == null || syncLogs.revlogCount() == 0) {
        return noRevlogs(log, syncInProgress).build();
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
          .addProperty("revlog_filename_zone",
              org.triplehelix.wpilogmcp.log.WallClock.revlogFilenameZone(log).basis())
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
                + "with wpilog timestamps. Check that both logs were recorded during the same "
                + "time period; if you know the offset, set it with set_revlog_offset.");
      } else if (overall == ConfidenceLevel.LOW) {
        response.addWarning(
            "Low synchronization confidence: the offset rests on weak correlation, or on the "
                + "REV log's filename time alone, which can be off by seconds or more. Check "
                + "signal_pairs (include_signal_pairs); set_revlog_offset sets a known offset. "
                + "Use with caution for timing-sensitive analysis.");
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
          + "set offset_ms to -500. offset_ms is required: omitting it is an error, not an "
          + "offset of zero.";
    }

    @Override
    protected JsonObject toolSchema() {
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
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      SynchronizedLogs syncLogs = logManager.getSynchronizedLogs(log.path());

      if (syncLogs == null || syncLogs.revlogCount() == 0) {
        return noRevlogs(log, logManager.isRevLogSyncInProgress(log.path())).build();
      }

      var offsetArg = getOptDouble(arguments, "offset_ms");
      if (offsetArg == null || !Double.isFinite(offsetArg)) {
        throw new IllegalArgumentException("Missing required parameter: offset_ms, the "
            + "milliseconds to add to revlog timestamps to get FPGA time (a finite number). The "
            + "synchronization was not changed.");
      }
      double offsetMs = offsetArg;
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

      // Rebuild SynchronizedLogs with the updated offset, atomically against the cached value
      // (a concurrent call on another bus must not be lost)
      String targetBus = target.canBusName();
      var updated = logManager.updateSynchronizedLogs(log.path(), current -> {
        SynchronizedLogs.Builder builder = new SynchronizedLogs.Builder().wpilog(current.wpilog());
        for (SyncedRevLog synced : current.revlogs()) {
          builder.addRevLog(synced.revlog(), synced.canBusName().equals(targetBus)
              ? userResult : synced.syncResult(), synced.canBusName());
        }
        return builder.build();
      });
      if (updated == null) {
        return noRevlogs(log, logManager.isRevLogSyncInProgress(log.path())).build();
      }

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
          + "Call this before querying revlog data if synchronization may still be in progress. "
          + "Returns instantly if sync is already done; returns not_applicable when this wpilog "
          + "has no revlogs. timeout_ms is capped at " + MAX_WAIT_MS + ".";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addIntegerProperty("timeout_ms",
              "Maximum time to wait in milliseconds (default: 30000, max: " + MAX_WAIT_MS + ")",
              false, 30000)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      int timeoutMs = Math.max(0, Math.min(MAX_WAIT_MS, getOptInt(arguments, "timeout_ms", 30000)));

      boolean wasInProgress = logManager.isRevLogSyncInProgress(log.path());
      boolean completed = logManager.waitForRevLogSync(log.path(), timeoutMs);
      var syncLogs = logManager.getSynchronizedLogs(log.path());
      if (completed && (syncLogs == null || syncLogs.revlogCount() == 0)) {
        return noRevlogs(log, false).build();
      }

      var response = success()
          .addProperty("completed", completed)
          .addProperty("was_in_progress", wasInProgress);

      if (!completed) {
        response.addWarning("RevLog synchronization did not complete within "
            + timeoutMs + "ms. Try again with a longer timeout.");
      }

      // Include current sync status summary
      if (syncLogs != null) {
        response.addProperty("revlog_count", syncLogs.revlogCount());
        response.addProperty("synchronized", syncLogs.hasAnySynchronized());
      }

      return response.build();
    }
  }
}
