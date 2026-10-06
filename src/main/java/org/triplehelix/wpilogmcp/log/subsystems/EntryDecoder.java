/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log.subsystems;

import edu.wpi.first.util.datalog.DataLogRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

/**
 * Decodes values from WPILib DataLogRecord based on entry type.
 *
 * <p>Handles primitive types directly and decodes structs by the log's own schemas
 * ({@link StructSchemas}). Shared by eager parsing and lazy on-demand decoding.
 *
 * @since 0.8.0
 */
public final class EntryDecoder {
  private static final Logger logger = LoggerFactory.getLogger(EntryDecoder.class);

  private EntryDecoder() {}

  /** The client already decoded primitive NT4 values; only arrays and opaque types need conversion. */
  public static Object decodeNetworkValue(Object value, String type, StructSchemas schemas) {
    return switch (type) {
      case "boolean", "int64", "float", "double", "string", "json" -> value;
      case "structschema" -> new String((byte[]) value, java.nio.charset.StandardCharsets.UTF_8);
      case "raw" -> ((byte[]) value).clone();
      case "boolean[]" -> {
        var list = (java.util.List<?>) value; boolean[] array = new boolean[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = (Boolean) list.get(i);
        yield array;
      }
      case "int64[]" -> ((java.util.List<?>) value).stream().mapToLong(v -> ((Number) v).longValue()).toArray();
      case "float[]" -> {
        var list = (java.util.List<?>) value; float[] array = new float[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = ((Number) list.get(i)).floatValue();
        yield array;
      }
      case "double[]" -> ((java.util.List<?>) value).stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray();
      case "string[]" -> ((java.util.List<?>) value).toArray(String[]::new);
      default -> {
        var bytes = (byte[]) value;
        yield isStruct(type) ? schemas.decode(type, bytes)
            : bytes.length <= 100 ? BinaryReader.bytesToHex(bytes) : "<" + bytes.length + " bytes>";
      }
    };
  }

  /**
   * Decodes a value from a DataLogRecord based on its type.
   *
   * @param record The log record
   * @param type The entry type (e.g., "double", "struct:Pose2d", "int64[]")
   * @param schemas The log's struct schemas
   * @return The decoded value
   * @throws org.triplehelix.wpilogmcp.log.struct.StructDecodeException when a struct record has
   *     no usable schema or does not fit it
   */
  public static Object decodeValue(DataLogRecord record, String type, StructSchemas schemas) {
    return switch (type) {
      case "boolean" -> record.getBoolean();
      case "int64" -> record.getInteger();
      case "float" -> record.getFloat();
      case "double" -> record.getDouble();
      case "string", "json", "structschema" -> record.getString();
      case "boolean[]" -> record.getBooleanArray();
      case "int64[]" -> record.getIntegerArray();
      case "float[]" -> record.getFloatArray();
      case "double[]" -> record.getDoubleArray();
      case "string[]" -> record.getStringArray();
      case "raw" -> record.getRaw();
      default -> {
        if (isStruct(type)) {
          yield schemas.decode(type, record.getRaw());
        }
        logger.debug("Unknown WPILib data type: '{}', falling back to raw bytes", type);
        byte[] raw = record.getRaw();
        yield raw.length <= 100 ? BinaryReader.bytesToHex(raw) : "<" + raw.length + " bytes>";
      }
    };
  }

  /** Whether an entry type is a struct or struct array. */
  public static boolean isStruct(String type) {
    return type.startsWith("struct:") || type.startsWith("structarray:");
  }

  /**
   * Why a record could not be decoded as its declared type, for {@code DecodeProblem}: the
   * record's size against the type, since WPILib's decode exceptions often carry no message.
   *
   * @param record The record, or null when it could not even be read
   * @param type The entry's declared type
   * @param e The exception
   */
  public static String malformedMessage(edu.wpi.first.util.datalog.DataLogRecord record,
      String type, Exception e) {
    var detail = e.getMessage() != null ? " (" + e.getMessage() + ")" : "";
    if (record == null) return "record could not be read as " + type + detail;
    return "record of " + record.getSize() + " bytes cannot be read as " + type + detail;
  }
}
