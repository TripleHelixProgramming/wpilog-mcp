package org.triplehelix.wpilogmcp.log;

import java.util.List;
import java.util.Map;

/**
 * Common interface for wpilog data access.
 *
 * <p>Implemented by:
 * <ul>
 *   <li>{@link ParsedLog} — eagerly loaded, all values in memory (used by tests, disk cache)</li>
 *   <li>{@link LazyParsedLog} — lazily loaded, values decoded on demand from memory-mapped file</li>
 * </ul>
 *
 * <p>Tools interact with log data exclusively through this interface via
 * {@link org.triplehelix.wpilogmcp.tools.LogRequiringTool#executeWithLog}.
 *
 * @since 0.8.0
 */
public interface LogData {

  /** The file path of the log. */
  String path();

  /** Entry metadata keyed by entry name. */
  Map<String, EntryInfo> entries();

  /**
   * Timestamped values keyed by entry name.
   *
   * <p>For {@link LazyParsedLog}, this returns a lazy map that decodes values on first access
   * and caches them with LRU eviction. For {@link ParsedLog}, this returns the eagerly-loaded map.
   */
  Map<String, List<TimestampedValue>> values();

  /** The earliest timestamp in the log (seconds). */
  double minTimestamp();

  /** The latest timestamp in the log (seconds). */
  double maxTimestamp();

  /** Whether the log was truncated during parsing. */
  boolean truncated();

  /** Message explaining truncation, or null. */
  String truncationMessage();

  /**
   * Returns the sample count for an entry without decoding values.
   *
   * <p>For {@link LazyParsedLog}, this returns the record offset count directly,
   * avoiding the expensive full decode that {@code values().get(name).size()} triggers.
   *
   * @param entryName The entry name
   * @return The number of samples, or 0 if the entry does not exist
   */
  default int sampleCount(String entryName) {
    var vals = values().get(entryName);
    return vals != null ? vals.size() : 0;
  }

  /** Number of entries in the log. */
  default int entryCount() {
    return entries().size();
  }

  /** Duration of the log in seconds. */
  default double duration() {
    return maxTimestamp() - minTimestamp();
  }
}
