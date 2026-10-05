/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.util.ArrayList;
import java.util.Comparator;
import org.triplehelix.wpilogmcp.tools.SignalResolver.MetadataRole;

/** Facts from the entire log: an import cannot stop before the Driver Station connected. */
public record LogMetadata(String serialNumber, String comments, String event,
    String matchType, Integer matchNumber, Integer teamNumber) {

  private record Fact(MetadataRole role, double time, int id, Object value) {}

  public static LogMetadata read(LogData log) {
    var facts = new ArrayList<Fact>();
    for (var entry : log.entries().values()) {
      MetadataRole.of(entry.name(), entry.type()).ifPresent(role -> {
        for (var sample : log.values().get(entry.name())) {
          facts.add(new Fact(role, sample.timestamp(), entry.id(), sample.value()));
        }
      });
    }
    facts.sort(Comparator.comparingDouble(Fact::time).thenComparingInt(Fact::id));
    String serial = null;
    String comments = null;
    String event = null;
    String match = null;
    Integer number = null;
    Integer team = null;
    long currentType = 0;
    long currentNumber = 0;
    for (var fact : facts) {
      String text = fact.value() instanceof String s && !s.isBlank() ? s.strip() : null;
      Long integer = fact.value() instanceof Number n ? n.longValue() : null;
      switch (fact.role()) {
        case SERIAL -> {
          if (text != null && serial != null && !serial.equals(text)) {
            throw new IllegalArgumentException("The log records conflicting robot serial numbers");
          }
          if (text != null) serial = text;
        }
        case COMMENTS -> {
          if (text != null) comments = text;
        }
        case EVENT -> {
          if (text != null) event = text;
        }
        case MATCH_TYPE -> {
          if (integer != null) currentType = integer;
        }
        case MATCH_NUMBER -> {
          if (integer != null) currentNumber = integer;
        }
        case TEAM -> {
          if (integer != null && integer > 0 && integer <= Integer.MAX_VALUE) team = integer.intValue();
        }
      }
      if (currentType >= 1 && currentType <= 3 && currentNumber > 0
          && currentNumber <= Integer.MAX_VALUE) {
        match = LogDirectory.MatchType.fromOrdinal((int) currentType).getFriendlyName();
        number = (int) currentNumber;
      }
    }
    return new LogMetadata(serial, comments, event, match, number, team);
  }
}
