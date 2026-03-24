package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Comparator;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;
import org.triplehelix.wpilogmcp.tba.TbaClient;
import org.triplehelix.wpilogmcp.tba.TbaEnrichment;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * Core WPILOG tools for log discovery and data access.
 *
 * <p>Tools included:
 * <ul>
 *   <li>{@code list_available_logs} - Browse logs in the configured directory</li>
 *   <li>{@code list_entries} - List all entries in a log file</li>
 *   <li>{@code get_entry_info} - Get detailed info about a specific entry</li>
 *   <li>{@code read_entry} - Read values from an entry with pagination</li>
 *   <li>{@code list_loaded_logs} - Show cache status</li>
 *   <li>{@code list_struct_types} - List supported struct types</li>
 *   <li>{@code health_check} - Server status and diagnostics</li>
 *   <li>{@code get_game_info} - Year-specific FRC game data</li>
 * </ul>
 */
public final class CoreTools {

  private CoreTools() {}

  /**
   * Registers all core tools with the MCP server.
   */
  public static void registerAll(ToolRegistry registry) {
    registry.registerTool(new ListAvailableLogsTool());
    registry.registerTool(new ListEntriesTool());
    registry.registerTool(new GetEntryInfoTool());
    registry.registerTool(new ReadEntryTool());
    registry.registerTool(new ListLoadedLogsTool());
    registry.registerTool(new ListStructTypesTool());
    registry.registerTool(new HealthCheckTool());
    registry.registerTool(new GetGameInfoTool());
  }

  static class ListAvailableLogsTool implements Tool {
    @Override
    public String name() {
      return "list_available_logs";
    }

    @Override
    public String description() {
      return "List WPILOG files available in the configured log directory with friendly names. "
          + "IMPORTANT: When TBA is configured, this tool automatically enriches each log with "
          + "match data including alliance scores, win/loss results, and actual match times. "
          + "Check the 'tba' field in each log entry for match outcomes—don't guess from telemetry! "
          + "Use this tool first to find logs and get match results, then pass the path to other tools.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var logDir = LogDirectory.getInstance();

      if (!logDir.isConfigured()) {
        var result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", "Log directory not configured. Start server with -logdir /path/to/logs");
        result.addProperty("hint", "Configure via: -logdir argument or WPILOG_DIR environment variable");
        return result;
      }

      var logs = logDir.listAvailableLogs();
      var tbaClient = TbaClient.getInstance();
      var tbaEnrichment = TbaEnrichment.getInstance();
      boolean tbaAvailable = tbaClient.isAvailable();

      var logsArray = new JsonArray();
      for (var log : logs) {
        var logObj = new JsonObject();
        logObj.addProperty("friendly_name", log.friendlyName());
        logObj.addProperty("path", log.path());
        logObj.addProperty("filename", log.filename());
        if (log.eventName() != null) logObj.addProperty("event", log.eventName());
        if (log.matchType() != null) logObj.addProperty("match_type", log.matchType());
        if (log.matchNumber() != null) logObj.addProperty("match_number", log.matchNumber());
        if (log.teamNumber() != null) logObj.addProperty("team_number", log.teamNumber());
        logObj.addProperty("size_bytes", log.fileSize());
        logObj.addProperty("last_modified", log.lastModified());

        if (tbaAvailable && tbaEnrichment.isEligibleForEnrichment(log)) {
          var tbaData = tbaEnrichment.enrichLog(log);
          tbaData.ifPresent(data -> logObj.add("tba", data));
        }

        logsArray.add(logObj);
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("log_directory", logDir.getLogDirectory().toString());
      result.addProperty("log_count", logs.size());
      if (tbaAvailable) result.addProperty("tba_enrichment", true);

      var cacheStats = new JsonObject();
      for (var entry : logDir.getCacheStats().entrySet()) {
        cacheStats.addProperty(entry.getKey(), entry.getValue());
      }
      result.add("metadata_cache", cacheStats);
      result.add("logs", logsArray);
      return result;
    }
  }

