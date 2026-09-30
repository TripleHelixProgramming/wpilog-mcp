/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * Query and search tools for WPILOG analysis.
 *
 * <p>Tools included:
 * <ul>
 *   <li>{@code search_entries} - Search entries by type, name, or sample count</li>
 *   <li>{@code get_types} - List all data types in the log</li>
 *   <li>{@code find_condition} - Find when values cross thresholds</li>
 *   <li>{@code search_strings} - Search string entries for patterns</li>
 * </ul>
 */
public final class QueryTools {

  private QueryTools() {}

  /**
   * Registers all query tools with the MCP server.
   */
  public static void registerAll(ToolRegistry registry) {
    registry.registerTool(new SearchEntriesTool());
    registry.registerTool(new GetTypesTool());
    registry.registerTool(new FindConditionTool());
    registry.registerTool(new SearchStringsTool());
  }

  static class SearchEntriesTool extends LogRequiringTool {
    @Override
    public String name() {
      return "search_entries";
    }

    @Override
    public String description() {
      return "Search for entries by type (substring of the type, e.g. 'Pose3d' or 'double'), "
          + "name (case-insensitive substring), and minimum sample count. Returns the matching "
          + "entry names in name order, or no_match with the criteria when none match.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty(
              "type", "string", "Filter by type (substring match, e.g., 'Pose3d')", false)
          .addProperty("pattern", "string", "Filter by name containing this string", false)
          .addIntegerProperty("min_samples", "Minimum number of samples required", false, null)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var typeFilter =
          arguments.has("type") && !arguments.get("type").isJsonNull() ? arguments.get("type").getAsString() : null;
      var nameContains =
          arguments.has("pattern") && !arguments.get("pattern").isJsonNull() ? arguments.get("pattern").getAsString() : null;
      var minSamples =
          arguments.has("min_samples") && !arguments.get("min_samples").isJsonNull() ? arguments.get("min_samples").getAsInt() : null;

      var matches = new ArrayList<String>();

      for (var entry : log.entries().values()) {
        if (typeFilter != null && !entry.type().contains(typeFilter)) continue;
        if (nameContains != null && !entry.name().toLowerCase().contains(nameContains.toLowerCase())) continue;

        if (minSamples != null && log.sampleCount(entry.name()) < minSamples) continue;

        matches.add(entry.name());
      }

      matches.sort(String::compareTo);
      if (matches.isEmpty()) {
        var criteria = new ArrayList<String>();
        if (typeFilter != null) criteria.add("type containing '" + typeFilter + "'");
        if (nameContains != null) criteria.add("name containing '" + nameContains + "'");
        if (minSamples != null) criteria.add("at least " + minSamples + " samples");
        return ResponseBuilder.noMatch(log.entries().isEmpty() ? "The log has no entries."
                : "No entry matches " + (criteria.isEmpty() ? "the search"
                    : String.join(" and ", criteria)) + ".")
            .hint("list_entries shows every entry with its type; get_types groups them by type.")
            .build();
      }

      var matchesArray = new JsonArray();
      matches.forEach(matchesArray::add);

