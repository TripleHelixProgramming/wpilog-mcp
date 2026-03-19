package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.mcp.McpServer;
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
  public static void registerAll(McpServer server) {
    server.registerTool(new SearchEntriesTool());
    server.registerTool(new GetTypesTool());
    server.registerTool(new FindConditionTool());
    server.registerTool(new SearchStringsTool());
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
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty(
              "type", "string", "Filter by type (substring match, e.g., 'Pose3d')", false)
          .addProperty("pattern", "string", "Filter by name containing this string", false)
          .addIntegerProperty("min_samples", "Minimum number of samples required", false, null)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.ParsedLog log, JsonObject arguments) throws Exception {
      var typeFilter =
          arguments.has("type") ? arguments.get("type").getAsString() : null;
      var nameContains =
          arguments.has("pattern") ? arguments.get("pattern").getAsString() : null;
      var minSamples =
          arguments.has("min_samples") ? arguments.get("min_samples").getAsInt() : null;

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
    public JsonObject inputSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.ParsedLog log, JsonObject arguments) throws Exception {
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
    public JsonObject inputSchema() {
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
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.ParsedLog log, JsonObject arguments) throws Exception {
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
        case "eq", "==" -> Math.abs(value - threshold) < 0.0001;
        default -> false;
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
    @Override
    public String name() {
      return "search_strings";
    }

    @Override
    public String description() {
      return "Search string entries for text patterns. "
          + "Useful for finding errors, warnings, or specific messages in console output logs. "
          + "Searches case-insensitively by default.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty(
              "pattern",
              "string",
              "Text pattern to search for (substring match, case-insensitive)",
              true)
          .addProperty(
              "entry_pattern",
              "string",
              "Optional: filter which entries to search (e.g., 'Console' or 'Output')",
              false)
          .addIntegerProperty("limit", "Maximum number of matches to return", false, 50)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(org.triplehelix.wpilogmcp.log.ParsedLog log, JsonObject arguments) throws Exception {
      var pattern = arguments.get("pattern").getAsString().toLowerCase();
      var entryPattern = arguments.has("entry_pattern") && !arguments.get("entry_pattern").isJsonNull()
          ? arguments.get("entry_pattern").getAsString().toLowerCase()
          : null;
      int limit = arguments.has("limit") && !arguments.get("limit").isJsonNull()
          ? arguments.get("limit").getAsInt()
          : 50;

      var matches = new ArrayList<JsonObject>();

      for (var e : log.entries().entrySet()) {
        var entryName = e.getKey();
        var info = e.getValue();

        if (entryPattern != null && !entryName.toLowerCase().contains(entryPattern)) {
          continue;
        }

        if (!"string".equals(info.type())) {
          continue;
        }

        var values = log.values().get(entryName);
        if (values == null) continue;

        for (var tv : values) {
          if (!(tv.value() instanceof String)) continue;

          var strValue = (String) tv.value();
          if (strValue.toLowerCase().contains(pattern)) {
            var match = new JsonObject();
            match.addProperty("timestamp_sec", tv.timestamp());
            match.addProperty("entry", entryName);
            match.addProperty("value", strValue);
            matches.add(match);

            if (matches.size() >= limit) break;
          }
        }

        if (matches.size() >= limit) break;
      }

      var matchesArray = new JsonArray();
      matches.forEach(matchesArray::add);

      return success()
          .addProperty("pattern", pattern)
          .addProperty("match_count", matches.size())
          .addData("matches", matchesArray)
          .build();
    }
  }
}
