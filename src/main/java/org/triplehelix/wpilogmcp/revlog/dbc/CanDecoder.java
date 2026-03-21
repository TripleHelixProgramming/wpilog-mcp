package org.triplehelix.wpilogmcp.revlog.dbc;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes raw CAN frames using DBC signal definitions.
 *
 * <p>The CanDecoder applies DBC message/signal definitions to raw CAN frame
 * data to extract named signal values. It handles the REV CAN protocol
 * specifics including arbitration ID structure.
 *
 * <p>REV CAN Arbitration ID structure (29-bit extended):
 * <pre>
 * Bits 28-24: Device Type (5 bits) - e.g., 2 = SPARK MAX
 * Bits 23-16: Manufacturer (8 bits) - 5 = REV Robotics
 * Bits 15-10: API Class (6 bits) - Identifies frame type
 * Bits 9-6:   API Index (4 bits) - Sub-type
 * Bits 5-0:   Device ID (6 bits) - CAN ID 0-63
 * </pre>
 *
 * @since 0.5.0
 */
public class CanDecoder {
  private static final Logger logger = LoggerFactory.getLogger(CanDecoder.class);

  /** Mask for device ID bits (bits 5-0). */
  public static final int DEVICE_ID_MASK = 0x3F;

  /** REV Robotics manufacturer ID. */
  public static final int REV_MANUFACTURER_ID = 5;

  /** SPARK MAX device type. */
  public static final int SPARK_MAX_DEVICE_TYPE = 2;

  /** SPARK Flex device type. */
  public static final int SPARK_FLEX_DEVICE_TYPE = 2; // Same as MAX for now

  private final DbcDatabase database;

  /**
   * Creates a new CanDecoder with the given DBC database.
   *
   * @param database The DBC database containing message/signal definitions
   */
  public CanDecoder(DbcDatabase database) {
    this.database = database;
  }

  /**
   * Decodes all signals from a CAN frame.
   *
   * @param arbitrationId The CAN arbitration ID
   * @param data The raw frame data (up to 8 bytes)
   * @return Map of signal names to decoded values, or empty map if message not found
   */
  public Map<String, Double> decode(int arbitrationId, byte[] data) {
    // Try exact match first
    Optional<DbcMessage> message = database.getMessage(arbitrationId);

    // If not found, try base ID match (ignoring device-specific bits)
    if (message.isEmpty()) {
      message = database.getMessageByBaseId(arbitrationId, DEVICE_ID_MASK);
    }

    if (message.isEmpty()) {
      logger.trace("No DBC message found for arbitration ID 0x{}", Integer.toHexString(arbitrationId));
      return Map.of();
    }

    return message.get().decodeAll(data);
  }

  /**
   * Decodes a specific signal from a CAN frame.
   *
   * @param arbitrationId The CAN arbitration ID
   * @param data The raw frame data
   * @param signalName The name of the signal to decode
   * @return The decoded signal value, or empty if not found
   */
  public Optional<Double> decodeSignal(int arbitrationId, byte[] data, String signalName) {
    Optional<DbcMessage> message = database.getMessage(arbitrationId);
    if (message.isEmpty()) {
      message = database.getMessageByBaseId(arbitrationId, DEVICE_ID_MASK);
    }

    if (message.isEmpty()) {
      return Optional.empty();
    }

    DbcSignal signal = message.get().getSignal(signalName);
    if (signal == null) {
      return Optional.empty();
    }

    try {
      return Optional.of(signal.decode(data));
    } catch (Exception e) {
      logger.warn("Failed to decode signal {}: {}", signalName, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Gets the message name for a CAN arbitration ID.
   *
   * @param arbitrationId The CAN arbitration ID
   * @return The message name, or empty if not found
   */
  public Optional<String> getMessageName(int arbitrationId) {
    Optional<DbcMessage> message = database.getMessage(arbitrationId);
    if (message.isEmpty()) {
      message = database.getMessageByBaseId(arbitrationId, DEVICE_ID_MASK);
    }
    return message.map(DbcMessage::name);
  }

  /**
   * Extracts the device ID from a CAN arbitration ID.
   *
   * @param arbitrationId The CAN arbitration ID
   * @return The device ID (0-63)
   */
  public static int extractDeviceId(int arbitrationId) {
    return arbitrationId & DEVICE_ID_MASK;
  }

  /**
   * Extracts the manufacturer ID from a CAN arbitration ID.
   *
   * @param arbitrationId The CAN arbitration ID
   * @return The manufacturer ID
   */
  public static int extractManufacturerId(int arbitrationId) {
    return (arbitrationId >> 16) & 0xFF;
  }

  /**
   * Extracts the device type from a CAN arbitration ID.
   *
   * @param arbitrationId The CAN arbitration ID
   * @return The device type code
   */
  public static int extractDeviceType(int arbitrationId) {
    return (arbitrationId >> 24) & 0x1F;
  }

  /**
   * Extracts the API class from a CAN arbitration ID.
   *
   * @param arbitrationId The CAN arbitration ID
   * @return The API class
   */
  public static int extractApiClass(int arbitrationId) {
    return (arbitrationId >> 10) & 0x3F;
  }

  /**
   * Extracts the API index from a CAN arbitration ID.
   *
   * @param arbitrationId The CAN arbitration ID
   * @return The API index
   */
  public static int extractApiIndex(int arbitrationId) {
    return (arbitrationId >> 6) & 0x0F;
  }

  /**
   * Checks if a CAN arbitration ID is from a REV device.
   *
   * @param arbitrationId The CAN arbitration ID
   * @return true if this is a REV device
   */
  public static boolean isRevDevice(int arbitrationId) {
    return extractManufacturerId(arbitrationId) == REV_MANUFACTURER_ID;
  }

  /**
   * Gets the device type name for a REV device.
   *
   * @param arbitrationId The CAN arbitration ID
   * @return The device type name, or "Unknown" if not recognized
   */
  public static String getDeviceTypeName(int arbitrationId) {
    int deviceType = extractDeviceType(arbitrationId);
    return switch (deviceType) {
      case 2 -> "SPARK MAX";
      default -> "Unknown REV Device";
    };
  }

  /**
   * Constructs a CAN arbitration ID from components.
   *
   * @param deviceType The device type (5 bits)
   * @param manufacturerId The manufacturer ID (8 bits)
   * @param apiClass The API class (6 bits)
   * @param apiIndex The API index (4 bits)
   * @param deviceId The device CAN ID (6 bits)
   * @return The constructed arbitration ID
   */
  public static int buildArbitrationId(int deviceType, int manufacturerId,
                                       int apiClass, int apiIndex, int deviceId) {
    return ((deviceType & 0x1F) << 24)
        | ((manufacturerId & 0xFF) << 16)
        | ((apiClass & 0x3F) << 10)
        | ((apiIndex & 0x0F) << 6)
        | (deviceId & 0x3F);
  }

  /**
   * Gets the underlying DBC database.
   *
   * @return The DBC database
   */
  public DbcDatabase getDatabase() {
    return database;
  }
}
