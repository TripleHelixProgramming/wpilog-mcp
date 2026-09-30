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
import java.util.LinkedHashMap;
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
    static final int DEFAULT_INLINE_ROWS = 500;
    static final int MAX_INLINE_ROWS = 5000;

    @Override
    public String name() {
      return "export_csv";
    }

    @Override
    public String description() {
      return "Export an entry to CSV for external analysis (Python, Excel, MATLAB), or return the "
          + "rows inline. Every value is flattened into columns: a struct becomes one column per "
          + "numeric or text field (nested fields as dot paths, e.g. translation.x, arrays as "
          + "field[i]), a struct array or primitive array becomes one row per element with an "
          + "index column. Files are written inside the server's export directory "
          + "(export_directory in every result): pass a bare or relative output_path, which is "
          + "resolved inside it, or omit it for a generated name; an absolute path must lie inside "
          + "it. The result gives the absolute path written. With inline=true no file is written "
          + "and the rows come back in the response (max_rows, default 500), for agents that "
          + "cannot read the export directory. Cite the export when you compute from it.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name to export", true)
          .addProperty("output_path", "string",
              "CSV file name or path inside the export directory (default: generated from the log and entry names)", false)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .addProperty("inline", "boolean", "Return the rows in the response instead of writing a file (default false)", false)
          .addIntegerProperty("max_rows", "Rows to return inline (default 500, max 5000)", false, DEFAULT_INLINE_ROWS)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var name = getRequiredString(arguments, "name");
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      boolean inline = getOptBoolean(arguments, "inline");
      int maxRows = validateRange(getOptInt(arguments, "max_rows", DEFAULT_INLINE_ROWS), 1,
          MAX_INLINE_ROWS, "max_rows");

      var values = log.values().get(name);
      if (values == null) {
        return errorResult("Entry not found: " + name);
      }
      var entry = log.entries().get(name);
      var type = entry != null ? entry.type() : "unknown";

      // Flatten: one row per sample, or per element of an array value
      var rows = new ArrayList<Row>();
      boolean indexed = false;
      for (var tv : values) {
        double t = tv.timestamp();
        if ((startTime != null && t < startTime) || (endTime != null && t > endTime)) continue;
        var elements = elementsOf(tv.value());
        if (elements != null) {
          indexed = true;
          for (int i = 0; i < elements.size(); i++) {
            rows.add(new Row(t, i, flatten(elements.get(i))));
          }
        } else {
          rows.add(new Row(t, -1, flatten(tv.value())));
        }
      }
      var columnSet = new java.util.TreeSet<String>();
      rows.forEach(r -> columnSet.addAll(r.fields().keySet()));
      var columns = new ArrayList<String>();
      columns.add("timestamp_sec");
      if (indexed) columns.add("index");
      columns.addAll(columnSet);

      if (inline) {
        var array = new JsonArray();
        for (var row : rows.subList(0, Math.min(maxRows, rows.size()))) {
          var cells = new JsonArray();
          cells.add(row.timestamp());
          if (indexed) cells.add(row.index());
          for (var c : columnSet) {
            var v = row.fields().get(c);
            if (v == null) cells.add(com.google.gson.JsonNull.INSTANCE);
            else if (v instanceof Number n && Double.isFinite(n.doubleValue())) cells.add(n);
            else cells.add(String.valueOf(v));
          }
          array.add(cells);
        }
        var result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("entry", name);
        result.addProperty("type", type);
        result.add("columns", GSON.toJsonTree(columns));
        ResultContract.addLimitedList(result, "rows", array, rows.size(), maxRows);
        result.addProperty("rows_exported", array.size());
        return result;
      }

      var exportDir = exportDirectory;
      Path outputFilePath;
      try {
        outputFilePath = resolveOutputPath(getOptString(arguments, "output_path", null), log, name,
            exportDir);
      } catch (IllegalArgumentException e) {
        return errorResult(e.getMessage());
      }

      int rowCount = 0;
      try (var writer = new PrintWriter(new FileWriter(outputFilePath.toFile()))) {
        writer.println(String.join(",", columns));
        for (var row : rows) {
          var sb = new StringBuilder();
          sb.append(row.timestamp());
          if (indexed) sb.append(',').append(row.index());
          for (var c : columnSet) {
            var v = row.fields().get(c);
            sb.append(',');
            if (v != null) sb.append(csvEscape(String.valueOf(v)));
          }
          writer.println(sb);
          rowCount++;
        }
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("entry", name);
      result.addProperty("output_path", outputFilePath.toString());
      result.addProperty("export_directory", exportDir.toRealPath().toString());
      result.addProperty("rows_exported", rowCount);
      result.addProperty("type", type);
      result.add("columns", GSON.toJsonTree(columns));
      return result;
    }

    record Row(double timestamp, int index, Map<String, Object> fields) {}

    /** The elements of an array value (struct array or primitive array), or null for a scalar. */
    static List<?> elementsOf(Object value) {
      if (value instanceof List<?> list) return list;
      if (value != null && value.getClass().isArray() && !(value instanceof byte[])) {
        int n = java.lang.reflect.Array.getLength(value);
        var out = new ArrayList<Object>(n);
        for (int i = 0; i < n; i++) out.add(java.lang.reflect.Array.get(value, i));
        return out;
      }
      return null;
    }

    /** A value as columns: "value" for a scalar; dot paths for a struct; field[i] for arrays. */
    static Map<String, Object> flatten(Object value) {
      var out = new LinkedHashMap<String, Object>();
      if (value instanceof Map<?, ?> map) {
        flattenInto("", map, out);
      } else {
        out.put("value", value instanceof byte[] b ? BinaryHex.of(b) : value);
      }
      return out;
    }

    private static void flattenInto(String prefix, Object value, Map<String, Object> out) {
      if (value instanceof Map<?, ?> map) {
        for (var e : map.entrySet()) {
          flattenInto(prefix.isEmpty() ? String.valueOf(e.getKey())
              : prefix + "." + e.getKey(), e.getValue(), out);
        }
      } else if (value instanceof List<?> || (value != null && value.getClass().isArray()
          && !(value instanceof byte[]))) {
        var elements = elementsOf(value);
        for (int i = 0; i < elements.size(); i++) {
          flattenInto(prefix + "[" + i + "]", elements.get(i), out);
        }
      } else if (value instanceof org.triplehelix.wpilogmcp.log.struct.EnumValue e) {
        // the number in the field's own column, its schema label beside it
        out.put(prefix, e.value());
        out.put(prefix + ".label", e.label());
      } else {
        out.put(prefix, value);
      }
    }

    /**
     * Where to write: a bare or relative name inside the export directory, a generated name when
     * none is given, or an absolute path that lies inside it. Symlinks cannot escape it.
     */
    static Path resolveOutputPath(String requested, LogData log, String entryName, Path exportDir)
        throws IOException {
      if (!Files.isDirectory(exportDir)) Files.createDirectories(exportDir);
      var realExportDir = exportDir.toRealPath();
      Path candidate;
      if (requested == null || requested.isBlank()) {
        var logStem = Path.of(log.path()).getFileName().toString().replaceAll("\\.wpilog$", "");
        var entryStem = entryName.replaceAll("[^A-Za-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
        candidate = realExportDir.resolve(logStem + "__" + entryStem + ".csv");
      } else {
        var p = Path.of(requested);
        candidate = (p.isAbsolute() ? p : realExportDir.resolve(p)).toAbsolutePath().normalize();
      }
      var notAllowed = "Output path not allowed: " + candidate + ". CSV files are written only "
          + "inside the export directory " + realExportDir + "; pass a bare file name (e.g. "
          + "\"pose.csv\") or a path relative to it, or omit output_path.";
      if (!candidate.startsWith(realExportDir) && !candidate.startsWith(exportDir.toAbsolutePath().normalize())) {
        throw new IllegalArgumentException(notAllowed);
      }
      if (Files.isSymbolicLink(candidate)) throw new IllegalArgumentException(notAllowed);
      var parent = candidate.getParent();
      if (parent == null) throw new IllegalArgumentException(notAllowed);
      // Only create subdirectories that stay inside the export directory
      if (!Files.exists(parent)) {
        if (!parent.normalize().startsWith(realExportDir)) throw new IllegalArgumentException(notAllowed);
        Files.createDirectories(parent);
      }
      var resolved = Files.exists(candidate) ? candidate.toRealPath()
          : parent.toRealPath().resolve(candidate.getFileName());
      if (!resolved.startsWith(realExportDir)) throw new IllegalArgumentException(notAllowed);
      return resolved;
    }

    /** Hex text for raw bytes. */
    static final class BinaryHex {
      static String of(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
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
          + "battery voltage over enabled time when the log records it (min with time, max, "
          + "average, threshold crossings; entry chosen as power_analysis does, or "
          + "voltage_entry) with brownout_risk and its basis as power_analysis gives them, "
          + "brownouts from the roboRIO flag when logged, and the brownout threshold with its "
          + "basis; the three largest current peaks in the same scope (power_analysis channel_analysis, each channel of an array separately); error and "
          + "warning counts from console and message text (one classification per line, as in "
          + "get_ds_timeline and search_strings) with the most frequent messages; code metadata "
          + "(get_code_metadata); and the most common data types. Each section names its source "
          + "entries; use the individual tools for detail."
          + GUIDANCE_UNIVERSAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("voltage_entry", "string", "Battery voltage entry (default: "
              + "BatteryVoltage, or Voltage under PowerDistribution/PDH/PDP/Battery)", false)
          .build();
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
      var batteryRole = SignalResolver.batteryVoltage(log, null,
          getOptString(arguments, "voltage_entry", null));
      var voltageEntry = batteryRole.chosen();
      var threshold = PowerFacts.threshold(log, null);
      var scope = TimeScope.resolve(log, timeline, timeline.hasEnabledData() ? "enabled" : "all",
          null, null);
      var flag = PowerFacts.flagEntry(log);
      var flagged = flag.map(f -> PowerFacts.brownouts(log, f, null, null).stream()
          .filter(b -> scope.contains(b.start())).toList()).orElse(List.of());
      var voltageFacts = voltageEntry.flatMap(name ->
          PowerFacts.voltage(log.values().get(name), scope, threshold.volts()));
      if (voltageFacts.isPresent()) {
        var v = voltageFacts.get();
        var battery = new JsonObject();
        battery.addProperty("entry", voltageEntry.get());
        battery.add("scope", scope.toJson());
        v.addTo(battery);
        threshold.addTo(battery);
        flag.ifPresent(f -> battery.add("rio_brownouts", PowerFacts.brownoutsJson(f, flagged)));
        PowerFacts.risk(v, threshold, flag.orElse(null), flagged).addTo(battery);
        report.add("battery", battery);
      } else if (voltageEntry.isPresent()) {
        skipped.add(skippedSection("battery", "No finite samples of " + voltageEntry.get()
            + " in scope '" + scope.name() + "'."));
      } else {
        skipped.add(skippedSection("battery",
            SignalResolver.unresolvedReason(batteryRole, "voltage_entry")));
      }

      // Peak currents: power_analysis's channel_analysis over the same scope
      var peaks = peakCurrents(log, scope, 3);
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
      for (var event : TextEvents.all(log)) {
        var level = TextEvents.level(event);
        if (!"error".equals(level) && !"warning".equals(level)) continue;
        boolean error = "error".equals(level);
        if (error) errorSamples++; else warningSamples++;
        if (!error) continue;
        var classified = ToolUtils.classifyText(event.text());
        var message = event.source() == TextEvents.Source.ALERT || classified == null
            ? event.text().strip() : classified.message();
        var pattern = ToolUtils.normalizeMessage(message);
        groups.computeIfAbsent(pattern, k -> new int[1])[0]++;
        firstSeen.putIfAbsent(pattern, event.timestamp());
        examples.putIfAbsent(pattern, message);
        if (firstErrors.size() < 5) {
          var o = new JsonObject();
          o.addProperty("timestamp_sec", event.timestamp());
          o.addProperty("entry", event.entry());
          o.addProperty("line", ToolUtils.truncate(message, ToolUtils.MESSAGE_LINE_LIMIT));
          firstErrors.add(o);
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
      ResultContract.addLimitedList(errors, "top_messages", top, groups.size(), 5);
      ResultContract.addLimitedList(errors, "samples", firstErrors, errorSamples, 5);
      errors.addProperty("note", "Counts are text samples classified ERROR or WARNING (a "
          + "multi-line sample counts once, by its most severe line; an alert once per "
          + "appearance, by its entry's level); search_strings lists every message.");
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
      report.addProperty("type_count", typeCounts.size());

      if (!skipped.isEmpty()) {
        report.add("skipped", skipped);
        report.addProperty("status", log.entryCount() == 0 ? "no_match" : "partial");
        if (log.entryCount() == 0) report.addProperty("reason", "The log has no entries.");
      }

      voltageEntry.ifPresent(name -> {
        var quality = DataQuality.fromSegments(scope.split(log.values().get(name)));
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

    /**
     * The largest current peaks: power_analysis's channel_analysis over the same scope (each
     * channel of an array separately), with the fields power_analysis reports for them.
     */
    static JsonArray peakCurrents(LogData log, TimeScope scope, int limit) {
      var out = new JsonArray();
      RobotAnalysisTools.PowerAnalysisTool.channelAnalysis(log, null, scope).channels().stream()
          .limit(limit).forEach(c -> {
            var o = new JsonObject();
            o.addProperty("entry", c.get("entry").getAsString());
            o.addProperty("peak_current_A", c.get("peak_current_A").getAsDouble());
            o.addProperty("peak_current_time_sec", c.get("peak_current_time_sec").getAsDouble());
            out.add(o);
          });
      return out;
    }
  }
}
