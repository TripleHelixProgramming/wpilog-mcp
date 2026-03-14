package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

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

  private ExportTools() {}

  /**
   * Registers all export tools with the MCP server.
   */
  public static void registerAll(McpServer server) {
    server.registerTool(new ExportCsvTool());
    server.registerTool(new GenerateReportTool());
  }

  static class ExportCsvTool implements Tool {
    @Override
    public String name() {
      return "export_csv";
    }

    @Override
    public String description() {
      return "Export entry data to a CSV file for external analysis in Excel, Python, or MATLAB.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name to export", true)
          .addProperty("output_path", "string", "Path for output CSV file", true)
          .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds", false, null)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) {
        return errorResult("No log loaded");
      }

      var name = arguments.get("name").getAsString();
      var outputPath = arguments.get("output_path").getAsString();
      var startTime = arguments.has("start_time") && !arguments.get("start_time").isJsonNull()
          ? arguments.get("start_time").getAsDouble()
          : null;
      var endTime = arguments.has("end_time") && !arguments.get("end_time").isJsonNull()
          ? arguments.get("end_time").getAsDouble()
          : null;

      var outputFilePath = Path.of(outputPath).toAbsolutePath().normalize();
      if (!isPathAllowed(outputFilePath)) {
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
          writer.println("timestamp_sec,value");
        }

        for (var tv : values) {
          double t = tv.timestamp();
          if ((startTime != null && t < startTime) || (endTime != null && t > endTime)) {
            continue;
          }

          if (tv.value() instanceof List) {
            var list = (List<?>) tv.value();
            for (int i = 0; i < list.size(); i++) {
              var element = list.get(i);
              if (element instanceof Map) {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) element;
                var sb = new StringBuilder();
                sb.append(t).append(",").append(i);
                map.values().forEach(v -> sb.append(",").append(v));
                writer.println(sb);
              } else {
                writer.println(t + "," + i + "," + element);
              }
              rowCount++;
            }
          } else if (tv.value() instanceof Map) {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) tv.value();
            var sb = new StringBuilder();
            sb.append(t);
            map.values().forEach(v -> sb.append(",").append(v));
            writer.println(sb);
            rowCount++;
          } else {
            writer.println(t + "," + tv.value());
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

    private boolean isPathAllowed(Path path) {
      var normalizedPath = path.toAbsolutePath().normalize();

      var logDir = LogDirectory.getInstance().getLogDirectory();
      if (logDir != null) {
        var normalizedLogDir = logDir.toAbsolutePath().normalize();
        if (normalizedPath.startsWith(normalizedLogDir)) {
          return true;
        }
      }

      var tempDir = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
      if (normalizedPath.startsWith(tempDir)) {
        return true;
      }

      var activeLog = getLogManager().getActiveLog();
      if (activeLog != null && activeLog.path() != null) {
        var logFileDir = Path.of(activeLog.path()).getParent();
        if (logFileDir != null) {
          var normalizedLogFileDir = logFileDir.toAbsolutePath().normalize();
          if (normalizedPath.startsWith(normalizedLogFileDir)) {
            return true;
          }
        }
      }

      return false;
    }
  }

  static class GenerateReportTool implements Tool {
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
    public JsonObject inputSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) {
        return errorResult("No log loaded");
      }

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
            double minV = Double.MAX_VALUE, maxV = Double.MIN_VALUE;
            for (var tv : values) {
              if (tv.value() instanceof Number num) {
                double v = num.doubleValue();
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
              }
            }
            var battery = new JsonObject();
            battery.addProperty("entry", entryName);
            battery.addProperty("min_voltage", minV);
            battery.addProperty("max_voltage", maxV);
            battery.addProperty("brownout_risk", minV < 7.0 ? "HIGH" : (minV < 9.0 ? "MODERATE" : "LOW"));
            report.add("battery", battery);
            break;
          }
        }
      }

      // Error count
      int errorCount = 0;
      var errorSamples = new ArrayList<String>();
      for (var e : log.entries().entrySet()) {
        if ("string".equals(e.getValue().type())) {
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

      return report;
    }
  }
}
