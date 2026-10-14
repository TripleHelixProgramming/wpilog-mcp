/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.RecordedValue;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.store.*;
import org.triplehelix.wpilogmcp.visualization.*;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;

/** The local recorded-preview adapter. Transport authority is checked again by each mutation.
 * Parsing preserves duplicate keys; no generic JSON-RPC parser precedes the contract owner. */
final class VisualizationEndpoint implements AutoCloseable {
  static final String PATH = "/visualizations/v1";
  private final SessionManager sessions;
  private final ServerIdentity identity;
  private final VisualizationService views;
  private final RecordedSources sources;
  private final LogManager logs = LogManager.getInstance();
  private final StoreDoor door = new StoreDoor(() -> logs.getAllowedDirectories());
  VisualizationEndpoint(SessionManager sessions, ServerIdentity identity) throws IOException {
    this.sessions = sessions; this.identity = identity; views = new VisualizationService(identity);
    sources = new RecordedSources(logs, views, door);
  }
  void release(String session) { views.releaseClient(UUID.fromString(session)); }
  @Override public void close() { views.close(); }

  void handle(HttpExchange exchange, Authority authority) throws IOException {
    try {
      authority.requireLocal();
      String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
      if (session == null) { error(exchange, 400, "MISSING_SESSION", "Missing Mcp-Session-Id header"); return; }
      if (sessions.getSession(session) == null) { error(exchange, 404, "SESSION_EXPIRED", "Session expired; reconnect before resuming the view"); return; }
      UUID client = UUID.fromString(session);
      String route = exchange.getRequestURI().getPath().substring(PATH.length());
      boolean get = exchange.getRequestMethod().equals("GET");
      if (!get && !exchange.getRequestMethod().equals("POST")) { error(exchange, 405, "INVALID_REQUEST", "Use GET or POST"); return; }
      var body = new JsonObject();
      if (!get) {
        var bytes = exchange.getRequestBody().readNBytes(VisualizationContracts.MAX_CONFIGURATION_BYTES + 1);
        body = VisualizationContracts.parse(new String(bytes, StandardCharsets.UTF_8));
      }
      JsonObject result;
      if (route.equals("/capabilities") && get) {
        result = identity.describe(); result.addProperty("stage", "recorded-preview"); result.addProperty("client_id", session);
        result.add("blank_configuration", VisualizationContracts.blank()); result.add("schema", VisualizationContracts.schema());
        result.addProperty("max_working_views", VisualizationService.MAX_DOCUMENTS); result.addProperty("draft_lifetime_days", 30);
        result.addProperty("max_plot_points", 4096); result.addProperty("max_visible_series", 32);
        result.addProperty("live", false); result.addProperty("templates", false);
      } else if (route.equals("/stores") && get) result = stores();
      else if (route.equals("/source") && !get) result = sources.identify(authority, Path.of(text(body, "path")));
      else if (route.equals("/source/describe") && !get) result = sources.describe(body.getAsJsonObject("source_ref"));
      else if (route.equals("/source/resolve") && !get) {
        result = new JsonObject(); result.addProperty("path", sources.resolve(body.getAsJsonObject("source_ref")).toString());
      } else if (route.equals("/source/value") && !get) result = value(body);
      else if (route.equals("/source/views") && !get) result = sourceViews(body.getAsJsonObject("source_ref"));
      else if (route.equals("/views") && !get) {
        var source = body.getAsJsonObject("source_ref"); sources.resolve(source);
        result = views.create(authority, uuid(body, "request_id"), source,
            body.has("configuration") ? body.getAsJsonObject("configuration") : VisualizationContracts.blank()).get();
      } else if (route.equals("/saved/open") && !get) result = openSaved(authority, body);
      else if (route.startsWith("/views/")) {
        var parts = route.substring(7).split("/", -1);
        if (parts.length > 2) throw new IllegalArgumentException("Unknown view route");
        UUID view = UUID.fromString(parts[0]); var current = views.get(view);
        String action = parts.length == 1 ? "" : parts[1];
        if (action.isEmpty() && get) result = current;
        else if (action.equals("attach") && !get) {
          // Ordering with removal is atomic, but the callback only enqueues, never waits on I/O.
          var future = new AtomicReference<CompletableFuture<JsonObject>>();
          if (!sessions.update(session, ignored -> future.set(views.attach(authority, view, client, () -> sessions.getSession(session) != null))))
            throw new IOException("SESSION_EXPIRED: session expired before attachment");
          result = future.get().get();
        } else if (action.equals("detach") && !get) result = views.detach(authority, view, client).get();
        else if (action.equals("describe") && !get) result = sources.describe(current.getAsJsonObject("source_ref"));
        else if (action.isEmpty() && !get) result = views.updateAttached(authority, view, revision(body), body.getAsJsonObject("configuration"), client).get();
        else if (action.equals("save") && !get) result = save(authority, client, view, current, body);
        else throw new IllegalArgumentException("Unknown view route or method");
      } else { error(exchange, 404, "NOT_FOUND", "Unknown visualization route"); return; }
      send(exchange, result);
    } catch (Exception failure) {
      Throwable cause = failure;
      while (cause instanceof ExecutionException && cause.getCause() != null) cause = cause.getCause();
      String message = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
      String code = message.matches("^[A-Z_]+:.*") ? message.substring(0, message.indexOf(':')) : "INVALID_REQUEST";
      int status = cause instanceof java.nio.file.AccessDeniedException || cause instanceof org.triplehelix.wpilogmcp.log.LogFileException ? 403
          : code.equals("SESSION_EXPIRED") ? 404 : Set.of("REVISION_CONFLICT", "VIEW_ATTACHED", "SOURCE_UNAVAILABLE", "NO_WRITABLE_STORE", "REQUEST_CONFLICT").contains(code) ? 409
          : cause instanceof RejectedExecutionException ? 503 : 400;
      error(exchange, status, status == 403 ? "ACCESS_DENIED" : code, message);
    }
  }
  private JsonObject stores() throws IOException {
    door.refresh();
    var result = new JsonObject(); var values = new JsonArray();
    for (var store : door.stores()) {
      var value = new JsonObject(); value.addProperty("store_id", store.description().id());
      value.addProperty("label", store.root().getFileName().toString()); value.addProperty("path", store.root().toString());
      value.addProperty("writable", !store.description().mirror() && Files.isWritable(store.root())); values.add(value);
    }
    result.add("stores", values); return result;
  }
  private JsonObject sourceViews(JsonObject source) throws Exception {
    var result = new JsonObject(); var working = new JsonArray(); views.summaries(source).forEach(working::add);
    result.add("working_views", working); var saved = new JsonArray(); var unreadable = new JsonArray();
    boolean incomplete = false;
    for (var store : door.stores()) {
      var listing = VisualizationMetadata.list(store);
      incomplete |= listing.incomplete();
      for (var problem : listing.unreadable()) {
        if (unreadable.size() >= 1024) { incomplete = true; break; }
        var item = new JsonObject(); item.addProperty("store_id", store.description().id());
        item.addProperty("file", problem.file()); item.addProperty("reason", problem.reason()); unreadable.add(item);
      }
      for (var view : listing.views()) if (view.get("source_ref").equals(source)) {
        if (saved.size() >= 1024) { incomplete = true; break; }
        var item = new JsonObject();
        for (var key : List.of("saved_view_id", "revision", "title")) item.add(key, view.get(key).deepCopy());
        item.addProperty("store_id", store.description().id()); saved.add(item);
      }
      if (saved.size() >= 1024 || unreadable.size() >= 1024) { incomplete = true; break; }
    }
    result.add("saved_views", saved); result.add("unreadable", unreadable);
    result.addProperty("listing_incomplete", incomplete); return result;
  }

