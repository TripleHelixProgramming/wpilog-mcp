package org.triplehelix.wpilogmcp.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.msgpack.value.ValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Serializes and deserializes {@link ParsedLog} to/from MessagePack binary format.
 *
 * <p>File format:
 * <ol>
 *   <li>Header: format version (int), server version (string), original path (string),
 *       file size (long), mtime (long), fingerprint (string), created at (long)</li>
 *   <li>Log metadata: minTimestamp (double), maxTimestamp (double), truncated (bool),
 *       truncationMessage (string or nil), entry count (int)</li>
 *   <li>For each entry: id (int), name (string), type (string), metadata (string),
 *       value count (int), then values as timestamp (double) + typed value</li>
 *   <li>CRC-32 checksum trailer (4 bytes, little-endian) over all preceding bytes</li>
 * </ol>
 *
 * <p>Values are written according to their entry type, using MessagePack's native type system.
 * Struct values ({@code Map<String,Object>}) are written as MessagePack maps recursively.
 *
 * <p>The CRC-32 checksum detects silent corruption (bit flips, partial writes, filesystem errors).
 * On read, the checksum is verified before returning data. Corrupt files are rejected and deleted.
 *
 * @since 0.5.0
 */
public class DiskCacheSerializer {
  private static final Logger logger = LoggerFactory.getLogger(DiskCacheSerializer.class);

  /** Current cache format version. Increment on any serialization change. */
  public static final int CURRENT_FORMAT_VERSION = 3;

  /**
   * Writes a ParsedLog to a cache file.
   *
   * @param log The parsed log to serialize
   * @param cacheFile The output file path
   * @param metadata The cache metadata header
   * @throws IOException if serialization fails
   */
  public void write(ParsedLog log, Path cacheFile, CacheMetadata metadata) throws IOException {
    var crc = new CRC32();
    try (OutputStream rawOut = new BufferedOutputStream(Files.newOutputStream(cacheFile))) {
      // Write MessagePack payload, computing CRC as bytes flow through
      var checkedOut = new CheckedOutputStream(rawOut, crc) {
        @Override public void close() throws IOException {
          // Don't close the underlying stream — we still need it for the CRC trailer
          flush();
        }
      };

      var packer = MessagePack.newDefaultPacker(checkedOut);
      try {
        writePayload(packer, log, metadata);
      } finally {
        packer.close(); // flushes and releases internal buffers; checkedOut.close() is a no-op
      }
      // CRC now covers all MessagePack bytes.

      // Append 4-byte CRC-32 trailer directly (bypassing the checksum stream)
      byte[] crcBytes = ByteBuffer.allocate(4)
          .order(ByteOrder.LITTLE_ENDIAN)
          .putInt((int) crc.getValue())
          .array();
      rawOut.write(crcBytes);
    }
  }

  private void writePayload(MessagePacker packer, ParsedLog log, CacheMetadata metadata)
      throws IOException {
    // Header
    packer.packInt(metadata.cacheFormatVersion());
    packer.packString(metadata.serverVersion());
    packer.packString(metadata.originalPath());
    packer.packLong(metadata.originalSizeBytes());
    packer.packLong(metadata.originalLastModified());
    packer.packString(metadata.contentFingerprint());
    packer.packLong(metadata.createdAt());

    // Log metadata
    packer.packDouble(log.minTimestamp());
    packer.packDouble(log.maxTimestamp());
    packer.packBoolean(log.truncated());
    if (log.truncationMessage() != null) {
      packer.packString(log.truncationMessage());
    } else {
      packer.packNil();
    }

    // Path
    packer.packString(log.path());

    // Entries
    var entries = log.entries();
    packer.packInt(entries.size());

    for (var entry : entries.entrySet()) {
      String name = entry.getKey();
      EntryInfo info = entry.getValue();

      // Entry metadata
      packer.packInt(info.id());
      packer.packString(info.name());
      packer.packString(info.type());
      if (info.metadata() != null) {
        packer.packString(info.metadata());
      } else {
        packer.packNil();
      }

      // Values for this entry
      List<TimestampedValue> values = log.values().get(name);
      if (values == null) {
        packer.packInt(0);
        continue;
      }

      packer.packInt(values.size());
      for (TimestampedValue tv : values) {
        packer.packDouble(tv.timestamp());
        packValue(packer, tv.value());
      }
    }
  }

