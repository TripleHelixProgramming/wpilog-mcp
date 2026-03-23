package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

import java.util.*;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * Discovery tools to help LLM agents understand and use the server's capabilities.
 *
 * <p>These tools address a fundamental discoverability problem: MCP exposes a flat list of 49
 * tools, and LLM agents often don't know which tools exist, what they can do, or when to use
 * them instead of fetching raw data and writing custom analysis code.
 *
 * <p>Tools included:
 * <ul>
 *   <li>{@code get_server_guide} - Comprehensive overview of all capabilities, grouped by category</li>
 *   <li>{@code suggest_tools} - Recommend tools for a given analysis task</li>
 * </ul>
 *
 * @since 0.6.1
 */
public final class DiscoveryTools {

  private DiscoveryTools() {}

  /**
   * Registers all discovery tools with the MCP server.
   */
  public static void registerAll(ToolRegistry registry) {
    registry.registerTool(new GetServerGuideTool());
    registry.registerTool(new SuggestToolsTool());
  }

  // ==================== TOOL CATALOG ====================
  // Central registry of all tools with metadata for discovery

  /**
   * Tool metadata for discovery purposes.
   */
  record ToolInfo(
      String name,
      String category,
      String briefDescription,
      List<String> keywords,
      List<String> useCases,
      boolean requiresLog,
      List<String> relatedTools
  ) {}

  /**
   * Category metadata for the server guide.
   */
  record CategoryInfo(
      String name,
      String description,
      String antiPattern,
      List<String> toolNames
  ) {}

  private static final List<ToolInfo> TOOL_CATALOG = buildToolCatalog();
  private static final List<CategoryInfo> CATEGORIES = buildCategories();

