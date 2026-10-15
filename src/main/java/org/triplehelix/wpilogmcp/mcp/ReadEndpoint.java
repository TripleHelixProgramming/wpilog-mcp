/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.triplehelix.wpilogmcp.data.ArrowStreamWriter;
import org.triplehelix.wpilogmcp.data.ArrowType;
import org.triplehelix.wpilogmcp.data.ColumnBuilder;
import org.triplehelix.wpilogmcp.data.ReadArrowTypes;
import org.triplehelix.wpilogmcp.read.LogAccess;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.ReadSnapshot;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.SampleBatch;
import org.triplehelix.wpilogmcp.read.SampleCursor;
import org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.store.StoreCatalog;

/** Additive native read routes; the established MCP and data endpoints keep their wire contracts. */
final class ReadEndpoint implements AutoCloseable {
  static final String PATH = "/read/v1";
  @FunctionalInterface interface Catalogs { StoreReadCatalog open() throws IOException; }
  private static final int MAX_REQUEST_BYTES = 64 << 10;
  private final LogAccess access;
  private final Catalogs catalogs;
  private final int maxSnapshots;
  private final long idleNanos;
  private final LongSupplier clock;
  private final Map<String, Lease> leases = new HashMap<>();
  private final ScheduledExecutorService timer;
  private int opening;
  private boolean closed;
  private final Object catalogLock = new Object();
  private CatalogUse currentCatalog;
  private boolean catalogsClosed;
  private static final class CatalogUse {
    final StoreReadCatalog value;
    int users;
    boolean retired;
    CatalogUse(StoreReadCatalog value) { this.value = value; }
  }
  final class CatalogLease implements org.triplehelix.wpilogmcp.read.server.CatalogRecordingSource.Lease {
    private final CatalogUse use;
    private boolean released;
    CatalogLease(CatalogUse use) { this.use = use; }
    public StoreReadCatalog value() { return use == null ? null : use.value; }
    @Override public void close() throws IOException {
      if (use == null) return;
      boolean release;
      synchronized (catalogLock) {
        if (released) return;
        released = true; release = --use.users == 0 && use.retired;
      }
      if (release) use.value.close();
    }
  }
  CatalogLease catalog() throws IOException {
    // Consult current configured roots on every admission, but retain the catalog/decode owner.
    // Factory construction does no inventory IO. A changed lease retires its previous roots.
    var candidate = catalogs.open();
    StoreReadCatalog discard = null, retire = null;
    CatalogUse admitted;
    synchronized (catalogLock) {
      if (catalogsClosed) { discard = candidate; admitted = null; }
      else {
        if (currentCatalog != null && (candidate == null || !currentCatalog.value.roots().equals(candidate.roots()))) {
          currentCatalog.retired = true;
          if (currentCatalog.users == 0) retire = currentCatalog.value;
          currentCatalog = null;
        }
        if (candidate != null) {
          if (currentCatalog == null) currentCatalog = new CatalogUse(candidate);
          else if (candidate != currentCatalog.value) discard = candidate;
          currentCatalog.users++;
        }
        admitted = currentCatalog;
      }
    }
    if (discard != null) discard.close();
    if (retire != null) retire.close();
    return new CatalogLease(admitted);
  }
  private static final class Lease {
    final ReadSnapshot snapshot;
    final JsonObject descriptor;
    long used;
    int active;
    boolean retired;
    Lease(ReadSnapshot snapshot, JsonObject descriptor, long used) { this.snapshot = snapshot; this.descriptor = descriptor; this.used = used; }
  }
  private java.util.function.Supplier<String> preferredStore = () -> null;
  void preferredStore(java.util.function.Supplier<String> supplier) { preferredStore = supplier; }

