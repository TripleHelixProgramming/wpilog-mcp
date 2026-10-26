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
import static org.triplehelix.wpilogmcp.visualization.VisualizationWorkspace.*;

/** The local recorded-preview adapter. Transport authority is checked again by each mutation.
 * Parsing preserves duplicate keys; no generic JSON-RPC parser precedes the contract owner. */
final class VisualizationEndpoint implements AutoCloseable {
  static final String PATH = "/visualizations/v1";
  static final String PATH_V2 = "/visualizations/v2";
  private final SessionManager sessions;
  private final ServerIdentity identity;
  private final VisualizationService views;
  private final RecordedSources sources;
  private final VisualizationWorkspace workspace;
  VisualizationEndpoint(SessionManager sessions, ToolRegistry registry) throws IOException {
    this.sessions = sessions; this.identity = registry.serverIdentity(); workspace = registry.visualizations();
    views = workspace.views; sources = workspace.sources;
  }
  /** A window pings every 30 seconds. One silent for four pings has most likely closed or reloaded
   * without ending its session, and another window may take its views: before this, a lost session
   * ending kept them for the hour the session lives. */
  static final java.time.Duration SILENT_WINDOW = java.time.Duration.ofMinutes(2);
  /** The time silence is measured against; tests move it. */
  volatile java.util.function.Supplier<java.time.Instant> now = java.time.Instant::now;
  private boolean silent(UUID client) {
    // Without touching the session: asking about a window must not keep it alive.
    return sessions.lastActivity(client.toString()).map(last -> !last.plus(SILENT_WINDOW).isAfter(now.get())).orElse(true);
  }
  void release(String session) { workspace.windows.release(session); views.releaseClient(UUID.fromString(session)); workspace.notifications.release(session); workspace.inventories.releaseSession(session); }
  ControlNotifications notifications() { return workspace.notifications; }
  @Override public void close() { workspace.close(); }