  private static List<ToolInfo> buildToolCatalog() {
    var tools = new ArrayList<ToolInfo>();

    // === CORE TOOLS ===
    tools.add(new ToolInfo("list_available_logs", "core",
        "List WPILOG files with TBA match data enrichment",
        List.of("logs", "files", "directory", "tba", "match", "score", "win", "loss"),
        List.of("Find available log files", "Get match scores from TBA", "See which matches we won/lost"),
        false, List.of("load_log", "get_tba_status")));

    tools.add(new ToolInfo("load_log", "core",
        "Load a WPILOG file for analysis",
        List.of("load", "open", "wpilog", "file"),
        List.of("Start analyzing a match log", "Load robot telemetry data"),
        false, List.of("list_available_logs", "list_entries")));

    tools.add(new ToolInfo("list_entries", "core",
        "List all data entries in the loaded log",
        List.of("entries", "signals", "channels", "data", "available"),
        List.of("See what data is logged", "Find entry names", "Discover available telemetry"),
        true, List.of("get_entry_info", "search_entries")));

    tools.add(new ToolInfo("get_entry_info", "core",
        "Get detailed info about a specific entry",
        List.of("entry", "info", "type", "metadata"),
        List.of("Check entry data type", "See sample count for an entry"),
        true, List.of("read_entry", "list_entries")));

    tools.add(new ToolInfo("read_entry", "core",
        "Read raw values from an entry with pagination",
        List.of("read", "values", "data", "raw", "samples"),
        List.of("Get raw telemetry values", "Read specific time range"),
        true, List.of("get_entry_info", "get_statistics")));

    tools.add(new ToolInfo("list_loaded_logs", "core",
        "List all loaded logs and cache status",
        List.of("loaded", "cache", "memory"),
        List.of("See which logs are in memory", "Check cache usage"),
        false, List.of("set_active_log", "unload_log")));

    tools.add(new ToolInfo("set_active_log", "core",
        "Switch between loaded logs for multi-match analysis",
        List.of("switch", "active", "select"),
        List.of("Switch to a different match", "Compare across matches"),
        false, List.of("list_loaded_logs", "compare_matches")));

    tools.add(new ToolInfo("unload_log", "core",
        "Unload a specific log file from memory",
        List.of("unload", "free", "memory", "close"),
        List.of("Free memory from a loaded log", "Close a specific match log"),
        false, List.of("list_loaded_logs", "unload_all_logs")));

    tools.add(new ToolInfo("unload_all_logs", "core",
        "Unload all log files from memory",
        List.of("unload", "free", "memory", "clear", "all"),
        List.of("Free all memory", "Clear all loaded logs"),
        false, List.of("list_loaded_logs", "unload_log")));

    tools.add(new ToolInfo("list_struct_types", "core",
        "List all WPILib struct types found in the log",
        List.of("struct", "types", "schema", "wpilib", "protobuf"),
        List.of("See what struct types are in the log", "Find custom struct definitions"),
        true, List.of("get_types", "search_entries")));

    tools.add(new ToolInfo("health_check", "core",
        "Check server health and version information",
        List.of("health", "status", "version", "memory", "heap"),
        List.of("Check server status", "Monitor memory usage", "Get version info"),
        false, List.of("list_loaded_logs")));

    // === QUERY TOOLS ===
    tools.add(new ToolInfo("search_entries", "query",
        "Search entries by type, name pattern, or sample count",
        List.of("search", "find", "filter", "pattern", "type"),
        List.of("Find all Pose2d entries", "Find entries with enough data", "Search by name pattern"),
        true, List.of("list_entries", "get_types")));

    tools.add(new ToolInfo("get_types", "query",
        "List all data types in the log",
        List.of("types", "schema", "struct"),
        List.of("See what types of data are logged", "Find struct types"),
        true, List.of("search_entries")));

    tools.add(new ToolInfo("find_condition", "query",
        "Find timestamps where values cross thresholds",
        List.of("threshold", "condition", "when", "trigger", "event"),
        List.of("When did voltage drop below 11V?", "Find brownout events", "Detect threshold crossings"),
        true, List.of("power_analysis", "detect_anomalies")));

    tools.add(new ToolInfo("search_strings", "query",
        "Search string entries for text patterns",
        List.of("search", "text", "string", "error", "warning", "message"),
        List.of("Find error messages", "Search console output", "Find specific warnings"),
        true, List.of("can_health", "generate_report")));

    // === STATISTICS TOOLS ===
    tools.add(new ToolInfo("get_statistics", "statistics",
        "Compute comprehensive statistics on numeric entries",
        List.of("statistics", "stats", "mean", "std", "percentile", "min", "max", "variance"),
        List.of("Get battery voltage statistics", "Analyze motor current distribution", "Compute percentiles"),
        true, List.of("compare_entries", "detect_anomalies")));

    tools.add(new ToolInfo("compare_entries", "statistics",
        "Compare two entries (e.g., setpoint vs actual)",
        List.of("compare", "diff", "rmse", "correlation", "error"),
        List.of("Compare commanded vs actual velocity", "Validate replay outputs", "Find tracking error"),
        true, List.of("get_statistics", "time_correlate")));

    tools.add(new ToolInfo("detect_anomalies", "statistics",
        "Find outliers using IQR method",
        List.of("anomaly", "outlier", "spike", "unusual", "iqr"),
        List.of("Find current spikes", "Detect unusual sensor readings", "Identify outliers"),
        true, List.of("get_statistics", "find_peaks")));

    tools.add(new ToolInfo("find_peaks", "statistics",
        "Find local maxima and minima",
        List.of("peak", "max", "min", "local", "extrema"),
        List.of("Find peak current draw", "Identify velocity peaks", "Find acceleration events"),
        true, List.of("detect_anomalies", "rate_of_change")));

    tools.add(new ToolInfo("rate_of_change", "statistics",
        "Compute derivatives (velocity from position, acceleration from velocity)",
        List.of("derivative", "rate", "velocity", "acceleration", "change"),
        List.of("Compute acceleration from velocity", "Find rate of change", "Differentiate signals"),
        true, List.of("find_peaks", "get_statistics")));

    tools.add(new ToolInfo("time_correlate", "statistics",
        "Compute correlation between two entries",
        List.of("correlation", "correlate", "relationship", "lag"),
        List.of("Check if signals are correlated", "Find time lag between signals", "Measure relationship strength"),
        true, List.of("compare_entries")));

    // === ROBOT ANALYSIS TOOLS ===
    tools.add(new ToolInfo("get_match_phases", "robot_analysis",
        "Detect match phases (auto/teleop) from DriverStation data",
        List.of("match", "phase", "auto", "teleop", "autonomous", "enabled", "disabled"),
        List.of("Find auto start/end times", "Get teleop duration", "Detect match structure"),
        true, List.of("analyze_auto", "get_ds_timeline")));

    tools.add(new ToolInfo("analyze_swerve", "robot_analysis",
        "Analyze swerve drive module performance",
        List.of("swerve", "module", "wheel", "drive", "slip", "odometry", "drift"),
        List.of("Check module speeds", "Detect wheel slip", "Analyze odometry drift"),
        true, List.of("power_analysis", "analyze_auto")));

    tools.add(new ToolInfo("power_analysis", "robot_analysis",
        "Analyze battery voltage and current distribution",
        List.of("power", "battery", "voltage", "current", "brownout", "pdp", "pdh"),
        List.of("Check for brownouts", "Find peak current draw", "Analyze battery health"),
        true, List.of("predict_battery_health", "can_health")));

    tools.add(new ToolInfo("can_health", "robot_analysis",
        "Analyze CAN bus health and error counts",
        List.of("can", "bus", "error", "timeout", "fault"),
        List.of("Check for CAN errors", "Find communication issues", "Diagnose CAN problems"),
        true, List.of("power_analysis")));

    tools.add(new ToolInfo("compare_matches", "robot_analysis",
        "Compare statistics across multiple loaded logs",
        List.of("compare", "match", "cross", "multiple"),
        List.of("Compare auto performance across matches", "Find patterns across matches"),
        true, List.of("set_active_log", "get_statistics")));

    tools.add(new ToolInfo("get_code_metadata", "robot_analysis",
        "Extract Git SHA, branch, and build info",
        List.of("git", "code", "version", "build", "sha", "branch"),
        List.of("Check what code version was running", "Find Git commit"),
        true, List.of("generate_report")));

    tools.add(new ToolInfo("moi_regression", "robot_analysis",
        "Estimate moment of inertia and damping from motor data",
        List.of("moi", "inertia", "damping", "regression", "motor", "mechanism"),
        List.of("Estimate mechanism inertia", "Characterize motor load", "System identification"),
        true, List.of("profile_mechanism")));

    // === FRC DOMAIN TOOLS ===
    tools.add(new ToolInfo("get_ds_timeline", "frc_domain",
        "Get DriverStation event timeline with mode transitions",
        List.of("driverstation", "ds", "timeline", "events", "mode"),
        List.of("See all DS events", "Find mode transitions", "Debug enable/disable timing"),
        true, List.of("get_match_phases", "analyze_auto")));

    tools.add(new ToolInfo("analyze_vision", "frc_domain",
        "Analyze vision system reliability and latency",
        List.of("vision", "camera", "apriltag", "latency", "fps"),
        List.of("Check vision update rate", "Measure vision latency", "Analyze target detection"),
        true, List.of("analyze_swerve")));

    tools.add(new ToolInfo("profile_mechanism", "frc_domain",
        "Analyze mechanism health and control tuning",
        List.of("mechanism", "arm", "elevator", "shooter", "tuning", "pid"),
        List.of("Check mechanism performance", "Analyze control response", "Find tuning issues"),
        true, List.of("moi_regression", "get_statistics")));

    tools.add(new ToolInfo("analyze_auto", "frc_domain",
        "Profile autonomous routine execution",
        List.of("auto", "autonomous", "routine", "path", "trajectory"),
        List.of("Analyze auto performance", "Check path following", "Debug auto issues"),
        true, List.of("get_match_phases", "analyze_cycles")));

    tools.add(new ToolInfo("analyze_cycles", "frc_domain",
        "Detect and analyze game piece cycle times",
        List.of("cycle", "scoring", "intake", "game", "piece", "coral", "algae", "note"),
        List.of("Measure cycle times", "Count scoring events", "Analyze throughput"),
        true, List.of("analyze_auto", "get_match_phases")));

    tools.add(new ToolInfo("analyze_replay_drift", "frc_domain",
        "Validate AdvantageKit replay consistency",
        List.of("replay", "advantagekit", "drift", "determinism", "validation"),
        List.of("Check replay accuracy", "Find non-determinism", "Validate logging"),
        true, List.of("compare_entries")));

    tools.add(new ToolInfo("predict_battery_health", "frc_domain",
        "Estimate battery health from voltage/current curves",
        List.of("battery", "health", "sag", "internal", "resistance"),
        List.of("Estimate battery age", "Check battery health", "Predict brownout risk"),
        true, List.of("power_analysis")));

    tools.add(new ToolInfo("analyze_loop_timing", "frc_domain",
        "Analyze robot loop timing consistency",
        List.of("loop", "timing", "overrun", "latency", "jitter"),
        List.of("Check for loop overruns", "Analyze timing jitter", "Find slow loops"),
        true, List.of("can_health")));

    tools.add(new ToolInfo("get_game_info", "frc_domain",
        "Get FRC game-specific information (match timing, field dimensions)",
        List.of("game", "frc", "field", "match", "timing", "rules"),
        List.of("Get match phase durations", "Find field dimensions", "Check game-specific rules"),
        false, List.of("get_match_phases", "analyze_cycles")));

    // === EXPORT TOOLS ===
    tools.add(new ToolInfo("export_csv", "export",
        "Export entry data to CSV for external analysis",
        List.of("export", "csv", "excel", "matlab", "python"),
        List.of("Export data for Excel", "Create CSV for Python analysis"),
        true, List.of("read_entry")));

    tools.add(new ToolInfo("generate_report", "export",
        "Generate comprehensive match summary report",
        List.of("report", "summary", "overview"),
        List.of("Get match overview", "Generate summary report"),
        true, List.of("power_analysis", "can_health")));

    // === TBA TOOLS ===
    tools.add(new ToolInfo("get_tba_status", "tba",
        "Check The Blue Alliance API integration status",
        List.of("tba", "api", "status", "blue", "alliance"),
        List.of("Check if TBA is configured", "Verify API connectivity"),
        false, List.of("list_available_logs", "get_tba_match_data")));

    tools.add(new ToolInfo("get_tba_match_data", "tba",
        "Query match scores and results from The Blue Alliance",
        List.of("tba", "match", "score", "win", "loss", "points", "autonomous", "teleop"),
        List.of("Get match scores", "Check autonomous points", "Find alliance results"),
        false, List.of("list_available_logs", "get_tba_status")));

    // === REVLOG TOOLS ===
    tools.add(new ToolInfo("list_revlog_signals", "revlog",
        "List available REV motor controller signals",
        List.of("rev", "revlog", "spark", "motor", "signals"),
        List.of("See available REV data", "Find motor controller signals"),
        true, List.of("get_revlog_data", "sync_status")));

    tools.add(new ToolInfo("get_revlog_data", "revlog",
        "Query REV signal data with synchronized timestamps",
        List.of("rev", "revlog", "spark", "motor", "data"),
        List.of("Get motor controller data", "Read REV signals"),
        true, List.of("list_revlog_signals")));

    tools.add(new ToolInfo("sync_status", "revlog",
        "Get REV log synchronization confidence and details",
        List.of("sync", "synchronization", "revlog", "timestamp", "offset"),
        List.of("Check timestamp accuracy", "Verify sync quality"),
        true, List.of("list_revlog_signals")));

    tools.add(new ToolInfo("set_revlog_offset", "revlog",
        "Manually set the REV log time offset",
        List.of("revlog", "offset", "manual", "sync", "timestamp"),
        List.of("Override automatic sync offset", "Manually align REV timestamps"),
        true, List.of("sync_status", "list_revlog_signals")));

    tools.add(new ToolInfo("wait_for_sync", "revlog",
        "Wait for REV log synchronization to complete",
        List.of("wait", "sync", "revlog", "synchronization", "ready"),
        List.of("Wait until REV sync is done", "Block until synchronization completes"),
        true, List.of("sync_status", "list_revlog_signals")));

    // === ROBOT ANALYSIS - additional ===
    tools.add(new ToolInfo("analyze_can_bus", "robot_analysis",
        "Analyze CAN bus utilization and error patterns",
        List.of("can", "bus", "utilization", "error", "bandwidth"),
        List.of("Check CAN bus utilization", "Find CAN error patterns", "Diagnose bus congestion"),
        true, List.of("can_health", "power_analysis")));

    // === DISCOVERY TOOLS ===
    tools.add(new ToolInfo("get_server_guide", "discovery",
        "Get comprehensive overview of server capabilities",
        List.of("help", "guide", "capabilities", "overview", "tools"),
        List.of("What can this server do?", "How do I use this?", "What tools are available?"),
        false, List.of("suggest_tools")));

    tools.add(new ToolInfo("suggest_tools", "discovery",
        "Get tool recommendations for a specific analysis task",
        List.of("suggest", "recommend", "help", "which", "tool"),
        List.of("What tool should I use for X?", "Help me analyze Y", "Recommend tools for Z"),
        false, List.of("get_server_guide")));

    return tools;
  }

