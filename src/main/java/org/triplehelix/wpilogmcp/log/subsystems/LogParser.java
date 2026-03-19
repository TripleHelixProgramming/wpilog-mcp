package org.triplehelix.wpilogmcp.log.subsystems;

import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Parses WPILOG files and decodes their contents using extensible struct decoders.
 *
 * <p>This class handles:
 *
 * <ul>
 *   <li>Reading WPILOG files using WPILib's DataLogReader
 *   <li>Extracting entry metadata and timestamped values
 *   <li>Delegating struct decoding to StructDecoderRegistry
 *   <li>Handling truncated log files gracefully
 * </ul>
 *
 * <p>Thread-safe (stateless).
 *
 * @since 0.4.0
 */
public class LogParser {
  private static final Logger logger = LoggerFactory.getLogger(LogParser.class);

  private final StructDecoderRegistry decoderRegistry;

  /**
   * Creates a LogParser with the specified decoder registry.
   *
   * @param decoderRegistry The registry for decoding structs
   */
  public LogParser(StructDecoderRegistry decoderRegistry) {
    this.decoderRegistry = decoderRegistry;
  }

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
    var entriesByName = new HashMap<String, EntryInfo>();
    var valuesByEntry = new HashMap<String, java.util.List<TimestampedValue>>();

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
            var value = decodeValue(record, info.type());
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

  /**
   * Decodes a value from a DataLogRecord based on its type.
   *
   * <p>Handles primitive types directly and delegates struct decoding to the registry.
   *
   * @param record The log record
   * @param type The entry type
   * @return The decoded value
   */
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
          byte[] raw = record.getRaw();
          yield decoderRegistry.decodeStruct(type, raw);
        }
        byte[] raw = record.getRaw();
        yield raw.length <= 100 ? BinaryReader.bytesToHex(raw) : "<" + raw.length + " bytes>";
      }
    };
  }
}
