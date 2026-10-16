/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import org.triplehelix.wpilogmcp.log.*;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.*;

/** Borrowed fixed-prefix evidence for the shared resolver. Values never enter a history cache;
 * one native batch is retained at a time. A failed/limited scan is never evidence of absence. */
final class BindingEvidence implements LogData {
  static final int MAX_RECORDS = 65_536, MAX_BYTES = 8 << 20, RECORD_BYTES = 64 << 10;
  static final class Budget {
    int records, bytes;
    final java.util.function.LongSupplier clock;
    final long deadline;
    Budget() { this(System::nanoTime); }
    Budget(java.util.function.LongSupplier clock) { this.clock = clock; deadline = clock.getAsLong() + Duration.ofSeconds(10).toNanos(); }
    void check() {
      if (Thread.currentThread().isInterrupted() || clock.getAsLong() >= deadline)
        throw new BindingLimit("Recording binding evidence exceeded its cooperative 10 second work budget; retry or select exact entries");
    }
  }
  private final ReadSnapshot snapshot;
  private final Budget budget;
  BindingEvidence(ReadSnapshot snapshot, Budget budget) { this.snapshot = snapshot; this.budget = budget; }
  public String path() { return snapshot.path().toString(); }
  public Map<String, EntryInfo> entries() { budget.check(); return snapshot.entries(); }
  public int sampleCount(String entry) { return Math.toIntExact(snapshot.sampleCount(entry)); }
  public StructSchemas structSchemas() { return snapshot.schemas(); }
  public Map<String, List<TimestampedValue>> values() { throw new BindingLimit("This role requires materialized history; select an exact entry instead"); }
  public double minTimestamp() { throw new BindingLimit("This role requires a full timeline; select an exact entry instead"); }
  public double maxTimestamp() { return minTimestamp(); }
  public boolean truncated() { return snapshot.truncated(); }
  public boolean damaged() { return snapshot.damaged(); }
  public String truncationMessage() { return snapshot.truncationMessage(); }
  public boolean anyValue(String entry, Predicate<Object> predicate) { return scan(entry, predicate, false).isPresent(); }
  public Optional<TimestampedValue> lastValue(String entry, Predicate<Object> predicate) { return scan(entry, predicate, true); }
  private Optional<TimestampedValue> scan(String entry, Predicate<Object> predicate, boolean latest) {
    budget.check();
    // File order avoids allocating a chronological permutation solely for selection. The
    // latest witness is compared by timestamp, with later equal-time ordinals winning.
    var query = new ReadQuery(entry, List.of(), ReadQuery.Order.FILE, 1, RECORD_BYTES)
        .withRecordLimit(RECORD_BYTES, ReadQuery.LimitAction.ERROR);
    TimestampedValue result = null; long latestUs = Long.MIN_VALUE;
    try (var cursor = snapshot.read(query)) {
      while (true) {
        budget.check();
        if (budget.records >= MAX_RECORDS || budget.bytes >= MAX_BYTES)
          throw new BindingLimit("Recording binding evidence exceeds 65536 records or 8 MiB; select exact entries or narrower fields");
        var batch = cursor.next(); budget.records += batch.recordsVisited(); budget.bytes = Math.addExact(budget.bytes, Math.toIntExact(batch.payloadBytes()));
        if (budget.bytes > MAX_BYTES) throw new BindingLimit("Recording binding evidence exceeds 8 MiB; select exact entries or narrower fields");
        if (!batch.problems().isEmpty()) throw new ReadException(batch.problems().get(0).code(),
            "Recording binding evidence is unreadable: " + batch.problems().get(0).message());
        for (var sample : batch.samples()) if (predicate.test(sample.value())) {
          var witness = new TimestampedValue(sample.timestampUs() / 1_000_000.0, sample.value());
          if (!latest) return Optional.of(witness);
          if (result == null || sample.timestampUs() >= latestUs) { result = witness; latestUs = sample.timestampUs(); }
        }
        if (batch.complete()) return Optional.ofNullable(result);
      }
    } catch (java.io.IOException failure) { throw new UncheckedIOException(failure); }
  }
}
