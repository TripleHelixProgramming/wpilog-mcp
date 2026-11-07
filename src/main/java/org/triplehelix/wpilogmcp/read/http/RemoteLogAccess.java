/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.http;

import org.triplehelix.wpilogmcp.read.internal.ReadWire;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.LogAccess;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.ReadSnapshot;
import org.triplehelix.wpilogmcp.read.SampleBatch;
import org.triplehelix.wpilogmcp.read.SampleCursor;

/** A remote read has the same explicit lifetime as a local one; no daemon installation occurs. */
public final class RemoteLogAccess implements LogAccess {
  private final URI server;
  private final HttpClient client;
  private final ScheduledExecutorService deadlines;
  private final Duration timeout;
  private final Set<RemoteSnapshot> snapshots = new HashSet<>();
  private boolean closed;
  private int admissions;
  public RemoteLogAccess(URI server) { this(server, Duration.ofSeconds(30)); }
  public RemoteLogAccess(URI server, Duration timeout) {
    if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Remote read timeout must be positive");
    this.timeout = timeout;
    this.server = validateServer(server);
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    deadlines = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "remote-read-deadlines"); t.setDaemon(true); return t; });
  }
  static URI validateServer(URI server) {
    if (server == null || !("http".equals(server.getScheme()) || "https".equals(server.getScheme()))
        || server.getHost() == null || server.getUserInfo() != null || server.getQuery() != null || server.getFragment() != null
        || !(server.getPath().isEmpty() || server.getPath().equals("/")))
      throw new IllegalArgumentException("A read server must be an http(s) origin without credentials, a path, query or fragment");
    return server;
  }
  int retainedSnapshots() { synchronized (snapshots) { return snapshots.size(); } }
  @Override public RemoteSnapshot open(Path path) throws IOException { return open(path.toString()); }
  /** Remote paths are server-owned strings: a Windows source is not parsed on the client's OS. */
  public RemoteSnapshot open(String serverPath) throws IOException {
    var body = new JsonObject(); body.addProperty("path", serverPath); return open(body);
  }
  public RemoteSnapshot open(String storeId, String sessionId, String filePath) throws IOException {
    var body = new JsonObject(); body.addProperty("store_id", storeId); body.addProperty("session_id", sessionId); body.addProperty("file_path", filePath); return open(body);
  }
  public RemoteSnapshot openSession(String storeId, String sessionId, String anchorPath,
      org.triplehelix.wpilogmcp.read.catalog.SessionSnapshot.Overlap overlap) throws IOException {
    var body = new JsonObject(); body.addProperty("store_id", storeId); body.addProperty("session_id", sessionId);
    body.addProperty("anchor_path", anchorPath); body.addProperty("overlap", overlap.name().toLowerCase(java.util.Locale.ROOT));
    return open(body);
  }
  public org.triplehelix.wpilogmcp.read.CatalogDescriptor catalog() throws IOException {
    admit();
    try { return ReadWire.JSON.fromJson(json("GET", "/read/v1/catalog", null, 200).get("catalog"),
        org.triplehelix.wpilogmcp.read.CatalogDescriptor.class); }
    finally { finishAdmission(); }
  }
  private RemoteSnapshot open(JsonObject request) throws IOException {
    admit();
    try {
      var descriptor = json("POST", "/read/v1/snapshots", request, 201);
      RemoteSnapshot snapshot;
      try { snapshot = new RemoteSnapshot(descriptor); }
      catch (RuntimeException failure) {
        var explained = new IOException("Invalid remote read descriptor", failure);
        var value = descriptor.get("id");
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            && value.getAsString().matches("[A-Za-z0-9-]+")) {
          try { delete(value.getAsString()); } catch (IOException cleanup) { explained.addSuppressed(cleanup); }
        }
        throw explained;
      }
      synchronized (snapshots) {
        if (!closed) { snapshots.add(snapshot); return snapshot; }
      }
      snapshot.close(); throw new ReadException(ReadProblem.Code.CLOSED, "Remote read access closed while opening a snapshot");
    } finally { finishAdmission(); }
  }
  private void admit() throws ReadException {
    synchronized (snapshots) {
      if (closed) throw new ReadException(ReadProblem.Code.CLOSED, "Remote read access is closed");
      admissions++;
    }
  }
  private void finishAdmission() {
    synchronized (snapshots) { admissions--; }
    finishClose();
  }
  private HttpResponse<InputStream> request(String method, String path, JsonObject body) throws IOException {
    var builder = HttpRequest.newBuilder(server.resolve(path)).timeout(timeout)
        .header("Content-Type", "application/json");
    try {
      return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofByteArray(ReadWire.bytes(body, 64 << 10))).build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt(); throw new IOException("Interrupted while reading the remote server", failure);
    }
  }
  private JsonObject json(String method, String path, JsonObject body, int expected) throws IOException {
    var response = request(method, path, body);
    try (var input = response.body()) {
      var deadline = deadlines.schedule(() -> closeInput(input), timeout.toNanos(), TimeUnit.NANOSECONDS);
      try {
        byte[] bytes = input.readNBytes(ReadWire.MAX_FRAME_BYTES + 1);
        if (bytes.length > ReadWire.MAX_FRAME_BYTES) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Remote read response exceeds its byte bound");
        if (response.statusCode() != expected) throw ReadWire.responseFailure(response.statusCode(), bytes);
        return ReadWire.parse(bytes);
      } finally { deadline.cancel(false); }
    }
  }
  private static void closeInput(InputStream input) { try { input.close(); } catch (IOException ignored) {} }
  private void delete(String id) throws IOException {
    var response = request("DELETE", "/read/v1/snapshots/" + id, null);
    try (var input = response.body()) {
      if (response.statusCode() != 204 && response.statusCode() != 410) throw new IOException("Closing the remote read snapshot returned HTTP " + response.statusCode());
    }
  }

  /** The server path and store facts stay verbatim; path() is only the local Java convenience. */
  public final class RemoteSnapshot implements ReadSnapshot {
    private final JsonObject descriptor;
    private final org.triplehelix.wpilogmcp.read.SnapshotDescriptor model;
    private final String id;
    private final Map<String, EntryInfo> entries;
    private final StructSchemas schemas;
    private final Set<RemoteCursor> cursors = new HashSet<>();
    private boolean retired, deleted;
    private int admissions;
    RemoteSnapshot(JsonObject descriptor) {
      if (ReadWire.integer(descriptor, "api_version") != ReadWire.VERSION) throw new IllegalArgumentException("Unsupported read API version");
      this.descriptor = descriptor.deepCopy();
      this.model = ReadWire.JSON.fromJson(descriptor, org.triplehelix.wpilogmcp.read.SnapshotDescriptor.class);
      this.id = ReadWire.text(descriptor, "id");
      if (!id.matches("[A-Za-z0-9-]+")) throw new IllegalArgumentException("Invalid remote snapshot id");
      Map<String, EntryInfo> recordedEntries = ReadWire.JSON.fromJson(descriptor.get("entries"), new TypeToken<Map<String, EntryInfo>>() {}.getType());
      entries = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(recordedEntries));
      schemas = StructSchemas.fromDescriptions(List.of(ReadWire.JSON.fromJson(descriptor.get("schemas"), StructSchemas.StructInfo[].class)));
    }
    public String serverPath() { return ReadWire.text(descriptor, "path"); }
    public org.triplehelix.wpilogmcp.read.SnapshotDescriptor descriptor() {
      return model;
    }
    @Override public Path path() { return Path.of(serverPath()); }
    @Override public boolean live() { return descriptor().live(); }
    @Override public List<ReadSnapshot.SourceFile> sources() { return descriptor().sources(); }
    @Override public List<ReadSnapshot.ExcludedSource> excludedSources() { return descriptor().excludedSources(); }
    @Override public FileSnapshot boundary() { return ReadWire.boundary(descriptor.get("boundary")); }
    @Override public Map<String, EntryInfo> entries() { return entries; }
    @Override public StructSchemas schemas() { return schemas; }
    @Override public long sampleCount(String name) {
      var count = descriptor.getAsJsonObject("sample_counts").get(name); return count == null ? 0 : count.getAsLong();
    }
    @Override public long indexBytes() { return Long.parseLong(ReadWire.text(descriptor, "index_bytes")); }
    @Override public boolean truncated() { return descriptor.get("truncated").getAsBoolean(); }
    @Override public boolean damaged() { return descriptor.get("damaged").getAsBoolean(); }
    @Override public String truncationMessage() { return ReadWire.nullableText(descriptor, "truncation_message"); }
    @Override public SampleCursor read(ReadQuery query) throws IOException {
      return new org.triplehelix.wpilogmcp.read.GuardedSampleCursor(requestCursor(ReadWire.query(query)), () -> {}, () -> {}, serverPath());
    }
    @Override public org.triplehelix.wpilogmcp.read.MultiSampleCursor readAll(List<ReadQuery> requested) throws IOException {
      var queries = org.triplehelix.wpilogmcp.read.MultiSampleCursor.validate(requested);
      var body = new JsonObject(); var list = new com.google.gson.JsonArray();
      queries.forEach(q -> list.add(ReadWire.query(q))); body.add("queries", list);
      var cursor = requestCursor(body);
      return new org.triplehelix.wpilogmcp.read.MultiSampleCursor() {
        public Batch next() throws IOException {
          var batch = cursor.next(); int index = cursor.queryIndex;
          if (index < 0 || index >= queries.size()) throw new IOException("Invalid remote query index");
          return new Batch(index, queries.get(index).entry(), new SampleBatch(batch.samples(), batch.problems(),
              batch.recordsVisited(), batch.payloadBytes(), cursor.entryComplete), batch.complete());
        }
        public void close() throws IOException { cursor.close(); }
      };
    }
    private RemoteCursor requestCursor(JsonObject body) throws IOException {
      synchronized (cursors) {
        if (retired) throw new ReadException(ReadProblem.Code.CLOSED, "Remote snapshot is closed");
        admissions++;
      }
      try {
        body.addProperty("format", "ndjson");
        var response = request("POST", "/read/v1/snapshots/" + id + "/query", body);
        if (response.statusCode() != 200) {
          try (var input = response.body()) {
            var deadline = deadlines.schedule(() -> closeInput(input), timeout.toNanos(), TimeUnit.NANOSECONDS);
            try {
              byte[] bytes = input.readNBytes(ReadWire.MAX_FRAME_BYTES + 1);
              if (bytes.length > ReadWire.MAX_FRAME_BYTES) throw new IOException("Oversized remote read error");
              throw ReadWire.responseFailure(response.statusCode(), bytes);
            } finally { deadline.cancel(false); }
          }
        }
        var cursor = new RemoteCursor(this, new BufferedInputStream(response.body()));
        synchronized (cursors) {
          if (!retired) {
            cursors.add(cursor);
            return cursor;
          }
        }
        cursor.close(); throw new ReadException(ReadProblem.Code.CLOSED, "Remote snapshot closed during query admission");
      } finally {
        boolean remove;
        synchronized (cursors) {
          admissions--; remove = retired && admissions == 0 && cursors.isEmpty() && !deleted;
          if (remove) deleted = true;
        }
        if (remove) releaseRemote();
      }
    }
    private void release(RemoteCursor cursor) throws IOException {
      boolean remove;
      synchronized (cursors) { cursors.remove(cursor); remove = retired && admissions == 0 && cursors.isEmpty() && !deleted; if (remove) deleted = true; }
      if (remove) releaseRemote();
    }
    @Override public void close() throws IOException {
      boolean remove;
      synchronized (cursors) { retired = true; remove = admissions == 0 && cursors.isEmpty() && !deleted; if (remove) deleted = true; }
      if (remove) releaseRemote();
    }
    private void releaseRemote() throws IOException {
      try { delete(id); }
      finally { synchronized (snapshots) { snapshots.remove(this); } finishClose(); }
    }
  }

  private final class RemoteCursor implements SampleCursor {
    private final RemoteSnapshot owner;
    private final InputStream input;
    private volatile boolean complete;
    private int queryIndex;
    private boolean entryComplete;
    private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
    RemoteCursor(RemoteSnapshot owner, InputStream input) { this.owner = owner; this.input = input; }
    @Override public SampleBatch next() throws IOException {
      if (complete) return new SampleBatch(List.of(), List.of(), 0, 0, true);
      if (released.get()) throw new ReadException(ReadProblem.Code.CLOSED, "Remote cursor is closed");
      var deadline = deadlines.schedule(() -> closeInput(input), timeout.toNanos(), TimeUnit.NANOSECONDS);
      try {
        var frame = ReadWire.line(input);
        if ("end".equals(ReadWire.nullableText(frame, "kind"))) { ReadWire.requireEnd(frame); complete = true; close(); return new SampleBatch(List.of(), List.of(), 0, 0, true); }
        var batch = ReadWire.batch(frame);
        queryIndex = frame.has("query_index") ? ReadWire.integer(frame, "query_index") : 0;
        entryComplete = frame.has("entry_complete") ? frame.get("entry_complete").getAsBoolean() : batch.complete();
        if (batch.complete()) { ReadWire.requireEnd(ReadWire.line(input)); complete = true; close(); }
        return batch;
      } catch (IOException | RuntimeException failure) {
        try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
        if (failure instanceof IOException io) throw io;
        throw new IOException("Invalid remote read batch", failure);
      } finally { deadline.cancel(false); }
    }
    @Override public void close() throws IOException {
      if (!released.compareAndSet(false, true)) return;
      try { input.close(); } finally { owner.release(this); }
    }
  }
  @Override public void close() throws IOException {
    List<RemoteSnapshot> retiring;
    synchronized (snapshots) { if (closed) return; closed = true; retiring = List.copyOf(snapshots); }
    IOException failure = null;
    for (var snapshot : retiring) try { snapshot.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    finishClose(); if (failure != null) throw failure;
  }
  private void finishClose() {
    synchronized (snapshots) { if (closed && admissions == 0 && snapshots.isEmpty()) deadlines.shutdown(); }
  }
}
