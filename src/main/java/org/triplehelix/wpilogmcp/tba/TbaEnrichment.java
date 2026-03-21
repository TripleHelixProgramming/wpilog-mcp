package org.triplehelix.wpilogmcp.tba;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.LogDirectory.LogFileInfo;

/**
 * Service for enriching log file information with TBA data.
 */
public class TbaEnrichment {
  private static final Logger logger = LoggerFactory.getLogger(TbaEnrichment.class);
  private static final java.util.regex.Pattern YEAR_PATTERN =
      java.util.regex.Pattern.compile("(\\d{4})");
  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("h:mm a");

  /** Singleton instance. */
  private static TbaEnrichment instance;

  /** TBA client for API access. */
  private final TbaClient client;

  /** Private constructor for singleton pattern. */
  private TbaEnrichment() {
    this.client = TbaClient.getInstance();
  }

  /**
   * Gets the singleton instance.
   *
   * @return The singleton instance
   */
  public static synchronized TbaEnrichment getInstance() {
    if (instance == null) {
      instance = new TbaEnrichment();
    }
    return instance;
  }

  /**
   * Checks if a log file is eligible for TBA enrichment.
   */
  public boolean isEligibleForEnrichment(LogFileInfo logInfo) {
    if (logInfo.eventName() == null || logInfo.eventName().isEmpty()) {
      return false;
    }

    var matchType = logInfo.matchType();
    if (matchType == null) {
      return false;
    }

    var lower = matchType.toLowerCase();
    if (lower.contains("simulation") || lower.contains("sim")) {
      return false;
    }

    if (lower.contains("practice")) {
      return false;
    }

    if (lower.contains("replay")) {
      return false;
    }

    if (logInfo.matchNumber() == null) {
      return false;
    }

    if (logInfo.teamNumber() == null) {
      return false;
    }

    return lower.contains("qualification") || lower.contains("qual")
        || lower.contains("semifinal") || lower.contains("semi")
        || lower.contains("final") || lower.contains("elimination")
        || lower.contains("quarterfinal");
  }

  /**
   * Extracts the year from a log file for TBA queries.
   */
  public int extractYear(LogFileInfo logInfo) {
    var eventName = logInfo.eventName();
    if (eventName != null && eventName.length() >= 4) {
      try {
        var matcher = YEAR_PATTERN.matcher(eventName);
        if (matcher.find()) {
          int year = Integer.parseInt(matcher.group(1));
          if (year >= 1992 && year <= 2100) {
            return year;
          }
        }
      } catch (NumberFormatException ignored) {
      }
    }

    long modTime = logInfo.lastModified();
    if (modTime > 0) {
      var date = Instant.ofEpochMilli(modTime).atZone(ZoneId.systemDefault()).toLocalDate();
      return date.getYear();
    }

    return LocalDate.now().getYear();
  }

  private String normalizeEventCode(String eventCode) {
    if (eventCode == null) return null;
    return eventCode.replaceAll("^\\d{4}", "").toLowerCase();
  }

  /**
   * Gets the timezone for an event from TBA API.
   * Falls back to America/New_York if not available (most FRC events are in Eastern US).
   */
  private ZoneId getEventTimezone(int year, String eventCode) {
    try {
      var eventOpt = client.getEvent(year, eventCode);
      if (eventOpt.isPresent()) {
        var event = eventOpt.get();
        if (event.has("timezone") && !event.get("timezone").isJsonNull()) {
          var tz = event.get("timezone").getAsString();
          return ZoneId.of(tz);
        }
      }
    } catch (Exception e) {
      logger.debug("Failed to get event timezone for {}{}: {}", year, eventCode, e.getMessage());
    }
    // Default to Eastern time for most US FRC events
    return ZoneId.of("America/New_York");
  }

  /**
   * Formats an epoch timestamp as a human-readable local time string.
   */
  private String formatMatchTime(Long epochSeconds, ZoneId timezone) {
    if (epochSeconds == null) return null;
    var zdt = Instant.ofEpochSecond(epochSeconds).atZone(timezone);
    return zdt.format(TIME_FORMAT);
  }

