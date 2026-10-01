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
import org.triplehelix.wpilogmcp.log.LogManager;
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
    registry.registerTool(new ResolveSignalsTool());
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
      return "List WPILOG files available in the configured log directory with friendly names, "
          + "newest first, paged: log_count is the number matching the filters, offset/limit "
          + "select a page (default 50), has_more says whether another page exists. Filters: "
          + "name (substring of the file or friendly name), event (event code, e.g. VACHE), "
          + "match_type (qm, sf, f, p, ...), since (a date like 2026-03-20: logs from then on). "
          + "IMPORTANT: When TBA is configured, this tool automatically enriches each listed log "
          + "with match data including alliance scores, win/loss results, and actual match "
          + "times. Check the 'tba' field in each log entry for match outcomes—don't guess from "
          + "telemetry! tba_enrichment.available says whether The Blue Alliance answered for "
          + "this page; when false, its reason (not configured, an outage, a rejected key) is why "
          + "no log carries a tba field. A tba field's match_key and lookup_method say which TBA "
          + "match it came from: an 'Elimination N' log is read as double-elimination bracket "
          + "match N (sfNm1) since 2023, and a finals log by the log's time (nearest_time). Use "
          + "this tool first to find logs and get match results, then pass the path to other "
          + "tools.";
    }

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Only logs whose file or friendly name contains this "
              + "(case-insensitive)", false)
          .addProperty("event", "string", "Only logs from this event code (case-insensitive)",
              false)
          .addProperty("match_type", "string", "Only this match type (qm, sf, f, p, e, ...)",
              false)
          .addProperty("since", "string", "Only logs from this date on: 2026-03-20, or an "
              + "ISO-8601 instant", false)
          .addIntegerProperty("offset", "Logs to skip", false, 0)
          .addIntegerProperty("limit", "Maximum logs to return (max 500)", false, DEFAULT_LIMIT)
          .build();
    }

    /** Milliseconds since the epoch for a date (start of day, UTC) or an ISO-8601 instant. */
    static long parseSince(String since) {
      try {
        return java.time.LocalDate.parse(since).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
            .toEpochMilli();
      } catch (java.time.format.DateTimeParseException e) {
        try {
          return java.time.Instant.parse(since).toEpochMilli();
        } catch (java.time.format.DateTimeParseException e2) {
          throw new IllegalArgumentException("since must be a date like 2026-03-20 or an "
              + "ISO-8601 instant like 2026-03-20T14:00:00Z, got '" + since + "'");
        }
      }
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

      var all = logDirectory.listAvailableLogs();
      var nameFilter = getOptString(arguments, "name", null);
      var eventFilter = getOptString(arguments, "event", null);
      var matchTypeArg = getOptString(arguments, "match_type", null);
      org.triplehelix.wpilogmcp.log.LogDirectory.MatchType matchTypeFilter = null;
      if (matchTypeArg != null) {
        var code = matchTypeArg.strip().toLowerCase();
        // qm/pm/em are the filename codes for q/p/e
        if (code.length() == 2 && code.endsWith("m")) code = code.substring(0, 1);
        matchTypeFilter = org.triplehelix.wpilogmcp.log.LogDirectory.MatchType.fromString(code);
        if (matchTypeFilter == null) {
          throw new IllegalArgumentException("Unknown match_type '" + matchTypeArg + "': use p, "
              + "q (or qm), qf, sf, f, or e");
        }
      }
      var wantedType = matchTypeFilter;
      var sinceArg = getOptString(arguments, "since", null);
      Long since = sinceArg != null ? parseSince(sinceArg) : null;
      int offset = Math.max(0, getOptInt(arguments, "offset", 0));
      int limit = Math.min(MAX_LIMIT, Math.max(1, getOptInt(arguments, "limit", DEFAULT_LIMIT)));
      var logs = all.stream()
          .filter(l -> nameFilter == null
              || l.filename().toLowerCase().contains(nameFilter.toLowerCase())
              || l.friendlyName().toLowerCase().contains(nameFilter.toLowerCase()))
          .filter(l -> eventFilter == null || (l.eventName() != null
              && l.eventName().equalsIgnoreCase(eventFilter)))
          .filter(l -> wantedType == null || (l.matchType() != null
              && org.triplehelix.wpilogmcp.log.LogDirectory.MatchType.fromString(l.matchType())
                  == wantedType))
          .filter(l -> since == null || (l.getBestTimestamp() != null
              && l.getBestTimestamp() >= since))
          .toList();
      var page = logs.subList(Math.min(offset, logs.size()),
          Math.min(logs.size(), Math.min(offset, logs.size()) + limit));
      var tbaEnrichment = TbaEnrichment.getInstance();
      boolean tbaAvailable = tbaClient.isAvailable();
      String tbaFailure = null; // the first outage or rejected key ends the page's enrichment

      var logsArray = new JsonArray();
      for (var log : page) {
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

        if (tbaAvailable && tbaFailure == null && tbaEnrichment.isEligibleForEnrichment(log)) {
          try {
            tbaEnrichment.enrichLogOrThrow(log).ifPresent(data -> logObj.add("tba", data));
          } catch (org.triplehelix.wpilogmcp.tba.TbaUnavailableException e) {
            // An outage is not "no data": said once, at the top level
            tbaFailure = e.getMessage();
          }
        }

        logsArray.add(logObj);
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("log_directory", logDirectory.getLogDirectory().toString());
      result.addProperty("log_count", logs.size());
      result.addProperty("total_logs", all.size());
      result.addProperty("offset", Math.min(offset, logs.size()));
      result.addProperty("returned", page.size());
      result.addProperty("has_more", Math.min(offset, logs.size()) + page.size() < logs.size());
      // TBA's availability as an object: an outage or a rejected key is not "no match data"
      var tbaStatus = new JsonObject();
      if (!tbaAvailable) {
        tbaStatus.addProperty("available", false);
        tbaStatus.addProperty("reason", "not configured (set TBA_API_KEY, -tba-key, or tba_key)");
      } else if (tbaFailure != null) {
        tbaStatus.addProperty("available", false);
        tbaStatus.addProperty("reason", tbaFailure + " The logs on this page carry no tba field "
            + "for that reason, not because The Blue Alliance has no data for them.");
      } else {
        tbaStatus.addProperty("available", true);
      }
      result.add("tba_enrichment", tbaStatus);

      var cacheStats = new JsonObject();
      for (var entry : logDirectory.getCacheStats().entrySet()) {
        cacheStats.addProperty(entry.getKey(), entry.getValue());
      }
      result.add("metadata_cache", cacheStats);
      ResultContract.addLimitedList(result, "logs", logsArray,
          Math.max(0, logs.size() - Math.min(offset, logs.size())), limit);
      if (logs.isEmpty() && !all.isEmpty()) {
        result.addProperty("status", "no_match");
        result.addProperty("reason", "No log matches the filters (" + all.size()
            + " logs in the directory).");
      }
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
          + "Optionally filter by name pattern; no_match, naming the pattern, when it matches "
          + "no entry. Struct and array entries hold numeric fields that numeric tools address "
          + "by path (see get_entry_info).";
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

      // Log metadata (replaces the discovery role of the removed load_log tool)
      var timeRange = new JsonObject();
      timeRange.addProperty("start", log.minTimestamp());
      timeRange.addProperty("end", log.maxTimestamp());
      timeRange.addProperty("duration", log.duration());

      if (sortedEntries.isEmpty()) {
        // An empty list is not a listing: say what was searched
        return ResponseBuilder.noMatch(log.entries().isEmpty() ? "The log has no entries."
                : "No entry name contains '" + pattern + "' (" + log.entries().size()
                    + " entries in the log).")
            .hint("list_entries without pattern lists every entry; search_entries filters by "
                + "type and sample count too.")
            .addProperty("log_path", log.path())
            .addProperty("entry_count", 0)
            .addData("time_range_sec", timeRange)
            .build();
      }

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("log_path", log.path());
      result.addProperty("entry_count", entriesArray.size());
      result.add("time_range_sec", timeRange);

      if (log.truncated()) {
        result.addProperty("truncated", true);
        result.addProperty("warning", log.truncationMessage());
      }

      if (sortedEntries.stream().anyMatch(e -> e.type().startsWith("struct")
          || e.type().endsWith("[]"))) {
        result.addProperty("note", "Numeric tools read struct fields and array elements appended "
            + "to the entry name (e.g. /RealOutputs/Drive/Pose.translation.x, "
            + "/PowerDistribution/ChannelCurrent[3]); get_entry_info lists an entry's "
            + "numeric_leaf_paths.");
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
        var lower = name.toLowerCase(java.util.Locale.ROOT);
        var suggestions = log.entries().keySet().stream()
            .filter(n -> n.toLowerCase(java.util.Locale.ROOT).contains(lower))
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
      return type.endsWith("[]") || type.equals("string")
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
    /** Most samples one page returns (struct samples can be large). */
    static final int MAX_SAMPLES = 10_000;

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
          .addIntegerProperty("limit", "Maximum number of samples to return (default: 100, "
              + "max: " + ReadEntryTool.MAX_SAMPLES + ")", false, 100)
          .addIntegerProperty("offset", "Number of samples to skip", false, 0)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      validateTimeRange(getOptDouble(arguments, "start_time"),
          getOptDouble(arguments, "end_time"));
      var name = getRequiredString(arguments, "name");
      var allValues = requireEntry(log, name); // not found: error with suggestions
      var problem = log.decodeProblem(name);
      if (allValues.isEmpty() && problem.isPresent()) {
        throw new IllegalArgumentException("Entry " + name + " has " + problem.get().totalRecords()
            + " records but none could be decoded: " + problem.get().message());
      }

      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      int limit = Math.min(MAX_SAMPLES, getOptInt(arguments, "limit", 100));
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

  static class ResolveSignalsTool extends LogRequiringTool {
    static final int MAX_CANDIDATES = 10;

    @Override
    public String name() {
      return "resolve_signals";
    }

    @Override
    public String description() {
      return "Show which entry plays each role in this log: robot_enabled, autonomous, "
          + "test_mode, fms_attached, battery_voltage, total_current, brownout_flag, "
          + "brownout_threshold, loop_time_full, loop_time_user, robot_pose, vision_pose, auto_chooser, "
          + "path_setpoint, path_actual, module_states_measured, module_states_setpoint, "
          + "chassis_speeds_measured, chassis_speeds_setpoint, gyro_yaw, "
          + "vision_pose_observations, vision_targets, can_bus, console_text, alerts. For each: "
          + "the entry (or entries, or a value); match, how it was chosen (explicit, convention: "
          + "a well-known AdvantageKit/WPILib/CTRE/PathPlanner name, type: the only entry of its "
          + "type or schema, heuristic, or none); the basis; the other candidates best first; "
          + "ambiguous when another candidate ranked as well (the one declared first wins); and "
          + "the tools that use it. The server does not guess: a heuristic role has no entry, "
          + "needs_confirmation, and candidates that match by name only, and the tools skip it. "
          + "Establish which candidate is right (get_entry_info, read_entry, or ask the user) "
          + "and pass it with the tool's parameter (voltage_entry, entry, pose_entry, "
          + "chooser_entry, ...). needs_confirmation lists those roles. These are the choices "
          + "the tools make; each tool's result records the entries it used under "
          + "inputs.entries.";
    }

    @Override
    protected JsonObject toolSchema() {
      var roleItem = new JsonObject();
      roleItem.addProperty("type", "string");
      return new SchemaBuilder()
          .addArrayProperty("roles", "Only these roles (default: all)", roleItem, false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var roles = new java.util.ArrayList<SignalResolver.Role>();
      if (arguments.has("roles") && arguments.get("roles").isJsonArray()) {
        for (var r : arguments.getAsJsonArray("roles")) {
          roles.add(SignalResolver.Role.fromWire(r.getAsString()));
        }
      } else {
        roles.addAll(java.util.List.of(SignalResolver.Role.values()));
      }
      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("log_path", log.path());
      var rolesJson = new JsonObject();
      var unresolved = new JsonArray();
      var confirm = new JsonArray();
      var warnings = new JsonArray();
      for (var role : roles) {
        var resolution = SignalResolver.resolve(log, role);
        rolesJson.add(role.wire(), resolution.toJson(MAX_CANDIDATES));
        if (resolution.entries().isEmpty() && resolution.value() == null) {
          unresolved.add(role.wire());
        }
        if (resolution.needsConfirmation()) confirm.add(role.wire());
        if (resolution.ambiguous()) {
          warnings.add(role.wire() + ": " + (resolution.entries().isEmpty() ? "nothing"
              : resolution.entries().get(0)) + " was chosen, but "
              + "another candidate ranked as well (" + String.join(", ",
                  resolution.candidates().stream().limit(4).toList()) + "); pass the right one "
              + "explicitly if it is not.");
        }
      }
      result.add("roles", rolesJson);
      result.add("unresolved", unresolved);
      if (!confirm.isEmpty()) result.add("needs_confirmation", confirm);
      if (!warnings.isEmpty()) result.add("warnings", warnings);
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
      return "List the log files currently loaded in the server's cache (path, entry count, "
          + "duration) and the cache status: how many are loaded and the JVM heap they share "
          + "(logs are evicted when idle or when the heap runs short). Logs load on demand, so "
          + "an empty list is normal.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    protected JsonElement executeInternal(JsonObject arguments) throws Exception {
      var loaded = logManager.listLoadedLogs().stream()
          .sorted(Comparator.comparing(LogManager.LoadedLogInfo::path)).toList();

      var logsArray = new JsonArray();
      for (var info : loaded) {
        var logObj = new JsonObject();
        logObj.addProperty("path", info.path());
        logObj.addProperty("entry_count", info.entryCount());
        logObj.addProperty("duration_sec", info.duration());
        logsArray.add(logObj);
      }

      var runtime = Runtime.getRuntime();
      var cache = new JsonObject();
      cache.addProperty("loaded_count", loaded.size());
      cache.addProperty("heap_used_mb",
          (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L));
      cache.addProperty("heap_max_mb", runtime.maxMemory() / (1024L * 1024L));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("loaded_count", loaded.size());
      result.add("logs", logsArray);
      result.add("cache", cache);
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
      if (names.isEmpty()) {
        // An empty list is not a listing: the log has no struct types
        return ResponseBuilder.noMatch("The log declares no struct types: no entry has a "
                + "struct:<Name> type and no /.schema/struct: schema is logged.")
            .hint("list_entries shows the types the log has; list_struct_types without path "
                + "lists the built-in WPILib struct layouts.")
            .addProperty("log_path", log.path())
            .build();
      }

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
      var inputs = new JsonObject();
      inputs.addProperty("log", log.path());
      result.add("inputs", inputs);
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

      // Disk caches, both in one directory. The revlog sync cache (SyncDiskCache) is in use.
      // The parsed-log cache (DiskCache) has not been on the load path since 0.8.0 — logs are
      // parsed lazily from memory-mapped files — but it is still configured and its directory
      // cleaned at startup, so its state is reported under a label that says so.
      var syncCache = new JsonObject();
      syncCache.addProperty("enabled", logManager.getSyncDiskCache().isEnabled());
      var parsedLogCache = new JsonObject();
      parsedLogCache.addProperty("used_by_load_path", false);
      parsedLogCache.addProperty("enabled", logManager.getDiskCache().isEnabled());
      try {
        var cacheDir = logManager.getCacheDirectory().getPath();
        syncCache.addProperty("directory", cacheDir.toString());
        parsedLogCache.addProperty("directory", cacheDir.toString());
        if (java.nio.file.Files.isDirectory(cacheDir)) {
          java.util.Map<Boolean, java.util.List<java.nio.file.Path>> bySyncCache;
          try (var stream = java.nio.file.Files.list(cacheDir)) {
            bySyncCache = stream.filter(f -> f.toString().endsWith(".msgpack"))
                .collect(java.util.stream.Collectors.partitioningBy(
                    f -> f.getFileName().toString().endsWith("-sync.msgpack")));
          }
          addCacheFileStats(syncCache, bySyncCache.get(true));
          addCacheFileStats(parsedLogCache, bySyncCache.get(false));
        }
      } catch (Exception e) {
        syncCache.addProperty("error", e.getMessage());
        parsedLogCache.addProperty("error", e.getMessage());
      }
      parsedLogCache.addProperty("format_version",
          org.triplehelix.wpilogmcp.cache.DiskCacheSerializer.CURRENT_FORMAT_VERSION);
      result.add("sync_disk_cache", syncCache);
      result.add("parsed_log_disk_cache", parsedLogCache);

      return result;
    }

    /** Adds a cache's file count and total size in MB. */
    private static void addCacheFileStats(JsonObject cache,
        java.util.List<java.nio.file.Path> files) throws java.io.IOException {
      long totalBytes = 0;
      for (var f : files) {
        totalBytes += java.nio.file.Files.size(f);
      }
      cache.addProperty("cached_files", files.size());
      cache.addProperty("total_size_mb", totalBytes / (1024L * 1024L));
    }
  }

}
