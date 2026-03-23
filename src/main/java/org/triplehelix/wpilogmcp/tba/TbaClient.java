package org.triplehelix.wpilogmcp.tba;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for The Blue Alliance (TBA) API v3.
 */
public class TbaClient {
  private static final Logger logger = LoggerFactory.getLogger(TbaClient.class);

  /** TBA API v3 base URL. */
  private static final String TBA_BASE_URL = "https://www.thebluealliance.com/api/v3";

  /** HTTP request timeout. */
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** Maximum entries per cache map to prevent unbounded memory growth. */
  private static final int MAX_CACHE_SIZE = 200;

  /** Singleton instance. */
  private static TbaClient instance;

  /** HTTP client for API requests. */
  private final HttpClient httpClient;

  /** JSON parser. */
  private final Gson gson;

  /** Cache for event data. */
  private final Map<String, CachedData<JsonObject>> eventCache;

  /** Cache for match data. */
  private final Map<String, CachedData<JsonObject>> matchCache;

  /** Cache for event matches list. */
  private final Map<String, CachedData<JsonArray>> eventMatchesCache;

  /** API key for TBA access. */
  private String apiKey;

  /** Private constructor for singleton pattern. */
  private TbaClient() {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .build();
    this.gson = new Gson();
    this.eventCache = new ConcurrentHashMap<>();
    this.matchCache = new ConcurrentHashMap<>();
    this.eventMatchesCache = new ConcurrentHashMap<>();
  }

  /**
   * Gets the singleton instance of TbaClient.
   *
   * @return The singleton instance
   */
  public static synchronized TbaClient getInstance() {
    if (instance == null) {
      instance = new TbaClient();
    }
    return instance;
  }

  /**
   * Configures the TBA client with an API key.
   *
   * @param apiKey TBA API key
   */
  public void configure(String apiKey) {
    this.apiKey = apiKey;
    if (apiKey != null && !apiKey.isEmpty()) {
      logger.debug("TbaClient configured with API key");
    } else {
      logger.debug("TbaClient API key cleared");
    }
  }

  /**
   * Checks if TBA features are available.
   *
   * @return true if TBA API is available
   */
  public boolean isAvailable() {
    return apiKey != null && !apiKey.isEmpty();
  }

  /**
   * Gets event information from TBA.
   */
  public Optional<JsonObject> getEvent(int year, String eventCode) {
    if (!isAvailable()) {
      return Optional.empty();
    }

    var eventKey = year + eventCode.toLowerCase();

    // Check cache first
    var cached = eventCache.get(eventKey);
    if (cached != null && !cached.isExpired()) {
      logger.trace("TBA cache hit for event: {}", eventKey);
      return Optional.ofNullable(cached.data);
    }

    logger.debug("TBA cache miss for event: {}. Fetching from API...", eventKey);
    try {
      var endpoint = "/event/" + eventKey;
      var data = fetchJson(endpoint, JsonObject.class);
      eventCache.put(eventKey, new CachedData<>(data));
      evictStaleEntries(eventCache);
      return Optional.ofNullable(data);
    } catch (Exception e) {
      logger.warn("TBA API error for event {}: {}", eventKey, e.getMessage());
      eventCache.put(eventKey, new CachedData<>(null));
      return Optional.empty();
    }
  }

  /**
   * Gets match information from TBA.
   */
  public Optional<JsonObject> getMatch(int year, String eventCode, String matchType, int matchNumber) {
    if (!isAvailable()) {
      return Optional.empty();
    }

    var matchKey = buildMatchKey(year, eventCode.toLowerCase(), matchType, matchNumber);
    if (matchKey == null) {
      return Optional.empty();
    }

    // Check cache first
    var cached = matchCache.get(matchKey);
    if (cached != null && !cached.isExpired()) {
      logger.trace("TBA cache hit for match: {}", matchKey);
      return Optional.ofNullable(cached.data);
    }

    logger.debug("TBA cache miss for match: {}. Fetching from API...", matchKey);
    try {
      var endpoint = "/match/" + matchKey;
      var data = fetchJson(endpoint, JsonObject.class);
      matchCache.put(matchKey, new CachedData<>(data));
      evictStaleEntries(matchCache);
      return Optional.ofNullable(data);
    } catch (Exception e) {
      logger.warn("TBA API error for match {}: {}", matchKey, e.getMessage());
      matchCache.put(matchKey, new CachedData<>(null));
      return Optional.empty();
    }
  }

