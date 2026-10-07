/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.triplehelix.wpilogmcp.config.ConfigException;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.store.MirrorService;
import org.triplehelix.wpilogmcp.store.StoreRegistry;

/** Local user controls, deliberately absent from the assistant's read-only tool registry. */
final class MirrorEndpoint implements AutoCloseable {
  static final String PATH = "/store/mirror";
  private final StoreRegistry stores;
  private volatile MirrorService service;
  private final java.util.concurrent.atomic.AtomicBoolean configuring = new java.util.concurrent.atomic.AtomicBoolean();
  MirrorEndpoint(StoreRegistry stores) { this.stores = stores; }
  boolean active() { var current = service; return current != null && current.active(); }

  void configure(MirrorConfig config) throws IOException {
    if (!configuring.compareAndSet(false, true)) throw new IOException("Mirror configuration is already changing");
    try {
      var old = service;
      if (old != null && old.config().equals(config)) return;
      if (old != null && old.active()) throw new IOException("Mirror sync is running; retry configuration after it finishes");
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
        var current = service; StoreEndpoint.send(exchange, 200, current == null ? Map.of("state", "disabled") : current.status()); return;
      }
      if (route.equals(PATH) && method.equals("DELETE")) { close(); StoreEndpoint.send(exchange, 200, Map.of("state", "disabled")); return; }
      if (!method.equals("POST")) { StoreEndpoint.send(exchange, 405, Map.of("error", "Mirror controls require POST")); return; }
      byte[] bytes = exchange.getRequestBody().readNBytes(65_537);
      if (bytes.length > 65_536) throw new IllegalArgumentException("Mirror body exceeds 64 KiB");
      var body = new GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
      if (body == null) throw new IllegalArgumentException("Expected a JSON object");
      if (route.equals(PATH + "/configure")) {
        configure(MirrorConfig.parse(body, s -> s)); StoreEndpoint.send(exchange, 202, service.status()); return;
      }
      var current = service;
      if (current == null) { StoreEndpoint.send(exchange, 409, Map.of("error", "Configure a mirror first")); return; }
      switch (route) {
        case PATH + "/sync" -> {
          if (current.active()) { StoreEndpoint.send(exchange, 409, Map.of("error", "Mirror sync is already running")); return; }
          current.syncNow(); StoreEndpoint.send(exchange, 202, current.status());
        }
        case PATH + "/pin_session", PATH + "/unpin_session" -> {
          var id = body.get("session_id");
          if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("session_id must be a string");
          // This can queue behind a transfer; accepting it must not block an HTTP worker.
          current.pin(id.getAsString(), route.endsWith("/pin_session")).whenComplete((result, error) -> {
            if (error != null) org.slf4j.LoggerFactory.getLogger(MirrorEndpoint.class).warn("Mirror pin update failed: {}", error.getMessage());
          });
          StoreEndpoint.send(exchange, 202, Map.of("session_id", id.getAsString(), "pinned", route.endsWith("/pin_session")));
        }
        default -> StoreEndpoint.send(exchange, 404, Map.of("error", "Unknown mirror control"));
      }
    } catch (ConfigException | RuntimeException e) { StoreEndpoint.send(exchange, 400, Map.of("error", "Invalid mirror request: " + e.getMessage())); }
    catch (IOException e) { StoreEndpoint.send(exchange, 409, Map.of("error", e.getMessage())); }
  }
  @Override public void close() {
    var previous = service; service = null; if (previous != null) previous.close();
  }
}
