package org.triplehelix.wpilogmcp.game;

import com.google.gson.JsonObject;

/**
 * Parsed game data for a specific FRC season.
 *
 * <p>Wraps the raw JSON and provides typed accessors for commonly used fields.
 * The full JSON is available via {@link #raw()} for tools that need game-specific
 * details not covered by the typed accessors.
 *
 * @since 0.5.0
 */
public class GameData {
  private final JsonObject raw;

  public GameData(JsonObject raw) {
    this.raw = raw;
  }

  /** The full raw JSON object. */
  public JsonObject raw() { return raw; }

  /** The FRC season year (e.g., 2026). */
  public int season() { return raw.get("season").getAsInt(); }

  /** The game name (e.g., "REBUILT"). */
  public String gameName() { return raw.get("game_name").getAsString(); }

  // ==================== Match Timing ====================

  /** Autonomous period duration in seconds. */
  public int autoDurationSec() {
    return raw.getAsJsonObject("match_timing").get("auto_duration_sec").getAsInt();
  }

  /** Teleop period duration in seconds. */
  public int teleopDurationSec() {
    return raw.getAsJsonObject("match_timing").get("teleop_duration_sec").getAsInt();
  }

  /** Total match duration in seconds. */
  public int totalDurationSec() {
    return raw.getAsJsonObject("match_timing").get("total_duration_sec").getAsInt();
  }

  /** Endgame duration in seconds. */
  public int endgameDurationSec() {
    return raw.getAsJsonObject("match_timing").get("endgame_duration_sec").getAsInt();
  }

  /** Seconds before match end that endgame starts. */
  public int endgameStartBeforeEndSec() {
    return raw.getAsJsonObject("match_timing").get("endgame_start_before_end_sec").getAsInt();
  }

  /** Delay between auto and teleop in seconds. */
  public int autoToTeleopDelaySec() {
    return raw.getAsJsonObject("match_timing").get("auto_to_teleop_delay_sec").getAsInt();
  }

  // ==================== Field Geometry ====================

  /** Field length in meters. */
  public double fieldLengthM() {
    return raw.getAsJsonObject("field_geometry").get("field_length_m").getAsDouble();
  }

  /** Field width in meters. */
  public double fieldWidthM() {
    return raw.getAsJsonObject("field_geometry").get("field_width_m").getAsDouble();
  }

  // ==================== Scoring ====================

  /** Gets the scoring section as raw JSON for game-specific access. */
  public JsonObject scoring() {
    return raw.getAsJsonObject("scoring");
  }

  // ==================== Analysis Hints ====================

  /** Gets the analysis hints section. */
  public JsonObject analysisHints() {
    return raw.has("analysis_hints") ? raw.getAsJsonObject("analysis_hints") : null;
  }

  // ==================== Game Pieces ====================

  /** Gets the game pieces array as raw JSON. */
  public com.google.gson.JsonArray gamePieces() {
    return raw.has("game_pieces") ? raw.getAsJsonArray("game_pieces") : null;
  }

  // ==================== Validation ====================

  /**
   * Validates that all required fields are present in the game data.
   * Call this after loading from a user-provided file to catch errors early.
   *
   * @throws IllegalArgumentException if a required field is missing
   */
  public void validate() {
    requireField("season");
    requireField("game_name");
    requireObject("match_timing",
        "auto_duration_sec", "teleop_duration_sec", "total_duration_sec",
        "endgame_duration_sec", "endgame_start_before_end_sec", "auto_to_teleop_delay_sec");
    requireObject("field_geometry", "field_length_m", "field_width_m");
  }

  private void requireField(String name) {
    if (!raw.has(name) || raw.get(name).isJsonNull()) {
      throw new IllegalArgumentException("Missing required game data field: " + name);
    }
  }

  private void requireObject(String objectName, String... fields) {
    if (!raw.has(objectName) || !raw.get(objectName).isJsonObject()) {
      throw new IllegalArgumentException("Missing required game data section: " + objectName);
    }
    var obj = raw.getAsJsonObject(objectName);
    for (String field : fields) {
      if (!obj.has(field) || obj.get(field).isJsonNull()) {
        throw new IllegalArgumentException(
            "Missing required field '" + field + "' in game data section '" + objectName + "'");
      }
    }
  }
}
