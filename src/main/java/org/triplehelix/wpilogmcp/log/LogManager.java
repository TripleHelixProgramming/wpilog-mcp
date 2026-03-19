package org.triplehelix.wpilogmcp.log;

import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages loading, caching, and accessing WPILOG files.
 *
 * <p>This class provides thread-safe log caching with LRU eviction. It uses a
 * {@link ReentrantReadWriteLock} to allow concurrent reads while ensuring exclusive
 * access during writes. The cache supports both count-based and memory-based eviction
 * strategies.
 */
public class LogManager {
  private static final Logger logger = LoggerFactory.getLogger(LogManager.class);

  /** Singleton instance. */
  private static final LogManager INSTANCE = new LogManager();

  /**
   * Default maximum number of logs to keep in cache before evicting least recently used.
   */
  private static final int DEFAULT_MAX_LOADED_LOGS = 20;

  /**
   * Maximum number of records to scan when extracting metadata from a log file.
   * This prevents excessive memory/time usage on very large logs.
   */
  static final int MAX_METADATA_RECORDS = 2000;

  /**
   * Configured maximum number of logs. If null, uses memory-based limiting if configured,
   * otherwise uses DEFAULT_MAX_LOADED_LOGS.
   */
  private Integer maxLoadedLogs = null;

  /**
   * Maximum memory (in MB) to use for log cache. Only used if maxLoadedLogs is not explicitly set.
   * If null, count-based limiting is used.
   */
  private Long maxMemoryMb = null;

  /**
   * Whether maxLoadedLogs was explicitly set (vs using default).
   */
  private boolean maxLogsExplicitlySet = false;

  /**
   * Set of allowed directories from which logs can be loaded.
   * If empty, path validation is disabled (for backwards compatibility).
   * Paths are normalized and absolute.
   */
  private final Set<Path> allowedDirectories = new HashSet<>();

  /**
   * Read-write lock for thread-safe access to the cache.
   * Using ReentrantReadWriteLock allows multiple concurrent readers while ensuring
   * exclusive access for writers.
   */
  private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();

  /**
   * Cache of loaded logs, keyed by absolute path.
   * Uses access-order LinkedHashMap for LRU behavior.
   * All access must be protected by {@link #cacheLock}.
   */
  private final LinkedHashMap<String, ParsedLog> loadedLogs =
      new LinkedHashMap<>(16, 0.75f, true);

  /**
   * Path to the currently active log. Queries operate on this log by default.
   * Marked volatile to ensure visibility across threads when read without lock.
   * Write operations are still protected by {@link #cacheLock}.
   */
  private volatile String activeLogPath = null;

  /** Private constructor for singleton pattern. */
  private LogManager() {}

  /**
   * Gets the singleton instance of LogManager.
   *
   * @return The singleton instance
   */
  public static LogManager getInstance() {
    return INSTANCE;
  }

  /**
   * Sets the maximum number of logs to keep in cache.
   *
   * @param max The maximum number of logs, or null to use default/memory-based
   */
  public void setMaxLoadedLogs(Integer max) {
    this.maxLoadedLogs = max;
    this.maxLogsExplicitlySet = (max != null);
    if (max != null) {
      logger.info("Max loaded logs set to: {}", max);
    }
  }

  /**
   * Sets the maximum memory (in MB) to use for log cache.
   * Only takes effect if maxLoadedLogs is not explicitly set.
   *
   * @param mb The maximum memory in MB, or null to disable memory-based limiting
   */
  public void setMaxMemoryMb(Long mb) {
    this.maxMemoryMb = mb;
    if (mb != null) {
      logger.info("Max memory for log cache set to: {} MB", mb);
    }
  }

  /**
   * Gets the effective maximum number of loaded logs.
   *
   * @return The max logs limit currently in effect
   */
  public int getEffectiveMaxLoadedLogs() {
    if (maxLoadedLogs != null) {
      return maxLoadedLogs;
    }
    // If memory-based limiting is in effect, return a high number
    // (actual eviction is handled by memory checks)
    if (maxMemoryMb != null && !maxLogsExplicitlySet) {
      return Integer.MAX_VALUE;
    }
    return DEFAULT_MAX_LOADED_LOGS;
  }

  /**
   * Estimates the memory usage of all loaded logs in bytes.
   */
  private long estimateMemoryUsage() {
    return loadedLogs.values().stream()
        .mapToLong(this::estimateLogMemory)
        .sum();
  }

  /**
   * Estimates the memory usage of a single log in bytes.
   * Uses type information and actual value sampling for better accuracy.
   */
  private long estimateLogMemory(ParsedLog log) {
    long totalMemory = 0;

    for (var entry : log.entries().entrySet()) {
      var entryName = entry.getKey();
      var entryInfo = entry.getValue();
      var values = log.values().get(entryName);

      if (values == null || values.isEmpty()) continue;

      // Estimate per-value memory based on type and actual values
      long perValueMemory = estimateValueMemory(entryInfo.type(), values.get(0).value());
      totalMemory += values.size() * perValueMemory;

      // Add entry metadata overhead: name string, EntryInfo object, List object, etc.
      totalMemory += 200 + (entryName.length() * 2); // Java strings are 2 bytes per char
    }

    return totalMemory;
  }

  /**
   * Estimates memory usage of a single value based on its type and content.
   */
  private long estimateValueMemory(String type, Object sampleValue) {
    // Base overhead for TimestampedValue object: timestamp (8) + object reference (8) + object header (16)
    long baseOverhead = 32;

    // Estimate value size based on type
    long valueSize = switch (type) {
      case "double", "int64" -> 8;
      case "float" -> 4;
      case "boolean" -> 1;
      case "string" -> {
        if (sampleValue instanceof String s) {
          // String overhead (object header + length field) + character data
          yield 40 + (s.length() * 2);
        }
        yield 100; // Default for unknown string size
      }
      case "byte[]", "raw" -> {
        if (sampleValue instanceof byte[] b) {
          yield 40 + b.length;
        }
        yield 100; // Default for unknown byte array size
      }
      default -> {
        // For structs and other complex types
        if (sampleValue instanceof java.util.Map) {
          @SuppressWarnings("unchecked")
          var map = (java.util.Map<String, Object>) sampleValue;
          // Map overhead + approximate size of entries
          yield 200 + (map.size() * 50);
        }
        yield 40; // Fallback for unknown types
      }
    };

    return baseOverhead + valueSize;
  }

