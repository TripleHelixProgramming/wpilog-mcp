/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LiveLog;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.GuardedSampleCursor;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.ReadSnapshot;
import org.triplehelix.wpilogmcp.read.RecordSeries;
import org.triplehelix.wpilogmcp.read.SampleCursor;
import org.triplehelix.wpilogmcp.read.SampleCursors;
import org.triplehelix.wpilogmcp.read.SharedTimeOrders;
import org.triplehelix.wpilogmcp.read.TimeOrderCache;

/** Compatibility view keeps the original manager identity for reload and synchronization checks.
 * Native cursors borrow that same index and mapping. Closing admission retains the LogUse until
 * every cursor finishes; legacy calls close it only after their annotations or stream are complete. */
public final class ManagedReadSnapshot implements ReadSnapshot {
  private final ManagedLogAccess owner;
  private final LogManager.LogUse use;
  private final SharedTimeOrders orders;
  private SharedTimeOrders.Lease orderLease;
  private final boolean callerOwnsUse;
  private int cursors;
  private boolean retired, callerClosed, released;

  ManagedReadSnapshot(ManagedLogAccess owner, LogManager.LogUse use, SharedTimeOrders orders, boolean callerOwnsUse) {
    this.owner = owner; this.use = use; this.orders = orders;
    this.callerOwnsUse = callerOwnsUse;
  }

  /** Legacy whole-entry caching and diagnostics are a policy over the shared record cursor.
   * Do not wrap this object: the manager's reload hooks compare its original identity. */
  public LogData log() { return use.log(); }
  public FileSnapshot snapshot() { return boundary(); }
  @Override public Path path() { return Path.of(log().path()); }
  /** Null for a writer-owned fixed prefix: appending does not invalidate that prefix. */
  @Override public FileSnapshot boundary() { return use.snapshot(); }
  @Override public Map<String, EntryInfo> entries() { return log().entries(); }
  @Override public StructSchemas schemas() { return log().structSchemas(); }
  @Override public long sampleCount(String entry) { return log().sampleCount(entry); }
  @Override public long indexBytes() {
    if (log() instanceof LazyParsedLog lazy) return lazy.indexBytes();
    if (log() instanceof LiveLog.View live) return live.indexBytes();
    return 0; // Eager compatibility logs hold decoded objects, not indexed byte positions.
  }
  @Override public boolean truncated() { return log().truncated(); }
  @Override public boolean live() { return log() instanceof LiveLog.View; }
  @Override public boolean damaged() { return log().damaged(); }
  @Override public String truncationMessage() { return log().truncationMessage(); }

  private RecordSeries records(String entry) throws IOException {
    if (log() instanceof LazyParsedLog lazy) return lazy.records(entry, true);
    if (log() instanceof LiveLog.View live) return live.records(entry, true);
    // Synthetic/eager compatibility logs have already lost exact timestamps and byte positions.
    // They can still serve legacy tools, but must never manufacture native record coordinates.
    throw new IOException("Exact record access requires an indexed WPILOG: " + path());
  }

  private void checkUnchanged() throws IOException {
    var boundary = boundary();
    if (boundary == null) return;
    var now = FileSnapshot.of(path());
    if (!boundary.sameAs(now)) throw new ReadException(ReadProblem.Code.FILE_CHANGED,
        "Log changed during this read: " + path() + ": " + boundary.describeChange(now)
            + ". Reopen the snapshot.");
  }

  @Override public SampleCursor read(ReadQuery query) throws IOException {
    synchronized (this) {
      if (retired) throw new ReadException(ReadProblem.Code.CLOSED, "Read snapshot is closed: " + path());
      cursors++;
    }
    try {
      checkUnchanged();
      if (!entries().containsKey(query.entry()))
        throw new IllegalArgumentException("No entry named '" + query.entry() + "' in " + path());
      var source = records(query.entry());
      int[] order = query.order() == ReadQuery.Order.TIME && !source.monotonic()
          ? orders().order(query.entry(), source.size(), source::timestampUs) : null;
      var cursor = SampleCursors.open(source, query, order, 64 << 20);
      checkUnchanged();
      return new GuardedSampleCursor(cursor, this::checkUnchanged, this::releaseCursor, path().toString());
    } catch (Throwable failure) {
      releaseCursor(); throw failure;
    }
  }

  private synchronized TimeOrderCache orders() {
    if (orderLease == null) {
      // A loaded disk log is already a version identity. A live view additionally identifies
      // its fixed publication boundary; a later prefix must never reuse a shorter ordering.
      Object version = log() instanceof LiveLog.View live ? live.readIdentity() : log();
      orderLease = orders.acquire(version);
    }
    return orderLease.cache();
  }

  private void releaseCursor() {
    boolean release;
    synchronized (this) { cursors--; release = claimRelease(); }
    if (release) releaseUse();
  }
  private boolean claimRelease() {
    if (!retired || cursors != 0 || released || callerOwnsUse && !callerClosed) return false;
    released = true; return true;
  }
  private void releaseUse() {
    try { use.close(); }
    finally { if (orderLease != null) orderLease.close(); owner.released(this); }
  }
  @Override public void close() {
    boolean release;
    synchronized (this) { retired = true; callerClosed = true; release = claimRelease(); }
    if (release) releaseUse();
  }

  /** An executing compatibility tool owns its acquisition until its call returns, even during
   * manager shutdown. Native snapshots instead retain only their admitted cursors. */
  void retireFromOwner() {
    boolean release;
    synchronized (this) { retired = true; release = claimRelease(); }
    if (release) releaseUse();
  }
}