  private JsonObject save(Authority authority, UUID client, UUID view, JsonObject current, JsonObject request) throws Exception {
    VisualizationService.requireOwner(current, client);
    if (revision(request) != current.get("revision").getAsLong()) throw new IOException("REVISION_CONFLICT: view changed before Save");
    UUID operation = uuid(request, "request_id");
    var previous = current.get("saved_view_ref");
    StoreDoor.Selected selected; UUID savedId; long savedRevision; String title;
    if (previous.isJsonNull()) {
      if (request.has("store_path")) {
        var root = Path.of(text(request, "store_path")); logs.stores().validate(root);
        // Use the same first-mutation store owner as empty import; no ingestion policy is enabled.
        var store = logs.stores().store(root);
        var metadata = new VisualizationMetadata(store);
        var saved = metadata.save(authority, operation, 0, operation, text(request, "title"),
            current.getAsJsonObject("source_ref"), current.getAsJsonObject("configuration")).get();
        door.refresh(); selected = door.stores().stream().filter(s -> s.root().equals(store.root())).findFirst().orElseThrow();
        return checkpoint(authority, client, view, current, selected, saved);
      }
      if (!request.has("store_id")) throw new IOException("NO_WRITABLE_STORE: choose a writable local store or Create store");
      selected = door.select(text(request, "store_id")); savedId = operation; savedRevision = 0; title = text(request, "title");
    } else {
      var ref = previous.getAsJsonObject(); selected = door.select(text(ref, "store_id"));
      savedId = uuid(ref, "saved_view_id"); savedRevision = ref.get("revision").getAsLong();
      title = saved(selected, savedId).get("title").getAsString();
    }
    var saved = new VisualizationMetadata(logs.stores().store(selected.root())).save(authority, savedId, savedRevision, operation, title,
        current.getAsJsonObject("source_ref"), current.getAsJsonObject("configuration")).get();
    return checkpoint(authority, client, view, current, selected, saved);
  }
  private JsonObject checkpoint(Authority authority, UUID client, UUID view, JsonObject current, StoreDoor.Selected store, JsonObject saved) throws Exception {
    var ref = new JsonObject(); ref.addProperty("store_id", store.description().id()); ref.add("saved_view_id", saved.get("saved_view_id")); ref.add("revision", saved.get("revision"));
    return views.checkpointAttached(authority, view, current.get("revision").getAsLong(), ref, client).get();
  }
  private JsonObject openSaved(Authority authority, JsonObject request) throws Exception {
    var store = door.select(text(request, "store_id")); var saved = saved(store, uuid(request, "saved_view_id"));
    sources.resolve(saved.getAsJsonObject("source_ref"));
    var view = views.create(authority, uuid(request, "request_id"), saved.getAsJsonObject("source_ref"), saved.getAsJsonObject("configuration")).get();
    if (!view.get("saved_view_ref").isJsonNull()) return view;
    return checkpoint(authority, null, UUID.fromString(view.get("view_id").getAsString()), view, store, saved);
  }
  private static JsonObject saved(StoreDoor.Selected store, UUID id) throws IOException {
    return VisualizationMetadata.read(store, id);
  }
  private JsonObject value(JsonObject request) throws IOException {
    try (var snapshot = sources.open(request.getAsJsonObject("source_ref"))) {
      Long at = null;
      if (request.has("at_sec")) {
        double seconds = request.get("at_sec").getAsDouble();
        if (!Double.isFinite(seconds) || Math.abs(seconds) > 9_007_199_254.0) throw new IllegalArgumentException("Cursor time is outside the exact microsecond range");
        at = Math.round(seconds * 1e6);
      }
      var result = RecordedValue.at(snapshot, text(request, "entry"), request.has("field") ? text(request, "field") : null, at);
      var out = new JsonObject(); var sample = result.sample();
      out.add("value", sample == null ? JsonNull.INSTANCE : ReadWire.value(sample.value()));
      if (sample == null) out.add("timestamp_us", JsonNull.INSTANCE); else out.addProperty("timestamp_us", Long.toString(sample.timestampUs()));
      out.addProperty("basis", "last_recorded_sample_at_or_before_cursor"); out.add("problems", StoreJson.JSON.toJsonTree(result.problems()));
      out.addProperty("problem_count", result.problemCount()); out.addProperty("problems_truncated", result.problemCount() > result.problems().size());
      sources.resolve(request.getAsJsonObject("source_ref"));
      return out;
    }
  }
  private static long revision(JsonObject body) {
    double value = body.get("revision").getAsDouble();
    if (value < 1 || value > 9_007_199_254_740_991L || value != Math.rint(value)) throw new IllegalArgumentException("Invalid revision");
    return (long) value;
  }
  private static UUID uuid(JsonObject body, String key) { return UUID.fromString(text(body, key)); }
  private static String text(JsonObject body, String key) { return ReadWire.text(body, key); }
  private static void send(HttpExchange exchange, JsonObject result) throws IOException {
    var bytes = ReadWire.bytes(result, 16 << 20);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(200, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
  }
  private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
    LocalIngestionEndpoint.error(exchange, status, code, message);
  }
}
