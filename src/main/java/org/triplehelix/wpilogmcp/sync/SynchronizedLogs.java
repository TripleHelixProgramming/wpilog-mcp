package org.triplehelix.wpilogmcp.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;

/**
 * Container for synchronized wpilog + multiple revlog data.
 *
 * <p>Supports multiple .revlog files (one per CAN bus) synchronized to a single .wpilog file.
 * Provides unified access to all data with timestamps converted to FPGA time.
 *
 * @since 0.5.0
 */
public class SynchronizedLogs {

  /** Pattern for parsing REV signal keys. */
  private static final Pattern SIGNAL_KEY_PATTERN =
      Pattern.compile("^REV/(?:([^/]+)/)?(\\w+_\\d+)/(\\w+)$");

  private final ParsedLog wpilog;
  private final List<SyncedRevLog> revlogs;

  /** Cache of transformed revlog values (signal key → offset-adjusted values). */
  private final Map<String, List<TimestampedValue>> transformedCache = new HashMap<>();

  /**
   * A single revlog with its sync result.
   *
   * @param revlog The parsed revlog data
   * @param syncResult The synchronization result
   * @param canBusName The CAN bus name (e.g., "rio", "canivore1")
   */
  public record SyncedRevLog(ParsedRevLog revlog, SyncResult syncResult, String canBusName) {}

  /**
   * Parsed components of a signal key.
   */
  private record SignalKeyParts(String canBus, String deviceKey, String signalName) {
    String deviceSignal() {
      return deviceKey + "/" + signalName;
    }
  }

  /**
   * Creates a synchronized logs container.
   *
   * @param wpilog The base wpilog
   * @param revlogs The synchronized revlogs (may be empty)
   */
  public SynchronizedLogs(ParsedLog wpilog, List<SyncedRevLog> revlogs) {
    this.wpilog = wpilog;
    this.revlogs = new ArrayList<>(revlogs);
  }

  /**
   * Creates a synchronized logs container with no revlogs.
   *
   * @param wpilog The base wpilog
   */
  public SynchronizedLogs(ParsedLog wpilog) {
    this(wpilog, List.of());
  }

  /**
   * Gets the base wpilog.
   *
   * @return The parsed wpilog
   */
  public ParsedLog wpilog() {
    return wpilog;
  }

  /**
   * Gets all synchronized revlogs.
   *
   * @return List of synced revlogs
   */
  public List<SyncedRevLog> revlogs() {
    return List.copyOf(revlogs);
  }

  /**
   * Gets the number of synchronized revlogs.
   *
   * @return The revlog count
   */
  public int revlogCount() {
    return revlogs.size();
  }

  /**
   * Checks if any revlogs are synchronized.
   *
   * @return true if at least one revlog is synchronized
   */
  public boolean hasAnySynchronized() {
    return revlogs.stream().anyMatch(r -> r.syncResult().method() != SyncMethod.FAILED);
  }

  /**
   * Gets the overall synchronization confidence (lowest/worst of all revlogs).
   *
   * <p>Since higher ordinal = worse confidence (FAILED has highest ordinal),
   * we find the max ordinal to get the worst confidence.
   *
   * @return The confidence level, or FAILED if no revlogs
   */
  public ConfidenceLevel overallConfidence() {
    return revlogs.stream()
        .map(r -> r.syncResult().confidenceLevel())
        .max(Comparator.comparingInt(ConfidenceLevel::ordinal))
        .orElse(ConfidenceLevel.FAILED);
  }

  /**
   * Gets a wpilog entry's values.
   *
   * @param entryName The entry name
   * @return The timestamped values, or null if not found
   */
  public List<TimestampedValue> getWpilogEntry(String entryName) {
    return wpilog.values().get(entryName);
  }

  /**
   * Gets a revlog signal with timestamps converted to FPGA time.
   *
   * <p>Signal key format options:
   * <ul>
   *   <li>"REV/{canBus}/{DeviceType}_{CanId}/{SignalName}" - specific CAN bus</li>
   *   <li>"REV/{DeviceType}_{CanId}/{SignalName}" - searches all buses</li>
   * </ul>
   *
   * @param signalKey The signal key in one of the formats above
   * @return The timestamped values with FPGA timestamps, or null if not found
   */
  public List<TimestampedValue> getRevLogSignal(String signalKey) {
    // Check cache first
    List<TimestampedValue> cached = transformedCache.get(signalKey);
    if (cached != null) {
      return cached;
    }

    SignalKeyParts parts = parseSignalKey(signalKey);
    if (parts == null) {
      return null;
    }

    for (SyncedRevLog synced : revlogs) {
      // Match by CAN bus name if specified
      if (parts.canBus() != null && !parts.canBus().equals(synced.canBusName())) {
        continue;
      }

      RevLogSignal signal = synced.revlog().signals().get(parts.deviceSignal());
      if (signal != null) {
        // Apply offset (with drift compensation) via SyncResult to convert revlog time → FPGA time
        SyncResult sync = synced.syncResult();

        List<TimestampedValue> transformed = signal.values().stream()
            .map(tv -> new TimestampedValue(sync.toFpgaTime(tv.timestamp()), tv.value()))
            .toList();
        transformedCache.put(signalKey, transformed);
        return transformed;
      }
    }

    return null;
  }