  void handle(HttpExchange exchange, Authority authority) throws IOException {
    handle(exchange, authority, true);
  }
  void handle(HttpExchange exchange, Authority authority, boolean inventoryAdmitted) throws IOException {
    try (var responseScope = InventoryResponses.open()) {
      var reply = SettledReply.current(); if (reply != null) reply.onLastWrite(responseScope::settle);
      String route = exchange.getRequestURI().getPath().substring(PATH.length());
      int version = exchange.getRequestURI().getPath().startsWith(PATH_V2) ? 2 : 1;
      VisualizationAccess.transport(authority, version, VisualizationAccess.http(route));
      String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
      if (session == null) { error(exchange, 400, "MISSING_SESSION", "Missing Mcp-Session-Id header"); return; }
      var owner = sessions.getSession(session);
      if (owner == null) { error(exchange, 404, "SESSION_EXPIRED", "Session expired; reconnect before resuming the view"); return; }
      UUID client = UUID.fromString(session);
      var inventoryClient = new RecordingInventories.Client(authority, session, owner::active);
      boolean get = exchange.getRequestMethod().equals("GET");
      if (!get && !exchange.getRequestMethod().equals("POST")) { error(exchange, 405, "INVALID_REQUEST", "Use GET or POST"); return; }
      var body = new JsonObject();
      if (!get) {
        boolean inventory = route.equals("/source/scopes") || route.equals("/source/describe") || route.equals("/source/health") || route.equals("/source/reduction") || route.startsWith("/source/entries/") || route.equals("/source/inventory/close");
        int maximum = LibraryRequests.http(exchange.getRequestURI().getPath()) ? LibraryRequests.requestBytes(route) : inventory ? 64 << 10 : VisualizationContracts.MAX_CONFIGURATION_BYTES;
        var bytes = RequestBodies.read(exchange, maximum);
        if ((inventory || LibraryRequests.http(exchange.getRequestURI().getPath())) && bytes.length > maximum)
          throw new InventoryFailure(413, "RESOURCE_LIMIT", "Request exceeds its " + maximum + " byte limit", false);
        body = VisualizationContracts.parse(new String(bytes, StandardCharsets.UTF_8));
        if (body.has("api_version") && !new com.google.gson.JsonPrimitive(version).equals(body.get("api_version")))
          throw new IllegalArgumentException("api_version disagrees with the route");
        VisualizationContracts.transportSource(body, version); LibraryReferences.version(body, version);
        if (body.has("view_id")) views.requireSourceVersion(UUID.fromString(text(body, "view_id")), version);
      }
      if (!get && !inventoryAdmitted && InventoryRequests.httpRead(exchange.getRequestURI().getPath())) throw InventoryRequests.busy();
      if (version == 2) body.addProperty("api_version", version);
      try (var reservation = LibraryRequests.http(exchange.getRequestURI().getPath()) ? workspace.inventories.reserveMetadataResponse() : null) {
        JsonObject result;
        if (route.equals("/capabilities") && get) {
          result = workspace.capabilities(session);
        } else if (route.equals("/window") && !get) {
          // The route/version check above owns this transport field. The budget contract
          // still accepts only the allowance; a caller-supplied window/session ID must refuse.
          body.remove("api_version");
          long bytes = WindowBudgets.validate(body); var admitted = new AtomicReference<JsonObject>();
          if (!sessions.update(session, ignored -> admitted.set(workspace.windows.register(session, bytes))))
            throw new IOException("SESSION_EXPIRED: reconnect before reporting the window allowance");
          result = admitted.get();
        } else if (route.equals("/stores") && get) result = workspace.stores();
        else if (route.equals("/source/scopes") && !get && version == 2) result = workspace.sourceScopes(body);
        else if (route.equals("/source/qualify") && !get && version == 2) {
          result = new JsonObject(); result.add("source_ref", sources.qualify(body.getAsJsonObject("source_ref"), body.has("scope_id") ? text(body, "scope_id") : null));
        }
        else if (route.equals("/source") && !get) result = sources.identify(authority, Path.of(text(body, "path")), body.has("scope_id") ? text(body, "scope_id") : null, version == 2);
        else if (route.equals("/source/describe") && !get) result = workspace.describe(inventoryClient, body.getAsJsonObject("source_ref"), body);
        else if (route.equals("/source/health") && !get) result = workspace.health(authority, body);
        else if (route.equals("/source/entries/list") && !get) result = workspace.inventories.list(inventoryClient, body);
        else if (route.equals("/source/entries/get") && !get) result = workspace.inventories.details(inventoryClient, body);
        else if (route.equals("/source/inventory/close") && !get) result = workspace.inventories.release(inventoryClient, body);
        else if (route.equals("/source/resolve") && !get) {
          result = new JsonObject(); result.addProperty("path", sources.resolve(body.getAsJsonObject("source_ref")).toString());
        } else if (route.equals("/library/list") && !get && version == 2) result = workspace.libraryList(authority, body);
        else if (route.equals("/library/migrate") && !get && version == 2) result = workspace.migrateLibrary(authority, body);
        else if (route.equals("/templates/list") && !get) result = workspace.templates(body);
        else if (route.equals("/templates/get") && !get) result = workspace.template(body);
        else if (route.equals("/templates/check") && !get) result = workspace.check(body);
        else if (route.equals("/templates/create") && !get) result = workspace.createTemplate(authority, body);
        else if (route.equals("/templates/update") && !get) result = workspace.updateTemplate(authority, body);
        else if (route.equals("/templates/import-preview") && !get) result = workspace.importPreview(body);
        else if (route.equals("/templates/import") && !get) result = workspace.importTemplate(authority, body);
        else if (route.equals("/templates/export") && !get) result = workspace.exportTemplate(body);
        else if (route.equals("/source/reduction") && !get) result = workspace.reduction(body, owner::active);
        else if (route.equals("/source/histogram") && !get) result = workspace.histogram(body, owner::active);
        else if (route.equals("/source/scatter") && !get) result = workspace.scatter(body, owner::active);
        else if (route.equals("/source/statistics") && !get) result = workspace.statistics(body, owner::active);
        else if (route.equals("/source/raw") && !get) result = workspace.raw(body);
        else if (route.equals("/source/value") && !get) result = workspace.value(body, owner::active);
        else if (route.equals("/source/views") && !get) result = version == 2 ? workspace.librarySourceViews(body) : workspace.sourceViews(body.getAsJsonObject("source_ref"));
        else if (route.equals("/views") && !get) {
          result = workspace.create(authority, body);
        } else if (route.equals("/saved/open") && !get) {
          body.addProperty("api_version", version); result = workspace.openSaved(authority, body);
        }
        else if (route.startsWith("/views/")) {
          var parts = route.substring(7).split("/", -1);
          if (parts.length > 2) throw new IllegalArgumentException("Unknown view route");
          UUID view = UUID.fromString(parts[0]); var current = views.get(view);
          VisualizationContracts.transportSource(current, version); LibraryReferences.version(current, version);
          String action = parts.length == 1 ? "" : parts[1];
          if (action.isEmpty() && get) result = current;
          else if (action.equals("attach") && !get) {
            // Ordering with removal is atomic, but the callback only enqueues, never waits on I/O.
            var future = new AtomicReference<CompletableFuture<JsonObject>>();
            if (!sessions.update(session, ignored -> future.set(views.attach(authority, view, client, () -> sessions.getSession(session) != null, this::silent))))
              throw new IOException("SESSION_EXPIRED: session expired before attachment");
            result = future.get().get();
          } else if (action.equals("detach") && !get) result = views.detach(authority, view, client).get();
          else if (action.equals("bindings") && !get) {
            if (body.has("view_id") && !view.toString().equals(text(body, "view_id"))) throw new IllegalArgumentException("Route and body view IDs differ");
            body.addProperty("view_id", view.toString()); result = workspace.bindView(authority, body);
          }
          else if (action.equals("describe") && !get) result = sources.describe(current.getAsJsonObject("source_ref"));
          else if (action.isEmpty() && !get) result = views.updateAttached(authority, view, revision(body), body.getAsJsonObject("configuration"), client).get();
          else if (action.equals("save") && !get) result = workspace.save(authority, client, view, current, body);
          else throw new IllegalArgumentException("Unknown view route or method");
        } else { error(exchange, 404, "NOT_FOUND", "Unknown visualization route"); return; }
        LibraryReferences.version(result, version);
        if (reservation != null) reservation.finish(result);
        send(exchange, result);
      }
    } catch (Exception failure) {
      Throwable cause = failure;
      while (cause instanceof ExecutionException && cause.getCause() != null) cause = cause.getCause();
      if (cause instanceof org.triplehelix.wpilogmcp.read.server.SourceScopes.Failure selection) { send(exchange, 409, selection.result()); return; }
      if (cause instanceof InventoryFailure inventory) { send(exchange, inventory.status, inventory.json()); return; }
      if (cause instanceof org.triplehelix.wpilogmcp.read.ReadException read) {
        var refusal = VisualizationReadFailure.of(read); var result = new JsonObject();
        result.addProperty("code", refusal.code()); result.addProperty("error", refusal.message()); result.addProperty("retryable", refusal.retryable());
        if (refusal.retryAfterMs() > 0) result.addProperty("retry_after_ms", refusal.retryAfterMs());
        send(exchange, refusal.status(), result); return;
      }
      String message = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
      String code = message.matches("^[A-Z_]+:.*") ? message.substring(0, message.indexOf(':')) : "INVALID_REQUEST";
      if (code.equals("SOURCE_SELECTION_REQUIRED")) {
        var selection = workspace.sourceScopes(new JsonObject()); selection.addProperty("status", "error");
        selection.addProperty("code", code); selection.addProperty("error", message);
        selection.addProperty("hint", "Ask the user to select a scope_id from list_source_scopes; never choose a copy automatically.");
        send(exchange, 409, selection); return;
      }
      int status = cause instanceof java.nio.file.AccessDeniedException || cause instanceof org.triplehelix.wpilogmcp.log.LogFileException ? 403
          : code.equals("SESSION_EXPIRED") ? 404 : Set.of("REVISION_CONFLICT", "VIEW_ATTACHED", "SOURCE_UNAVAILABLE", "NO_WRITABLE_STORE", "REQUEST_CONFLICT", "LIBRARY_UNAVAILABLE", "LEGACY_REFERENCE", "LIBRARY_LIMIT").contains(code) ? 409
          : cause instanceof RejectedExecutionException ? 503 : 400;
      if (code.startsWith("LIBRARY_")) {
        var refusal = new JsonObject(); refusal.addProperty("status", "error"); refusal.addProperty("code", code); refusal.addProperty("error", message);
        boolean retryable = code.equals("LIBRARY_BUSY") || code.equals("LIBRARY_CHECKPOINT_PENDING");
        refusal.addProperty("retryable", retryable); if (retryable) refusal.addProperty("retry_after_ms", 1000);
        send(exchange, retryable ? 503 : status, refusal);
      } else error(exchange, status, status == 403 ? "ACCESS_DENIED" : code, message);
    }
  }
  private static void send(HttpExchange exchange, JsonObject result) throws IOException {
    send(exchange, 200, result);
  }
  private static void send(HttpExchange exchange, int status, JsonObject result) throws IOException {
    var bytes = ReadWire.bytes(result, 16 << 20);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
  }
  private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
    LocalIngestionEndpoint.error(exchange, status, code, message);
  }
}
