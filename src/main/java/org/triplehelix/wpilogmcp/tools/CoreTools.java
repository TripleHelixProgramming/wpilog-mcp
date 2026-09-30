/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Comparator;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;

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
 *   <li>{@code list_struct_types} - List a log's struct types and their schemas</li>
 *   <li>{@code health_check} - Server status and diagnostics</li>
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
    // GetGameInfoTool is registered in FrcDomainTools to match the discovery catalog category
  }

  static class ListAvailableLogsTool extends ToolBase {
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
    protected JsonElement executeInternal(JsonObject arguments) throws Exception {
      if (!logDirectory.isConfigured()) {
        var result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", "Log directory not configured. Start server with -logdir /path/to/logs");
        result.addProperty("hint", "Configure via: -logdir argument or WPILOG_DIR environment variable");
        return result;
      }

      var logs = logDirectory.listAvailableLogs();
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
      result.addProperty("log_directory", logDirectory.getLogDirectory().toString());
      result.addProperty("log_count", logs.size());
      if (tbaAvailable) result.addProperty("tba_enrichment", true);

      var cacheStats = new JsonObject();
      for (var entry : logDirectory.getCacheStats().entrySet()) {
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
        var entryObj = new JsonObject();
        entryObj.addProperty("name", entry.name());
        entryObj.addProperty("type", entry.type());
        entryObj.addProperty("sample_count", log.sampleCount(entry.name()));
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
    /** Longest array or list shown whole in a sample; longer ones are cut, and say so. */
    static final int SAMPLE_ELEMENTS = 20;

    @Override
    public String name() {
      return "get_entry_info";
    }

    @Override
    public String description() {
      return "Describe one entry: type, metadata, sample count, time range, and three "
          + "representative samples (first, middle, last among non-empty values; "
          + "non_empty_sample_count says how many values are not empty arrays or strings). "
          + "For a struct entry: its schema, where the schema came from (logged by this log, "
          + "WPILib's, or an assumed template), fields, and numeric_leaf_paths, the numeric "
          + "fields that other tools can address as entry + path (e.g. "
          + "/Vision/Camera0/PoseObservations[*].tagCount). decode_problem reports records that "
          + "could not be decoded and why.";
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

      var decoded = log.values().get(name);
      var values = decoded != null ? decoded
          : java.util.List.<org.triplehelix.wpilogmcp.log.TimestampedValue>of();

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("name", entry.name());
      result.addProperty("type", entry.type());
      result.addProperty("metadata", entry.metadata());
      result.addProperty("sample_count", values.size());
      log.decodeProblem(name).ifPresent(problem -> {
        var o = AccessTrackingLogData.toJson(name, problem);
        o.remove("entry");
        result.add("decode_problem", o);
      });

      var structName = org.triplehelix.wpilogmcp.log.struct.StructSchemas.structName(entry.type());
      if (structName != null) {
        var schemas = log.structSchemas();
        boolean isArray = org.triplehelix.wpilogmcp.log.struct.StructSchemas.isArrayType(
            entry.type());
        var struct = schemas.info(structName)
            .map(info -> StructDescriptions.describe(schemas, info))
            .orElseGet(() -> StructDescriptions.missing(structName));
        struct.addProperty("is_array", isArray);
        if (struct.has("numeric_leaf_paths")) {
          // Paths relative to the entry: an array's elements are selected with [i] or [*]
          var paths = new JsonArray();
          for (var p : struct.remove("numeric_leaf_paths").getAsJsonArray()) {
            paths.add((isArray ? "[*]." : ".") + p.getAsString());
          }
          result.add("numeric_leaf_paths", paths);
        }
        result.add("struct", struct);
      } else if (entry.type().equals("double[]") || entry.type().equals("float[]")
          || entry.type().equals("int64[]") || entry.type().equals("boolean[]")) {
        var paths = new JsonArray();
        paths.add("[*]");
        result.add("numeric_leaf_paths", paths);
      }

      if (!values.isEmpty()) {
        var timeRange = new JsonObject();
        timeRange.addProperty("start", values.get(0).timestamp());
        timeRange.addProperty("end", values.get(values.size() - 1).timestamp());
        result.add("time_range_sec", timeRange);

        // Representative samples: first, middle, and last among the non-empty values
        var nonEmpty = new java.util.ArrayList<Integer>();
        for (int i = 0; i < values.size(); i++) {
          if (!isEmptyValue(values.get(i).value())) nonEmpty.add(i);
        }
        if (canBeEmpty(entry.type())) result.addProperty("non_empty_sample_count", nonEmpty.size());
        var pool = nonEmpty.isEmpty()
            ? java.util.stream.IntStream.range(0, values.size()).boxed().toList() : nonEmpty;
        var samples = new JsonArray();
        java.util.stream.IntStream.of(0, pool.size() / 2, pool.size() - 1).distinct()
            .forEach(k -> samples.add(sample(values.get(pool.get(k)))));
        result.add("sample_values", samples);
      }

      return result;
    }

    static boolean canBeEmpty(String type) {
      return type.endsWith("[]") || type.startsWith("structarray:") || type.equals("string")
          || type.equals("json") || type.equals("raw");
    }

    static boolean isEmptyValue(Object value) {
      if (value instanceof java.util.List<?> list) return list.isEmpty();
      if (value instanceof String str) return str.isEmpty();
      if (value != null && value.getClass().isArray()) {
        return java.lang.reflect.Array.getLength(value) == 0;
      }
      return false;
    }

    /** One sample; a long array is cut to its first elements, with its full length. */
    static JsonObject sample(org.triplehelix.wpilogmcp.log.TimestampedValue tv) {
      var sample = new JsonObject();
      sample.addProperty("timestamp_sec", tv.timestamp());
      var json = GSON.toJsonTree(tv.value());
      if (json.isJsonArray() && json.getAsJsonArray().size() > SAMPLE_ELEMENTS) {
        var full = json.getAsJsonArray();
        var cut = new JsonArray();
        for (int i = 0; i < SAMPLE_ELEMENTS; i++) cut.add(full.get(i));
        sample.add("value", cut);
        sample.addProperty("value_length", full.size());
        sample.addProperty("value_truncated", true);
      } else {
        sample.add("value", json);
      }
      return sample;
    }
  }

  static class ReadEntryTool extends LogRequiringTool {
    @Override
    public String name() {
      return "read_entry";
    }

    @Override
    public String description() {
      return "Read values from an entry, in time order, with optional time range and paging: "
          + "total_in_range is the true count, offset/limit select a page, has_more says whether "
          + "another page exists, and limits.samples gives total (after offset) and returned. "
          + "Struct values are decoded by the log's own schema: nested objects with the schema's "
          + "field names, enum fields as {value, label}, rotations with a _derived block "
          + "(degrees; roll, pitch, yaw). Records that could not be decoded are reported in "
          + "warnings. One page is not the whole signal: use get_statistics, find_condition, or "
          + "find_peaks for claims about a window.";
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
      var allValues = requireEntry(log, name); // not found: error with suggestions
      var problem = log.decodeProblem(name);
      if (allValues.isEmpty() && problem.isPresent()) {
        throw new IllegalArgumentException("Entry " + name + " has " + problem.get().totalRecords()
            + " records but none could be decoded: " + problem.get().message());
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
      result.addProperty("has_more", offset + paged.size() < totalInRange);
      ResultContract.addLimitedList(result, "samples", samples, Math.max(0, totalInRange - offset),
          limit);
      return result;
    }
  }

  static class ListLoadedLogsTool extends ToolBase {
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
    protected JsonElement executeInternal(JsonObject arguments) throws Exception {
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

  static class ListStructTypesTool extends ToolBase {
    /** Entries listed per struct type; the count is always complete. */
    static final int ENTRY_LIMIT = 20;

    @Override
    public String name() {
      return "list_struct_types";
    }

    @Override
    public String description() {
      return "List struct types and how they decode. Struct values are decoded from each log's "
          + "own schemas (/.schema/struct:<Name> entries), so any struct the log records a "
          + "schema for decodes, including a team's own. With path: every struct type the log "
          + "records or uses, with source (logged; wpilib or assumed when the log has no schema "
          + "for it: assumed layouts may not match the team's struct), size, schema, fields, "
          + "numeric leaf paths, and the entries that use it. Without path: the fallback "
          + "schemas used when a log records none.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("path", "string", "Path to the log file (from list_available_logs); "
              + "omit to list only the fallback schemas", false)
          .build();
    }

    @Override
    protected JsonElement executeInternal(JsonObject arguments) throws Exception {
      var path = getOptString(arguments, "path", null);
      var result = new JsonObject();
      result.addProperty("success", true);
      if (path == null) {
        var schemas = org.triplehelix.wpilogmcp.log.struct.StructSchemas.fallbackOnly();
        var types = new JsonArray();
        for (var name : schemas.allStructs()) {
          schemas.info(name).ifPresent(info -> types.add(StructDescriptions.describe(schemas, info)));
        }
        result.addProperty("note", "Structs are decoded from each log's own schemas; these are "
            + "used only for struct types a log records no schema for. Pass path to see a log's "
            + "struct types.");
        result.add("struct_types", types);
        return result;
      }

      var log = logManager.getOrLoad(path);
      var schemas = log.structSchemas();
      // Declaration (entry id) order, whatever order the entry map iterates in
      var usedBy = new java.util.LinkedHashMap<String, java.util.List<String>>();
      var byId = log.entries().values().stream()
          .sorted(Comparator.comparingInt(EntryInfo::id)).toList();
      for (var entry : byId) {
        var struct = org.triplehelix.wpilogmcp.log.struct.StructSchemas.structName(entry.type());
        if (struct != null) usedBy.computeIfAbsent(struct, k -> new java.util.ArrayList<>())
            .add(entry.name());
      }
      var names = new java.util.LinkedHashSet<>(schemas.loggedStructs());
      names.addAll(usedBy.keySet());

      var types = new JsonArray();
      var warnings = new JsonArray();
      for (var name : names) {
        var info = schemas.info(name);
        var o = info.map(i -> StructDescriptions.describe(schemas, i))
            .orElseGet(() -> StructDescriptions.missing(name));
        var entries = usedBy.getOrDefault(name, java.util.List.of());
        var listed = new JsonArray();
        entries.stream().limit(ENTRY_LIMIT).forEach(listed::add);
        o.addProperty("entry_count", entries.size());
        ResultContract.addLimitedList(o, "entries", listed, entries.size(), ENTRY_LIMIT);
        types.add(o);
        if (entries.isEmpty()) continue;
        if (info.isEmpty() || !info.get().valid()) {
          warnings.add(entries.size() + " entries of struct " + name + " cannot be decoded: "
              + o.get("error").getAsString());
        } else if (info.get().source()
            == org.triplehelix.wpilogmcp.log.struct.StructSchemas.Source.ASSUMED) {
          warnings.add(entries.size() + " entries of struct " + name + " are decoded by an "
              + "assumed template layout; the log records no schema for it.");
        }
      }
      result.addProperty("log_path", log.path());
      result.addProperty("struct_type_count", types.size());
      result.add("struct_types", types);
      if (!warnings.isEmpty()) result.add("warnings", warnings);
      return result;
    }
  }

  static class HealthCheckTool extends ToolBase {
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
    protected JsonElement executeInternal(JsonObject arguments) throws Exception {
      var result = new JsonObject();
      result.addProperty("success", true);
      // "status" is the result contract's field; it reads "ok" for a healthy server
      result.addProperty("server_version", org.triplehelix.wpilogmcp.Version.VERSION);

      result.addProperty("loaded_logs", logManager.getLoadedLogPaths().size());

      // TBA availability
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

      // JVM heap usage (includes caches, tool execution, and all other allocations)
      result.addProperty("jvm_heap_used_mb", logManager.getEstimatedMemoryUsageMb());

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

}
