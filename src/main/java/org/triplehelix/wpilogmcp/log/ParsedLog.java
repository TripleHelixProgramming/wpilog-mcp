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
 * A parsed WPILOG file containing entries, values, and metadata.
 *
 * @param path The file path of the log
 * @param entries Map of entry names to their metadata
 * @param values Map of entry names to their timestamped values
 * @param minTimestamp The earliest timestamp in the log
 * @param maxTimestamp The latest timestamp in the log
 * @param truncated Whether the log was truncated during parsing
 * @param truncationMessage Message explaining why truncation occurred, if applicable
 * @param structSchemas The log's struct schemas (with WPILib's as a fallback)
 * @param decodeProblems Entries some of whose records could not be decoded
 * @since 0.4.0
 */
public record ParsedLog(
    String path,
    Map<String, EntryInfo> entries,
    Map<String, List<TimestampedValue>> values,
    double minTimestamp,
    double maxTimestamp,
    boolean truncated,
    String truncationMessage,
    StructSchemas structSchemas,
    Map<String, DecodeProblem> decodeProblems) implements LogData {

  /**
   * Creates a ParsedLog whose struct schemas are read from its own schema entries.
   *
   * @param path The file path
   * @param entries Entry metadata
   * @param values Timestamped values
   * @param minTimestamp Earliest timestamp
   * @param maxTimestamp Latest timestamp
   * @param truncated Whether the log was truncated
   * @param truncationMessage Why, or null
   */
  public ParsedLog(
      String path,
      Map<String, EntryInfo> entries,
      Map<String, List<TimestampedValue>> values,
      double minTimestamp,
      double maxTimestamp,
      boolean truncated,
      String truncationMessage) {
    this(path, entries, values, minTimestamp, maxTimestamp, truncated, truncationMessage,
        StructSchemas.fromLog(entries, name -> {
          var vals = values.get(name);
          return vals == null || vals.isEmpty() ? null : vals.get(0).value();
        }), Map.of());
  }

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

  @Override
  public Optional<DecodeProblem> decodeProblem(String entryName) {
    return Optional.ofNullable(decodeProblems.get(entryName));
  }

  /** Records of the entry, including any that could not be decoded (as the lazy log counts). */
  @Override
  public int sampleCount(String entryName) {
    var vals = values.get(entryName);
    if (vals == null) return 0;
    var problem = decodeProblems.get(entryName);
    return vals.size() + (problem == null ? 0 : problem.failedRecords());
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
