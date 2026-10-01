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
import org.triplehelix.wpilogmcp.log.LogScan;
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
      logger.debug("Invalid WPILOG file: {}", path);
      throw org.triplehelix.wpilogmcp.log.LogFileException.invalid(path);
    }

    // The same record scan LazyParsedLog uses (a damaged tail is not read), then each entry's
    // records decoded from their byte offsets
    var scan = LogScan.of(reader, path);
    var entriesByName = new java.util.LinkedHashMap<>(scan.entries());
    var valuesByEntry = new java.util.LinkedHashMap<String, java.util.List<TimestampedValue>>();
    // Records that could not be decoded are reported, never silently dropped (as the lazy log
    // reports them), so both implementations agree on sample counts and problems
    var problems = new LinkedHashMap<String, DecodeProblem>();
    for (var info : entriesByName.values()) {
      var values = new ArrayList<TimestampedValue>();
      var offsets = scan.offsets().get(info.name());
      int failed = 0;
      String firstFailure = null;
      for (int k = 0; k < offsets.size(); k++) {
        var record = DataLogAccess.getRecord(reader, offsets.get(k));
        double timestamp = record.getTimestamp() / 1_000_000.0;
        try {
          // Structs wait for the schemas: keep their bytes until the pass is done
          var value = EntryDecoder.isStruct(info.type()) ? record.getRaw()
              : EntryDecoder.decodeValue(record, info.type(), StructSchemas.fallbackOnly());
          values.add(new TimestampedValue(timestamp, value));
        } catch (Exception e) {
          failed++;
          if (firstFailure == null) {
            firstFailure = EntryDecoder.malformedMessage(record, info.type(), e);
          }
          logger.trace("Malformed record at timestamp {} for entry {}: {}", timestamp,
              info.name(), e.getMessage());
        }
      }
      if (failed > 0) {
        problems.put(info.name(), new DecodeProblem(firstFailure, failed, offsets.size()));
      }
      valuesByEntry.put(info.name(), values);
    }
    logger.debug("Pass through complete. {} data records.", scan.dataRecords());

    var schemas = StructSchemas.fromLog(entriesByName, name -> {
      var vals = valuesByEntry.get(name);
      return vals == null || vals.isEmpty() ? null : vals.get(0).value();
    });
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
      if (failed > 0) {
        var earlier = problems.get(info.name());
        int total = scan.offsets().get(info.name()).size();
        problems.put(info.name(), earlier == null
            ? new DecodeProblem(firstFailure, failed, total)
            : new DecodeProblem(earlier.message(), earlier.failedRecords() + failed, total));
      }
      valuesByEntry.put(info.name(), decoded);
    }

    return new ParsedLog(
        path.toString(),
        entriesByName,
        valuesByEntry,
        scan.minTimestamp(),
        scan.maxTimestamp(),
        scan.truncated(),
        scan.truncationMessage(),
        schemas,
        java.util.Collections.unmodifiableMap(problems));
  }
}
