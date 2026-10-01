/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.game;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.FrcDomainTools;

@DisplayName("GameKnowledgeBase")
class GameKnowledgeBaseTest {

  @Nested
  @DisplayName("Bundled Game Data")
  class BundledData {

    @Test
    @DisplayName("loads 2026 REBUILT game data from bundled resource")
    void loads2026() {
      var kb = GameKnowledgeBase.getInstance();
      var game = kb.getGame(2026);

      assertNotNull(game, "2026 game data should be bundled");
      assertEquals(2026, game.season());
      assertEquals("REBUILT", game.gameName());
    }

    @Test
    @DisplayName("2026 match timing is correct")
    void matchTiming2026() {
      var game = GameKnowledgeBase.getInstance().getGame(2026);
      assertNotNull(game);

      assertEquals(20, game.autoDurationSec(), "Auto is 20 seconds in REBUILT");
      assertEquals(140, game.teleopDurationSec(), "Teleop is 2:20 (140s) in REBUILT");
      assertEquals(160, game.totalDurationSec(), "Total match is 2:40 (160s)");
      assertEquals(30, game.endgameDurationSec(), "Endgame is 30 seconds");
      assertEquals(30, game.endgameStartBeforeEndSec());
      assertEquals(3, game.autoToTeleopDelaySec(), "3-second delay between auto and teleop");
    }

    @Test
    @DisplayName("2026 field geometry is correct")
    void fieldGeometry2026() {
      var game = GameKnowledgeBase.getInstance().getGame(2026);
      assertNotNull(game);

      assertEquals(16.54, game.fieldLengthM(), 0.01);
      assertEquals(8.07, game.fieldWidthM(), 0.01);
    }

    @Test
    @DisplayName("2026 scoring values are present")
    void scoring2026() {
      var game = GameKnowledgeBase.getInstance().getGame(2026);
      assertNotNull(game);

      var scoring = game.scoring();
      assertNotNull(scoring);
      assertTrue(scoring.has("match_points"));
      assertTrue(scoring.has("ranking_points"));
      assertTrue(scoring.has("fouls"));

      // Verify specific values
      var auto = scoring.getAsJsonObject("match_points").getAsJsonObject("auto");
      assertEquals(1, auto.get("fuel_active_hub").getAsInt());
      assertEquals(15, auto.get("tower_level_1").getAsInt());

      var teleop = scoring.getAsJsonObject("match_points").getAsJsonObject("teleop");
      assertEquals(10, teleop.get("tower_level_1").getAsInt());
      assertEquals(20, teleop.get("tower_level_2").getAsInt());
      assertEquals(30, teleop.get("tower_level_3").getAsInt());
    }

    @Test
    @DisplayName("2026 ranking point thresholds are correct")
    void rankingPoints2026() {
      var game = GameKnowledgeBase.getInstance().getGame(2026);
      assertNotNull(game);

      var rp = game.scoring().getAsJsonObject("ranking_points");
      assertEquals(100, rp.getAsJsonObject("energized_rp").get("regional_threshold").getAsInt());
      assertEquals(360, rp.getAsJsonObject("supercharged_rp").get("regional_threshold").getAsInt());
      assertEquals(50, rp.getAsJsonObject("traversal_rp").get("regional_threshold").getAsInt());
    }