  private static List<CategoryInfo> buildCategories() {
    return List.of(
        new CategoryInfo("core",
            "Log loading and data access. Start here to load logs and discover available data.",
            "Don't manually parse log files—use load_log and list_entries.",
            List.of("list_available_logs", "load_log", "list_entries", "get_entry_info", "read_entry",
                "list_loaded_logs", "set_active_log", "unload_log", "unload_all_logs")),

        new CategoryInfo("query",
            "Search and filter log data. Find specific entries, types, and events.",
            "Don't iterate through all entries manually—use search_entries or find_condition.",
            List.of("search_entries", "get_types", "find_condition", "search_strings")),

        new CategoryInfo("statistics",
            "Statistical analysis on numeric data. Compute stats, find correlations, detect anomalies.",
            "NEVER compute statistics manually with code—use get_statistics. "
                + "NEVER compute correlation manually—use time_correlate.",
            List.of("get_statistics", "compare_entries", "detect_anomalies", "find_peaks",
                "rate_of_change", "time_correlate")),

        new CategoryInfo("robot_analysis",
            "Robot-specific analysis: power, swerve, CAN health, match phases.",
            "Don't write custom brownout detection—use power_analysis. "
                + "Don't manually detect match phases—use get_match_phases.",
            List.of("get_match_phases", "analyze_swerve", "power_analysis", "can_health",
                "compare_matches", "get_code_metadata", "moi_regression", "analyze_can_bus")),

        new CategoryInfo("frc_domain",
            "FRC-specific analysis: vision, mechanisms, cycles, autonomous, battery health.",
            "Don't estimate cycle times manually—use analyze_cycles. "
                + "Don't manually analyze auto—use analyze_auto.",
            List.of("get_ds_timeline", "analyze_vision", "profile_mechanism", "analyze_auto",
                "analyze_cycles", "analyze_replay_drift", "predict_battery_health",
                "analyze_loop_timing", "get_game_info")),

        new CategoryInfo("export",
            "Export data for external tools (Excel, Python, MATLAB).",
            "Only export if you truly need external analysis—most analysis is built-in.",
            List.of("export_csv", "generate_report")),

        new CategoryInfo("tba",
            "The Blue Alliance integration: match scores, results, and autonomous points.",
            "Don't guess match outcomes—use get_tba_match_data or check list_available_logs for TBA enrichment.",
            List.of("get_tba_status", "get_tba_match_data")),

        new CategoryInfo("revlog",
            "REV Hardware Client log analysis with synchronized timestamps.",
            "Don't manually align REV timestamps—synchronization is automatic.",
            List.of("list_revlog_signals", "get_revlog_data", "sync_status", "set_revlog_offset", "wait_for_sync")),

        new CategoryInfo("discovery",
            "Tools to help you discover and use server capabilities.",
            "When unsure what to do, use suggest_tools with your goal.",
            List.of("get_server_guide", "suggest_tools"))
    );
  }

