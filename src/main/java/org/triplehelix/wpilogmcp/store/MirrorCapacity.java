/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;

/**
 * One mirror's logical disk budget, not filesystem allocation or JVM heap. Metadata headroom
 * stays inside the cap so payload admission cannot consume space needed for confirmations and
 * cleanup. The caller keeps a reservation until I/O actually unwinds, even after a timeout.
 */
public final class MirrorCapacity {
  public record Snapshot(long heldBytes, long metadataBytes, long reservedBytes, long limitBytes,
      long metadataReserveBytes, boolean capacityPaused) {}
  private long held, metadata, reserved, reservedMetadata, limit, headroom;

  public MirrorCapacity(long held, long metadata, long limit, long headroom) {
    if (held < 0 || metadata < 0 || limit <= 0 || headroom < 0) throw new IllegalArgumentException("Invalid mirror accounting");
    this.held = held; this.metadata = metadata; this.limit = limit; this.headroom = headroom;
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(held, metadata, Math.addExact(reserved, reservedMetadata), limit, headroom, !fits(1, 0));
  }

  /** Measure engine-owned logical bytes outside a store lock. Unmanaged files affect physical
   * free space but are neither adopted nor counted as disposable replica payloads. */
  static MirrorCapacity measure(java.nio.file.Path root,
      org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator security,
      org.triplehelix.wpilogmcp.config.MirrorConfig config) throws IOException {
    var catalog = StoreCatalog.read(root, security);
    var unmanaged = new java.util.HashSet<>(catalog.unmanaged());
    var payloads = new java.util.HashSet<java.nio.file.Path>();
    catalog.allFiles().forEach(file -> payloads.add(file.path()));
    var io = new StoreFiles(root, security);
    var journal = ReplicaJournal.read(root, io);
    for (var entry : journal.retired()) payloads.add(io.resolve(root, entry.path()));
    if (journal.pending() != null && journal.pending().operation().equals("transfer")) {
      payloads.add(io.resolve(root, journal.pending().target().path()));
      payloads.add(io.resolve(root, journal.pending().staging()));
    }
    for (var session : catalog.sessions()) {
      for (var clock : session.session().clocks()) payloads.add(ClockRecords.resolve(io, session.path(), clock));
      for (var text : session.session().systemLogs().files())
        payloads.add(SystemLogFiles.resolve(io, root, session.path(), session.session(), text));
    }
    for (var robot : catalog.robots()) for (var text : SystemLogIndex.read(io, robot.path()).files())
      payloads.add(SystemLogFiles.shared(io, root, robot.path(), text.file()));
    java.nio.file.Path linkedStage = null;
    if (journal.pending() != null && journal.pending().operation().equals("transfer")) {
      var stage = io.resolve(root, journal.pending().staging()); var object = io.resolve(root, journal.pending().target().path());
      if (java.nio.file.Files.exists(stage) && java.nio.file.Files.exists(object) && java.nio.file.Files.isSameFile(stage, object)) linkedStage = stage;
    }
    long held = 0, metadata = 0;
    try {
      for (var path : StoreCatalog.regularFiles(root)) {
        io.check(path);
        if (unmanaged.contains(path) || path.equals(root.resolve("store.lock")) || path.equals(linkedStage)) continue;
        long bytes = java.nio.file.Files.size(path);
        if (payloads.contains(path) || path.startsWith(root.resolve(".mirror/files"))) held = Math.addExact(held, bytes);
        else metadata = Math.addExact(metadata, bytes);
      }
    } catch (ArithmeticException overflow) { throw new IOException("Mirror logical byte count overflow", overflow); }
    return new MirrorCapacity(held, metadata, config.maxSizeBytes(), config.metadataReserveBytes());
  }

  private boolean fits(long extra, long extraMetadata) {
    try { return Math.addExact(Math.addExact(held, reserved), Math.addExact(extra,
        Math.max(Math.addExact(Math.addExact(metadata, reservedMetadata), extraMetadata), headroom))) <= limit; }
    catch (ArithmeticException overflow) { return false; }
  }

  /** Pins and replacement jobs use this same admission; neither can bypass the cap. */
  public synchronized Reservation reserve(long bytes) throws IOException {
    if (bytes < 0) throw new IllegalArgumentException("Negative reservation");
    if (!fits(bytes, 0)) throw new IOException("CAPACITY_PAUSED: held bytes and metadata headroom leave insufficient capacity");
    reserved = Math.addExact(reserved, bytes); return new Reservation(bytes, false);
  }
  public synchronized Reservation reserveMetadata(long bytes) throws IOException {
    if (bytes < 0) throw new IllegalArgumentException("Negative reservation");
    if (!fits(0, bytes)) throw new IOException("CAPACITY_PAUSED: metadata publication requires more capacity; held files preserved");
    reservedMetadata = Math.addExact(reservedMetadata, bytes); return new Reservation(bytes, true);
  }

  public final class Reservation implements AutoCloseable {
    private final long bytes;
    private final boolean metadataCharge;
    private boolean released;
    private Reservation(long bytes, boolean metadataCharge) { this.bytes = bytes; this.metadataCharge = metadataCharge; }
    /** Publication transfers the charge; it does not make the new bytes free. */
    public void publish() {
      synchronized (MirrorCapacity.this) {
        if (released) throw new IllegalStateException("Reservation already released");
        if (metadataCharge) { metadata = Math.addExact(metadata, bytes); reservedMetadata -= bytes; }
        else { held = Math.addExact(held, bytes); reserved -= bytes; }
        released = true;
      }
    }
    @Override public void close() {
      synchronized (MirrorCapacity.this) {
        if (!released) {
          if (metadataCharge) reservedMetadata -= bytes; else reserved -= bytes;
          released = true;
        }
      }
    }
  }
}
