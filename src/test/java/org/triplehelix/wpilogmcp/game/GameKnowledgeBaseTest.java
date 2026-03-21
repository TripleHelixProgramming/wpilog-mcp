package org.triplehelix.wpilogmcp.game;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
