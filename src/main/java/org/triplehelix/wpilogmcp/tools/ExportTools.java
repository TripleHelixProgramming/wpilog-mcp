package org.triplehelix.wpilogmcp.tools;

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
          } else if (tv.value() instanceof double[] arr) {
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
      return "Generate a comprehensive match summary report including duration, errors found, "
          + "peak currents, minimum voltage, and other key metrics.";
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

      // Battery voltage
      for (var entryName : log.entries().keySet()) {
        if (entryName.toLowerCase().contains("batteryvoltage") || entryName.toLowerCase().contains("battery_voltage")) {
          var values = log.values().get(entryName);
          if (values != null && !values.isEmpty()) {
            double minV = Double.MAX_VALUE, maxV = Double.NEGATIVE_INFINITY;
            for (var tv : values) {
              if (tv.value() instanceof Number num) {
                double v = num.doubleValue();
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
              }
            }
            if (minV < Double.MAX_VALUE) {
              var battery = new JsonObject();
              battery.addProperty("entry", entryName);
              battery.addProperty("min_voltage", minV);
              battery.addProperty("max_voltage", maxV);
              // Brownout threshold: 6.8V for roboRIO 1 (roboRIO 2 uses 6.3V)
              battery.addProperty("brownout_risk", minV < 6.8 ? "HIGH" : (minV < 9.0 ? "MODERATE" : "LOW"));
              report.add("battery", battery);
            }
            break;
          }
        }
      }

      // Error count — only scan string entries likely to contain errors to avoid
      // decoding all string entries (which defeats lazy loading on large logs).
      int errorCount = 0;
      var errorSamples = new ArrayList<String>();
      for (var e : log.entries().entrySet()) {
        if ("string".equals(e.getValue().type()) && log.sampleCount(e.getKey()) > 0) {
          var values = log.values().get(e.getKey());
          if (values != null) {
            for (var tv : values) {
              if (tv.value() instanceof String str) {
                var lower = str.toLowerCase();
                if (lower.contains("error") || lower.contains("exception") || lower.contains("fault")) {
                  errorCount++;
                  if (errorSamples.size() < 5) {
                    errorSamples.add(str.length() > 100 ? str.substring(0, 100) + "..." : str);
                  }
                }
              }
            }
          }
        }
      }

      var errors = new JsonObject();
      errors.addProperty("total_errors", errorCount);
      errors.add("samples", GSON.toJsonTree(errorSamples));
      report.add("errors", errors);

      // Code metadata
      var codeInfo = new JsonObject();
      for (var entryName : log.entries().keySet()) {
        if (entryName.contains("GitSHA")) {
          var values = log.values().get(entryName);
          if (values != null && !values.isEmpty()) {
            codeInfo.addProperty("git_sha", String.valueOf(values.get(0).value()));
          }
        } else if (entryName.contains("GitBranch")) {
          var values = log.values().get(entryName);
          if (values != null && !values.isEmpty()) {
            codeInfo.addProperty("git_branch", String.valueOf(values.get(0).value()));
          }
        }
      }
      if (codeInfo.size() > 0) {
        report.add("code_info", codeInfo);
      }

      // Data type summary
      var typeCounts = new HashMap<String, Integer>();
      for (var entry : log.entries().values()) {
        typeCounts.merge(entry.type(), 1, Integer::sum);
      }
      var types = new JsonObject();
      typeCounts.entrySet().stream()
          .sorted((a, b) -> b.getValue() - a.getValue())
          .limit(10)
          .forEach(e -> types.addProperty(e.getKey(), e.getValue()));
      report.add("top_data_types", types);

      // Add data quality from battery voltage values if available
      for (var entryName : log.entries().keySet()) {
        if (entryName.toLowerCase().contains("batteryvoltage") || entryName.toLowerCase().contains("battery_voltage")) {
          var qualityValues = log.values().get(entryName);
          if (qualityValues != null && !qualityValues.isEmpty()) {
            var quality = DataQuality.fromValues(qualityValues);
            report.add("data_quality", quality.toJson());
            var directives = AnalysisDirectives.fromQuality(quality)
                .addSingleMatchCaveat()
                .addGuidance("Report is a summary — use individual tools for detailed analysis");
            report.add("server_analysis_directives", directives.toJson());
            break;
          }
        }
      }

      return report;
    }
  }
}
