/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * Export tools for WPILOG data.
 *
 * <p>Tools included:
 * <ul>
 *   <li>{@code export_csv} - Export entry data to CSV</li>
 *   <li>{@code generate_report} - Generate comprehensive match report</li>
 * </ul>
 */
public final class ExportTools {
  private static final Logger logger = LoggerFactory.getLogger(ExportTools.class);

  /** Default export directory: {tmpdir}/wpilog-export/ */
  private static volatile Path exportDirectory =
      Path.of(System.getProperty("java.io.tmpdir"), "wpilog-export");

  private ExportTools() {}

  /**
   * Sets the export directory. CSV exports are restricted to this directory.
   *
   * @param path The export directory path
   */
  public static void setExportDirectory(String path) {
    if (path != null && !path.isBlank()) {
      exportDirectory = Path.of(path).toAbsolutePath().normalize();
      logger.info("Export directory: {}", exportDirectory);
    }
  }

  /**
   * Gets the configured export directory.
   *
   * @return The export directory path
   */
  public static Path getExportDirectory() {
    return exportDirectory;
  }

  /**
   * Registers all export tools with the MCP server.
   */
  public static void registerAll(ToolRegistry registry) {
    registry.registerTool(new ExportCsvTool());
    registry.registerTool(new GenerateReportTool());
  }

  static class ExportCsvTool extends LogRequiringTool {
    @Override
    public String name() {
      return "export_csv";
    }

    @Override
    public String description() {
      return "Export entry data to a CSV file for external analysis in Excel, Python, or MATLAB.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name to export", true)
          .addProperty("output_path", "string", "Path for output CSV file", true)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var name = getRequiredString(arguments, "name");
      var outputPath = getRequiredString(arguments, "output_path");
      var startTime = arguments.has("start_time") && !arguments.get("start_time").isJsonNull()
          ? arguments.get("start_time").getAsDouble()
          : null;
      var endTime = arguments.has("end_time") && !arguments.get("end_time").isJsonNull()
          ? arguments.get("end_time").getAsDouble()
          : null;

      var outputFilePath = Path.of(outputPath).toAbsolutePath().normalize();
      if (!isPathAllowed(outputFilePath, log)) {
        return errorResult(
            "Output path not allowed. CSV files can only be written to the configured log "
                + "directory or system temp directory. Path: " + outputFilePath);
      }

      var values = log.values().get(name);
      if (values == null) {
        return errorResult("Entry not found: " + name);
      }

      var entry = log.entries().get(name);
      var type = entry != null ? entry.type() : "unknown";
      boolean isArray = type.startsWith("structarray:") || type.contains("[]");

