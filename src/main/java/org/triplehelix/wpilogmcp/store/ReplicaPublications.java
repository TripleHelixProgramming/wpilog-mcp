/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Store-ordered pending facts, published without receipt I/O on a status or metrics request.
 * The path-keyed mutable state belongs only to the store queue; readers retain immutable views. */
public final class ReplicaPublications {
  public static final int MAX_OBSERVATIONS = 32_768;
  public static final long MAX_ACCOUNTED_BYTES = 32L << 20;
  public enum State { INITIALIZING, READY, UNAVAILABLE }
  public record Cause(String code, String message, String firstFailedAt, String failedAt, boolean durable) {}
  public record Entry(String sessionId, Cause cause) {}
  public record Snapshot(String storeId, State state, int knownPendingCount, Integer totalPendingCount,
      String oldestFailedAt, boolean oldestTimeComplete, List<Entry> entries,
      String failureCode, String failureMessage) {}
  record Event(Path receipt, Entry pending) {}
  private record Held(Entry entry, long bytes) {}
  private final Map<Path, Held> pending = new HashMap<>();
  private final Set<Path> changed = new HashSet<>(), seen = new HashSet<>();
  private final AtomicReference<Snapshot> published = new AtomicReference<>(new Snapshot(null,
      State.INITIALIZING, 0, null, null, false, List.of(), null, null));
  private String storeId, failureCode;
  private State state = State.INITIALIZING;
  private boolean sweeping, observationsStopped;
  private long accounted, sweepBytes;

  public Snapshot snapshot() { return published.get(); }

  static String message(String code) {
    return switch (code) {
      case "RESOURCE_LIMIT" -> "Replica catalog metadata capacity is exhausted.";
      case "AUTHORITY_CONFLICT" -> "A completed receipt conflicts with published immutable content.";
      case "SOURCE_UNAVAILABLE" -> "The recorded receipt or store is unavailable.";
      case "PUBLICATION_FAILED" -> "Replica catalog publication could not be completed.";
      default -> "The failure cause was not recorded.";
    };
  }

  static Cause unknown() { return new Cause("CAUSE_NOT_RECORDED", message("CAUSE_NOT_RECORDED"), null, null, true); }

  /** Disk text never becomes a public message, even when an attacker supplied a recognized code. */
  static Cause recorded(String code, String first, String latest) {
    if (code == null || !Set.of("RESOURCE_LIMIT", "AUTHORITY_CONFLICT", "SOURCE_UNAVAILABLE", "PUBLICATION_FAILED").contains(code))
      return unknown();
    try {
      var a = Instant.parse(first); var b = Instant.parse(latest);
      if (a.isAfter(b)) return unknown();
      return new Cause(code, message(code), a.toString(), b.toString(), true);
    } catch (RuntimeException invalid) { return unknown(); }
  }

  static Cause failed(Throwable failure, Cause previous, Instant now, boolean durable) {
    String detail = failure.getMessage();
    String code = failure instanceof ReplicaCatalogFiles.CapacityRefused
        || detail != null && (detail.startsWith("RESOURCE_LIMIT:")
            || detail.equals("Replica metadata limit reached; existing records are preserved")) ? "RESOURCE_LIMIT"
        : detail != null && detail.startsWith("AUTHORITY_CONFLICT:") ? "AUTHORITY_CONFLICT"
        : failure instanceof java.nio.file.FileSystemException ? "SOURCE_UNAVAILABLE" : "PUBLICATION_FAILED";
    String first = previous == null || previous.firstFailedAt() == null ? now.toString() : previous.firstFailedAt();
    return new Cause(code, message(code), first, now.toString(), durable);
  }

  void header(String id) {
    if (java.util.Objects.equals(storeId, id)) return;
    if (storeId != null && !storeId.equals(id)) {
      pending.clear(); accounted = 0; unavailable("SOURCE_UNAVAILABLE");
      observationsStopped = true;
    }
    storeId = id;
    publish();
  }

