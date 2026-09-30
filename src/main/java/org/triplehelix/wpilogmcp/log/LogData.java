/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

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

  /**
   * The struct schemas this log records, with WPILib's canonical schemas as a fallback. Struct
   * values are decoded by these; tools use them to describe a struct entry's fields.
   */
  default StructSchemas structSchemas() {
    return StructSchemas.fromLog(entries(), name -> {
      var vals = values().get(name);
      return vals == null || vals.isEmpty() ? null : vals.get(0).value();
    });
  }

  /**
   * Why some or all of an entry's records could not be decoded, if any could not. For a lazily
   * decoded log this decodes the entry first.
   *
   * @param entryName The entry name
   * @return The problem, or empty when every record decoded
   */
  default Optional<DecodeProblem> decodeProblem(String entryName) {
    return Optional.empty();
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