  /**
   * Reads a ParsedLog from a cache file.
   *
   * @param cacheFile The cache file to read
   * @return The deserialized ParsedLog, or null if the file is invalid/corrupt
   */
  public ParsedLog read(Path cacheFile) {
    try {
      // Read the entire file, split into payload (all but last 4 bytes) and CRC trailer
      byte[] fileBytes = Files.readAllBytes(cacheFile);
      if (fileBytes.length < 5) { // minimum: 1 byte payload + 4 byte CRC
        logger.debug("Cache file too small: {} bytes", fileBytes.length);
        return null;
      }

      int payloadLength = fileBytes.length - 4;
      byte[] crcBytes = java.util.Arrays.copyOfRange(fileBytes, payloadLength, fileBytes.length);
      int storedCrc = ByteBuffer.wrap(crcBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();

      // Verify checksum
      var crc = new CRC32();
      crc.update(fileBytes, 0, payloadLength);
      int computedCrc = (int) crc.getValue();

      if (storedCrc != computedCrc) {
        logger.warn("Cache checksum mismatch for {}: stored=0x{}, computed=0x{}",
            cacheFile.getFileName(),
            Integer.toHexString(storedCrc), Integer.toHexString(computedCrc));
        return null;
      }

      // Deserialize the verified payload
      try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(fileBytes, 0, payloadLength)) {
        return readPayload(unpacker);
      }

    } catch (Exception e) {
      logger.warn("Failed to read cache file {}: {}", cacheFile, e.getMessage());
      return null;
    }
  }

  private ParsedLog readPayload(MessageUnpacker unpacker) throws IOException {
    // Header
    int formatVersion = unpacker.unpackInt();
    if (formatVersion != CURRENT_FORMAT_VERSION) {
      logger.debug("Cache format version mismatch: expected {}, got {}",
          CURRENT_FORMAT_VERSION, formatVersion);
      return null;
    }

    String serverVersion = unpacker.unpackString();
    String originalPath = unpacker.unpackString();
    long originalSize = unpacker.unpackLong();
    long originalMtime = unpacker.unpackLong();
    String fingerprint = unpacker.unpackString();
    long createdAt = unpacker.unpackLong();

    // Log metadata
    double minTimestamp = unpacker.unpackDouble();
    double maxTimestamp = unpacker.unpackDouble();
    boolean truncated = unpacker.unpackBoolean();
    String truncationMessage = unpacker.tryUnpackNil() ? null : unpacker.unpackString();
    String path = unpacker.unpackString();

    // Entries
    int entryCount = unpacker.unpackInt();
    var entries = new HashMap<String, EntryInfo>(entryCount);
    var values = new HashMap<String, List<TimestampedValue>>(entryCount);

    for (int e = 0; e < entryCount; e++) {
      int id = unpacker.unpackInt();
      String name = unpacker.unpackString();
      String type = unpacker.unpackString();
      String meta = unpacker.tryUnpackNil() ? null : unpacker.unpackString();

      entries.put(name, new EntryInfo(id, name, type, meta));

      int valueCount = unpacker.unpackInt();
      var valueList = new ArrayList<TimestampedValue>(valueCount);

      for (int v = 0; v < valueCount; v++) {
        double timestamp = unpacker.unpackDouble();
        Object value = unpackTypedValue(unpacker, type);
        valueList.add(new TimestampedValue(timestamp, value));
      }

      values.put(name, valueList);
    }

    return new ParsedLog(path, entries, values, minTimestamp, maxTimestamp,
        truncated, truncationMessage);
  }