  // ==================== TOOL IMPLEMENTATIONS ====================

  /**
   * Provides a comprehensive overview of all server capabilities.
   */
  static class GetServerGuideTool implements Tool {

    @Override
    public String name() {
      return "get_server_guide";
    }

    @Override
    public String description() {
      return "IMPORTANT: Call this tool first to understand what analysis capabilities are available. "
          + "Returns a structured overview of all 49 tools organized by category, with usage guidance "
          + "and anti-patterns to avoid. This server has extensive built-in analysis—don't write custom "
          + "code when a tool already exists.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("category", "string",
              "Filter by category: core, query, statistics, robot_analysis, frc_domain, export, tba, revlog, discovery",
              false)
          .addProperty("include_examples", "boolean",
              "Include example use cases for each tool (default: true)", false)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      String categoryFilter = getOptString(arguments, "category", null);
      boolean includeExamples = !arguments.has("include_examples")
          || arguments.get("include_examples").getAsBoolean();

      var result = new JsonObject();
      result.addProperty("success", true);

      // Overview section
      var overview = new JsonObject();
      overview.addProperty("server_name", "wpilog-mcp");
      overview.addProperty("version", org.triplehelix.wpilogmcp.Version.VERSION);
      overview.addProperty("total_tools", TOOL_CATALOG.size());
      overview.addProperty("purpose",
          "Parse and analyze FRC robot telemetry logs (.wpilog) and REV motor controller logs (.revlog)");
      result.add("overview", overview);

