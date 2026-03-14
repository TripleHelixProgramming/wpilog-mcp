package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.triplehelix.wpilogmcp.log.LogManager.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * Robot-specific analysis tools for WPILOG data.
 */
public final class RobotAnalysisTools {

  private RobotAnalysisTools() {}

  public static void registerAll(McpServer server) {
    server.registerTool(new GetMatchPhasesTool());
    server.registerTool(new AnalyzeSwerveTool());
    server.registerTool(new PowerAnalysisTool());
    server.registerTool(new CanHealthTool());
    server.registerTool(new CompareMatchesTool());
    server.registerTool(new GetCodeMetadataTool());
  }

  static class GetMatchPhasesTool implements Tool {
    @Override
    public String name() { return "get_match_phases"; }

    @Override
    public String description() {
      return "Auto-detect match phases (autonomous 0-15s, teleop 15-135s, endgame 120-135s) from DriverStation data or log timestamps.";
    }

    @Override
    public JsonObject inputSchema() { return new SchemaBuilder().build(); }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var matchStart = log.entries().keySet().stream()
          .filter(name -> name.contains("DriverStation") && name.contains("Enabled"))
          .map(name -> log.values().get(name))
          .filter(Objects::nonNull)
          .flatMap(List::stream)
          .filter(tv -> tv.value() instanceof Boolean enabled && enabled)
          .mapToDouble(TimestampedValue::timestamp)
          .min();

      double start = matchStart.orElse(log.minTimestamp());
      double end = log.maxTimestamp();

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("log_duration", log.duration());

      var phases = new JsonObject();
      phases.add("autonomous", createPhase(start, Math.min(start + 15, end), "Auto (0-15s)"));
      if (log.duration() > 15) {
        phases.add("teleop", createPhase(start + 15, Math.min(start + 135, end), "Teleop (15-135s)"));
      }
      if (log.duration() > 120) {
        phases.add("endgame", createPhase(start + 120, Math.min(start + 135, end), "Endgame (120-135s)"));
      }
      result.add("phases", phases);
      return result;
    }

