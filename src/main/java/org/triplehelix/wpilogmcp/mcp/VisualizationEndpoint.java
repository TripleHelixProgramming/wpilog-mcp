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
  private final SessionManager sessions;
  private final ServerIdentity identity;
  private final VisualizationService views;
  private final RecordedSources sources;
  private final VisualizationWorkspace workspace;
  VisualizationEndpoint(SessionManager sessions, ToolRegistry registry) throws IOException {
    this.sessions = sessions; this.identity = registry.serverIdentity(); workspace = registry.visualizations();
    views = workspace.views; sources = workspace.sources;
  }
  void release(String session) { views.releaseClient(UUID.fromString(session)); workspace.notifications.release(session); }
  ControlNotifications notifications() { return workspace.notifications; }
  @Override public void close() { workspace.close(); }

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
        var bytes = RequestBodies.read(exchange, VisualizationContracts.MAX_CONFIGURATION_BYTES);
        body = VisualizationContracts.parse(new String(bytes, StandardCharsets.UTF_8));
      }
      JsonObject result;
      if (route.equals("/capabilities") && get) {
        result = workspace.capabilities(); result.addProperty("client_id", session);
      } else if (route.equals("/stores") && get) result = workspace.stores();
      else if (route.equals("/source") && !get) result = sources.identify(authority, Path.of(text(body, "path")));
      else if (route.equals("/source/describe") && !get) result = sources.describe(body.getAsJsonObject("source_ref"));
      else if (route.equals("/source/resolve") && !get) {
        result = new JsonObject(); result.addProperty("path", sources.resolve(body.getAsJsonObject("source_ref")).toString());
      } else if (route.equals("/templates/list") && !get) result = workspace.templates(body);
      else if (route.equals("/templates/get") && !get) result = workspace.template(body);
      else if (route.equals("/templates/check") && !get) result = workspace.check(body);
      else if (route.equals("/templates/create") && !get) result = workspace.createTemplate(authority, body);
      else if (route.equals("/templates/update") && !get) result = workspace.updateTemplate(authority, body);
      else if (route.equals("/templates/import-preview") && !get) result = workspace.importPreview(body);
      else if (route.equals("/templates/import") && !get) result = workspace.importTemplate(authority, body);
      else if (route.equals("/templates/export") && !get) result = workspace.exportTemplate(body);
      else if (route.equals("/source/statistics") && !get) result = workspace.statistics(body);
      else if (route.equals("/source/raw") && !get) result = workspace.raw(body);
      else if (route.equals("/source/value") && !get) result = workspace.value(body);
      else if (route.equals("/source/views") && !get) result = workspace.sourceViews(body.getAsJsonObject("source_ref"));
      else if (route.equals("/views") && !get) {
        result = workspace.create(authority, body);
      } else if (route.equals("/saved/open") && !get) result = workspace.openSaved(authority, body);
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
        else if (action.equals("save") && !get) result = workspace.save(authority, client, view, current, body);
        else throw new IllegalArgumentException("Unknown view route or method");
      } else { error(exchange, 404, "NOT_FOUND", "Unknown visualization route"); return; }
      send(exchange, result);
    } catch (Exception failure) {
      Throwable cause = failure;
      while (cause instanceof ExecutionException && cause.getCause() != null) cause = cause.getCause();
      if (cause instanceof org.triplehelix.wpilogmcp.read.ReadException read) {
        var refusal = VisualizationReadFailure.of(read); var result = new JsonObject();
        result.addProperty("code", refusal.code()); result.addProperty("error", refusal.message()); result.addProperty("retryable", refusal.retryable());
        send(exchange, refusal.status(), result); return;
      }
      String message = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
      String code = message.matches("^[A-Z_]+:.*") ? message.substring(0, message.indexOf(':')) : "INVALID_REQUEST";
      int status = cause instanceof java.nio.file.AccessDeniedException || cause instanceof org.triplehelix.wpilogmcp.log.LogFileException ? 403
          : code.equals("SESSION_EXPIRED") ? 404 : Set.of("REVISION_CONFLICT", "VIEW_ATTACHED", "SOURCE_UNAVAILABLE", "NO_WRITABLE_STORE", "REQUEST_CONFLICT").contains(code) ? 409
          : cause instanceof RejectedExecutionException ? 503 : 400;
      error(exchange, status, status == 403 ? "ACCESS_DENIED" : code, message);
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
