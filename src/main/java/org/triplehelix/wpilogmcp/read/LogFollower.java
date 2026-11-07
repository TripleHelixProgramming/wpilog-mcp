/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.triplehelix.wpilogmcp.log.FileSnapshot;

/** Caller-driven append following reuses the scan's identity and anchor rules. A replacement
 * resets the ordinal continuation explicitly. Snapshots belong to this follower; cursors retain
 * each mapped prefix independently and may finish after refresh or close. */
public final class LogFollower implements AutoCloseable {
  public enum State { INITIAL, UNCHANGED, APPENDED, REPLACED }
  public record Update(State state, ReadSnapshot snapshot, long scannedFrom, long resumePoint,
      Map<String, Long> previousCounts) {
    public Update { previousCounts = Map.copyOf(previousCounts); }
    /** New physical records, including repeated and backward timestamps; no timestamp guessing. */
    public SampleCursor readNew(String entry) throws IOException {
      return snapshot.read(new ReadQuery(entry, List.of(), ReadQuery.Order.FILE, 4096, 4 << 20)
          .fromOrdinal(Math.toIntExact(previousCounts.getOrDefault(entry, 0L))));
    }
  }
  private final LocalLogAccess access;
  private final Path path;
  private final AtomicReference<LocalReadSnapshot> current = new AtomicReference<>();
  private final AtomicBoolean refreshing = new AtomicBoolean(), closed = new AtomicBoolean();
  LogFollower(LogAccess access, Path path) throws IOException {
    if (!(access instanceof LocalLogAccess local)) throw new IllegalArgumentException("Local append following requires LogAccess.local");
    this.access = local; this.path = path;
  }
  public Update refresh() throws IOException {
    if (!refreshing.compareAndSet(false, true)) throw new IllegalStateException("Only one follower refresh may run at once");
    try {
      if (closed.get()) throw new ReadException(ReadProblem.Code.CLOSED, "Log follower is closed");
      var old = current.get();
      var counts = new java.util.LinkedHashMap<String, Long>();
      if (old != null) old.entries().keySet().forEach(e -> counts.put(e, old.sampleCount(e)));
      if (old != null && old.boundary().sameAs(FileSnapshot.of(path)))
        return new Update(State.UNCHANGED, old, old.scannedFrom(), old.resumePoint(), counts);
      var next = access.open(path, old, true);
      if (closed.get() || !current.compareAndSet(old, next)) {
        next.close(); throw new ReadException(ReadProblem.Code.CLOSED, "Follower closed during refresh");
      }
      if (closed.get()) { close(); throw new ReadException(ReadProblem.Code.CLOSED, "Follower closed during refresh"); }
      var state = old == null ? State.INITIAL : next.resumed() ? State.APPENDED : State.REPLACED;
      if (old != null) old.close();
      return new Update(state, next, next.scannedFrom(), next.resumePoint(), state == State.APPENDED ? counts : Map.of());
    } finally { refreshing.set(false); }
  }
  @Override public void close() throws IOException {
    closed.set(true); var snapshot = current.getAndSet(null); if (snapshot != null) snapshot.close();
  }
}