      // Critical guidance
      var guidance = new JsonObject();
      guidance.addProperty("primary_rule",
          "ALWAYS check for a built-in tool before writing custom analysis code. "
          + "This server has 49 specialized tools covering statistics, power analysis, "
          + "swerve diagnostics, cycle detection, battery health prediction, and more.");
      guidance.addProperty("tba_tip",
          "To get match scores: call list_available_logs (includes TBA data) or get_tba_match_data. "
          + "TBA data includes autonomous points, final scores, and win/loss results.");
      guidance.addProperty("statistics_tip",
          "NEVER compute mean/std/percentiles manually—use get_statistics. "
          + "NEVER compute correlation manually—use time_correlate.");
      guidance.addProperty("match_phases_tip",
          "NEVER manually parse timestamps to find auto/teleop—use get_match_phases.");
      result.add("critical_guidance", guidance);

      // Limitations section - important for correct client behavior
      var limitations = new JsonObject();
      limitations.addProperty("concurrency",
          "NOT SAFE FOR CONCURRENT USE. This server maintains shared state (active log, log cache). "
          + "Do not call multiple tools in parallel from the same session. Execute tool calls sequentially.");
      limitations.addProperty("single_session",
          "Designed for single-client use. If multiple agents or sessions share this server, "
          + "they will conflict over the active log and cache state.");
      limitations.addProperty("multi_instance_workaround",
          "Running multiple SEPARATE server instances pointing to the same log directory IS safe. "
          + "The disk cache uses file locking and atomic operations. Each instance has its own in-memory state.");
      limitations.addProperty("llm_sub_agent_warning",
          "IMPORTANT: Some LLM frameworks spawn sub-agents that may parallelize work and ignore this guidance. "
          + "Explicitly instruct your agent to operate sequentially when analyzing multiple logs.");
      result.add("limitations", limitations);