  /**
   * Gets all matches for an event from TBA.
   */
  public Optional<JsonArray> getEventMatches(int year, String eventCode) {
    if (!isAvailable()) {
      return Optional.empty();
    }

    var eventKey = year + eventCode.toLowerCase();

    // Check cache first
    var cached = eventMatchesCache.get(eventKey);
    if (cached != null && !cached.isExpired()) {
      logger.trace("TBA cache hit for event matches: {}", eventKey);
      return Optional.ofNullable(cached.data);
    }

    logger.debug("TBA cache miss for event matches: {}. Fetching from API...", eventKey);
    try {
      var endpoint = "/event/" + eventKey + "/matches";
      var data = fetchJson(endpoint, JsonArray.class);
      eventMatchesCache.put(eventKey, new CachedData<>(data));
      evictStaleEntries(eventMatchesCache);
      return Optional.ofNullable(data);
    } catch (Exception e) {
      logger.warn("TBA API error for event matches {}: {}", eventKey, e.getMessage());
      eventMatchesCache.put(eventKey, new CachedData<>(null));
      return Optional.empty();
    }
  }

  /**
   * Finds a specific team's match result.
   * For generic "Elimination" match types, uses smart matching based on match number
   * and timestamp to find the correct TBA match.
   */
  public Optional<TeamMatchResult> getTeamMatchResult(
      int year, String eventCode, String matchType, int matchNumber, int teamNumber) {
    return getTeamMatchResult(year, eventCode, matchType, matchNumber, teamNumber, null);
  }

  /**
   * Finds a specific team's match result with optional timestamp hint.
   * For generic "Elimination" match types, uses smart matching based on match number
   * and timestamp to find the correct TBA match.
   *
   * @param logFileTimestampMs Optional log file timestamp (epoch millis) to help match
   *                           elimination matches by time proximity
   */
  public Optional<TeamMatchResult> getTeamMatchResult(
      int year, String eventCode, String matchType, int matchNumber, int teamNumber,
      Long logFileTimestampMs) {

    // First try direct lookup (works for Qualification, Semifinal, Final, etc.)
    var matchOpt = getMatch(year, eventCode, matchType, matchNumber);
    if (matchOpt.isPresent()) {
      return extractTeamResult(matchOpt.get(), teamNumber);
    }

    // If match type is generic "Elimination", try smart matching
    if (matchType != null && isGenericElimination(matchType)) {
      logger.debug("Attempting smart elimination match lookup for team {} at {}{}, match #{}",
          teamNumber, year, eventCode, matchNumber);
      return findEliminationMatch(year, eventCode, matchNumber, teamNumber, logFileTimestampMs);
    }

    return Optional.empty();
  }

  /**
   * Checks if match type is a generic "Elimination" that needs smart matching.
   */
  private boolean isGenericElimination(String matchType) {
    var lower = matchType.toLowerCase();
    return (lower.contains("elimination") || lower.contains("elim"))
        && !lower.contains("semi")
        && !lower.contains("quarter")
        && !lower.contains("final");
  }

