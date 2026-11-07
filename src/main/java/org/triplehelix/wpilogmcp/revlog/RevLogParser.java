/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.revlog;

import org.triplehelix.wpilogmcp.log.LogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.revlog.dbc.CanDecoder;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcDatabase;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcSignal;

/**
 * Parser for REV .revlog binary files.
 *
 * <p>Supports two formats:
 * <ul>
 *   <li><b>WPILOG format</b> — DataLog files containing CAN bus data with entry names
 *       like "CAN/{DeviceId}/Periodic Status {N}". Parsed via WPILib's DataLogReader.</li>
 *   <li><b>REV native binary format</b> — Variable-length records with firmware and periodic
 *       status entries containing raw CAN frames. Format documented by REVrobotics/node-revlog-converter.</li>
 * </ul>
 *
 * <p>Format detection is automatic: the parser tries WPILOG first, then falls back to the
 * native format. Both paths decode CAN signals using the same DBC-based pipeline.
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
  private final int maxSamples;
  private final int maxInputBytes;
  private final long packedBudget;
  private final java.util.Set<String> selectedSignals;

  /** Native read admission bounds decoded expansion without changing the legacy recovery policy. */
  public static final class SampleLimitException extends IllegalArgumentException {
    SampleLimitException(int limit) { this("REV decoded sample limit exceeded: " + limit); }
    SampleLimitException(String message) { super(message); }
  }
  private final class SignalValues extends LinkedHashMap<String, List<TimestampedValue>> {
    private final int limit;
    private int count;
    private final PackedSignalValues.Budget packed;
    SignalValues(int limit, long budget) { this.limit = limit; packed = budget > 0 ? new PackedSignalValues.Budget(budget) : null; }
    void append(String key, double timestamp, double value) {
      // Model identity uses the first recorded model, as applyModels does for a full decode.
      if (selectedSignals != null && key.endsWith("/SparkModel") && containsKey(key)) return;
      if (count == limit) throw new SampleLimitException(limit);
      count++;
      var values = computeIfAbsent(key, ignored -> packed == null ? new ArrayList<>() : new PackedSignalValues(packed, key));
      if (values instanceof PackedSignalValues compact) compact.append(timestamp, value);
      else values.add(new TimestampedValue(timestamp, value));
    }
  }

  /**
   * Creates a RevLogParser with the given DBC database.
   *
   * @param dbcDatabase The DBC database for signal decoding
   */
  public RevLogParser(DbcDatabase dbcDatabase) {
    this(dbcDatabase, Integer.MAX_VALUE);
  }

  public RevLogParser(DbcDatabase dbcDatabase, int maxSamples) {
    this(dbcDatabase, maxSamples, Integer.MAX_VALUE);
  }

  /** A finite input bound also applies if a native file grows after admission's size check. */
  public RevLogParser(DbcDatabase dbcDatabase, int maxSamples, int maxInputBytes) {
    this(dbcDatabase, maxSamples, maxInputBytes, 0);
  }

  /** A separate storage policy leaves legacy parsing and synchronization answers unchanged. */
  public static RevLogParser compact(DbcDatabase database, long decodedBytes, int inputBytes) {
    if (decodedBytes <= 0) throw new IllegalArgumentException("Decoded byte budget must be positive");
    return new RevLogParser(database, Integer.MAX_VALUE, inputBytes, decodedBytes);
  }
  public static RevLogParser selected(DbcDatabase database, long decodedBytes, int inputBytes, java.util.Set<String> signals) {
    if (signals == null || signals.isEmpty() || signals.size() > 256 || signals.stream().anyMatch(v -> v == null || v.isEmpty()))
      throw new IllegalArgumentException("Select 1..256 exact REV signal keys");
    return new RevLogParser(database, Integer.MAX_VALUE, inputBytes, decodedBytes, java.util.Set.copyOf(signals));
  }
  private RevLogParser(DbcDatabase dbcDatabase, int maxSamples, int maxInputBytes, long packedBudget) {
    this(dbcDatabase, maxSamples, maxInputBytes, packedBudget, null);
  }
  private RevLogParser(DbcDatabase dbcDatabase, int maxSamples, int maxInputBytes, long packedBudget, java.util.Set<String> selectedSignals) {
    this.packedBudget = packedBudget;
    this.selectedSignals = selectedSignals;
    if (maxSamples < 1) throw new IllegalArgumentException("REV sample limit must be positive");
    if (maxInputBytes < 1) throw new IllegalArgumentException("REV input limit must be positive");
    this.decoder = new CanDecoder(dbcDatabase);
    this.maxSamples = maxSamples;
    this.maxInputBytes = maxInputBytes;
  }

  /** Preserve compact immutable storage instead of rebuilding millions of boxed values. */
  public static List<TimestampedValue> immutableValues(List<TimestampedValue> values) {
    return values instanceof PackedSignalValues ? values : List.copyOf(values);
  }
  public static long retainedValueBytes(Map<String, RevLogSignal> signals) {
    return signals.values().stream().mapToLong(s -> s.values() instanceof PackedSignalValues packed
        ? packed.bytes() : 80L * s.values().size()).sum();
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

  /** Pull verification must distinguish a complete scan from the permissive recovery reader. */
  public ParsedRevLog parseComplete(Path path) throws IOException {
    try (var lease = LogFileAccess.read(path)) {
      if (!hasWpilogMagic(path)) return parseNativeFormat(path, true);
      try (var scoped = new ScopedLogReader(path)) {
        var scan = org.triplehelix.wpilogmcp.log.LogScan.of(scoped.reader(), path);
        if (scan.truncated()) throw new IOException("REV scan did not reach a clean EOF: " + scan.truncationMessage());
        return parseWpilog(path, scoped.reader());
      }
    }
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

    try (var lease = LogFileAccess.read(path)) {
      return parseFile(path);
    }
  }

  private ParsedRevLog parseFile(Path path) throws IOException {
    String pathStr = path.toString();
    // Native files have no WPILOG header, so they never acquire a file mapping.
    if (!hasWpilogMagic(path)) {
      logger.debug("File lacks WPILOG magic header, trying REV native binary: {}", pathStr);
      return parseNativeFormat(path);
    }

    try (var scoped = new ScopedLogReader(path)) {
      return parseWpilog(path, scoped.reader());
    }
  }

  private ParsedRevLog parseWpilog(Path path, LogReader reader) {
    String pathStr = path.toString();

    // Extract timestamp from filename
    String filenameTimestamp = extractFilenameTimestamp(path.getFileName().toString());

    // Track entries and devices
    Map<Integer, EntryMetadata> entriesById = new HashMap<>();
    Map<Integer, RevLogDevice> devices = new LinkedHashMap<>();
    var signalValues = new SignalValues(maxSamples, packedBudget);

    double minTimestamp = Double.MAX_VALUE;
    double maxTimestamp = Double.NEGATIVE_INFINITY;
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
          } catch (SampleLimitException limit) {
            throw limit;
          } catch (Exception e) {
            corruptRecordCount++;
            logger.trace("Error processing record at {}: {}", timestamp, e.getMessage());
          }
        }
      } catch (SampleLimitException limit) {
        throw limit;
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

    applyModels(devices, signalValues);
    if (selectedSignals != null) signalValues.keySet().removeIf(key -> !selectedSignals.contains(key));

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
        maxTimestamp == Double.NEGATIVE_INFINITY ? 0 : maxTimestamp,
        recordCount);
  }

  /**
   * Processes a data record, updating devices and signals.
   */
  /** Exact device/signal keys avoid expanding every DBC signal for a one-signal query.
   * Null selection preserves the legacy decoder and all synchronization inputs unchanged. */
  private Map<String, Double> decodeSelected(int id, byte[] data, String deviceKey) {
    if (selectedSignals == null) return decoder.decode(id, data);
    var values = new LinkedHashMap<String, Double>();
    String prefix = deviceKey + "/";
    var prefixes = new java.util.ArrayList<String>(); prefixes.add(prefix);
    boolean needsModel = deviceKey.startsWith("Spark_");
    if (needsModel) {
      String deviceNumber = deviceKey.substring("Spark_".length());
      prefixes.add("SparkMax_" + deviceNumber + "/"); prefixes.add("SparkFlex_" + deviceNumber + "/");
    }
    for (var key : selectedSignals) if (prefixes.stream().anyMatch(key::startsWith)) {
      String name = key.substring(key.lastIndexOf('/') + 1);
      decoder.decodeSignal(id, data, name).ifPresent(value -> values.put(name, value));
    }
    if (needsModel && selectedSignals.stream().anyMatch(key -> prefixes.stream().anyMatch(key::startsWith)))
      decoder.decodeSignal(id, data, "SparkModel").ifPresent(value -> values.put("SparkModel", value));
    return values;
  }

  private void processDataRecord(
      DataLogRecord record,
      EntryMetadata entryMeta,
      Map<Integer, RevLogDevice> devices,
      SignalValues signalValues,
      double timestamp) {

    String entryName = entryMeta.name;

    // Check for device entry (firmware info)
    Matcher deviceMatcher = DEVICE_ENTRY_PATTERN.matcher(entryName);
    if (deviceMatcher.find()) {
      int deviceId = Integer.parseInt(deviceMatcher.group(1));
      if (!devices.containsKey(deviceId)) {
        // The entry name carries the CAN id alone; the status frames that decode below are
        // the SPARK family's, and status 0 names the model (see applyModels)
        String deviceType = "SPARK";
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
        devices.put(deviceId, new RevLogDevice(deviceId, "SPARK"));
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
      Map<String, Double> decodedSignals = decodeSelected(arbitrationId, rawData, deviceKey);

      // Store each decoded signal
      for (var signalEntry : decodedSignals.entrySet()) {
        String signalKey = deviceKey + "/" + signalEntry.getKey();
        signalValues.append(signalKey, timestamp, signalEntry.getValue());
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
    // Device type = 2 (SPARK), manufacturer = 5 (REV), API class 46 (periodic status on
    // firmware 25+, which REVLib 2026 and later require), API index = the status frame number
    return CanDecoder.buildArbitrationId(2, 5, 46, statusFrame, deviceId);
  }

  // ==================== REV Native Binary Format ====================

  /**
   * Parses a .revlog file in REV's native binary format.
   *
   * <p>Format specification (from REVrobotics/node-revlog-converter):
   * <ul>
   *   <li>Records are variable-length, starting with a 1-byte bitfield</li>
   *   <li>Bitfield bits 0-1: entry ID byte length - 1</li>
   *   <li>Bitfield bits 2-3: payload size byte length - 1</li>
   *   <li>Entry ID 1 = firmware info (10-byte chunks: 4-byte CAN ID + 6-byte data)</li>
   *   <li>Entry ID 2 = periodic status (16-byte chunks: 4-byte timestamp + 4-byte CAN ID + 8-byte data)</li>
   * </ul>
   *
   * @param path The file path
   * @return The parsed revlog data
   * @throws IOException if the file cannot be read or parsed
   */
  private ParsedRevLog parseNativeFormat(Path path) throws IOException {
    return parseNativeFormat(path, false);
  }

  private ParsedRevLog parseNativeFormat(Path path, boolean complete) throws IOException {
    byte[] fileData;
    if (maxInputBytes == Integer.MAX_VALUE) fileData = Files.readAllBytes(path);
    else try (var input = Files.newInputStream(path)) {
      fileData = input.readNBytes(maxInputBytes);
      if (input.read() != -1) throw new IOException("REV input exceeds " + maxInputBytes + " bytes");
    }
    var buf = ByteBuffer.wrap(fileData).order(ByteOrder.LITTLE_ENDIAN);

    String filenameTimestamp = extractFilenameTimestamp(path.getFileName().toString());

    Map<Integer, RevLogDevice> devices = new LinkedHashMap<>();
    var signalValues = new SignalValues(maxSamples, packedBudget);
    double minTimestamp = Double.MAX_VALUE;
    double maxTimestamp = Double.NEGATIVE_INFINITY;
    long recordCount = 0;

    int pos = 0;
    while (pos < fileData.length) {
      if (recordCount > MAX_RECORDS) {
        if (complete) throw new IOException("REV scan stopped before EOF at the record limit");
        logger.warn("Native revlog exceeded maximum record count, truncating");
        break;
      }

      try {
        // Read record header bitfield
        int bitfield = fileData[pos] & 0xFF;
        if (complete && (bitfield & 0xf0) != 0) throw new IOException("Invalid REV record header");
        pos++;

        int entryIdLen = (bitfield & 0x03) + 1;
        int payloadSizeLen = ((bitfield >> 2) & 0x03) + 1;

        if (pos + entryIdLen + payloadSizeLen > fileData.length) {
          if (complete) throw new IOException("REV scan stopped inside a header before EOF");
          break;
        }

        // Read entry ID (variable length, LE)
        long entryId = readVarInt(fileData, pos, entryIdLen);
        pos += entryIdLen;

        // Read payload size (variable length, LE)
        long payloadSize = readVarInt(fileData, pos, payloadSizeLen);
        pos += payloadSizeLen;

        if (payloadSize < 0 || pos + payloadSize > fileData.length) {
          if (complete) throw new IOException("REV scan stopped inside a payload before EOF");
          logger.debug("Native revlog: invalid payload size {} at offset {}", payloadSize, pos);
          break;
        }

        int payloadStart = pos;
        if (complete && ((entryId == 1 && payloadSize % 10 != 0) || (entryId == 2 && payloadSize % 16 != 0))) {
          throw new IOException("REV record has a partial CAN frame");
        }
        pos += (int) payloadSize;
        recordCount++;

        if (entryId == 1) {
          // Firmware entry: 10-byte chunks (4-byte CAN ID LE + 6-byte data)
          parseFirmwareEntry(fileData, payloadStart, (int) payloadSize, devices);
        } else if (entryId == 2) {
          // Periodic status: 16-byte chunks (4-byte timestamp ms LE + 4-byte CAN ID LE + 8-byte data)
          parsePeriodicEntry(fileData, payloadStart, (int) payloadSize,
              devices, signalValues);

          // Update timestamp range from the last chunk in this payload
          int numChunks = (int) payloadSize / 16;
          if (numChunks > 0) {
            int firstChunkOffset = payloadStart;
            int lastChunkOffset = payloadStart + (numChunks - 1) * 16;
            double firstTs = readU32LE(fileData, firstChunkOffset) / 1000.0;
            double lastTs = readU32LE(fileData, lastChunkOffset) / 1000.0;
            minTimestamp = Math.min(minTimestamp, firstTs);
            maxTimestamp = Math.max(maxTimestamp, lastTs);
          }
        }
        // Other entry IDs are silently ignored
      } catch (SampleLimitException limit) {
        throw limit;
      } catch (Exception e) {
        if (complete) throw new IOException("REV scan did not reach a clean EOF at byte " + pos, e);
        logger.debug("Native revlog parse error at offset {}: {}", pos, e.getMessage());
        break;
      }
    }

    applyModels(devices, signalValues);
    if (selectedSignals != null) signalValues.keySet().removeIf(key -> !selectedSignals.contains(key));

    // Convert signal values to RevLogSignal objects
    Map<String, RevLogSignal> signals = new LinkedHashMap<>();
    for (var entry : signalValues.entrySet()) {
      String key = entry.getKey();
      int slashIndex = key.lastIndexOf('/');
      if (slashIndex > 0) {
        String deviceKey = key.substring(0, slashIndex);
        String signalName = key.substring(slashIndex + 1);
        String unit = getSignalUnit(signalName);
        signals.put(key, new RevLogSignal(signalName, deviceKey, entry.getValue(), unit));
      }
    }

    logger.info("Parsed native revlog: {} devices, {} signals, {} records from {}",
        devices.size(), signals.size(), recordCount, path.getFileName());

    return new ParsedRevLog(
        path.toString(),
        filenameTimestamp,
        devices,
        signals,
        minTimestamp == Double.MAX_VALUE ? 0 : minTimestamp,
        maxTimestamp == Double.NEGATIVE_INFINITY ? 0 : maxTimestamp,
        recordCount);
  }

  /**
   * Parses firmware entry chunks (entry ID 1).
   * Each chunk: 4-byte CAN ID (LE) + 6-byte firmware data.
   */
  private void parseFirmwareEntry(byte[] data, int offset, int size,
      Map<Integer, RevLogDevice> devices) {
    for (int i = offset; i + 10 <= offset + size; i += 10) {
      int canMsgId = readI32LE(data, i);
      int deviceType = (canMsgId >> 24) & 0x1F;
      int deviceId = canMsgId & 0x3F;

      String typeName = switch (deviceType) {
        case 2 -> "SPARK"; // the family; status 0 names the model (applyModels)
        case 12 -> "Servo Hub";
        case 7 -> "MAXSpline Encoder";
        default -> "Unknown (" + deviceType + ")";
      };

      // Parse firmware version from the 6-byte data
      String firmware = null;
      if (deviceType == 2 || deviceType == 12) {
        // Spark/Servo Hub: byte 0=major, 1=minor, 2-3=build (BE u16)
        int major = data[i + 4] & 0xFF;
        int minor = data[i + 5] & 0xFF;
        int build = ((data[i + 6] & 0xFF) << 8) | (data[i + 7] & 0xFF);
        firmware = major + "." + minor + "." + build;
      }

      // Key by composite (deviceType << 6 | deviceId) to avoid collisions
      // when different device types share the same CAN ID
      int compositeKey = (deviceType << 6) | deviceId;
      if (!devices.containsKey(compositeKey)) {
        devices.put(compositeKey, new RevLogDevice(deviceId, typeName, firmware));
        logger.debug("Native revlog device: CAN ID {}, type {}, firmware {}",
            deviceId, typeName, firmware);
      }
    }
  }

  /**
   * Parses periodic status entry chunks (entry ID 2).
   * Each chunk: 4-byte timestamp ms (LE) + 4-byte CAN ID (LE) + 8-byte CAN data.
   */
  private void parsePeriodicEntry(byte[] data, int offset, int size,
      Map<Integer, RevLogDevice> devices,
      SignalValues signalValues) {
    for (int i = offset; i + 16 <= offset + size; i += 16) {
      long timestampMs = readU32LE(data, i);
      int canMsgId = readI32LE(data, i + 4);
      byte[] canData = new byte[8];
      System.arraycopy(data, i + 8, canData, 0, 8);

      double timestamp = timestampMs / 1000.0;

      // Extract device info from CAN message ID
      int deviceType = (canMsgId >> 24) & 0x1F;
      int deviceId = canMsgId & 0x3F;

      // Key by composite (deviceType << 6 | deviceId) to avoid collisions
      // when different device types share the same CAN ID
      int compositeKey = (deviceType << 6) | deviceId;
      if (!devices.containsKey(compositeKey)) {
        String typeName = switch (deviceType) {
          case 2 -> "SPARK"; // the family; status 0 names the model (applyModels)
          case 12 -> "Servo Hub";
          case 7 -> "MAXSpline Encoder";
          default -> "Unknown (" + deviceType + ")";
        };
        devices.put(compositeKey, new RevLogDevice(deviceId, typeName));
      }

      String deviceKey = devices.get(compositeKey).deviceKey();

      // The chunk holds the frame's 29-bit CAN arbitration ID (device type, manufacturer, API
      // class and index, device number). The decoder looks it up with the device bits masked:
      // periodic status frames are API class 46 (firmware 25+); the legacy class 6 status 0
      // that firmware still sends for old followers carries no data and decodes to nothing.
      Map<String, Double> decodedSignals = decodeSelected(canMsgId & 0x1FFF_FFFF, canData, deviceKey);

      for (var signalEntry : decodedSignals.entrySet()) {
        String signalKey = deviceKey + "/" + signalEntry.getKey();
        signalValues.append(signalKey, timestamp, signalEntry.getValue());
      }
    }
  }

  /** Reads a variable-length unsigned integer (LE, 1-4 bytes). */
  private static long readVarInt(byte[] data, int offset, int len) {
    long value = 0;
    for (int i = 0; i < len; i++) {
      value |= (long) (data[offset + i] & 0xFF) << (8 * i);
    }
    return value;
  }

  /** Reads a 32-bit unsigned integer (LE). */
  private static long readU32LE(byte[] data, int offset) {
    return ((long) (data[offset] & 0xFF))
        | ((long) (data[offset + 1] & 0xFF) << 8)
        | ((long) (data[offset + 2] & 0xFF) << 16)
        | ((long) (data[offset + 3] & 0xFF) << 24);
  }

  /** Reads a 32-bit signed integer (LE). */
  private static int readI32LE(byte[] data, int offset) {
    return (data[offset] & 0xFF)
        | ((data[offset + 1] & 0xFF) << 8)
        | ((data[offset + 2] & 0xFF) << 16)
        | ((data[offset + 3] & 0xFF) << 24);
  }

  /** The unit the DBC gives a signal ("" when it has none, or the signal is unknown). */
  /** A hash of the DBC text that decodes this parser's signals ("" for one built in code). */
  public String dbcContentHash() {
    return decoder.getDatabase().contentHash();
  }

  /**
   * The model a SPARK's status 0 SPARK_MODEL field names, by the codes of REVLib's
   * SparkLowLevel.SparkModel (2026.0.5): 1 = SPARK Flex, 2 = SPARK MAX, 0 = unknown.
   */
  static String sparkModelLabel(double code) {
    if (code == 1) return "SPARK Flex";
    if (code == 2) return "SPARK MAX";
    return null;
  }

  /**
   * Labels each SPARK by the model its status 0 frames report and re-keys its signals. The CAN
   * ID says only "SPARK" (device type 2 is the family, MAX and Flex alike); the frame's
   * SPARK_MODEL field says which, so the label and key become SparkMax_N or SparkFlex_N. A
   * device whose frames never carried the field keeps the family label (key Spark_N).
   */
  static void applyModels(Map<Integer, RevLogDevice> devices,
      SignalValues signalValues) {
    for (var entry : new ArrayList<>(devices.entrySet())) {
      var device = entry.getValue();
      if (!"SPARK".equals(device.deviceType())) continue;
      var samples = signalValues.get(device.deviceKey() + "/SparkModel");
      if (samples == null || samples.isEmpty()) continue;
      var label = sparkModelLabel(((Number) samples.get(0).value()).doubleValue());
      if (label == null) continue;
      var relabeled = new RevLogDevice(device.canId(), label, device.firmwareVersion());
      var oldPrefix = device.deviceKey() + "/";
      var newPrefix = relabeled.deviceKey() + "/";
      for (var key : new ArrayList<>(signalValues.keySet())) {
        if (key.startsWith(oldPrefix)) {
          signalValues.put(newPrefix + key.substring(oldPrefix.length()), signalValues.remove(key));
        }
      }
      devices.put(entry.getKey(), relabeled);
    }
  }

  private String getSignalUnit(String signalName) {
    return decoder.getDatabase().messages().values().stream()
        .map(m -> m.getSignal(signalName))
        .filter(Objects::nonNull)
        .map(DbcSignal::unit)
        .findFirst().orElse("");
  }

  /** WPILOG magic header bytes: "WPILOG" in ASCII. */
  private static final byte[] WPILOG_MAGIC = {'W', 'P', 'I', 'L', 'O', 'G'};

  /**
   * Checks if a file starts with the WPILOG magic header.
   *
   * @param path The file to check
   * @return true if the file starts with "WPILOG"
   */
  private static boolean hasWpilogMagic(Path path) {
    try {
      byte[] header = new byte[WPILOG_MAGIC.length];
      try (var is = Files.newInputStream(path)) {
        int read = is.read(header);
        if (read < WPILOG_MAGIC.length) return false;
      }
      for (int i = 0; i < WPILOG_MAGIC.length; i++) {
        if (header[i] != WPILOG_MAGIC[i]) return false;
      }
      return true;
    } catch (IOException e) {
      return false;
    }
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
