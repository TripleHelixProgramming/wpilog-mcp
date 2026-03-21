package org.triplehelix.wpilogmcp.revlog;

import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.revlog.dbc.CanDecoder;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcDatabase;

/**
 * Parser for REV .revlog binary files.
 *
 * <p>RevLog files use the WPILOG binary format to store CAN bus data captured
 * from REV SPARK MAX/Flex motor controllers. This parser:
 * <ul>
 *   <li>Reads the WPILOG-format binary file</li>
 *   <li>Identifies REV motor controller devices</li>
 *   <li>Decodes periodic status frames using DBC definitions</li>
 *   <li>Extracts named signals with timestamps</li>
 * </ul>
 *
 * <p>Entry naming conventions in revlog files:
 * <ul>
 *   <li>Device entries: "CAN/{DeviceId}" with type "rawBytes"</li>
 *   <li>Status frames: "CAN/{DeviceId}/Periodic Status {N}" with raw CAN data</li>
 * </ul>
 *
 * @since 0.5.0
 */
public class RevLogParser {
  private static final Logger logger = LoggerFactory.getLogger(RevLogParser.class);

  /** Pattern to extract device ID from entry name. */
  private static final Pattern DEVICE_ENTRY_PATTERN = Pattern.compile("CAN/(\\d+)$");

  /** Pattern to extract device ID and status frame number from entry name. */
  private static final Pattern STATUS_FRAME_PATTERN = Pattern.compile(
      "CAN/(\\d+)/Periodic Status (\\d+)");

  /** Pattern to extract timestamp from revlog filename. */
  private static final Pattern FILENAME_TIMESTAMP_PATTERN = Pattern.compile(
      "REV_(\\d{8}_\\d{6})(?:_\\w+)?\\.revlog$");

  /** Maximum number of records to parse before truncating (guards against corrupt files). */
  private static final long MAX_RECORDS = 10_000_000L;

  private final CanDecoder decoder;

  /**
   * Creates a RevLogParser with the given DBC database.
   *
   * @param dbcDatabase The DBC database for signal decoding
   */
  public RevLogParser(DbcDatabase dbcDatabase) {
    this.decoder = new CanDecoder(dbcDatabase);
  }

  /**
   * Parses a .revlog file.
   *
   * @param path The path to the revlog file
   * @return The parsed revlog data
   * @throws IOException if the file cannot be read or is invalid
   */
  public ParsedRevLog parse(Path path) throws IOException {
    return parse(path.toString());
  }

  /**
   * Parses a .revlog file.
   *
   * @param pathStr The path to the revlog file
   * @return The parsed revlog data
   * @throws IOException if the file cannot be read or is invalid
   */
  public ParsedRevLog parse(String pathStr) throws IOException {
    Path path = Path.of(pathStr);
    DataLogReader reader = new DataLogReader(pathStr);

    if (!reader.isValid()) {
      throw new IOException("Invalid revlog file: " + pathStr);
    }

    // Extract timestamp from filename
    String filenameTimestamp = extractFilenameTimestamp(path.getFileName().toString());

    // Track entries and devices
    Map<Integer, EntryMetadata> entriesById = new HashMap<>();
    Map<Integer, RevLogDevice> devices = new LinkedHashMap<>();
    Map<String, List<TimestampedValue>> signalValues = new LinkedHashMap<>();

    double minTimestamp = Double.MAX_VALUE;
    double maxTimestamp = Double.MIN_VALUE;
    long recordCount = 0;

    logger.debug("Starting revlog parse: {}", pathStr);

    long corruptRecordCount = 0;

    for (DataLogRecord record : reader) {
      recordCount++;

      if (recordCount > MAX_RECORDS) {
        logger.warn("Revlog exceeded maximum record count ({}), truncating", MAX_RECORDS);
        break;
      }

      try {
        if (record.isStart()) {
          var startData = record.getStartData();
          if (startData.name == null || startData.name.isEmpty()) {
            logger.trace("Skipping start record with null/empty name at entry {}", startData.entry);
            continue;
          }
          entriesById.put(startData.entry, new EntryMetadata(
              startData.entry, startData.name, startData.type));
          logger.trace("Found entry [{}]: name={}, type={}",
              startData.entry, startData.name, startData.type);

        } else if (!record.isFinish() && !record.isSetMetadata()) {
          // Data record
          EntryMetadata entryMeta = entriesById.get(record.getEntry());
          if (entryMeta == null) continue;

          long rawTimestamp = record.getTimestamp();
          if (rawTimestamp < 0) {
            logger.trace("Skipping record with negative timestamp: {}", rawTimestamp);
            corruptRecordCount++;
            continue;
          }

          double timestamp = rawTimestamp / 1_000_000.0;
          minTimestamp = Math.min(minTimestamp, timestamp);
          maxTimestamp = Math.max(maxTimestamp, timestamp);

          try {
            processDataRecord(record, entryMeta, devices, signalValues, timestamp);
          } catch (Exception e) {
            corruptRecordCount++;
            logger.trace("Error processing record at {}: {}", timestamp, e.getMessage());
          }
        }
      } catch (Exception e) {
        // Guard against malformed records that throw during isStart/isFinish/getEntry etc.
        corruptRecordCount++;
        if (corruptRecordCount <= 5) {
          logger.debug("Malformed record #{} in {}: {}", recordCount, pathStr, e.getMessage());
        }
      }
    }

    if (corruptRecordCount > 0) {
      logger.warn("Skipped {} corrupt/malformed records in {}", corruptRecordCount, pathStr);
    }

    logger.info("Parsed revlog: {} devices, {} signals, {} records",
        devices.size(), signalValues.size(), recordCount);

    // Convert signal values map to RevLogSignal objects
    Map<String, RevLogSignal> signals = new LinkedHashMap<>();
    for (var entry : signalValues.entrySet()) {
      String key = entry.getKey();
      List<TimestampedValue> values = entry.getValue();

      // Parse key: "DeviceKey/SignalName"
      int slashIndex = key.lastIndexOf('/');
      if (slashIndex > 0) {
        String deviceKey = key.substring(0, slashIndex);
        String signalName = key.substring(slashIndex + 1);
        String unit = getSignalUnit(signalName);

        signals.put(key, new RevLogSignal(signalName, deviceKey, values, unit));
      }
    }

    return new ParsedRevLog(
        pathStr,
        filenameTimestamp,
        devices,
        signals,
        minTimestamp == Double.MAX_VALUE ? 0 : minTimestamp,
        maxTimestamp == Double.MIN_VALUE ? 0 : maxTimestamp,
        recordCount);
  }