      // Categories section
      var categoriesArray = new JsonArray();
      for (var category : CATEGORIES) {
        if (categoryFilter != null && !category.name().equals(categoryFilter)) {
          continue;
        }

        var catObj = new JsonObject();
        catObj.addProperty("name", category.name());
        catObj.addProperty("description", category.description());
        catObj.addProperty("anti_pattern", category.antiPattern());

        // Tools in this category
        var toolsArray = new JsonArray();
        for (var toolInfo : TOOL_CATALOG) {
          if (!toolInfo.category().equals(category.name())) continue;

          var toolObj = new JsonObject();
          toolObj.addProperty("name", toolInfo.name());
          toolObj.addProperty("description", toolInfo.briefDescription());
          toolObj.addProperty("requires_log", toolInfo.requiresLog());

          if (includeExamples && !toolInfo.useCases().isEmpty()) {
            toolObj.add("example_uses", GSON.toJsonTree(toolInfo.useCases()));
          }

          if (!toolInfo.relatedTools().isEmpty()) {
            toolObj.add("related_tools", GSON.toJsonTree(toolInfo.relatedTools()));
          }

          toolsArray.add(toolObj);
        }
        catObj.add("tools", toolsArray);
        catObj.addProperty("tool_count", toolsArray.size());
        categoriesArray.add(catObj);
      }
      result.add("categories", categoriesArray);

