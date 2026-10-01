/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.triplehelix.wpilogmcp.log.DecodeProblem;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

/**
 * A view of a log whose entries iterate in a different order from their natural one (reversed,
 * or shuffled by a seed), with every entry keeping its id, name, type, metadata, and values.
 *
 * <p>A tool whose result depends on map iteration order (a {@code findFirst} over
 * {@code log.entries()}) gives a different answer on this view. Entry ids are unchanged, so a
 * tool that breaks ties by declaration order (entry id) gives the same answer.
 */
final class PermutedLogData implements LogData {

  /** The orders the conformance test checks: reversed, then two seeded shuffles. */
  static final List<Long> ORDERS = List.of(0L, 1L, 2L);

  private final LogData delegate;
  private final Map<String, EntryInfo> entries;
  private final Map<String, List<TimestampedValue>> values;

  /**
   * @param delegate The log
   * @param order 0 to reverse the entries; any other value shuffles them with that seed
   */
  PermutedLogData(LogData delegate, long order) {
    this.delegate = delegate;
    var names = new ArrayList<>(delegate.entries().keySet());
    if (order == 0) {
      Collections.reverse(names);
    } else {
      Collections.shuffle(names, new java.util.Random(order));
    }
    var reordered = new LinkedHashMap<String, EntryInfo>();
    for (var name : names) reordered.put(name, delegate.entries().get(name));
    this.entries = Collections.unmodifiableMap(reordered);
    this.values = new ValuesView(new LinkedHashSet<>(names));
  }

  private final class ValuesView extends AbstractMap<String, List<TimestampedValue>> {
    private final Set<String> keys;

    ValuesView(Set<String> keys) {
      this.keys = Collections.unmodifiableSet(keys);
    }

    @Override
    public List<TimestampedValue> get(Object key) {
      return delegate.values().get(key);
    }

    @Override
    public boolean containsKey(Object key) {
      return delegate.values().containsKey(key);
    }

    @Override
    public Set<String> keySet() {
      return keys;
    }

    @Override
    public int size() {
      return keys.size();
    }

    @Override
    public Set<Entry<String, List<TimestampedValue>>> entrySet() {
      var result = new LinkedHashSet<Entry<String, List<TimestampedValue>>>();
      for (var key : keys) {
        var v = delegate.values().get(key);
        if (v != null) result.add(new SimpleImmutableEntry<>(key, v));
      }
      return result;
    }
  }

  @Override public String path() { return delegate.path(); }
  @Override public Map<String, EntryInfo> entries() { return entries; }
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
