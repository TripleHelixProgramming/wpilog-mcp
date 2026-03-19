package org.triplehelix.wpilogmcp.log;

import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a configured directory of WPILOG files for browsing and discovery.
 *
 * <p>This class is a thread-safe singleton that provides log file discovery and
 * metadata caching. It uses {@link ConcurrentHashMap} for the cache and
 * {@link LongAdder} for thread-safe statistics counters.
 */
public class LogDirectory {
  private static final Logger logger = LoggerFactory.getLogger(LogDirectory.class);

  /**
   * Singleton holder class for thread-safe lazy initialization.
   * This pattern ensures thread-safety without synchronization overhead.
   */
  private static class Holder {
    static final LogDirectory INSTANCE = new LogDirectory();
  }

  /** Configured root directory for log file discovery. */
  private Path logDirectory;

  /**
   * Cache of log file metadata, keyed by absolute path.
   * Uses ConcurrentHashMap for thread-safe access.
   */
  private final Map<String, CachedLogInfo> metadataCache = new ConcurrentHashMap<>();

  /**
   * Cache hit counter for diagnostics.
   * Uses LongAdder for thread-safe, high-performance incrementing.
   */
  private final LongAdder cacheHits = new LongAdder();

  /**
   * Cache miss counter for diagnostics.
   * Uses LongAdder for thread-safe, high-performance incrementing.
   */
  private final LongAdder cacheMisses = new LongAdder();

  /** Default team number to use when file metadata is missing. */
  private Integer defaultTeamNumber = null;

  /** Private constructor for singleton pattern. */
  private LogDirectory() {}

  /**
   * Gets the singleton instance of LogDirectory.
   *
   * <p>Uses the initialization-on-demand holder idiom for thread-safe lazy
   * initialization without synchronization overhead.
   *
   * @return The singleton instance
   */
  public static LogDirectory getInstance() {
    return Holder.INSTANCE;
  }

  /** Match types used in FRC. */
  public enum MatchType {
    PRACTICE("Practice"),
    QUALIFICATION("Qualification"),
    ELIMINATION("Elimination"),
    SEMIFINAL("Semifinal"),
    FINAL("Final"),
    QUARTERFINAL("Quarterfinal");

    private final String friendlyName;

    MatchType(String friendlyName) {
      this.friendlyName = friendlyName;
    }

    public String getFriendlyName() {
      return friendlyName;
    }

    public static MatchType fromString(String value) {
      if (value == null || value.isEmpty()) return null;
      var lower = value.toLowerCase().trim();
      if (lower.contains("practice") || lower.equals("p")) return PRACTICE;
      if (lower.contains("qualification") || lower.contains("qual") || lower.equals("q")) return QUALIFICATION;
      if (lower.contains("elimination") || lower.contains("elim") || lower.equals("e")) return ELIMINATION;
      if (lower.contains("semifinal") || lower.contains("semi") || lower.equals("sf")) return SEMIFINAL;
      if (lower.contains("final") && !lower.contains("semi") && !lower.contains("quarter")) return FINAL;
      if (lower.contains("quarterfinal") || lower.contains("quarter") || lower.equals("qf")) return QUARTERFINAL;
      return null;
    }

    public static MatchType fromOrdinal(int ordinal) {
      return switch (ordinal) {
        case 1 -> PRACTICE;
        case 2 -> QUALIFICATION;
        case 3 -> ELIMINATION;
        default -> null;
      };
    }
  }

  /**
   * Sets the root directory for log file discovery.
   */
  public void setLogDirectory(String path) {
    if (path != null && !path.isEmpty()) {
      this.logDirectory = Path.of(path).toAbsolutePath();
      logger.info("Log directory set to: {}", logDirectory);
    } else {
      this.logDirectory = null;
      logger.info("Log directory cleared");
    }
  }

  public Path getLogDirectory() {
    return logDirectory;
  }

  public void setDefaultTeamNumber(Integer teamNumber) {
    this.defaultTeamNumber = teamNumber;
    if (teamNumber != null) {
      logger.info("Default team number set to: {}", teamNumber);
    }
  }

  public Integer getDefaultTeamNumber() {
    return defaultTeamNumber;
  }

  public boolean isConfigured() {
    return logDirectory != null && Files.isDirectory(logDirectory);
  }

  public Map<String, Long> getCacheStats() {
    return Map.of("size", (long) metadataCache.size(), "hits", cacheHits.sum(), "misses", cacheMisses.sum());
  }

  public void clearCache() {
    metadataCache.clear();
    cacheHits.reset();
    cacheMisses.reset();
  }

