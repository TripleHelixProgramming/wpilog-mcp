/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.revlog.dbc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser for DBC (CAN database) files.
 *
 * <p>DBC files define CAN message structures and signal layouts. This parser
 * handles the subset of DBC syntax needed for REV motor controller signals.
 *
 * <p>Supported DBC elements:
 * <ul>
 *   <li>VERSION - Database version string</li>
 *   <li>BO_ - Message definitions</li>
 *   <li>SG_ - Signal definitions within messages</li>
 * </ul>
 *
 * <p>Example DBC content:
 * <pre>
 * VERSION ""
 *
 * BO_ 0x0205B800 Status_0: 8 SparkMax
 *  SG_ AppliedOutput : 0|16@1- (0.00003082369457075716,0) [-1|1] "duty_cycle"
 *  SG_ Faults : 16|16@1+ (1,0) [0|65535] ""
 * </pre>
 *
 * @since 0.5.0
 */
public class DbcParser {
  private static final Logger logger = LoggerFactory.getLogger(DbcParser.class);

  // Pattern for VERSION line: VERSION "string" or VERSION ""
  private static final Pattern VERSION_PATTERN = Pattern.compile(
      "VERSION\\s+\"([^\"]*)\"");

  // Pattern for BO_ (message) line: BO_ <id> <name>: <dlc> <transmitter>
  // ID can be decimal or hex (0x prefix)
  private static final Pattern MESSAGE_PATTERN = Pattern.compile(
      "BO_\\s+(0x[0-9A-Fa-f]+|\\d+)\\s+(\\w+)\\s*:\\s*(\\d+)\\s+(\\w+)?");

  // Pattern for SIG_VALTYPE_ line: SIG_VALTYPE_ <message id> <signal> : <1 float32 | 2 float64>;
  private static final Pattern VALTYPE_PATTERN = Pattern.compile(
      "SIG_VALTYPE_\\s+(0x[0-9A-Fa-f]+|\\d+)\\s+(\\w+)\\s*:\\s*(\\d)");

  // Pattern for SG_ (signal) line:
  // SG_ <name> : <start>|<length>@<byteOrder><sign> (<scale>,<offset>) [<min>|<max>] "<unit>"
  private static final Pattern SIGNAL_PATTERN = Pattern.compile(
      "SG_\\s+(\\w+)\\s*:\\s*(\\d+)\\|(\\d+)@([01])([+-])\\s*" +
      "\\(([^,]+),([^)]+)\\)\\s*\\[([^|]+)\\|([^\\]]+)\\]\\s*\"([^\"]*)\"");