      // Common workflows
      var workflows = new JsonArray();

      var basicWorkflow = new JsonObject();
      basicWorkflow.addProperty("name", "Basic Match Analysis");
      basicWorkflow.add("steps", GSON.toJsonTree(List.of(
          "1. list_available_logs - See available logs with TBA match data",
          "2. load_log - Load a specific match",
          "3. get_match_phases - Find auto/teleop timing",
          "4. generate_report - Get overview of match health",
          "5. Use specialized tools as needed (power_analysis, analyze_swerve, etc.)"
      )));
      workflows.add(basicWorkflow);

      var cycleWorkflow = new JsonObject();
      cycleWorkflow.addProperty("name", "Cycle Time Analysis");
      cycleWorkflow.add("steps", GSON.toJsonTree(List.of(
          "1. load_log - Load match log",
          "2. analyze_cycles - Detect and measure cycle times",
          "3. get_tba_match_data - Verify against actual scored points",
          "4. compare_matches - Compare cycle times across matches"
      )));
      workflows.add(cycleWorkflow);

      var powerWorkflow = new JsonObject();
      powerWorkflow.addProperty("name", "Power/Brownout Investigation");
      powerWorkflow.add("steps", GSON.toJsonTree(List.of(
          "1. load_log - Load match log",
          "2. power_analysis - Check for brownouts and current peaks",
          "3. find_condition - Find exact timestamps of voltage drops",
          "4. predict_battery_health - Assess battery condition",
          "5. can_health - Check for CAN errors (often accompany brownouts)"
      )));
      workflows.add(powerWorkflow);

      result.add("common_workflows", workflows);