  static class ListEntriesTool extends LogRequiringTool {
    @Override
    public String name() {
      return "list_entries";
    }

    @Override
    public String description() {
      return "List all entries in a log file. Returns log metadata (time range, duration, "
          + "truncation status) and entry list with types and sample counts. "
          + "Optionally filter by name pattern.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("pattern", "string", "Optional pattern to filter entry names (substring match)", false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var pattern = arguments.has("pattern") && !arguments.get("pattern").isJsonNull()
          ? arguments.get("pattern").getAsString() : null;

      var entriesArray = new JsonArray();
      var sortedEntries = log.entries().values().stream()
          .filter(e -> pattern == null || e.name().toLowerCase().contains(pattern.toLowerCase()))
          .sorted(Comparator.comparing(EntryInfo::name))
          .toList();

      for (var entry : sortedEntries) {
        var values = log.values().get(entry.name());
        var entryObj = new JsonObject();
        entryObj.addProperty("name", entry.name());
        entryObj.addProperty("type", entry.type());
        entryObj.addProperty("sample_count", values != null ? values.size() : 0);
        entriesArray.add(entryObj);
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("log_path", log.path());
      result.addProperty("entry_count", entriesArray.size());

      // Log metadata (replaces the discovery role of the removed load_log tool)
      var timeRange = new JsonObject();
      timeRange.addProperty("start", log.minTimestamp());
      timeRange.addProperty("end", log.maxTimestamp());
      timeRange.addProperty("duration", log.duration());
      result.add("time_range_sec", timeRange);

      if (log.truncated()) {
        result.addProperty("truncated", true);
        result.addProperty("warning", log.truncationMessage());
      }

      result.add("entries", entriesArray);
      return result;
    }
  }

  static class GetEntryInfoTool extends LogRequiringTool {
    @Override
    public String name() {
      return "get_entry_info";
    }

    @Override
    public String description() {
      return "Get detailed information about a specific entry including metadata and sample values.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "The entry name (e.g., '/Vision/Summary/ObservationScore')", true)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var name = getRequiredString(arguments, "name");
      var entry = log.entries().get(name);

      if (entry == null) {
        var suggestions = log.entries().keySet().stream()
            .filter(n -> n.contains(name))
            .limit(5)
            .toList();

        var result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", "Entry not found: " + name);
        if (!suggestions.isEmpty()) result.add("suggestions", GSON.toJsonTree(suggestions));
        return result;
      }

      var values = log.values().get(name);

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("name", entry.name());
      result.addProperty("type", entry.type());
      result.addProperty("metadata", entry.metadata());
      result.addProperty("sample_count", values != null ? values.size() : 0);

      if (values != null && !values.isEmpty()) {
        var timeRange = new JsonObject();
        timeRange.addProperty("start", values.get(0).timestamp());
        timeRange.addProperty("end", values.get(values.size() - 1).timestamp());
        result.add("time_range_sec", timeRange);

        var samples = new JsonArray();
        int[] indices = {0, values.size() / 2, values.size() - 1};
        for (int idx : indices) {
          if (idx >= 0 && idx < values.size()) {
            var tv = values.get(idx);
            var sample = new JsonObject();
            sample.addProperty("timestamp_sec", tv.timestamp());
            sample.add("value", GSON.toJsonTree(tv.value()));
            samples.add(sample);
          }
        }
        result.add("sample_values", samples);
      }

      return result;
    }
  }

  static class ReadEntryTool extends LogRequiringTool {
    @Override
    public String name() {
      return "read_entry";
    }

