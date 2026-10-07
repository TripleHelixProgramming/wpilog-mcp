/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import edu.wpi.first.util.datalog.DataLogAccess;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.config.ClientLeases;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.tools.SignalResolver.MetadataRole;

/**
 * Manages the configured directories of WPILOG files for browsing and discovery.
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

  /**
   * Configured root directories for log file discovery, in the order given: an unmodifiable list,
   * replaced whole, so a reader works on one consistent snapshot.
   */
  private volatile List<Path> logDirectories = List.of();

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
  private volatile Integer defaultTeamNumber = null;

  /** Maximum directory depth for log/revlog file scanning (default: 5). */
  private volatile int scanDepth = 5;

  /** Private constructor for singleton pattern. */
  private LogDirectory() {
  }

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
      if (lower.contains("qualification") || lower.contains("qual") || lower.equals("q")
          || lower.equals("qm")) {
        return QUALIFICATION;
      }
      if (lower.contains("elimination") || lower.contains("elim") || lower.equals("e")) return ELIMINATION;
      if (lower.contains("semifinal") || lower.contains("semi") || lower.equals("sf")) return SEMIFINAL;
      if ((lower.contains("final") && !lower.contains("semi") && !lower.contains("quarter"))
          || lower.equals("f")) {
        return FINAL;
      }
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
   * Sets the root directories for log file discovery, replacing those set before. Null and blank
   * entries are ignored, and a directory given twice is kept once, at its first position.
   *
   * @param paths The directories, or null for none
   * @since 0.9.0
   */
  public void setLogDirectories(List<String> paths) {
    var dirs = paths == null ? List.<Path>of() : paths.stream()
        .filter(p -> p != null && !p.isBlank())
        .map(p -> Path.of(p).toAbsolutePath().normalize())
        .distinct()
        .toList();
    this.logDirectories = dirs;
    if (dirs.isEmpty()) {
      logger.info("Log directories cleared");
    } else {
      logger.info("Log directories set to: {}", dirs);
    }
  }

  /**
   * Sets a single root directory for log file discovery, or none when the path is null or blank.
   */
  public void setLogDirectory(String path) {
    setLogDirectories(path == null ? null : List.of(path));
  }

  /**
   * The configured root directories, absolute, in the order given.
   *
   * @return An unmodifiable list, empty when none is configured
   * @since 0.9.0
   */
  public List<Path> getLogDirectories() {
    var paths = new ArrayList<>(logDirectories);
    ClientLeases.getInstance().directories().forEach(directory -> paths.add(directory.path()));
    return paths.stream().distinct().toList();
  }

  /**
   * The team number for logs that do not record one. FRC team numbers are positive: 0 or less
   * (earlier one-line installers wrote {@code team: 0}) is no team, with a warning.
   */
  public void setDefaultTeamNumber(Integer teamNumber) {
    if (teamNumber != null && teamNumber <= 0) {
      logger.warn("Ignoring team number {}: FRC team numbers are positive. Set team (or -team, "
          + "or WPILOG_TEAM) to your team's number.", teamNumber);
      teamNumber = null;
    }
    this.defaultTeamNumber = teamNumber;
    if (teamNumber != null) {
      logger.info("Default team number set to: {}", teamNumber);
    }
  }

  public Integer getDefaultTeamNumber() {
    return defaultTeamNumber;
  }

  /**
   * Sets the maximum directory depth for log/revlog file scanning.
   *
   * @param depth Maximum depth (default: 5)
   */
  public void setScanDepth(int depth) {
    this.scanDepth = Math.max(1, depth);
    logger.info("Directory scan depth set to: {}", this.scanDepth);
  }

  /**
   * Gets the maximum directory scan depth.
   *
   * @return The scan depth
   */
  public int getScanDepth() {
    return scanDepth;
  }

  /** Whether at least one configured directory exists. */
  public boolean isConfigured() {
    return getLogDirectories().stream().anyMatch(Files::isDirectory);
  }

  /** Cache size, hits, and misses, in that order (Map.of's order changes from run to run). */
  public Map<String, Long> getCacheStats() {
    var stats = new LinkedHashMap<String, Long>();
    stats.put("size", (long) metadataCache.size());
    stats.put("hits", cacheHits.sum());
    stats.put("misses", cacheMisses.sum());
    return Collections.unmodifiableMap(stats);
  }

  public void clearCache() {
    metadataCache.clear();
    cacheHits.reset();
    cacheMisses.reset();
  }

  /**
   * A configured directory that could not be scanned, and why.
   *
   * @param directory The directory, as configured
   * @param reason Why: it does not exist, is not a directory, or could not be read
   * @since 0.9.0
   */
  public record UnavailableDirectory(Path directory, String reason) {}

  /**
   * The WPILOG files found in the configured directories.
   *
   * @param directories The directories scanned, as configured
   * @param logs The files found, newest first, each listed once even when two directories reach
   *     it (nested directories, or two names for the same one)
   * @param unavailable The directories that could not be scanned
   * @since 0.9.0
   */
  public record DirectoryScan(List<Path> directories, List<LogFileInfo> logs,
      List<UnavailableDirectory> unavailable, List<StoreCatalog.Snapshot> stores) {

    /** Whether no configured directory could be scanned. */
    public boolean noneReadable() {
      return unavailable.size() == directories.size();
    }
  }

  private static final Predicate<Path> WPILOG_FILE = p -> p.toString().endsWith(".wpilog");

  private static final Predicate<Path> REVLOG_FILE =
      p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".revlog");

  private static final Comparator<LogFileInfo> NEWEST_LOG_FIRST =
      Comparator.comparing(LogFileInfo::getBestTimestamp,
              Comparator.nullsLast(Comparator.<Long>reverseOrder()))
          .thenComparing(LogFileInfo::path);

  private static final Comparator<RevLogFileInfo> NEWEST_REVLOG_FIRST =
      Comparator.comparing(RevLogFileInfo::parsedTimestamp,
          Comparator.nullsLast(Comparator.reverseOrder()));

  /**
   * Scans every configured directory for WPILOG files. A directory that cannot be scanned is
   * reported in the result rather than failing the scan.
   *
   * @return The files found and the directories that could not be scanned
   * @throws IOException if no directory is configured
   * @since 0.9.0
   */
  public DirectoryScan scanLogs() throws IOException {
    var dirs = getLogDirectories();
    if (dirs.isEmpty()) throw new IOException("Log directory not configured");

    var unavailable = new ArrayList<UnavailableDirectory>();
    var stores = new ArrayList<StoreCatalog.Snapshot>();
    var found = new ArrayList<LogFileInfo>();
    var security = new SecurityValidator();
    dirs.forEach(security::addAllowedDirectory);
    var seenStores = new HashSet<Path>();
    for (var dir : dirs) {
      var problem = unavailableReason(dir);
      if (problem.isPresent()) {
        unavailable.add(new UnavailableDirectory(dir, problem.get()));
        continue;
      }
      try {
        for (var root : StoreCatalog.discover(dir)) {
          if (!seenStores.add(root)) continue;
          var store = StoreCatalog.read(root, security);
          stores.add(store);
          LogManager.getInstance().stores().discovered(root);
          for (var file : store.allFiles()) {
            if (!file.file().kind().equals("wpilog") || file.session() == null) continue;
            var session = file.session();
            found.add(new LogFileInfo(file.path().toString(), file.path().getFileName().toString(),
                session.event(), session.matchType(), session.matchNumber(),
                session.teamNumber() != null ? session.teamNumber() : teamFor(file.path()),
                getLastModified(file.path()), file.file().sizeBytes(),
                Instant.parse(session.startedAt()).toEpochMilli(), file,
                java.util.Optional.ofNullable(getOrExtractLogInfo(file.path()).robot()).orElse(file.robot())));
          }
        }
        if (!StoreCatalog.isStore(dir)) {
          findFiles(List.of(dir), WPILOG_FILE, unavailable).stream()
              .map(this::getOrExtractLogInfo).forEach(found::add);
        }
      } catch (IOException e) {
        unavailable.add(new UnavailableDirectory(dir, "could not be read (" + e.getMessage() + ")"));
      }
    }
    var seen = new HashSet<Path>();
    var logs = found.stream().filter(l -> seen.add(realPath(Path.of(l.path()))))
        .sorted(NEWEST_LOG_FIRST).toList();
    unavailable.forEach(u -> logger.warn("Log directory {} skipped: {}", u.directory(), u.reason()));
    logger.info("Found {} log files in {} of {} directories. Cache hits: {}, misses: {}",
        logs.size(), dirs.size() - unavailable.size(), dirs.size(), cacheHits.sum(), cacheMisses.sum());
    return new DirectoryScan(dirs, logs, List.copyOf(unavailable), List.copyOf(stores));
  }

  /**
   * Lists the WPILOG files in the configured directories, newest first, skipping any directory
   * that cannot be scanned.
   *
   * @throws IOException if no directory is configured, or none could be scanned
   */
  public List<LogFileInfo> listAvailableLogs() throws IOException {
    var scan = scanLogs();
    if (scan.noneReadable()) throw new IOException(noneReadableMessage(scan.unavailable()));
    return scan.logs();
  }

  private static String noneReadableMessage(List<UnavailableDirectory> unavailable) {
    return "No configured log directory could be read: " + unavailable.stream()
        .map(u -> u.directory() + " " + u.reason())
        .collect(Collectors.joining("; "));
  }

  /**
   * The configured directories that hold a file, directly or in a subdirectory. Paths are compared
   * after resolving links, so a directory configured through a link, or a file named through one,
   * still matches.
   *
   * @param file The file
   * @return The containing directories as configured, in configuration order (empty when none)
   * @since 0.9.0
   */
  public List<Path> directoriesContaining(Path file) {
    var real = realPath(file);
    return getLogDirectories().stream().filter(dir -> real.startsWith(realPath(dir))).toList();
  }

  /**
   * The files matching {@code wanted} under each directory, to the scan depth, in directory
   * order. A file reached from two directories (nested directories, or two names for the same
   * one) is listed once, under the first. A directory that cannot be walked is added to
   * {@code unavailable} and contributes nothing.
   */
  private List<Path> findFiles(List<Path> dirs, Predicate<Path> wanted,
      List<UnavailableDirectory> unavailable) {
    var seen = new HashSet<Path>();
    var found = new ArrayList<Path>();
    for (var dir : dirs) {
      var problem = unavailableReason(dir);
      if (problem.isPresent()) {
        unavailable.add(new UnavailableDirectory(dir, problem.get()));
        continue;
      }
      List<Path> files;
      try {
        Path storeRoot = dir.toAbsolutePath().normalize();
        while (storeRoot != null && !StoreCatalog.isStore(storeRoot)) storeRoot = storeRoot.getParent();
        if (storeRoot != null) {
          var security = new SecurityValidator();
          getLogDirectories().forEach(security::addAllowedDirectory);
          var store = StoreCatalog.read(storeRoot, security);
          var realDir = realPath(dir);
          files = store.allFiles().stream().filter(f -> f.session() != null)
              .filter(f -> wanted == REVLOG_FILE ? f.file().kind().equals("revlog") : f.file().kind().equals("wpilog"))
              .map(StoreCatalog.StoredFile::path).filter(p -> p.startsWith(realDir)).toList();
        } else {
          try (var paths = Files.walk(dir, scanDepth)) {
            files = paths.filter(Files::isRegularFile).filter(wanted)
                .filter(p -> StoreCatalog.containing(p).isEmpty()).toList();
          }
        }
      } catch (IOException | UncheckedIOException e) {
        var cause = e instanceof UncheckedIOException u ? u.getCause() : e;
        unavailable.add(new UnavailableDirectory(dir, "could not be read (" + cause.getMessage() + ")"));
        continue;
      }
      for (var file : files) {
        var real = realPath(file);
        if (dirs.stream().map(LogDirectory::realPath).anyMatch(real::startsWith)
            && seen.add(real)) found.add(file);
      }
    }
    return found;
  }

  /** Why a directory cannot be scanned, or empty when it can be. */
  private static Optional<String> unavailableReason(Path dir) {
    if (!Files.exists(dir)) return Optional.of("does not exist");
    if (!Files.isDirectory(dir)) return Optional.of("is not a directory");
    if (!Files.isReadable(dir)) return Optional.of("is not readable");
    return Optional.empty();
  }

  /** The path with links resolved, or its normalized absolute form when it cannot be resolved. */
  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return path.toAbsolutePath().normalize();
    }
  }

  private LogFileInfo getOrExtractLogInfo(Path path) {
    var pathKey = path.toAbsolutePath().toString();
    long currentLastModified = getLastModified(path);

    var cached = metadataCache.get(pathKey);
    if (cached != null && cached.cachedLastModified() == currentLastModified) {
      cacheHits.increment();
      return withFallbackTeam(cached.info());
    }

    cacheMisses.increment();
    var info = extractLogInfo(path);
    metadataCache.put(pathKey, new CachedLogInfo(info, currentLastModified));
    return withFallbackTeam(info);
  }

  /** Apply leases after the metadata cache, so changing a lease cannot leave an old team cached. */
  private LogFileInfo withFallbackTeam(LogFileInfo info) {
    if (info.teamNumber() != null) return info;
    var team = teamFor(Path.of(info.path()));
    if (team == null) return info;
    return new LogFileInfo(info.path(), info.filename(), info.eventName(), info.matchType(),
        info.matchNumber(), team, info.lastModified(), info.fileSize(),
        info.logCreationTime(), info.stored(), info.robot());
  }

  private Integer teamFor(Path file) {
    var leased = ClientLeases.getInstance().teamFor(realPath(file));
    return leased == null ? defaultTeamNumber : leased;
  }

  /** Permanent origins win duplicate paths; their team still follows the active lease precedence. */
  public List<DirectoryOrigin> directoryOrigins() {
    var configured = logDirectories.stream().map(LogDirectory::realPath).toList();
    return getLogDirectories().stream().map(dir -> new DirectoryOrigin(dir.toString(),
        configured.contains(realPath(dir)) ? "configured" : "leased", teamFor(dir))).toList();
  }

  public record DirectoryOrigin(String path, String origin, Integer team) {}

  /**
   * Reads the bounded metadata prefix through the resolver roles, then the file name for facts
   * not recorded there. Import inspection, unlike a directory listing, can read late identity.
   *
   * <p>The reader releases its mapping before returning, so browsing a folder does not prevent
   * a later import from moving those files on Windows.
   */
  private LogFileInfo extractLogInfo(Path path) {
    var filename = path.getFileName().toString();
    var eventName = (String) null;
    var matchType = (MatchType) null;
    var matchNumber = (Integer) null;
    var teamNumber = (Integer) null;
    org.triplehelix.wpilogmcp.store.StoreManifest.Robot robot = null;
    String serialNumber = null, comments = null;
    // The Driver Station's match type and number as the records go by. A number counts only
    // while a match type is set: with match type None there is no match, and the number can
    // hold anything (real logs start with a five-digit one).
    long currentType = 0;
    long currentNumber = 0;

    try (var scoped = new ScopedLogReader(path)) {
      var reader = scoped.reader();
      if (reader.isValid()) {
        var facts = new HashMap<Integer, MetadataRole>();
        int recordCount = 0;
        // Walk records by their own bounds, as the log scan does: WPILib's iterator skips a
        // short final record
        int pos = DataLogAccess.firstRecordOffset(path);
        int size = DataLogAccess.size(reader);
        while (pos >= 12 && pos < size) {
          if (recordCount++ >= LogManager.MAX_METADATA_RECORDS) break;
          int next = DataLogAccess.recordEnd(reader, pos);
          if (next < 0) break; // the file ends inside this record
          var record = DataLogAccess.getRecord(reader, pos);
          pos = next;

          if (record.isStart()) {
            var startData = record.getStartData();
            MetadataRole.of(startData.name, startData.type)
                .ifPresent(fact -> facts.put(startData.entry, fact));
          } else if (!record.isFinish() && !record.isSetMetadata()) {
            var fact = facts.get(record.getEntry());
            if (fact == null) continue;
            // Values not set yet (an empty name, 0, match type None) leave the fact as it is
            switch (fact) {
              case EVENT -> {
                var s = stringOf(record);
                if (s != null && !s.isBlank()) eventName = s.strip();
              }
              case MATCH_TYPE -> {
                var v = integerOf(record);
                if (v != null) currentType = v;
              }
              case MATCH_NUMBER -> {
                var v = integerOf(record);
                if (v != null) currentNumber = v;
              }
              case SERIAL -> { var v = stringOf(record); if (v != null && !v.isBlank()) serialNumber = v.strip(); }
              case COMMENTS -> { var v = stringOf(record); if (v != null && !v.isBlank()) comments = v.strip(); }
              case TEAM -> {
                var v = integerOf(record);
                if (v != null && v > 0 && v <= Integer.MAX_VALUE) teamNumber = v.intValue();
              }
            }
            if (currentType >= 1 && currentType <= 3) {
              matchType = MatchType.fromOrdinal((int) currentType);
              if (currentNumber > 0 && currentNumber <= Integer.MAX_VALUE) {
                matchNumber = (int) currentNumber;
              }
            }
            if (eventName != null && matchType != null && matchNumber != null && teamNumber != null
                && serialNumber != null && comments != null) break;
          }
        }
      }
    } catch (Exception e) {
      logger.debug("Metadata extraction error for {}: {}", filename, e.getMessage());
    }

    if (serialNumber != null) robot = new org.triplehelix.wpilogmcp.store.StoreManifest.Robot(
        serialNumber, serialNumber, null, comments, "logged");

    // What the records leave unset comes from the file name. The match is taken whole from one
    // or the other: the name's number under the records' type would be neither's match.
    var name = LogFileName.parse(filename);
    if (eventName == null) eventName = name.event();
    if ((matchType == null || matchNumber == null) && name.matchType() != null) {
      matchType = name.matchType();
      matchNumber = name.matchNumber();
    }

    // A replay or simulation output (_sim) is marked in its match type, whichever gave it
    String matchTypeLabel = matchType == null ? null
        : matchType.getFriendlyName() + (name.simulation() ? " (sim)" : "");

    return new LogFileInfo(
        path.toString(), filename, eventName,
        matchTypeLabel,
        matchNumber, teamNumber, getLastModified(path), getFileSize(path),
        creationTime(name), null, robot);
  }

  /** The record's string, or null when it cannot be read as one. */
  private static String stringOf(DataLogRecord record) {
    try { return record.getString(); } catch (RuntimeException e) { return null; }
  }

  /** The record's integer, or null when it is not an 8-byte integer. */
  private static Long integerOf(DataLogRecord record) {
    try { return record.getInteger(); } catch (RuntimeException e) { return null; }
  }

  /**
   * Extracts the creation time from a wpilog filename, or null if unparseable.
   *
   * @param filename The filename (not full path)
   * @return Epoch milliseconds, or null if the filename carries no time
   * @since 0.8.0
   */
  public Long extractCreationTime(String filename) {
    return creationTime(LogFileName.parse(filename));
  }

  /**
   * The file-name time as epoch milliseconds. The roboRIO names files in its own zone, UTC
   * unless a team changed it (see {@link WallClock}), and DataLogManager always in UTC; a
   * desktop running simulation names them in its local zone.
   */
  private static Long creationTime(LogFileName name) {
    if (name.time() == null) return null;
    ZoneId zone = name.simulation() ? ZoneId.systemDefault() : ZoneOffset.UTC;
    return name.time().atZone(zone).toInstant().toEpochMilli();
  }

  private long getLastModified(Path path) {
    try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException e) { return 0; }
  }

  private long getFileSize(Path path) {
    try { return Files.size(path); } catch (IOException e) { return 0; }
  }

  public record LogFileInfo(String path, String filename, String eventName, String matchType,
                            Integer matchNumber, Integer teamNumber, long lastModified, long fileSize,
                            Long logCreationTime, StoreCatalog.StoredFile stored,
                            org.triplehelix.wpilogmcp.store.StoreManifest.Robot robot) {

    public LogFileInfo(String path, String filename, String eventName, String matchType,
        Integer matchNumber, Integer teamNumber, long lastModified, long fileSize, Long logCreationTime,
        StoreCatalog.StoredFile stored) {
      this(path, filename, eventName, matchType, matchNumber, teamNumber, lastModified, fileSize,
          logCreationTime, stored, stored == null ? null : stored.robot());
    }

    public LogFileInfo(String path, String filename, String eventName, String matchType,
        Integer matchNumber, Integer teamNumber, long lastModified, long fileSize, Long logCreationTime) {
      this(path, filename, eventName, matchType, matchNumber, teamNumber, lastModified, fileSize,
          logCreationTime, null);
    }

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
      if (parts.isEmpty()) return filename.replace(".wpilog", "");
      // A replay or simulation output with no match type to carry the mark
      if (matchType == null && filename.toLowerCase(Locale.ROOT).endsWith("_sim.wpilog")) {
        parts.add("(sim)");
      }
      return String.join(" ", parts);
    }

    public Long getBestTimestamp() {
      return logCreationTime != null ? logCreationTime : (lastModified > 0 ? lastModified : null);
    }
  }

  private record CachedLogInfo(LogFileInfo info, long cachedLastModified) {}

  // =====================================================================
  // RevLog File Discovery
  // =====================================================================

  /** Pattern to parse REV log filenames: REV_YYYYMMDD_HHMMSS[_busname].revlog */
  private static final Pattern REVLOG_FILENAME_PATTERN = Pattern.compile(
      "REV_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})(?:_([\\w]+))?\\.revlog$",
      Pattern.CASE_INSENSITIVE);

  /**
   * Information about a discovered .revlog file.
   *
   * @param path The full path to the file
   * @param filenameTimestamp The timestamp parsed from the filename (e.g., "20260320_143052")
   * @param parsedTimestamp The timestamp as a LocalDateTime, or null if parsing failed
   * @param canBusName The CAN bus name from the filename (e.g., "canivore"), or null
   * @param fileSize The file size in bytes
   * @since 0.5.0
   */
  public record RevLogFileInfo(
      Path path,
      String filenameTimestamp,
      LocalDateTime parsedTimestamp,
      String canBusName,
      long fileSize) {

    /**
     * Gets the filename without the path.
     *
     * @return The filename
     */
    public String filename() {
      return path.getFileName().toString();
    }

    /**
     * The file-name time as epoch milliseconds, read as UTC, the roboRIO's default zone (REVLib
     * names the file by the roboRIO clock), or null when the name carries no time. The revlog
     * matching in {@code LogManager} applies the wpilog's own zone offset instead when it has one.
     *
     * @return Epoch milliseconds, or null
     */
    public Long timestampMillis() {
      if (parsedTimestamp == null) return null;
      return parsedTimestamp.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
  }

  /**
   * Lists the .revlog files in the configured directories, skipping any directory that cannot be
   * scanned.
   *
   * <p>RevLog files are CAN bus logs from REV SPARK motor controllers. They use
   * the naming convention: REV_YYYYMMDD_HHMMSS[_busname].revlog
   *
   * @return List of discovered revlog files, sorted by timestamp (newest first)
   * @throws IOException if no directory is configured, or none could be scanned
   * @since 0.5.0
   */
  public List<RevLogFileInfo> listRevLogFiles() throws IOException {
    var dirs = getLogDirectories();
    if (dirs.isEmpty()) throw new IOException("Log directory not configured");

    var unavailable = new ArrayList<UnavailableDirectory>();
    var revlogs = findFiles(dirs, REVLOG_FILE, unavailable).stream()
        .map(this::extractRevLogInfo)
        .sorted(NEWEST_REVLOG_FIRST)
        .toList();
    unavailable.forEach(u -> logger.warn("Log directory {} skipped: it {}", u.directory(),
        u.reason()));
    if (unavailable.size() == dirs.size()) throw new IOException(noneReadableMessage(unavailable));

    logger.info("Found {} revlog files", revlogs.size());
    return revlogs;
  }

  /**
   * Finds revlog files that overlap with a given time range.
   *
   * <p>This is useful for finding revlogs that correspond to a specific wpilog file.
   *
   * @param startTime The start of the time range (epoch millis)
   * @param endTime The end of the time range (epoch millis)
   * @param toleranceMinutes Additional minutes to add before/after the range
   * @return List of matching revlog files
   * @throws IOException if the directory cannot be read
   * @since 0.5.0
   */
  public List<RevLogFileInfo> findRevLogsInTimeRange(
      long startTime, long endTime, int toleranceMinutes) throws IOException {

    long toleranceMillis = toleranceMinutes * 60_000L;
    long rangeStart = startTime - toleranceMillis;
    long rangeEnd = endTime + toleranceMillis;

    return listRevLogFiles().stream()
        .filter(r -> {
          Long ts = r.timestampMillis();
          if (ts == null) return false;
          return ts >= rangeStart && ts <= rangeEnd;
        })
        .toList();
  }

  /**
   * Lists revlog files in a specific directory (walks up to the configured scan depth).
   *
   * <p>This is used for discovering revlogs in directories outside the configured logdir,
   * such as the parent directory of an ad-hoc wpilog path.
   *
   * @param dir The directory to scan
   * @return List of discovered revlog files, sorted by timestamp (newest first)
   * @since 0.8.0
   */
  public List<RevLogFileInfo> listRevLogFilesInDirectory(Path dir) {
    if (dir == null) return List.of();
    return listRevLogFilesInDirectories(List.of(dir));
  }

  /**
   * Lists revlog files in the given directories (each walked up to the configured scan depth),
   * each file once even when two directories reach it. A directory that cannot be scanned is
   * skipped.
   *
   * @param dirs The directories to scan
   * @return List of discovered revlog files, sorted by timestamp (newest first)
   * @since 0.9.0
   */
  public List<RevLogFileInfo> listRevLogFilesInDirectories(List<Path> dirs) {
    var unavailable = new ArrayList<UnavailableDirectory>();
    var revlogs = findFiles(dirs, REVLOG_FILE, unavailable).stream()
        .map(this::extractRevLogInfo)
        .sorted(NEWEST_REVLOG_FIRST)
        .toList();
    unavailable.forEach(u -> logger.debug("No revlogs from {}: it {}", u.directory(),
        u.reason()));
    return revlogs;
  }

  /**
   * Extracts metadata from a revlog file path.
   */
  RevLogFileInfo extractRevLogInfo(Path path) {
    var filename = path.getFileName().toString();
    var matcher = REVLOG_FILENAME_PATTERN.matcher(filename);

    String filenameTimestamp = null;
    LocalDateTime parsedTimestamp = null;
    String canBusName = null;

    if (matcher.find()) {
      try {
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        int hour = Integer.parseInt(matcher.group(4));
        int minute = Integer.parseInt(matcher.group(5));
        int second = Integer.parseInt(matcher.group(6));

        filenameTimestamp = matcher.group(1) + matcher.group(2) + matcher.group(3) + "_"
            + matcher.group(4) + matcher.group(5) + matcher.group(6);
        parsedTimestamp = LocalDateTime.of(year, month, day, hour, minute, second);
        canBusName = matcher.group(7); // May be null
      } catch (Exception e) {
        logger.debug("Failed to parse revlog filename timestamp: {}", filename);
      }
    }

    return new RevLogFileInfo(
        path,
        filenameTimestamp,
        parsedTimestamp,
        canBusName,
        getFileSize(path));
  }
}
