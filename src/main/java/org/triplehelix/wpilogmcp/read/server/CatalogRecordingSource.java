/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.server;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.catalog.RecordingReference;
import org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog;

/** Catalog/provenance admission for the ordinal engine. Only the read worker probes publication;
 * the recorder exposes only writer identity and revision, and never executes follower work.
 * External store writers and grant changes are checked at most five seconds apart while idle.
 * No unchanged poll opens a native snapshot, decodes a value or scans a WPILOG prefix. */
public final class CatalogRecordingSource implements OrdinalFollow.Source {
  public interface Lease extends AutoCloseable {
    StoreReadCatalog value();
    @Override void close() throws IOException;
  }
  @FunctionalInterface public interface Catalogs { Lease acquire() throws IOException; }
  private record Stamp(Path path, FileSnapshot boundary, String hash) {}
  private final LogManager logs;
  private final Catalogs catalogs;
  private final RecordingReference reference;
  private final VerifiedContentHashes verified;
  private StoreReadCatalog.Recording recording;
  private Map<Path, Object> publications = Map.of();
  private List<Stamp> stamps = List.of();
  private long catalogPublication, revision, checkedAt;

  public CatalogRecordingSource(LogManager logs, Catalogs catalogs, RecordingReference reference) {
    this(logs, catalogs, reference, new VerifiedContentHashes());
  }
  public CatalogRecordingSource(LogManager logs, Catalogs catalogs, RecordingReference reference, VerifiedContentHashes verified) {
    this.logs = logs; this.catalogs = catalogs; this.reference = reference;
    this.verified = verified;
  }
  public RecordingReference reference() { return reference; }
  @FunctionalInterface public interface Access { void check() throws IOException; }
  /** Grant-only ticket: no prefix, descriptors, catalog snapshot or recording manifest retained. */
  public Access access() {
    if (recording == null) throw new IllegalStateException("Open the recording before detaching its access ticket");
    record Location(Path root, Path path, String member) {}
    var locations = new java.util.concurrent.atomic.AtomicReference<>(recording.members().stream()
        .map(m -> new Location(m.root(), m.facts().path(), m.facts().file().provenance().originalName())).toList());
    var catalogs = this.catalogs; var logs = this.logs; var reference = this.reference;
    return () -> {
      try (var lease = catalogs.acquire()) {
        var retained = locations.get();
        if (lease.value() == null || retained.stream().anyMatch(m -> !lease.value().roots().contains(m.root())))
          throw new java.nio.file.AccessDeniedException("Recording store is no longer configured");
        try { for (var member : retained) logs.validateSourcePath(member.path()); }
        catch (java.nio.file.NoSuchFileException moved) {
          // Only a missing old path needs a fresh metadata resolution. The catalog owns
          // recording aliases; ordinary pages never reopen a prefix or hash file contents.
          var current = lease.value().recording(reference); var updated = new ArrayList<Location>();
          for (var before : retained) {
            var member = current.members().stream().filter(m -> m.facts().file().provenance().originalName().equals(before.member())).findFirst()
                .orElseThrow(() -> new java.nio.file.AccessDeniedException("Recording member is no longer available"));
            updated.add(new Location(member.root(), logs.validateSourcePath(member.facts().path()), before.member()));
          }
          locations.set(List.copyOf(updated));
        }
      }
    };
  }

