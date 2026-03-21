package org.triplehelix.wpilogmcp.revlog;

/**
 * Metadata about a REV motor controller device discovered in a .revlog file.
 *
 * <p>Each device is identified by its CAN ID and includes information about its
 * type (SPARK MAX or SPARK Flex) and firmware version if available.
 *
 * @param canId The CAN bus ID of the device (0-62)
 * @param deviceType The device type (e.g., "SPARK MAX", "SPARK Flex")
 * @param firmwareVersion The firmware version string, or null if not available
 * @since 0.5.0
 */
public record RevLogDevice(
    int canId,
    String deviceType,
    String firmwareVersion) {

  /**
   * Creates a device with unknown firmware version.
   *
   * @param canId The CAN ID
   * @param deviceType The device type
   */
  public RevLogDevice(int canId, String deviceType) {
    this(canId, deviceType, null);
  }

  /**
   * Gets a unique key for this device suitable for use in signal names.
   *
   * <p>The key format is: "{DeviceType}_{CanId}" with spaces removed and proper casing.
   * Examples: "SparkMax_1", "SparkFlex_5"
   *
   * @return The device key
   */
  public String deviceKey() {
    // Convert "SPARK MAX" -> "SparkMax", "SPARK Flex" -> "SparkFlex"
    String normalizedType = normalizeDeviceType(deviceType);
    return normalizedType + "_" + canId;
  }

  /**
   * Normalizes device type to PascalCase without spaces.
   */
  private static String normalizeDeviceType(String type) {
    if (type == null || type.isBlank()) {
      return "Unknown";
    }
    // Split by spaces and capitalize each word
    StringBuilder result = new StringBuilder();
    for (String word : type.split("\\s+")) {
      if (!word.isEmpty()) {
        result.append(Character.toUpperCase(word.charAt(0)));
        if (word.length() > 1) {
          result.append(word.substring(1).toLowerCase());
        }
      }
    }
    return result.isEmpty() ? "Unknown" : result.toString();
  }
}