  /**
   * Resets configuration to defaults (for testing).
   */
  public void resetConfiguration() {
    this.maxLoadedLogs = null;
    this.maxMemoryMb = null;
    this.maxLogsExplicitlySet = false;
    this.allowedDirectories.clear();
  }

  /**
   * Adds a directory to the list of allowed directories for loading logs.
   * Only paths within allowed directories can be loaded.
   *
   * <p><b>Security:</b> This prevents path traversal attacks by restricting
   * file access to explicitly allowed directories.
   *
   * @param directory The directory to allow (will be normalized to absolute path)
   */
  public void addAllowedDirectory(Path directory) {
    if (directory != null) {
      Path normalized = directory.toAbsolutePath().normalize();
      allowedDirectories.add(normalized);
      logger.info("Added allowed directory: {}", normalized);
    }
  }

  /**
   * Adds a directory to the list of allowed directories for loading logs.
   *
   * @param directory The directory path string to allow
   */
  public void addAllowedDirectory(String directory) {
    if (directory != null && !directory.isBlank()) {
      addAllowedDirectory(Path.of(directory));
    }
  }

  /**
   * Clears all allowed directories.
   */
  public void clearAllowedDirectories() {
    allowedDirectories.clear();
    logger.info("Cleared all allowed directories");
  }

  /**
   * Gets a copy of the allowed directories set.
   *
   * @return A new set containing the allowed directories
   */
  public Set<Path> getAllowedDirectories() {
    return new HashSet<>(allowedDirectories);
  }

  /**
   * Validates that a path is within an allowed directory.
   *
   * <p>If no allowed directories are configured, all paths are allowed
   * (for backwards compatibility). Otherwise, the path must be within
   * one of the configured allowed directories.
   *
   * @param filePath The path to validate (should be absolute and normalized)
   * @throws IOException if the path is outside allowed directories
   */
  private void validatePathSecurity(Path filePath) throws IOException {
    if (allowedDirectories.isEmpty()) {
      // No restrictions configured - allow all paths (backwards compatibility)
      return;
    }

    Path normalizedPath = filePath.toAbsolutePath().normalize();

    // Check if path is within any allowed directory
    for (Path allowedDir : allowedDirectories) {
      if (normalizedPath.startsWith(allowedDir)) {
        return; // Path is allowed
      }
    }

    // Also check if the file is already in the cache (allow re-access to cached logs)
    cacheLock.readLock().lock();
    try {
      if (loadedLogs.containsKey(normalizedPath.toString())) {
        return; // Already cached, allow access
      }
    } finally {
      cacheLock.readLock().unlock();
    }

    logger.warn("Access denied: path '{}' is outside allowed directories", normalizedPath);
    throw new IOException("Access denied: path is outside configured log directories. " +
        "Configure allowed directories or use list_available_logs to find valid paths.");
  }

