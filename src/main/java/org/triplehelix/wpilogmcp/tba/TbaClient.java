/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
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
import org.triplehelix.wpilogmcp.config.ClientLeases;

/**
 * Client for The Blue Alliance (TBA) API v3.
 */
public class TbaClient {
  private static final Logger logger = LoggerFactory.getLogger(TbaClient.class);

  /** TBA API v3 base URL. */
  private static final String TBA_BASE_URL = "https://www.thebluealliance.com/api/v3";

  /** HTTP request timeout. */
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** lookup_method: a key built from the match type and number. */
  public static final String LOOKUP_DIRECT = "direct";
  /** lookup_method: "Elimination N" read as double-elimination bracket match N (TBA's sfNm1). */
  public static final String LOOKUP_BRACKET = "double_elimination_bracket";
  /** lookup_method: the team's playoff match nearest the log's time. */
  public static final String LOOKUP_NEAREST_TIME = "nearest_time";
  /** lookup_method: playoff match number N in the order the team played (a pre-2023 heuristic). */
  public static final String LOOKUP_PLAY_ORDER = "play_order";
  /** The first season of the double-elimination bracket, whose matches TBA keys sf1m1-sf13m1. */
  public static final int DOUBLE_ELIMINATION_FIRST_YEAR = 2023;
  /** Matches of the double-elimination bracket before the finals. */
  public static final int DOUBLE_ELIMINATION_BRACKET_MATCHES = 13;

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

  /** API key for TBA access. Volatile for safe publication to HTTP handler threads. */
  private volatile String apiKey;

  /** The API's base URL (the public TBA API unless a test or mirror sets another). */
  private volatile String baseUrl = TBA_BASE_URL;

  /** A non-200 HTTP status from TBA. */
  static final class HttpStatusException extends IOException {
    final int status;

    HttpStatusException(int status) {
      super("TBA API returned status " + status);
      this.status = status;
    }
  }

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
   * Points the client at another base URL (a recorded-response server in tests, or a mirror);
   * null restores the public TBA API. Clears the caches.
   *
   * @param url The base URL, e.g. {@code http://localhost:8080/api/v3}, or null
   */
  public void setBaseUrl(String url) {
    this.baseUrl = url == null ? TBA_BASE_URL : url;
    clearCache();
  }

  /** The exception for a request TBA could not answer (not a 404). */
  private static TbaUnavailableException unavailable(Exception e) {
    if (e instanceof HttpStatusException h) {
      return new TbaUnavailableException("The Blue Alliance returned HTTP " + h.status
          + (h.status == 401 ? " (the API key was rejected)" : "") + ".", e);
    }
    return new TbaUnavailableException("The Blue Alliance could not be reached: "
        + e.getMessage(), e);
  }

  /**
   * Checks if TBA features are available.
   *
   * @return true if TBA API is available
   */
  public boolean isAvailable() {
    var key = ClientLeases.getInstance().keyOr(apiKey);
    return key != null && !key.isEmpty();
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
    } catch (HttpStatusException e) {
      if (e.status == 404) {
        eventCache.put(eventKey, new CachedData<>(null));
        return Optional.empty();
      }
      throw unavailable(e);
    } catch (Exception e) {
      throw unavailable(e);
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
    } catch (HttpStatusException e) {
      if (e.status == 404) {
        matchCache.put(matchKey, new CachedData<>(null));
        return Optional.empty();
      }
      throw unavailable(e);
    } catch (Exception e) {
      throw unavailable(e);
    }
  }

