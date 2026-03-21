package org.triplehelix.wpilogmcp.revlog.dbc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A CAN message definition from a DBC file.
 *
 * <p>Each message represents a CAN frame with a specific arbitration ID. Messages
 * contain one or more signals that define how to interpret the frame's data bytes.
 *
 * <p>Example DBC message definition:
 * <pre>
 * BO_ 0x2051800 Periodic_Status_0: 8 SparkMax
 *  SG_ AppliedOutput : 0|16@1- (0.0001,0) [-1|1] "duty_cycle"
 *  SG_ Faults : 16|16@1+ (1,0) [0|65535] ""
 * </pre>
 *
 * @param id The CAN arbitration ID (29-bit for extended, 11-bit for standard)
 * @param name The message name (e.g., "Periodic_Status_0")
 * @param dlc The Data Length Code (number of data bytes, typically 8)
 * @param transmitter The transmitter node name (e.g., "SparkMax")
 * @param signals Map of signal names to their definitions
 * @since 0.5.0
 */
public record DbcMessage(
    int id,
    String name,
    int dlc,
    String transmitter,
    Map<String, DbcSignal> signals) {

  /**
   * Creates a DbcMessage with an immutable copy of the signals map.
   */
  public DbcMessage {
    signals = Collections.unmodifiableMap(new LinkedHashMap<>(signals));
  }

  /**
   * Gets a signal by name.
   *
   * @param signalName The signal name
   * @return The signal definition, or null if not found
   */
  public DbcSignal getSignal(String signalName) {
    return signals.get(signalName);
  }

  /**
   * Checks if this message has a signal with the given name.
   *
   * @param signalName The signal name
   * @return true if the signal exists
   */
  public boolean hasSignal(String signalName) {
    return signals.containsKey(signalName);
  }

  /**
   * Gets the number of signals in this message.
   *
   * @return The signal count
   */
  public int signalCount() {
    return signals.size();
  }

  /**
   * Decodes all signals from raw CAN frame data.
   *
   * @param data The raw CAN frame data
   * @return Map of signal names to decoded values
   */
  public Map<String, Double> decodeAll(byte[] data) {
    Map<String, Double> decoded = new LinkedHashMap<>();
    for (var entry : signals.entrySet()) {
      try {
        double value = entry.getValue().decode(data);
        decoded.put(entry.getKey(), value);
      } catch (Exception e) {
        // Skip signals that can't be decoded from this data
      }
    }
    return decoded;
  }

  /**
   * Creates a builder for constructing DbcMessage instances.
   *
   * @param id The CAN arbitration ID
   * @param name The message name
   * @return A new builder
   */
  public static Builder builder(int id, String name) {
    return new Builder(id, name);
  }

  /**
   * Builder for DbcMessage instances.
   */
  public static class Builder {
    private final int id;
    private final String name;
    private int dlc = 8;
    private String transmitter = "";
    private final Map<String, DbcSignal> signals = new LinkedHashMap<>();

    private Builder(int id, String name) {
      this.id = id;
      this.name = name;
    }

    public Builder dlc(int dlc) {
      this.dlc = dlc;
      return this;
    }

    public Builder transmitter(String transmitter) {
      this.transmitter = transmitter;
      return this;
    }

    public Builder addSignal(DbcSignal signal) {
      this.signals.put(signal.name(), signal);
      return this;
    }

    public DbcMessage build() {
      return new DbcMessage(id, name, dlc, transmitter, signals);
    }
  }
}
