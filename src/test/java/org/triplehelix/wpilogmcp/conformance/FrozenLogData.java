/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.triplehelix.wpilogmcp.log.*;

/** A fixture's independent call orders share decoded data, never a second file scan per call. */
record FrozenLogData(String path, Map<String, EntryInfo> entries,
    Map<String, List<TimestampedValue>> values, double minTimestamp, double maxTimestamp,
    boolean truncated, boolean damaged, String truncationMessage,
    org.triplehelix.wpilogmcp.log.struct.StructSchemas structSchemas,
    Map<String, DecodeProblem> problems, Map<String, Integer> counts) implements LogData {
  static FrozenLogData copy(LogData log) {
    var values = new java.util.LinkedHashMap<String, List<TimestampedValue>>();
    var problems = new java.util.LinkedHashMap<String, DecodeProblem>();
    var counts = new java.util.LinkedHashMap<String, Integer>();
    for (String name : log.entries().keySet()) {
      counts.put(name, log.sampleCount(name));
      var data = log.values().get(name);
      if (data != null) values.put(name, List.copyOf(data));
      log.decodeProblem(name).ifPresent(problem -> problems.put(name, problem));
    }
    return new FrozenLogData(log.path(), Map.copyOf(log.entries()), Map.copyOf(values),
        log.minTimestamp(), log.maxTimestamp(), log.truncated(), log.damaged(),
        log.truncationMessage(), log.structSchemas(), Map.copyOf(problems), Map.copyOf(counts));
  }
  @Override public int sampleCount(String name) { return counts.getOrDefault(name, 0); }
  @Override public Optional<DecodeProblem> decodeProblem(String name) { return Optional.ofNullable(problems.get(name)); }
}
