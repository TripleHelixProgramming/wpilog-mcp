/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.SampleBatch;
import org.triplehelix.wpilogmcp.read.catalog.SessionSnapshot;

/**
 * A finished session's member union on its anchor clock, as the shared scope and signal-role code
 * reads a log. Only the Driver Station timeline entries are materialized, because a phase scope
 * needs their histories; every other question streams through a cursor and stops at its first
 * answer. Every record any of it visits is charged to the comparison's budget, so a scope or a role
 * cannot read past the call's caps, and nothing is cached beyond the call.
 */
final class SessionView implements LogData {
  /** Charges visited records; refuses the call when its cap is exceeded. */
  @FunctionalInterface interface Budget { void visit(long records); }

  private final SessionSnapshot snapshot;
  private final double min;
  private final double max;
  private final Budget budget;
  private final Set<String> timeline;
  private final Map<String, List<TimestampedValue>> loaded = new HashMap<>();

  /** The bounds come from the members' manifested data times mapped through their recorded clocks. */
  SessionView(SessionSnapshot snapshot, double min, double max, Budget budget) {
    this.snapshot = snapshot;
    this.min = min;
    this.max = max;
    this.budget = budget;
    var sources = MatchTimeline.sourcesOf(this);
    timeline = Stream.of(sources.enabled(), sources.autonomous(), sources.test(), sources.fmsAttached(),
        sources.controlWord()).filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
  }

  @Override public String path() { return snapshot.path().toString(); }
  @Override public Map<String, EntryInfo> entries() { return snapshot.entries(); }
  @Override public StructSchemas structSchemas() { return snapshot.schemas(); }
  @Override public double minTimestamp() { return min; }
  @Override public double maxTimestamp() { return max; }
  @Override public boolean truncated() { return snapshot.truncated(); }
  @Override public boolean damaged() { return snapshot.damaged(); }
  @Override public String truncationMessage() { return snapshot.truncationMessage(); }
  @Override public int sampleCount(String entry) {
    return (int) Math.min(Integer.MAX_VALUE, snapshot.sampleCount(entry));
  }

  /** The timeline entries' histories; any other entry reads as absent rather than materialized. */
  @Override public Map<String, List<TimestampedValue>> values() {
    return new AbstractMap<>() {
      @Override public List<TimestampedValue> get(Object key) {
        return key instanceof String name && timeline.contains(name) ? history(name) : null;
      }
      @Override public boolean containsKey(Object key) { return get(key) != null; }
      @Override public Set<Entry<String, List<TimestampedValue>>> entrySet() {
        throw new UnsupportedOperationException("A session view materializes only its timeline entries");
      }
    };
  }

  @Override public boolean anyValue(String entry, Predicate<Object> predicate) {
    return scan(entry, predicate, false).isPresent();
  }

  @Override public Optional<TimestampedValue> lastValue(String entry, Predicate<Object> predicate) {
    return scan(entry, predicate, true);
  }

  private List<TimestampedValue> history(String name) {
    return loaded.computeIfAbsent(name, key -> {
      var out = new ArrayList<TimestampedValue>();
      read(key, batch -> batch.samples().forEach(s -> out.add(TimestampedValue.atMicros(s.timestampUs(), s.value()))));
      return List.copyOf(out);
    });
  }

  private Optional<TimestampedValue> scan(String entry, Predicate<Object> predicate, boolean latest) {
    if (!snapshot.entries().containsKey(entry)) return Optional.empty();
    var found = new TimestampedValue[1];
    read(entry, batch -> {
      for (var sample : batch.samples()) if (predicate.test(sample.value())) {
        found[0] = TimestampedValue.atMicros(sample.timestampUs(), sample.value());
        if (!latest) throw Found.INSTANCE;
      }
    });
    return Optional.ofNullable(found[0]);
  }

  /** Stops a streaming existence check at its first witness without reading the rest. */
  private static final class Found extends RuntimeException {
    static final Found INSTANCE = new Found();
    private Found() { super(null, null, false, false); }
  }

  @FunctionalInterface private interface Batches { void accept(SampleBatch batch); }

  private void read(String entry, Batches consumer) {
    try (var cursor = snapshot.read(new ReadQuery(entry, List.of(), ReadQuery.Order.TIME, 4096, 4 << 20))) {
      while (true) {
        var batch = cursor.next();
        budget.visit(batch.recordsVisited());
        if (!batch.problems().isEmpty()) {
          var problem = batch.problems().get(0);
          throw new UncheckedIOException(new ReadException(problem.code(), "Session entry " + entry
              + " is unreadable: " + problem.message()));
        }
        consumer.accept(batch);
        if (batch.complete()) return;
      }
    } catch (Found found) {
      // The witness was recorded; the remaining records are never visited.
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
