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
 * BO_ 33824768 Periodic_Status_0: 8 SparkMax
 *  SG_ AppliedOutput : 0|16@1- (0.0001,0) [-1|1] "duty_cycle"
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

    String[] lines = content.split("\n");

    for (String line : lines) {
      line = line.trim();

      if (line.isEmpty() || line.startsWith("//") || line.startsWith("CM_")) {
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

    DbcDatabase db = new DbcDatabase(version, messages);
    logger.info("Parsed DBC database: {} messages, {} total signals",
        db.messageCount(), db.totalSignalCount());

    return db;
  }

  /**
   * Parses an ID string that may be decimal or hex (0x prefix).
   */
  private int parseId(String idStr) {
    if (idStr.toLowerCase().startsWith("0x")) {
      return Integer.parseInt(idStr.substring(2), 16);
    }
    return Integer.parseInt(idStr);
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
