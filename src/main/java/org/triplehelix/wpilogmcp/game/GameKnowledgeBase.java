package org.triplehelix.wpilogmcp.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides year-specific FRC game knowledge for contextual log analysis.
 *
 * <p>Game data is loaded from JSON files bundled as resources ({@code games/YYYY-name.json})
 * or from user-provided files. Data includes match timing, scoring values, field geometry,
 * and analysis hints that enable tools and LLMs to interpret log data in game context.
 *
 * <p>Resolution order for a given season:
 * <ol>
 *   <li>User-provided file via {@link #loadFromFile(Path)}</li>
 *   <li>Bundled resource ({@code games/YYYY-*.json} on classpath)</li>
 * </ol>
 *
 * @since 0.5.0
 */
public class GameKnowledgeBase {
  private static final Logger logger = LoggerFactory.getLogger(GameKnowledgeBase.class);
  private static final Gson GSON = new Gson();

  /** Singleton instance. */
  private static final GameKnowledgeBase INSTANCE = new GameKnowledgeBase();

  /** Known bundled game files (season year → resource path). */
  private static final Map<Integer, String> BUNDLED_GAMES = Map.of(
      2024, "games/2024-crescendo.json",
      2025, "games/2025-reefscape.json",
      2026, "games/2026-rebuilt.json"
  );

  /** Loaded game data cache (season year → parsed JSON). */
  private final Map<Integer, GameData> cache = new ConcurrentHashMap<>();

  private GameKnowledgeBase() {}

  public static GameKnowledgeBase getInstance() {
    return INSTANCE;
  }

  /**
   * Gets game data for a specific season.
   *
   * @param season The FRC season year (e.g., 2026)
   * @return The game data, or null if no data is available for that season
   */
  public GameData getGame(int season) {
    return cache.computeIfAbsent(season, this::loadGame);
  }

  /**
   * Gets game data for the current season (based on system clock).
   * FRC seasons start in January, so the current year is used.
   *
   * @return The current season's game data, or null if unavailable
   */
  public GameData getCurrentGame() {
    int year = java.time.Year.now().getValue();
    return getGame(year);
  }

  /**
   * Loads game data from an external file and caches it.
   *
   * @param file Path to a game JSON file
   * @return The loaded game data, or null if loading failed
   */
  public GameData loadFromFile(Path file) {
    try {
      String json = Files.readString(file, StandardCharsets.UTF_8);
      JsonObject obj = GSON.fromJson(json, JsonObject.class);
      var data = new GameData(obj);
      data.validate();
      cache.put(data.season(), data);
      logger.info("Loaded game data from file: {} (season {})", file, data.season());
      return data;
    } catch (Exception e) {
      logger.warn("Failed to load game data from {}: {}", file, e.getMessage());
      return null;
    }
  }

  /**
   * Lists all available seasons (bundled + loaded).
   *
   * @return Array of available season years
   */
  public int[] availableSeasons() {
    var seasons = new java.util.TreeSet<Integer>();
    seasons.addAll(BUNDLED_GAMES.keySet());
    seasons.addAll(cache.keySet());
    return seasons.stream().mapToInt(Integer::intValue).toArray();
  }

  private GameData loadGame(int season) {
    String resourcePath = BUNDLED_GAMES.get(season);
    if (resourcePath == null) {
      logger.debug("No bundled game data for season {}", season);
      return null;
    }

    try (var stream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (stream == null) {
        logger.warn("Bundled game resource not found: {}", resourcePath);
        return null;
      }
      var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
      JsonObject obj = GSON.fromJson(reader, JsonObject.class);
      var data = new GameData(obj);
      data.validate();
      logger.info("Loaded bundled game data: {} {}", data.gameName(), data.season());
      return data;
    } catch (Exception e) {
      // Catch both IOException and JsonSyntaxException/IllegalArgumentException
      logger.error("Failed to load bundled game data for {}: {}", season, e.getMessage());
      return null;
    }
  }
}