      return result;
    }
  }

  /**
   * Recommends tools based on a natural language task description.
   */
  static class SuggestToolsTool implements Tool {

    @Override
    public String name() {
      return "suggest_tools";
    }

    @Override
    public String description() {
      return "Given a natural language description of what you want to analyze, "
          + "this tool recommends the most relevant tools and provides a suggested workflow. "
          + "Use this when unsure which tools to use for a specific analysis task.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("task", "string",
              "Natural language description of what you want to analyze "
              + "(e.g., 'check why our auto was inconsistent' or 'investigate brownout during teleop')",
              true)
          .addIntegerProperty("max_suggestions", "Maximum number of tools to suggest (default: 5)", false, 5)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      String task = getRequiredString(arguments, "task").toLowerCase();
      int maxSuggestions = getOptInt(arguments, "max_suggestions", 5);

      // Score each tool based on keyword matches
      var scores = new HashMap<ToolInfo, Integer>();
      for (var tool : TOOL_CATALOG) {
        int score = 0;

        // Check keywords
        for (var keyword : tool.keywords()) {
          if (task.contains(keyword)) {
            score += 2;
          }
        }

        // Check use cases
        for (var useCase : tool.useCases()) {
          if (task.contains(useCase.toLowerCase()) || fuzzyMatch(task, useCase.toLowerCase())) {
            score += 3;
          }
        }

        // Check tool name
        if (task.contains(tool.name().replace("_", " "))) {
          score += 5;
        }

        // Check category-specific boosts
        if (task.contains("auto") && tool.category().equals("frc_domain")) score += 1;
        if (task.contains("battery") && (tool.name().contains("power") || tool.name().contains("battery"))) score += 3;
        if (task.contains("score") && tool.category().equals("tba")) score += 3;
        if (task.contains("cycle") && tool.name().contains("cycle")) score += 3;
        if (task.contains("brownout") && tool.name().contains("power")) score += 3;
        if (task.contains("swerve") && tool.name().contains("swerve")) score += 3;
        if (task.contains("vision") && tool.name().contains("vision")) score += 3;
        if (task.contains("statistics") || task.contains("average") || task.contains("mean")) {
          if (tool.name().equals("get_statistics")) score += 5;
        }
        if (task.contains("correlat")) {
          if (tool.name().equals("time_correlate")) score += 5;
        }

        if (score > 0) {
          scores.put(tool, score);
        }
      }

      // Sort by score and take top N
      var sortedTools = scores.entrySet().stream()
          .sorted((a, b) -> b.getValue() - a.getValue())
          .limit(maxSuggestions)
          .toList();

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("task", task);

      var suggestions = new JsonArray();
      for (var entry : sortedTools) {
        var tool = entry.getKey();
        var suggestion = new JsonObject();
        suggestion.addProperty("tool", tool.name());
        suggestion.addProperty("description", tool.briefDescription());
        suggestion.addProperty("relevance_score", entry.getValue());
        suggestion.addProperty("category", tool.category());
        suggestion.add("example_uses", GSON.toJsonTree(tool.useCases()));
        if (!tool.relatedTools().isEmpty()) {
          suggestion.add("related_tools", GSON.toJsonTree(tool.relatedTools()));
        }
        suggestions.add(suggestion);
      }
      result.add("suggestions", suggestions);
      result.addProperty("suggestion_count", suggestions.size());

      // Generate workflow suggestion if we found relevant tools
      if (!sortedTools.isEmpty()) {
        var workflow = generateWorkflow(task, sortedTools.stream().map(Map.Entry::getKey).toList());
        result.add("suggested_workflow", workflow);
      }

      // Add anti-patterns for this task
      var antiPatterns = new JsonArray();
      if (task.contains("statistic") || task.contains("mean") || task.contains("average")) {
        antiPatterns.add("Don't compute statistics manually—use get_statistics");
      }
      if (task.contains("correlat")) {
        antiPatterns.add("Don't compute correlation manually—use time_correlate");
      }
      if (task.contains("score") || task.contains("points") || task.contains("win")) {
        antiPatterns.add("Don't guess match outcomes—use get_tba_match_data or check list_available_logs TBA data");
      }
      if (task.contains("auto") || task.contains("teleop")) {
        antiPatterns.add("Don't manually parse timestamps for match phases—use get_match_phases");
      }
      if (task.contains("brownout") || task.contains("battery") || task.contains("voltage")) {
        antiPatterns.add("Don't manually check voltage thresholds—use power_analysis");
      }
      if (antiPatterns.size() > 0) {
        result.add("anti_patterns", antiPatterns);
      }

      return result;
    }

    /**
     * Simple fuzzy matching: checks if words from the query appear in the target.
     */
    private boolean fuzzyMatch(String query, String target) {
      var queryWords = query.split("\\s+");
      int matches = 0;
      for (var word : queryWords) {
        if (word.length() > 3 && target.contains(word)) {
          matches++;
        }
      }
      return matches >= 2;
    }

    /**
     * Generate a workflow suggestion based on the task and suggested tools.
     */
    private JsonArray generateWorkflow(String task, List<ToolInfo> tools) {
      var workflow = new JsonArray();

      // Always start with loading if not already loaded
      boolean hasLoadTool = tools.stream().anyMatch(t -> t.name().equals("load_log"));
      boolean hasListTool = tools.stream().anyMatch(t -> t.name().equals("list_available_logs"));

      if (!hasLoadTool && !hasListTool) {
        workflow.add("1. list_available_logs - Find available logs (includes TBA match data)");
        workflow.add("2. load_log - Load the specific match you want to analyze");
      }

      // Add suggested tools in logical order
      int step = hasLoadTool || hasListTool ? 1 : 3;
      for (var tool : tools) {
        if (tool.name().equals("load_log") || tool.name().equals("list_available_logs")) {
          continue; // Already added
        }
        workflow.add(step + ". " + tool.name() + " - " + tool.briefDescription());
        step++;
      }

      return workflow;
    }
  }
}