  void begin(String id) {
    if (storeId != null && !storeId.equals(id)) { pending.clear(); accounted = 0; }
    storeId = id; sweeping = true; observationsStopped = false;
    changed.clear(); seen.clear(); sweepBytes = 0; failureCode = null; state = State.INITIALIZING;
    publish();
  }

  boolean changed(Path receipt) { return observationsStopped || changed.contains(receipt); }
  Cause cause(Path receipt) {
    var held = pending.get(receipt);
    return held == null ? null : held.entry().cause();
  }

  /** Live outcomes take precedence over every observation prepared during the current sweep. */
  void live(Event event) {
    if (sweeping && !remember(changed, event.receipt())) observationsStopped = true;
    apply(event); publish();
  }

  void observed(Event event) {
    if (changed(event.receipt())) return;
    if (!remember(seen, event.receipt())) { observationsStopped = true; return; }
    apply(event);
  }

  private boolean remember(Set<Path> set, Path key) {
    if (set.contains(key)) return true;
    long bytes = 128L + 2L * key.toString().length();
    if (set.size() >= MAX_OBSERVATIONS || bytes > MAX_ACCOUNTED_BYTES - accounted - sweepBytes) {
      unavailable("RESOURCE_LIMIT"); return false;
    }
    set.add(key); sweepBytes += bytes; return true;
  }

  private void apply(Event event) {
    var previous = pending.get(event.receipt());
    if (event.pending() == null) {
      if (previous != null) { pending.remove(event.receipt()); accounted -= previous.bytes(); }
      return;
    }
    var entry = event.pending();
    if (entry.sessionId().length() > 1024) { unavailable("RESOURCE_LIMIT"); return; }
    long bytes = 768L + 2L * event.receipt().toString().length() + 2L * entry.sessionId().length();
    long replacing = previous == null ? 0 : previous.bytes();
    if (previous == null && pending.size() >= MAX_OBSERVATIONS
        || bytes > MAX_ACCOUNTED_BYTES - (accounted - replacing) - sweepBytes) {
      unavailable("RESOURCE_LIMIT"); return;
    }
    pending.put(event.receipt(), new Held(entry, bytes)); accounted += bytes - replacing;
  }

  void unavailable(String code) { failureCode = code; state = State.UNAVAILABLE; }

  void finish(boolean complete, String code) {
    if (complete && failureCode == null && !observationsStopped) {
      var iterator = pending.entrySet().iterator();
      while (iterator.hasNext()) {
        var item = iterator.next();
        if (!seen.contains(item.getKey()) && !changed.contains(item.getKey())) {
          accounted -= item.getValue().bytes(); iterator.remove();
        }
      }
      state = State.READY;
    } else unavailable(failureCode != null ? failureCode : code == null ? "SOURCE_UNAVAILABLE" : code);
    sweeping = false; changed.clear(); seen.clear(); sweepBytes = 0; publish();
  }

  /** A sweep batches observations; live events and the final boundary publish immediately. */
  void publish() {
    var rows = new ArrayList<>(pending.entrySet());
    rows.sort(Comparator.<Map.Entry<Path, Held>, Instant>comparing(e -> e.getValue().entry().cause().firstFailedAt() == null
            ? null : Instant.parse(e.getValue().entry().cause().firstFailedAt()),
        Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(e -> e.getValue().entry().sessionId())
        .thenComparing(e -> e.getKey().toString()));
    var entries = rows.stream().map(e -> e.getValue().entry()).toList();
    boolean times = entries.stream().allMatch(e -> e.cause().firstFailedAt() != null);
    String oldest = times && !entries.isEmpty() ? entries.get(0).cause().firstFailedAt() : null;
    published.set(new Snapshot(storeId, state, entries.size(), state == State.READY ? entries.size() : null,
        oldest, state == State.READY && times, entries, failureCode,
        failureCode == null ? null : "RESOURCE_LIMIT".equals(failureCode)
            ? "Pending publication inventory exceeds its metadata bound." : message(failureCode)));
  }
}