  /**
   * Smart lookup for elimination matches when only "Elimination #N" is known.
   * Fetches all event matches, filters to elimination matches for the team,
   * and finds the best match based on match number and timestamp proximity.
   */
  private Optional<TeamMatchResult> findEliminationMatch(
      int year, String eventCode, int matchNumber, int teamNumber, Long logFileTimestampMs) {

    var allMatchesOpt = getEventMatches(year, eventCode);
    if (allMatchesOpt.isEmpty()) {
      logger.debug("No event matches found for {}{}", year, eventCode);
      return Optional.empty();
    }

    var teamKey = "frc" + teamNumber;
    var elimLevels = Set.of("qf", "sf", "f");
    var candidateMatches = new ArrayList<JsonObject>();

    // Filter to elimination matches where team participated
    for (var elem : allMatchesOpt.get()) {
      var match = elem.getAsJsonObject();

      // Check if it's an elimination match
      var compLevel = match.has("comp_level") ? match.get("comp_level").getAsString() : null;
      if (compLevel == null || !elimLevels.contains(compLevel)) {
        continue;
      }

      // Check if team is in this match
      if (!teamInMatch(match, teamKey)) {
        continue;
      }

      candidateMatches.add(match);
    }

    if (candidateMatches.isEmpty()) {
      logger.debug("No elimination matches found for team {} at {}{}", teamNumber, year, eventCode);
      return Optional.empty();
    }

    logger.debug("Found {} elimination matches for team {} at {}{}",
        candidateMatches.size(), teamNumber, year, eventCode);

    // Try to find by match number first (most reliable if the numbering is consistent)
    // Match number in logs often corresponds to the overall elimination match sequence
    var byNumber = findMatchByEliminationNumber(candidateMatches, matchNumber);
    if (byNumber.isPresent()) {
      logger.info("Found elimination match by number {} for team {} at {}{}",
          matchNumber, teamNumber, year, eventCode);
      return extractTeamResult(byNumber.get(), teamNumber);
    }

    // Fall back to timestamp matching if available
    if (logFileTimestampMs != null) {
      var byTime = findMatchByTimestamp(candidateMatches, logFileTimestampMs);
      if (byTime.isPresent()) {
        logger.info("Found elimination match by timestamp for team {} at {}{}",
            teamNumber, year, eventCode);
        return extractTeamResult(byTime.get(), teamNumber);
      }
    }

    logger.debug("Could not match elimination #{} for team {} at {}{} "
        + "(candidates: {}, logTimestamp: {})",
        matchNumber, teamNumber, year, eventCode, candidateMatches.size(),
        logFileTimestampMs != null ? Instant.ofEpochMilli(logFileTimestampMs) : "none");
    return Optional.empty();
  }

