package org.triplehelix.wpilogmcp.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.google.gson.JsonObject;
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
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;
import org.triplehelix.wpilogmcp.tools.CoreTools;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.FrcDomainTools;
import org.triplehelix.wpilogmcp.tools.QueryTools;
import org.triplehelix.wpilogmcp.tools.RobotAnalysisTools;
import org.triplehelix.wpilogmcp.tools.StatisticsTools;
import org.triplehelix.wpilogmcp.tools.TbaTools;

/**
 * Integration stress test that exercises all MCP server functionality with real log files.
 *
 * <p>This test is disabled by default. To run it, provide the log directory via system property:
 * <pre>
 * ./gradlew test -Dstress.logdir=/path/to/logs --tests "*StressTest*"
 * </pre>
 *
 * <p>Or via environment variable:
 * <pre>
 * STRESS_LOGDIR=/path/to/logs ./gradlew test --tests "*StressTest*"
 * </pre>
 *
 * <p>The test will:
 * <ul>
 *   <li>List all available logs in the directory</li>
 *   <li>Load each log file and exercise all tools</li>
 *   <li>Report timing and success/failure statistics</li>
 *   <li>Test concurrent operations</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MCP Server Stress Test")
class StressTest {

  private static Path logDirectory;
  private static List<Tool> tools;
  private static List<String> availableLogPaths;
  private static List<String> loadedEntryNames;

  // Statistics
  private static final AtomicInteger totalOperations = new AtomicInteger(0);
  private static final AtomicInteger successfulOperations = new AtomicInteger(0);
  private static final AtomicInteger failedOperations = new AtomicInteger(0);
  private static long totalTimeMs = 0;

  @BeforeAll
  static void setup() {
    // Check for log directory from system property or environment variable
    String logDirPath = System.getProperty("stress.logdir");
    if (logDirPath == null || logDirPath.isEmpty()) {
      logDirPath = System.getenv("STRESS_LOGDIR");
    }

    assumeTrue(logDirPath != null && !logDirPath.isEmpty(),
        "Stress test skipped: No log directory provided. " +
        "Set -Dstress.logdir=/path/to/logs or STRESS_LOGDIR environment variable.");

    logDirectory = Path.of(logDirPath);
    assumeTrue(Files.isDirectory(logDirectory),
        "Stress test skipped: Log directory does not exist: " + logDirPath);

    // Configure LogDirectory
    LogDirectory.getInstance().setLogDirectory(logDirPath);
    LogManager.getInstance().addAllowedDirectory(logDirPath);

    // Register all tools
    tools = new ArrayList<>();
    var capturingServer = new McpServer() {
      @Override
      public void registerTool(Tool tool) {
        tools.add(tool);
      }
    };

    CoreTools.registerAll(capturingServer);
    QueryTools.registerAll(capturingServer);
    StatisticsTools.registerAll(capturingServer);
    FrcDomainTools.registerAll(capturingServer);
    RobotAnalysisTools.registerAll(capturingServer);
    ExportTools.registerAll(capturingServer);
    TbaTools.registerAll(capturingServer);

    System.out.println("\n========================================");
    System.out.println("MCP Server Stress Test");
    System.out.println("========================================");
    System.out.println("Log directory: " + logDirectory);
    System.out.println("Registered " + tools.size() + " tools");
    System.out.println();
  }

  @BeforeEach
  void resetState() {
    LogManager.getInstance().unloadAllLogs();
  }

  @Test
  @Order(1)
  @DisplayName("1. List available logs")
  void listAvailableLogs() throws Exception {
    var tool = findTool("list_available_logs");
    var args = new JsonObject();

    long start = System.currentTimeMillis();
    var result = executeTool(tool, args);
    long duration = System.currentTimeMillis() - start;

    assertTrue(result.has("success") && result.get("success").getAsBoolean(),
        "list_available_logs failed: " + result);

    int logCount = result.get("log_count").getAsInt();
    System.out.println("Found " + logCount + " log files in " + duration + "ms");

    // Store paths for later tests
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

  @Test
  @Order(2)
  @DisplayName("2. Load all logs sequentially")
  void loadAllLogsSequentially() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty(),
        "No logs available to test");

    var tool = findTool("load_log");
    int loaded = 0;
    int failed = 0;
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
          System.out.printf("  [FAIL] %s - %s%n",
              Path.of(path).getFileName(),
              result.has("error") ? result.get("error").getAsString() : "unknown error");
        }
      } catch (Exception e) {
        failed++;
        System.out.printf("  [ERROR] %s - %s%n", Path.of(path).getFileName(), e.getMessage());
      }

      // Unload to test each independently
      LogManager.getInstance().unloadAllLogs();
    }

    System.out.printf("%nLoaded %d/%d logs successfully (%d failed) in %dms total%n",
        loaded, availableLogPaths.size(), failed, totalLoadTime);
    assertTrue(loaded > 0, "Failed to load any logs");
  }

  @Test
  @Order(3)
  @DisplayName("3. Exercise CoreTools on first log")
  void exerciseCoreTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());

    // Load the first log
    String logPath = availableLogPaths.get(0);
    loadLog(logPath);

    System.out.println("\nExercising CoreTools on: " + Path.of(logPath).getFileName());

    // list_entries
    testTool("list_entries", new JsonObject(), result -> {
      assertTrue(result.has("entries"));
      var entries = result.getAsJsonArray("entries");
      loadedEntryNames = new ArrayList<>();
      for (var entry : entries) {
        loadedEntryNames.add(entry.getAsJsonObject().get("name").getAsString());
      }
      System.out.println("  list_entries: " + loadedEntryNames.size() + " entries");
    });

    // get_entry_info for first few entries
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
    }

    // read_entry with pagination
    if (loadedEntryNames != null && !loadedEntryNames.isEmpty()) {
      var args = new JsonObject();
      args.addProperty("name", loadedEntryNames.get(0));
      args.addProperty("limit", 10);
      testTool("read_entry", args, result -> {
        int returned = result.has("samples") ? result.getAsJsonArray("samples").size() : 0;
        System.out.println("  read_entry (limit 10): returned " + returned + " samples");
      });
    }

    // list_loaded_logs
    testTool("list_loaded_logs", new JsonObject(), result -> {
      int loaded = result.has("loaded_count") ? result.get("loaded_count").getAsInt() : 0;
      int capacity = result.has("max_cached_logs") ? result.get("max_cached_logs").getAsInt() : 0;
      System.out.println("  list_loaded_logs: " + loaded + " loaded, " + capacity + " max capacity");
    });

    // list_struct_types
    testTool("list_struct_types", new JsonObject(), result -> {
      int geomCount = result.has("geometry") ? result.getAsJsonArray("geometry").size() : 0;
      int kinemCount = result.has("kinematics") ? result.getAsJsonArray("kinematics").size() : 0;
      int visionCount = result.has("vision") ? result.getAsJsonArray("vision").size() : 0;
      int autoCount = result.has("autonomous") ? result.getAsJsonArray("autonomous").size() : 0;
      System.out.println("  list_struct_types: " + (geomCount + kinemCount + visionCount + autoCount) + " struct types total");
    });

    // health_check
    testTool("health_check", new JsonObject(), result -> {
      String status = result.has("status") ? result.get("status").getAsString() : "unknown";
      int loadedLogs = result.has("loaded_logs") ? result.get("loaded_logs").getAsInt() : 0;
      boolean tbaAvailable = result.has("tba_available") && result.get("tba_available").getAsBoolean();
      System.out.printf("  health_check: status=%s, loaded=%d, tba=%b%n", status, loadedLogs, tbaAvailable);
    });
  }

  @Test
  @Order(4)
  @DisplayName("4. Exercise QueryTools")
  void exerciseQueryTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    assumeTrue(loadedEntryNames != null && !loadedEntryNames.isEmpty());

    // Ensure log is loaded
    loadLog(availableLogPaths.get(0));

    System.out.println("\nExercising QueryTools:");

    // search_entries
    testTool("search_entries", new JsonObject(), result -> {
      int count = result.getAsJsonArray("matches").size();
      System.out.println("  search_entries (all): " + count + " entries");
    });

    // search_entries with pattern
    var patternArgs = new JsonObject();
    patternArgs.addProperty("pattern", ".");
    testTool("search_entries", patternArgs, result -> {
      int count = result.getAsJsonArray("matches").size();
      System.out.println("  search_entries (pattern .): " + count + " entries");
    });

    // get_types
    testTool("get_types", new JsonObject(), result -> {
      int count = result.has("types") ? result.getAsJsonArray("types").size() : 0;
      System.out.println("  get_types: " + count + " types");
    });

    // search_strings (if there are string entries)
    var stringEntries = loadedEntryNames.stream()
        .filter(name -> name.toLowerCase().contains("string") ||
                        name.toLowerCase().contains("name") ||
                        name.toLowerCase().contains("event"))
        .toList();

    if (!stringEntries.isEmpty()) {
      var searchArgs = new JsonObject();
      searchArgs.addProperty("pattern", ".*");
      searchArgs.addProperty("limit", 5);
      testTool("search_strings", searchArgs, result -> {
        int matches = result.has("matches") ? result.getAsJsonArray("matches").size() : 0;
        System.out.println("  search_strings: " + matches + " matches");
      });
    }

    // find_condition on a numeric entry (e.g., find when voltage crosses threshold)
    var numericForCondition = loadedEntryNames.stream()
        .filter(name -> name.toLowerCase().contains("voltage") ||
                        name.toLowerCase().contains("current") ||
                        name.toLowerCase().contains("velocity"))
        .limit(1)
        .toList();

    if (!numericForCondition.isEmpty()) {
      var condArgs = new JsonObject();
      condArgs.addProperty("name", numericForCondition.get(0));
      condArgs.addProperty("operator", "gt");
      condArgs.addProperty("threshold", 0.0);
      condArgs.addProperty("limit", 10);
      testTool("find_condition", condArgs, result -> {
        int transitions = result.has("transitions") ? result.getAsJsonArray("transitions").size() : 0;
        System.out.println("  find_condition (" + numericForCondition.get(0) + " > 0): " + transitions + " transitions");
      });
    }
  }

  @Test
  @Order(5)
  @DisplayName("5. Exercise StatisticsTools")
  void exerciseStatisticsTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    assumeTrue(loadedEntryNames != null && !loadedEntryNames.isEmpty());

    loadLog(availableLogPaths.get(0));

    System.out.println("\nExercising StatisticsTools:");

    // Find numeric entries for testing
    var numericEntries = loadedEntryNames.stream()
        .filter(name -> name.toLowerCase().contains("velocity") ||
                        name.toLowerCase().contains("position") ||
                        name.toLowerCase().contains("voltage") ||
                        name.toLowerCase().contains("current") ||
                        name.toLowerCase().contains("angle") ||
                        name.toLowerCase().contains("speed"))
        .limit(5)
        .toList();

    // get_statistics
    for (String entry : numericEntries) {
      var args = new JsonObject();
      args.addProperty("name", entry);
      testTool("get_statistics", args, result -> {
        if (result.has("mean")) {
          System.out.printf("  get_statistics (%s): mean=%.3f, std=%.3f%n",
              entry, result.get("mean").getAsDouble(), result.get("std_dev").getAsDouble());
        } else {
          System.out.println("  get_statistics (" + entry + "): not numeric");
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
        System.out.println("  detect_anomalies: " + anomalies + " anomalies found");
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

    // time_correlate (if we have at least 2 numeric entries)
    if (numericEntries.size() >= 2) {
      var corrArgs = new JsonObject();
      corrArgs.addProperty("name1", numericEntries.get(0));
      corrArgs.addProperty("name2", numericEntries.get(1));
      testTool("time_correlate", corrArgs, result -> {
        if (result.has("correlation")) {
          double corr = result.get("correlation").getAsDouble();
          System.out.printf("  time_correlate: correlation=%.4f%n", corr);
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
          double rmse = result.get("rmse").getAsDouble();
          System.out.printf("  compare_entries: rmse=%.4f%n", rmse);
        }
      });
    }
  }

  @Test
  @Order(6)
  @DisplayName("6. Exercise FrcDomainTools and RobotAnalysisTools")
  void exerciseFrcDomainTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());

    loadLog(availableLogPaths.get(0));

    System.out.println("\nExercising FrcDomainTools and RobotAnalysisTools:");

    // get_match_phases
    testTool("get_match_phases", new JsonObject(), result -> {
      if (result.has("phases")) {
        var phases = result.getAsJsonObject("phases");
        System.out.println("  get_match_phases: " + phases.size() + " phases detected");
        for (var entry : phases.entrySet()) {
          var phase = entry.getValue().getAsJsonObject();
          System.out.printf("    - %s: %.2fs to %.2fs (%.1fs)%n",
              entry.getKey(),
              phase.get("start").getAsDouble(),
              phase.get("end").getAsDouble(),
              phase.get("duration").getAsDouble());
        }
      } else {
        System.out.println("  get_match_phases: no phases detected");
      }
    });

    // analyze_auto
    testTool("analyze_auto", new JsonObject(), result -> {
      if (result.has("auto_duration_sec")) {
        System.out.printf("  analyze_auto: duration=%.2fs%n",
            result.get("auto_duration_sec").getAsDouble());
      } else {
        System.out.println("  analyze_auto: no auto period detected");
      }
    });

    // get_ds_timeline (robot state changes)
    testTool("get_ds_timeline", new JsonObject(), result -> {
      if (result.has("events")) {
        int events = result.getAsJsonArray("events").size();
        System.out.println("  get_ds_timeline: " + events + " events");
      }
    });

    // profile_mechanism (try common mechanism names)
    String[] mechanismNames = {
        "Arm", "Elevator", "Shooter", "Intake", "Drivetrain", "Swerve"
    };
    for (String name : mechanismNames) {
      var mechArgs = new JsonObject();
      mechArgs.addProperty("mechanism_name", name);
      testTool("profile_mechanism", mechArgs, result -> {
        if (result.has("setpoint") && result.has("measurement")) {
          double rmse = result.has("rmse") ? result.get("rmse").getAsDouble() : 0.0;
          System.out.printf("  profile_mechanism (%s): rmse=%.4f%n", name, rmse);
        }
      });
    }

    // analyze_loop_timing
    testTool("analyze_loop_timing", new JsonObject(), result -> {
      if (result.has("loop_time_stats")) {
        var stats = result.getAsJsonObject("loop_time_stats");
        double mean = stats.has("mean") ? stats.get("mean").getAsDouble() : 0.0;
        System.out.printf("  analyze_loop_timing: mean=%.2fms%n", mean);
        if (result.has("overruns")) {
          int overruns = result.getAsJsonArray("overruns").size();
          System.out.printf("    - detected %d loop overruns%n", overruns);
        }
      } else {
        System.out.println("  analyze_loop_timing: no loop timing data found");
      }
    });

    // analyze_can_bus
    testTool("analyze_can_bus", new JsonObject(), result -> {
      if (result.has("utilization_stats")) {
        var stats = result.getAsJsonObject("utilization_stats");
        double mean = stats.has("mean") ? stats.get("mean").getAsDouble() : 0.0;
        System.out.printf("  analyze_can_bus: utilization=%.1f%%%n", mean);
        if (result.has("tx_errors") || result.has("rx_errors")) {
          int txErrors = result.has("tx_errors") ? result.get("tx_errors").getAsInt() : 0;
          int rxErrors = result.has("rx_errors") ? result.get("rx_errors").getAsInt() : 0;
          System.out.printf("    - tx_errors=%d, rx_errors=%d%n", txErrors, rxErrors);
        }
      } else {
        System.out.println("  analyze_can_bus: no CAN bus data found");
      }
    });
  }

  @Test
  @Order(7)
  @DisplayName("7. Exercise ExportTools and TbaTools")
  void exerciseExportAndTbaTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    assumeTrue(loadedEntryNames != null && !loadedEntryNames.isEmpty());

    loadLog(availableLogPaths.get(0));

    System.out.println("\nExercising ExportTools and TbaTools:");

    // export_csv (test with a numeric entry)
    var numericEntry = loadedEntryNames.stream()
        .filter(name -> name.toLowerCase().contains("voltage") ||
                        name.toLowerCase().contains("position"))
        .findFirst();

    if (numericEntry.isPresent()) {
      var exportArgs = new JsonObject();
      exportArgs.addProperty("name", numericEntry.get());
      exportArgs.addProperty("output_path", System.getProperty("java.io.tmpdir") + "/stress_test_export.csv");
      testTool("export_csv", exportArgs, result -> {
        int rows = result.has("rows_exported") ? result.get("rows_exported").getAsInt() : 0;
        System.out.println("  export_csv: exported " + rows + " rows to " +
            result.get("output_path").getAsString());
      });
    }

    // generate_report
    testTool("generate_report", new JsonObject(), result -> {
      if (result.has("basic_info")) {
        var basicInfo = result.getAsJsonObject("basic_info");
        double duration = basicInfo.get("duration_sec").getAsDouble();
        int entries = basicInfo.get("entry_count").getAsInt();
        System.out.printf("  generate_report: %.1fs duration, %d entries", duration, entries);
        if (result.has("battery")) {
          var battery = result.getAsJsonObject("battery");
          System.out.printf(", min voltage: %.2fV", battery.get("min_voltage").getAsDouble());
        }
        System.out.println();
      }
    });

    // get_tba_status
    testTool("get_tba_status", new JsonObject(), result -> {
      boolean available = result.has("available") && result.get("available").getAsBoolean();
      String status = result.has("status") ? result.get("status").getAsString() : "unknown";
      System.out.println("  get_tba_status: " + status + " (available: " + available + ")");
    });
  }

  @Test
  @Order(8)
  @DisplayName("8. Exercise additional RobotAnalysisTools")
  void exerciseAdditionalRobotAnalysisTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());

    loadLog(availableLogPaths.get(0));

    System.out.println("\nExercising additional RobotAnalysisTools:");

    // analyze_swerve
    testTool("analyze_swerve", new JsonObject(), result -> {
      if (result.has("modules_found")) {
        int modules = result.get("modules_found").getAsInt();
        System.out.println("  analyze_swerve: " + modules + " modules found");
      } else {
        System.out.println("  analyze_swerve: no swerve modules detected");
      }
    });

    // power_analysis
    testTool("power_analysis", new JsonObject(), result -> {
      if (result.has("battery_stats")) {
        System.out.println("  power_analysis: battery statistics generated");
      } else {
        System.out.println("  power_analysis: no battery data found");
      }
    });

    // can_health
    testTool("can_health", new JsonObject(), result -> {
      if (result.has("devices")) {
        int devices = result.getAsJsonArray("devices").size();
        System.out.println("  can_health: " + devices + " CAN devices analyzed");
      } else {
        System.out.println("  can_health: no CAN data found");
      }
    });

    // get_code_metadata
    testTool("get_code_metadata", new JsonObject(), result -> {
      if (result.has("git_sha") || result.has("build_date")) {
        System.out.println("  get_code_metadata: code metadata found");
      } else {
        System.out.println("  get_code_metadata: no code metadata found");
      }
    });

    // analyze_cycles (with state entry)
    // Try to find a suitable state entry
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
          System.out.println("  analyze_cycles (" + stateEntry.get() + "): " + samples + " samples");
        });
      } else {
        System.out.println("  analyze_cycles: skipped (no suitable state entry found)");
      }
    }
  }

  @Test
  @Order(9)
  @DisplayName("9. Cache stress test")
  void cacheStressTest() throws Exception {
    assumeTrue(availableLogPaths != null && availableLogPaths.size() >= 2);

    System.out.println("\nCache stress test:");

    // Configure small cache
    LogManager.getInstance().unloadAllLogs();
    LogManager.getInstance().setMaxLoadedLogs(3);

    // Load more logs than cache can hold
    int logsToLoad = Math.min(5, availableLogPaths.size());
    System.out.println("  Loading " + logsToLoad + " logs with cache size 3...");

    for (int i = 0; i < logsToLoad; i++) {
      loadLog(availableLogPaths.get(i));
      int loaded = LogManager.getInstance().getLoadedLogCount();
      System.out.println("    After loading log " + (i + 1) + ": " + loaded + " in cache");
    }

    int finalCount = LogManager.getInstance().getLoadedLogCount();
    assertTrue(finalCount <= 3, "Cache should not exceed max size");
    System.out.println("  Cache eviction working correctly (max 3, actual " + finalCount + ")");

    // Reset
    LogManager.getInstance().resetConfiguration();
  }

  @Test
  @Order(10)
  @DisplayName("10. Concurrent operations test")
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
            // Randomly pick a tool and execute
            var tool = tools.get((threadId + i) % tools.size());
            var args = new JsonObject();

            // Add required args based on tool
            if (tool.name().contains("entry") && !loadedEntryNames.isEmpty()) {
              args.addProperty("name", loadedEntryNames.get(i % loadedEntryNames.size()));
            }

            try {
              tool.execute(args);
            } catch (Exception e) {
              // Expected for some tools with missing args
            }
          }
        } catch (Throwable e) {
          synchronized (errors) {
            errors.add(e);
          }
        }
      });
      threads.add(thread);
    }

    long start = System.currentTimeMillis();
    threads.forEach(Thread::start);
    for (var thread : threads) {
      thread.join();
    }
    long duration = System.currentTimeMillis() - start;

    int totalOps = threadCount * operationsPerThread;
    System.out.printf("  Executed %d operations across %d threads in %dms%n",
        totalOps, threadCount, duration);
    System.out.printf("  Throughput: %.1f ops/sec%n", totalOps * 1000.0 / duration);

    if (!errors.isEmpty()) {
      System.out.println("  Errors: " + errors.size());
      for (var error : errors) {
        error.printStackTrace();
      }
    }

    assertTrue(errors.isEmpty(), "Concurrent operations should not throw errors");
  }

  @Test
  @Order(99)
  @DisplayName("11. Summary")
  void printSummary() {
    System.out.println("\n========================================");
    System.out.println("Stress Test Summary");
    System.out.println("========================================");
    System.out.println("Total operations: " + totalOperations.get());
    System.out.println("Successful: " + successfulOperations.get());
    System.out.println("Failed: " + failedOperations.get());
    System.out.printf("Total time: %dms%n", totalTimeMs);
    if (totalOperations.get() > 0) {
      System.out.printf("Average time per operation: %.2fms%n",
          (double) totalTimeMs / totalOperations.get());
    }
    System.out.println("========================================\n");
  }

  // ==================== Helper Methods ====================

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
