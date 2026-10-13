/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.rev;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader;

/** An access owner's bounded, immutable REV decodes. One cold parse runs at a time; concurrent
 * readers of that version join it. Active leases are charged even after eviction or shutdown.
 * The eager native parser needs its input in heap, so input size and decoded expansion are bounded
 * before each allocation. This cache never stores an alignment: each manifest read supplies it. */
public final class RevReadCache implements AutoCloseable {
  public static final long MAX_FILE_BYTES = 64L << 20;
  private static final long DEFAULT_BUDGET = 512L << 20;
  private static final long OVERHEAD = 1L << 20;
  private record Key(Path path, FileSnapshot boundary, java.util.Set<String> signals) {}
  private static final class Entry {
    final CompletableFuture<Map<String, RevLogSignal>> data = new CompletableFuture<>();
    long bytes;
    int users;
    Entry(long bytes) { this.bytes = bytes; }
  }
  private final long budget;
  private final Map<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);
  private long held;
  private boolean loading, closed;
  public RevReadCache() { this(DEFAULT_BUDGET); }
  RevReadCache(long budget) { this.budget = budget; }

  Lease acquire(Path path, FileSnapshot boundary) throws IOException { return acquire(path, boundary, null); }
  Lease acquire(Path path, FileSnapshot boundary, java.util.Set<String> signals) throws IOException {
    if (boundary == null) throw new java.nio.file.NoSuchFileException(path.toString());
    if (boundary.size() > MAX_FILE_BYTES) throw limit("REV file exceeds eager read limit " + MAX_FILE_BYTES + " bytes");
    var key = new Key(path, boundary, signals);
    Entry entry;
    boolean parse = false;
    synchronized (this) {
      if (closed) throw new ReadException(ReadProblem.Code.CLOSED, "REV read cache is closed");
      var completeKey = new Key(path, boundary, null);
      if (signals != null && entries.containsKey(completeKey)) key = completeKey;
      entry = entries.get(key);
      if (entry == null) {
        if (loading) throw new ReadException(ReadProblem.Code.BUSY, "Another REV file is being decoded; retry when it completes");
        // Leave room for useful decoding. Evict only idle entries; an HTTP stream's retained
        // values still count, and cannot be made free by removing the map entry.
        var iterator = entries.values().iterator();
        while (iterator.hasNext() && (budget - held < budget / 2 || entries.size() >= 8)) {
          var old = iterator.next();
          if (old.users == 0) { held -= old.bytes; iterator.remove(); }
        }
        if (entries.size() >= 8) throw limit("Eight REV file versions are in use; close another read first");
        long available = budget - held;
        if (available <= 2 * boundary.size() + OVERHEAD + 2048) throw limit("REV read memory is in use; close another read first");
        entry = new Entry(available); held += available;
        entries.put(key, entry); loading = true; parse = true;
      }
      entry.users++;
    }
    if (parse) {
      try (var ignored = LogFileAccess.read(path)) {
        if (!boundary.sameAs(FileSnapshot.of(path))) throw changed(path);
        long decodedBytes = entry.bytes - 2 * boundary.size() - OVERHEAD;
        var dbc = new DbcLoader().loadEmbedded();
        var parser = signals == null ? RevLogParser.compact(dbc, decodedBytes, (int) MAX_FILE_BYTES)
            : RevLogParser.selected(dbc, decodedBytes, (int) MAX_FILE_BYTES, signals);
        var parsed = parser.parse(path);
        if (!boundary.sameAs(FileSnapshot.of(path))) throw changed(path);
        var data = RecordedRevRead.immutableSignals(parsed.signals());
        long weight = 2 * boundary.size() + OVERHEAD + RevLogParser.retainedValueBytes(data);
        synchronized (this) { held -= entry.bytes - weight; entry.bytes = weight; loading = false; }
        entry.data.complete(data);
      } catch (Throwable failure) {
        if (failure instanceof RevLogParser.SampleLimitException) failure = limit(failure.getMessage());
        synchronized (this) { loading = false; entries.remove(key, entry); }
        entry.data.completeExceptionally(failure);
      }
    }
    try { return new Lease(key, entry, entry.data.get()); }
    catch (InterruptedException e) {
      release(key, entry); Thread.currentThread().interrupt(); throw new IOException("Interrupted awaiting REV decode", e);
    } catch (ExecutionException e) {
      release(key, entry);
      var cause = e.getCause();
      if (cause instanceof IOException io) throw io;
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new IOException("REV decoding failed", cause);
    }
  }
  private static ReadException limit(String message) { return new ReadException(ReadProblem.Code.RESOURCE_LIMIT, message); }
  private static ReadException changed(Path path) { return new ReadException(ReadProblem.Code.FILE_CHANGED, "REV file changed while reading: " + path); }
  private synchronized void release(Key key, Entry entry) {
    if (--entry.users == 0 && (closed || entry.data.isCompletedExceptionally())) {
      entries.remove(key, entry); held -= entry.bytes;
    }
  }
  final class Lease implements AutoCloseable {
    private final Key key;
    private final Entry entry;
    final Map<String, RevLogSignal> signals;
    private boolean released;
    Lease(Key key, Entry entry, Map<String, RevLogSignal> signals) { this.key = key; this.entry = entry; this.signals = signals; }
    @Override public synchronized void close() { if (!released) { released = true; release(key, entry); } }
  }
  @Override public synchronized void close() {
    closed = true;
    var iterator = entries.values().iterator();
    while (iterator.hasNext()) { var entry = iterator.next(); if (entry.users == 0) { held -= entry.bytes; iterator.remove(); } }
  }
}