  /**
   * Checks if a team participated in a match.
   */
  private boolean teamInMatch(JsonObject match, String teamKey) {
    var alliances = match.getAsJsonObject("alliances");
    if (alliances == null) return false;

    for (var alliance : new String[]{"red", "blue"}) {
      var allianceData = alliances.getAsJsonObject(alliance);
      if (allianceData == null) continue;

      var teamKeys = allianceData.getAsJsonArray("team_keys");
      if (teamKeys == null) continue;

      for (var t : teamKeys) {
        if (teamKey.equals(t.getAsString())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Tries to find elimination match by match number.
   * The elimination match number often corresponds to the play order:
   * e.g., "Elimination 9" might be the 9th elimination match played.
   */
  private Optional<JsonObject> findMatchByEliminationNumber(
      List<JsonObject> candidates, int targetNumber) {

    // Sort candidates by actual time to establish play order
    var sorted = new ArrayList<>(candidates);
    sorted.sort(Comparator.comparingLong(m -> {
      if (m.has("actual_time") && !m.get("actual_time").isJsonNull()) {
        return m.get("actual_time").getAsLong();
      }
      if (m.has("time") && !m.get("time").isJsonNull()) {
        return m.get("time").getAsLong();
      }
      return Long.MAX_VALUE;
    }));

    // The target match number is 1-indexed (Elimination 1, Elimination 2, etc.)
    // This represents the Nth elimination match the team played
    if (targetNumber >= 1 && targetNumber <= sorted.size()) {
      return Optional.of(sorted.get(targetNumber - 1));
    }

    return Optional.empty();
  }

  /**
   * Finds the elimination match closest in time to the log file timestamp.
   * Allows up to 2 hour window for reasonable matching.
   */
  private Optional<JsonObject> findMatchByTimestamp(List<JsonObject> candidates, long logTimestampMs) {
    long logTimestampSec = logTimestampMs / 1000;
    long maxDriftSeconds = 2 * 60 * 60; // 2 hours

    var bestMatch = (JsonObject) null;
    long bestDiff = Long.MAX_VALUE;

    for (var match : candidates) {
      var matchTime = (Long) null;
      if (match.has("actual_time") && !match.get("actual_time").isJsonNull()) {
        matchTime = match.get("actual_time").getAsLong();
      } else if (match.has("time") && !match.get("time").isJsonNull()) {
        matchTime = match.get("time").getAsLong();
      }

      if (matchTime != null) {
        long diff = Math.abs(matchTime - logTimestampSec);
        if (diff < bestDiff && diff < maxDriftSeconds) {
          bestDiff = diff;
          bestMatch = match;
        }
      }
    }

    if (bestMatch != null) {
      logger.debug("Best timestamp match: {} seconds difference", bestDiff);
    }
    return Optional.ofNullable(bestMatch);
  }

  private Optional<TeamMatchResult> extractTeamResult(JsonObject match, int teamNumber) {
    var teamKey = "frc" + teamNumber;

    var alliances = match.getAsJsonObject("alliances");
    if (alliances == null) {
      return Optional.empty();
    }

    for (var alliance : new String[]{"red", "blue"}) {
      var allianceData = alliances.getAsJsonObject(alliance);
      if (allianceData == null) continue;

      var teamKeys = allianceData.getAsJsonArray("team_keys");
      if (teamKeys == null) continue;

      for (var teamElem : teamKeys) {
        if (teamKey.equals(teamElem.getAsString())) {
          int score = allianceData.has("score") ? allianceData.get("score").getAsInt() : -1;
          var winningAlliance = match.has("winning_alliance")
              ? match.get("winning_alliance").getAsString()
              : null;

          var won = (Boolean) null;
          if (winningAlliance != null && !winningAlliance.isEmpty()) {
            won = alliance.equals(winningAlliance);
          }

          var actualTime = match.has("actual_time") && !match.get("actual_time").isJsonNull()
              ? match.get("actual_time").getAsLong()
              : null;
          var scheduledTime = match.has("time") && !match.get("time").isJsonNull()
              ? match.get("time").getAsLong()
              : null;

          return Optional.of(new TeamMatchResult(
              alliance,
              score,
              won,
              actualTime,
              scheduledTime
          ));
        }
      }
    }

    return Optional.empty();
  }

  private String buildMatchKey(int year, String eventCode, String matchType, int matchNumber) {
    var compLevel = mapMatchTypeToCompLevel(matchType);
    if (compLevel == null) {
      logger.info("Unsupported match type for TBA lookup: '{}' (year={}, event={}, match={})",
          matchType, year, eventCode, matchNumber);
      return null;
    }

    var eventKey = year + eventCode;

    var matchKey = (String) null;
    if ("qm".equals(compLevel)) {
      matchKey = eventKey + "_qm" + matchNumber;
    } else if ("sf".equals(compLevel)) {
      // Since 2023, FRC uses double-elimination where semifinal keys are sequential
      matchKey = eventKey + "_sf" + matchNumber + "m1";
    } else if ("f".equals(compLevel)) {
      matchKey = eventKey + "_f1m" + matchNumber;
    } else {
      matchKey = eventKey + "_" + compLevel + "1m" + matchNumber;
    }

    logger.debug("Built TBA match key: {} (from type='{}', number={})", matchKey, matchType, matchNumber);
    return matchKey;
  }

  private String mapMatchTypeToCompLevel(String matchType) {
    if (matchType == null) {
      return null;
    }

    var lower = matchType.toLowerCase();

    if (lower.contains("qualification") || lower.contains("qual")) {
      return "qm";
    }
    if (lower.contains("semifinal") || lower.contains("semi")) {
      return "sf";
    }
    if (lower.contains("final") && !lower.contains("semi")) {
      return "f";
    }
    if (lower.contains("quarterfinal") || lower.contains("quarter")) {
      return "qf";
    }
    if (lower.contains("elimination") || lower.contains("elim")) {
      return null;
    }
    if (lower.contains("practice")) {
      return null;
    }

    return null;
  }

  private <T> T fetchJson(String endpoint, Class<T> type) throws IOException {
    var request = HttpRequest.newBuilder()
        .uri(URI.create(TBA_BASE_URL + endpoint))
        .header("X-TBA-Auth-Key", apiKey)
        .header("Accept", "application/json")
        .timeout(TIMEOUT)
        .GET()
        .build();

    try {
      long startTime = System.currentTimeMillis();
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      long duration = System.currentTimeMillis() - startTime;

      if (response.statusCode() != 200) {
        logger.warn("TBA API returned status {} for endpoint {} in {}ms", 
            response.statusCode(), endpoint, duration);
        throw new IOException("TBA API returned status " + response.statusCode());
      }

      logger.trace("TBA API request successful: {} in {}ms", endpoint, duration);
      return gson.fromJson(response.body(), type);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Request interrupted", e);
    }
  }

  /**
   * Evicts expired entries and trims to MAX_CACHE_SIZE if a cache exceeds its limit.
   * Called periodically to prevent unbounded memory growth in long-running servers.
   */
  private <T> void evictStaleEntries(Map<String, CachedData<T>> cacheMap) {
    // Remove expired entries first
    cacheMap.entrySet().removeIf(e -> e.getValue().isExpired());
    // If still over limit, remove oldest entries
    while (cacheMap.size() > MAX_CACHE_SIZE) {
      var oldest = cacheMap.entrySet().stream()
          .min((a, b) -> a.getValue().cachedAt.compareTo(b.getValue().cachedAt));
      oldest.ifPresent(e -> cacheMap.remove(e.getKey()));
    }
  }

  /**
   * Clears all cached data.
   */
  public void clearCache() {
    logger.debug("Clearing TBA cache");
    eventCache.clear();
    matchCache.clear();
    eventMatchesCache.clear();
  }

  /**
   * Gets cache statistics for diagnostics.
   */
  public Map<String, Integer> getCacheStats() {
    return Map.of(
        "events", eventCache.size(),
        "matches", matchCache.size(),
        "eventMatches", eventMatchesCache.size()
    );
  }

  /** Result of a team's performance in a specific match. */
  public record TeamMatchResult(
      String alliance,
      int score,
      Boolean won,
      Long actualTimeSeconds,
      Long scheduledTimeSeconds
  ) {
    public Long getMatchTimeSeconds() {
      return actualTimeSeconds != null ? actualTimeSeconds : scheduledTimeSeconds;
    }

    public Instant getMatchTime() {
      var seconds = getMatchTimeSeconds();
      return seconds != null ? Instant.ofEpochSecond(seconds) : null;
    }
  }

  /** Wrapper for cached data with expiration tracking. */
  private static class CachedData<T> {
    final T data;
    final Instant cachedAt;
    private static final Duration CACHE_DURATION = Duration.ofHours(24);
    private static final Duration FAILURE_CACHE_DURATION = Duration.ofMinutes(5);

    CachedData(T data) {
      this.data = data;
      this.cachedAt = Instant.now();
    }

    boolean isExpired() {
      var age = Duration.between(cachedAt, Instant.now());
      var maxAge = data == null ? FAILURE_CACHE_DURATION : CACHE_DURATION;
      return age.compareTo(maxAge) > 0;
    }
  }
}