    private JsonObject createPhase(double s, double e, String desc) {
      var obj = new JsonObject();
      obj.addProperty("start", s);
      obj.addProperty("end", e);
      obj.addProperty("duration", Math.max(0, e - s));
      obj.addProperty("description", desc);
      return obj;
    }
  }

  static class AnalyzeSwerveTool implements Tool {
    @Override
    public String name() { return "analyze_swerve"; }

    @Override
    public String description() { return "Analyze swerve drive module performance. Looks for wheel slip, module synchronization issues, and speed discrepancies."; }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("module_prefix", "string", "Entry path prefix (e.g., '/Drive/Module')", false)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var prefix = arguments.has("module_prefix") ? arguments.get("module_prefix").getAsString() : null;

      var categorizedEntries = log.entries().entrySet().stream()
          .filter(e -> prefix == null || e.getKey().startsWith(prefix))
          .collect(Collectors.groupingBy(e -> {
            var type = e.getValue().type();
            if (type.contains("SwerveModuleState")) return "module_states";
            if (type.contains("SwerveModulePosition")) return "module_positions";
            if (type.contains("ChassisSpeeds")) return "chassis_speeds";
            return "other";
          }, Collectors.mapping(Map.Entry::getKey, Collectors.toList())));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("swerve_entries", GSON.toJsonTree(categorizedEntries));

      var states = categorizedEntries.get("module_states");
      if (states != null) {
        var analysis = states.stream()
            .map(name -> analyzeModule(name, log.values().get(name)))
            .filter(Objects::nonNull)
            .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
        result.add("module_analysis", analysis);
      }

      return result;
    }

    private JsonObject analyzeModule(String name, List<TimestampedValue> values) {
      if (values == null || values.isEmpty()) return null;
      
      var speeds = values.stream()
          .flatMap(tv -> {
            if (tv.value() instanceof Map) {
              return java.util.stream.Stream.of((Map<?, ?>) tv.value());
            } else if (tv.value() instanceof List) {
              return ((List<?>) tv.value()).stream()
                  .filter(v -> v instanceof Map)
                  .map(v -> (Map<?, ?>) v);
            }
            return java.util.stream.Stream.empty();
          })
          .map(m -> m.get("speed_mps"))
          .filter(v -> v instanceof Number)
          .mapToDouble(v -> Math.abs(((Number) v).doubleValue()))
          .toArray();

      if (speeds.length == 0) return null;
      var stats = java.util.Arrays.stream(speeds).summaryStatistics();
      var obj = new JsonObject();
      obj.addProperty("entry", name);
      obj.addProperty("max_speed_mps", stats.getMax());
      obj.addProperty("avg_speed_mps", stats.getAverage());
      obj.addProperty("sample_count", stats.getCount());
      return obj;
    }
  }

  static class PowerAnalysisTool implements Tool {
    @Override
    public String name() { return "power_analysis"; }

    @Override
    public String description() { return "Analyze battery and current distribution data. Finds peak currents per channel and brownout risk."; }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("power_prefix", "string", "Entry path prefix (e.g., '/PDP')", false)
          .addNumberProperty("brownout_threshold", "Voltage threshold (default 7.0V)", false, 7.0)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var prefix = arguments.has("power_prefix") ? arguments.get("power_prefix").getAsString() : null;
      double threshold = arguments.has("brownout_threshold") ? arguments.get("brownout_threshold").getAsDouble() : 7.0;

      var voltageEntry = log.entries().keySet().stream()
          .filter(n -> (prefix == null || n.startsWith(prefix)) && (n.toLowerCase().contains("voltage")))
          .findFirst();

      var result = new JsonObject();
      result.addProperty("success", true);

      voltageEntry.ifPresent(name -> {
        var values = log.values().get(name);
        if (values != null && !values.isEmpty()) {
          var stats = values.stream()
              .filter(tv -> tv.value() instanceof Number)
              .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
              .summaryStatistics();
          
          var vObj = new JsonObject();
          vObj.addProperty("entry", name);
          vObj.addProperty("min_voltage", stats.getMin());
          vObj.addProperty("max_voltage", stats.getMax());
          vObj.addProperty("avg_voltage", stats.getAverage());
          
          long brownouts = values.stream()
              .filter(tv -> tv.value() instanceof Number && ((Number) tv.value()).doubleValue() < threshold)
              .count();
          vObj.addProperty("samples_below_threshold", brownouts);
          vObj.addProperty("brownout_risk", brownouts > 0 ? "HIGH" : (stats.getMin() < threshold + 1 ? "MODERATE" : "LOW"));
          result.add("voltage_analysis", vObj);
        }
      });

      return result;
    }
  }

  static class CanHealthTool implements Tool {
    @Override
    public String name() { return "can_health"; }

    @Override
    public String description() { return "Analyze CAN bus health by looking for timeout errors and communication issues."; }

    @Override
    public JsonObject inputSchema() { return new SchemaBuilder().build(); }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var errorCounts = log.entries().entrySet().stream()
          .filter(e -> "string".equals(e.getValue().type()))
          .collect(Collectors.toMap(Map.Entry::getKey, e -> {
            var values = log.values().get(e.getKey());
            return values == null ? 0L : values.stream()
                .filter(tv -> tv.value() instanceof String s && isCanError(s))
                .count();
          }))
          .entrySet().stream()
          .filter(e -> e.getValue() > 0)
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("error_counts_by_entry", GSON.toJsonTree(errorCounts));
      
      long total = errorCounts.values().stream().mapToLong(Long::longValue).sum();
      result.addProperty("total_can_errors", total);
      result.addProperty("health_assessment", total == 0 ? "GOOD" : (total < 50 ? "CONCERNING" : "POOR"));
      
      return result;
    }

    private boolean isCanError(String s) {
      var lower = s.toLowerCase();
      return lower.contains("can") && (lower.contains("timeout") || lower.contains("error") || lower.contains("fault"));
    }
  }

  static class CompareMatchesTool implements Tool {
    @Override
    public String name() { return "compare_matches"; }

    @Override
    public String description() { return "Compare statistics for an entry across multiple loaded log files."; }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().addProperty("name", "string", "Entry name", true).build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var loadedPaths = getLogManager().getLoadedLogPaths();
      if (loadedPaths.size() < 2) return errorResult("Need at least 2 logs loaded to compare. Currently loaded: " + loadedPaths.size());

      var name = arguments.get("name").getAsString();
      var originalActive = getLogManager().getActiveLogPath();

      var comparisons = loadedPaths.stream()
          .map(path -> {
            getLogManager().setActiveLog(path);
            var log = getLogManager().getActiveLog();
            var stats = new JsonObject();
            stats.addProperty("log_filename", Path.of(path).getFileName().toString());
            
            var vals = log.values().get(name);
            if (vals != null) {
              var s = vals.stream()
                  .filter(tv -> tv.value() instanceof Number)
                  .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
                  .summaryStatistics();
              if (s.getCount() > 0) {
                var sObj = new JsonObject();
                sObj.addProperty("min", s.getMin());
                sObj.addProperty("max", s.getMax());
                sObj.addProperty("mean", s.getAverage());
                stats.add("statistics", sObj);
              }
            }
            return stats;
          })
          .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

      if (originalActive != null) getLogManager().setActiveLog(originalActive);

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("comparisons", comparisons);
      return result;
    }
  }

  static class GetCodeMetadataTool implements Tool {
    @Override
    public String name() { return "get_code_metadata"; }

    @Override
    public String description() { return "Extract code metadata including Git SHA, branch, and build date."; }

    @Override
    public JsonObject inputSchema() { return new SchemaBuilder().build(); }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var keys = List.of("GitSHA", "GitBranch", "GitDirty", "BuildDate", "ProjectName", "Version");
      var found = log.entries().keySet().stream()
          .filter(name -> keys.stream().anyMatch(name::contains))
          .collect(Collectors.toMap(
              name -> keys.stream().filter(name::contains).findFirst().get(),
              name -> log.values().get(name).get(0).value(),
              (v1, v2) -> v1));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("metadata", GSON.toJsonTree(found));
      return result;
    }
  }
}
