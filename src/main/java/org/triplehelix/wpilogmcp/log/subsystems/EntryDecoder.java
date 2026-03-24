package org.triplehelix.wpilogmcp.log.subsystems;

import edu.wpi.first.util.datalog.DataLogRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes values from WPILib DataLogRecord based on entry type.
 *
 * <p>Handles primitive types directly and delegates struct decoding to a
 * {@link StructDecoderRegistry}. Extracted from {@link LogParser} so both
 * eager parsing and lazy on-demand decoding can share the same logic.
 *
 * @since 0.8.0
 */
public final class EntryDecoder {
  private static final Logger logger = LoggerFactory.getLogger(EntryDecoder.class);

  private EntryDecoder() {}

  /**
   * Decodes a value from a DataLogRecord based on its type.
   *
   * @param record The log record
   * @param type The entry type (e.g., "double", "struct:Pose2d", "int64[]")
   * @param decoderRegistry The struct decoder registry for complex types
   * @return The decoded value
   */
  public static Object decodeValue(DataLogRecord record, String type,
      StructDecoderRegistry decoderRegistry) {
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
        logger.debug("Unknown WPILib data type: '{}', falling back to raw bytes", type);
        byte[] raw = record.getRaw();
        yield raw.length <= 100 ? BinaryReader.bytesToHex(raw) : "<" + raw.length + " bytes>";
      }
    };
  }
}