  /**
   * Gets all events for a year from TBA. Results are cached.
   *
   * @param year The competition year
   * @return Array of event objects, or empty if unavailable
   */
  public Optional<JsonArray> getEventsForYear(int year) {
    if (!isAvailable()) return Optional.empty();

    try {
      return Optional.ofNullable(fetchJson("/events/" + year, JsonArray.class));
    } catch (Exception e) {
      logger.debug("Failed to fetch events for year {}: {}", year, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Searches for events matching a partial name or code for a given year.
   *
   * @param year The competition year
   * @param query The search string (matched against event code, name, and city)
   * @param maxResults Maximum number of results to return
   * @return List of matching event codes with names
   */
  public List<String> searchEvents(int year, String query, int maxResults) {
    var eventsOpt = getEventsForYear(year);
    if (eventsOpt.isEmpty()) return List.of();

    var lowerQuery = query.toLowerCase();
    var results = new java.util.ArrayList<String>();

    for (var element : eventsOpt.get()) {
      if (!element.isJsonObject()) continue;
      var event = element.getAsJsonObject();

      String eventCode = event.has("event_code") ? event.get("event_code").getAsString() : "";
      String name = event.has("name") ? event.get("name").getAsString() : "";
      String city = event.has("city") && !event.get("city").isJsonNull()
          ? event.get("city").getAsString() : "";
      String key = event.has("key") ? event.get("key").getAsString() : "";

      if (eventCode.toLowerCase().contains(lowerQuery)
          || name.toLowerCase().contains(lowerQuery)
          || city.toLowerCase().contains(lowerQuery)) {
        results.add(eventCode + " (" + name + ")");
        if (results.size() >= maxResults) break;
      }
    }
    return results;
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
    } catch (HttpStatusException e) {
      if (e.status == 404) {
        eventMatchesCache.put(eventKey, new CachedData<>(null));
        return Optional.empty();
      }
      throw unavailable(e);
    } catch (Exception e) {
      throw unavailable(e);
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

    // A key from the match type and number ("Elimination N" is sfNm1 since 2023)
    var matchOpt = getMatch(year, eventCode, matchType, matchNumber);
    if (matchOpt.isPresent()) {
      var method = matchType != null && isGenericElimination(matchType)
          ? LOOKUP_BRACKET : LOOKUP_DIRECT;
      return extractTeamResult(matchOpt.get(), teamNumber, method);
    }

    // A generic "Elimination" that built no key (the finals since 2023, every playoff match
    // before): search the team's playoff matches
    if (matchType != null && isGenericElimination(matchType)) {
      logger.debug("Searching the playoff matches of team {} at {}{} for Elimination {}",
          teamNumber, year, eventCode, matchNumber);
      return findEliminationMatch(year, eventCode, matchNumber, teamNumber, logFileTimestampMs);
    }

    return Optional.empty();
  }

  /**
   * Checks if match type is a generic "Elimination" that needs smart matching.
   */
  public static boolean isGenericElimination(String matchType) {
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

    // Before 2023 the Driver Station's "Elimination N" was, at best, playoff match number N in
    // the order the team played: a heuristic, labeled as one. Since 2023 N is the bracket match
    // number, which the direct lookup already tried, so only the log's time can place a match
    // the bracket does not number (the finals)
    if (year < DOUBLE_ELIMINATION_FIRST_YEAR) {
      var byNumber = findMatchByEliminationNumber(candidateMatches, matchNumber);
      if (byNumber.isPresent()) {
        logger.info("Elimination {} read as playoff match number {} in play order for team {} "
            + "at {}{}", matchNumber, matchNumber, teamNumber, year, eventCode);
        return extractTeamResult(byNumber.get(), teamNumber, LOOKUP_PLAY_ORDER);
      }
    }

    if (logFileTimestampMs != null) {
      var byTime = findMatchByTimestamp(candidateMatches, logFileTimestampMs);
      if (byTime.isPresent()) {
        logger.info("Elimination {} of team {} at {}{} taken as its playoff match nearest the "
            + "log's time", matchNumber, teamNumber, year, eventCode);
        return extractTeamResult(byTime.get(), teamNumber, LOOKUP_NEAREST_TIME);
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

  private Optional<TeamMatchResult> extractTeamResult(JsonObject match, int teamNumber,
      String lookupMethod) {
    var teamKey = "frc" + teamNumber;
    var matchKey = match.has("key") && !match.get("key").isJsonNull()
        ? match.get("key").getAsString() : null;

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

          var other = alliances.getAsJsonObject("red".equals(alliance) ? "blue" : "red");
          var opponentScore = other != null && other.has("score")
              && !other.get("score").isJsonNull() ? (Integer) other.get("score").getAsInt() : null;
          return Optional.of(new TeamMatchResult(
              matchKey,
              lookupMethod,
              alliance,
              score,
              opponentScore,
              won,
              actualTime,
              scheduledTime
          ));
        }
      }
    }

    return Optional.empty();
  }

  /**
   * Whether "Elimination N" names a double-elimination bracket match: since 2023 the Driver
   * Station numbers playoff matches by the bracket, whose matches 1-13 TBA keys as sf1m1-sf13m1
   * (the finals, f1m1-f1m3, carry no bracket number).
   */
  public static boolean isBracketMatch(int year, int matchNumber) {
    return year >= DOUBLE_ELIMINATION_FIRST_YEAR && matchNumber >= 1
        && matchNumber <= DOUBLE_ELIMINATION_BRACKET_MATCHES;
  }

  private String buildMatchKey(int year, String eventCode, String matchType, int matchNumber) {
    var compLevel = mapMatchTypeToCompLevel(matchType);
    if (compLevel == null) {
      if (matchType != null && isGenericElimination(matchType)
          && isBracketMatch(year, matchNumber)) {
        return year + eventCode + "_sf" + matchNumber + "m1";
      }
      logger.info("Unsupported match type for TBA lookup: '{}' (year={}, event={}, match={})",
          matchType, year, eventCode, matchNumber);
      return null;
    }

    var eventKey = year + eventCode;

    var matchKey = (String) null;
    if ("qm".equals(compLevel)) {
      matchKey = eventKey + "_qm" + matchNumber;
    } else if ("sf".equals(compLevel)) {
      // sf{N}m1 refers to the Nth semifinal series, not necessarily the team's Nth
      // semifinal match. For double-elimination (2023+), series numbers are sequential.
      matchKey = eventKey + "_sf" + matchNumber + "m1";
    } else if ("qf".equals(compLevel)) {
      // qf{N}m1 refers to the Nth quarterfinal series (same pattern as semifinal).
      matchKey = eventKey + "_qf" + matchNumber + "m1";
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

    var lower = matchType.strip().toLowerCase();

    // TBA's own comp_level codes, as list_available_logs reports match types
    switch (lower) {
      case "qm", "q" -> {
        return "qm";
      }
      case "qf" -> {
        return "qf";
      }
      case "sf" -> {
        return "sf";
      }
      case "f" -> {
        return "f";
      }
      default -> {
        // spelled out, below
      }
    }
    if (lower.contains("qualification") || lower.contains("qual")) {
      return "qm";
    }
    if (lower.contains("quarterfinal") || lower.contains("quarter")) {
      return "qf";
    }
    if (lower.contains("semifinal") || lower.contains("semi")) {
      return "sf";
    }
    if (lower.contains("final") && !lower.contains("semi") && !lower.contains("quarter")) {
      return "f";
    }
    if (lower.contains("elimination") || lower.contains("elim")) {
      return null;
    }
    if (lower.contains("practice")) {
      return null;
    }

    return null;
  }

  /** What The Blue Alliance says about the configured key, from its {@code /status} endpoint. */
  public record KeyCheck(boolean valid, String detail, JsonObject status) {}

  /**
   * Asks The Blue Alliance whether the configured key works ({@code /status}; not cached: this
   * is the check a user runs right after configuring a key).
   */
  public KeyCheck checkKey() {
    if (!isAvailable()) return new KeyCheck(false, "no API key is configured", null);
    try {
      var status = fetchJson("/status", JsonObject.class);
      return new KeyCheck(true, "accepted by The Blue Alliance", status);
    } catch (HttpStatusException e) {
      return new KeyCheck(false, e.status == 401
          ? "rejected by The Blue Alliance (HTTP 401): check the key"
          : "The Blue Alliance returned HTTP " + e.status, null);
    } catch (IOException e) {
      return new KeyCheck(false, "The Blue Alliance could not be reached: " + e.getMessage(),
          null);
    }
  }

  private <T> T fetchJson(String endpoint, Class<T> type) throws IOException {
    // A lease may end after the caller's availability check. Report that as unavailable,
    // rather than passing null to the HTTP builder and turning it into an internal error.
    var key = ClientLeases.getInstance().keyOr(apiKey);
    if (key == null || key.isEmpty()) throw new IOException("No TBA API key is configured");
    var request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + endpoint))
        .header("X-TBA-Auth-Key", key)
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
        throw new HttpStatusException(response.statusCode());
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

  /**
   * Result of a team's performance in a specific match.
   *
   * @param matchKey TBA's key of the match the result came from
   * @param lookupMethod How the match was found: {@link #LOOKUP_DIRECT}, {@link #LOOKUP_BRACKET},
   *     {@link #LOOKUP_NEAREST_TIME}, or {@link #LOOKUP_PLAY_ORDER}
   * @param opponentScore The other alliance's score, or null when not reported
   */
  public record TeamMatchResult(
      String matchKey,
      String lookupMethod,
      String alliance,
      int score,
      Integer opponentScore,
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