  /**
   * Loads a WPILOG file and caches it.
   *
   * <p><b>Security:</b> If allowed directories are configured via {@link #addAllowedDirectory},
   * the path must be within one of those directories. This prevents path traversal attacks.
   *
   * @param path Path to the WPILOG file
   * @return The parsed log
   * @throws IOException if the file cannot be read, is invalid, or is outside allowed directories
   */
  public ParsedLog loadLog(String path) throws IOException {
    var filePath = Path.of(path).toAbsolutePath().normalize();
    var normalizedPath = filePath.toString();

    // Security check: validate path is within allowed directories
    validatePathSecurity(filePath);

    // First check with read lock if already cached
    ParsedLog cachedLog = null;
    cacheLock.readLock().lock();
    try {
      cachedLog = loadedLogs.get(normalizedPath);
    } finally {
      cacheLock.readLock().unlock();
    }

    // If cached, update active path under write lock and return
    if (cachedLog != null) {
      logger.debug("Log file already in cache: {}", normalizedPath);
      cacheLock.writeLock().lock();
      try {
        activeLogPath = normalizedPath;
        // Re-fetch under write lock to ensure we return the correct value
        // (LinkedHashMap access-order update also happens here)
        return loadedLogs.get(normalizedPath);
      } finally {
        cacheLock.writeLock().unlock();
      }
    }

    // Parse outside of lock to avoid blocking other threads
    logger.info("Loading new log file: {}", normalizedPath);
    long startTime = System.currentTimeMillis();
    var log = parseLog(filePath);
    long duration = System.currentTimeMillis() - startTime;

    logger.info("Parsed log '{}' in {}ms. Entry count: {}, Time range: {}s to {}s",
        log.path(), duration, log.entryCount(),
        String.format("%.2f", log.minTimestamp()),
        String.format("%.2f", log.maxTimestamp()));

    // Now acquire write lock to update cache
    cacheLock.writeLock().lock();
    try {
      // Double-check in case another thread loaded it while we were parsing
      if (!loadedLogs.containsKey(normalizedPath)) {
        loadedLogs.put(normalizedPath, log);
        evictIfNeeded();
      }
      activeLogPath = normalizedPath;
      return loadedLogs.get(normalizedPath);
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Evicts the least recently used log if the cache is at capacity.
   * Uses either count-based or memory-based limits depending on configuration.
   *
   * <p><b>Thread safety:</b> This method must be called while holding the write lock.
   * It is only called from {@link #loadLog(String)} which acquires the write lock.
   */
  private void evictIfNeeded() {
    boolean useMemoryLimit = maxMemoryMb != null && !maxLogsExplicitlySet;
    int effectiveMax = getEffectiveMaxLoadedLogs();
    boolean evictedAny = false;

    while (shouldEvict(useMemoryLimit, effectiveMax)) {
      var toEvict = loadedLogs.keySet().stream()
          .filter(key -> !key.equals(activeLogPath))
          .findFirst();

      if (toEvict.isPresent()) {
        var key = toEvict.get();
        if (useMemoryLimit) {
          long memoryMb = estimateMemoryUsage() / (1024L * 1024L);
          logger.info("Memory limit ({} MB) exceeded (~{} MB used). Evicting: {}",
              maxMemoryMb, memoryMb, key);
        } else {
          logger.info("Cache capacity reached ({}). Evicting least recently used log: {}",
              effectiveMax, key);
        }
        loadedLogs.remove(key);
        evictedAny = true;
      } else {
        break;
      }
    }

    if (evictedAny) {
      // GC hint is necessary to release memory-mapped buffers from WPILib's DataLogReader.
      logger.debug("Hinting GC to release memory-mapped buffers after eviction");
      System.gc();
    }
  }

  /**
   * Determines if eviction is needed based on current limits.
   *
   * <p><b>Thread safety:</b> This method must be called while holding the read or write lock.
   *
   * @param useMemoryLimit Whether to use memory-based eviction
   * @param effectiveMax The effective maximum number of logs (used for count-based eviction)
   * @return true if eviction is needed
   */
  private boolean shouldEvict(boolean useMemoryLimit, int effectiveMax) {
    if (loadedLogs.size() <= 1) {
      return false; // Always keep at least the active log
    }

    if (useMemoryLimit) {
      long memoryBytes = estimateMemoryUsage();
      // Use long literals to prevent integer overflow before conversion
      long limitBytes = maxMemoryMb * 1024L * 1024L;
      return memoryBytes > limitBytes;
    } else {
      return loadedLogs.size() > effectiveMax;
    }
  }

  /**
   * Gets the currently active log.
   *
   * @return The active log, or null if no log is loaded
   */
  public ParsedLog getActiveLog() {
    cacheLock.readLock().lock();
    try {
      if (activeLogPath == null) {
        return null;
      }
      return loadedLogs.get(activeLogPath);
    } finally {
      cacheLock.readLock().unlock();
    }
  }

  /**
   * Gets all loaded log paths.
   *
   * @return A copy of the list of loaded log paths
   */
  public List<String> getLoadedLogPaths() {
    cacheLock.readLock().lock();
    try {
      return new ArrayList<>(loadedLogs.keySet());
    } finally {
      cacheLock.readLock().unlock();
    }
  }

  /**
   * Sets the active log.
   *
   * @param path Path to the log file (must already be loaded)
   * @return true if the log was found and set as active, false otherwise
   */
  public boolean setActiveLog(String path) {
    var filePath = Path.of(path).toAbsolutePath();
    var normalizedPath = filePath.toString();

    cacheLock.writeLock().lock();
    try {
      if (loadedLogs.containsKey(normalizedPath)) {
        logger.debug("Setting active log to: {}", normalizedPath);
        activeLogPath = normalizedPath;
        return true;
      }
      logger.warn("Attempted to set active log to path not in cache: {}", normalizedPath);
      return false;
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Unloads a log from the cache.
   *
   * <p>Note: This method calls {@link System#gc()} after unloading to hint the JVM
   * to release memory-mapped buffers used by WPILib's DataLogReader. This is necessary
   * because memory-mapped files may not be released until garbage collection occurs.
   *
   * @param path Path to the log file to unload
   * @return true if the log was found and unloaded, false otherwise
   */
  public boolean unloadLog(String path) {
    var filePath = Path.of(path).toAbsolutePath();
    var normalizedPath = filePath.toString();

    cacheLock.writeLock().lock();
    try {
      if (loadedLogs.remove(normalizedPath) != null) {
        logger.info("Unloaded log file: {}", normalizedPath);
        if (normalizedPath.equals(activeLogPath)) {
          activeLogPath = loadedLogs.isEmpty() ? null : loadedLogs.keySet().iterator().next();
          logger.debug("Active log path changed after unload to: {}", activeLogPath);
        }
        // GC hint is necessary to release memory-mapped buffers from WPILib's DataLogReader.
        // Without this, file handles may remain open and memory may not be reclaimed promptly.
        logger.debug("Hinting GC to release memory-mapped buffers after unload");
        System.gc();
        return true;
      }
      return false;
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Unloads all logs from the cache.
   *
   * <p>Note: This method calls {@link System#gc()} after clearing to hint the JVM
   * to release memory-mapped buffers used by WPILib's DataLogReader.
   *
   * @return The number of logs that were unloaded
   */
  public int unloadAllLogs() {
    cacheLock.writeLock().lock();
    try {
      int count = loadedLogs.size();
      logger.info("Unloading all logs from cache ({} logs)", count);
      loadedLogs.clear();
      activeLogPath = null;
      if (count > 0) {
        // GC hint is necessary to release memory-mapped buffers from WPILib's DataLogReader.
        logger.debug("Hinting GC to release memory-mapped buffers after clear");
        System.gc();
      }
      return count;
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Gets the path of the currently active log.
   *
   * @return The active log path, or null if no log is active
   */
  public String getActiveLogPath() {
    cacheLock.readLock().lock();
    try {
      return activeLogPath;
    } finally {
      cacheLock.readLock().unlock();
    }
  }

  /**
   * Gets the number of currently loaded logs.
   *
   * @return The number of loaded logs
   */
  public int getLoadedLogCount() {
    cacheLock.readLock().lock();
    try {
      return loadedLogs.size();
    } finally {
      cacheLock.readLock().unlock();
    }
  }

  public int getMaxLoadedLogs() {
    return getEffectiveMaxLoadedLogs();
  }

  /**
   * Gets the configured max memory limit in MB, or null if not set.
   */
  public Long getMaxMemoryMb() {
    return maxMemoryMb;
  }

  /**
   * Gets the estimated memory usage of all loaded logs in MB.
   */
  public long getEstimatedMemoryUsageMb() {
    // Use long literals to prevent integer overflow before division
    return estimateMemoryUsage() / (1024L * 1024L);
  }

  /**
   * Parses a WPILOG file.
   */
  private ParsedLog parseLog(Path path) throws IOException {
    var reader = new DataLogReader(path.toString());
    if (!reader.isValid()) {
      logger.error("Invalid WPILOG file: {}", path);
      throw new IOException("Invalid WPILOG file: " + path);
    }

    var entriesById = new HashMap<Integer, EntryInfo>();
    var entriesByName = new HashMap<String, EntryInfo>();
    var valuesByEntry = new HashMap<String, List<TimestampedValue>>();

    double minTimestamp = Double.MAX_VALUE;
    double maxTimestamp = Double.MIN_VALUE;
    boolean truncated = false;
    var truncationMessage = (String) null;

    logger.debug("Starting pass through log file records...");
    int recordCount = 0;
    try {
      for (var record : reader) {
        recordCount++;
        if (record.isStart()) {
          var startData = record.getStartData();
          var info =
              new EntryInfo(
                  startData.entry, startData.name, startData.type, startData.metadata);
          entriesById.put(startData.entry, info);
          entriesByName.put(startData.name, info);
          valuesByEntry.put(startData.name, new ArrayList<>());
          logger.trace("Found entry [{}]: name={}, type={}", startData.entry, startData.name, startData.type);

        } else if (!record.isFinish() && !record.isSetMetadata()) {
          // Data record
          var info = entriesById.get(record.getEntry());
          if (info == null) continue;

          double timestamp = record.getTimestamp() / 1_000_000.0;
          minTimestamp = Math.min(minTimestamp, timestamp);
          maxTimestamp = Math.max(maxTimestamp, timestamp);

          try {
            var value = decodeValue(record, info.type());
            var values = valuesByEntry.get(info.name());
            if (values != null) {
              values.add(new TimestampedValue(timestamp, value));
            }
          } catch (Exception e) {
            logger.trace("Malformed record at timestamp {} for entry {}: {}", timestamp, info.name(), e.getMessage());
          }
        }
      }
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().contains("capacity")) {
        truncated = true;
        truncationMessage =
            "Log file is truncated (incomplete write). Data up to "
                + String.format("%.2f", maxTimestamp)
                + " seconds was recovered.";
        logger.warn("Log file '{}' is truncated: {}", path, truncationMessage);
      } else {
        logger.error("Error reading record from log file: {}", e.getMessage(), e);
        throw e;
      }
    }
    logger.debug("Pass through complete. Processed {} records.", recordCount);

    return new ParsedLog(
        path.toString(),
        entriesByName,
        valuesByEntry,
        minTimestamp == Double.MAX_VALUE ? 0 : minTimestamp,
        maxTimestamp == Double.MIN_VALUE ? 0 : maxTimestamp,
        truncated,
        truncationMessage);
  }

  private Object decodeValue(DataLogRecord record, String type) {
    return switch (type) {
      case "boolean" -> record.getBoolean();
      case "int64" -> record.getInteger();
      case "float" -> record.getFloat();
      case "double" -> record.getDouble();
      case "string", "json" -> record.getString();
      case "boolean[]" -> record.getBooleanArray();
      case "int64[]" -> record.getIntegerArray();
      case "float[]" -> record.getFloatArray();
      case "double[]" -> record.getDoubleArray();
      case "string[]" -> record.getStringArray();
      case "raw" -> record.getRaw();
      default -> {
        if (type.startsWith("struct:") || type.startsWith("structarray:")) {
          yield decodeStruct(record, type);
        }
        byte[] raw = record.getRaw();
        yield raw.length <= 100 ? bytesToHex(raw) : "<" + raw.length + " bytes>";
      }
    };
  }

  private Object decodeStruct(DataLogRecord record, String type) {
    var isArray = type.startsWith("structarray:") || type.endsWith("[]");
    
    // Robustly extract the base struct type name
    var structType = type;
    if (structType.startsWith("structarray:")) {
      structType = structType.substring(12);
    } else if (structType.startsWith("struct:")) {
      structType = structType.substring(7);
    }
    if (structType.endsWith("[]")) {
      structType = structType.substring(0, structType.length() - 2);
    }
    structType = structType.trim();
    
    byte[] data = record.getRaw();

    return switch (structType) {
      case "Pose2d" -> isArray ? decodePose2dArray(data) : decodePose2d(data, 0);
      case "Pose3d" -> isArray ? decodePose3dArray(data) : decodePose3d(data, 0);
      case "Translation2d" -> isArray ? decodeTranslation2dArray(data) : decodeTranslation2d(data, 0);
      case "Translation3d" -> isArray ? decodeTranslation3dArray(data) : decodeTranslation3d(data, 0);
      case "Rotation2d" -> isArray ? decodeRotation2dArray(data) : decodeRotation2d(data, 0);
      case "Rotation3d" -> isArray ? decodeRotation3dArray(data) : decodeRotation3d(data, 0);
      case "Transform2d" -> isArray ? decodeTransform2dArray(data) : decodeTransform2d(data, 0);
      case "Transform3d" -> isArray ? decodeTransform3dArray(data) : decodeTransform3d(data, 0);
      case "Twist2d" -> isArray ? decodeTwist2dArray(data) : decodeTwist2d(data, 0);
      case "Twist3d" -> isArray ? decodeTwist3dArray(data) : decodeTwist3d(data, 0);
      case "ChassisSpeeds" -> isArray ? decodeChassisSpeedsArray(data) : decodeChassisSpeeds(data, 0);
      case "SwerveModuleState" ->
          isArray ? decodeSwerveModuleStateArray(data) : decodeSwerveModuleState(data, 0);
      case "SwerveModulePosition" ->
          isArray ? decodeSwerveModulePositionArray(data) : decodeSwerveModulePosition(data, 0);
      case "TargetObservation" ->
          isArray ? decodeTargetObservationArray(data) : decodeTargetObservation(data, 0);
      case "PoseObservation" ->
          isArray ? decodePoseObservationArray(data) : decodePoseObservation(data, 0);
      case "SwerveSample" ->
          isArray ? decodeSwerveSampleArray(data) : decodeSwerveSample(data, 0);
      default -> data.length <= 100 ? bytesToHex(data) : "<" + structType + ": " + data.length + " bytes>";
    };
  }

  private Map<String, Object> decodePose2d(byte[] data, int offset) {
    if (data.length < offset + 24) return Map.of("error", "insufficient data");
    double x = readDouble(data, offset);
    double y = readDouble(data, offset + 8);
    double rotation = readDouble(data, offset + 16);
    return Map.of("x", x, "y", y, "rotation_rad", rotation, "rotation_deg", Math.toDegrees(rotation));
  }

  private List<Map<String, Object>> decodePose2dArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + 24 <= data.length; offset += 24) {
      result.add(decodePose2d(data, offset));
    }
    return result;
  }

  private Map<String, Object> decodePose3d(byte[] data, int offset) {
    if (data.length < offset + 56) return Map.of("error", "insufficient data");
    double x = readDouble(data, offset);
    double y = readDouble(data, offset + 8);
    double z = readDouble(data, offset + 16);
    double qw = readDouble(data, offset + 24);
    double qx = readDouble(data, offset + 32);
    double qy = readDouble(data, offset + 40);
    double qz = readDouble(data, offset + 48);
    return Map.of(
        "x", x, "y", y, "z", z,
        "qw", qw, "qx", qx, "qy", qy, "qz", qz);
  }

  private List<Map<String, Object>> decodePose3dArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + 56 <= data.length; offset += 56) {
      result.add(decodePose3d(data, offset));
    }
    return result;
  }

  private Map<String, Object> decodeTranslation2d(byte[] data, int offset) {
    if (data.length < offset + 16) return Map.of("error", "insufficient data");
    return Map.of("x", readDouble(data, offset), "y", readDouble(data, offset + 8));
  }

  private List<Map<String, Object>> decodeTranslation2dArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + 16 <= data.length; offset += 16) {
      result.add(decodeTranslation2d(data, offset));
    }
    return result;
  }

  private Map<String, Object> decodeTranslation3d(byte[] data, int offset) {
    if (data.length < offset + 24) return Map.of("error", "insufficient data");
    return Map.of(
        "x", readDouble(data, offset),
        "y", readDouble(data, offset + 8),
        "z", readDouble(data, offset + 16));
  }

  private List<Map<String, Object>> decodeTranslation3dArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + 24 <= data.length; offset += 24) {
      result.add(decodeTranslation3d(data, offset));
    }
    return result;
  }

  private Map<String, Object> decodeRotation2d(byte[] data, int offset) {
    if (data.length < offset + 8) return Map.of("error", "insufficient data");
    double rad = readDouble(data, offset);
    return Map.of("radians", rad, "degrees", Math.toDegrees(rad));
  }

  private List<Map<String, Object>> decodeRotation2dArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + 8 <= data.length; offset += 8) {
      result.add(decodeRotation2d(data, offset));
    }
    return result;
  }

