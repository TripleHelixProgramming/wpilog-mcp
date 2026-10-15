/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.catalog.RecordingReference;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.read.server.OrdinalFollow;

/** Read-only, epoch-bound follow leases. Long polls run on their own bounded executor in the
 * transport; creation and DELETE retain separate capacity. Locks order admission only, never
 * prefix acquisition, decoding, waits, response writes or disposal. */
final class FollowEndpoint implements AutoCloseable {
  static final String PATH = ReadEndpoint.PATH + "/follows";
  @FunctionalInterface interface Sources { OrdinalFollow.Source open(RecordingReference reference) throws IOException; }
  private static final class Lease {
    final OrdinalFollow engine;
    long used;
    int active;
    Lease(OrdinalFollow engine, long used) { this.engine = engine; this.used = used; }
  }
  private final ReadEndpoint reader;
  private final Sources sources;
  private final String epoch = UUID.randomUUID().toString();
  private final Map<String, Lease> leases = new HashMap<>();
  private final OrdinalFollow.Budget budget = new OrdinalFollow.Budget(64L << 20);
  private final LongSupplier clock;
  private final long idleNanos;
  private final int capacity;
  private final java.util.concurrent.ScheduledExecutorService timer;
  private int opening;
  private boolean closed;

  FollowEndpoint(ReadEndpoint reader, Sources sources) { this(reader, sources, 32, Duration.ofSeconds(60), System::nanoTime); }
  FollowEndpoint(ReadEndpoint reader, Sources sources, int capacity, Duration idle, LongSupplier clock) {
    if (capacity < 1 || idle.isNegative() || idle.isZero()) throw new IllegalArgumentException("Positive follow lease limits required");
    this.reader = reader; this.sources = sources; this.capacity = capacity; this.idleNanos = idle.toNanos(); this.clock = clock;
    timer = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "follow-lease-expiry"); t.setDaemon(true); return t; });
    timer.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
  }
  void handle(HttpExchange exchange) throws IOException {
    try {
      expire();
      synchronized (leases) { if (closed) throw new Refusal(503, "CLOSED", "Follow admission is closed"); }
      String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
      if (path.equals(PATH)) {
        require(method, "POST"); create(exchange, reader.body(exchange)); return;
      }
      if (!path.startsWith(PATH + "/")) throw new Refusal(404, "NOT_FOUND", "Unknown follow route");
      String[] parts = path.substring(PATH.length() + 1).split("/", -1);
      if (parts.length == 1 && !parts[0].isBlank()) {
        require(method, "DELETE"); Lease removed;
        synchronized (leases) { removed = leases.remove(parts[0]); }
        if (removed != null) removed.engine.close();
        exchange.sendResponseHeaders(204, -1); return;
      }
      if (parts.length != 2 || !parts[1].equals("next")) throw new Refusal(404, "NOT_FOUND", "Unknown follow route");
      require(method, "POST"); var body = reader.body(exchange);
      int wait = body.has("wait_ms") ? ReadWire.integer(body, "wait_ms") : 25000;
      if (wait < 0 || wait > 25000) throw new IllegalArgumentException("wait_ms must be between 0 and 25000");
      String cursor = ReadWire.text(body, "cursor"); Lease lease;
      synchronized (leases) {
        lease = leases.get(parts[0]);
        if (lease == null) throw new Refusal(410, "CURSOR_EXPIRED", "Follow expired, closed or belongs to another server epoch; explicitly rebootstrap the same recording");
        lease.active++;
      }
      try {
        var page = lease.engine.next(cursor, Duration.ofMillis(wait));
        if (page.isEmpty()) { exchange.sendResponseHeaders(204, -1); return; }
        try (var frame = page.get()) {
          exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
          exchange.getResponseHeaders().set("Cache-Control", "no-store");
          exchange.sendResponseHeaders(200, frame.payload().remaining());
          try (var out = exchange.getResponseBody()) { frame.writeTo(out); }
        }
      } finally { synchronized (leases) { lease.active--; lease.used = clock.getAsLong(); } }
    } catch (Refusal e) { ReadEndpoint.sendFailure(exchange, e.status, e.code, e.getMessage()); }
    catch (ReadException e) { ReadEndpoint.sendFailure(exchange, ReadEndpoint.status(e.code()),
        e.code() == org.triplehelix.wpilogmcp.read.ReadProblem.Code.FILE_CHANGED ? "HISTORY_UNAVAILABLE" : e.code().name(), e.getMessage(), e.retryable()); }
    catch (java.nio.file.AccessDeniedException | org.triplehelix.wpilogmcp.log.LogFileException e) { ReadEndpoint.sendFailure(exchange, 403, "ACCESS_DENIED", e.getMessage()); }
    catch (ReadWire.WireLimit e) { ReadEndpoint.sendFailure(exchange, 413, "RESOURCE_LIMIT", e.getMessage()); }
    catch (IllegalArgumentException e) { ReadEndpoint.sendFailure(exchange, 400, "INVALID_REQUEST", e.getMessage()); }
    catch (IOException e) {
      String code = e.getMessage() != null && e.getMessage().startsWith("HISTORY_UNAVAILABLE:") ? "HISTORY_UNAVAILABLE" : "SOURCE_UNAVAILABLE";
      ReadEndpoint.sendFailure(exchange, 409, code, e.getMessage());
    }
  }
  private void create(HttpExchange exchange, JsonObject body) throws IOException {
    if (!body.has("source_ref") || !body.get("source_ref").isJsonObject()) throw new IllegalArgumentException("source_ref must be an object");
    var ref = org.triplehelix.wpilogmcp.visualization.VisualizationContracts.source(body.getAsJsonObject("source_ref"));
    if (!ReadWire.text(ref, "kind").equals("session")) throw new IllegalArgumentException("A follow requires a captured session source_ref");
    var anchor = ref.getAsJsonObject("anchor_member");
    var hashes = new ArrayList<String>();
    if (ref.has("finalized_members")) for (var member : ref.getAsJsonArray("finalized_members")) hashes.add(ReadWire.text(member.getAsJsonObject(), "content_sha256"));
    var reference = new RecordingReference(ReadWire.text(ref, "origin_store_id"), ReadWire.text(ref, "session_id"),
        ReadWire.text(anchor, "original_name"), ReadWire.text(anchor, "recording_started_at"), hashes);
    var queries = new ArrayList<ReadQuery>();
    if (!body.has("queries") || !body.get("queries").isJsonArray()) throw new IllegalArgumentException("queries must be an array");
    for (var query : body.getAsJsonArray("queries")) {
      if (!query.isJsonObject()) throw new IllegalArgumentException("Each query must be an object");
      try { queries.add(ReadWire.query(query.getAsJsonObject())); }
      catch (ClassCastException | IllegalStateException e) { throw new IllegalArgumentException("Query fields have an invalid JSON shape", e); }
    }
    int records = body.has("max_records") ? ReadWire.integer(body, "max_records") : OrdinalFollow.MAX_RECORDS;
    int bytes = body.has("max_page_bytes") ? ReadWire.integer(body, "max_page_bytes") : OrdinalFollow.MAX_PAGE_BYTES;
    synchronized (leases) {
      if (leases.size() + opening >= capacity) throw new Refusal(429, "RESOURCE_LIMIT", "Close another follow before opening a new one");
      opening++;
    }
    OrdinalFollow engine = null; String id = null; boolean published = false;
    try {
      engine = new OrdinalFollow(epoch, sources.open(reference), queries, budget, records, bytes);
      id = engine.id();
      synchronized (leases) {
        if (closed) throw new Refusal(503, "CLOSED", "Follow admission closed while opening");
        leases.put(id, new Lease(engine, clock.getAsLong())); published = true;
      }
      var response = new JsonObject(); response.addProperty("api_version", ReadWire.VERSION);
      response.addProperty("id", id); response.addProperty("server_epoch", epoch); response.addProperty("cursor", engine.initialCursor());
      response.addProperty("idle_timeout_sec", idleNanos / 1_000_000_000.0);
      response.addProperty("max_wait_ms", 25000); response.add("source_ref", ref);
      try { ReadEndpoint.send(exchange, 201, response); }
      catch (IOException failure) { synchronized (leases) { leases.remove(id); } published = false; throw failure; }
    } finally {
      synchronized (leases) { opening--; }
      if (!published && engine != null) engine.close();
    }
  }
  private static void require(String actual, String expected) throws Refusal {
    if (!actual.equals(expected)) throw new Refusal(405, "METHOD_NOT_ALLOWED", "This follow route requires " + expected);
  }
  private static final class Refusal extends IOException {
    final int status; final String code;
    Refusal(int status, String code, String message) { super(message); this.status = status; this.code = code; }
  }
  void expire() {
    var expired = new ArrayList<Lease>(); long now = clock.getAsLong();
    synchronized (leases) {
      leases.values().removeIf(lease -> { if (lease.active == 0 && now - lease.used >= idleNanos) { expired.add(lease); return true; } return false; });
    }
    expired.forEach(FollowEndpoint::release);
  }
  int size() { synchronized (leases) { return leases.size(); } }
  long heldBytes() { return budget.heldBytes(); }
  @Override public void close() {
    List<Lease> retiring;
    synchronized (leases) { if (closed) return; closed = true; retiring = List.copyOf(leases.values()); leases.clear(); }
    timer.shutdownNow(); retiring.forEach(FollowEndpoint::release);
  }
  private static void release(Lease lease) {
    try { lease.engine.close(); }
    catch (IOException e) { org.slf4j.LoggerFactory.getLogger(FollowEndpoint.class).warn("Could not close follow: {}", e.getMessage()); }
  }
}
