/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LogReader;
import org.triplehelix.wpilogmcp.log.LogScan;
import org.triplehelix.wpilogmcp.log.RecordOffsets;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

/** Mapping admission is a short refcount gate. Scanning, sorting, waiting for another sort and
 * unmapping happen outside it. The final cursor releases every window before a move can proceed. */
final class LocalReadSnapshot implements ReadSnapshot {
  private final LocalLogAccess owner;
  private final Path path;
  private final ReadOptions options;
  private final FileSnapshot boundary;
  private final ScopedLogReader scoped;
  private final LogScan scan;
  private final boolean following;
  private final boolean resumed;
  private final StructSchemas schemas;
  private SharedTimeOrders.Lease orderLease;
  private record Version(Path path, FileSnapshot boundary) {}
  private int cursors;
  private boolean retired, releasing;

  static LocalReadSnapshot open(LocalLogAccess owner, Path path, ReadOptions options) throws IOException {
    return open(owner, path, options, null, false);
  }
  static LocalReadSnapshot open(LocalLogAccess owner, Path path, ReadOptions options, LocalReadSnapshot previous, boolean following) throws IOException {
    var before = FileSnapshot.of(path);
    if (before == null) throw new java.nio.file.NoSuchFileException(path.toString());
    var scoped = new ScopedLogReader(path, options.windowBytes());
    try {
      var snapshot = new LocalReadSnapshot(owner, path, options, before, scoped, previous, following);
      snapshot.checkUnchanged();
      if (scoped.reader().size() != before.size())
        throw new ReadException(ReadProblem.Code.FILE_CHANGED, "File grew while mapping " + path + "; reopen it.");
      return snapshot;
    } catch (Throwable e) {
      try { scoped.close(); } catch (IOException close) { e.addSuppressed(close); }
      throw e;
    }
  }

  private LocalReadSnapshot(LocalLogAccess owner, Path path, ReadOptions options,
      FileSnapshot boundary, ScopedLogReader scoped, LocalReadSnapshot previous, boolean following) throws IOException {
    this.owner = owner; this.path = path; this.options = options; this.boundary = boundary; this.scoped = scoped;
    this.following = following;
    try { scan = previous != null && boundary.grewFrom(previous.boundary)
        ? LogScan.resume(previous.scan, scoped.reader(), path, options.maxRecordBytes())
        : LogScan.of(scoped.reader(), path, options.maxRecordBytes()); }
    catch (LogScan.RecordLimitException e) {
      throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, e.getMessage(), e);
    }
    resumed = previous != null && boundary.grewFrom(previous.boundary) && scan.scannedFrom() == previous.scan.resumePoint();
    scan.offsets().values().forEach(RecordOffsets::compact);
    // Reuse schema precedence and assumptions. Only schema payloads are decoded at open, never telemetry.
    for (var entry : scan.entries().values()) {
      if (StructSchemas.schemaEntryStruct(entry.name(), entry.type()) == null) continue;
      var offsets = scan.offsets().get(entry.name());
      if (offsets.isEmpty()) continue;
      long bytes = scoped.reader().recordPayloadSize(offsets.get(0));
      if (bytes > options.maxRecordBytes()) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT,
          "Recorded schema '" + entry.name() + "' has " + bytes + " bytes, above maxRecordBytes="
              + options.maxRecordBytes() + "; its declared layout cannot be replaced by a fallback.");
    }
    schemas = StructSchemas.fromLog(scan.entries(), name -> {
      var offsets = scan.offsets().get(name);
      if (offsets == null || offsets.isEmpty()) return null;
      long offset = offsets.get(0);
      try { return scoped.reader().getRecord(offset).getString(); }
      catch (RuntimeException e) { return null; }
    });
  }

  @Override public Path path() { return path; }
  @Override public FileSnapshot boundary() { return boundary; }
  @Override public Map<String, EntryInfo> entries() { return scan.entries(); }
  @Override public StructSchemas schemas() { return schemas; }
  @Override public long sampleCount(String entry) {
    var offsets = scan.offsets().get(entry); return offsets == null ? 0 : offsets.size();
  }
  @Override public long indexBytes() { return scan.offsets().values().stream().mapToLong(RecordOffsets::storageBytes).sum(); }
  @Override public boolean truncated() { return scan.truncated(); }
  @Override public boolean damaged() { return scan.damaged(); }
  @Override public String truncationMessage() { return scan.truncationMessage(); }
  LogReader reader() { return scoped.reader(); }
  boolean resumed() { return resumed; }
  long scannedFrom() { return scan.scannedFrom(); }
  long resumePoint() { return scan.resumePoint(); }
  int maxRecordBytes() { return options.maxRecordBytes(); }

  void checkUnchanged() throws IOException {
    var now = FileSnapshot.of(path);
    if (following && now != null && now.grewFrom(boundary) && scan.anchorsMatch(scoped.reader())) return;
    if (!boundary.sameAs(now)) throw new ReadException(ReadProblem.Code.FILE_CHANGED,
        "Log changed during this read: " + path + ": " + boundary.describeChange(now) + ". Reopen the snapshot.");
  }

  @Override public SampleCursor read(ReadQuery query) throws IOException {
    var info = entries().get(query.entry());
    if (info == null) throw new IllegalArgumentException("No entry named '" + query.entry() + "' in " + path);
    synchronized (this) {
      if (retired) throw new ReadException(ReadProblem.Code.CLOSED, "Read snapshot is closed: " + path);
      cursors++;
    }
    try {
      checkUnchanged();
      var offsets = scan.offsets().get(query.entry());
      int[] order = query.order() == ReadQuery.Order.TIME && !offsets.monotonic() ? orders().order(query.entry(), offsets.size(), i -> reader().recordTimestampUs(offsets.get(i))) : null;
      var cursor = new LocalSampleCursor(this, info, offsets, order, query);
      checkUnchanged();
      return cursor;
    } catch (Throwable e) {
      try { releaseCursor(); } catch (IOException close) { e.addSuppressed(close); }
      throw e;
    }
  }

  private synchronized TimeOrderCache orders() {
    if (orderLease == null) orderLease = owner.orders().acquire(new Version(path, boundary));
    return orderLease.cache();
  }

  void releaseCursor() throws IOException {
    boolean release;
    synchronized (this) { cursors--; release = claimRelease(); }
    if (release) releaseMapping();
  }
  private boolean claimRelease() {
    if (!retired || cursors != 0 || releasing) return false;
    releasing = true; return true;
  }
  private void releaseMapping() throws IOException {
    try { scoped.close(); }
    finally { if (orderLease != null) orderLease.close(); owner.released(this); }
  }
  @Override public void close() throws IOException {
    boolean release;
    synchronized (this) { retired = true; release = claimRelease(); }
    if (release) releaseMapping();
  }
}
