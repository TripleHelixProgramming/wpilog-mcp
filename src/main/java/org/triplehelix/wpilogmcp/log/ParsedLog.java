package org.triplehelix.wpilogmcp.log;

import java.util.List;
import java.util.Map;

/**
 * A parsed WPILOG file containing entries, values, and metadata.
 *
 * @param path The file path of the log
 * @param entries Map of entry names to their metadata
 * @param values Map of entry names to their timestamped values
 * @param minTimestamp The earliest timestamp in the log
 * @param maxTimestamp The latest timestamp in the log
 * @param truncated Whether the log was truncated during parsing
 * @param truncationMessage Message explaining why truncation occurred, if applicable
 * @since 0.4.0
 */
public record ParsedLog(
    String path,
    Map<String, EntryInfo> entries,
    Map<String, List<TimestampedValue>> values,
    double minTimestamp,
    double maxTimestamp,
    boolean truncated,
    String truncationMessage) {

  /**
   * Creates a ParsedLog without truncation information.
   *
   * @param path The file path
   * @param entries Entry metadata
   * @param values Timestamped values
   * @param minTimestamp Earliest timestamp
   * @param maxTimestamp Latest timestamp
   */
  public ParsedLog(
      String path,
      Map<String, EntryInfo> entries,
      Map<String, List<TimestampedValue>> values,
      double minTimestamp,
      double maxTimestamp) {
    this(path, entries, values, minTimestamp, maxTimestamp, false, null);
  }

  /**
   * Gets the number of entries in the log.
   *
   * @return The entry count
   */
  public int entryCount() {
    return entries.size();
  }

  /**
   * Gets the duration of the log in seconds.
   *
   * @return The duration (maxTimestamp - minTimestamp)
   */
  public double duration() {
    return maxTimestamp - minTimestamp;
  }
}