  private Map<String, Object> decodeRotation3d(byte[] data, int offset) {
    if (data.length < offset + 32) return Map.of("error", "insufficient data");
    return Map.of(
        "qw", readDouble(data, offset),
        "qx", readDouble(data, offset + 8),
        "qy", readDouble(data, offset + 16),
        "qz", readDouble(data, offset + 24));
  }

  private List<Map<String, Object>> decodeRotation3dArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + 32 <= data.length; offset += 32) {
      result.add(decodeRotation3d(data, offset));
    }
    return result;
  }

  private Map<String, Object> decodeTransform2d(byte[] data, int offset) {
    return decodePose2d(data, offset); // Same layout
  }

  private List<Map<String, Object>> decodeTransform2dArray(byte[] data) {
    return decodePose2dArray(data);
  }

  private Map<String, Object> decodeTransform3d(byte[] data, int offset) {
    return decodePose3d(data, offset); // Same layout
  }

  private List<Map<String, Object>> decodeTransform3dArray(byte[] data) {
    return decodePose3dArray(data);
  }

  private Map<String, Object> decodeTwist2d(byte[] data, int offset) {
    if (data.length < offset + 24) return Map.of("error", "insufficient data");
    return Map.of(
        "dx", readDouble(data, offset),
        "dy", readDouble(data, offset + 8),
        "dtheta", readDouble(data, offset + 16));
  }

  private List<Map<String, Object>> decodeTwist2dArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + 24 <= data.length; offset += 24) {
      result.add(decodeTwist2d(data, offset));
    }
    return result;
  }

  private Map<String, Object> decodeTwist3d(byte[] data, int offset) {
    if (data.length < offset + 48) return Map.of("error", "insufficient data");
    return Map.of(
        "dx", readDouble(data, offset),
        "dy", readDouble(data, offset + 8),
        "dz", readDouble(data, offset + 16),
        "rx", readDouble(data, offset + 24),
        "ry", readDouble(data, offset + 32),
        "rz", readDouble(data, offset + 40));
  }

  private List<Map<String, Object>> decodeTwist3dArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + 48 <= data.length; offset += 48) {
      result.add(decodeTwist3d(data, offset));
    }
    return result;
  }

  private Map<String, Object> decodeChassisSpeeds(byte[] data, int offset) {
    if (data.length < offset + 24) return Map.of("error", "insufficient data");
    return Map.of(
        "vx_mps", readDouble(data, offset),
        "vy_mps", readDouble(data, offset + 8),
        "omega_radps", readDouble(data, offset + 16));
  }

  private List<Map<String, Object>> decodeChassisSpeedsArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + 24 <= data.length; offset += 24) {
      result.add(decodeChassisSpeeds(data, offset));
    }
    return result;
  }

  private Map<String, Object> decodeSwerveModuleState(byte[] data, int offset) {
    if (data.length < offset + 16) return Map.of("error", "insufficient data");
    double speed = readDouble(data, offset);
    double angle = readDouble(data, offset + 8);
    return Map.of("speed_mps", speed, "angle_rad", angle, "angle_deg", Math.toDegrees(angle));
  }

  private List<Map<String, Object>> decodeSwerveModuleStateArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + 16 <= data.length; offset += 16) {
      result.add(decodeSwerveModuleState(data, offset));
    }
    return result;
  }

  private Map<String, Object> decodeSwerveModulePosition(byte[] data, int offset) {
    if (data.length < offset + 16) return Map.of("error", "insufficient data");
    double distance = readDouble(data, offset);
    double angle = readDouble(data, offset + 8);
    return Map.of("distance_m", distance, "angle_rad", angle, "angle_deg", Math.toDegrees(angle));
  }

  private List<Map<String, Object>> decodeSwerveModulePositionArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + 16 <= data.length; offset += 16) {
      result.add(decodeSwerveModulePosition(data, offset));
    }
    return result;
  }

  // ==================== NEW STRUCT DECODERS ====================

  /**
   * Size of TargetObservation struct in bytes.
   * Layout: yaw(8) + pitch(8) + skew(8) + area(8) + confidence(4) + objectID(4) = 40 bytes
   */
  private static final int TARGET_OBSERVATION_SIZE = 40;

  /**
   * Decodes a TargetObservation struct from a byte array.
   *
   * <p>Layout (40 bytes total):
   * <ul>
   *   <li>Rotation2d yaw (8 bytes) - double radians</li>
   *   <li>Rotation2d pitch (8 bytes) - double radians</li>
   *   <li>Rotation2d skew (8 bytes) - double radians</li>
   *   <li>double area (8 bytes)</li>
   *   <li>float confidence (4 bytes)</li>
   *   <li>int32 objectID (4 bytes)</li>
   * </ul>
   */
  private Map<String, Object> decodeTargetObservation(byte[] data, int offset) {
    if (data.length < offset + TARGET_OBSERVATION_SIZE) {
      return Map.of("error", "insufficient data for TargetObservation");
    }
    double yawRad = readDouble(data, offset);
    double pitchRad = readDouble(data, offset + 8);
    double skewRad = readDouble(data, offset + 16);
    double area = readDouble(data, offset + 24);
    float confidence = readFloat(data, offset + 32);
    int objectID = readInt32(data, offset + 36);

    return Map.of(
        "yaw_rad", yawRad,
        "yaw_deg", Math.toDegrees(yawRad),
        "pitch_rad", pitchRad,
        "pitch_deg", Math.toDegrees(pitchRad),
        "skew_rad", skewRad,
        "skew_deg", Math.toDegrees(skewRad),
        "area", area,
        "confidence", confidence,
        "objectID", objectID);
  }

  private List<Map<String, Object>> decodeTargetObservationArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + TARGET_OBSERVATION_SIZE <= data.length; offset += TARGET_OBSERVATION_SIZE) {
      result.add(decodeTargetObservation(data, offset));
    }
    return result;
  }

  /**
   * Size of PoseObservation struct in bytes.
   * Layout: timestamp(8) + Pose3d(56) + ambiguity(8) + tagCount(4) + avgTagDist(8) + type(4) = 88 bytes
   */
  private static final int POSE_OBSERVATION_SIZE = 88;

  /** Enum values for PoseObservationType. */
  private static final String[] POSE_OBSERVATION_TYPES = {"MEGATAG_1", "MEGATAG_2", "PHOTONVISION"};

  /**
   * Decodes a PoseObservation struct from a byte array.
   *
   * <p>Layout (88 bytes total):
   * <ul>
   *   <li>double timestamp (8 bytes)</li>
   *   <li>Pose3d pose (56 bytes) - Translation3d(24) + Rotation3d/Quaternion(32)</li>
   *   <li>double ambiguity (8 bytes)</li>
   *   <li>int32 tagCount (4 bytes)</li>
   *   <li>double averageTagDistance (8 bytes)</li>
   *   <li>int32 type (4 bytes) - PoseObservationType enum</li>
   * </ul>
   */
  private Map<String, Object> decodePoseObservation(byte[] data, int offset) {
    if (data.length < offset + POSE_OBSERVATION_SIZE) {
      return Map.of("error", "insufficient data for PoseObservation");
    }
    double timestamp = readDouble(data, offset);

    // Decode embedded Pose3d (56 bytes): Translation3d (x, y, z) + Quaternion (w, x, y, z)
    double poseX = readDouble(data, offset + 8);
    double poseY = readDouble(data, offset + 16);
    double poseZ = readDouble(data, offset + 24);
    double qw = readDouble(data, offset + 32);
    double qx = readDouble(data, offset + 40);
    double qy = readDouble(data, offset + 48);
    double qz = readDouble(data, offset + 56);

    double ambiguity = readDouble(data, offset + 64);
    int tagCount = readInt32(data, offset + 72);
    double averageTagDistance = readDouble(data, offset + 76);
    int typeOrdinal = readInt32(data, offset + 84);

    String typeName = (typeOrdinal >= 0 && typeOrdinal < POSE_OBSERVATION_TYPES.length)
        ? POSE_OBSERVATION_TYPES[typeOrdinal]
        : "UNKNOWN(" + typeOrdinal + ")";

    // Return flat structure with pose fields prefixed
    var result = new LinkedHashMap<String, Object>();
    result.put("timestamp", timestamp);
    result.put("pose_x", poseX);
    result.put("pose_y", poseY);
    result.put("pose_z", poseZ);
    result.put("pose_qw", qw);
    result.put("pose_qx", qx);
    result.put("pose_qy", qy);
    result.put("pose_qz", qz);
    result.put("ambiguity", ambiguity);
    result.put("tagCount", tagCount);
    result.put("averageTagDistance", averageTagDistance);
    result.put("type", typeName);
    return result;
  }

  private List<Map<String, Object>> decodePoseObservationArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + POSE_OBSERVATION_SIZE <= data.length; offset += POSE_OBSERVATION_SIZE) {
      result.add(decodePoseObservation(data, offset));
    }
    return result;
  }

  /**
   * Size of SwerveSample struct in bytes (from Choreo library).
   * Layout: 10 scalar doubles (80) + moduleForcesX[4] (32) + moduleForcesY[4] (32) = 144 bytes
   */
  private static final int SWERVE_SAMPLE_SIZE = 144;

  /**
   * Decodes a SwerveSample struct from a byte array.
   *
   * <p>Layout (144 bytes total):
   * <ul>
   *   <li>double timestamp (8 bytes)</li>
   *   <li>double x (8 bytes)</li>
   *   <li>double y (8 bytes)</li>
   *   <li>double heading (8 bytes)</li>
   *   <li>double vx (8 bytes)</li>
   *   <li>double vy (8 bytes)</li>
   *   <li>double omega (8 bytes)</li>
   *   <li>double ax (8 bytes)</li>
   *   <li>double ay (8 bytes)</li>
   *   <li>double alpha (8 bytes)</li>
   *   <li>double[4] moduleForcesX (32 bytes)</li>
   *   <li>double[4] moduleForcesY (32 bytes)</li>
   * </ul>
   */
  private Map<String, Object> decodeSwerveSample(byte[] data, int offset) {
    if (data.length < offset + SWERVE_SAMPLE_SIZE) {
      return Map.of("error", "insufficient data for SwerveSample");
    }

    double timestamp = readDouble(data, offset);
    double x = readDouble(data, offset + 8);
    double y = readDouble(data, offset + 16);
    double heading = readDouble(data, offset + 24);
    double vx = readDouble(data, offset + 32);
    double vy = readDouble(data, offset + 40);
    double omega = readDouble(data, offset + 48);
    double ax = readDouble(data, offset + 56);
    double ay = readDouble(data, offset + 64);
    double alpha = readDouble(data, offset + 72);

    // Read module forces arrays (4 elements each)
    double[] moduleForcesX = new double[4];
    double[] moduleForcesY = new double[4];
    for (int i = 0; i < 4; i++) {
      moduleForcesX[i] = readDouble(data, offset + 80 + i * 8);
      moduleForcesY[i] = readDouble(data, offset + 112 + i * 8);
    }

    var result = new LinkedHashMap<String, Object>();
    result.put("timestamp", timestamp);
    result.put("x", x);
    result.put("y", y);
    result.put("heading", heading);
    result.put("heading_deg", Math.toDegrees(heading));
    result.put("vx", vx);
    result.put("vy", vy);
    result.put("omega", omega);
    result.put("ax", ax);
    result.put("ay", ay);
    result.put("alpha", alpha);
    result.put("moduleForcesX", moduleForcesX);
    result.put("moduleForcesY", moduleForcesY);
    return result;
  }

  private List<Map<String, Object>> decodeSwerveSampleArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    for (int offset = 0; offset + SWERVE_SAMPLE_SIZE <= data.length; offset += SWERVE_SAMPLE_SIZE) {
      result.add(decodeSwerveSample(data, offset));
    }
    return result;
  }

  // ==================== BINARY READ HELPERS ====================

  private double readDouble(byte[] data, int offset) {
    long bits = 0;
    for (int i = 0; i < 8; i++) {
      bits |= (long) (data[offset + i] & 0xFF) << (i * 8);
    }
    return Double.longBitsToDouble(bits);
  }

  private float readFloat(byte[] data, int offset) {
    int bits = 0;
    for (int i = 0; i < 4; i++) {
      bits |= (data[offset + i] & 0xFF) << (i * 8);
    }
    return Float.intBitsToFloat(bits);
  }

  private int readInt32(byte[] data, int offset) {
    int value = 0;
    for (int i = 0; i < 4; i++) {
      value |= (data[offset + i] & 0xFF) << (i * 8);
    }
    return value;
  }

  private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

  private String bytesToHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      hexChars[i * 2] = HEX_CHARS[v >>> 4];
      hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
    }
    return new String(hexChars);
  }

  // ==================== PACKAGE-PRIVATE TEST ACCESSORS ====================
  // These methods provide access to internal state for testing without requiring
  // reflection. They are package-private to limit visibility to tests in the same package.

  /**
   * Reads a little-endian double from a byte array at the given offset.
   * Package-private for testing struct decoding.
   *
   * @param data The byte array
   * @param offset The offset to read from
   * @return The decoded double value
   */
  double testReadDouble(byte[] data, int offset) {
    return readDouble(data, offset);
  }

  /**
   * Decodes a Pose2d struct from a byte array.
   * Package-private for testing struct decoding.
   *
   * @param data The byte array containing the Pose2d
   * @param offset The offset to read from
   * @return A map containing x, y, rotation_rad, and rotation_deg
   */
  Map<String, Object> testDecodePose2d(byte[] data, int offset) {
    return decodePose2d(data, offset);
  }

  /**
   * Reads a little-endian float from a byte array at the given offset.
   * Package-private for testing struct decoding.
   *
   * @param data The byte array
   * @param offset The offset to read from
   * @return The decoded float value
   */
  float testReadFloat(byte[] data, int offset) {
    return readFloat(data, offset);
  }

  /**
   * Reads a little-endian int32 from a byte array at the given offset.
   * Package-private for testing struct decoding.
   *
   * @param data The byte array
   * @param offset The offset to read from
   * @return The decoded int value
   */
  int testReadInt32(byte[] data, int offset) {
    return readInt32(data, offset);
  }

  /**
   * Decodes a TargetObservation struct from a byte array.
   * Package-private for testing struct decoding.
   *
   * @param data The byte array containing the TargetObservation
   * @param offset The offset to read from
   * @return A map containing the decoded fields
   */
  Map<String, Object> testDecodeTargetObservation(byte[] data, int offset) {
    return decodeTargetObservation(data, offset);
  }

  /**
   * Decodes a TargetObservation array from a byte array.
   * Package-private for testing struct decoding.
   *
   * @param data The byte array containing the array data
   * @return A list of maps containing the decoded fields
   */
  List<Map<String, Object>> testDecodeTargetObservationArray(byte[] data) {
    return decodeTargetObservationArray(data);
  }

  /**
   * Decodes a PoseObservation struct from a byte array.
   * Package-private for testing struct decoding.
   *
   * @param data The byte array containing the PoseObservation
   * @param offset The offset to read from
   * @return A map containing the decoded fields
   */
  Map<String, Object> testDecodePoseObservation(byte[] data, int offset) {
    return decodePoseObservation(data, offset);
  }

  /**
   * Decodes a PoseObservation array from a byte array.
   * Package-private for testing struct decoding.
   *
   * @param data The byte array containing the array data
   * @return A list of maps containing the decoded fields
   */
  List<Map<String, Object>> testDecodePoseObservationArray(byte[] data) {
    return decodePoseObservationArray(data);
  }

  /**
   * Decodes a SwerveSample struct from a byte array.
   * Package-private for testing struct decoding.
   *
   * @param data The byte array containing the SwerveSample
   * @param offset The offset to read from
   * @return A map containing the decoded fields
   */
  Map<String, Object> testDecodeSwerveSample(byte[] data, int offset) {
    return decodeSwerveSample(data, offset);
  }

  /**
   * Decodes a SwerveSample array from a byte array.
   * Package-private for testing struct decoding.
   *
   * @param data The byte array containing the array data
   * @return A list of maps containing the decoded fields
   */
  List<Map<String, Object>> testDecodeSwerveSampleArray(byte[] data) {
    return decodeSwerveSampleArray(data);
  }

  // Additional struct type test accessors

  Map<String, Object> testDecodePose3d(byte[] data, int offset) {
    return decodePose3d(data, offset);
  }

  Map<String, Object> testDecodeTranslation2d(byte[] data, int offset) {
    return decodeTranslation2d(data, offset);
  }

  Map<String, Object> testDecodeTranslation3d(byte[] data, int offset) {
    return decodeTranslation3d(data, offset);
  }

  Map<String, Object> testDecodeRotation2d(byte[] data, int offset) {
    return decodeRotation2d(data, offset);
  }

  Map<String, Object> testDecodeRotation3d(byte[] data, int offset) {
    return decodeRotation3d(data, offset);
  }

  Map<String, Object> testDecodeTwist2d(byte[] data, int offset) {
    return decodeTwist2d(data, offset);
  }

  Map<String, Object> testDecodeTwist3d(byte[] data, int offset) {
    return decodeTwist3d(data, offset);
  }

  Map<String, Object> testDecodeChassisSpeeds(byte[] data, int offset) {
    return decodeChassisSpeeds(data, offset);
  }

  Map<String, Object> testDecodeSwerveModuleState(byte[] data, int offset) {
    return decodeSwerveModuleState(data, offset);
  }

  Map<String, Object> testDecodeSwerveModulePosition(byte[] data, int offset) {
    return decodeSwerveModulePosition(data, offset);
  }

  /**
   * Directly adds a parsed log to the cache for testing.
   * Package-private for testing cache behavior.
   *
   * @param path The path key for the log
   * @param log The parsed log to add
   */
  void testPutLog(String path, ParsedLog log) {
    cacheLock.writeLock().lock();
    try {
      loadedLogs.put(path, log);
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Sets the active log path directly for testing.
   * Package-private for testing cache behavior.
   *
   * @param path The path to set as active
   */
  void testSetActiveLogPath(String path) {
    cacheLock.writeLock().lock();
    try {
      activeLogPath = path;
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Triggers cache eviction for testing.
   * Package-private for testing LRU eviction.
   */
  void testEvictIfNeeded() {
    cacheLock.writeLock().lock();
    try {
      evictIfNeeded();
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Checks if a specific path is in the cache.
   * Package-private for testing cache behavior.
   *
   * @param path The path to check
   * @return true if the path is in the cache
   */
  boolean testContainsLog(String path) {
    cacheLock.readLock().lock();
    try {
      return loadedLogs.containsKey(path);
    } finally {
      cacheLock.readLock().unlock();
    }
  }

  /** Entry information from a START record. */
  public record EntryInfo(int id, String name, String type, String metadata) {}

  /** A value with its timestamp. */
  public record TimestampedValue(double timestamp, Object value) {}

  /** A parsed WPILOG file. */
  public record ParsedLog(
      String path,
      Map<String, EntryInfo> entries,
      Map<String, List<TimestampedValue>> values,
      double minTimestamp,
      double maxTimestamp,
      boolean truncated,
      String truncationMessage) {

    public ParsedLog(
        String path,
        Map<String, EntryInfo> entries,
        Map<String, List<TimestampedValue>> values,
        double minTimestamp,
        double maxTimestamp) {
      this(path, entries, values, minTimestamp, maxTimestamp, false, null);
    }

    public int entryCount() {
      return entries.size();
    }

    public double duration() {
      return maxTimestamp - minTimestamp;
    }
  }
}