    @Override
    public String description() {
      return "Read values from an entry. Supports time range filtering and pagination.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "The entry name", true)
          .addNumberProperty("start_time", "Start timestamp in seconds (optional)", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds (optional)", false, null)
          .addIntegerProperty("limit", "Maximum number of samples to return", false, 100)
          .addIntegerProperty("offset", "Number of samples to skip", false, 0)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var name = getRequiredString(arguments, "name");
      var allValues = log.values().get(name);

      if (allValues == null) {
        throw new IllegalArgumentException("Entry not found: " + name);
      }

      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      int limit = getOptInt(arguments, "limit", 100);
      int offset = getOptInt(arguments, "offset", 0);

      if (limit <= 0) {
        throw new IllegalArgumentException("Parameter 'limit' must be positive, got " + limit);
      }
      if (offset < 0) {
        throw new IllegalArgumentException("Parameter 'offset' must be non-negative, got " + offset);
      }

      var filtered = allValues.stream()
          .filter(tv -> startTime == null || tv.timestamp() >= startTime)
          .filter(tv -> endTime == null || tv.timestamp() <= endTime)
          .toList();

      int totalInRange = filtered.size();
      var paged = filtered.stream().skip(offset).limit(limit).toList();

      var samples = new JsonArray();
      for (var tv : paged) {
        var sample = new JsonObject();
        sample.addProperty("timestamp_sec", tv.timestamp());
        sample.add("value", GSON.toJsonTree(tv.value()));
        samples.add(sample);
      }

      var entry = log.entries().get(name);

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("name", name);
      result.addProperty("type", entry != null ? entry.type() : "unknown");
      result.addProperty("total_in_range", totalInRange);
      result.addProperty("returned_count", paged.size());
      result.addProperty("offset", offset);
      result.addProperty("limit", limit);
      result.add("samples", samples);
      return result;
    }
  }

  static class ListLoadedLogsTool implements Tool {
    @Override
    public String name() {
      return "list_loaded_logs";
    }

    @Override
    public String description() {
      return "List all currently cached log files and cache status.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var logManager = getLogManager();

      var paths = logManager.getLoadedLogPaths();

      var logsArray = new JsonArray();
      for (var path : paths) {
        var logObj = new JsonObject();
        logObj.addProperty("path", path);
        logsArray.add(logObj);
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("loaded_count", paths.size());
      result.add("logs", logsArray);
      return result;
    }
  }

  static class ListStructTypesTool implements Tool {
    @Override
    public String name() {
      return "list_struct_types";
    }

    @Override
    public String description() {
      return "List all supported struct types for decoding.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var result = new JsonObject();
      result.addProperty("success", true);

      var geometry = new JsonArray();
      geometry.add("Pose2d");
      geometry.add("Pose3d");
      geometry.add("Translation2d");
      geometry.add("Translation3d");
      geometry.add("Rotation2d");
      geometry.add("Rotation3d");
      geometry.add("Transform2d");
      geometry.add("Transform3d");
      geometry.add("Twist2d");
      geometry.add("Twist3d");

      var kinematics = new JsonArray();
      kinematics.add("ChassisSpeeds");
      kinematics.add("SwerveModuleState");
      kinematics.add("SwerveModulePosition");

      var vision = new JsonArray();
      vision.add("TargetObservation");
      vision.add("PoseObservation");
      vision.add("SwerveSample");

      var structs = new JsonObject();
      structs.add("geometry", geometry);
      structs.add("kinematics", kinematics);
      structs.add("vision", vision);

      result.add("struct_types", structs);
      return result;
    }
  }

  static class HealthCheckTool implements Tool {
    @Override
    public String name() {
      return "health_check";
    }

