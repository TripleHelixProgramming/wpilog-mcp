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
        var current = service; StoreEndpoint.send(exchange, 200, current == null ? Map.of("state", "disabled") : current.status()); return;
      }
      if (route.equals(PATH) && method.equals("DELETE")) { StoreEndpoint.send(exchange, 200, disable()); return; }
      if (!method.equals("POST")) { StoreEndpoint.send(exchange, 405, Map.of("error", "Mirror controls require POST")); return; }
      byte[] bytes = RequestBodies.read(exchange, 65_536);
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