  @Override public void authorize() throws IOException { if (recording != null) access().check(); }
  @Override public OrdinalFollow.Prefix open() throws IOException {
    long version = logs.catalogPublication();
    try (var lease = catalogs.acquire()) {
      if (lease.value() == null) throw new IOException("SOURCE_UNAVAILABLE: no configured recording store");
      var resolved = lease.value().recording(reference);
      if (resolved.members().size() > 64) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "A follow admits at most 64 recording members");
      var tokens = new HashMap<Path, Object>(); var boundaries = new ArrayList<Stamp>();
      var opened = new ArrayList<OrdinalFollow.Member>();
      long indexed = 0;
      try {
        for (var member : resolved.members()) {
          var path = logs.validateSourcePath(member.facts().path());
          // Observe before opening: a publication racing snapshot admission can only cause
          // an extra wake, never hide an ordinal until a later sample happens to arrive.
          tokens.put(path, logs.capturePublication(path));
          var stamp = new Stamp(path, FileSnapshot.of(path), member.facts().file().sha256()); boundaries.add(stamp);
          boolean growing = StoreReadCatalog.open(member.facts());
          if (!growing) verified.verify(path, stamp.boundary(), stamp.hash());
          var snapshot = lease.value().openWpilog(path);
          // Register ownership before any validation below can throw.
          opened.add(new OrdinalFollow.Member(member.facts().file().provenance().originalName(), 0, snapshot, stamp.hash()));
          if (!growing && (!java.util.Objects.equals(stamp.boundary(), FileSnapshot.of(path))
              || snapshot.boundary() != null && !stamp.boundary().sameAs(snapshot.boundary())))
            throw new IOException("SOURCE_UNAVAILABLE: finalized recording changed after hash verification");
          String identity = member.facts().file().provenance().writerIdentity();
          if (growing && snapshot instanceof ManagedReadSnapshot managed && managed.log() instanceof org.triplehelix.wpilogmcp.log.LiveLog.View live) {
            if (identity != null && !identity.equals(live.captureIdentity()))
              throw new ReadException(ReadProblem.Code.FILE_CHANGED, "Recording metadata belongs to another writer; explicitly rebootstrap the same retained recording");
            identity = live.captureIdentity();
          }
          opened.set(opened.size() - 1, new OrdinalFollow.Member(member.facts().file().provenance().originalName(), 0, snapshot, stamp.hash(), identity));
          if (growing && !snapshot.live()) throw new IOException("SOURCE_UNAVAILABLE: open recording needs its writer's managed prefix");
          indexed = Math.addExact(indexed, snapshot.indexBytes());
          if (indexed > StoreReadCatalog.DEFAULT_SESSION_INDEX_BYTES)
            throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Recording indexes exceed the 128 MiB follow admission budget");
        }
        String identity = java.util.UUID.nameUUIDFromBytes((resolved.identity().collectingStoreId() + "\n"
            + resolved.identity().startedAt()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var prefix = new OrdinalFollow.Prefix(identity, ++revision, resolved.finalized(), opened);
        recording = resolved; publications = java.util.Collections.unmodifiableMap(tokens);
        stamps = List.copyOf(boundaries); catalogPublication = version; checkedAt = System.nanoTime();
        return prefix;
      } catch (Throwable failure) {
        for (var member : opened) try { member.snapshot().close(); } catch (IOException e) { failure.addSuppressed(e); }
        throw failure;
      }
    }
  }
  @Override public boolean awaitChange(Duration wait, BooleanSupplier cancelled) throws IOException {
    long deadline = System.nanoTime() + wait.toNanos();
    do {
      if (cancelled.getAsBoolean()) return false;
      for (var token : publications.entrySet())
        if (!java.util.Objects.equals(token.getValue(), logs.capturePublication(token.getKey()))) return true;
      long now = System.nanoTime(), version = logs.catalogPublication();
      if (version != catalogPublication || now - checkedAt >= Duration.ofSeconds(5).toNanos()) {
        try (var lease = catalogs.acquire()) {
          if (lease.value() == null) throw new IOException("SOURCE_UNAVAILABLE: recording store is no longer configured");
          var current = lease.value().recording(reference);
          for (var member : current.members()) logs.validateSourcePath(member.facts().path());
          if (!recording.equals(current)) return true;
          for (var stamp : stamps) if (!java.util.Objects.equals(stamp.boundary(), FileSnapshot.of(stamp.path()))) return true;
        }
        catalogPublication = version; checkedAt = now;
      }
      if (now >= deadline) return false;
      java.util.concurrent.locks.LockSupport.parkNanos(Math.min(deadline - now, Duration.ofMillis(25).toNanos()));
      if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Follow wait was cancelled");
    } while (true);
  }
}