  ReadEndpoint(LogAccess access) { this(access, () -> null); }
  ReadEndpoint(LogAccess access, Catalogs catalogs) { this(access, catalogs, 32, Duration.ofSeconds(60).toNanos(), System::nanoTime); }
  ReadEndpoint(LogAccess access, int maxSnapshots, long idleNanos, LongSupplier clock) { this(access, () -> null, maxSnapshots, idleNanos, clock); }
  ReadEndpoint(LogAccess access, Catalogs catalogs, int maxSnapshots, long idleNanos, LongSupplier clock) {
    if (maxSnapshots < 1 || idleNanos < 1) throw new IllegalArgumentException("Read lease limits must be positive");
    this.access = access; this.catalogs = catalogs; this.maxSnapshots = maxSnapshots; this.idleNanos = idleNanos; this.clock = clock;
    timer = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "read-lease-expiry"); t.setDaemon(true); return t; });
    timer.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
  }

  void handle(HttpExchange exchange) throws IOException {
    try {
      expire();
      synchronized (leases) { if (closed) throw new Refusal(503, "CLOSED", "Read admission is closed"); }
      String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
      if (path.equals(PATH + "/catalog")) {
        requireMethod(method, "GET");
        try (var catalogLease = catalog()) {
          var catalog = catalogLease.value();
          // Serialize the existing catalog directly into the bounded writer, rather than first
          // allocating an unbounded second Gson tree for all of a season's manifest records.
          var response = new java.util.LinkedHashMap<String, Object>();
          response.put("api_version", ReadWire.VERSION);
          response.put("catalog", catalog == null ? new StoreReadCatalog.Snapshot(List.of(), List.of()) : catalog.snapshot());
          var preferred = preferredStore.get();
          if (preferred != null) response.put("preferred_store_id", preferred);
          send(exchange, 200, response);
        }
      } else if (path.equals(PATH + "/rev/query") || path.equals(PATH + "/rev/describe")) {
        requireMethod(method, "POST");
        try (var catalogLease = catalog()) {
          var catalog = catalogLease.value();
          if (catalog == null) throw new Refusal(404, "NOT_FOUND", "No store is configured");
          var request = body(exchange);
          if (path.endsWith("/describe")) RevReadEndpoint.describe(exchange, catalog, request);
          else RevReadEndpoint.handle(exchange, catalog, request);
        }
      } else if (path.equals(PATH + "/snapshots")) {
        requireMethod(method, "POST"); open(exchange, body(exchange));
      } else if (path.startsWith(PATH + "/snapshots/")) {
        String[] parts = path.substring((PATH + "/snapshots/").length()).split("/", -1);
        if (parts.length < 1 || parts[0].isEmpty() || parts.length > 2 || parts.length == 2 && !parts[1].equals("query"))
          throw new Refusal(404, "NOT_FOUND", "Unknown read route");
        if (parts.length == 1 && method.equals("DELETE")) {
          Lease lease; synchronized (leases) { lease = leases.remove(parts[0]); }
          if (lease != null) retire(lease);
          exchange.sendResponseHeaders(204, -1); exchange.close(); return;
        }
        var lease = lease(parts[0]);
        try {
          if (parts.length == 1) { requireMethod(method, "GET"); send(exchange, 200, lease.descriptor); }
          else { requireMethod(method, "POST"); query(exchange, lease, body(exchange)); }
        } finally { finished(lease); }
      } else throw new Refusal(404, "NOT_FOUND", "Unknown read route");
    } catch (Refusal failure) { sendFailure(exchange, failure.status, failure.code, failure.getMessage()); }
    catch (ReadException failure) { sendFailure(exchange, status(failure.code()), failure.code().name(), failure.getMessage()); }
    catch (java.nio.file.AccessDeniedException | org.triplehelix.wpilogmcp.log.LogFileException failure) {
      sendFailure(exchange, 403, "ACCESS_DENIED", failure.getMessage());
    } catch (ReadWire.WireLimit failure) { sendFailure(exchange, 413, "RESOURCE_LIMIT", failure.getMessage()); }
    catch (IllegalArgumentException failure) { sendFailure(exchange, 400, "INVALID_REQUEST", failure.getMessage()); }
    catch (IOException failure) { sendFailure(exchange, 400, "READ_FAILED", failure.getMessage()); }
  }

  private void open(HttpExchange exchange, JsonObject request) throws IOException {
    synchronized (leases) {
      if (leases.size() + opening >= maxSnapshots) throw new Refusal(429, "RESOURCE_LIMIT", "Too many retained read snapshots; close one first");
      opening++;
    }
    ReadSnapshot snapshot = null;
    try (var catalogLease = catalog()) {
      var catalog = catalogLease.value();
      Path path; StoreReadCatalog.StoreFile facts = null;
      if (request.has("path")) {
        if (request.has("store_id")) throw new IllegalArgumentException("Choose path or store identity, not both");
        path = Path.of(ReadWire.text(request, "path"));
        // Preserve missing durable aliases, but a real symlink's target decides store membership.
        // An alias from a loose directory must not turn a store stray into an ordinary file.
        Path canonical = java.nio.file.Files.exists(path) ? path.toRealPath() : path;
        if (StoreCatalog.containing(path).isPresent() || StoreCatalog.containing(canonical).isPresent()) {
          if (catalog == null) throw new Refusal(403, "ACCESS_DENIED", "The source is not in a configured store catalog");
          facts = catalog.resolve(StoreCatalog.containing(path).isPresent() ? path : canonical)
              .orElseThrow(() -> new Refusal(403, "ACCESS_DENIED", "The source is not a manifested store file"));
          path = facts.facts().path();
        }
      } else if (request.has("anchor_path")) {
        if (catalog == null) throw new Refusal(404, "NOT_FOUND", "No store is configured");
        var policy = org.triplehelix.wpilogmcp.read.catalog.SessionSnapshot.Overlap.valueOf(
            request.has("overlap") ? ReadWire.text(request, "overlap").toUpperCase(java.util.Locale.ROOT) : "REJECT");
        snapshot = catalog.openSession(ReadWire.text(request, "store_id"), ReadWire.text(request, "session_id"), ReadWire.text(request, "anchor_path"), policy);
        path = snapshot.path();
      } else {
        if (catalog == null) throw new Refusal(404, "NOT_FOUND", "No store is configured");
        String storeId = ReadWire.text(request, "store_id"), session = ReadWire.text(request, "session_id"), file = ReadWire.text(request, "file_path");
        var candidates = new ArrayList<StoreReadCatalog.StoreFile>();
        for (var store : catalog.snapshot().stores()) if (store.header().id().equals(storeId))
          for (var item : store.allFiles()) if (item.session() != null && item.session().names(session) && item.file().path().equals(file))
            candidates.add(new StoreReadCatalog.StoreFile(storeId, store.root(), item));
        if (candidates.size() != 1) throw new Refusal(404, "NOT_FOUND", "Store/session/file identity does not name one manifested file");
        facts = candidates.get(0); path = facts.facts().path();
      }
      if (snapshot == null) snapshot = access.open(path);
      String id = UUID.randomUUID().toString(); var descriptor = ReadWire.descriptor(id, snapshot);
      descriptor.addProperty("idle_timeout_sec", idleNanos / 1_000_000_000.0);
      if (facts != null) descriptor.add("store", ReadWire.parse(ReadWire.bytes(facts, ReadWire.MAX_FRAME_BYTES)));
      var lease = new Lease(snapshot, descriptor, clock.getAsLong());
      synchronized (leases) {
        if (closed) throw new Refusal(503, "CLOSED", "Read admission closed during open");
        leases.put(id, lease);
      }
      snapshot = null;
      try { send(exchange, 201, descriptor); }
      catch (IOException | RuntimeException failure) {
        synchronized (leases) { leases.remove(id); }
        lease.snapshot.close(); throw failure;
      }
    } finally {
      synchronized (leases) { opening--; }
      if (snapshot != null) snapshot.close();
    }
  }

  private Lease lease(String id) throws Refusal {
    synchronized (leases) {
      var lease = leases.get(id);
      if (lease == null) throw new Refusal(410, "CLOSED", "Read snapshot expired or was closed; reopen it");
      lease.active++; lease.used = clock.getAsLong(); return lease;
    }
  }
  private void query(HttpExchange exchange, Lease lease, JsonObject request) throws IOException {
    if (request.has("queries")) { multiQuery(exchange, lease, request); return; }
    var query = ReadWire.query(request);
    if (!lease.snapshot.entries().containsKey(query.entry())) throw new Refusal(404, "NOT_FOUND", "No entry named '" + query.entry() + "'");
    String format = request.has("format") ? ReadWire.text(request, "format") : "ndjson";
    if (!format.equals("ndjson") && !format.equals("arrow")) throw new IllegalArgumentException("format must be ndjson or arrow");
    try (var cursor = lease.snapshot.read(query)) {
      var first = cursor.next(); // Admission and first decode failures still have an HTTP status.
      if (format.equals("arrow")) arrow(exchange, lease, query, cursor, first);
      else {
        var frames = ReadWire.wireBatches(first);
        byte[] initial = ReadWire.bytes(ReadWire.batch(frames.next()), ReadWire.MAX_FRAME_BYTES);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        try (var out = exchange.getResponseBody()) {
          out.write(initial); out.write('\n'); out.flush();
          try {
            while (frames.hasNext()) line(out, ReadWire.batch(frames.next()));
            var batch = first;
            while (!batch.complete()) {
              batch = cursor.next(); frames = ReadWire.wireBatches(batch);
              while (frames.hasNext()) line(out, ReadWire.batch(frames.next()));
            }
            line(out, ReadWire.end(null));
          } catch (IOException | RuntimeException failure) { line(out, ReadWire.end(explained(failure))); }
        }
      }
    }
  }

  private void multiQuery(HttpExchange exchange, Lease lease, JsonObject request) throws IOException {
    if (!request.get("queries").isJsonArray()) throw new IllegalArgumentException("queries must be an array");
    var queries = new ArrayList<ReadQuery>();
    for (var item : request.getAsJsonArray("queries")) queries.add(ReadWire.query(item.getAsJsonObject()));
    org.triplehelix.wpilogmcp.read.MultiSampleCursor.validate(queries);
    String format = request.has("format") ? ReadWire.text(request, "format") : "ndjson";
    if (!format.equals("ndjson") && !format.equals("arrow")) throw new IllegalArgumentException("format must be ndjson or arrow");
    if (format.equals("arrow")) { multiArrow(exchange, lease, queries); return; }
    try (var cursor = lease.snapshot.readAll(queries)) {
      var batch = cursor.next();
      exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
      exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(200, 0);
      try (var out = exchange.getResponseBody()) {
        try {
          while (true) {
            var parts = ReadWire.wireBatches(batch.batch());
            while (parts.hasNext()) {
              var part = parts.next(); var frame = ReadWire.batch(part);
              frame.addProperty("query_index", batch.queryIndex()); frame.addProperty("entry", batch.entry());
              frame.addProperty("entry_complete", part.complete());
              frame.addProperty("complete", part.complete() && batch.complete()); line(out, frame);
            }
            if (batch.complete()) break;
            batch = cursor.next();
          }
          line(out, ReadWire.end(null));
        } catch (IOException | RuntimeException failure) { line(out, ReadWire.end(explained(failure))); }
      }
    }
  }

  private void arrow(HttpExchange exchange, Lease lease, ReadQuery query, SampleCursor cursor, SampleBatch first) throws IOException {
    var fields = arrowFields(lease, query);
    exchange.getResponseHeaders().set("Content-Type", DataEndpoint.ARROW_TYPE);
    exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(200, 0);
    try (var out = exchange.getResponseBody()) {
      writeArrow(out, lease, query, fields, cursor, first);
    }
  }
  private List<ArrowType.Field> arrowFields(Lease lease, ReadQuery query) throws IOException {
    String entry = query.entry();
    ArrowType type;
    try { type = ReadArrowTypes.queryType(lease.snapshot.entries().get(entry).type(), lease.snapshot.schemas(), query, 64 << 20); }
    catch (IllegalArgumentException failure) {
      throw new ReadException(ReadProblem.Code.SCHEMA_UNAVAILABLE,
          "Arrow cannot declare this entry's schema: " + failure.getMessage() + "; use NDJSON for decode diagnostics", failure);
    }
    var fields = new ArrayList<>(List.of(new ArrowType.Field("timestamp_us", new ArrowType.Int64(), false),
        new ArrowType.Field("record_offset", new ArrowType.Int64(), false), new ArrowType.Field("ordinal", new ArrowType.Int64(), false),
        new ArrowType.Field("value", type, true)));
    if (!lease.snapshot.sources().isEmpty()) {
      fields.add(new ArrowType.Field("source_path", new ArrowType.Utf8(), false));
      fields.add(new ArrowType.Field("source_timestamp_us", new ArrowType.Int64(), false));
    }
    return List.copyOf(fields);
  }
  private boolean writeArrow(OutputStream out, Lease lease, ReadQuery query, List<ArrowType.Field> fields,
      SampleCursor cursor, SampleBatch first) throws IOException {
      var writer = new ArrowStreamWriter(out, fields); var columns = fields.stream().map(f -> ColumnBuilder.of(f.type())).toList();
      writer.writeSchema(Map.of("api_version", "1", "snapshot_id", lease.descriptor.get("id").getAsString(), "entry", query.entry()));
      boolean success = true;
      try {
        var batch = first;
        while (true) {
          for (var sample : batch.samples()) {
            columns.get(0).append(sample.timestampUs()); columns.get(1).append(sample.recordOffset());
            columns.get(2).append((long) sample.ordinal()); columns.get(3).append(sample.value());
            if (columns.size() > 4) { columns.get(4).append(sample.source().path()); columns.get(5).append(sample.source().timestampUs()); }
          }
          writer.writeBatch(columns, Map.of("problems", ReadWire.JSON.toJson(batch.problems()),
              "records_visited", Integer.toString(batch.recordsVisited()), "payload_bytes", Long.toString(batch.payloadBytes())));
          if (batch.complete()) break;
          batch = cursor.next();
        }
        writer.writeBatch(columns, Map.of("status", "ok"));
      } catch (IOException | RuntimeException failure) {
        success = false;
        columns.forEach(ColumnBuilder::reset);
        var reason = explained(failure); writer.writeBatch(columns, Map.of("status", "error", "code", reason.code().name(), "message", reason.getMessage()));
      }
      writer.finish(); return success;
  }
  private void multiArrow(HttpExchange exchange, Lease lease, List<ReadQuery> queries) throws IOException {
    var shapes = new ArrayList<List<ArrowType.Field>>();
    for (var query : queries) shapes.add(arrowFields(lease, query));
    try (var cursor = lease.snapshot.readAll(queries)) {
      var first = cursor.next();
      String boundary = "wpilog-" + UUID.randomUUID();
      exchange.getResponseHeaders().set("Content-Type", "multipart/mixed; boundary=" + boundary);
      exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(200, 0);
      try (var out = exchange.getResponseBody()) {
        for (int i = 0; i < queries.size(); i++) {
          out.write(("--" + boundary + "\r\nContent-Type: " + DataEndpoint.ARROW_TYPE
              + "\r\nX-Query-Index: " + i + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          var child = new SampleCursor() {
            public SampleBatch next() throws IOException { return cursor.next().batch(); }
            public void close() {}
          };
          if (!writeArrow(out, lease, queries.get(i), shapes.get(i), child, first.batch())) return;
          out.write(new byte[]{'\r', '\n'});
          if (i + 1 < queries.size()) first = cursor.next();
        }
        out.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
    }
  }

  JsonObject body(HttpExchange exchange) throws IOException {
    var timeout = timer.schedule(exchange::close, 10, TimeUnit.SECONDS);
    try (var input = exchange.getRequestBody()) {
      byte[] bytes = RequestBodies.read(exchange, MAX_REQUEST_BYTES);
      if (bytes.length > MAX_REQUEST_BYTES) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Read request exceeds " + MAX_REQUEST_BYTES + " bytes");
      try { return ReadWire.parse(bytes); }
      catch (IOException invalid) { throw new IllegalArgumentException("Invalid read JSON", invalid); }
    } finally { timeout.cancel(false); }
  }
  private static void requireMethod(String actual, String expected) throws Refusal {
    if (!actual.equals(expected)) throw new Refusal(405, "METHOD_NOT_ALLOWED", "This read route requires " + expected);
  }
  private static void line(OutputStream out, JsonObject object) throws IOException {
    out.write(ReadWire.bytes(object, ReadWire.MAX_FRAME_BYTES)); out.write('\n'); out.flush();
  }
  static void send(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = ReadWire.bytes(body, ReadWire.MAX_FRAME_BYTES);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) { out.write(bytes); }
  }
  static void sendFailure(HttpExchange exchange, int status, String code, String message) throws IOException {
    sendFailure(exchange, status, code, message, false);
  }
  static void sendFailure(HttpExchange exchange, int status, String code, String message, boolean retryable) throws IOException {
    if (exchange.getResponseCode() >= 0) { exchange.close(); return; }
    var body = new JsonObject(); body.addProperty("status", "error"); body.addProperty("code", code); body.addProperty("message", message);
    if (status == 413) body.addProperty("retryable", retryable);
    send(exchange, status, body);
  }
  private static ReadException explained(Exception failure) {
    if (failure instanceof ReadWire.WireLimit) return new ReadException(ReadProblem.Code.RESOURCE_LIMIT, failure.getMessage(), failure);
    return failure instanceof ReadException read ? read : new ReadException(ReadProblem.Code.MALFORMED_RECORD, failure.getMessage(), failure);
  }
  static int status(ReadProblem.Code code) {
    return switch (code) { case ACCESS_DENIED -> 403; case FILE_CHANGED -> 409; case CLOSED, FOLLOW_ENDED -> 410; case BUSY -> 503; case RESOURCE_LIMIT -> 413; default -> 400; };
  }
  private static final class Refusal extends IOException {
    final int status; final String code;
    Refusal(int status, String code, String message) { super(message); this.status = status; this.code = code; }
  }
  private void expire() {
    var expired = new ArrayList<Lease>(); long now = clock.getAsLong();
    synchronized (leases) {
      leases.values().removeIf(lease -> { if (lease.active == 0 && now - lease.used >= idleNanos) { expired.add(lease); return true; } return false; });
    }
    expired.forEach(ReadEndpoint::release);
  }
  private void retire(Lease lease) {
    boolean close;
    synchronized (leases) { lease.retired = true; close = lease.active == 0; }
    if (close) release(lease);
  }
  private void finished(Lease lease) {
    boolean close;
    synchronized (leases) {
      lease.used = clock.getAsLong(); close = --lease.active == 0 && lease.retired;
    }
    if (close) release(lease);
  }
  private static void release(Lease lease) {
    try { lease.snapshot.close(); }
    catch (IOException failure) { org.slf4j.LoggerFactory.getLogger(ReadEndpoint.class).warn("Could not close a read snapshot: {}", failure.getMessage()); }
  }
  @Override public void close() {
    List<Lease> retiring;
    synchronized (leases) { if (closed) return; closed = true; retiring = List.copyOf(leases.values()); leases.clear(); }
    timer.shutdownNow(); retiring.forEach(this::retire);
    StoreReadCatalog retire = null;
    synchronized (catalogLock) {
      catalogsClosed = true;
      if (currentCatalog != null) {
        currentCatalog.retired = true;
        if (currentCatalog.users == 0) retire = currentCatalog.value;
        currentCatalog = null;
      }
    }
    if (retire != null) try { retire.close(); }
    catch (IOException failure) { org.slf4j.LoggerFactory.getLogger(ReadEndpoint.class).warn("Could not close read catalog: {}", failure.getMessage()); }
  }
}
