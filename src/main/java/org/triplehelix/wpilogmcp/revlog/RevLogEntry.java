package org.triplehelix.wpilogmcp.revlog;

/**
 * A raw entry from a .revlog file before signal decoding.
 *
 * <p>RevLog files use the WPILOG binary format with custom entry types for CAN data:
 * <ul>
 *   <li>Firmware records (entry type "rawBytes"): Device identification</li>
 *   <li>Periodic status frame records: Timestamped CAN data</li>
 * </ul>
 *
 * <p>This record holds the raw data before DBC decoding is applied.
 *
 * @param timestamp The timestamp in microseconds (CLOCK_MONOTONIC)
 * @param canId The CAN arbitration ID (includes device ID and frame type)
 * @param data The raw CAN frame data (up to 8 bytes)
 * @param entryType The entry type from the log (e.g., "rawBytes", "Periodic Status 0")
 * @since 0.5.0
 */
public record RevLogEntry(
    long timestamp,
    int canId,
    byte[] data,
    String entryType) {

  /**
   * Extracts the device CAN ID from the arbitration ID.
   *
   * <p>REV CAN protocol uses bits 5-0 of the 29-bit arbitration ID for the device ID.
   *
   * @return The device CAN ID (0-63)
   */
  public int deviceId() {
    return canId & 0x3F;
  }

  /**
   * Extracts the API class from the arbitration ID.
   *
   * <p>REV CAN protocol uses bits 15-10 for the API class which identifies
   * the type of message (status frame number, control frame, etc.).
   *
   * @return The API class
   */
  public int apiClass() {
    return (canId >> 10) & 0x3F;
  }

  /**
   * Extracts the API index from the arbitration ID.
   *
   * <p>REV CAN protocol uses bits 9-6 for the API index which provides
   * sub-classification within the API class.
   *
   * @return The API index
   */
  public int apiIndex() {
    return (canId >> 6) & 0x0F;
  }

  /**
   * Gets the manufacturer ID from the arbitration ID.
   *
   * <p>For REV devices, this should be 5 (REV Robotics).
   *
   * @return The manufacturer ID
   */
  public int manufacturerId() {
    return (canId >> 16) & 0xFF;
  }

  /**
   * Gets the device type from the arbitration ID.
   *
   * <p>Identifies the type of device (SPARK MAX = 2, etc.).
   *
   * @return The device type code
   */
  public int deviceType() {
    return (canId >> 24) & 0x1F;
  }

  /**
   * Checks if this entry is a periodic status frame.
   *
   * @return true if this is a periodic status frame
   */
  public boolean isPeriodicStatus() {
    return entryType != null && entryType.startsWith("Periodic Status");
  }

  /**
   * Gets the status frame number if this is a periodic status frame.
   *
   * @return The status frame number (0-7), or -1 if not a periodic status frame
   */
  public int statusFrameNumber() {
    if (!isPeriodicStatus()) return -1;
    try {
      return Integer.parseInt(entryType.substring("Periodic Status ".length()).trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
