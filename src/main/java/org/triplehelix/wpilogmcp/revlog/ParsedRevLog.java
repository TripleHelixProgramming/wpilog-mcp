package org.triplehelix.wpilogmcp.revlog;

import java.util.Map;

/**
 * A parsed .revlog file containing decoded CAN signals from REV motor controllers.
 *
 * <p>The revlog format is a WPILOG-compatible binary format containing CAN bus data
 * captured from REV SPARK MAX/Flex motor controllers. This record contains:
 * <ul>
 *   <li>Device information (CAN IDs, types, firmware versions)</li>
 *   <li>Decoded signals (appliedOutput, velocity, temperature, etc.)</li>
 *   <li>Timing metadata for synchronization</li>
 * </ul>
 *
 * <p><b>Important:</b> Timestamps in this record are in the revlog's CLOCK_MONOTONIC
 * time base, not FPGA time. Synchronization with a .wpilog file is required before
 * comparing timestamps across logs.
 *
 * @param path The file path of the revlog
 * @param filenameTimestamp The timestamp extracted from the filename (e.g., "20260320_143052")
 * @param devices Map of CAN ID to device information
 * @param signals Map of signal keys to decoded signals (key format: "SparkMax_1/appliedOutput")
 * @param minTimestamp The earliest timestamp in seconds (revlog time base)
 * @param maxTimestamp The latest timestamp in seconds (revlog time base)
 * @param recordCount The total number of records parsed
 * @since 0.5.0
 */
public record ParsedRevLog(
    String path,
    String filenameTimestamp,
    Map<Integer, RevLogDevice> devices,
    Map<String, RevLogSignal> signals,
    double minTimestamp,
    double maxTimestamp,
    long recordCount) {

  /**
   * Gets the number of devices discovered in the revlog.
   *
   * @return The device count
   */
  public int deviceCount() {
    return devices.size();
  }

  /**
   * Gets the number of unique signals decoded.
   *
   * @return The signal count
   */
  public int signalCount() {
    return signals.size();
  }

  /**
   * Gets the duration of the revlog in seconds.
   *
   * @return The duration (maxTimestamp - minTimestamp)
   */
  public double duration() {
    return maxTimestamp - minTimestamp;
  }

  /**
   * Gets a device by its CAN ID.
   *
   * @param canId The CAN ID
   * @return The device, or null if not found
   */
  public RevLogDevice getDevice(int canId) {
    return devices.get(canId);
  }

  /**
   * Gets a signal by its full key.
   *
   * @param key The signal key (e.g., "SparkMax_1/appliedOutput")
   * @return The signal, or null if not found
   */
  public RevLogSignal getSignal(String key) {
    return signals.get(key);
  }

  /**
   * Gets a signal for a specific device and signal name.
   *
   * @param deviceKey The device key (e.g., "SparkMax_1")
   * @param signalName The signal name (e.g., "appliedOutput")
   * @return The signal, or null if not found
   */
  public RevLogSignal getSignal(String deviceKey, String signalName) {
    return signals.get(deviceKey + "/" + signalName);
  }
}
