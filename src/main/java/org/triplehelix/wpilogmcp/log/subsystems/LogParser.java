/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log.subsystems;

import edu.wpi.first.util.datalog.DataLogAccess;
import edu.wpi.first.util.datalog.DataLogReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.DecodeProblem;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.struct.StructDecodeException;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

/**
 * Parses WPILOG files eagerly, decoding every value.
 *
 * <p>This class handles:
 *
 * <ul>
 *   <li>Reading WPILOG files using WPILib's DataLogReader
 *   <li>Extracting entry metadata and timestamped values
 *   <li>Decoding structs by the log's own schemas, once the whole file has been read (a schema
 *       entry may follow the data it describes)
 *   <li>Handling truncated log files gracefully
 * </ul>
 *
 * <p>Thread-safe (stateless).
 *
 * @since 0.4.0
 */
public class LogParser {
  private static final Logger logger = LoggerFactory.getLogger(LogParser.class);

  /** Creates a LogParser. */
  public LogParser() {}

  /**
   * Parses a WPILOG file and returns its contents.
   *
   * @param path The path to the log file
   * @return The parsed log with all entries and values
   * @throws IOException if the file cannot be read or is invalid
   */
  public ParsedLog parse(Path path) throws IOException {
    var reader = new DataLogReader(path.toString());
    if (!reader.isValid()) {
      logger.error("Invalid WPILOG file: {}", path);
      throw new IOException("Invalid WPILOG file: " + path);
    }

    var entriesById = new HashMap<Integer, EntryInfo>();
    // Declaration order, as in LazyParsedLog
    var entriesByName = new java.util.LinkedHashMap<String, EntryInfo>();
    var valuesByEntry = new java.util.LinkedHashMap<String, java.util.List<TimestampedValue>>();

    double minTimestamp = Double.MAX_VALUE;
    double maxTimestamp = Double.NEGATIVE_INFINITY;
    boolean truncated = false;
    var truncationMessage = (String) null;

    logger.debug("Starting pass through log file records...");
    int recordCount = 0;
    // Walk records by their own bounds: WPILib's iterator skips a final record under 16 bytes
    int pos = DataLogAccess.firstRecordOffset(path);
    int size = DataLogAccess.size(reader);
    try {
      while (pos < size) {
        int next = DataLogAccess.recordEnd(reader, pos);
        if (next < 0) {
          throw new IllegalArgumentException("truncated: the file ends inside a record");
        }
        var record = DataLogAccess.getRecord(reader, pos);
        pos = next;
        recordCount++;
        if (record.isStart()) {
          var startData = record.getStartData();
          var info =
              new EntryInfo(
                  startData.entry, startData.name, startData.type, startData.metadata);
          var existing = entriesByName.get(startData.name);
          if (existing == null) {
            entriesById.put(startData.entry, info);
            entriesByName.put(startData.name, info);
            valuesByEntry.put(startData.name, new ArrayList<>());
          } else if (existing.type().equals(startData.type)) {
            entriesById.put(startData.entry, existing); // same name restarted: one entry
          } else {
            logger.warn("Entry '{}' restarted with type '{}' (was '{}'); ignoring its records",
                startData.name, startData.type, existing.type());
          }
          logger.trace(
              "Found entry [{}]: name={}, type={}",
              startData.entry,
              startData.name,
              startData.type);

        } else if (!record.isFinish() && !record.isSetMetadata()) {
          // Data record
          var info = entriesById.get(record.getEntry());
          if (info == null) continue;

          double timestamp = record.getTimestamp() / 1_000_000.0;
          minTimestamp = Math.min(minTimestamp, timestamp);
          maxTimestamp = Math.max(maxTimestamp, timestamp);

          try {
            // Structs wait for the schemas: keep their bytes until the pass is done
            var value = EntryDecoder.isStruct(info.type()) ? record.getRaw()
                : EntryDecoder.decodeValue(record, info.type(), StructSchemas.fallbackOnly());
            var values = valuesByEntry.get(info.name());
            if (values != null) {
              values.add(new TimestampedValue(timestamp, value));
            }
          } catch (Exception e) {
            logger.trace(
                "Malformed record at timestamp {} for entry {}: {}",
                timestamp,
                info.name(),
                e.getMessage());
          }
        }
      }
    } catch (IllegalArgumentException e) {
      // WPILib throws IllegalArgumentException with "capacity" for truncated logs.
      // Include fallback match strings in case the message wording changes.
      String msg = e.getMessage();
      if (msg != null && (msg.contains("capacity") || msg.contains("truncat")
              || msg.contains("incomplete") || msg.contains("buffer"))) {
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

    var schemas = StructSchemas.fromLog(entriesByName, name -> {
      var vals = valuesByEntry.get(name);
      return vals == null || vals.isEmpty() ? null : vals.get(0).value();
    });
    var problems = new LinkedHashMap<String, DecodeProblem>();
    for (var info : entriesByName.values()) {
      if (!EntryDecoder.isStruct(info.type())) continue;
      var raw = valuesByEntry.get(info.name());
      var decoded = new ArrayList<TimestampedValue>(raw.size());
      int failed = 0;
      String firstFailure = null;
      for (var tv : raw) {
        try {
          decoded.add(new TimestampedValue(tv.timestamp(),
              schemas.decode(info.type(), (byte[]) tv.value())));
        } catch (StructDecodeException e) {
          failed++;
          if (firstFailure == null) firstFailure = e.getMessage();
        }
      }
      if (failed > 0) problems.put(info.name(), new DecodeProblem(firstFailure, failed, raw.size()));
      valuesByEntry.put(info.name(), decoded);
    }

    return new ParsedLog(
        path.toString(),
        entriesByName,
        valuesByEntry,
        minTimestamp == Double.MAX_VALUE ? 0 : minTimestamp,
        maxTimestamp == Double.NEGATIVE_INFINITY ? 0 : maxTimestamp,
        truncated,
        truncationMessage,
        schemas,
        java.util.Collections.unmodifiableMap(problems));
  }
}