      return success()
          .addProperty("match_count", matches.size())
          .addData("matches", matchesArray)
          .build();
    }
  }

  static class GetTypesTool extends LogRequiringTool {
    @Override
    public String name() {
      return "get_types";
    }

    @Override
    public String description() {
      return "Get all data types used in the log file and which entries use each type.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var byType = log.entries().values().stream()
          .collect(Collectors.groupingBy(
              EntryInfo::type,
              Collectors.mapping(EntryInfo::name, Collectors.toList())));

      if (byType.isEmpty()) {
        return ResponseBuilder.noMatch("The log has no entries.").build();
      }

      var typesArray = new JsonArray();
      byType.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(entry -> {
            var typeObj = new JsonObject();
            typeObj.addProperty("type", entry.getKey());
            typeObj.addProperty("entry_count", entry.getValue().size());
            typeObj.add("entries", GSON.toJsonTree(entry.getValue().stream().sorted().toList()));
            typesArray.add(typeObj);
          });

      return success()
          .addProperty("type_count", byType.size())
          .addData("types", typesArray)
          .build();
    }
  }

  static class FindConditionTool extends LogRequiringTool {
    private static final java.util.Set<String> OPERATORS = java.util.Set.of("lt", "<", "lte",
        "<=", "gt", ">", "gte", ">=", "eq", "==", "ne", "!=", "abs_lt", "abs_lte", "abs_gt",
        "abs_gte");

    @Override
    public String name() {
      return "find_condition";
    }

    @Override
    public String description() {
      return "Find when a numeric or boolean entry satisfies a condition (value <op> threshold; "
          + "booleans read as 1/0), or when several do at once: conditions {all: [...]} or "
          + "{any: [...]} of {name, field, operator, threshold} (e.g. disabled AND stationary: "
          + "/DriverStation/Enabled eq 0 with a chassis speed abs_lt 0.05). Each value holds until "
          + "the entry's next sample, so entries logged only on change combine correctly. "
          + "Operators: lt, lte, gt, gte, eq, ne, and abs_lt/abs_lte/abs_gt/abs_gte on the "
          + "absolute value. Returns transitions (each time the condition becomes true) and "
          + "intervals (start, end, duration; an interval still true at the end of a window ends "
          + "with end_reason window_end), plus total_true_sec and fraction_of_window. "
          + "transition_count and interval_count are true totals; lists are cut at limit, with "
          + "limits giving total and returned. Useful for questions like 'When did battery "
          + "voltage drop below 11V, and for how long?'" + NumericSignal.PATH_HELP
          + " Thresholds on an angle apply to the value as logged (not unwrapped). scope and "
          + "windows restrict the time (each window is searched on its own; window_sec is the "
          + "time searched once every entry has a value), and the intervals returned can be "
          + "passed as windows to the statistics tools.";
    }

    @Override
    protected JsonObject toolSchema() {
      var conditionItem = new JsonObject();
      conditionItem.addProperty("type", "object");
      var itemProperties = new JsonObject();
      for (var key : List.of("name", "field", "operator")) {
        var p = new JsonObject();
        p.addProperty("type", "string");
        itemProperties.add(key, p);
      }
      var thresholdProperty = new JsonObject();
      thresholdProperty.addProperty("type", "number");
      itemProperties.add("threshold", thresholdProperty);
      conditionItem.add("properties", itemProperties);
      var conditions = new JsonObject();
      conditions.addProperty("type", "object");
      conditions.addProperty("description", "Compound condition instead of name/operator/"
          + "threshold: {\"all\": [...]} (every condition true) or {\"any\": [...]} (at least "
          + "one), each item {name, field?, operator, threshold}");
      var conditionsProperties = new JsonObject();
      for (var key : List.of("all", "any")) {
        var list = new JsonObject();
        list.addProperty("type", "array");
        list.add("items", conditionItem);
        conditionsProperties.add(key, list);
      }
      conditions.add("properties", conditionsProperties);
      var schema = new SchemaBuilder()
          .addProperty("name", "string", "Entry name (e.g., /Robot/BatteryVoltage), optionally with "
              + "a field path (e.g. /RealOutputs/Drive/Pose.translation.x); not with conditions",
              false)
          .addProperty("field", "string", NumericSignal.FIELD_PARAM, false)
          .addProperty(
              "operator",
              "string",
              "Comparison operator: lt (<), lte (<=), gt (>), gte (>=), eq (==), ne (!=), or "
                  + "abs_lt, abs_lte, abs_gt, abs_gte (on the absolute value)",
              false)
          .addNumberProperty("threshold", "Threshold value to compare against", false, null)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(),
              false)
          .addIntegerProperty("limit", "Maximum number of transitions and intervals to return", false, 100)
          .build();
      schema.getAsJsonObject("properties").add("conditions", conditions);
      return schema;
    }

    /** One condition: a signal compared with a threshold. */
    private record Condition(NumericSignal signal, String operator, double threshold) {
      String describe() {
        var op = operator.toLowerCase(java.util.Locale.ROOT);
        var subject = op.startsWith("abs_") ? "|" + signal.label() + "|" : signal.label();
        return subject + " " + operatorSymbol(op.startsWith("abs_") ? op.substring(4) : op) + " "
            + threshold;
      }
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      int limit = getOptInt(arguments, "limit", 100);
      validatePositive(limit, "limit");

      boolean all = true;
      var conditions = new ArrayList<Condition>();
      if (arguments.has("conditions") && !arguments.get("conditions").isJsonNull()) {
        if (arguments.has("name")) {
          throw new IllegalArgumentException("Pass either name/operator/threshold or conditions, "
              + "not both");
        }
        var spec = arguments.get("conditions");
        if (!spec.isJsonObject() || spec.getAsJsonObject().size() != 1
            || !(spec.getAsJsonObject().has("all") || spec.getAsJsonObject().has("any"))) {
          throw new IllegalArgumentException("conditions must be {\"all\": [...]} or "
              + "{\"any\": [...]}");
        }
        all = spec.getAsJsonObject().has("all");
        var items = spec.getAsJsonObject().get(all ? "all" : "any");
        if (!items.isJsonArray() || items.getAsJsonArray().isEmpty()) {
          throw new IllegalArgumentException("conditions." + (all ? "all" : "any")
              + " must be a non-empty array of {name, field?, operator, threshold}");
        }
        for (var item : items.getAsJsonArray()) {
          if (!item.isJsonObject()) {
            throw new IllegalArgumentException("Each condition must be {name, field?, operator, "
                + "threshold}, got " + item);
          }
          conditions.add(condition(log, item.getAsJsonObject()));
        }
      } else {
        conditions.add(condition(log, arguments));
      }
      var scope = TimeScope.fromArguments(log, null, arguments);

      // Values are known once every condition's entry has logged
      double firstKnown = Double.NEGATIVE_INFINITY;
      for (var c : conditions) {
        if (c.signal().values().isEmpty()) {
          throw new IllegalArgumentException("No values for entry: " + c.signal().label());
        }
        firstKnown = Math.max(firstKnown, c.signal().values().get(0).timestamp());
      }

      var transitions = new ArrayList<JsonObject>();
      var intervals = new ArrayList<JsonObject>();
      double totalTrue = 0;
      double knownDuration = 0;
      int n = conditions.size();
      for (var window : scope.windows()) {
        double windowStart = Math.max(window.start(), firstKnown);
        double windowEnd = window.end();
        if (windowEnd < windowStart) continue;
        knownDuration += windowEnd - windowStart;

        // Each condition's truth from the value in force before the window (null: none yet)
        var truth = new Boolean[n];
        var current = new double[n];
        for (int k = 0; k < n; k++) {
          var before = lastFiniteBefore(conditions.get(k).signal().values(), windowStart);
          if (before != null) {
            current[k] = before;
            truth[k] = evaluateCondition(before, conditions.get(k).operator(),
                conditions.get(k).threshold());
          }
        }
        boolean wasTrue = combine(truth, all);
        double openedAt = windowStart;
        if (wasTrue) {
          transitions.add(transition(windowStart, n == 1 ? current[0] : null, current, true));
        }

        // Every sample of every entry in the window, in time order
        var cursors = new int[n];
        for (int k = 0; k < n; k++) {
          cursors[k] = firstAtOrAfter(conditions.get(k).signal().values(), windowStart);
        }
        while (true) {
          double t = Double.POSITIVE_INFINITY;
          for (int k = 0; k < n; k++) {
            var values = conditions.get(k).signal().values();
            if (cursors[k] < values.size() && window.contains(values.get(cursors[k]).timestamp())) {
              t = Math.min(t, values.get(cursors[k]).timestamp());
            }
          }
          if (t == Double.POSITIVE_INFINITY) break;
          Double trigger = null;
          for (int k = 0; k < n; k++) {
            var values = conditions.get(k).signal().values();
            while (cursors[k] < values.size() && values.get(cursors[k]).timestamp() == t) {
              var v = toDouble(values.get(cursors[k]).value());
              if (v != null && Double.isFinite(v)) {
                current[k] = v;
                truth[k] = evaluateCondition(v, conditions.get(k).operator(),
                    conditions.get(k).threshold());
                if (trigger == null) trigger = v;
              }
              cursors[k]++;
            }
          }
          boolean isTrue = combine(truth, all);
          if (isTrue && !wasTrue) {
            transitions.add(transition(t, n == 1 ? trigger : null, current, false));
            openedAt = t;
          } else if (!isTrue && wasTrue) {
            intervals.add(interval(openedAt, t, "condition_false"));
            totalTrue += t - openedAt;
          }
          wasTrue = isTrue;
        }
        if (wasTrue) {
          intervals.add(interval(openedAt, windowEnd, "window_end"));
          totalTrue += Math.max(0, windowEnd - openedAt);
        }
      }

      var transitionsArray = new JsonArray();
      transitions.stream().limit(limit).forEach(transitionsArray::add);
      var intervalsArray = new JsonArray();
      intervals.stream().limit(limit).forEach(intervalsArray::add);

      var description = n == 1 ? conditions.get(0).describe()
          : String.join(all ? " AND " : " OR ",
              conditions.stream().map(c -> "(" + c.describe() + ")").toList());
      var builder = success();
      if (n == 1) builder.addProperty("name", conditions.get(0).signal().label());
      builder.addProperty("condition", description);
      if (n > 1) {
        builder.addProperty("combine", all ? "all" : "any");
        var labels = new JsonArray();
        conditions.forEach(c -> labels.add(c.describe()));
        builder.addData("conditions", labels);
      }
      builder.addProperty("transition_count", transitions.size())
          .addLimitedList("transitions", transitionsArray, transitions.size(), limit)
          .addProperty("interval_count", intervals.size())
          .addLimitedList("intervals", intervalsArray, intervals.size(), limit)
          .addProperty("total_true_sec", totalTrue)
          .addProperty("window_sec", knownDuration);
      for (int k = 0; k < n; k++) {
        builder.addInputSignal(n == 1 ? "entry" : "condition" + k, conditions.get(k).signal());
      }
      builder.addInputScope(scope);
      if (knownDuration > 0) {
        builder.addProperty("fraction_of_window", totalTrue / knownDuration);
      }
      return builder.build();
    }

    /** A condition from {name, field?, operator, threshold} (the tool's own arguments or an item). */
    private Condition condition(org.triplehelix.wpilogmcp.log.LogData log, JsonObject args) {
      if (!args.has("name") || args.get("name").isJsonNull()) {
        throw new IllegalArgumentException("Missing required parameter: name (or pass conditions)");
      }
      var operator = getRequiredString(args, "operator");
      if (!OPERATORS.contains(operator.toLowerCase(java.util.Locale.ROOT))) {
        throw new IllegalArgumentException("Unknown operator: " + operator + ". Valid operators: "
            + "lt, <, lte, <=, gt, >, gte, >=, eq, ==, ne, !=, abs_lt, abs_lte, abs_gt, abs_gte");
      }
      var threshold = getOptDouble(args, "threshold");
      if (threshold == null) {
        throw new IllegalArgumentException("Missing required parameter: threshold");
      }
      var signal = StatisticsTools.signal(log, args, "name", "field", name());
      return new Condition(signal, operator, threshold);
    }

    private static boolean combine(Boolean[] truth, boolean all) {
      boolean any = false;
      for (var t : truth) {
        if (t == null) return false; // an entry with no value yet: not known to hold
        if (t) any = true;
        else if (all) return false;
      }
      return all || any;
    }

    private static JsonObject transition(double t, Double value, double[] values,
        boolean atWindowStart) {
      var o = new JsonObject();
      o.addProperty("timestamp_sec", t);
      if (values.length == 1) {
        o.addProperty("value", value != null ? value : values[0]);
      } else {
        var array = new JsonArray();
        for (double v : values) array.add(v);
        o.add("values", array);
      }
      if (atWindowStart) o.addProperty("at_window_start", true);
      return o;
    }

    /** The last finite value strictly before {@code t}, or null. */
    private static Double lastFiniteBefore(List<org.triplehelix.wpilogmcp.log.TimestampedValue>
        values, double t) {
      for (int i = firstAtOrAfter(values, t) - 1; i >= 0; i--) {
        var v = toDouble(values.get(i).value());
        if (v != null && Double.isFinite(v)) return v;
      }
      return null;
    }

    private static int firstAtOrAfter(List<org.triplehelix.wpilogmcp.log.TimestampedValue> values,
        double t) {
      int lo = 0;
      int hi = values.size();
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (values.get(mid).timestamp() < t) lo = mid + 1;
        else hi = mid;
      }
      return lo;
    }

    static JsonObject interval(double start, double end, String reason) {
      var o = new JsonObject();
      o.addProperty("start", start);
      o.addProperty("end", end);
      o.addProperty("duration", Math.max(0, end - start));
      o.addProperty("end_reason", reason);
      return o;
    }

    private static boolean evaluateCondition(double value, String operator, double threshold) {
      var op = operator.toLowerCase(java.util.Locale.ROOT);
      if (op.startsWith("abs_")) {
        value = Math.abs(value);
        op = op.substring(4);
      }
      return switch (op) {
        case "lt", "<" -> value < threshold;
        case "lte", "<=" -> value <= threshold;
        case "gt", ">" -> value > threshold;
        case "gte", ">=" -> value >= threshold;
        case "eq", "==" -> Math.abs(value - threshold) <= Math.max(1e-9, Math.abs(threshold) * 1e-6);
        case "ne", "!=" -> Math.abs(value - threshold) > Math.max(1e-9, Math.abs(threshold) * 1e-6);
        default -> throw new IllegalArgumentException("Unknown operator: " + operator);
      };
    }

    private static String operatorSymbol(String operator) {
      return switch (operator.toLowerCase(java.util.Locale.ROOT)) {
        case "lt" -> "<";
        case "lte" -> "<=";
        case "gt" -> ">";
        case "gte" -> ">=";
        case "eq" -> "==";
        case "ne" -> "!=";
        default -> operator;
      };
    }
  }

  static class SearchStringsTool extends LogRequiringTool {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_MAX_VALUE_CHARS = 500;

    @Override
    public String name() {
      return "search_strings";
    }

    @Override
    public String description() {
      return "List or search the text a log holds, completely and in time order across all "
          + "entries: string entries (console output, messages); string[] entries such as WPILib "
          + "Alerts (/RealOutputs/Alerts/warnings), where each message is one match from when it "
          + "appeared (timestamp_sec) to when it cleared (end_sec, duration_sec; "
          + "active_at_log_end when it never did); and the string values of json entries. Each "
          + "match says its source (string, alert, json). Filters: pattern (case-insensitive "
          + "substring, or a regex with regex=true), level (error, warning, info, or any; an "
          + "alert's level comes from its entry name, other text is classified exactly as "
          + "get_ds_timeline counts it), entry_pattern, start_time/end_time (an alert matches when "
          + "it was present in the range). Results are paged: total_matches is the full count, "
          + "offset/limit select a page, has_more says whether more remain, so nothing is "
          + "silently dropped. collapse_repeats folds runs of identical samples that are adjacent "
          + "in the same entry into one match with repeat_count. Each match carries its level and "
          + "the matching line (line is cut at 200 chars; value at max_value_chars, with "
          + "*_truncated flags). Regex mode is case-insensitive with ^/$ anchoring to lines; a "
          + "pattern that backtracks for more than a second on one value is rejected.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("pattern", "string",
              "Text to search for: case-insensitive substring, or a regular expression when regex=true. "
              + "Omit to list every string sample (use level/entry_pattern/time window to narrow).",
              false)
          .addProperty("regex", "boolean",
              "Treat pattern as a Java regular expression (case-insensitive). Default: false", false)
          .addProperty("level", "string",
              "Only messages classified as 'error' or 'warning' (same rules as get_ds_timeline), "
              + "'info' (info alerts), or 'any' (default)", false)
          .addProperty("entry_pattern", "string",
              "Optional: filter which entries to search (e.g., 'Console' or 'Output')", false)
          .addNumberProperty("start_time", "Start timestamp in seconds (optional)", false, null)
          .addNumberProperty("end_time", "End timestamp in seconds (optional)", false, null)
          .addIntegerProperty("offset", "Number of matches to skip, for paging (default: 0)", false, 0)
          .addIntegerProperty("limit",
              "Maximum matches to return per call (default: " + DEFAULT_LIMIT + ", max: " + MAX_LIMIT + ")",
              false, DEFAULT_LIMIT)
          .addProperty("collapse_repeats", "boolean",
              "Fold runs of identical samples that are adjacent in the same entry's stream into one "
              + "match with repeat_count and last_timestamp_sec; any other sample in between (even one "
              + "the filters exclude) ends the run. Default: false", false)
          .addIntegerProperty("max_value_chars",
              "Truncate each returned value to this many characters (default: " + DEFAULT_MAX_VALUE_CHARS + ")",
              false, DEFAULT_MAX_VALUE_CHARS)
          .build();
    }

    /**
     * Regex evaluation budget per text value: a pattern that backtracks past this on one value is
     * rejected. Per value, not per call, so a cheap pattern over a long console log is not
     * mistaken for a catastrophic one.
     */
    private static final long REGEX_BUDGET_NANOS = 1_000_000_000L;

    /** One matching sample. */
    private static final class Match {
      final double timestamp;
      final String entry;
      final int entryId;
      final int sampleIndex;
      final String level;
      final String line;
      final String value;
      final TextEvents.Event event;
      int repeatCount = 1;
      double lastTimestamp;
      int lastSampleIndex;

      Match(TextEvents.Event event, String level, String line) {
        this.timestamp = event.timestamp();
        this.entry = event.entry();
        this.entryId = event.entryId();
        this.sampleIndex = event.sampleIndex();
        this.level = level;
        this.line = line;
        this.value = event.text();
        this.event = event;
        this.lastTimestamp = timestamp;
        this.lastSampleIndex = sampleIndex;
      }
    }

    /**
     * A view of a string whose {@code charAt} checks a deadline, so a catastrophically
     * backtracking user regex fails fast instead of hanging the server.
     */
    private static final class DeadlineCharSequence implements CharSequence {
      private final CharSequence text;
      private final long deadlineNanos;

      DeadlineCharSequence(CharSequence text, long deadlineNanos) {
        this.text = text;
        this.deadlineNanos = deadlineNanos;
      }

      @Override public int length() { return text.length(); }

      @Override public char charAt(int index) {
        if (System.nanoTime() > deadlineNanos) {
          throw new IllegalArgumentException("Regex evaluation exceeded "
              + (REGEX_BUDGET_NANOS / 1_000_000) + " ms on a single value (" + text.length()
              + " characters); simplify the pattern (avoid nested quantifiers)");
        }
        return text.charAt(index);
      }

      @Override public CharSequence subSequence(int start, int end) {
        return new DeadlineCharSequence(text.subSequence(start, end), deadlineNanos);
      }

      @Override public String toString() { return text.toString(); }
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var pattern = getOptString(arguments, "pattern", null);
      boolean regex = getOptBoolean(arguments, "regex");
      var level = getOptString(arguments, "level", "any").toLowerCase();
      if (!level.equals("any") && !level.equals("error") && !level.equals("warning")
          && !level.equals("info")) {
        throw new IllegalArgumentException("level must be 'error', 'warning', 'info', or 'any' "
            + "(got '" + level + "')");
      }
      var entryPattern = getOptString(arguments, "entry_pattern", null);
      var entryPatternLower = entryPattern == null ? null : entryPattern.toLowerCase();
      var startTime = getOptDouble(arguments, "start_time");
      var endTime = getOptDouble(arguments, "end_time");
      int offset = Math.max(0, getOptInt(arguments, "offset", 0));
      int limit = Math.min(MAX_LIMIT, Math.max(1, getOptInt(arguments, "limit", DEFAULT_LIMIT)));
      boolean collapseRepeats = getOptBoolean(arguments, "collapse_repeats");
      int maxValueChars = Math.max(1, getOptInt(arguments, "max_value_chars", DEFAULT_MAX_VALUE_CHARS));

      java.util.regex.Pattern compiled = null;
      String patternLower = null;
      if (pattern != null && !pattern.isEmpty()) {
        if (regex) {
          try {
            // MULTILINE: ^ and $ anchor to lines of a multi-line console sample; UNICODE_CASE so
            // case folding matches the substring mode. '.' still stops at a line break.
            compiled = java.util.regex.Pattern.compile(pattern,
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE
                    | java.util.regex.Pattern.MULTILINE);
          } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex: " + e.getDescription());
          }
        } else {
          patternLower = pattern.toLowerCase(java.util.Locale.ROOT);
        }
      }
      var matches = new ArrayList<Match>();
      for (var info : TextEvents.textEntries(log)) {
        var entryName = info.name();
        if (entryPatternLower != null
            && !entryName.toLowerCase(java.util.Locale.ROOT).contains(entryPatternLower)) continue;
        for (var event : TextEvents.of(log, info)) {
          if (!event.overlaps(startTime, endTime)) continue;
          var value = event.text();
          var classified = classifyText(value);
          var sampleLevel = TextEvents.level(event);
          if (!level.equals("any") && !level.equals(sampleLevel)) continue;
          String line;
          if (compiled != null) {
            var m = compiled.matcher(
                new DeadlineCharSequence(value, System.nanoTime() + REGEX_BUDGET_NANOS));
            if (!m.find()) continue;
            line = lineAt(value, m.start());
          } else if (patternLower != null) {
            int idx = value.toLowerCase(java.util.Locale.ROOT).indexOf(patternLower);
            if (idx < 0) continue;
            line = lineAt(value, idx);
          } else {
            line = classified != null ? classified.message() : lineAt(value, 0);
          }
          matches.add(new Match(event, sampleLevel, line));
        }
      }
      matches.sort(Comparator.comparingDouble((Match m) -> m.timestamp).thenComparingInt(m -> m.entryId));
      int totalMatches = matches.size();

      if (collapseRepeats) {
        // Fold only samples that are adjacent in their entry's own stream (a filtered-out or
        // different sample in between ends the run), so a repeat_count never spans a gap.
        var collapsed = new ArrayList<Match>();
        var lastByEntry = new HashMap<String, Match>();
        for (var m : matches) {
          var previous = lastByEntry.get(m.entry);
          // alerts are already one match per appearance
          if (previous != null && m.event.source() != TextEvents.Source.ALERT
              && previous.lastSampleIndex + 1 == m.sampleIndex
              && previous.value.equals(m.value)) {
            previous.repeatCount++;
            previous.lastTimestamp = m.timestamp;
            previous.lastSampleIndex = m.sampleIndex;
            continue;
          }
          collapsed.add(m);
          lastByEntry.put(m.entry, m);
        }
        matches = collapsed;
      }

      int available = matches.size();
      int from = Math.min(offset, available);
      int to = Math.min(from + limit, available);
      var page = matches.subList(from, to);

      var matchesArray = new JsonArray();
      for (var m : page) {
        var obj = new JsonObject();
        obj.addProperty("timestamp_sec", m.timestamp);
        obj.addProperty("entry", m.entry);
        obj.addProperty("source", m.event.source().wire());
        if (m.event.source() == TextEvents.Source.ALERT) {
          if (m.event.end() != null) {
            obj.addProperty("end_sec", m.event.end());
            obj.addProperty("duration_sec", m.event.duration());
          } else {
            obj.addProperty("active_at_log_end", true);
          }
        }
        if (m.level != null) obj.addProperty("level", m.level);
        obj.addProperty("line", truncate(m.line, MESSAGE_LINE_LIMIT));
        if (m.line.length() > MESSAGE_LINE_LIMIT) obj.addProperty("line_truncated", true);
        if (m.value.length() > maxValueChars) {
          obj.addProperty("value", m.value.substring(0, maxValueChars) + "...");
          obj.addProperty("value_truncated", true);
        } else {
          obj.addProperty("value", m.value);
        }
        if (m.repeatCount > 1) {
          obj.addProperty("repeat_count", m.repeatCount);
          obj.addProperty("last_timestamp_sec", m.lastTimestamp);
        }
        matchesArray.add(obj);
      }

      var builder = success();
      if (pattern != null) builder.addProperty("pattern", pattern);
      builder.addProperty("regex", regex)
          .addProperty("level", level)
          .addProperty("total_matches", totalMatches);
      if (collapseRepeats) builder.addProperty("total_after_collapse", available);
      return builder
          .addProperty("offset", from)
          .addProperty("limit", limit)
          .addProperty("returned", page.size())
          .addProperty("match_count", page.size())
          .addProperty("has_more", to < available)
          .addLimitedList("matches", matchesArray, Math.max(0, available - from), limit)
          .build();
    }

    /**
     * Returns the line of {@code text} that contains character index {@code index}, stripped.
     * Safe when the index is 0 or lands on a line break (an empty line is returned).
     */
    static String lineAt(String text, int index) {
      int start = index > 0 ? text.lastIndexOf('\n', index - 1) + 1 : 0;
      int end = text.indexOf('\n', index);
      if (end < 0) end = text.length();
      if (end < start) end = start;
      return text.substring(start, end).strip();
    }
  }
}