      int rowCount = 0;
      try (var writer = new PrintWriter(new FileWriter(outputFilePath.toFile()))) {
        if (isArray && type.contains("SwerveModuleState")) {
          writer.println("timestamp_sec,module_index,speed_mps,angle_rad,angle_deg");
        } else if (type.contains("Pose2d")) {
          writer.println("timestamp_sec,x,y,rotation_rad,rotation_deg");
        } else if (type.contains("Pose3d")) {
          writer.println("timestamp_sec,x,y,z,qw,qx,qy,qz");
        } else if (type.contains("SwerveModuleState")) {
          writer.println("timestamp_sec,speed_mps,angle_rad,angle_deg");
        } else if (isArray) {
          writer.println("timestamp_sec,index,value");
        } else {
          // For Map-typed values (generic structs), discover keys from first value
          // to write a correct header with one column per field.
          if (!values.isEmpty() && values.get(0).value() instanceof Map<?, ?> firstRawMap) {
            @SuppressWarnings("unchecked")
            var firstMap = (Map<String, Object>) firstRawMap;
            var sortedKeys = new java.util.TreeSet<>(firstMap.keySet());
            writer.println("timestamp_sec," + String.join(",", sortedKeys));
          } else {
            writer.println("timestamp_sec,value");
          }
        }

        for (var tv : values) {
          double t = tv.timestamp();
          if ((startTime != null && t < startTime) || (endTime != null && t > endTime)) {
            continue;
          }

          if (tv.value() instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
              var element = list.get(i);
              if (element instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) rawMap;
                var sb = new StringBuilder();
                sb.append(t).append(",").append(i);
                writeStructFields(sb, map, type);
                writer.println(sb);
              } else {
                writer.println(t + "," + i + "," + csvEscape(String.valueOf(element)));
              }
              rowCount++;
            }
          } else if (RobotAnalysisTools.PowerAnalysisTool.toDoubleArray(tv.value()) != null) {
            var arr = RobotAnalysisTools.PowerAnalysisTool.toDoubleArray(tv.value());
            for (int i = 0; i < arr.length; i++) {
              writer.println(t + "," + i + "," + arr[i]);
              rowCount++;
            }
          } else if (tv.value() instanceof long[] arr) {
            for (int i = 0; i < arr.length; i++) {
              writer.println(t + "," + i + "," + arr[i]);
              rowCount++;
            }
          } else if (tv.value() instanceof float[] arr) {
            for (int i = 0; i < arr.length; i++) {
              writer.println(t + "," + i + "," + arr[i]);
              rowCount++;
            }
          } else if (tv.value() instanceof boolean[] arr) {
            for (int i = 0; i < arr.length; i++) {
              writer.println(t + "," + i + "," + arr[i]);
              rowCount++;
            }
          } else if (tv.value() instanceof String[] arr) {
            for (int i = 0; i < arr.length; i++) {
              writer.println(t + "," + i + "," + csvEscape(arr[i]));
              rowCount++;
            }
          } else if (tv.value() instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) rawMap;
            var sb = new StringBuilder();
            sb.append(t);
            writeStructFields(sb, map, type);
            writer.println(sb);
            rowCount++;
          } else {
            writer.println(t + "," + csvEscape(String.valueOf(tv.value())));
            rowCount++;
          }
        }
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("entry", name);
      result.addProperty("output_path", outputFilePath.toString());
      result.addProperty("rows_exported", rowCount);
      result.addProperty("type", type);

      return result;
    }

    /**
     * Writes struct fields to the StringBuilder in the correct order for the entry type.
     * Known struct types use explicit field ordering matching their CSV headers.
     * Unknown struct types use alphabetically sorted keys for deterministic output.
     */
    private static void writeStructFields(StringBuilder sb, Map<String, Object> map, String type) {
      if (type.contains("SwerveModuleState")) {
        sb.append(",").append(map.get("speed_mps"));
        sb.append(",").append(map.get("angle_rad"));
        sb.append(",").append(map.get("angle_deg"));
      } else if (type.contains("Pose2d")) {
        sb.append(",").append(map.get("x"));
        sb.append(",").append(map.get("y"));
        sb.append(",").append(map.get("rotation_rad"));
        sb.append(",").append(map.get("rotation_deg"));
      } else if (type.contains("Pose3d")) {
        sb.append(",").append(map.get("x"));
        sb.append(",").append(map.get("y"));
        sb.append(",").append(map.get("z"));
        sb.append(",").append(map.get("qw"));
        sb.append(",").append(map.get("qx"));
        sb.append(",").append(map.get("qy"));
        sb.append(",").append(map.get("qz"));
      } else {
        // Generic struct: alphabetically sorted keys for deterministic column order
        var sortedKeys = new java.util.TreeSet<>(map.keySet());
        sortedKeys.forEach(key -> sb.append(",").append(csvEscape(String.valueOf(map.get(key)))));
      }
    }

    /**
     * Escapes a value for CSV output per RFC 4180.
     * Wraps in double-quotes if the value contains commas, double-quotes, or newlines.
     */
    private static String csvEscape(String value) {
      if (value == null) return "";
      if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
      }
      return value;
    }

    private boolean isPathAllowed(Path path, LogData log) {
      // Exports are restricted to the configured export directory only.
      // Resolve symlinks to prevent symlink-based path escape:
      // - If the file already exists, resolve the FULL path (catches symlinks in filename)
      // - If it doesn't exist, resolve the parent and reject if the filename is a symlink
      try {
        var absPath = path.toAbsolutePath().normalize();

        // Auto-create export directory if it doesn't exist
        var exportDir = exportDirectory;
        if (!Files.isDirectory(exportDir)) {
          Files.createDirectories(exportDir);
        }
        var resolvedExportDir = exportDir.toRealPath();

        if (Files.exists(absPath)) {
          // File exists — resolve entire path to follow all symlinks
          var resolvedPath = absPath.toRealPath();
          return resolvedPath.startsWith(resolvedExportDir);
        } else {
          // File doesn't exist — resolve parent, reject symlink filenames
          var parent = absPath.getParent();
          if (parent == null) return false;
          if (Files.isSymbolicLink(absPath)) return false;
          var resolvedPath = parent.toRealPath().resolve(absPath.getFileName());
          return resolvedPath.startsWith(resolvedExportDir);
        }
      } catch (IOException e) {
        return false; // Cannot resolve — deny by default
      }
    }
  }

  static class GenerateReportTool extends LogRequiringTool {
    @Override
    public String name() {
      return "generate_report";
    }

    @Override
    public String description() {
      return "Generate a one-call summary of a log: duration and truncation; the DriverStation "
          + "timeline (enabled segments, enabled time, FMS matches, as in get_match_phases); "
          + "battery voltage (min with time, max, average; entry chosen as power_analysis does), "
          + "brownouts from the roboRIO flag when logged, and the brownout threshold with its "
          + "basis; the three largest current peaks (power_analysis channel_analysis); error and "
          + "warning counts from console and message text (one classification per line, as in "
          + "get_ds_timeline and search_strings) with the most frequent messages; code metadata "
          + "(get_code_metadata); and the most common data types. Each section names its source "
          + "entries; use the individual tools for detail."
          + GUIDANCE_UNIVERSAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var report = new JsonObject();
      report.addProperty("success", true);
      report.addProperty("log_path", log.path());
      report.addProperty("log_filename", Path.of(log.path()).getFileName().toString());

      var basics = new JsonObject();
      basics.addProperty("duration_sec", log.duration());
      basics.addProperty("start_timestamp", log.minTimestamp());
      basics.addProperty("end_timestamp", log.maxTimestamp());
      basics.addProperty("entry_count", log.entryCount());
      basics.addProperty("truncated", log.truncated());
      if (log.truncated()) {
        basics.addProperty("truncation_message", log.truncationMessage());
      }
      report.add("basic_info", basics);
      var skipped = new JsonArray();

      // DriverStation timeline
      var timeline = MatchTimeline.of(log);
      if (timeline.hasEnabledData()) {
        var t = new JsonObject();
        var enabled = timeline.enabledSegments();
        t.addProperty("enabled_segments", enabled.size());
        t.addProperty("enabled_time_sec",
            enabled.stream().mapToDouble(MatchTimeline.Segment::duration).sum());
        t.addProperty("matches", timeline.matches().size());
        t.addProperty("season", timeline.season().year());
        t.addProperty("source", timeline.sources().enabled() != null
            ? timeline.sources().enabled() : timeline.sources().controlWord());
        report.add("timeline", t);
      } else {
        skipped.add(skippedSection("timeline", "no DriverStation state entries"));
      }

      // Battery: the same entry choice and threshold as power_analysis
      var voltageEntry = ToolUtils.selectVoltageEntry(log, null);
      var threshold = PowerFacts.threshold(log, null);
      if (voltageEntry.isPresent()) {
        var values = log.values().get(voltageEntry.get());
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0;
        double minTime = 0;
        int n = 0;
        for (var tv : values) {
          if (!(tv.value() instanceof Number num) || !Double.isFinite(num.doubleValue())) continue;
          double v = num.doubleValue();
          if (v < min) {
            min = v;
            minTime = tv.timestamp();
          }
          max = Math.max(max, v);
          sum += v;
          n++;
        }
        var battery = new JsonObject();
        battery.addProperty("entry", voltageEntry.get());
        battery.addProperty("min_voltage", min);
        battery.addProperty("min_voltage_time_sec", minTime);
        battery.addProperty("max_voltage", max);
        battery.addProperty("avg_voltage_whole_log", sum / n);
        threshold.addTo(battery);
        var flag = PowerFacts.flagEntry(log);
        if (flag.isPresent()) {
          battery.add("rio_brownouts", PowerFacts.brownoutsJson(flag.get(),
              PowerFacts.brownouts(log, flag.get(), null, null)));
        }
        battery.addProperty("brownout_risk", min < threshold.volts() ? "HIGH"
            : (min < 9.0 ? "MODERATE" : "LOW"));
        report.add("battery", battery);
      } else {
        skipped.add(skippedSection("battery", "no battery voltage entry with finite samples"));
      }

      // Peak currents: the same amperage entries and ranking as power_analysis
      var peaks = peakCurrents(log, 3);
      if (!peaks.isEmpty()) {
        report.add("peak_currents", peaks);
      } else {
        skipped.add(skippedSection("peak_currents", "no amperage entries"));
      }

      // Errors and warnings: one classification per sample, as get_ds_timeline counts them
      int errorSamples = 0;
      int warningSamples = 0;
      var groups = new java.util.LinkedHashMap<String, int[]>(); // pattern -> [count]
      var firstSeen = new HashMap<String, Double>();
      var examples = new HashMap<String, String>();
      var firstErrors = new JsonArray();
      for (var e : log.entries().values().stream()
          .sorted(java.util.Comparator.comparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
          .toList()) {
        if (!"string".equals(e.type()) || log.sampleCount(e.name()) == 0) continue;
        for (var tv : log.values().get(e.name())) {
          if (!(tv.value() instanceof String str) || str.isBlank()) continue;
          var classified = ToolUtils.classifyText(str);
          if (classified == null) continue;
          boolean error = "ERROR".equals(classified.type());
          if (error) errorSamples++; else warningSamples++;
          if (!error) continue;
          var pattern = ToolUtils.normalizeMessage(classified.message());
          groups.computeIfAbsent(pattern, k -> new int[1])[0]++;
          firstSeen.putIfAbsent(pattern, tv.timestamp());
          examples.putIfAbsent(pattern, classified.message());
          if (firstErrors.size() < 5) {
            var o = new JsonObject();
            o.addProperty("timestamp_sec", tv.timestamp());
            o.addProperty("entry", e.name());
            o.addProperty("line", ToolUtils.truncate(classified.message(),
                ToolUtils.MESSAGE_LINE_LIMIT));
            firstErrors.add(o);
          }
        }
      }
      var errors = new JsonObject();
      errors.addProperty("total_errors", errorSamples);
      errors.addProperty("total_warnings", warningSamples);
      errors.addProperty("distinct_error_messages", groups.size());
      var top = new JsonArray();
      groups.entrySet().stream()
          .sorted(java.util.Comparator.comparingInt((java.util.Map.Entry<String, int[]> g) ->
                  -g.getValue()[0])
              .thenComparingDouble(g -> firstSeen.get(g.getKey())))
          .limit(5)
          .forEach(g -> {
            var o = new JsonObject();
            o.addProperty("message", ToolUtils.truncate(g.getKey(), ToolUtils.MESSAGE_LINE_LIMIT));
            o.addProperty("example", ToolUtils.truncate(examples.get(g.getKey()),
                ToolUtils.MESSAGE_LINE_LIMIT));
            o.addProperty("count", g.getValue()[0]);
            o.addProperty("first_timestamp", firstSeen.get(g.getKey()));
            top.add(o);
          });
      errors.add("top_messages", top);
      errors.add("samples", firstErrors);
      errors.addProperty("note", "Counts are samples classified ERROR or WARNING (a multi-line "
          + "sample counts once, by its most severe line); search_strings lists every message.");
      report.add("errors", errors);

      // Code metadata: the same entries and choice as get_code_metadata
      var codeInfo = new JsonObject();
      for (var e : log.entries().values().stream()
          .sorted(java.util.Comparator.comparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
          .toList()) {
        if (!"string".equals(e.type())) continue;
        var key = RobotAnalysisTools.GetCodeMetadataTool.key(e.name());
        if (key == null) continue;
        var outKey = switch (key) {
          case "GitSHA" -> "git_sha";
          case "GitBranch" -> "git_branch";
          case "GitDirty" -> "git_dirty";
          case "GitDate" -> "git_date";
          case "BuildDate" -> "build_date";
          case "ProjectName" -> "project_name";
          default -> "version";
        };
        if (codeInfo.has(outKey)) continue;
        var values = log.values().get(e.name());
        if (values != null && !values.isEmpty()) {
          codeInfo.addProperty(outKey, String.valueOf(values.get(0).value()));
        }
      }
      if (codeInfo.size() > 0) {
        report.add("code_info", codeInfo);
      } else {
        skipped.add(skippedSection("code_info", "no code metadata entries"));
      }

      // Data type summary (ties by type name, so the order is stable)
      var typeCounts = new HashMap<String, Integer>();
      for (var entry : log.entries().values()) {
        typeCounts.merge(entry.type(), 1, Integer::sum);
      }
      var types = new JsonObject();
      typeCounts.entrySet().stream()
          .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed()
              .thenComparing(java.util.Map.Entry.comparingByKey()))
          .limit(10)
          .forEach(e -> types.addProperty(e.getKey(), e.getValue()));
      report.add("top_data_types", types);

      if (!skipped.isEmpty()) {
        report.add("skipped", skipped);
        report.addProperty("status", log.entryCount() == 0 ? "no_match" : "partial");
        if (log.entryCount() == 0) report.addProperty("reason", "The log has no entries.");
      }

      voltageEntry.ifPresent(name -> {
        var quality = DataQuality.fromValues(log.values().get(name));
        report.add("data_quality", quality.toJson());
        var directives = AnalysisDirectives.fromQuality(quality)
            .addSingleMatchCaveat()
            .addGuidance("Report is a summary — use individual tools for detailed analysis");
        report.add("server_analysis_directives", directives.toJson());
      });
      return report;
    }

    static JsonObject skippedSection(String section, String reason) {
      var o = new JsonObject();
      o.addProperty("section", section);
      o.addProperty("reason", reason);
      return o;
    }

    /** The largest current peaks, from the same amperage entries power_analysis analyzes. */
    static JsonArray peakCurrents(LogData log, int limit) {
      var peaks = new java.util.ArrayList<JsonObject>();
      for (var e : log.entries().values().stream()
          .sorted(java.util.Comparator.comparingInt(org.triplehelix.wpilogmcp.log.EntryInfo::id))
          .toList()) {
        if (!RobotAnalysisTools.PowerAnalysisTool.isCurrentEntryName(e.name())) continue;
        var values = log.values().get(e.name());
        if (values == null) continue;
        double best = 0;
        double bestTime = 0;
        String bestName = null;
        for (var tv : values) {
          if (tv.value() instanceof Number n && Double.isFinite(n.doubleValue())) {
            if (bestName == null || Math.abs(n.doubleValue()) > Math.abs(best)) {
              best = n.doubleValue();
              bestTime = tv.timestamp();
              bestName = e.name();
            }
          } else if (RobotAnalysisTools.PowerAnalysisTool.toDoubleArray(tv.value()) != null) {
            var arr = RobotAnalysisTools.PowerAnalysisTool.toDoubleArray(tv.value());
            for (int i = 0; i < arr.length; i++) {
              if (Double.isFinite(arr[i]) && (bestName == null || Math.abs(arr[i]) > Math.abs(best))) {
                best = arr[i];
                bestTime = tv.timestamp();
                bestName = e.name() + "[" + i + "]";
              }
            }
          }
        }
        if (bestName != null) {
          var o = new JsonObject();
          o.addProperty("entry", bestName);
          o.addProperty("peak_current_A", best);
          o.addProperty("peak_current_time_sec", bestTime);
          peaks.add(o);
        }
      }
      peaks.sort(java.util.Comparator.comparingDouble(
          (JsonObject o) -> -Math.abs(o.get("peak_current_A").getAsDouble())));
      var out = new JsonArray();
      peaks.stream().limit(limit).forEach(out::add);
      return out;
    }
  }
}
