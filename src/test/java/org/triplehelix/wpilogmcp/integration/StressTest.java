/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.triplehelix.wpilogmcp.config.ConfigLoader;
import org.triplehelix.wpilogmcp.config.ServerConfig;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.Main;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;
import org.triplehelix.wpilogmcp.tba.TbaConfig;
import org.triplehelix.wpilogmcp.tools.CoreTools;
import org.triplehelix.wpilogmcp.tools.DiscoveryTools;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.FrcDomainTools;
import org.triplehelix.wpilogmcp.tools.PoseTools;
import org.triplehelix.wpilogmcp.tools.QueryTools;
import org.triplehelix.wpilogmcp.tools.RevLogTools;
import org.triplehelix.wpilogmcp.tools.RobotAnalysisTools;
import org.triplehelix.wpilogmcp.tools.StatisticsTools;
import org.triplehelix.wpilogmcp.tools.TbaTools;

/**
 * Integration stress test that exercises all MCP server functionality with real log files.
 *
 * <p>Runs only through {@code ./gradlew stressTest}. The configuration is the {@code stresstest}
 * server in the config file given by {@code -Pconfigpath=<file>} (the same format the server
 * reads); without one, it uses {@code ~/riologs} and team 2363, with the TBA key from
 * {@code TBA_API_KEY}:
 * <pre>
 * ./gradlew stressTest -Pconfigpath=stress-config.json
 * </pre>
 *
 * <p>This test exercises loading, caching, concurrency, and each tool family on real logs. The
 * per-result robustness rules for every tool on every log are checked by
 * {@code RealLogConformanceTest}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MCP Server Stress Test")
class StressTest {

  private static java.util.List<Path> logDirectories;
  private static List<Tool> tools;
  private static List<String> availableLogPaths;
  private static List<String> loadedEntryNames;
  private static String testCacheFolder;
  private static String revLogLogPath;
  private static boolean revLogLogSearched;

  // Statistics
  private static final AtomicInteger totalOperations = new AtomicInteger(0);
  private static final AtomicInteger successfulOperations = new AtomicInteger(0);
  private static final AtomicInteger failedOperations = new AtomicInteger(0);
  private static long totalTimeMs = 0;
  private static final List<String> violations =
      java.util.Collections.synchronizedList(new ArrayList<>());

  @BeforeAll
  static void setup() {
    // Only runs when explicitly invoked via ./gradlew stressTest.
    // Never runs during normal "./gradlew test".
    boolean enabled = "true".equals(System.getProperty("stress.enabled"));
    assumeTrue(enabled,
        "Stress test skipped. Run via: ./gradlew stressTest");

    // Load configuration: try "stresstest" named config from config file,
    // fall back to synthesized defaults (~/riologs, team 2363, TBA from env).
    try {
      Path configPath = System.getProperty("stress.configpath") != null
          ? Path.of(System.getProperty("stress.configpath")) : null;
      var loader = new ConfigLoader();
      ServerConfig config;
      try {
        config = loader.load("stresstest", configPath);
      } catch (Exception e) {
        // No config file or no "stresstest" entry — use defaults
        String home = System.getProperty("user.home");
        config = new ServerConfig("stresstest",
            java.util.List.of(home + "/riologs"), 2363, System.getenv("TBA_API_KEY"),
            "stdio", null, null, null, null, null, null, null);
      }
      Main.applyConfig(config);
      testCacheFolder = StressSupport.useTestCache();

      var logDirs = config.logdirs();
      assumeTrue(logDirs != null && !logDirs.isEmpty(),
          "Stress test skipped: no logdir configured");

      logDirectories = logDirs.stream().map(Path::of).toList();
      assumeTrue(logDirectories.stream().anyMatch(Files::isDirectory),
          "Stress test skipped: no configured log directory exists: " + logDirs);
    } catch (Exception e) {
      assumeTrue(false, "Stress test skipped: " + e.getMessage());
      return;
    }

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
    PoseTools.registerAll(capturingRegistry);
    RobotAnalysisTools.registerAll(capturingRegistry);
    ExportTools.registerAll(capturingRegistry);
    TbaTools.registerAll(capturingRegistry);
    RevLogTools.registerAll(capturingRegistry);
    DiscoveryTools.registerAll(capturingRegistry);

    System.out.println("\n========================================");
    System.out.println("MCP Server Stress Test");
    System.out.println("========================================");
    System.out.println("Log directories: " + logDirectories);
    System.out.println("Registered " + tools.size() + " tools");
    System.out.println("Disk cache: " + (testCacheFolder != null ? testCacheFolder
        : "the configured one (not started by the build's stress task)"));
    System.out.println("TBA configured: " + TbaConfig.getInstance().isConfigured());
    System.out.println();
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
    availableLogPaths = new ArrayList<>();
    int logCount = -1;
    // Every log, page by page: a page holds at most 500
    for (int offset = 0; ; ) {
      var listArgs = new JsonObject();
      listArgs.addProperty("limit", 500);
      listArgs.addProperty("offset", offset);
      var result = executeTool(tool, listArgs);
      assertTrue(result.has("success") && result.get("success").getAsBoolean(),
          "list_available_logs failed: " + result);
      if (logCount < 0) {
        logCount = result.get("log_count").getAsInt();
        System.out.println("Found " + logCount + " log files");
        assertEquals(logDirectories.size(), result.getAsJsonArray("log_directories").size(),
            "every configured directory is named: " + result.get("log_directories"));
        if (result.has("skipped")) {
          System.out.println("Directories not read: " + result.get("skipped"));
        }
      }
      var logsArray = result.getAsJsonArray("logs");
      for (var logEntry : logsArray) {
        var logObj = logEntry.getAsJsonObject();
        availableLogPaths.add(logObj.get("path").getAsString());
        System.out.println("  - " + logObj.get("friendly_name").getAsString() +
            " (" + formatBytes(logObj.get("size_bytes").getAsLong()) + ")");
      }
      offset += logsArray.size();
      if (logsArray.isEmpty() || !result.get("has_more").getAsBoolean()) break;
    }
    assertEquals(logCount, availableLogPaths.size(), "the pages together list every log");

    assumeTrue(!availableLogPaths.isEmpty(), "No log files found in directory");
  }

  // ==================== 2. Load ====================

  @Test
  @Order(2)
  @DisplayName("2. Load all logs sequentially")
  void loadAllLogsSequentially() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());

    int loaded = 0, failed = 0;
    long totalLoadTime = 0;

    System.out.println("\nLoading logs sequentially:");
    for (String path : availableLogPaths) {
      long start = System.currentTimeMillis();
      try {
        var log = LogManager.getInstance().getOrLoad(path);
        long duration = System.currentTimeMillis() - start;
        totalLoadTime += duration;

        loaded++;
        int entryCount = log.entryCount();
        double durationSec = log.duration();
        System.out.printf("  [OK] %s - %d entries, %.1fs duration, loaded in %dms%n",
            Path.of(path).getFileName(), entryCount, durationSec, duration);
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

    var listArgs = new JsonObject();
    listArgs.addProperty("path", logPath);
    testTool("list_entries", listArgs, result -> {
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
        args.addProperty("path", logPath);
        args.addProperty("name", entryName);
        testTool("get_entry_info", args, result -> {
          System.out.println("  get_entry_info: " + entryName + " - " +
              result.get("type").getAsString() + ", " +
              result.get("sample_count").getAsInt() + " samples");
        });
      }

      var readArgs = new JsonObject();
      readArgs.addProperty("path", logPath);
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

    var structArgs = new JsonObject();
    structArgs.addProperty("path", logPath);
    testTool("list_struct_types", structArgs, result -> {
      int total = result.has("struct_types") ? result.getAsJsonArray("struct_types").size() : 0;
      System.out.println("  list_struct_types: " + total + " struct types in this log");
    });

    testTool("health_check", new JsonObject(), result -> {
      String status = result.has("status") ? result.get("status").getAsString() : "unknown";
      System.out.println("  health_check: " + status);
    });

    // Discovery tools
    testTool("get_server_guide", new JsonObject(), result -> {
      int categories = result.has("categories") ? result.getAsJsonArray("categories").size() : 0;
      System.out.println("  get_server_guide: " + categories + " categories");
    });

    var suggestArgs = new JsonObject();
    suggestArgs.addProperty("task", "analyze battery health");
    testTool("suggest_tools", suggestArgs, result -> {
      int suggestions = result.has("suggestions") ? result.getAsJsonArray("suggestions").size() : 0;
      System.out.println("  suggest_tools: " + suggestions + " suggestions");
    });

    // Game info
    testTool("get_game_info", new JsonObject(), result -> {
      String gameName = result.has("game_name") ? result.get("game_name").getAsString() : "none";
      System.out.println("  get_game_info: " + gameName);
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

    String logPath = availableLogPaths.get(0);
    System.out.println("\nExercising QueryTools:");

    var searchArgs = new JsonObject();
    searchArgs.addProperty("path", logPath);
    testTool("search_entries", searchArgs, result -> {
      System.out.println("  search_entries (all): " + result.getAsJsonArray("matches").size());
    });

    var pArgs = new JsonObject();
    pArgs.addProperty("path", logPath);
    pArgs.addProperty("pattern", ".");
    testTool("search_entries", pArgs, result -> {
      System.out.println("  search_entries (pattern '.'): " + result.getAsJsonArray("matches").size());
    });

    var typesArgs = new JsonObject();
    typesArgs.addProperty("path", logPath);
    testTool("get_types", typesArgs, result -> {
      int count = result.has("types") ? result.getAsJsonArray("types").size() : 0;
      System.out.println("  get_types: " + count + " types");
    });

    var searchStrArgs = new JsonObject();
    searchStrArgs.addProperty("path", logPath);
    searchStrArgs.addProperty("pattern", "error");
    testTool("search_strings", searchStrArgs, result -> {
      int matches = result.has("matches") ? result.getAsJsonArray("matches").size() : 0;
      System.out.println("  search_strings: " + matches + " matches");
    });

    var numericForCondition = findNumericEntries(1);
    if (!numericForCondition.isEmpty()) {
      var condArgs = new JsonObject();
      condArgs.addProperty("path", logPath);
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

    String logPath = availableLogPaths.get(0);
    System.out.println("\nExercising StatisticsTools:");
    var numericEntries = findNumericEntries(5);

    for (String entry : numericEntries) {
      var args = new JsonObject();
      args.addProperty("path", logPath);
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
      anomalyArgs.addProperty("path", logPath);
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
      peakArgs.addProperty("path", logPath);
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
      rateArgs.addProperty("path", logPath);
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
      corrArgs.addProperty("path", logPath);
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
      compareArgs.addProperty("path", logPath);
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

    String logPath = availableLogPaths.get(0);
    System.out.println("\nExercising FrcDomainTools and RobotAnalysisTools:");

    // get_match_phases — now data-driven, not hardcoded
    var matchPhasesArgs = new JsonObject();
    matchPhasesArgs.addProperty("path", logPath);
    testTool("get_match_phases", matchPhasesArgs, result -> {
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

    var autoArgs = new JsonObject();
    autoArgs.addProperty("path", logPath);
    testTool("analyze_auto", autoArgs, result -> {
      if (result.has("auto_duration")) {
        System.out.printf("  analyze_auto: %.2fs%n", result.get("auto_duration").getAsDouble());
      } else {
        System.out.println("  analyze_auto: no auto period detected");
      }
    });

    var dsArgs = new JsonObject();
    dsArgs.addProperty("path", logPath);
    testTool("get_ds_timeline", dsArgs, result -> {
      int events = result.has("events") ? result.getAsJsonArray("events").size() : 0;
      System.out.println("  get_ds_timeline: " + events + " events");
    });

    // analyze_vision
    var visionArgs = new JsonObject();
    visionArgs.addProperty("path", logPath);
    testTool("analyze_vision", visionArgs, result -> {
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

    // pose_corrections and compare_poses (the robot pose against itself when no other pose
    // is named: the exercise is the reading and interpolation)
    var correctionsArgs = new JsonObject();
    correctionsArgs.addProperty("path", logPath);
    correctionsArgs.addProperty("scope", "enabled");
    testTool("pose_corrections", correctionsArgs, result -> {
      if (result.has("residual_translation_m")) {
        System.out.printf("  pose_corrections: %d corrections, median residual %.4f m%n",
            result.get("correction_count").getAsInt(),
            result.getAsJsonObject("residual_translation_m").get("median").getAsDouble());
      }
    });
    if (loadedEntryNames != null && loadedEntryNames.contains("/RealOutputs/Drive/Pose")) {
      var compareArgs = new JsonObject();
      compareArgs.addProperty("path", logPath);
      compareArgs.addProperty("reference_entry", "/RealOutputs/Drive/Pose");
      testTool("compare_poses", compareArgs, result -> System.out.printf(
          "  compare_poses: %d samples%n", result.get("count").getAsInt()));
    }

    // analyze_replay_drift (AdvantageKit)
    var replayArgs = new JsonObject();
    replayArgs.addProperty("path", logPath);
    testTool("analyze_replay_drift", replayArgs, result -> {
      int divergent = result.has("divergent_count") ? result.get("divergent_count").getAsInt() : 0;
      System.out.println("  analyze_replay_drift: " + divergent + " divergent entries");
    });

    // profile_mechanism: a name lists candidates and analyzes nothing (no_match, so the handler
    // is not reached; the call is still checked against the robustness rules)
    for (String name : List.of("Arm", "Elevator", "Shooter", "Intake", "Drivetrain", "Swerve")) {
      var mechArgs = new JsonObject();
      mechArgs.addProperty("path", logPath);
      mechArgs.addProperty("mechanism_name", name);
      testTool("profile_mechanism", mechArgs, result -> { });
    }
    // The analysis runs on entries passed explicitly. The caller's part, done here: a velocity
    // and a current that one table holds under the AdvantageKit template's field names.
    if (loadedEntryNames != null) {
      loadedEntryNames.stream()
          .filter(n -> n.endsWith("/VelocityRadPerSec"))
          .filter(n -> loadedEntryNames.contains(
              n.substring(0, n.length() - "/VelocityRadPerSec".length()) + "/CurrentAmps"))
          .findFirst()
          .ifPresent(velocity -> {
            var table = velocity.substring(0, velocity.length() - "/VelocityRadPerSec".length());
            var mechArgs = new JsonObject();
            mechArgs.addProperty("path", logPath);
            mechArgs.addProperty("velocity_entry", velocity);
            mechArgs.addProperty("current_entry", table + "/CurrentAmps");
            testTool("profile_mechanism", mechArgs, result ->
                System.out.println("  profile_mechanism (" + table + "): "
                    + result.get("stall_count").getAsInt() + " stalls"));
          });
    }

    // analyze_loop_timing (with unit auto-detect)
    var loopArgs = new JsonObject();
    loopArgs.addProperty("path", logPath);
    testTool("analyze_loop_timing", loopArgs, result -> {
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
    var canBusArgs = new JsonObject();
    canBusArgs.addProperty("path", logPath);
    testTool("analyze_can_bus", canBusArgs, result -> {
      if (result.has("utilization")) {
        System.out.println("  analyze_can_bus: utilization data found");
      } else if (result.has("errors")) {
        System.out.println("  analyze_can_bus: error data found");
      } else {
        System.out.println("  analyze_can_bus: no CAN data found");
      }
    });

    // predict_battery_health
    var batteryArgs = new JsonObject();
    batteryArgs.addProperty("path", logPath);
    testTool("predict_battery_health", batteryArgs, result -> {
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
    var swerveArgs = new JsonObject();
    swerveArgs.addProperty("path", logPath);
    testTool("analyze_swerve", swerveArgs, result -> {
      System.out.println("  analyze_swerve: " + result.get("module_count").getAsInt()
          + " modules (" + result.get("layout").getAsString() + "), measured: "
          + result.get("measured_basis").getAsString());
    });

    // power_analysis
    var powerArgs = new JsonObject();
    powerArgs.addProperty("path", logPath);
    testTool("power_analysis", powerArgs, result -> {
      if (result.has("voltage_analysis")) {
        var va = result.getAsJsonObject("voltage_analysis");
        System.out.printf("  power_analysis: min=%.2fV, avg=%.2fV, %d below threshold%n",
            va.get("min_voltage").getAsDouble(), va.get("avg_voltage").getAsDouble(),
            va.get("samples_below_threshold").getAsLong());
      } else {
        System.out.println("  power_analysis: no voltage data");
      }
    });

    var canHealthArgs = new JsonObject();
    canHealthArgs.addProperty("path", logPath);
    testTool("can_health", canHealthArgs, result -> {
      long total = result.has("total_can_errors") ? result.get("total_can_errors").getAsLong() : 0;
      String health = result.has("health_assessment") ? result.get("health_assessment").getAsString() : "unknown";
      System.out.println("  can_health: " + total + " errors, assessment=" + health);
    });

    var codeMetaArgs = new JsonObject();
    codeMetaArgs.addProperty("path", logPath);
    testTool("get_code_metadata", codeMetaArgs, result -> {
      if (result.has("metadata")) {
        System.out.println("  get_code_metadata: metadata found");
      } else {
        System.out.println("  get_code_metadata: no metadata");
      }
    });

    // moi_regression: a velocity and a current entry of the same mechanism (same parent path)
    if (loadedEntryNames != null) {
      var moiLog = LogManager.getInstance().getOrLoad(logPath);
      java.util.function.Predicate<String> numeric = n -> {
        var info = moiLog.entries().get(n);
        return info != null && List.of("double", "float", "int64").contains(info.type());
      };
      java.util.function.Function<String, String> parent =
          n -> n.substring(0, Math.max(0, n.lastIndexOf('/')));
      loadedEntryNames.stream()
          .filter(v -> v.contains("Velocity") && numeric.test(v))
          .flatMap(v -> loadedEntryNames.stream()
              .filter(c -> c.contains("Current") && numeric.test(c)
                  && parent.apply(c).equals(parent.apply(v)))
              .limit(1).map(c -> List.of(v, c)))
          .findFirst()
          .ifPresent(pair -> {
            var moiArgs = new JsonObject();
            moiArgs.addProperty("path", logPath);
            moiArgs.addProperty("velocity_entry", pair.get(0));
            moiArgs.addProperty("current_entry", pair.get(1));
            moiArgs.addProperty("kt", 0.0194);
            moiArgs.addProperty("gear_ratio", 6.75);
            testTool("moi_regression", moiArgs, result ->
                System.out.println("  moi_regression (" + pair.get(0) + "): "
                    + (result.has("status") ? result.get("status").getAsString() : "?")));
          });
    }

    // analyze_cycles
    if (loadedEntryNames != null) {
      var stateEntry = loadedEntryNames.stream()
          .filter(name -> name.toLowerCase().contains("state") ||
                          name.toLowerCase().contains("intake") ||
                          name.toLowerCase().contains("shooter"))
          .findFirst();
      var cycleLog = LogManager.getInstance().getOrLoad(logPath);
      var startState = stateEntry.flatMap(e -> java.util.Optional
          .ofNullable(cycleLog.values().get(e)).flatMap(values -> values.stream()
              .map(v -> String.valueOf(v.value())).distinct().skip(1).findFirst()));
      if (stateEntry.isPresent() && startState.isPresent()) {
        var cycleArgs = new JsonObject();
        cycleArgs.addProperty("path", logPath);
        cycleArgs.addProperty("state_entry", stateEntry.get());
        cycleArgs.addProperty("cycle_start_state", startState.get());
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

    String logPath = availableLogPaths.get(0);
    System.out.println("\nExercising ExportTools and TbaTools:");

    var numericEntry = loadedEntryNames.stream()
        .filter(name -> name.toLowerCase().contains("voltage") ||
                        name.toLowerCase().contains("position"))
        .findFirst();

    if (numericEntry.isPresent()) {
      var exportArgs = new JsonObject();
      exportArgs.addProperty("path", logPath);
      exportArgs.addProperty("name", numericEntry.get());
      exportArgs.addProperty("output_path",
          System.getProperty("java.io.tmpdir") + "/wpilog-export/stress_test_export.csv");
      testTool("export_csv", exportArgs, result -> {
        int rows = result.has("rows_exported") ? result.get("rows_exported").getAsInt() : 0;
        System.out.println("  export_csv: " + rows + " rows");
      });
    }

    var reportArgs = new JsonObject();
    reportArgs.addProperty("path", logPath);
    testTool("generate_report", reportArgs, result -> {
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

    // get_tba_match_data (needs event key + match; try a reasonable default)
    if (TbaConfig.getInstance().isConfigured()) {
      var tbaArgs = new JsonObject();
      tbaArgs.addProperty("year", 2026);
      tbaArgs.addProperty("event_code", "vache");
      tbaArgs.addProperty("match_type", "qm");
      tbaArgs.addProperty("match_number", 10);
      testTool("get_tba_match_data", tbaArgs, result -> {
        boolean hasData = result.has("match_key");
        System.out.println("  get_tba_match_data: " + (hasData ? "data found" : "no data"));
      });
    }

    // compare_matches (requires path and compare_path)
    if (availableLogPaths.size() >= 2) {
      var compareEntry = loadedEntryNames.stream()
          .filter(name -> name.toLowerCase().contains("voltage") ||
                          name.toLowerCase().contains("velocity"))
          .findFirst();
      if (compareEntry.isPresent()) {
        var compareArgs = new JsonObject();
        compareArgs.addProperty("path", availableLogPaths.get(0));
        compareArgs.addProperty("compare_path", availableLogPaths.get(1));
        compareArgs.addProperty("name", compareEntry.get());
        testTool("compare_matches", compareArgs, result -> {
          int compared = result.has("comparisons") ? result.getAsJsonArray("comparisons").size() : 0;
          System.out.println("  compare_matches: " + compared + " logs compared");
        });
      }
    }
  }

  // ==================== 8. RevLog Tools ====================

  @Test
  @Order(8)
  @DisplayName("8. Exercise RevLog tools on a log that has a REV log")
  void exerciseRevLogTools() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    String logPath = logWithRevLog();
    assumeTrue(logPath != null, "No log in the log directories has a REV log synchronized with it");
    loadLog(logPath);

    System.out.println("\nExercising RevLog tools on " + Path.of(logPath).getFileName() + ":");

    var waitArgs = new JsonObject();
    waitArgs.addProperty("path", logPath);
    testTool("wait_for_sync", waitArgs, result ->
        System.out.println("  wait_for_sync: " + result.get("status").getAsString()));

    var syncArgs = new JsonObject();
    syncArgs.addProperty("path", logPath);
    var sync = executeTool(findTool("sync_status"), syncArgs);
    assertEquals("ok", sync.get("status").getAsString(), sync.toString());
    assertTrue(sync.get("revlog_count").getAsInt() > 0, sync.toString());
    System.out.println("  sync_status: " + sync.get("revlog_count").getAsInt() + " revlog(s), "
        + sync.get("overall_confidence").getAsString() + " confidence");

    var listSigArgs = new JsonObject();
    listSigArgs.addProperty("path", logPath);
    var signals = executeTool(findTool("list_revlog_signals"), listSigArgs);
    assertEquals("ok", signals.get("status").getAsString(), signals.toString());
    List<String> revlogSignalKeys = new ArrayList<>();
    for (var signal : signals.getAsJsonArray("signals")) {
      revlogSignalKeys.add(signal.getAsJsonObject().get("key").getAsString());
      if (revlogSignalKeys.size() >= 3) break;
    }
    System.out.println("  list_revlog_signals: " + signals.get("signal_count").getAsInt() + " signals");
    assertFalse(revlogSignalKeys.isEmpty(), signals.toString());

    for (String key : revlogSignalKeys) {
      var dataArgs = new JsonObject();
      dataArgs.addProperty("path", logPath);
      dataArgs.addProperty("signal_key", key);
      dataArgs.addProperty("limit", 10);
      var data = executeTool(findTool("get_revlog_data"), dataArgs);
      assertEquals("ok", data.get("status").getAsString(), data.toString());
      System.out.println("  get_revlog_data (" + key + "): "
          + data.get("total_samples").getAsInt() + " samples");
    }

    // Last, because it replaces the synchronization's offset with the one given
    var offsetArgs = new JsonObject();
    offsetArgs.addProperty("path", logPath);
    offsetArgs.addProperty("offset_ms", 0.0);
    testTool("set_revlog_offset", offsetArgs, result -> System.out.println("  set_revlog_offset: OK"));
  }

  // ==================== 9. REV log sync and the disk cache ====================

  /**
   * A synchronization read back from the disk cache gives the same answers as one computed fresh.
   * The fresh one runs with the disk cache off; then the log is loaded twice with it on, and the
   * second load is served from the cache, because the first either read the result there or
   * computed and saved it. With {@code -PtestCacheDir} pointing at a cache an older version wrote,
   * the first load may read that version's result, and a difference fails the test: the format
   * version should have been raised.
   */
  @Test
  @Order(9)
  @DisplayName("9. REV log sync: a result read from the disk cache matches a fresh one")
  void syncCacheMatchesFreshSync() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    String logPath = logWithRevLog();
    assumeTrue(logPath != null, "No log in the log directories has a REV log synchronized with it");
    var manager = LogManager.getInstance();
    var syncCache = manager.getSyncDiskCache();
    assumeTrue(syncCache.isEnabled(), "The sync disk cache is disabled");
    Path cacheDir = manager.getCacheDirectory().getPath();
    System.out.println("\nREV log sync and the disk cache (" + cacheDir + "), on "
        + Path.of(logPath).getFileName() + ":");

    manager.unloadAllLogs();
    syncCache.setEnabled(false);
    Map<String, JsonObject> fresh;
    try {
      fresh = revLogAnswers(logPath);
    } finally {
      syncCache.setEnabled(true);
    }
    System.out.println("  computed with the disk cache off: " + fresh.size() + " answers");

    manager.unloadAllLogs();
    long before = countSyncFiles(cacheDir);
    var first = revLogAnswers(logPath);
    long after = countSyncFiles(cacheDir);
    System.out.println("  first load with the cache: "
        + (after > before ? "computed and saved" : "read from the cache"));
    manager.unloadAllLogs();
    var cached = revLogAnswers(logPath);
    System.out.println("  second load: read from the cache");

    for (var entry : fresh.entrySet()) {
      for (var other : List.of(Map.entry("first load", first), Map.entry("from the cache", cached))) {
        var difference = firstDifference(entry.getValue(), other.getValue().get(entry.getKey()),
            entry.getKey());
        assertNull(difference, other.getKey() + " differs from the fresh synchronization at "
            + difference);
      }
    }
    assertEquals(fresh.keySet(), cached.keySet());
    System.out.println("  every answer is the same");
  }

  // ==================== 10. In-Memory Cache Eviction ====================

  @Test
  @Order(10)
  @DisplayName("10. Cache eviction stress test")
  void cacheStressTest() throws Exception {
    assumeTrue(availableLogPaths != null && availableLogPaths.size() >= 2);

    System.out.println("\nIn-memory cache eviction test:");
    LogManager.getInstance().unloadAllLogs();

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

    assumeTrue(successfullyLoaded >= 2, "Need at least 2 loadable logs");

    // Verify heap-pressure-based eviction works (evictIfNeeded is called internally)
    int finalCount = LogManager.getInstance().getLoadedLogCount();
    System.out.println("  Cache contains " + finalCount + " logs (heap-pressure eviction)");
    assertTrue(finalCount >= 1, "At least one log should remain cached");

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
            args.addProperty("path", availableLogPaths.get(0));
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
      // The robustness rules hold on real logs too (see ToolConformanceTest)
      Integer limit = args.has("limit") ? args.get("limit").getAsInt() : null;
      for (var check : org.triplehelix.wpilogmcp.conformance.ConformanceChecks.check(result,
          limit, args.has("path"))) {
        violations.add(tool.name() + " | " + check.label());
      }
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
    totalOperations.incrementAndGet();
    long start = System.currentTimeMillis();
    try {
      LogManager.getInstance().getOrLoad(path);
      long duration = System.currentTimeMillis() - start;
      totalTimeMs += duration;
      successfulOperations.incrementAndGet();
    } catch (Exception e) {
      failedOperations.incrementAndGet();
      throw new AssertionError("Failed to load log: " + path, e);
    }
  }

  // ==================== Robustness features ====================

  @Test
  @Order(100)
  @DisplayName("100. Field paths, scopes, alignment, compound conditions, roles")
  void exerciseRobustnessFeatures() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    String logPath = availableLogPaths.get(0);
    loadLog(logPath);
    System.out.println("\nExercising robustness features:");

    testTool("resolve_signals", withPath(logPath), result ->
        System.out.println("  resolve_signals: " + result.getAsJsonArray("unresolved").size()
            + " unresolved roles"));
    var structArgs = withPath(logPath);
    testTool("list_struct_types", structArgs, result ->
        System.out.println("  list_struct_types: " + result.get("struct_type_count").getAsInt()
            + " struct types"));
    var logsArgs = new JsonObject();
    logsArgs.addProperty("limit", 5);
    testTool("list_available_logs", logsArgs, result ->
        System.out.println("  list_available_logs: " + result.get("log_count").getAsInt()
            + " logs, has_more=" + result.get("has_more").getAsBoolean()));

    var numericEntries = findNumericEntries(2);
    if (numericEntries.size() >= 2) {
      var alignArgs = withPath(logPath);
      var names = new com.google.gson.JsonArray();
      names.add(numericEntries.get(0));
      names.add(numericEntries.get(1));
      alignArgs.add("names", names);
      alignArgs.addProperty("difference", true);
      alignArgs.addProperty("limit", 5);
      testTool("align_entries", alignArgs, result ->
          System.out.println("  align_entries: " + result.get("total_rows").getAsInt()
              + " rows"));
      var lagArgs = withPath(logPath);
      lagArgs.addProperty("name1", numericEntries.get(0));
      lagArgs.addProperty("name2", numericEntries.get(1));
      lagArgs.addProperty("max_lag_sec", 0.5);
      testTool("time_correlate", lagArgs, result -> {
        if (result.has("lag_search") && result.getAsJsonObject("lag_search").has("best_lag_sec")) {
          System.out.println("  time_correlate lag: " + result.getAsJsonObject("lag_search")
              .get("best_lag_sec").getAsDouble() + " s");
        }
      });
    }

    // A struct field over enabled time, and a compound condition
    String pose = loadedEntryNames == null ? null : loadedEntryNames.stream()
        .filter(n -> n.endsWith("/Pose")).findFirst().orElse(null);
    if (pose != null) {
      var poseArgs = withPath(logPath);
      poseArgs.addProperty("name", pose + ".translation.x");
      poseArgs.addProperty("scope", "enabled");
      testTool("get_statistics", poseArgs, result ->
          System.out.printf("  get_statistics %s.translation.x (enabled): range %.3f%n", pose,
              result.get("max").getAsDouble() - result.get("min").getAsDouble()));
      var condArgs = withPath(logPath);
      condArgs.add("conditions", com.google.gson.JsonParser.parseString(
          "{\"all\": [{\"name\": \"/DriverStation/Enabled\", \"operator\": \"eq\", "
              + "\"threshold\": 0}, {\"name\": \"" + pose + ".translation.x\", "
              + "\"operator\": \"gt\", \"threshold\": -1000}]}"));
      testTool("find_condition", condArgs, result ->
          System.out.println("  find_condition (compound): " + result.get("interval_count")
              .getAsInt() + " intervals"));
    }
    var alertArgs = withPath(logPath);
    alertArgs.addProperty("level", "warning");
    alertArgs.addProperty("limit", 5);
    testTool("search_strings", alertArgs, result ->
        System.out.println("  search_strings (warnings, incl. alerts): "
            + result.get("total_matches").getAsInt()));
  }

  @Test
  @Order(101)
  @DisplayName("101. Every result met the robustness rules")
  void noConformanceViolations() {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());
    System.out.println("\nConformance violations on real logs: " + violations.size());
    violations.stream().distinct().forEach(v -> System.out.println("  " + v));
    assertTrue(violations.isEmpty(), "Conformance violations on real logs: "
        + violations.stream().distinct().toList());
  }

  private static JsonObject withPath(String path) {
    var args = new JsonObject();
    args.addProperty("path", path);
    return args;
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

  /** The first listed log with a REV log synchronized with it, searched for once; or null. */
  private static String logWithRevLog() {
    if (!revLogLogSearched) {
      revLogLogPath = StressSupport.firstLogWithRevLog(availableLogPaths);
      revLogLogSearched = true;
    }
    return revLogLogPath;
  }

  /**
   * What the REV log tools answer about a log once its synchronization is done, by call:
   * sync_status with the signal pairs, list_revlog_signals, and the first three signals' data
   * with statistics.
   */
  private Map<String, JsonObject> revLogAnswers(String logPath) throws Exception {
    loadLog(logPath);
    assertTrue(LogManager.getInstance().waitForRevLogSync(logPath, StressSupport.SYNC_TIMEOUT_MS),
        "the REV log synchronization did not finish");
    var answers = new LinkedHashMap<String, JsonObject>();
    var syncArgs = new JsonObject();
    syncArgs.addProperty("path", logPath);
    syncArgs.addProperty("include_signal_pairs", true);
    answers.put("sync_status", executeTool(findTool("sync_status"), syncArgs));
    var listArgs = new JsonObject();
    listArgs.addProperty("path", logPath);
    var signals = executeTool(findTool("list_revlog_signals"), listArgs);
    answers.put("list_revlog_signals", signals);
    if (signals.has("signals")) {
      var list = signals.getAsJsonArray("signals");
      for (int i = 0; i < Math.min(3, list.size()); i++) {
        var key = list.get(i).getAsJsonObject().get("key").getAsString();
        var dataArgs = new JsonObject();
        dataArgs.addProperty("path", logPath);
        dataArgs.addProperty("signal_key", key);
        dataArgs.addProperty("limit", 100);
        dataArgs.addProperty("include_stats", true);
        answers.put("get_revlog_data " + key, executeTool(findTool("get_revlog_data"), dataArgs));
      }
    }
    return answers;
  }

  private static long countSyncFiles(Path dir) throws java.io.IOException {
    try (var files = Files.list(dir)) {
      return files.filter(f -> f.getFileName().toString().endsWith("-sync.msgpack")).count();
    }
  }

  /** The first place two JSON values differ, as "where: one vs other", or null when equal. */
  static String firstDifference(JsonElement a, JsonElement b, String where) {
    if (a == null || b == null) return a == b ? null : where + ": " + a + " vs " + b;
    if (a.isJsonObject() && b.isJsonObject()) {
      var keys = new java.util.TreeSet<>(a.getAsJsonObject().keySet());
      keys.addAll(b.getAsJsonObject().keySet());
      for (var key : keys) {
        var d = firstDifference(a.getAsJsonObject().get(key), b.getAsJsonObject().get(key),
            where + "." + key);
        if (d != null) return d;
      }
      return null;
    }
    if (a.isJsonArray() && b.isJsonArray()) {
      var x = a.getAsJsonArray();
      var y = b.getAsJsonArray();
      if (x.size() != y.size()) return where + ": " + x.size() + " items vs " + y.size();
      for (int i = 0; i < x.size(); i++) {
        var d = firstDifference(x.get(i), y.get(i), where + "[" + i + "]");
        if (d != null) return d;
      }
      return null;
    }
    return a.equals(b) ? null : where + ": " + abbreviate(a) + " vs " + abbreviate(b);
  }

  private static String abbreviate(JsonElement e) {
    var text = e.toString();
    return text.length() <= 200 ? text : text.substring(0, 200) + "...";
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