  /**
   * Processes a data record, updating devices and signals.
   */
  private void processDataRecord(
      DataLogRecord record,
      EntryMetadata entryMeta,
      Map<Integer, RevLogDevice> devices,
      Map<String, List<TimestampedValue>> signalValues,
      double timestamp) {

    String entryName = entryMeta.name;

    // Check for device entry (firmware info)
    Matcher deviceMatcher = DEVICE_ENTRY_PATTERN.matcher(entryName);
    if (deviceMatcher.find()) {
      int deviceId = Integer.parseInt(deviceMatcher.group(1));
      if (!devices.containsKey(deviceId)) {
        // Try to determine device type from the data
        String deviceType = "SPARK MAX"; // Default assumption
        devices.put(deviceId, new RevLogDevice(deviceId, deviceType));
        logger.debug("Discovered device: CAN ID {}, type {}", deviceId, deviceType);
      }
      return;
    }

    // Check for status frame entry
    Matcher statusMatcher = STATUS_FRAME_PATTERN.matcher(entryName);
    if (statusMatcher.find()) {
      int deviceId = Integer.parseInt(statusMatcher.group(1));
      int statusFrame = Integer.parseInt(statusMatcher.group(2));

      // Ensure device is registered
      if (!devices.containsKey(deviceId)) {
        devices.put(deviceId, new RevLogDevice(deviceId, "SPARK MAX"));
      }

      RevLogDevice device = devices.get(deviceId);
      String deviceKey = device.deviceKey();

      // Get raw CAN data - CAN frames are 8 bytes; reject truncated or oversized data
      byte[] rawData;
      try {
        rawData = record.getRaw();
      } catch (Exception e) {
        logger.trace("Failed to read raw data for CAN/{}/Status {}: {}",
            deviceId, statusFrame, e.getMessage());
        return;
      }
      if (rawData == null || rawData.length < 8) {
        return;
      }

      // Build the arbitration ID for this status frame
      int arbitrationId = buildStatusFrameArbId(statusFrame, deviceId);

      // Decode all signals from this frame
      Map<String, Double> decodedSignals = decoder.decode(arbitrationId, rawData);

      // Store each decoded signal
      for (var signalEntry : decodedSignals.entrySet()) {
        String signalKey = deviceKey + "/" + signalEntry.getKey();
        signalValues
            .computeIfAbsent(signalKey, k -> new ArrayList<>())
            .add(new TimestampedValue(timestamp, signalEntry.getValue()));
      }
    }
  }

  /**
   * Builds the CAN arbitration ID for a status frame.
   *
   * @param statusFrame The status frame number (0-7)
   * @param deviceId The device CAN ID (0-63)
   * @return The arbitration ID
   */
  private int buildStatusFrameArbId(int statusFrame, int deviceId) {
    // Device type = 2 (SPARK MAX)
    // Manufacturer = 5 (REV)
    // API Class = 6 (Status frames)
    // API Index = statusFrame
    // Device ID = deviceId
    return CanDecoder.buildArbitrationId(2, 5, 6, statusFrame, deviceId);
  }

  /**
   * Gets the unit for a signal based on its name.
   */
  private String getSignalUnit(String signalName) {
    return switch (signalName.toLowerCase()) {
      case "appliedoutput" -> "duty_cycle";
      case "velocity", "altencoderavelocity", "analogvelocity", "dutycyclevelocity" -> "rpm";
      case "position", "altencodeposition", "analogposition", "dutycycleposition",
           "dutycycleabsoluteposition" -> "rotations";
      case "temperature", "motortemperature" -> "degC";
      case "busvoltage", "analogvoltage" -> "V";
      case "outputcurrent" -> "A";
      case "dutycyclefrequency" -> "Hz";
      default -> "";
    };
  }

  /**
   * Extracts the timestamp from a revlog filename.
   *
   * @param filename The filename (e.g., "REV_20260320_143052.revlog")
   * @return The timestamp string (e.g., "20260320_143052") or null if not found
   */
  private String extractFilenameTimestamp(String filename) {
    Matcher matcher = FILENAME_TIMESTAMP_PATTERN.matcher(filename);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return null;
  }

  /**
   * Internal record for entry metadata.
   */
  private record EntryMetadata(int id, String name, String type) {}
}
