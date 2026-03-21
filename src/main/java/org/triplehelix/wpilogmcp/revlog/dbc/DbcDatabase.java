package org.triplehelix.wpilogmcp.revlog.dbc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A database of CAN message and signal definitions from a DBC file.
 *
 * <p>The DbcDatabase is the top-level container for all message definitions
 * parsed from a DBC file. It provides efficient lookup of messages by their
 * CAN arbitration ID.
 *
 * <p>For REV motor controllers, messages are identified by their arbitration ID
 * which encodes the device type, manufacturer, and API information. The database
 * supports matching messages by their base ID (ignoring the device-specific bits).
 *
 * @since 0.5.0
 */
public class DbcDatabase {

  private final String version;
  private final Map<Integer, DbcMessage> messagesById;
  private final Map<String, DbcMessage> messagesByName;

  /**
   * Creates a new DbcDatabase.
   *
   * @param version The DBC version string
   * @param messages The message definitions
   */
  public DbcDatabase(String version, Map<Integer, DbcMessage> messages) {
    this.version = version;
    this.messagesById = Collections.unmodifiableMap(new LinkedHashMap<>(messages));

    Map<String, DbcMessage> byName = new LinkedHashMap<>();
    for (DbcMessage msg : messages.values()) {
      byName.put(msg.name(), msg);
    }
    this.messagesByName = Collections.unmodifiableMap(byName);
  }

  /**
   * Gets the DBC version string.
   *
   * @return The version, or empty string if not specified
   */
  public String version() {
    return version;
  }

  /**
   * Gets a message by its exact CAN arbitration ID.
   *
   * @param id The CAN arbitration ID
   * @return The message, or empty if not found
   */
  public Optional<DbcMessage> getMessage(int id) {
    return Optional.ofNullable(messagesById.get(id));
  }

  /**
   * Gets a message by name.
   *
   * @param name The message name
   * @return The message, or empty if not found
   */
  public Optional<DbcMessage> getMessageByName(String name) {
    return Optional.ofNullable(messagesByName.get(name));
  }

  /**
   * Gets a message by matching the base API ID (masking out device-specific bits).
   *
   * <p>REV CAN arbitration IDs include device-specific bits (CAN ID in bits 5-0).
   * This method finds a message where the base ID (with device bits masked) matches.
   *
   * @param arbitrationId The full CAN arbitration ID
   * @param deviceMask The mask for device-specific bits (e.g., 0x3F for 6-bit CAN ID)
   * @return The matching message, or empty if not found
   */
  public Optional<DbcMessage> getMessageByBaseId(int arbitrationId, int deviceMask) {
    int baseId = arbitrationId & ~deviceMask;
    for (var entry : messagesById.entrySet()) {
      int msgBaseId = entry.getKey() & ~deviceMask;
      if (msgBaseId == baseId) {
        return Optional.of(entry.getValue());
      }
    }
    return Optional.empty();
  }

  /**
   * Gets all messages in the database.
   *
   * @return Unmodifiable map of message ID to message definition
   */
  public Map<Integer, DbcMessage> messages() {
    return messagesById;
  }

  /**
   * Gets the number of messages in the database.
   *
   * @return The message count
   */
  public int messageCount() {
    return messagesById.size();
  }

  /**
   * Gets the total number of signals across all messages.
   *
   * @return The total signal count
   */
  public int totalSignalCount() {
    return messagesById.values().stream()
        .mapToInt(DbcMessage::signalCount)
        .sum();
  }

  /**
   * Creates an empty database.
   *
   * @return An empty DbcDatabase
   */
  public static DbcDatabase empty() {
    return new DbcDatabase("", Map.of());
  }

  /**
   * Creates a builder for constructing DbcDatabase instances.
   *
   * @return A new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for DbcDatabase instances.
   */
  public static class Builder {
    private String version = "";
    private final Map<Integer, DbcMessage> messages = new LinkedHashMap<>();

    public Builder version(String version) {
      this.version = version;
      return this;
    }

    public Builder addMessage(DbcMessage message) {
      this.messages.put(message.id(), message);
      return this;
    }

    public DbcDatabase build() {
      return new DbcDatabase(version, messages);
    }
  }
}