  /**
   * Enriches a log with TBA match data.
   */
  public Optional<JsonObject> enrichLog(LogFileInfo logInfo) {
    if (!client.isAvailable()) {
      return Optional.empty();
    }

    if (!isEligibleForEnrichment(logInfo)) {
      logger.trace("Log {} not eligible for TBA enrichment", logInfo.filename());
      return Optional.empty();
    }

    int year = extractYear(logInfo);
    var eventCode = normalizeEventCode(logInfo.eventName());
    var matchType = logInfo.matchType();
    int matchNumber = logInfo.matchNumber();
    int teamNumber = logInfo.teamNumber();

    logger.debug("Requesting TBA enrichment for {}: year={}, event={}, match={}, team={}",
        logInfo.filename(), year, eventCode, (matchType + " " + matchNumber), teamNumber);

    // Pass log file timestamp as hint for smart elimination match lookup
    // Prefer timestamp from filename (stable) over file modification time (changes when copied)
    var logTimestamp = logInfo.getBestTimestamp();
    var resultOpt =
        client.getTeamMatchResult(year, eventCode, matchType, matchNumber, teamNumber, logTimestamp);

    if (resultOpt.isEmpty()) {
      logger.info("No TBA data found for {} (year={}, event={}, type={}, match={}, team={})",
          logInfo.filename(), year, eventCode, matchType, matchNumber, teamNumber);
      return Optional.empty();
    }

    var result = resultOpt.get();
    logger.debug("Enriched {} with TBA data: alliance={}, won={}", 
        logInfo.filename(), result.alliance(), result.won());

    // Get event timezone for formatting times
    var eventTimezone = getEventTimezone(year, eventCode);

    var tba = new JsonObject();
    tba.addProperty("team_number", teamNumber);
    tba.addProperty("alliance", result.alliance());
    tba.addProperty("score", result.score());
    if (result.won() != null) {
      tba.addProperty("won", result.won());
    }
    if (result.actualTimeSeconds() != null) {
      tba.addProperty("actual_time", result.actualTimeSeconds());
      var formatted = formatMatchTime(result.actualTimeSeconds(), eventTimezone);
      if (formatted != null) {
        tba.addProperty("actual_time_local", formatted);
      }
    }
    if (result.scheduledTimeSeconds() != null) {
      tba.addProperty("scheduled_time", result.scheduledTimeSeconds());
      var formatted = formatMatchTime(result.scheduledTimeSeconds(), eventTimezone);
      if (formatted != null) {
        tba.addProperty("scheduled_time_local", formatted);
      }
    }

    var matchOpt = client.getMatch(year, eventCode, matchType, matchNumber);
    if (matchOpt.isPresent()) {
      var match = matchOpt.get();
      var opponentAlliance = "red".equals(result.alliance()) ? "blue" : "red";
      var alliances = match.getAsJsonObject("alliances");
      if (alliances != null) {
        var opponent = alliances.getAsJsonObject(opponentAlliance);
        if (opponent != null && opponent.has("score")) {
          tba.addProperty("opponent_score", opponent.get("score").getAsInt());
        }
      }
    }

    return Optional.of(tba);
  }

  /**
   * Gets the corrected match start time from TBA.
   */
  public Optional<Long> getMatchStartTime(LogFileInfo logInfo) {
    if (!client.isAvailable() || !isEligibleForEnrichment(logInfo)) {
      return Optional.empty();
    }

    int year = extractYear(logInfo);
    var eventCode = normalizeEventCode(logInfo.eventName());
    var matchType = logInfo.matchType();
    int matchNumber = logInfo.matchNumber();

    var matchOpt = client.getMatch(year, eventCode, matchType, matchNumber);
    if (matchOpt.isEmpty()) {
      return Optional.empty();
    }

    var match = matchOpt.get();

    if (match.has("actual_time") && !match.get("actual_time").isJsonNull()) {
      return Optional.of(match.get("actual_time").getAsLong());
    }
    if (match.has("time") && !match.get("time").isJsonNull()) {
      return Optional.of(match.get("time").getAsLong());
    }

    return Optional.empty();
  }
}
