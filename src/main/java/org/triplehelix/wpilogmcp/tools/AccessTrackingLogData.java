/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.triplehelix.wpilogmcp.log.DecodeProblem;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

/**
 * A log as one tool call sees it, remembering which entries' values the tool read, so that
 * {@link LogRequiringTool} can report entries whose records could not all be decoded — whatever
 * the tool did with the values it got. One instance per call.
 *
 * @since 0.9.0
 */
final class AccessTrackingLogData implements LogData {

  private final LogData delegate;
  private final Set<String> read = ConcurrentHashMap.newKeySet();
  private final Map<String, List<TimestampedValue>> values;

  AccessTrackingLogData(LogData delegate) {
    this.delegate = delegate;
    this.values = new AbstractMap<>() {
      @Override
      public List<TimestampedValue> get(Object key) {
        if (key instanceof String name) read.add(name);
        return delegate.values().get(key);
      }

      @Override
      public boolean containsKey(Object key) {
        return delegate.values().containsKey(key);
      }

      @Override
      public Set<String> keySet() {
        return delegate.values().keySet();
      }

      @Override
      public int size() {
        return delegate.values().size();
      }

      @Override
      public Set<Entry<String, List<TimestampedValue>>> entrySet() {
        // An entry counts as read when its values are, not when a tool iterates past it
        var entries = delegate.values().entrySet();
        return new java.util.AbstractSet<>() {
          @Override
          public java.util.Iterator<Entry<String, List<TimestampedValue>>> iterator() {
            var it = entries.iterator();
            return new java.util.Iterator<>() {
              @Override
              public boolean hasNext() {
                return it.hasNext();
              }

              @Override
              public Entry<String, List<TimestampedValue>> next() {
                return new TrackedEntry(it.next());
              }
            };
          }

          @Override
          public int size() {
            return entries.size();
          }
        };
      }
    };
  }

  /** A map entry that records its key as read when its value is taken. */
  private final class TrackedEntry implements Map.Entry<String, List<TimestampedValue>> {
    private final Map.Entry<String, List<TimestampedValue>> entry;

    TrackedEntry(Map.Entry<String, List<TimestampedValue>> entry) {
      this.entry = entry;
    }

    @Override
    public String getKey() {
      return entry.getKey();
    }

    @Override
    public List<TimestampedValue> getValue() {
      read.add(entry.getKey());
      return entry.getValue();
    }

    @Override
    public List<TimestampedValue> setValue(List<TimestampedValue> value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Map.Entry<?, ?> e && getKey().equals(e.getKey())
          && java.util.Objects.equals(getValue(), e.getValue());
    }

    @Override
    public int hashCode() {
      return getKey().hashCode() ^ java.util.Objects.hashCode(getValue());
    }
  }

  /** Most entry names {@link #recordInputs} lists before giving only the total. */
  static final int MAX_LISTED_INPUTS = 10;

  /**
   * Gives a result that does not say what it used an {@code inputs} block: the log, and the
   * entries whose values the tool read (name order, at most {@value #MAX_LISTED_INPUTS}, with the
   * total). Tools that record their inputs by role keep their own block.
   */
  void recordInputs(JsonObject result) {
    if (result.has("inputs")) return;
    var inputs = new JsonObject();
    inputs.addProperty("log", delegate.path());
    if (!read.isEmpty()) {
      var names = new JsonArray();
      new TreeSet<>(read).stream().limit(MAX_LISTED_INPUTS).forEach(names::add);
      inputs.add("entries_read", names);
      if (read.size() > MAX_LISTED_INPUTS) inputs.addProperty("entries_read_total", read.size());
    }
    result.add("inputs", inputs);
  }

  /** The log being tracked. */
  LogData delegate() {
    return delegate;
  }

  /**
   * Adds a warning and {@code _metadata.decode_problems} for every entry the tool read whose
   * records could not all be decoded. Entries are reported in name order.
   */
  void annotate(JsonObject result) {
    var problems = new JsonArray();
    var warnings = new java.util.ArrayList<String>();
    for (var name : new TreeSet<>(read)) {
      var problem = delegate.decodeProblem(name);
      if (problem.isEmpty()) continue;
      problems.add(toJson(name, problem.get()));
      warnings.add(warning(name, problem.get()));
    }
    if (problems.isEmpty()) return;
    var warningArray = result.has("warnings") && result.get("warnings").isJsonArray()
        ? result.getAsJsonArray("warnings") : new JsonArray();
    warnings.forEach(warningArray::add);
    result.add("warnings", warningArray);
    var metadata = result.has("_metadata") && result.get("_metadata").isJsonObject()
        ? result.getAsJsonObject("_metadata") : new JsonObject();
    metadata.add("decode_problems", problems);
    result.add("_metadata", metadata);
  }

  static JsonObject toJson(String name, DecodeProblem problem) {
    var o = new JsonObject();
    o.addProperty("entry", name);
    o.addProperty("failed_records", problem.failedRecords());
    o.addProperty("total_records", problem.totalRecords());
    o.addProperty("reason", problem.message());
    return o;
  }

  static String warning(String name, DecodeProblem problem) {
    return problem.allFailed()
        ? "Entry " + name + ": none of its " + problem.totalRecords()
            + " records could be decoded (" + problem.message() + "); it contributed no data."
        : "Entry " + name + ": " + problem.failedRecords() + " of " + problem.totalRecords()
            + " records could not be decoded (" + problem.message()
            + "); results use the rest.";
  }

  @Override public String path() { return delegate.path(); }
  @Override public Map<String, EntryInfo> entries() { return delegate.entries(); }
  @Override public Map<String, List<TimestampedValue>> values() { return values; }
  @Override public double minTimestamp() { return delegate.minTimestamp(); }
  @Override public double maxTimestamp() { return delegate.maxTimestamp(); }
  @Override public boolean truncated() { return delegate.truncated(); }
  @Override public boolean damaged() { return delegate.damaged(); }
  @Override public String truncationMessage() { return delegate.truncationMessage(); }
  @Override public int sampleCount(String entryName) { return delegate.sampleCount(entryName); }
  @Override public StructSchemas structSchemas() { return delegate.structSchemas(); }

  @Override
  public Optional<DecodeProblem> decodeProblem(String entryName) {
    return delegate.decodeProblem(entryName);
  }
}
