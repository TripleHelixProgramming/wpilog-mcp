/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.catalog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.*;
import org.triplehelix.wpilogmcp.store.StoreCatalog;

/** A manifested session on an explicitly chosen anchor clock. Unproven files are named and
 * excluded, never given a fabricated zero offset. Overlapping recordings require KEEP_ALL;
 * their observations retain source coordinates and are never silently deduplicated by value. */
public final class SessionSnapshot implements ReadSnapshot {
  public enum Overlap { REJECT, KEEP_ALL }
  private record Part(ReadSnapshot snapshot, SourceFile source, double start, double end) {}
  private final Path manifest;
  private final List<Part> parts;
  private final List<ExcludedSource> excluded;
  private final Map<String, EntryInfo> entries;
  private final StructSchemas schemas;
  private final StoreReadCatalog owner;
  private final long indexBytes;
  private int cursors;
  private boolean released, membersClosed;
  private volatile boolean closed;

  static SessionSnapshot open(StoreReadCatalog catalog, List<StoreCatalog.StoredFile> files,
      StoreCatalog.StoredFile anchor, Overlap overlap, String collectingStore) throws IOException {
    var parts = new ArrayList<Part>(); var excluded = new ArrayList<ExcludedSource>();
    var hashes = new HashSet<String>(); long charged = 0;
    try {
      var ordered = files.stream().sorted(java.util.Comparator.comparing(f -> !f.path().equals(anchor.path()))).toList();
      for (var file : ordered) {
        if (!"wpilog".equals(file.file().kind())) continue;
        if (file.file().sha256() != null && hashes.contains(file.file().sha256())) {
          excluded.add(new ExcludedSource(file.path().toString(), "Identical SHA-256 already included")); continue;
        }
        var alignment = org.triplehelix.wpilogmcp.store.RecordedClocks.between(file, anchor, files, collectingStore);
        if (alignment.isEmpty() || !alignment.get().aligned()) {
          excluded.add(new ExcludedSource(file.path().toString(), alignment.isEmpty()
              ? "No recorded alignment to the requested anchor" : alignment.get().refusal())); continue;
        }
        var clock = alignment.get().steps();
        if (parts.size() == 64) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "A session union admits at most 64 files");
        boolean open = file.session().openCapture() != null
            && file.session().openCapture().path().equals(file.file().path());
        String unavailable = "This open capture needs its recording server's managed prefix: " + file.path()
            + ". Read it through that server, or follow the file individually.";
        if (open && catalog.localSessions()) {
          if (file.path().equals(anchor.path())) throw new IOException(unavailable);
          excluded.add(new ExcludedSource(file.path().toString(), unavailable)); continue;
        }
        var snapshot = catalog.openWpilog(file.path());
        if (open && !snapshot.live()) {
          snapshot.close();
          if (file.path().equals(anchor.path())) throw new IOException(unavailable);
          excluded.add(new ExcludedSource(file.path().toString(), unavailable)); continue;
        }
        try { catalog.reserveIndex(snapshot.indexBytes()); }
        catch (Throwable failure) { try { snapshot.close(); } catch (IOException e) { failure.addSuppressed(e); } throw failure; }
        charged += snapshot.indexBytes();
        var source = new SourceFile(file.path().toString(), file.file().sha256(), file.file().provenance(), clock);
        double start = file.file().minTimestampSec(), end = file.file().maxTimestampSec();
        for (var step : clock) { start = step.toFpgaTime(start); end = step.toFpgaTime(end); }
        parts.add(new Part(snapshot, source, start, end));
        if (file.file().sha256() != null) hashes.add(file.file().sha256());
      }
      if (parts.isEmpty()) throw new IOException("No readable WPILOG on the requested session clock");
      return new SessionSnapshot(catalog, anchor.manifestPath(), parts, excluded, overlap);
    } catch (Throwable failure) {
      for (var part : parts) try { part.snapshot().close(); } catch (IOException e) { failure.addSuppressed(e); }
      catalog.releaseIndex(charged, null);
      throw failure;
    }
  }
  private SessionSnapshot(StoreReadCatalog owner, Path manifest, List<Part> parts, List<ExcludedSource> excluded, Overlap overlap) throws IOException {
    this.owner = owner; this.manifest = manifest; this.parts = List.copyOf(parts); this.excluded = List.copyOf(excluded);
    this.indexBytes = parts.stream().mapToLong(p -> p.snapshot().indexBytes()).sum();
    var names = new LinkedHashMap<String, EntryInfo>();
    var layouts = new LinkedHashMap<String, StructSchemas.StructInfo>();
    for (int i = 0; i < parts.size(); i++) {
      var part = parts.get(i);
      for (var entry : part.snapshot().entries().values()) {
        var old = names.putIfAbsent(entry.name(), entry);
        if (old != null && (!old.type().equals(entry.type()) || !java.util.Objects.equals(old.metadata(), entry.metadata())))
          throw new IOException("Conflicting type or metadata for '" + entry.name() + "' in session " + manifest);
        if (overlap == Overlap.REJECT) for (int j = 0; j < i; j++) {
          var previous = parts.get(j);
          // Manifest timestamps can lag a writer-owned prefix. They cannot prove that a
          // live entry is disjoint from another recording, even when the manifest says so.
          boolean live = part.snapshot().live() || previous.snapshot().live();
          if (previous.snapshot().entries().containsKey(entry.name()) && (live
              || part.start() <= previous.end() && previous.start() <= part.end()))
            throw new IOException((live ? "Potentially overlapping live recordings of '" : "Overlapping recordings of '") + entry.name()
                + "'; choose KEEP_ALL to retain both sources");
        }
      }
      for (var name : part.snapshot().schemas().allStructs()) {
        var schema = part.snapshot().schemas().info(name).orElseThrow(); var old = layouts.putIfAbsent(name, schema);
        if (old != null && !java.util.Objects.equals(old.schema(), schema.schema())) throw new IOException("Conflicting schema " + name + " in " + manifest);
      }
    }
    entries = java.util.Collections.unmodifiableMap(names); schemas = StructSchemas.fromDescriptions(List.copyOf(layouts.values()));
  }
  @Override public Path path() { return manifest; }
  @Override public FileSnapshot boundary() { return null; }
  @Override public boolean live() { return parts.stream().anyMatch(p -> p.snapshot().live()); }
  @Override public List<SourceFile> sources() { return parts.stream().map(Part::source).toList(); }
  @Override public List<ExcludedSource> excludedSources() { return excluded; }
  @Override public Map<String, EntryInfo> entries() { return entries; }
  @Override public StructSchemas schemas() { return schemas; }
  @Override public long sampleCount(String name) { return parts.stream().mapToLong(p -> p.snapshot().sampleCount(name)).sum(); }
  @Override public long indexBytes() { return indexBytes; }
  @Override public boolean truncated() { return parts.stream().anyMatch(p -> p.snapshot().truncated()); }
  @Override public boolean damaged() { return parts.stream().anyMatch(p -> p.snapshot().damaged()); }
  @Override public String truncationMessage() {
    var messages = parts.stream().filter(p -> p.snapshot().truncationMessage() != null)
        .map(p -> p.source().path() + ": " + p.snapshot().truncationMessage()).toList();
    return messages.isEmpty() ? null : String.join("; ", messages);
  }
  @Override public SampleCursor read(ReadQuery query) throws IOException {
    if (closed) throw new ReadException(ReadProblem.Code.CLOSED, "Session snapshot is closed");
    if (!entries.containsKey(query.entry())) throw new IllegalArgumentException("Unknown entry " + query.entry());
    if (query.order() != ReadQuery.Order.TIME) throw new IllegalArgumentException("A session union requires time order; use individual files for file order");
    if (query.firstOrdinal() != 0) throw new IllegalArgumentException("A session has no single file ordinal; use individual files for first_ordinal");
    if (query.reduction() != null && query.windows().isEmpty()) throw new IllegalArgumentException("A reduced session union requires explicit windows on the anchor clock");
    synchronized (this) {
      if (closed) throw new ReadException(ReadProblem.Code.CLOSED, "Session snapshot is closed");
      cursors++;
    }
    var children = new ArrayList<SessionCursor.Child>();
    try {
      for (var part : parts) if (part.snapshot().entries().containsKey(query.entry())) {
        var windows = SessionClock.sourceWindows(query.windows(), part.source().clock());
        if (!query.windows().isEmpty() && windows.isEmpty()) continue;
        // Seek conservatively on the source clock, then test the exact transformed timestamp.
        // Outward rounding preserves edge samples without decoding every record in the entry.
        var raw = new ReadQuery(query.entry(), windows, ReadQuery.Order.TIME, 64, 64 << 10, query.field(), null, 0, query.recordLimit());
        children.add(new SessionCursor.Child(part.snapshot().read(raw), part.source()));
      }
      if (closed) throw new ReadException(ReadProblem.Code.CLOSED, "Session closed during admission");
      var cursor = SampleCursors.reduce(new SessionCursor(children, query), query, 0, 0);
      // A merge may return cached heads without asking a child for another batch. It must
      // still reject changes to any disk source and honor close after a reduced terminal.
      return new GuardedSampleCursor(cursor, this::checkBoundaries, this::releaseCursor, manifest.toString());
    } catch (Throwable failure) {
      for (var child : children) try { child.cursor.close(); } catch (IOException e) { failure.addSuppressed(e); }
      releaseCursor(); throw failure;
    }
  }
  private void checkBoundaries() throws IOException {
    for (var part : parts) {
      var boundary = part.snapshot().boundary();
      if (boundary != null && !boundary.sameAs(FileSnapshot.of(part.snapshot().path())))
        throw new ReadException(ReadProblem.Code.FILE_CHANGED,
            "Session source changed during this read: " + part.source().path() + ". Reopen the session.");
    }
  }
  private void releaseCursor() {
    synchronized (this) { cursors--; }
    releaseBudget();
  }
  private void releaseBudget() {
    synchronized (this) {
      if (!membersClosed || cursors != 0 || released) return;
      released = true;
    }
    owner.releaseIndex(indexBytes(), this);
  }
  @Override public void close() throws IOException {
    synchronized (this) { if (closed) return; closed = true; }
    IOException failure = null;
    for (var part : parts) try { part.snapshot().close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    synchronized (this) { membersClosed = true; }
    releaseBudget();
    if (failure != null) throw failure;
  }
}
