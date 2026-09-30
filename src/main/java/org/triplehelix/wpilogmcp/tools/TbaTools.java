/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;


import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * The Blue Alliance integration tools.
 *
 * <p>Tools included:
 * <ul>
 *   <li>{@code get_tba_status} - Get TBA API integration status</li>
 *   <li>{@code get_tba_match_data} - Query match scores and detailed results from TBA</li>
 * </ul>
 *
 * @since 0.6.1 Added get_tba_match_data tool
 */
public final class TbaTools {

  private TbaTools() {}

  /**
   * Registers all TBA tools with the MCP server.
   */
  public static void registerAll(ToolRegistry registry) {
    registry.registerTool(new GetTbaStatusTool());
    registry.registerTool(new GetTbaMatchDataTool());
  }

  static class GetTbaStatusTool extends ToolBase {
    @Override
    public String name() {
      return "get_tba_status";
    }

    @Override
    public String description() {
      return "Check if The Blue Alliance API is configured and available. "
          + "When TBA is configured, you can: (1) Use list_available_logs to see match scores "
          + "and win/loss results for each log, or (2) Use get_tba_match_data to query specific "
          + "match details including autonomous points. TBA data is the authoritative source for "
          + "match outcomes—don't guess from telemetry!";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    protected JsonElement executeInternal(JsonObject arguments) throws Exception {
      var client = tbaClient;

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("available", client.isAvailable());

      if (client.isAvailable()) {
        result.addProperty("configuration", "configured");

        var cache = new JsonObject();
        for (var entry : client.getCacheStats().entrySet()) {
          cache.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("cache", cache);

        result.addProperty("hint",
            "TBA data will be included in list_available_logs for logs with team number in metadata");
      } else {
        result.addProperty("configuration", "not_configured");
        result.addProperty("hint",
            "Set TBA_API_KEY environment variable or use -tba-key argument. "
                + "Get a free API key at https://www.thebluealliance.com/account");
      }

      return result;
    }
  }

  /**
   * Query match scores and detailed results from The Blue Alliance.
   *
   * <p>This tool provides direct access to TBA match data, including:
   * <ul>
   *   <li>Match scores for both alliances</li>
   *   <li>Win/loss/tie result</li>
   *   <li>Detailed score breakdown (autonomous points, teleop points, etc.)</li>
   *   <li>Team lists per alliance</li>
   * </ul>
   */
  static class GetTbaMatchDataTool extends ToolBase {
    @Override
    public String name() {
      return "get_tba_match_data";
    }

    @Override
    public String description() {
      return "Query match scores and detailed results from The Blue Alliance. "
          + "Use this to answer questions like 'What was our score?', 'Did we win?', "
          + "'How many autonomous points did we score?', or 'What were the match results?'. "
          + "Returns alliance scores, win/loss status, and detailed score breakdown including "
          + "autonomous points when available. "
          + "IMPORTANT: This is the primary tool for getting match outcome data—don't guess or infer "
          + "match results from telemetry when you can query TBA directly.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addIntegerProperty("year", "Competition year (e.g., 2024)", true, null)
          .addProperty("event_code", "string",
              "TBA event code — this is NOT the abbreviation from log filenames. "
              + "Find the correct code at thebluealliance.com/events/{year}. "
              + "Examples: 'caph' (Poway), 'cmptx' (Houston Championship)", true)
          .addProperty("match_type", "string",
              "Match type: 'Qualification', 'Quarterfinal', 'Semifinal', 'Final', or "
                  + "'Elimination', or TBA's codes 'qm' (or 'q'), 'qf', 'sf', 'f'", true)
          .addIntegerProperty("match_number", "Match number within the type", true, null)
          .addIntegerProperty("team_number",
              "Optional: Your team number to highlight your alliance's data", false, null)
          .build();
    }

    @Override
    protected JsonElement executeInternal(JsonObject arguments) throws Exception {
      var client = tbaClient;

      if (!client.isAvailable()) {
        return errorResult("TBA API not configured. Set TBA_API_KEY environment variable or use -tba-key argument. "
            + "Get a free API key at https://www.thebluealliance.com/account");
      }

      int year = arguments.get("year").getAsInt();
      String eventCode = getRequiredString(arguments, "event_code");
      String matchType = getRequiredString(arguments, "match_type");
      int matchNumber = arguments.get("match_number").getAsInt();
      Integer teamNumber = arguments.has("team_number") && !arguments.get("team_number").isJsonNull()
          ? arguments.get("team_number").getAsInt()
          : null;

      try {
        return lookup(client, year, eventCode, matchType, matchNumber, teamNumber);
      } catch (org.triplehelix.wpilogmcp.tba.TbaUnavailableException e) {
        return errorResult(e.getMessage() + " Try again, or check the API key with "
            + "get_tba_status.");
      }
    }

    private JsonElement lookup(org.triplehelix.wpilogmcp.tba.TbaClient client, int year,
        String eventCode, String matchType,
        int matchNumber, Integer teamNumber) {
      // Try to get match data
      var matchOpt = client.getMatch(year, eventCode, matchType, matchNumber);

      // If not found and it's an elimination match, try smart matching
      if (matchOpt.isEmpty() && isGenericElimination(matchType) && teamNumber != null) {
        var teamResultOpt = client.getTeamMatchResult(year, eventCode, matchType, matchNumber, teamNumber);
        if (teamResultOpt.isPresent()) {
          // Build a simplified result from TeamMatchResult
          var teamResult = teamResultOpt.get();
          var result = new JsonObject();
          result.addProperty("success", true);
          result.addProperty("match_found", true);
          result.addProperty("lookup_method", "smart_elimination_match");

          var yourAlliance = new JsonObject();
          yourAlliance.addProperty("color", teamResult.alliance());
          yourAlliance.addProperty("score", teamResult.score());
          yourAlliance.addProperty("won", teamResult.won());
          result.add("your_alliance", yourAlliance);

          if (teamResult.getMatchTime() != null) {
            result.addProperty("match_time", formatTime(teamResult.getMatchTimeSeconds()));
          }

          result.addProperty("note",
              "Match found via elimination match search. For full details, use specific match type "
              + "(Quarterfinal, Semifinal, Final) instead of generic 'Elimination'.");

          return result;
        }
      }

      if (matchOpt.isEmpty()) {
        var result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("status", "no_match");
        result.addProperty("match_found", false);

        // Validate the event code to provide a helpful error
        var eventOpt = client.getEvent(year, eventCode);
        if (eventOpt.isEmpty()) {
          // Event doesn't exist — this is likely the wrong event code
          result.addProperty("reason",
              "Event '" + eventCode + "' not found on TBA for " + year + ". "
              + "The event_code must be a TBA event code (e.g., 'caph'), not an abbreviation from log filenames.");

          // Try to find similar events
          var similar = client.searchEvents(year, eventCode, 5);
          if (!similar.isEmpty()) {
            var suggestedEvents = new JsonArray();
            similar.forEach(suggestedEvents::add);
            result.add("similar_events", suggestedEvents);
            result.addProperty("hint", "Did you mean one of these event codes?");
          } else {
            result.addProperty("hint",
                "Search for the correct event code at thebluealliance.com/events/" + year);
          }
        } else {
          // Event exists but match wasn't found
          result.addProperty("reason",
              "Event '" + eventCode + "' exists but match " + matchType + " " + matchNumber
              + " was not found. Check match_type and match_number.");

          var suggestions = new JsonArray();
          suggestions.add("For elimination matches, try 'Semifinal' or 'Final' instead of 'Elimination'");
          suggestions.add("Match numbers are 1-indexed (first qual is match 1, not 0)");
          suggestions.add("Verify match exists at thebluealliance.com/event/" + year + eventCode.toLowerCase());
          result.add("suggestions", suggestions);
        }

        return result;
      }

      var match = matchOpt.get();
      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("match_found", true);

      // Basic match info
      if (match.has("key")) {
        result.addProperty("match_key", match.get("key").getAsString());
      }
      if (match.has("comp_level")) {
        result.addProperty("comp_level", match.get("comp_level").getAsString());
      }
      if (match.has("match_number")) {
        result.addProperty("match_number", match.get("match_number").getAsInt());
      }

      // Match time
      if (match.has("actual_time") && !match.get("actual_time").isJsonNull()) {
        result.addProperty("match_time", formatTime(match.get("actual_time").getAsLong()));
      } else if (match.has("time") && !match.get("time").isJsonNull()) {
        result.addProperty("scheduled_time", formatTime(match.get("time").getAsLong()));
      }

      // Winning alliance
      String winningAlliance = match.has("winning_alliance")
          ? match.get("winning_alliance").getAsString()
          : "";
      if (!winningAlliance.isEmpty()) {
        result.addProperty("winning_alliance", winningAlliance);
      } else {
        result.addProperty("winning_alliance", "tie_or_not_played");
      }

      // Alliance data
      var alliances = match.getAsJsonObject("alliances");
      if (alliances != null) {
        var alliancesResult = new JsonObject();

        for (var color : new String[]{"red", "blue"}) {
          var allianceData = alliances.getAsJsonObject(color);
          if (allianceData == null) continue;

          var allianceResult = new JsonObject();
          allianceResult.addProperty("score", allianceData.get("score").getAsInt());

          // Team list
          var teamKeys = allianceData.getAsJsonArray("team_keys");
          if (teamKeys != null) {
            var teams = new JsonArray();
            for (var tk : teamKeys) {
              // "frc1234" to 1234; a B team ("frc1234B") stays a string, "1234B"
              var teamKey = tk.getAsString();
              var number = teamKey.startsWith("frc") ? teamKey.substring(3) : teamKey;
              if (number.matches("\\d+")) {
                teams.add(Integer.parseInt(number));
              } else {
                teams.add(number);
              }
            }
            allianceResult.add("teams", teams);
          }

          // Check if this is the user's alliance
          if (teamNumber != null && teamKeys != null) {
            for (var tk : teamKeys) {
              if (tk.getAsString().equals("frc" + teamNumber)) {
                allianceResult.addProperty("your_alliance", true);
                allianceResult.addProperty("won", color.equals(winningAlliance));
              }
            }
          }

          alliancesResult.add(color, allianceResult);
        }

        result.add("alliances", alliancesResult);
      }

      // Score breakdown (detailed scoring)
      var scoreBreakdown = match.getAsJsonObject("score_breakdown");
      if (scoreBreakdown != null) {
        var breakdownResult = new JsonObject();

        for (var color : new String[]{"red", "blue"}) {
          var colorBreakdown = scoreBreakdown.getAsJsonObject(color);
          if (colorBreakdown == null) continue;

          var colorResult = new JsonObject();

          // Every points subtotal: TBA names them ...Points in every season's breakdown
          // (autoPoints, teleopPoints, foulPoints, totalPoints, and the game's own), so each
          // year's game is covered without a per-game list
          for (var field : colorBreakdown.entrySet()) {
            if (field.getKey().endsWith("Points") && field.getValue().isJsonPrimitive()
                && field.getValue().getAsJsonPrimitive().isNumber()) {
              colorResult.add(field.getKey(), field.getValue());
            }
          }

          // Only add if we found something
          if (colorResult.size() > 0) {
            breakdownResult.add(color, colorResult);
          }
        }

        if (breakdownResult.size() > 0) {
          result.add("score_breakdown", breakdownResult);
        }
      }

      return result;
    }

    private boolean isGenericElimination(String matchType) {
      var lower = matchType.toLowerCase();
      return (lower.contains("elimination") || lower.contains("elim"))
          && !lower.contains("semi")
          && !lower.contains("quarter")
          && !lower.contains("final");
    }

    private String formatTime(Long epochSeconds) {
      if (epochSeconds == null) return null;
      return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
          .withZone(ZoneId.systemDefault())
          .format(Instant.ofEpochSecond(epochSeconds));
    }

    private void extractIfPresent(JsonObject source, JsonObject dest, String key) {
      if (source.has(key) && !source.get(key).isJsonNull()) {
        var value = source.get(key);
        if (value.isJsonPrimitive()) {
          if (value.getAsJsonPrimitive().isNumber()) {
            dest.addProperty(key, value.getAsInt());
          } else if (value.getAsJsonPrimitive().isBoolean()) {
            dest.addProperty(key, value.getAsBoolean());
          } else {
            dest.addProperty(key, value.getAsString());
          }
        }
      }
    }
  }
}
