package org.triplehelix.wpilogmcp.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.triplehelix.wpilogmcp.cache.DiskCache;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;
import org.triplehelix.wpilogmcp.tba.TbaConfig;
import org.triplehelix.wpilogmcp.tools.CoreTools;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.FrcDomainTools;
import org.triplehelix.wpilogmcp.tools.QueryTools;
import org.triplehelix.wpilogmcp.tools.RevLogTools;
import org.triplehelix.wpilogmcp.tools.RobotAnalysisTools;
import org.triplehelix.wpilogmcp.tools.StatisticsTools;
import org.triplehelix.wpilogmcp.tools.TbaTools;

/**
 * Integration stress test that exercises all MCP server functionality with real log files.
 *
 * <p>Configuration is loaded from {@code .mcp.json} in the project root directory, which
 * is the same file used to configure the MCP server for Claude/Cursor. This ensures
 * the stress test runs against the same log files and with the same settings as production.
 *
 * <p>Alternatively, provide the log directory via system property or environment variable:
 * <pre>
 * ./gradlew stressTest
 * ./gradlew test -Dstress.logdir=/path/to/logs --tests "*StressTest*"
 * STRESS_LOGDIR=/path/to/logs ./gradlew test --tests "*StressTest*"
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MCP Server Stress Test")
class StressTest {

  private static Path logDirectory;
  private static List<Tool> tools;
  private static List<String> availableLogPaths;
  private static List<String> loadedEntryNames;
  private static Path diskCacheDir;

  // Statistics
  private static final AtomicInteger totalOperations = new AtomicInteger(0);
  private static final AtomicInteger successfulOperations = new AtomicInteger(0);
  private static final AtomicInteger failedOperations = new AtomicInteger(0);
  private static long totalTimeMs = 0;

  @BeforeAll
  static void setup() {
    // Stress test only runs when explicitly invoked via:
    //   ./gradlew stressTest                    (sets stress.logdir system property)
    //   ./gradlew test -Dstress.logdir=/path    (explicit system property)
    //   STRESS_LOGDIR=/path ./gradlew test      (environment variable)
    // Never runs during normal "./gradlew test" to avoid loading real log files.
    String logDirPath = System.getProperty("stress.logdir");
    if (logDirPath == null || logDirPath.isEmpty()) {
      logDirPath = System.getenv("STRESS_LOGDIR");
    }

    // Only try .mcp.json when stress.logdir is set (the stressTest gradle task sets it)
    boolean explicitlyEnabled = logDirPath != null && !logDirPath.isEmpty();
    if (explicitlyEnabled && ".mcp.json".equals(logDirPath)) {
      // Special sentinel: the stressTest task sets stress.logdir=.mcp.json to
      // request auto-configuration from the MCP config file
      logDirPath = loadFromMcpJson();
    }

    assumeTrue(logDirPath != null && !logDirPath.isEmpty(),
        "Stress test skipped: Set -Dstress.logdir=/path or STRESS_LOGDIR, or use ./gradlew stressTest.");

    logDirectory = Path.of(logDirPath);
    assumeTrue(Files.isDirectory(logDirectory),
        "Stress test skipped: Log directory does not exist: " + logDirPath);

    // Configure LogDirectory and security
    LogDirectory.getInstance().setLogDirectory(logDirPath);
    LogManager.getInstance().addAllowedDirectory(logDirPath);

    // Register all tools
    tools = new ArrayList<>();
    var capturingRegistry = new ToolRegistry() {
      @Override
      public void registerTool(Tool tool) {
        tools.add(tool);
      }
    };

    CoreTools.registerAll(capturingRegistry);
    QueryTools.registerAll(capturingRegistry);
    StatisticsTools.registerAll(capturingRegistry);
    FrcDomainTools.registerAll(capturingRegistry);
    RobotAnalysisTools.registerAll(capturingRegistry);
    ExportTools.registerAll(capturingRegistry);
    TbaTools.registerAll(capturingRegistry);
    RevLogTools.registerAll(capturingRegistry);

    System.out.println("\n========================================");
    System.out.println("MCP Server Stress Test");
    System.out.println("========================================");
    System.out.println("Log directory: " + logDirectory);
    System.out.println("Registered " + tools.size() + " tools");
    System.out.println("Disk cache enabled: " + LogManager.getInstance().getDiskCache().isEnabled());
    System.out.println("TBA configured: " + TbaConfig.getInstance().isConfigured());
    System.out.println();
  }

  /**
   * Reads log directory, team number, and TBA key from .mcp.json in the project root.
   */
  private static String loadFromMcpJson() {
    // Walk up from CWD or use known project root paths
    Path[] candidates = {
        Path.of(".mcp.json"),
        Path.of(System.getProperty("user.dir"), ".mcp.json"),
        // Gradle runs tests from the project root
    };

    for (Path candidate : candidates) {
      if (Files.exists(candidate)) {
        try {
          String json = Files.readString(candidate);
          var root = new Gson().fromJson(json, JsonObject.class);
          var servers = root.getAsJsonObject("mcpServers");
          if (servers == null) continue;

          // Find the wpilog server config
          for (var entry : servers.entrySet()) {
            var serverConfig = entry.getValue().getAsJsonObject();
            var args = serverConfig.getAsJsonArray("args");

            String logDir = null;
            String teamNumber = null;

            // Parse args array for -logdir and -team
            if (args != null) {
              for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i).getAsString();
                if ("-logdir".equals(arg) && i + 1 < args.size()) {
                  logDir = args.get(i + 1).getAsString();
                }
                if ("-team".equals(arg) && i + 1 < args.size()) {
                  teamNumber = args.get(i + 1).getAsString();
                }
              }
            }

            // Apply team number
            if (teamNumber != null) {
              try {
                LogDirectory.getInstance().setDefaultTeamNumber(Integer.parseInt(teamNumber));
                System.out.println("Team number from .mcp.json: " + teamNumber);
              } catch (NumberFormatException e) {
                // ignore
              }
            }

            // Apply TBA key from env block
            if (serverConfig.has("env")) {
              var env = serverConfig.getAsJsonObject("env");
              if (env.has("TBA_API_KEY")) {
                String tbaKey = env.get("TBA_API_KEY").getAsString();
                TbaConfig.getInstance().setApiKey(tbaKey);
                TbaConfig.getInstance().applyToClient();
                System.out.println("TBA API key loaded from .mcp.json");
              }
            }

            if (logDir != null) {
              System.out.println("Log directory from .mcp.json: " + logDir);
              return logDir;
            }
          }
        } catch (IOException e) {
          System.err.println("Failed to read .mcp.json: " + e.getMessage());
        }
      }
    }
    return null;
  }

  @BeforeEach
  void resetState() {
    LogManager.getInstance().unloadAllLogs();
  }

  // ==================== 1. Discovery ====================

  @Test
  @Order(1)
  @DisplayName("1. List available logs")
  void listAvailableLogs() throws Exception {
    var tool = findTool("list_available_logs");
    var result = executeTool(tool, new JsonObject());

    assertTrue(result.has("success") && result.get("success").getAsBoolean(),
        "list_available_logs failed: " + result);

    int logCount = result.get("log_count").getAsInt();
    System.out.println("Found " + logCount + " log files");

    availableLogPaths = new ArrayList<>();
    var logsArray = result.getAsJsonArray("logs");
    for (var logEntry : logsArray) {
      var logObj = logEntry.getAsJsonObject();
      availableLogPaths.add(logObj.get("path").getAsString());
      System.out.println("  - " + logObj.get("friendly_name").getAsString() +
          " (" + formatBytes(logObj.get("size_bytes").getAsLong()) + ")");
    }

    assumeTrue(!availableLogPaths.isEmpty(), "No log files found in directory");
  }

  // ==================== 2. Load ====================

  @Test
  @Order(2)
  @DisplayName("2. Load all logs sequentially")
  void loadAllLogsSequentially() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());

    var tool = findTool("load_log");
    int loaded = 0, failed = 0;
    long totalLoadTime = 0;

    System.out.println("\nLoading logs sequentially:");
    for (String path : availableLogPaths) {
      var args = new JsonObject();
      args.addProperty("path", path);

      long start = System.currentTimeMillis();
      try {
        var result = executeTool(tool, args);
        long duration = System.currentTimeMillis() - start;
        totalLoadTime += duration;

        if (result.has("success") && result.get("success").getAsBoolean()) {
          loaded++;
          int entryCount = result.get("entry_count").getAsInt();
          double durationSec = result.getAsJsonObject("time_range_sec").get("duration").getAsDouble();
          System.out.printf("  [OK] %s - %d entries, %.1fs duration, loaded in %dms%n",
              Path.of(path).getFileName(), entryCount, durationSec, duration);
        } else {
          failed++;
          System.out.printf("  [FAIL] %s - %s%n", Path.of(path).getFileName(),
              result.has("error") ? result.get("error").getAsString() : "unknown error");
        }
      } catch (OutOfMemoryError e) {
        failed++;
        System.gc();
        System.out.printf("  [OOM] %s - file too large%n", Path.of(path).getFileName());
      } catch (Exception e) {
        failed++;
        System.out.printf("  [ERROR] %s - %s%n", Path.of(path).getFileName(), e.getMessage());
      }

      LogManager.getInstance().unloadAllLogs();
    }

    System.out.printf("%nLoaded %d/%d logs (%d failed) in %dms total%n",
        loaded, availableLogPaths.size(), failed, totalLoadTime);
    assertTrue(loaded > 0, "Failed to load any logs");
  }

  // ==================== 3. CoreTools ====================

  @Test
  @Order(3)
  @DisplayName("3. Exercise CoreTools")
  void exerciseCoreTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    String logPath = availableLogPaths.get(0);
    loadLog(logPath);

    System.out.println("\nExercising CoreTools on: " + Path.of(logPath).getFileName());

    testTool("list_entries", new JsonObject(), result -> {
      var entries = result.getAsJsonArray("entries");
      loadedEntryNames = new ArrayList<>();
      for (var entry : entries) {
        loadedEntryNames.add(entry.getAsJsonObject().get("name").getAsString());
      }
      System.out.println("  list_entries: " + loadedEntryNames.size() + " entries");
    });

    if (loadedEntryNames != null && !loadedEntryNames.isEmpty()) {
      for (int i = 0; i < Math.min(3, loadedEntryNames.size()); i++) {
        String entryName = loadedEntryNames.get(i);
        var args = new JsonObject();
        args.addProperty("name", entryName);
        testTool("get_entry_info", args, result -> {
          System.out.println("  get_entry_info: " + entryName + " - " +
              result.get("type").getAsString() + ", " +
              result.get("sample_count").getAsInt() + " samples");
        });
      }

      var readArgs = new JsonObject();
      readArgs.addProperty("name", loadedEntryNames.get(0));
      readArgs.addProperty("limit", 10);
      testTool("read_entry", readArgs, result -> {
        int returned = result.has("samples") ? result.getAsJsonArray("samples").size() : 0;
        System.out.println("  read_entry (limit 10): " + returned + " samples");
      });
    }

    testTool("list_loaded_logs", new JsonObject(), result -> {
      int loaded = result.has("loaded_count") ? result.get("loaded_count").getAsInt() : 0;
      System.out.println("  list_loaded_logs: " + loaded + " loaded");
    });

    testTool("list_struct_types", new JsonObject(), result -> {
      int total = 0;
      for (String cat : List.of("geometry", "kinematics", "vision", "autonomous")) {
        if (result.has(cat)) total += result.getAsJsonArray(cat).size();
      }
      System.out.println("  list_struct_types: " + total + " struct types");
    });

    testTool("health_check", new JsonObject(), result -> {
      String status = result.has("status") ? result.get("status").getAsString() : "unknown";
      System.out.println("  health_check: " + status);
    });
  }

  // ==================== 4. QueryTools ====================

  @Test
  @Order(4)
  @DisplayName("4. Exercise QueryTools")
  void exerciseQueryTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    assumeTrue(loadedEntryNames != null && !loadedEntryNames.isEmpty());
    loadLog(availableLogPaths.get(0));

    System.out.println("\nExercising QueryTools:");

    testTool("search_entries", new JsonObject(), result -> {
      System.out.println("  search_entries (all): " + result.getAsJsonArray("matches").size());
    });

    var pArgs = new JsonObject();
    pArgs.addProperty("pattern", ".");
    testTool("search_entries", pArgs, result -> {
      System.out.println("  search_entries (pattern '.'): " + result.getAsJsonArray("matches").size());
    });

    testTool("get_types", new JsonObject(), result -> {
      int count = result.has("types") ? result.getAsJsonArray("types").size() : 0;
      System.out.println("  get_types: " + count + " types");
    });

    var numericForCondition = findNumericEntries(1);
    if (!numericForCondition.isEmpty()) {
      var condArgs = new JsonObject();
      condArgs.addProperty("name", numericForCondition.get(0));
      condArgs.addProperty("operator", "gt");
      condArgs.addProperty("threshold", 0.0);
      condArgs.addProperty("limit", 10);
      testTool("find_condition", condArgs, result -> {
        int transitions = result.has("transitions") ? result.getAsJsonArray("transitions").size() : 0;
        System.out.println("  find_condition: " + transitions + " transitions");
      });
    }
  }

  // ==================== 5. StatisticsTools + Data Quality ====================

  @Test
  @Order(5)
  @DisplayName("5. Exercise StatisticsTools (with data quality & directives)")
  void exerciseStatisticsTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    assumeTrue(loadedEntryNames != null && !loadedEntryNames.isEmpty());
    loadLog(availableLogPaths.get(0));

    System.out.println("\nExercising StatisticsTools:");
    var numericEntries = findNumericEntries(5);

    for (String entry : numericEntries) {
      var args = new JsonObject();
      args.addProperty("name", entry);
      testTool("get_statistics", args, result -> {
        if (result.has("mean")) {
          System.out.printf("  get_statistics (%s): mean=%.3f, std=%.3f",
              entry, result.get("mean").getAsDouble(), result.get("std_dev").getAsDouble());

          // Verify data quality metadata is present
          if (result.has("data_quality")) {
            var dq = result.getAsJsonObject("data_quality");
            double score = dq.get("quality_score").getAsDouble();
            int samples = dq.get("sample_count").getAsInt();
            int gaps = dq.get("gap_count").getAsInt();
            System.out.printf(", quality=%.2f (%d samples, %d gaps)", score, samples, gaps);
          }

          // Verify analysis directives are present
          if (result.has("server_analysis_directives")) {
            var directives = result.getAsJsonObject("server_analysis_directives");
            String confidence = directives.get("confidence_level").getAsString();
            System.out.printf(", confidence=%s", confidence);
            if (directives.has("interpretation_guidance")) {
              int guidanceCount = directives.getAsJsonArray("interpretation_guidance").size();
              System.out.printf(" (%d guidance items)", guidanceCount);
            }
          }
          System.out.println();
        }
      });
    }

    // detect_anomalies
    if (!numericEntries.isEmpty()) {
      var anomalyArgs = new JsonObject();
      anomalyArgs.addProperty("name", numericEntries.get(0));
      anomalyArgs.addProperty("limit", 5);
      testTool("detect_anomalies", anomalyArgs, result -> {
        int anomalies = result.has("anomaly_count") ? result.get("anomaly_count").getAsInt() : 0;
        System.out.println("  detect_anomalies: " + anomalies + " anomalies");
      });
    }

    // find_peaks
    if (!numericEntries.isEmpty()) {
      var peakArgs = new JsonObject();
      peakArgs.addProperty("name", numericEntries.get(0));
      peakArgs.addProperty("limit", 5);
      testTool("find_peaks", peakArgs, result -> {
        int maxima = result.has("maxima") ? result.getAsJsonArray("maxima").size() : 0;
        int minima = result.has("minima") ? result.getAsJsonArray("minima").size() : 0;
        System.out.println("  find_peaks: " + maxima + " maxima, " + minima + " minima");
      });
    }

    // rate_of_change
    if (!numericEntries.isEmpty()) {
      var rateArgs = new JsonObject();
      rateArgs.addProperty("name", numericEntries.get(0));
      rateArgs.addProperty("limit", 10);
      testTool("rate_of_change", rateArgs, result -> {
        if (result.has("statistics")) {
          double avgRate = result.getAsJsonObject("statistics").get("avg_rate").getAsDouble();
          System.out.printf("  rate_of_change: avg_rate=%.4f%n", avgRate);
        }
      });
    }

    // time_correlate
    if (numericEntries.size() >= 2) {
      var corrArgs = new JsonObject();
      corrArgs.addProperty("name1", numericEntries.get(0));
      corrArgs.addProperty("name2", numericEntries.get(1));
      testTool("time_correlate", corrArgs, result -> {
        if (result.has("correlation")) {
          System.out.printf("  time_correlate: r=%.4f%n", result.get("correlation").getAsDouble());
        }
      });
    }

    // compare_entries
    if (numericEntries.size() >= 2) {
      var compareArgs = new JsonObject();
      compareArgs.addProperty("name1", numericEntries.get(0));
      compareArgs.addProperty("name2", numericEntries.get(1));
      testTool("compare_entries", compareArgs, result -> {
        if (result.has("rmse")) {
          System.out.printf("  compare_entries: rmse=%.4f%n", result.get("rmse").getAsDouble());
        }
      });
    }
  }

  // ==================== 6. FRC Domain + Robot Analysis ====================

  @Test
  @Order(6)
  @DisplayName("6. Exercise FrcDomainTools and RobotAnalysisTools")
  void exerciseFrcDomainTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    loadLog(availableLogPaths.get(0));

    System.out.println("\nExercising FrcDomainTools and RobotAnalysisTools:");

    // get_match_phases — now data-driven, not hardcoded
    testTool("get_match_phases", new JsonObject(), result -> {
      String source = result.has("source") ? result.get("source").getAsString() : "unknown";
      System.out.println("  get_match_phases (source: " + source + "):");
      if (result.has("phases")) {
        var phases = result.getAsJsonObject("phases");
        for (var entry : phases.entrySet()) {
          var phase = entry.getValue().getAsJsonObject();
          System.out.printf("    %s: %.2fs - %.2fs (%.1fs)%n",
              entry.getKey(), phase.get("start").getAsDouble(),
              phase.get("end").getAsDouble(), phase.get("duration").getAsDouble());
        }
      }
      if (result.has("match_duration")) {
        System.out.printf("    Total match duration: %.1fs%n", result.get("match_duration").getAsDouble());
      }
      if (result.has("warnings")) {
        for (var w : result.getAsJsonArray("warnings")) {
          System.out.println("    WARNING: " + w.getAsString());
        }
      }
    });

    testTool("analyze_auto", new JsonObject(), result -> {
      if (result.has("auto_duration")) {
        System.out.printf("  analyze_auto: %.2fs%n", result.get("auto_duration").getAsDouble());
      } else {
        System.out.println("  analyze_auto: no auto period detected");
      }
    });

    testTool("get_ds_timeline", new JsonObject(), result -> {
      int events = result.has("events") ? result.getAsJsonArray("events").size() : 0;
      System.out.println("  get_ds_timeline: " + events + " events");
    });

    // analyze_vision
    testTool("analyze_vision", new JsonObject(), result -> {
      if (result.has("target_acquisition")) {
        var acq = result.getAsJsonArray("target_acquisition");
        System.out.println("  analyze_vision: " + acq.size() + " target entries analyzed");
        for (int i = 0; i < Math.min(3, acq.size()); i++) {
          var a = acq.get(i).getAsJsonObject();
          System.out.printf("    %s: %.0f%% acquisition rate, %d flicker events%n",
              a.get("entry").getAsString(),
              a.get("acquisition_rate").getAsDouble() * 100,
              a.get("flicker_events").getAsInt());
        }
      }
      if (result.has("pose_jumps")) {
        System.out.println("    Pose jumps: " + result.get("jump_count").getAsInt());
      }
    });

    // analyze_replay_drift (AdvantageKit)
    testTool("analyze_replay_drift", new JsonObject(), result -> {
      int divergent = result.has("divergent_count") ? result.get("divergent_count").getAsInt() : 0;
      System.out.println("  analyze_replay_drift: " + divergent + " divergent entries");
    });

    // profile_mechanism
    for (String name : List.of("Arm", "Elevator", "Shooter", "Intake", "Drivetrain", "Swerve")) {
      var mechArgs = new JsonObject();
      mechArgs.addProperty("mechanism_name", name);
      testTool("profile_mechanism", mechArgs, result -> {
        if (result.has("following_error")) {
          double rmse = result.getAsJsonObject("following_error").get("rmse").getAsDouble();
          System.out.printf("  profile_mechanism (%s): rmse=%.4f%n", name, rmse);
        }
      });
    }

    // analyze_loop_timing (with unit auto-detect)
    testTool("analyze_loop_timing", new JsonObject(), result -> {
      if (result.has("statistics")) {
        var stats = result.getAsJsonObject("statistics");
        System.out.printf("  analyze_loop_timing: avg=%.2fms, p99=%.2fms, %d violations%n",
            stats.get("avg_ms").getAsDouble(), stats.get("p99_ms").getAsDouble(),
            result.get("violation_count").getAsInt());
      } else {
        System.out.println("  analyze_loop_timing: no loop timing data found");
      }
    });

    // analyze_can_bus
    testTool("analyze_can_bus", new JsonObject(), result -> {
      if (result.has("utilization")) {
        System.out.println("  analyze_can_bus: utilization data found");
      } else if (result.has("errors")) {
        System.out.println("  analyze_can_bus: error data found");
      } else {
        System.out.println("  analyze_can_bus: no CAN data found");
      }
    });

    // predict_battery_health
    testTool("predict_battery_health", new JsonObject(), result -> {
      if (result.has("health_score")) {
        int score = result.get("health_score").getAsInt();
        String risk = result.get("risk_level").getAsString();
        System.out.printf("  predict_battery_health: score=%d, risk=%s%n", score, risk);
        if (result.has("voltage_stats")) {
          var vs = result.getAsJsonObject("voltage_stats");
          System.out.printf("    voltage: min=%.2fV, avg=%.2fV, sag=%.2fV%n",
              vs.get("min_volts").getAsDouble(),
              vs.get("avg_volts").getAsDouble(),
              vs.get("voltage_sag").getAsDouble());
        }
        if (result.has("recommendations")) {
          for (var rec : result.getAsJsonArray("recommendations")) {
            System.out.println("    recommendation: " + rec.getAsString());
          }
        }
      }
    });

    // analyze_swerve
    testTool("analyze_swerve", new JsonObject(), result -> {
      if (result.has("swerve_entries")) {
        System.out.println("  analyze_swerve: swerve entries found");
      } else {
        System.out.println("  analyze_swerve: no swerve data");
      }
    });

    // power_analysis
    testTool("power_analysis", new JsonObject(), result -> {
      if (result.has("voltage_analysis")) {
        var va = result.getAsJsonObject("voltage_analysis");
        System.out.printf("  power_analysis: min=%.2fV, avg=%.2fV, %d below threshold%n",
            va.get("min_voltage").getAsDouble(), va.get("avg_voltage").getAsDouble(),
            va.get("samples_below_threshold").getAsLong());
      } else {
        System.out.println("  power_analysis: no voltage data");
      }
    });

    testTool("can_health", new JsonObject(), result -> {
      long total = result.has("total_can_errors") ? result.get("total_can_errors").getAsLong() : 0;
      String health = result.has("health_assessment") ? result.get("health_assessment").getAsString() : "unknown";
      System.out.println("  can_health: " + total + " errors, assessment=" + health);
    });

    testTool("get_code_metadata", new JsonObject(), result -> {
      if (result.has("metadata")) {
        System.out.println("  get_code_metadata: metadata found");
      } else {
        System.out.println("  get_code_metadata: no metadata");
      }
    });

    // analyze_cycles
    if (loadedEntryNames != null) {
      var stateEntry = loadedEntryNames.stream()
          .filter(name -> name.toLowerCase().contains("state") ||
                          name.toLowerCase().contains("intake") ||
                          name.toLowerCase().contains("shooter"))
          .findFirst();
      if (stateEntry.isPresent()) {
        var cycleArgs = new JsonObject();
        cycleArgs.addProperty("state_entry", stateEntry.get());
        testTool("analyze_cycles", cycleArgs, result -> {
          int samples = result.has("sample_count") ? result.get("sample_count").getAsInt() : 0;
          System.out.println("  analyze_cycles: " + samples + " samples");
        });
      }
    }
  }

  // ==================== 7. Export + TBA ====================

  @Test
  @Order(7)
  @DisplayName("7. Exercise ExportTools and TbaTools")
  void exerciseExportAndTbaTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    assumeTrue(loadedEntryNames != null && !loadedEntryNames.isEmpty());
    loadLog(availableLogPaths.get(0));

    System.out.println("\nExercising ExportTools and TbaTools:");

    var numericEntry = loadedEntryNames.stream()
        .filter(name -> name.toLowerCase().contains("voltage") ||
                        name.toLowerCase().contains("position"))
        .findFirst();

    if (numericEntry.isPresent()) {
      var exportArgs = new JsonObject();
      exportArgs.addProperty("name", numericEntry.get());
      exportArgs.addProperty("output_path",
          System.getProperty("java.io.tmpdir") + "/stress_test_export.csv");
      testTool("export_csv", exportArgs, result -> {
        int rows = result.has("rows_exported") ? result.get("rows_exported").getAsInt() : 0;
        System.out.println("  export_csv: " + rows + " rows");
      });
    }

    testTool("generate_report", new JsonObject(), result -> {
      if (result.has("basic_info")) {
        var info = result.getAsJsonObject("basic_info");
        System.out.printf("  generate_report: %.1fs, %d entries%n",
            info.get("duration_sec").getAsDouble(), info.get("entry_count").getAsInt());
      }
    });

    testTool("get_tba_status", new JsonObject(), result -> {
      boolean available = result.has("available") && result.get("available").getAsBoolean();
      System.out.println("  get_tba_status: available=" + available);
    });
  }

  // ==================== 8. RevLog Tools ====================

  @Test
  @Order(8)
  @DisplayName("8. Exercise RevLog tools")
  void exerciseRevLogTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    loadLog(availableLogPaths.get(0));

    System.out.println("\nExercising RevLog tools:");

    testTool("sync_status", new JsonObject(), result -> {
      int revlogCount = result.has("revlog_count") ? result.get("revlog_count").getAsInt() : 0;
      System.out.println("  sync_status: " + revlogCount + " revlogs");
    });

    List<String> revlogSignalKeys = new ArrayList<>();
    testTool("list_revlog_signals", new JsonObject(), result -> {
      int signalCount = result.has("signal_count") ? result.get("signal_count").getAsInt() : 0;
      System.out.println("  list_revlog_signals: " + signalCount + " signals");
      if (result.has("signals")) {
        for (var signal : result.getAsJsonArray("signals")) {
          revlogSignalKeys.add(signal.getAsJsonObject().get("key").getAsString());
          if (revlogSignalKeys.size() >= 3) break;
        }
      }
    });

    for (String key : revlogSignalKeys) {
      var dataArgs = new JsonObject();
      dataArgs.addProperty("signal_key", key);
      dataArgs.addProperty("limit", 10);
      testTool("get_revlog_data", dataArgs, result -> {
        int samples = result.has("sample_count") ? result.get("sample_count").getAsInt() : 0;
        System.out.println("  get_revlog_data (" + key + "): " + samples + " samples");
      });
    }
  }

  // ==================== 9. Disk Cache ====================

  @Test
  @Order(9)
  @DisplayName("9. Disk cache stress test")
  void diskCacheStressTest() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());

    DiskCache diskCache = LogManager.getInstance().getDiskCache();
    assumeTrue(diskCache.isEnabled(), "Disk cache is disabled");

    System.out.println("\nDisk cache stress test:");

    String logPath = availableLogPaths.get(0);

    // First load: parses from disk, writes to cache
    LogManager.getInstance().unloadAllLogs();
    long firstLoadStart = System.currentTimeMillis();
    loadLog(logPath);
    long firstLoadMs = System.currentTimeMillis() - firstLoadStart;
    System.out.printf("  First load (parse + cache write): %dms%n", firstLoadMs);

    // Wait for async cache write to complete
    Thread.sleep(2000);

    // Second load: should hit disk cache
    LogManager.getInstance().unloadAllLogs();
    long secondLoadStart = System.currentTimeMillis();
    loadLog(logPath);
    long secondLoadMs = System.currentTimeMillis() - secondLoadStart;
    System.out.printf("  Second load (disk cache hit): %dms%n", secondLoadMs);

    if (secondLoadMs < firstLoadMs && firstLoadMs > 100) {
      double speedup = (double) firstLoadMs / Math.max(secondLoadMs, 1);
      System.out.printf("  Speedup: %.1fx%n", speedup);
    }

    // Verify data integrity: compare entry counts
    var log1entries = LogManager.getInstance().getActiveLog().entryCount();

    // Third load from cache
    LogManager.getInstance().unloadAllLogs();
    loadLog(logPath);
    var log2entries = LogManager.getInstance().getActiveLog().entryCount();

    assertEquals(log1entries, log2entries,
        "Cached log should have same entry count as original parse");
    System.out.printf("  Integrity check: %d entries (consistent)%n", log1entries);

    // Test cache with multiple logs
    if (availableLogPaths.size() >= 2) {
      LogManager.getInstance().unloadAllLogs();
      loadLog(availableLogPaths.get(0));
      loadLog(availableLogPaths.get(1));
      System.out.println("  Multi-log cache: loaded 2 logs successfully");
    }
  }

  // ==================== 10. In-Memory Cache Eviction ====================

  @Test
  @Order(10)
  @DisplayName("10. Cache eviction stress test")
  void cacheStressTest() throws Exception {
    assumeTrue(availableLogPaths != null && availableLogPaths.size() >= 2);

    System.out.println("\nIn-memory cache eviction test:");
    LogManager.getInstance().unloadAllLogs();
    LogManager.getInstance().setMaxLoadedLogs(3);

    int successfullyLoaded = 0;
    for (int i = 0; i < availableLogPaths.size() && successfullyLoaded < 5; i++) {
      try {
        loadLog(availableLogPaths.get(i));
        successfullyLoaded++;
        int loaded = LogManager.getInstance().getLoadedLogCount();
        System.out.println("    After load " + successfullyLoaded + ": " + loaded + " in cache");
      } catch (AssertionError | Exception e) {
        System.out.println("    Skipping (invalid/too large)");
      }
    }

    assumeTrue(successfullyLoaded > 3, "Need at least 4 loadable logs");

    int finalCount = LogManager.getInstance().getLoadedLogCount();
    assertTrue(finalCount <= 3, "Cache should respect max size limit");
    System.out.println("  Eviction working: max 3, actual " + finalCount);

    LogManager.getInstance().resetConfiguration();
  }

  // ==================== 11. Concurrent Operations ====================

  @Test
  @Order(11)
  @DisplayName("11. Concurrent operations test")
  void concurrentOperationsTest() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    assumeTrue(loadedEntryNames != null && !loadedEntryNames.isEmpty());
    loadLog(availableLogPaths.get(0));

    System.out.println("\nConcurrent operations test:");

    int threadCount = 4;
    int operationsPerThread = 10;
    var errors = new ArrayList<Throwable>();

    var threads = new ArrayList<Thread>();
    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      var thread = new Thread(() -> {
        try {
          for (int i = 0; i < operationsPerThread; i++) {
            var tool = tools.get((threadId + i) % tools.size());
            var args = new JsonObject();
            if (tool.name().contains("entry") && !loadedEntryNames.isEmpty()) {
              args.addProperty("name", loadedEntryNames.get(i % loadedEntryNames.size()));
            }
            try { tool.execute(args); } catch (Exception e) { /* expected for some */ }
          }
        } catch (Throwable e) {
          synchronized (errors) { errors.add(e); }
        }
      });
      threads.add(thread);
    }

    long start = System.currentTimeMillis();
    threads.forEach(Thread::start);
    for (var thread : threads) thread.join();
    long duration = System.currentTimeMillis() - start;

    int totalOps = threadCount * operationsPerThread;
    System.out.printf("  %d ops across %d threads in %dms (%.1f ops/sec)%n",
        totalOps, threadCount, duration, totalOps * 1000.0 / duration);

    assertTrue(errors.isEmpty(), "Concurrent operations should not throw: " + errors);
  }

  // ==================== 12. Summary ====================

  @Test
  @Order(99)
  @DisplayName("12. Summary")
  void printSummary() {
    System.out.println("\n========================================");
    System.out.println("Stress Test Summary");
    System.out.println("========================================");
    System.out.println("Total operations: " + totalOperations.get());
    System.out.println("Successful: " + successfulOperations.get());
    System.out.println("Failed: " + failedOperations.get());
    System.out.printf("Total time: %dms%n", totalTimeMs);
    if (totalOperations.get() > 0) {
      System.out.printf("Average: %.2fms/op%n", (double) totalTimeMs / totalOperations.get());
    }
    System.out.println("========================================\n");
  }

  // ==================== Helpers ====================

  private Tool findTool(String name) {
    return tools.stream()
        .filter(t -> t.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Tool not found: " + name));
  }

  private JsonObject executeTool(Tool tool, JsonObject args) throws Exception {
    totalOperations.incrementAndGet();
    long start = System.currentTimeMillis();
    try {
      var result = tool.execute(args);
      long duration = System.currentTimeMillis() - start;
      totalTimeMs += duration;
      if (result.isJsonObject()) {
        var obj = result.getAsJsonObject();
        if (obj.has("success") && obj.get("success").getAsBoolean()) {
          successfulOperations.incrementAndGet();
        } else {
          failedOperations.incrementAndGet();
        }
        return obj;
      }
      successfulOperations.incrementAndGet();
      return new JsonObject();
    } catch (Exception e) {
      failedOperations.incrementAndGet();
      throw e;
    }
  }

  private void loadLog(String path) throws Exception {
    var tool = findTool("load_log");
    var args = new JsonObject();
    args.addProperty("path", path);
    var result = executeTool(tool, args);
    assertTrue(result.has("success") && result.get("success").getAsBoolean(),
        "Failed to load log: " + path);
  }

  private void testTool(String toolName, JsonObject args, ToolResultHandler handler) {
    try {
      var tool = findTool(toolName);
      var result = executeTool(tool, args);
      if (result.has("success") && result.get("success").getAsBoolean()) {
        handler.handle(result);
      }
    } catch (Exception e) {
      System.out.println("  " + toolName + ": ERROR - " + e.getMessage());
    }
  }

  private List<String> findNumericEntries(int limit) {
    if (loadedEntryNames == null) return List.of();
    return loadedEntryNames.stream()
        .filter(name -> name.toLowerCase().contains("velocity") ||
                        name.toLowerCase().contains("position") ||
                        name.toLowerCase().contains("voltage") ||
                        name.toLowerCase().contains("current") ||
                        name.toLowerCase().contains("angle") ||
                        name.toLowerCase().contains("speed"))
        .limit(limit)
        .toList();
  }

  @FunctionalInterface
  interface ToolResultHandler {
    void handle(JsonObject result);
  }

  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
  }
}
