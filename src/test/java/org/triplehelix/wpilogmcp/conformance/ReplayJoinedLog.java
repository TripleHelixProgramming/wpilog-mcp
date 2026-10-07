/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Test-only view of all rolled files for like-for-like correlation with the whole source.
 * No combined file is written: it could exceed the reader's 2 GB bound. HTTP tools still
 * read one file, and their narrower windows can legitimately choose another alignment.
 */
final class ReplayJoinedLog implements LogData, AutoCloseable {
  private final List<LogManager.LogUse> uses = new ArrayList<>();
  private final Map<String, EntryInfo> entries = new LinkedHashMap<>();
  private final Map<String, List<TimestampedValue>> decoded = new LinkedHashMap<>();

  ReplayJoinedLog(LogManager manager, List<Path> files) throws Exception {
    try {
      for (var file : files) {
        var use = manager.acquire(file.toString()); uses.add(use);
        use.log().entries().forEach((name, info) -> { if (!name.equals("/Daemon/Robot/Identity")) entries.putIfAbsent(name, info); });
      }
    } catch (Exception failure) { close(); throw failure; }
  }
  @Override public String path() { return uses.get(0).log().path(); }
  @Override public Map<String, EntryInfo> entries() { return entries; }
  @Override public int sampleCount(String name) {
    int count = 0;
    for (int i = 0; i < uses.size(); i++) {
      var part = uses.get(i).log(); int n = part.sampleCount(name);
      if (n == 0) continue;
      var metadata = com.google.gson.JsonParser.parseString(part.entries().get(name).metadata()).getAsJsonObject();
      count += n - (i > 0 && metadata.has("capture_schema_seed") ? 1 : 0);
    }
    return count;
  }
  @Override public Map<String, List<TimestampedValue>> values() {
    return new AbstractMap<>() {
      @Override public List<TimestampedValue> get(Object name) {
        if (!entries.containsKey(name)) return null;
        return decoded.computeIfAbsent((String) name, key -> {
          var all = new ArrayList<TimestampedValue>();
          for (int i = 0; i < uses.size(); i++) {
            var part = uses.get(i).log(); var values = part.values().get(key); if (values == null) continue;
            var metadata = com.google.gson.JsonParser.parseString(part.entries().get(key).metadata()).getAsJsonObject();
            int skip = i > 0 && metadata.has("capture_schema_seed") && !values.isEmpty() ? 1 : 0;
            all.addAll(values.subList(skip, values.size()));
          }
          return List.copyOf(all);
        });
      }
      @Override public Set<Map.Entry<String, List<TimestampedValue>>> entrySet() {
        return new AbstractSet<>() {
          @Override public int size() { return entries.size(); }
          @Override public java.util.Iterator<Map.Entry<String, List<TimestampedValue>>> iterator() {
            return entries.keySet().stream().<Map.Entry<String, List<TimestampedValue>>>map(k -> Map.entry(k, get(k))).iterator();
          }
        };
      }
    };
  }
  @Override public double minTimestamp() { return uses.stream().mapToDouble(u -> u.log().minTimestamp()).min().orElse(0); }
  @Override public double maxTimestamp() { return uses.stream().mapToDouble(u -> u.log().maxTimestamp()).max().orElse(0); }
  @Override public boolean truncated() { return false; }
  @Override public String truncationMessage() { return null; }
  @Override public void close() { uses.forEach(LogManager.LogUse::close); }
}