  /**
   * Lists all available WPILOG files in the configured directory.
   */
  public List<LogFileInfo> listAvailableLogs() throws IOException {
    if (!isConfigured()) throw new IOException("Log directory not configured");

    try (var paths = Files.walk(logDirectory, 3)) {
      var logs = paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".wpilog"))
          .map(this::getOrExtractLogInfo)
          .sorted(Comparator.comparing(LogFileInfo::getBestTimestamp, 
              Comparator.nullsLast(Comparator.reverseOrder())))
          .toList();

      logger.info("Found {} log files. Cache hits: {}, misses: {}",
          logs.size(), cacheHits.sum(), cacheMisses.sum());
      return logs;
    }
  }

  private LogFileInfo getOrExtractLogInfo(Path path) {
    var pathKey = path.toAbsolutePath().toString();
    long currentLastModified = getLastModified(path);

    var cached = metadataCache.get(pathKey);
    if (cached != null && cached.cachedLastModified() == currentLastModified) {
      cacheHits.increment();
      return cached.info();
    }

    cacheMisses.increment();
    var info = extractLogInfo(path);
    metadataCache.put(pathKey, new CachedLogInfo(info, currentLastModified));
    return info;
  }

  /**
   * Extracts metadata from a log file by reading the first few records.
   *
   * <p>Note: WPILib's DataLogReader does not implement AutoCloseable, so we cannot use
   * try-with-resources. The reader uses memory-mapped buffers internally which are released
   * when the reader is garbage collected. We rely on the JVM's GC to clean up these resources.
   */
  private LogFileInfo extractLogInfo(Path path) {
    var filename = path.getFileName().toString();
    var eventName = (String) null;
    var matchType = (MatchType) null;
    var matchNumber = (Integer) null;
    var teamNumber = (Integer) null;

    try {
      var reader = new DataLogReader(path.toString());
      if (reader.isValid()) {
        var entryNames = new HashMap<Integer, String>();
        int recordCount = 0;
        for (var record : reader) {
          if (recordCount++ > LogManager.MAX_METADATA_RECORDS) break;

          if (record.isStart()) {
            var startData = record.getStartData();
            entryNames.put(startData.entry, startData.name);
          } else if (!record.isFinish() && !record.isSetMetadata()) {
            var entryName = entryNames.get(record.getEntry());
            if (entryName != null) {
              var lowerName = entryName.toLowerCase();
              if (lowerName.contains("eventname")) {
                var s = getSafeString(record);
                if (s != null && !s.isEmpty()) eventName = s;
              } else if (lowerName.contains("matchtype")) {
                var mt = parseMatchTypeFromRecord(record);
                if (mt != null) matchType = mt;
              } else if (lowerName.contains("matchnumber")) {
                int mn = (int) record.getInteger();
                if (mn > 0) matchNumber = mn;
              } else if (lowerName.contains("stationnumber") || lowerName.contains("teamnumber")) {
                int val = (int) record.getInteger();
                if (val > 10) teamNumber = val;
              }
            }
            if (eventName != null && matchType != null && matchNumber != null && teamNumber != null) break;
          }
        }
      }
    } catch (Exception e) {
      logger.debug("Metadata extraction error for {}: {}", filename, e.getMessage());
    }

    // Fallback to filename parsing
    String matchTypeStr = null;
    if (eventName == null || matchType == null || matchNumber == null) {
      var parsed = parseFilename(filename, path, getLastModified(path), getFileSize(path));
      if (parsed != null) {
        if (eventName == null) eventName = parsed.eventName();
        if (matchType == null && parsed.matchType() != null) {
          matchType = MatchType.fromString(parsed.matchType());
          matchTypeStr = parsed.matchType(); // Preserve the original string (may include " (sim)")
        }
        if (matchNumber == null) matchNumber = parsed.matchNumber();
      }
    }

    if (teamNumber == null) teamNumber = defaultTeamNumber;

    // Use preserved string if available (includes sim indicator), otherwise use enum friendly name
    String finalMatchType = matchTypeStr != null ? matchTypeStr : (matchType != null ? matchType.getFriendlyName() : null);

    return new LogFileInfo(
        path.toString(), filename, eventName,
        finalMatchType,
        matchNumber, teamNumber, getLastModified(path), getFileSize(path),
        extractCreationTime(filename));
  }

  private String getSafeString(DataLogRecord record) {
    try { return record.getString(); } catch (Exception e) { return null; }
  }

  private MatchType parseMatchTypeFromRecord(DataLogRecord record) {
    try {
      var s = record.getString();
      if (s.length() == 1 && s.charAt(0) <= 3) return MatchType.fromOrdinal(s.charAt(0));
      return MatchType.fromString(s);
    } catch (Exception e) {
      try { return MatchType.fromOrdinal((int) record.getInteger()); } catch (Exception ignored) {}
    }
    return null;
  }

  private Long extractCreationTime(String filename) {
    var parsed = parseFilename(filename, Path.of(filename), 0, 0);
    return parsed != null ? parsed.logCreationTime() : null;
  }

  /**
   * Parses a WPILOG filename to extract metadata.
   *
   * <p>Expected filename format (from WPILib's DataLogManager):
   * <pre>
   * {name}_{YY}-{MM}-{DD}_{HH}-{mm}-{SS}_{event}[_{matchType}{matchNum}][_sim].wpilog
   * </pre>
   *
   * <p>Examples:
   * <ul>
   *   <li>{@code frc_25-03-15_10-30-00_vadc.wpilog} - Practice at VADC</li>
   *   <li>{@code frc_25-03-15_10-30-00_vadc_qm42.wpilog} - Qualification match 42 at VADC</li>
   *   <li>{@code frc_25-03-15_10-30-00_vadc_qm42_sim.wpilog} - Simulated match</li>
   * </ul>
   *
   * @param filename The filename to parse
   * @param path The full path to the file
   * @param lastModified Last modified timestamp in millis
   * @param fileSize File size in bytes
   * @return Parsed LogFileInfo or null if filename doesn't match expected format
   */
  private LogFileInfo parseFilename(String filename, Path path, long lastModified, long fileSize) {
    // Regex breakdown:
    // ^[a-z]+_                           - Prefix (e.g., "frc_")
    // (\d{2})-(\d{2})-(\d{2})_           - Date: YY-MM-DD (groups 1-3)
    // (\d{2})-(\d{2})-(\d{2})_           - Time: HH-mm-SS (groups 4-6)
    // ([a-z0-9]+)                        - Event code (group 7, e.g., "vadc")
    // (?:_([a-z]+)(\d+))?                - Optional match: type + number (groups 8-9, e.g., "qm42")
    // (?:_sim)?                          - Optional simulation indicator
    // \.wpilog$                          - File extension
    var regex = "^[a-z]+_(\\d{2})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})_([a-z0-9]+)(?:_([a-z]+)(\\d+))?(?:_sim)?\\.wpilog$";
    var pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    var matcher = pattern.matcher(filename);
    
    if (!matcher.matches()) return null;

    var logCreationTime = parseFilenameTimestamp(
        matcher.group(1), matcher.group(2), matcher.group(3),
        matcher.group(4), matcher.group(5), matcher.group(6));

    var eventName = matcher.group(7).toUpperCase();
    var typeCode = matcher.group(8);
    var numStr = matcher.group(9);
    var matchType = (String) null;
    var matchNumber = (Integer) null;

    if (typeCode != null && numStr != null) {
      matchNumber = Integer.parseInt(numStr);
      var m = MatchType.fromString(typeCode);
      matchType = m != null ? m.getFriendlyName() : typeCode;
    } else {
      matchType = "Practice";
    }

    if (filename.toLowerCase().endsWith("_sim.wpilog")) matchType += " (sim)";

    return new LogFileInfo(path.toString(), filename, eventName, matchType, matchNumber, null, lastModified, fileSize, logCreationTime);
  }

  private Long parseFilenameTimestamp(String yy, String mm, String dd, String hh, String min, String ss) {
    try {
      var ldt = LocalDateTime.of(2000 + Integer.parseInt(yy), Integer.parseInt(mm), Integer.parseInt(dd), 
                                 Integer.parseInt(hh), Integer.parseInt(min), Integer.parseInt(ss));
      return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    } catch (Exception e) { return null; }
  }

  private long getLastModified(Path path) {
    try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException e) { return 0; }
  }

  private long getFileSize(Path path) {
    try { return Files.size(path); } catch (IOException e) { return 0; }
  }

  public record LogFileInfo(String path, String filename, String eventName, String matchType, 
                            Integer matchNumber, Integer teamNumber, long lastModified, long fileSize, 
                            Long logCreationTime) {
    
    /** Overloaded constructor for backwards compatibility with tests. */
    public LogFileInfo(String path, String filename, String eventName, String matchType, 
                       Integer matchNumber, Integer teamNumber, long lastModified, long fileSize) {
      this(path, filename, eventName, matchType, matchNumber, teamNumber, lastModified, fileSize, null);
    }

    public String friendlyName() {
      var parts = new ArrayList<String>();
      if (eventName != null) parts.add(eventName);
      if (matchType != null) parts.add(matchType);
      if (matchNumber != null) parts.add(matchNumber.toString());
      return parts.isEmpty() ? filename.replace(".wpilog", "") : String.join(" ", parts);
    }

    public Long getBestTimestamp() {
      return logCreationTime != null ? logCreationTime : (lastModified > 0 ? lastModified : null);
    }
  }

  private record CachedLogInfo(LogFileInfo info, long cachedLastModified) {}
}