  /**
   * Parses a DBC file content string into a DbcDatabase.
   *
   * @param content The DBC file content
   * @return The parsed database
   * @throws IllegalArgumentException if the content is invalid
   */
  public DbcDatabase parse(String content) {
    if (content == null || content.isBlank()) {
      logger.warn("Empty DBC content, returning empty database");
      return DbcDatabase.empty();
    }

    String version = "";
    Map<Integer, DbcMessage> messages = new LinkedHashMap<>();
    DbcMessage.Builder currentMessage = null;
    // SIG_VALTYPE_ lines follow every message; applied once all are read
    Map<Integer, Map<String, DbcSignal.ValueType>> valueTypes = new LinkedHashMap<>();

    String[] lines = content.split("\n");

    for (String line : lines) {
      line = line.trim();

      if (line.isEmpty() || line.startsWith("//") || line.startsWith("CM_")) {
        continue;
      }

      Matcher valueTypeMatcher = VALTYPE_PATTERN.matcher(line);
      if (line.startsWith("SIG_VALTYPE_") && valueTypeMatcher.find()) {
        valueTypes.computeIfAbsent(parseId(valueTypeMatcher.group(1)), k -> new LinkedHashMap<>())
            .put(valueTypeMatcher.group(2),
                DbcSignal.ValueType.fromDbcCode(Integer.parseInt(valueTypeMatcher.group(3))));
        continue;
      }

      // Check for VERSION
      Matcher versionMatcher = VERSION_PATTERN.matcher(line);
      if (versionMatcher.find()) {
        version = versionMatcher.group(1);
        logger.debug("Found DBC version: {}", version);
        continue;
      }

      // Check for message definition
      Matcher messageMatcher = MESSAGE_PATTERN.matcher(line);
      if (messageMatcher.find()) {
        // Save previous message if any
        if (currentMessage != null) {
          DbcMessage msg = currentMessage.build();
          messages.put(msg.id(), msg);
        }

        String idStr = messageMatcher.group(1);
        int id = parseId(idStr);
        String name = messageMatcher.group(2);
        int dlc = Integer.parseInt(messageMatcher.group(3));
        String transmitter = messageMatcher.group(4);

        currentMessage = DbcMessage.builder(id, name)
            .dlc(dlc)
            .transmitter(transmitter != null ? transmitter : "");

        logger.trace("Parsed message: {} (id=0x{}, dlc={})", name, Integer.toHexString(id), dlc);
        continue;
      }

      // Check for signal definition
      Matcher signalMatcher = SIGNAL_PATTERN.matcher(line);
      if (signalMatcher.find() && currentMessage != null) {
        String name = signalMatcher.group(1);
        int startBit = Integer.parseInt(signalMatcher.group(2));
        int bitLength = Integer.parseInt(signalMatcher.group(3));
        boolean littleEndian = signalMatcher.group(4).equals("1");
        boolean signed = signalMatcher.group(5).equals("-");
        double scale = parseDouble(signalMatcher.group(6));
        double offset = parseDouble(signalMatcher.group(7));
        double min = parseDouble(signalMatcher.group(8));
        double max = parseDouble(signalMatcher.group(9));
        String unit = signalMatcher.group(10);

        DbcSignal signal = DbcSignal.builder(name)
            .startBit(startBit)
            .bitLength(bitLength)
            .littleEndian(littleEndian)
            .signed(signed)
            .scale(scale)
            .offset(offset)
            .min(min)
            .max(max)
            .unit(unit)
            .build();

        currentMessage.addSignal(signal);
        logger.trace("  Parsed signal: {} ({}|{}@{}{}, scale={}, unit={})",
            name, startBit, bitLength, littleEndian ? "1" : "0", signed ? "-" : "+", scale, unit);
      }
    }

    // Save last message
    if (currentMessage != null) {
      DbcMessage msg = currentMessage.build();
      messages.put(msg.id(), msg);
    }

    // Apply value types (float signals)
    for (var typed : valueTypes.entrySet()) {
      DbcMessage msg = messages.get(typed.getKey());
      if (msg == null) {
        logger.debug("SIG_VALTYPE_ for unknown message 0x{}", Integer.toHexString(typed.getKey()));
        continue;
      }
      var builder = DbcMessage.builder(msg.id(), msg.name()).dlc(msg.dlc())
          .transmitter(msg.transmitter());
      for (DbcSignal signal : msg.signals().values()) {
        var type = typed.getValue().get(signal.name());
        builder.addSignal(type != null ? signal.withValueType(type) : signal);
      }
      messages.put(msg.id(), builder.build());
    }

    DbcDatabase db = new DbcDatabase(version, messages, contentHash(content));
    logger.info("Parsed DBC database: {} messages, {} total signals",
        db.messageCount(), db.totalSignalCount());

    return db;
  }

  /** The first 16 hex digits of the SHA-256 of the DBC text: names what decoded a log. */
  static String contentHash(String content) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var hex = new StringBuilder();
      for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Parses an ID string that may be decimal or hex (0x prefix). A DBC marks an extended (29-bit)
   * frame by setting bit 31 of its ID; the arbitration ID is the low 29 bits.
   */
  private int parseId(String idStr) {
    long id = idStr.toLowerCase().startsWith("0x") ? Long.parseLong(idStr.substring(2), 16)
        : Long.parseLong(idStr);
    return (int) (id & 0x1FFF_FFFFL);
  }

  /**
   * Parses a double, handling both integer and decimal formats.
   */
  private double parseDouble(String str) {
    try {
      return Double.parseDouble(str.trim());
    } catch (NumberFormatException e) {
      logger.warn("Could not parse double: {}", str);
      return 0.0;
    }
  }
}