    @Test
    @DisplayName("2026 game pieces are defined")
    void gamePieces2026() {
      var game = GameKnowledgeBase.getInstance().getGame(2026);
      assertNotNull(game);

      var pieces = game.gamePieces();
      assertNotNull(pieces);
      assertEquals(1, pieces.size());
      var fuel = pieces.get(0).getAsJsonObject();
      assertEquals("FUEL", fuel.get("name").getAsString());
      assertEquals(0.150, fuel.get("diameter_m").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("2026 analysis hints are present")
    void analysisHints2026() {
      var game = GameKnowledgeBase.getInstance().getGame(2026);
      assertNotNull(game);

      var hints = game.analysisHints();
      assertNotNull(hints);
      assertTrue(hints.has("endgame_activity"));
      assertTrue(hints.has("hub_strategy"));
      assertTrue(hints.has("fuel_context"));
      assertTrue(hints.has("cycle_time"));
    }

    @Test
    @DisplayName("2026 shift timing is defined")
    void shiftTiming2026() {
      var game = GameKnowledgeBase.getInstance().getGame(2026);
      assertNotNull(game);

      var shifts = game.raw().getAsJsonObject("match_timing").getAsJsonObject("shifts");
      assertNotNull(shifts);
      assertEquals(7, shifts.size(), "Should have 7 shifts (auto + transition + 4 alliance + endgame)");

      var endGame = shifts.getAsJsonObject("end_game");
      assertEquals(130, endGame.get("start_sec").getAsInt());
      assertEquals(160, endGame.get("end_sec").getAsInt());
    }
  }

  @Nested
  @DisplayName("Missing Seasons")
  class MissingSeasons {

    @Test
    @DisplayName("returns null for unknown season")
    void returnsNullForUnknown() {
      var game = GameKnowledgeBase.getInstance().getGame(1999);
      assertNull(game);
    }

    @Test
    @DisplayName("returns null for future unknown season")
    void returnsNullForFuture() {
      var game = GameKnowledgeBase.getInstance().getGame(2099);
      assertNull(game);
    }
  }

  @Nested
  @DisplayName("User-Provided Files")
  class UserProvidedFiles {

    @TempDir Path tempDir;

    @Test
    @DisplayName("loads game data from external file")
    void loadsFromFile() throws Exception {
      String json = """
          {
            "format_version": 1,
            "season": 2025,
            "game_name": "REEFSCAPE",
            "match_timing": {
              "auto_duration_sec": 15,
              "teleop_duration_sec": 135,
              "total_duration_sec": 150,
              "endgame_duration_sec": 20,
              "endgame_start_before_end_sec": 20,
              "auto_to_teleop_delay_sec": 3
            },
            "field_geometry": {
              "field_length_m": 16.54,
              "field_width_m": 8.21
            },
            "scoring": {},
            "game_pieces": [
              {"name": "Coral", "type": "branching element"}
            ]
          }
          """;

      Path file = tempDir.resolve("2025-reefscape.json");
      Files.writeString(file, json);

      var kb = GameKnowledgeBase.getInstance();
      var game = kb.loadFromFile(file);

      assertNotNull(game);
      assertEquals(2025, game.season());
      assertEquals("REEFSCAPE", game.gameName());
      assertEquals(15, game.autoDurationSec());
      assertEquals(135, game.teleopDurationSec());

      // Should also be findable by season
      var cached = kb.getGame(2025);
      assertNotNull(cached);
      assertEquals("REEFSCAPE", cached.gameName());
    }

    @Test
    @DisplayName("returns null for invalid file")
    void returnsNullForInvalidFile() {
      var kb = GameKnowledgeBase.getInstance();
      var game = kb.loadFromFile(tempDir.resolve("nonexistent.json"));
      assertNull(game);
    }

    @Test
    @DisplayName("rejects file missing required fields (§4.1 fix)")
    void rejectsFileMissingRequiredFields() throws Exception {
      // Missing match_timing section entirely
      String json = """
          {
            "season": 2024,
            "game_name": "INCOMPLETE"
          }
          """;

      Path file = tempDir.resolve("incomplete.json");
      java.nio.file.Files.writeString(file, json);

      var kb = GameKnowledgeBase.getInstance();
      var game = kb.loadFromFile(file);
      // Should return null because validation fails
      assertNull(game, "Should reject game data with missing required fields");
    }

    @Test
    @DisplayName("rejects file missing fields within required section")
    void rejectsFileMissingSubFields() throws Exception {
      // Has match_timing but missing auto_duration_sec
      String json = """
          {
            "season": 2024,
            "game_name": "PARTIAL",
            "match_timing": {
              "teleop_duration_sec": 135,
              "total_duration_sec": 150
            },
            "field_geometry": {
              "field_length_m": 16.54,
              "field_width_m": 8.21
            }
          }
          """;

      Path file = tempDir.resolve("partial.json");
      java.nio.file.Files.writeString(file, json);

      var kb = GameKnowledgeBase.getInstance();
      var game = kb.loadFromFile(file);
      assertNull(game, "Should reject game data missing required sub-fields");
    }
  }

  @Nested
  @DisplayName("Bundled Resource Validation (§3.2 fix)")
  class BundledValidation {

    @Test
    @DisplayName("bundled 2026 data passes validation")
    void bundled2026PassesValidation() {
      var game = GameKnowledgeBase.getInstance().getGame(2026);
      assertNotNull(game, "Bundled 2026 data should load successfully");
      // validate() is called during loading — if it fails, getGame returns null
      // Verify key fields are accessible without NPE
      assertDoesNotThrow(() -> {
        game.season();
        game.gameName();
        game.autoDurationSec();
        game.teleopDurationSec();
        game.totalDurationSec();
        game.endgameDurationSec();
        game.fieldLengthM();
        game.fieldWidthM();
      });
    }
  }

  /** Reads a bundled game file directly, bypassing the singleton cache (a test may replace a season). */
  private static GameData bundled(String resource) throws Exception {
    try (var stream = GameKnowledgeBaseTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(stream, "bundled resource " + resource);
      var obj = new Gson().fromJson(
          new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
      var data = new GameData(obj);
      data.validate();
      return data;
    }
  }

  private static JsonObject rankingPointsOf(GameData game) {
    return game.scoring().getAsJsonObject("ranking_points");
  }

  private static JsonObject robotConstraints(GameData game) {
    return game.raw().getAsJsonObject("robot_constraints");
  }

  @Nested
  @DisplayName("2025 REEFSCAPE values (review 6 section 3.2)")
  class Reefscape2025 {
    private static final String FILE = "games/2025-reefscape.json";

    @Test
    @DisplayName("field is 57 ft 6-7/8 in by 26 ft 5 in (17.548 x 8.052 m), not the 2023 field")
    void fieldGeometry() throws Exception {
      var game = bundled(FILE);
      assertEquals(17.548, game.fieldLengthM(), 0.001);
      assertEquals(8.052, game.fieldWidthM(), 0.001);
      var geometry = game.raw().getAsJsonObject("field_geometry");
      assertEquals(690.875, geometry.get("field_length_in").getAsDouble(), 0.001);
      assertEquals(317.0, geometry.get("field_width_in").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("a win is 3 RP and a tie 1; AUTO, CORAL, and BARGE RPs with championship tiers; coopertition is not an RP")
    void rankingPoints() throws Exception {
      var game = bundled(FILE);
      var rp = rankingPointsOf(game);
      assertEquals(3, rp.get("win").getAsInt(), "Table 6-2: a win is 3 RP in 2025");
      assertEquals(1, rp.get("tie").getAsInt());
      assertTrue(rp.has("auto_rp"), "the AUTO RP (all robots LEAVE and 1 CORAL in AUTO) exists");
      assertFalse(rp.has("coop_rp"), "coopertition lowers the CORAL RP requirement; it is not a ranking point");
      assertFalse(rp.has("reef_rp"), "the manual calls it the CORAL RP");
      var coral = rp.getAsJsonObject("coral_rp");
      assertEquals(5, coral.get("regional_threshold").getAsInt());
      assertEquals(5, coral.get("district_championship_threshold").getAsInt());
      assertEquals(7, coral.get("championship_threshold").getAsInt(), "Team Update 21");
      assertEquals(4, coral.get("levels_required").getAsInt());
      assertEquals(3, coral.get("levels_required_with_coopertition").getAsInt());
      var barge = rp.getAsJsonObject("barge_rp");
      assertEquals(14, barge.get("regional_threshold").getAsInt());
      assertEquals(14, barge.get("district_championship_threshold").getAsInt());
      assertEquals(16, barge.get("championship_threshold").getAsInt(), "Team Update 21");
      var coop = game.scoring().getAsJsonObject("coopertition_bonus");
      assertEquals(2, coop.get("algae_per_processor").getAsInt());
      assertEquals(1, coop.get("coopertition_points").getAsInt());
    }

    @Test
    @DisplayName("match points follow Table 6-2")
    void matchPoints() throws Exception {
      var points = bundled(FILE).scoring().getAsJsonObject("match_points");
      var auto = points.getAsJsonObject("auto");
      assertEquals(3, auto.get("leave").getAsInt());
      assertEquals(3, auto.get("coral_l1").getAsInt());
      assertEquals(7, auto.get("coral_l4").getAsInt());
      var teleop = points.getAsJsonObject("teleop");
      assertEquals(2, teleop.get("coral_l1").getAsInt());
      assertEquals(5, teleop.get("coral_l4").getAsInt());
      assertEquals(6, teleop.get("algae_processor").getAsInt());
      assertEquals(4, teleop.get("algae_net").getAsInt());
      var endgame = points.getAsJsonObject("endgame");
      assertEquals(2, endgame.get("park").getAsInt());
      assertEquals(6, endgame.get("shallow_cage").getAsInt());
      assertEquals(12, endgame.get("deep_cage").getAsInt());
    }

    @Test
    @DisplayName("a MAJOR FOUL is 6 points, a MINOR FOUL 2 (Table 6-3)")
    void fouls() throws Exception {
      var fouls = bundled(FILE).scoring().getAsJsonObject("fouls");
      assertEquals(2, fouls.get("minor_foul").getAsInt());
      assertEquals(6, fouls.get("major_foul").getAsInt());
    }

    @Test
    @DisplayName("robot limits: 115 lbs, 120 in perimeter, 3 ft 6 in tall, 135 lbs with bumpers (R103, R104, R408)")
    void robotLimits() throws Exception {
      var limits = robotConstraints(bundled(FILE));
      assertEquals(115.0, limits.get("max_weight_lbs").getAsDouble(), 0.001);
      assertEquals(120.0, limits.get("max_starting_perimeter_in").getAsDouble(), 0.001);
      assertEquals(42.0, limits.get("max_starting_height_in").getAsDouble(), 0.001);
      assertEquals(135.0, limits.get("max_weight_with_bumpers_lbs").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("records the final manual revision")
    void provenance() throws Exception {
      var game = bundled(FILE);
      assertTrue(game.isBundled());
      assertTrue(game.manualVersion().orElse("").startsWith("TU21"), game.manualVersion().toString());
      assertTrue(game.manualUrl().orElse("").contains("frc2025"));
    }
  }

  @Nested
  @DisplayName("2024 CRESCENDO values (review 6 section 3.2)")
  class Crescendo2024 {
    private static final String FILE = "games/2024-crescendo.json";

    @Test
    @DisplayName("field is 54 ft 3-1/4 in by 26 ft 11-1/4 in (16.542 x 8.211 m)")
    void fieldGeometry() throws Exception {
      var game = bundled(FILE);
      assertEquals(16.542, game.fieldLengthM(), 0.001);
      assertEquals(8.211, game.fieldWidthM(), 0.001);
      var geometry = game.raw().getAsJsonObject("field_geometry");
      assertEquals(651.25, geometry.get("field_length_in").getAsDouble(), 0.001);
      assertEquals(323.25, geometry.get("field_width_in").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("MELODY thresholds differ by event tier and with the Coopertition Bonus; ENSEMBLE needs 2 ONSTAGE robots")
    void rankingPoints() throws Exception {
      var rp = rankingPointsOf(bundled(FILE));
      assertEquals(2, rp.get("win").getAsInt(), "a win was 2 RP in 2024");
      assertEquals(1, rp.get("tie").getAsInt());
      var melody = rp.getAsJsonObject("melody_rp");
      assertEquals(18, melody.get("regional_threshold").getAsInt());
      assertEquals(15, melody.get("regional_threshold_with_coopertition").getAsInt());
      assertEquals(21, melody.get("district_championship_threshold").getAsInt());
      assertEquals(18, melody.get("district_championship_threshold_with_coopertition").getAsInt());
      assertEquals(25, melody.get("championship_threshold").getAsInt());
      assertEquals(21, melody.get("championship_threshold_with_coopertition").getAsInt());
      var ensemble = rp.getAsJsonObject("ensemble_rp");
      assertEquals(10, ensemble.get("regional_threshold").getAsInt());
      assertEquals(2, ensemble.get("min_onstage_robots").getAsInt());
    }

    @Test
    @DisplayName("fouls 2 and 5; robot limits 125.5 lbs (Team Update 21), 120 in, 48 in")
    void foulsAndRobotLimits() throws Exception {
      var game = bundled(FILE);
      var fouls = game.scoring().getAsJsonObject("fouls");
      assertEquals(2, fouls.get("minor_foul").getAsInt());
      assertEquals(5, fouls.get("major_foul").getAsInt());
      var limits = robotConstraints(game);
      assertEquals(125.5, limits.get("max_weight_lbs").getAsDouble(), 0.001);
      assertEquals(120.0, limits.get("max_starting_perimeter_in").getAsDouble(), 0.001);
      assertEquals(48.0, limits.get("max_starting_height_in").getAsDouble(), 0.001);
      assertTrue(game.manualVersion().orElse("").startsWith("TU21"));
    }
  }

  @Nested
  @DisplayName("2026 REBUILT values re-checked against TU22")
  class Rebuilt2026 {
    private static final String FILE = "games/2026-rebuilt.json";

    @Test
    @DisplayName("manual version TU22, 24 FUEL preloaded per alliance, tiered RP thresholds, 135 lb with bumpers")
    void recheckedValues() throws Exception {
      var game = bundled(FILE);
      assertTrue(game.manualVersion().orElse("").startsWith("TU22"), game.manualVersion().toString());
      var fuel = game.gamePieces().get(0).getAsJsonObject();
      assertEquals(8, fuel.get("preload_max_per_robot").getAsInt());
      assertEquals(24, fuel.get("preload_max_per_alliance").getAsInt(),
          "8 per robot and 3 robots; the manual's 48 is both alliances");
      var rp = rankingPointsOf(game);
      assertEquals(3, rp.get("win").getAsInt());
      var energized = rp.getAsJsonObject("energized_rp");
      assertEquals(100, energized.get("regional_threshold").getAsInt());
      assertEquals(240, energized.get("district_championship_threshold").getAsInt());
      assertEquals(360, energized.get("championship_threshold").getAsInt());
      var supercharged = rp.getAsJsonObject("supercharged_rp");
      assertEquals(360, supercharged.get("district_championship_threshold").getAsInt());
      assertEquals(500, supercharged.get("championship_threshold").getAsInt());
      assertEquals(50, rp.getAsJsonObject("traversal_rp").get("championship_threshold").getAsInt());
      var fouls = game.scoring().getAsJsonObject("fouls");
      assertEquals(5, fouls.get("minor_foul").getAsInt());
      assertEquals(15, fouls.get("major_foul").getAsInt());
      var limits = robotConstraints(game);
      assertEquals(110.0, limits.get("max_starting_perimeter_in").getAsDouble(), 0.001);
      assertEquals(30.0, limits.get("max_starting_height_in").getAsDouble(), 0.001);
      assertEquals(115.0, limits.get("max_weight_lbs").getAsDouble(), 0.001);
      assertEquals(135.0, limits.get("max_weight_with_bumpers_lbs").getAsDouble(), 0.001);
    }
  }

  @Nested
  @DisplayName("get_game_info provenance (review 6 sections 3.2 and 9.4)")
  class GetGameInfoProvenance {
    @TempDir Path tempDir;

    private JsonObject gameInfo(int season) throws Exception {
      var registry = new ToolRegistry();
      FrcDomainTools.registerAll(registry);
      var args = new JsonObject();
      args.addProperty("season", season);
      return registry.getTool("get_game_info").execute(args).getAsJsonObject();
    }

    @Test
    @DisplayName("a bundled season is labelled a knowledge base with its manual version")
    void bundledSeason() throws Exception {
      var result = gameInfo(2026);
      assertTrue(result.get("success").getAsBoolean());
      assertEquals("bundled knowledge base, not the log; verify against the current manual",
          result.get("source").getAsString());
      assertTrue(result.get("manual_version").getAsString().startsWith("TU22"));
      assertTrue(result.get("manual_url").getAsString().contains("frc2026"));
      var basis = result.get("basis").getAsString();
      assertTrue(basis.contains("match_timing") && basis.contains("scoring")
          && basis.contains("game manual"), basis);
      assertTrue(result.has("robot_constraints"));
      assertEquals(115.0,
          result.getAsJsonObject("robot_constraints").get("max_weight_lbs").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("a user-provided file is labelled with its path; an unrecorded manual version is unknown")
    void userProvidedFile() throws Exception {
      // Season 2001: no bundled season and no fixture log is affected by the cached file.
      String json = """
          {
            "season": 2001,
            "game_name": "USER_FILE",
            "match_timing": {
              "auto_duration_sec": 15,
              "teleop_duration_sec": 120,
              "total_duration_sec": 135,
              "endgame_duration_sec": 20,
              "endgame_start_before_end_sec": 20,
              "auto_to_teleop_delay_sec": 0
            },
            "field_geometry": {"field_length_m": 16.46, "field_width_m": 8.23},
            "scoring": {}
          }
          """;
      Path file = tempDir.resolve("2001-user.json");
      Files.writeString(file, json);
      var loaded = GameKnowledgeBase.getInstance().loadFromFile(file);
      assertNotNull(loaded);
      assertFalse(loaded.isBundled());
      assertEquals(file.toAbsolutePath().toString(), loaded.origin());
      assertTrue(loaded.manualVersion().isEmpty());

      var result = gameInfo(2001);
      assertTrue(result.get("success").getAsBoolean());
      var source = result.get("source").getAsString();
      assertTrue(source.startsWith("user-provided game file "), source);
      assertTrue(source.contains(file.toAbsolutePath().toString()), source);
      assertTrue(source.contains("not the log"), source);
      assertEquals("unknown", result.get("manual_version").getAsString());
      assertFalse(result.has("manual_url"));
      assertTrue(result.has("basis"));
    }
  }

  @Nested
  @DisplayName("Available Seasons")
  class AvailableSeasons {

    @Test
    @DisplayName("includes bundled seasons")
    void includesBundled() {
      var kb = GameKnowledgeBase.getInstance();
      int[] seasons = kb.availableSeasons();

      boolean has2026 = false;
      for (int s : seasons) {
        if (s == 2026) has2026 = true;
      }
      assertTrue(has2026, "Available seasons should include bundled 2026");
    }
  }
}
