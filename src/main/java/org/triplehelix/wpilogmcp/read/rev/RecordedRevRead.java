/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.rev;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/** Detached REV values on the raw clock and, only with explicit recorded proof, the anchor's
 * clock. This adapter reuses the existing eager REV decoder and bundled DBC only. Its timestamps
 * retain that decoder's double seconds; it does not fabricate exact record offsets or microseconds.
 * Unlike the WPILOG cursor this remains an eager adapter, bounded and shared by RevReadCache.
 * Closing the read returns its memory charge; no correlation or persistence occurs. */
public final class RecordedRevRead implements AutoCloseable {
  public enum State { RECORDED, UNSYNCHRONIZED }
  public enum Clock { RAW, FPGA }
  private final Path path;
  private final FileSnapshot boundary;
  private final SyncResult alignment;
  private final Map<String, RevLogSignal> signals;
  private final RevReadCache.Lease lease;

  private RecordedRevRead(Path path, FileSnapshot boundary, SyncResult alignment, RevReadCache.Lease lease, java.util.Set<String> selected) {
    this.path = path; this.boundary = boundary;
    this.alignment = new SyncResult(alignment.offsetMicros(), alignment.confidence(), alignment.confidenceLevel(),
        List.copyOf(alignment.signalPairs()), alignment.method(), alignment.explanation(),
        alignment.driftRateNanosPerSec(), alignment.referenceTimeSec());
    this.lease = lease;
    if (selected == null || selected.equals(lease.signals.keySet())) this.signals = lease.signals;
    else {
      var subset = new LinkedHashMap<String, RevLogSignal>();
      lease.signals.forEach((key, value) -> { if (selected.contains(key)) subset.put(key, value); });
      this.signals = java.util.Collections.unmodifiableMap(subset);
    }
  }

  static Map<String, RevLogSignal> immutableSignals(Map<String, RevLogSignal> signals) {
    var copy = new LinkedHashMap<String, RevLogSignal>();
    signals.forEach((key, signal) -> copy.put(key, new RevLogSignal(signal.name(), signal.deviceKey(),
        org.triplehelix.wpilogmcp.revlog.RevLogParser.immutableValues(signal.values()), signal.unit())));
    return java.util.Collections.unmodifiableMap(copy);
  }

  /** Explicit roots apply even to a caller-supplied alignment. A null alignment means raw-clock
   * access only; callers must never substitute zero for absent synchronization. Store clients use
   * the catalog's openRev method so that the manifest owner validates the association. */
  public static RecordedRevRead open(Path path, List<Path> roots, SyncResult recorded) throws IOException {
    try (var cache = new RevReadCache()) { return open(path, roots, recorded, cache); }
  }
  public static RecordedRevRead open(Path path, List<Path> roots, SyncResult recorded, RevReadCache cache) throws IOException {
    return open(path, roots, recorded, cache, null);
  }
  /** Restrict decoding and retained values to explicit device/signal keys. No selection means all. */
  public static RecordedRevRead open(Path path, List<Path> roots, SyncResult recorded, RevReadCache cache,
      java.util.Set<String> selected) throws IOException {
    if (selected != null) {
      selected = java.util.Set.copyOf(selected);
      if (selected.isEmpty() || selected.size() > 256 || selected.stream().anyMatch(String::isEmpty))
        throw new IllegalArgumentException("Select 1..256 exact REV signal keys");
    }
    if (roots.isEmpty()) throw new IllegalArgumentException("At least one REV read root is required");
    var security = new SecurityValidator();
    for (var root : roots) security.addAllowedDirectory(root.toRealPath());
    security.validate(path);
    var real = path.toRealPath();
    security.validate(real);
    var alignment = recorded == null ? SyncResult.failed("No recorded alignment was supplied") : recorded;
    if (alignment.method() == null || alignment.confidenceLevel() == null || alignment.signalPairs() == null
        || alignment.explanation() == null || !Double.isFinite(alignment.confidence())
        || alignment.confidence() < 0 || alignment.confidence() > 1
        || !Double.isFinite(alignment.driftRateNanosPerSec()) || !Double.isFinite(alignment.referenceTimeSec())) {
      throw new IOException("Invalid explicit recorded REV alignment");
    }
    var before = FileSnapshot.of(real);
    var lease = cache.acquire(real, before, selected);
    return new RecordedRevRead(real, before, alignment, lease, selected);
  }
  @Override public void close() { lease.close(); }

  public Path path() { return path; }
  public FileSnapshot boundary() { return boundary; }
  public State state() { return alignment.isSuccessful() ? State.RECORDED : State.UNSYNCHRONIZED; }
  public SyncResult alignment() { return alignment; }
  public String reason() { return alignment.explanation(); }
  public Map<String, RevLogSignal> signals() { return signals; }
  public List<TimestampedValue> rawValues(String signal) {
    var found = signals.get(Objects.requireNonNull(signal));
    if (found == null) throw new IllegalArgumentException("Unknown REV signal: " + signal);
    return found.values();
  }
  /** Paging bounds only the transformed copy: parsing itself retains the existing eager decoder.
   * The clock is explicit so an absent alignment can never quietly look like a zero offset. */
  public List<TimestampedValue> samples(String signal, int offset, int limit, Clock clock) throws IOException {
    Objects.requireNonNull(clock, "clock");
    if (offset < 0 || limit < 0) throw new IllegalArgumentException("REV offset and limit must be nonnegative");
    if (clock == Clock.FPGA && !alignment.isSuccessful())
      throw new IOException("REV signal is unsynchronized: " + reason());
    var values = rawValues(signal);
    int start = Math.min(offset, values.size());
    int end = (int) Math.min((long) start + limit, values.size());
    var page = values.subList(start, end);
    return clock == Clock.RAW ? List.copyOf(page) : page.stream()
        .map(value -> new TimestampedValue(alignment.toFpgaTime(value.timestamp()), value.value())).toList();
  }
  public List<TimestampedValue> values(String signal) throws IOException {
    return samples(signal, 0, Integer.MAX_VALUE, Clock.FPGA);
  }
}
