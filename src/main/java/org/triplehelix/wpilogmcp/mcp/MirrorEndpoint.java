/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;
import org.triplehelix.wpilogmcp.config.ConfigException;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.store.MirrorService;
import org.triplehelix.wpilogmcp.store.StoreRegistry;

/** Local user controls, deliberately absent from the assistant's read-only tool registry. */
final class MirrorEndpoint implements AutoCloseable {
  static final String PATH = "/store/mirror";
  private final StoreRegistry stores;
  private final java.util.function.Supplier<java.nio.file.Path> liveFolder;
  private volatile MirrorService service;
  private final java.util.concurrent.atomic.AtomicBoolean configuring;
  MirrorEndpoint(StoreRegistry stores) { this(stores, () -> null); }
  /** The live source's folder, which the mirror must never share: only synchronization evicts mirror files. */
  MirrorEndpoint(StoreRegistry stores, java.util.function.Supplier<java.nio.file.Path> liveFolder) {
    this(stores, liveFolder, new java.util.concurrent.atomic.AtomicBoolean());
  }
  /** {@code configuring} is shared with the live source's control (see there). */
  MirrorEndpoint(StoreRegistry stores, java.util.function.Supplier<java.nio.file.Path> liveFolder,
      java.util.concurrent.atomic.AtomicBoolean configuring) {
    this.stores = stores; this.liveFolder = liveFolder; this.configuring = configuring;
  }
  java.nio.file.Path folder() { var current = service; return current == null ? null : current.config().folder(); }
  boolean active() { var current = service; return current != null && current.active(); }

  void configure(MirrorConfig config) throws IOException {
    if (!configuring.compareAndSet(false, true)) throw new IOException(LiveSourceEndpoint.BUSY);
    try {
      var old = service;
      if (old != null && old.config().equals(config)) return;
      if (old != null && old.active()) throw new IOException("Mirror sync is running; retry configuration after it finishes");
      var live = liveFolder.get();
      if (live != null && LiveSourceEndpoint.sameFolder(live, config.folder())) throw new IOException("The mirror folder cannot be the live folder; choose another folder");
      var store = stores.store(config.folder()); // A session lease or permanent configuration must admit this folder.
      var next = new MirrorService(store, config);
      if (old != null) old.close();
      service = next; next.start();
    } finally { configuring.set(false); }
  }
  void handle(HttpExchange exchange) throws IOException {
    String route = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
    try {
      if (route.equals(PATH) && method.equals("GET")) {
        var current = service; reply(exchange, 200, current == null ? Map.of("state", "disabled") : current.status()); return;
      }
      if (route.equals(PATH) && method.equals("DELETE")) { reply(exchange, 200, disable()); return; }
      if (route.equals(PATH + "/files") && method.equals("GET")) {
        var current = service;
        if (current == null) throw new IOException("Configure a mirror first");
        var query = DataEndpoint.query(exchange.getRequestURI().getRawQuery());
        String offset = StoreEndpoint.one(query, "offset"), limit = StoreEndpoint.one(query, "limit");
        reply(exchange, 200, current.files(offset == null ? 0 : Integer.parseInt(offset), limit == null ? 100 : Integer.parseInt(limit))); return;
      }
      if (!method.equals("POST")) { reply(exchange, 405, Map.of("error", "Mirror controls require POST")); return; }
      byte[] bytes = RequestBodies.read(exchange, 65_536);
      if (bytes.length > 65_536) throw new IllegalArgumentException("Mirror body exceeds 64 KiB");
      JsonObject body;
      try { body = org.triplehelix.wpilogmcp.read.internal.ReadWire.parse(bytes); }
      catch (IOException invalid) { throw new IllegalArgumentException("Invalid mirror JSON", invalid); }
      if (route.equals(PATH + "/configure")) {
        configure(MirrorConfig.parse(body, s -> s)); reply(exchange, 202, service.status()); return;
      }
      var current = service;
      if (current == null) { reply(exchange, 409, Map.of("error", "Configure a mirror first")); return; }
      switch (route) {
        case PATH + "/migrate" -> {
          if (current.active()) throw new IOException("Mirror operation is already running");
          if (!body.keySet().equals(java.util.Set.of("store_id")) || !body.get("store_id").isJsonPrimitive()
              || !body.getAsJsonPrimitive("store_id").isString()) throw new IllegalArgumentException("store_id is required");
          String id = body.get("store_id").getAsString(); java.util.UUID.fromString(id);
          current.migrate(id); reply(exchange, 202, current.status());
        }
        case PATH + "/cleanup_preview" -> {
          if (!body.keySet().equals(java.util.Set.of("keys")) || !body.get("keys").isJsonArray()) throw new IllegalArgumentException("keys array is required");
          var keys = new java.util.ArrayList<String>();
          for (var key : body.getAsJsonArray("keys")) {
            if (!key.isJsonPrimitive() || !key.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("keys must be strings");
            keys.add(key.getAsString());
          }
          reply(exchange, 200, current.cleanupPreview(keys));
        }
        case PATH + "/cleanup", PATH + "/reinclude" -> reply(exchange, 409,
            Map.of("code", "WRITER_NOT_READY", "error", "Cleanup and exclusion changes require the generation publication engine; no files or exclusions changed"));
        case PATH + "/sync" -> {
          if (current.active()) { reply(exchange, 409, Map.of("error", "Mirror sync is already running")); return; }
          current.syncNow(); reply(exchange, 202, current.status());
        }
        case PATH + "/pin_session", PATH + "/unpin_session" -> {
          var id = body.get("session_id");
          if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("session_id must be a string");
          // This can queue behind a transfer; accepting it must not block an HTTP worker.
          current.pin(id.getAsString(), route.endsWith("/pin_session")).whenComplete((result, error) -> {
            if (error != null) org.slf4j.LoggerFactory.getLogger(MirrorEndpoint.class).warn("Mirror pin update failed: {}", error.getMessage());
          });
          reply(exchange, 202, Map.of("session_id", id.getAsString(), "pinned", route.endsWith("/pin_session")));
        }
        default -> reply(exchange, 404, Map.of("error", "Unknown mirror control"));
      }
    } catch (ConfigException | RuntimeException e) { reply(exchange, 400, Map.of("error", "Invalid mirror request: " + e.getMessage())); }
    catch (IOException e) { reply(exchange, 409, Map.of("error", e.getMessage())); }
  }
  /** Control metadata is bounded independently of the raw-data streaming routes. */
  private static void reply(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] bytes;
    try {
      bytes = org.triplehelix.wpilogmcp.read.internal.ReadWire.bytes(
          org.triplehelix.wpilogmcp.store.StoreJson.JSON.toJsonTree(value).getAsJsonObject(), 1_048_576);
    } catch (org.triplehelix.wpilogmcp.read.ReadException limit) {
      StoreEndpoint.send(exchange, 413, Map.of("code", "RESPONSE_TOO_LARGE", "error", "Mirror reply exceeds 1 MiB; use a smaller file page")); return;
    }
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) { output.write(bytes); }
  }
  /** Turning the mirror off waits its turn: during a configuration it would report the mirror
   * disabled while the configuration then started the next service. */
  Map<String, Object> disable() throws IOException {
    if (!configuring.compareAndSet(false, true)) throw new IOException(LiveSourceEndpoint.BUSY);
    try { close(); } finally { configuring.set(false); }
    return Map.of("state", "disabled");
  }
  @Override public void close() {
    var previous = service; service = null; if (previous != null) previous.close();
  }
}