    @Override
    public String description() {
      return "Verify server is working correctly and get system status.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("status", "OK");
      result.addProperty("server_version", org.triplehelix.wpilogmcp.Version.VERSION);

      var logManager = getLogManager();
      result.addProperty("loaded_logs", logManager.getLoadedLogPaths().size());

      // TBA availability
      var tbaConfig = org.triplehelix.wpilogmcp.tba.TbaConfig.getInstance();
      result.addProperty("tba_available", tbaConfig.isConfigured());

      // RevLog sync status
      result.addProperty("revlog_sync_in_progress", logManager.isAnyRevLogSyncInProgress());

      // JVM memory info
      var runtime = Runtime.getRuntime();
      var memory = new JsonObject();
      memory.addProperty("used_mb", (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L));
      memory.addProperty("total_mb", runtime.totalMemory() / (1024L * 1024L));
      memory.addProperty("max_mb", runtime.maxMemory() / (1024L * 1024L));
      memory.addProperty("free_mb", runtime.freeMemory() / (1024L * 1024L));
      result.add("jvm_memory", memory);

      // In-memory cache info
      result.addProperty("cache_memory_mb", logManager.getEstimatedMemoryUsageMb());

      // Disk cache info
      var diskCache = logManager.getDiskCache();
      var diskCacheInfo = new JsonObject();
      diskCacheInfo.addProperty("enabled", diskCache.isEnabled());
      try {
        var cacheDir = logManager.getCacheDirectory().getPath();
        diskCacheInfo.addProperty("directory", cacheDir.toString());
        if (java.nio.file.Files.isDirectory(cacheDir)) {
          long fileCount = 0;
          long totalBytes = 0;
          try (var stream = java.nio.file.Files.list(cacheDir)) {
            var files = stream.filter(f -> f.toString().endsWith(".msgpack")).toList();
            fileCount = files.size();
            for (var f : files) {
              totalBytes += java.nio.file.Files.size(f);
            }
          }
          diskCacheInfo.addProperty("cached_files", fileCount);
          diskCacheInfo.addProperty("total_size_mb", totalBytes / (1024L * 1024L));
        }
      } catch (Exception e) {
        diskCacheInfo.addProperty("error", e.getMessage());
      }
      diskCacheInfo.addProperty("format_version",
          org.triplehelix.wpilogmcp.cache.DiskCacheSerializer.CURRENT_FORMAT_VERSION);
      result.add("disk_cache", diskCacheInfo);

      return result;
    }
  }

  /**
   * Provides year-specific FRC game information for contextual log analysis.
   */
  static class GetGameInfoTool implements Tool {
    @Override
    public String name() {
      return "get_game_info";
    }

    @Override
    public String description() {
      return "Get year-specific FRC game information (match timing, scoring values, field geometry, "
          + "game pieces, and analysis hints). Use this to understand the context of a log file: "
          + "what the match phases are, what scoring actions look like, and what mechanisms to expect. "
          + "Defaults to the current season if no year is specified.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addIntegerProperty("season", "FRC season year (e.g., 2026). Defaults to current year.", false, null)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var kb = org.triplehelix.wpilogmcp.game.GameKnowledgeBase.getInstance();

      int season = arguments.has("season") && !arguments.get("season").isJsonNull()
          ? arguments.get("season").getAsInt()
          : java.time.Year.now().getValue();

      var game = kb.getGame(season);
      if (game == null) {
        var result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", "No game data available for season " + season);
        var available = kb.availableSeasons();
        if (available.length > 0) {
          var arr = new com.google.gson.JsonArray();
          for (int s : available) arr.add(s);
          result.add("available_seasons", arr);
        }
        return result;
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("season", game.season());
      result.addProperty("game_name", game.gameName());
      result.add("match_timing", game.raw().getAsJsonObject("match_timing"));
      result.add("scoring", game.scoring());
      result.add("field_geometry", game.raw().getAsJsonObject("field_geometry"));
      result.add("game_pieces", game.gamePieces());
      if (game.analysisHints() != null) {
        result.add("analysis_hints", game.analysisHints());
      }
      if (game.raw().has("typical_mechanisms")) {
        result.add("typical_mechanisms", game.raw().getAsJsonArray("typical_mechanisms"));
      }
      if (game.raw().has("hub_mechanics")) {
        result.add("hub_mechanics", game.raw().getAsJsonObject("hub_mechanics"));
      }
      return result;
    }
  }
}