  /**
   * Gets all available entries (wpilog + all synchronized revlogs).
   *
   * <p>RevLog entries are prefixed with "REV/" and include:
   * <ul>
   *   <li>The CAN bus name if multiple revlogs are present</li>
   *   <li>Metadata about sync confidence in the entry's metadata field</li>
   * </ul>
   *
   * @return Map of all entry names to their info
   */
  public Map<String, EntryInfo> getAllEntries() {
    Map<String, EntryInfo> all = new LinkedHashMap<>(wpilog.entries());

    for (SyncedRevLog synced : revlogs) {
      String confidence = synced.syncResult().confidenceLevel().getLabel();
      // Include bus prefix only if multiple revlogs
      String busPrefix = revlogs.size() > 1 ? synced.canBusName() + "/" : "";

      for (RevLogSignal signal : synced.revlog().signals().values()) {
        String key = "REV/" + busPrefix + signal.fullKey();
        String metadata =
            String.format(
                "{\"source\":\"revlog\",\"can_bus\":\"%s\",\"confidence\":\"%s\"}",
                synced.canBusName(), confidence);

        all.put(key, new EntryInfo(-1, key, "double", metadata));
      }
    }

    return all;
  }

  /**
   * Gets all values for an entry (wpilog or revlog).
   *
   * @param entryName The entry name (wpilog entries use original names, revlog entries use
   *     "REV/..." format)
   * @return The timestamped values, or null if not found
   */
  public List<TimestampedValue> getValues(String entryName) {
    if (entryName.startsWith("REV/")) {
      return getRevLogSignal(entryName);
    }
    return getWpilogEntry(entryName);
  }

  /**
   * Gets sync details for a specific CAN bus.
   *
   * @param canBusName The CAN bus name
   * @return The sync result, or null if not found
   */
  public SyncResult getSyncResult(String canBusName) {
    return revlogs.stream()
        .filter(r -> r.canBusName().equals(canBusName))
        .map(SyncedRevLog::syncResult)
        .findFirst()
        .orElse(null);
  }

  /**
   * Parses a signal key into its components.
   *
   * @param signalKey The signal key (e.g., "REV/rio/SparkMax_1/appliedOutput")
   * @return The parsed parts, or null if invalid format
   */
  private SignalKeyParts parseSignalKey(String signalKey) {
    Matcher matcher = SIGNAL_KEY_PATTERN.matcher(signalKey);
    if (matcher.matches()) {
      String canBus = matcher.group(1); // May be null for "REV/SparkMax_1/signal" format
      String deviceKey = matcher.group(2);
      String signalName = matcher.group(3);
      return new SignalKeyParts(canBus, deviceKey, signalName);
    }
    return null;
  }

  /**
   * Builder for creating SynchronizedLogs instances.
   */
  public static class Builder {
    private ParsedLog wpilog;
    private final List<SyncedRevLog> revlogs = new ArrayList<>();

    /**
     * Sets the base wpilog.
     *
     * @param wpilog The parsed wpilog
     * @return This builder
     */
    public Builder wpilog(ParsedLog wpilog) {
      this.wpilog = wpilog;
      return this;
    }

    /**
     * Adds a synchronized revlog.
     *
     * @param revlog The parsed revlog
     * @param syncResult The sync result
     * @param canBusName The CAN bus name
     * @return This builder
     */
    public Builder addRevLog(ParsedRevLog revlog, SyncResult syncResult, String canBusName) {
      revlogs.add(new SyncedRevLog(revlog, syncResult, canBusName));
      return this;
    }

    /**
     * Adds a synchronized revlog with auto-generated CAN bus name.
     *
     * @param revlog The parsed revlog
     * @param syncResult The sync result
     * @return This builder
     */
    public Builder addRevLog(ParsedRevLog revlog, SyncResult syncResult) {
      String canBusName = inferCanBusName(revlog, revlogs.size());
      revlogs.add(new SyncedRevLog(revlog, syncResult, canBusName));
      return this;
    }

    /**
     * Builds the SynchronizedLogs instance.
     *
     * @return The built instance
     * @throws IllegalStateException if wpilog is not set
     */
    public SynchronizedLogs build() {
      if (wpilog == null) {
        throw new IllegalStateException("wpilog must be set");
      }
      return new SynchronizedLogs(wpilog, revlogs);
    }

    /**
     * Infers a CAN bus name from the revlog filename or index.
     */
    private String inferCanBusName(ParsedRevLog revlog, int index) {
      // Try to extract from filename (e.g., "rio_20240315_143052.revlog")
      String filename = revlog.path();
      if (filename != null) {
        int lastSlash = filename.lastIndexOf('/');
        String name = lastSlash >= 0 ? filename.substring(lastSlash + 1) : filename;
        int underscore = name.indexOf('_');
        if (underscore > 0) {
          String prefix = name.substring(0, underscore).toLowerCase();
          if (!prefix.equals("rev")) {
            return prefix;
          }
        }
      }

      // Fall back to numbered bus
      return index == 0 ? "rio" : "can" + index;
    }
  }
}
