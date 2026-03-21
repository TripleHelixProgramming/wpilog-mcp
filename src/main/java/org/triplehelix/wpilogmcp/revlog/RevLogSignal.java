package org.triplehelix.wpilogmcp.revlog;

import java.util.List;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * A decoded CAN signal from a REV motor controller with timestamped values.
 *
 * <p>Signals are extracted from CAN status frames using DBC definitions. Each signal
 * contains a time series of values with timestamps in microseconds from the revlog's
 * CLOCK_MONOTONIC time base (not FPGA time - synchronization is required).
 *
 * @param name The signal name (e.g., "appliedOutput", "velocity", "temperature")
 * @param deviceKey The device identifier (e.g., "SparkMax_1")
 * @param values The timestamped values (timestamp in seconds, relative to revlog start)
 * @param unit The signal unit (e.g., "duty_cycle", "rpm", "degC", "A", "V")
 * @since 0.5.0
 */
public record RevLogSignal(
    String name,
    String deviceKey,
    List<TimestampedValue> values,
    String unit) {

  /**
   * Gets the number of samples in this signal.
   *
   * @return The sample count
   */
  public int sampleCount() {
    return values.size();
  }

  /**
   * Gets the full signal key for use in entry maps.
   *
   * <p>Format: "{deviceKey}/{signalName}"
   * Example: "SparkMax_1/appliedOutput"
   *
   * @return The full signal key
   */
  public String fullKey() {
    return deviceKey + "/" + name;
  }

  /**
   * Gets the minimum timestamp in this signal.
   *
   * @return The minimum timestamp in seconds, or 0 if empty
   */
  public double minTimestamp() {
    if (values.isEmpty()) return 0;
    return values.get(0).timestamp();
  }

  /**
   * Gets the maximum timestamp in this signal.
   *
   * @return The maximum timestamp in seconds, or 0 if empty
   */
  public double maxTimestamp() {
    if (values.isEmpty()) return 0;
    return values.get(values.size() - 1).timestamp();
  }
}