  /**
   * Reads only the metadata header from a cache file without deserializing the full log.
   *
   * @param cacheFile The cache file
   * @return The metadata, or null if the file is invalid
   */
  /**
   * Reads only the metadata header from a cache file.
   *
   * <p>Does NOT verify the CRC checksum (that would require reading the entire file).
   * Use {@link #read(Path)} for full integrity verification.
   *
   * @param cacheFile The cache file
   * @return The metadata, or null if the file is invalid
   */
  public CacheMetadata readMetadata(Path cacheFile) {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(cacheFile));
         MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(in)) {

      int formatVersion = unpacker.unpackInt();
      String serverVersion = unpacker.unpackString();
      String originalPath = unpacker.unpackString();
      long originalSize = unpacker.unpackLong();
      long originalMtime = unpacker.unpackLong();
      String fingerprint = unpacker.unpackString();
      long createdAt = unpacker.unpackLong();

      return new CacheMetadata(formatVersion, serverVersion, originalPath,
          originalSize, originalMtime, fingerprint, createdAt);

    } catch (Exception e) {
      logger.debug("Failed to read cache metadata from {}: {}", cacheFile, e.getMessage());
      return null;
    }
  }

  // ==================== Value Serialization ====================

  private void packValue(MessagePacker packer, Object value) throws IOException {
    if (value == null) {
      packer.packNil();
    } else if (value instanceof Double d) {
      packer.packDouble(d);
    } else if (value instanceof Float f) {
      packer.packFloat(f);
    } else if (value instanceof Long l) {
      packer.packLong(l);
    } else if (value instanceof Integer i) {
      packer.packInt(i);
    } else if (value instanceof Boolean b) {
      packer.packBoolean(b);
    } else if (value instanceof String s) {
      packer.packString(s);
    } else if (value instanceof byte[] bytes) {
      packer.packBinaryHeader(bytes.length);
      packer.writePayload(bytes);
    } else if (value instanceof Map<?, ?> map) {
      packMap(packer, map);
    } else if (value instanceof List<?> list) {
      packer.packArrayHeader(list.size());
      for (Object item : list) {
        packValue(packer, item);
      }
    } else if (value instanceof double[] arr) {
      packer.packArrayHeader(arr.length);
      for (double d : arr) packer.packDouble(d);
    } else if (value instanceof float[] arr) {
      packer.packArrayHeader(arr.length);
      for (float f : arr) packer.packFloat(f);
    } else if (value instanceof long[] arr) {
      packer.packArrayHeader(arr.length);
      for (long l : arr) packer.packLong(l);
    } else if (value instanceof boolean[] arr) {
      packer.packArrayHeader(arr.length);
      for (boolean b : arr) packer.packBoolean(b);
    } else if (value instanceof String[] arr) {
      packer.packArrayHeader(arr.length);
      for (String s : arr) packer.packString(s);
    } else {
      // Fallback: serialize as string
      packer.packString(value.toString());
    }
  }

  @SuppressWarnings("unchecked")
  private void packMap(MessagePacker packer, Map<?, ?> map) throws IOException {
    packer.packMapHeader(map.size());
    for (var entry : map.entrySet()) {
      packer.packString(entry.getKey().toString());
      packValue(packer, entry.getValue());
    }
  }

  /**
   * Unpacks a value using the entry's declared type to reconstruct the correct Java type.
   *
   * <p>Primitive arrays ({@code double[]}, {@code long[]}, etc.) are stored as MessagePack
   * arrays of their element type. Without the type hint, they would deserialize as
   * {@code List<Object>}, which breaks code expecting the original array type.
   */
  private Object unpackTypedValue(MessageUnpacker unpacker, String entryType) throws IOException {
    // For primitive array types, reconstruct the original Java array type.
    // For int64, force Long to avoid MessagePack narrowing small values to Integer
    // (which would break instanceof Long checks in numeric extraction).
    return switch (entryType) {
      case "double[]" -> unpackDoubleArray(unpacker);
      case "float[]" -> unpackFloatArray(unpacker);
      case "int64[]" -> unpackLongArray(unpacker);
      case "boolean[]" -> unpackBooleanArray(unpacker);
      case "string[]" -> unpackStringArray(unpacker);
      case "int64" -> unpacker.unpackLong();
      default -> unpackValue(unpacker);
    };
  }

  private double[] unpackDoubleArray(MessageUnpacker unpacker) throws IOException {
    if (unpacker.getNextFormat().getValueType() == ValueType.NIL) {
      unpacker.unpackNil();
      return null;
    }
    int len = unpacker.unpackArrayHeader();
    double[] arr = new double[len];
    for (int i = 0; i < len; i++) arr[i] = unpacker.unpackDouble();
    return arr;
  }

  private float[] unpackFloatArray(MessageUnpacker unpacker) throws IOException {
    if (unpacker.getNextFormat().getValueType() == ValueType.NIL) {
      unpacker.unpackNil();
      return null;
    }
    int len = unpacker.unpackArrayHeader();
    float[] arr = new float[len];
    for (int i = 0; i < len; i++) arr[i] = (float) unpacker.unpackDouble();
    return arr;
  }

  private long[] unpackLongArray(MessageUnpacker unpacker) throws IOException {
    if (unpacker.getNextFormat().getValueType() == ValueType.NIL) {
      unpacker.unpackNil();
      return null;
    }
    int len = unpacker.unpackArrayHeader();
    long[] arr = new long[len];
    for (int i = 0; i < len; i++) arr[i] = unpacker.unpackLong();
    return arr;
  }

  private boolean[] unpackBooleanArray(MessageUnpacker unpacker) throws IOException {
    if (unpacker.getNextFormat().getValueType() == ValueType.NIL) {
      unpacker.unpackNil();
      return null;
    }
    int len = unpacker.unpackArrayHeader();
    boolean[] arr = new boolean[len];
    for (int i = 0; i < len; i++) arr[i] = unpacker.unpackBoolean();
    return arr;
  }

  private String[] unpackStringArray(MessageUnpacker unpacker) throws IOException {
    if (unpacker.getNextFormat().getValueType() == ValueType.NIL) {
      unpacker.unpackNil();
      return null;
    }
    int len = unpacker.unpackArrayHeader();
    String[] arr = new String[len];
    for (int i = 0; i < len; i++) arr[i] = unpacker.unpackString();
    return arr;
  }

  private Object unpackValue(MessageUnpacker unpacker) throws IOException {
    var format = unpacker.getNextFormat();
    var type = format.getValueType();

    return switch (type) {
      case NIL -> { unpacker.unpackNil(); yield null; }
      case BOOLEAN -> unpacker.unpackBoolean();
      case INTEGER -> {
        long val = unpacker.unpackLong();
        yield (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) ? (int) val : val;
      }
      case FLOAT -> {
        // Check the MessagePack format to distinguish 32-bit from 64-bit floats
        yield (format == org.msgpack.core.MessageFormat.FLOAT32)
            ? unpacker.unpackFloat()
            : unpacker.unpackDouble();
      }
      case STRING -> unpacker.unpackString();
      case BINARY -> {
        int len = unpacker.unpackBinaryHeader();
        yield unpacker.readPayload(len);
      }
      case ARRAY -> {
        int len = unpacker.unpackArrayHeader();
        var list = new ArrayList<Object>(len);
        for (int i = 0; i < len; i++) {
          list.add(unpackValue(unpacker));
        }
        yield list;
      }
      case MAP -> {
        int len = unpacker.unpackMapHeader();
        var map = new LinkedHashMap<String, Object>(len);
        for (int i = 0; i < len; i++) {
          String key = unpacker.unpackString();
          Object val = unpackValue(unpacker);
          map.put(key, val);
        }
        yield map;
      }
      default -> {
        // Skip unknown types
        Value v = unpacker.unpackValue();
        yield v.toString();
      }
    };
  }
}
