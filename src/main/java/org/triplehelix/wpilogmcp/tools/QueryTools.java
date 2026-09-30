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
      return "Search for entries matching various criteria.";
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

        var values = log.values().get(entry.name());
        int sampleCount = values != null ? values.size() : 0;

        if (minSamples != null && sampleCount < minSamples) continue;

        matches.add(entry.name());
      }

      matches.sort(String::compareTo);

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
    @Override
    public String name() {
      return "find_condition";
    }

    @Override
    public String description() {
      return "Find timestamps where a numeric entry crosses a threshold. "
          + "Useful for questions like 'When did battery voltage drop below 11V?' "
          + "Returns transition points where the condition first becomes true.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("name", "string", "Entry name (e.g., /Robot/BatteryVoltage)", true)
          .addProperty(
              "operator",
              "string",
              "Comparison operator: lt (<), lte (<=), gt (>), gte (>=), eq (==)",
              true)
          .addNumberProperty("threshold", "Threshold value to compare against", true, null)
          .addIntegerProperty("limit", "Maximum number of transitions to return", false, 100)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.LogData log, JsonObject arguments) throws Exception {
      var name = arguments.get("name").getAsString();
      var operator = arguments.get("operator").getAsString();
      double threshold = arguments.get("threshold").getAsDouble();
      int limit = arguments.has("limit") && !arguments.get("limit").isJsonNull()
          ? arguments.get("limit").getAsInt()
          : 100;

      var entry = log.entries().get(name);
      if (entry == null) {
        throw new IllegalArgumentException("Entry not found: " + name);
      }

      if (!isNumericType(entry.type())) {
        throw new IllegalArgumentException("Entry is not numeric type: " + entry.type());
      }

      var values = log.values().get(name);
      if (values == null || values.isEmpty()) {
        throw new IllegalArgumentException("No values for entry: " + name);
      }

      var transitions = new ArrayList<JsonObject>();
      boolean wasTrue = false;

      for (var tv : values) {
        if (!(tv.value() instanceof Number)) continue;

        double value = ((Number) tv.value()).doubleValue();
        boolean isTrue = evaluateCondition(value, operator, threshold);

        if (isTrue && !wasTrue) {
          var transition = new JsonObject();
          transition.addProperty("timestamp_sec", tv.timestamp());
          transition.addProperty("value", value);
          transitions.add(transition);

          if (transitions.size() >= limit) break;
        }
        wasTrue = isTrue;
      }

      var transitionsArray = new JsonArray();
      transitions.forEach(transitionsArray::add);

      return success()
          .addProperty("name", name)
          .addProperty("condition", name + " " + operatorSymbol(operator) + " " + threshold)
          .addProperty("transition_count", transitions.size())
          .addData("transitions", transitionsArray)
          .build();
    }

    private boolean evaluateCondition(double value, String operator, double threshold) {
      return switch (operator.toLowerCase()) {
        case "lt", "<" -> value < threshold;
        case "lte", "<=" -> value <= threshold;
        case "gt", ">" -> value > threshold;
        case "gte", ">=" -> value >= threshold;
        case "eq", "==" -> Math.abs(value - threshold) <= Math.max(1e-9, Math.abs(threshold) * 1e-6);
        default -> throw new IllegalArgumentException("Unknown operator: " + operator + ". Valid operators: lt, <, lte, <=, gt, >, gte, >=, eq, ==");
      };
    }

    private String operatorSymbol(String operator) {
      return switch (operator.toLowerCase()) {
        case "lt" -> "<";
        case "lte" -> "<=";
        case "gt" -> ">";
        case "gte" -> ">=";
        case "eq" -> "==";
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
      return "List or search the text logged in string entries (console output, alerts, messages), "
          + "completely and in time order across all entries. Filters: pattern (case-insensitive "
          + "substring, or a regex with regex=true), level (error, warning, or any; classified exactly "
          + "as get_ds_timeline counts them), entry_pattern, start_time/end_time. Results are paged: "
          + "total_matches is the full count, offset/limit select a page, has_more says whether more "
          + "remain, so nothing is silently dropped. collapse_repeats folds runs of identical samples "
          + "that are adjacent in the same entry into one match with repeat_count. Each match carries "
          + "its level and the matching line (line is cut at 200 chars; value at max_value_chars, "
          + "with *_truncated flags). Regex mode is case-insensitive with ^/$ anchoring to lines; a "
          + "pattern that backtracks for more than a second is rejected.";
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
              + "or 'any' (default)", false)
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

    /** Regex evaluation budget per call; a pattern that backtracks past this is rejected. */
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
      int repeatCount = 1;
      double lastTimestamp;
      int lastSampleIndex;

      Match(double timestamp, String entry, int entryId, int sampleIndex, String level, String line, String value) {
        this.timestamp = timestamp;
        this.entry = entry;
        this.entryId = entryId;
        this.sampleIndex = sampleIndex;
        this.level = level;
        this.line = line;
        this.value = value;
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
              + (REGEX_BUDGET_NANOS / 1_000_000) + " ms; simplify the pattern (avoid nested quantifiers)");
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
      if (!level.equals("any") && !level.equals("error") && !level.equals("warning")) {
        throw new IllegalArgumentException("level must be 'error', 'warning', or 'any' (got '" + level + "')");
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
      long deadline = System.nanoTime() + REGEX_BUDGET_NANOS;

      var matches = new ArrayList<Match>();
      for (var e : log.entries().entrySet()) {
        var entryName = e.getKey();
        var info = e.getValue();
        if (!"string".equals(info.type())) continue;
        if (entryPatternLower != null
            && !entryName.toLowerCase(java.util.Locale.ROOT).contains(entryPatternLower)) continue;
        var values = log.values().get(entryName);
        if (values == null) continue;
        int sampleIndex = -1;
        for (var tv : values) {
          sampleIndex++;
          if (!inTimeRange(tv.timestamp(), startTime, endTime)) continue;
          if (!(tv.value() instanceof String value) || value.isBlank()) continue;
          var classified = classifyText(value);
          var sampleLevel = classified == null ? null : classified.type().toLowerCase(java.util.Locale.ROOT);
          if (!level.equals("any") && !level.equals(sampleLevel)) continue;
          String line;
          if (compiled != null) {
            var m = compiled.matcher(new DeadlineCharSequence(value, deadline));
            if (!m.find()) continue;
            line = lineAt(value, m.start());
          } else if (patternLower != null) {
            int idx = value.toLowerCase(java.util.Locale.ROOT).indexOf(patternLower);
            if (idx < 0) continue;
            line = lineAt(value, idx);
          } else {
            line = classified != null ? classified.message() : lineAt(value, 0);
          }
          matches.add(new Match(tv.timestamp(), entryName, info.id(), sampleIndex, sampleLevel, line, value));
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
          if (previous != null && previous.lastSampleIndex + 1 == m.sampleIndex
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
          .addData("matches", matchesArray)
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
