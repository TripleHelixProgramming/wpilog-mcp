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
  private volatile Map<java.nio.file.Path, MirrorService> services = Map.of();
  private volatile java.util.function.Supplier<Object> peerStatus = () -> Map.of("state", "not_started", "candidates", java.util.List.of());
  private volatile java.util.function.Function<String, java.util.List<String>> discovered = master -> java.util.List.of();
  private volatile Runnable configured = () -> {};
  void onConfigured(Runnable listener) { configured = listener; }
  void discovery(java.util.function.Supplier<Object> status, java.util.function.Function<String, java.util.List<String>> supplier) {
    peerStatus = status; discovered = supplier; services.values().forEach(service -> service.discovery(supplier));
  }
  private MirrorService.ImpactReader impacts = (header, files) -> { throw new IOException("VIEW_IMPACT_UNAVAILABLE: view owner unavailable"); };
  void impacts(MirrorService.ImpactReader reader) { impacts = reader; services.values().forEach(s -> s.impacts(reader)); }
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
  java.nio.file.Path folder() { var all = services; return all.size() == 1 ? all.keySet().iterator().next() : null; }
  java.util.List<java.nio.file.Path> folders() { return java.util.List.copyOf(services.keySet()); }
  boolean active() { return services.values().stream().anyMatch(MirrorService::active); }
  boolean configured() { return !services.isEmpty(); }
  boolean discoveryEnabled() { return services.values().stream().anyMatch(service -> service.config().discovery()); }
  private MirrorService selected(String id) throws IOException {
    var all = services;
    if (id == null && all.size() == 1) return all.values().iterator().next();
    if (id != null) for (var service : all.values()) if (id.equals(service.id())) return service;
    if (all.isEmpty()) throw new IOException("Configure a mirror first");
    throw new IOException("SOURCE_SELECTION_REQUIRED: pass ?mirror=<mirror_id> from GET /store/mirror; no mirror is a default");
  }
  private Object status() {
    var all = services;
    if (all.isEmpty()) return Map.of("state", "disabled");
    if (all.size() == 1) return all.values().iterator().next().status();
    return Map.of("state", "multiple", "mirrors", all.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> e.getValue().status()).toList());
  }

  void configure(MirrorConfig config) throws IOException {
    if (!configuring.compareAndSet(false, true)) throw new IOException(LiveSourceEndpoint.BUSY);
    try {
      var store = stores.store(config.folder());
      var old = services.get(store.root());
      if (old != null && old.config().equals(config)) return;
      if (old != null && old.active()) throw new IOException("Mirror sync is running; retry configuration after it finishes");
      if (old == null && services.size() >= 8) throw new IOException("RESOURCE_LIMIT: at most eight configured mirrors");
      for (var folder : services.keySet()) if (!folder.equals(store.root()) && (folder.startsWith(store.root()) || store.root().startsWith(folder)))
        throw new IOException("Mirror folders cannot overlap");
      var live = liveFolder.get();
      if (live != null && (LiveSourceEndpoint.sameFolder(live, config.folder()) || live.startsWith(store.root()) || store.root().startsWith(live)))
        throw new IOException("The mirror folder cannot overlap the live folder; choose another folder");
      // A session lease or permanent configuration must admit this folder.
      var next = new MirrorService(store, config); next.impacts(impacts); next.discovery(discovered);
      next.rootGrant(stores.retainMirrorRoot(store.root()));
      if (old != null) old.close();
      var all = new java.util.LinkedHashMap<>(services); all.put(store.root(), next); services = Map.copyOf(all); next.start();
    } finally { configuring.set(false); }
    configured.run(); // Provider I/O and callbacks run after configuration publication and guard release.
  }
  void handle(HttpExchange exchange) throws IOException {
    String route = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
    try {
      var query = DataEndpoint.query(exchange.getRequestURI().getRawQuery());
      String selectedId = StoreEndpoint.one(query, "mirror");
      if (route.equals(PATH + "/peers") && method.equals("GET")) { reply(exchange, 200, peerStatus.get()); return; }
      if (route.equals(PATH) && method.equals("GET")) {
        reply(exchange, 200, selectedId == null ? status() : selected(selectedId).status()); return;
      }
      if (route.equals(PATH) && method.equals("DELETE")) { reply(exchange, 200, disable(selectedId)); return; }
      if ((route.equals(PATH + "/files") || route.equals(PATH + "/exclusions")) && method.equals("GET")) {
        var current = selected(selectedId);
        String offset = StoreEndpoint.one(query, "offset"), limit = StoreEndpoint.one(query, "limit");
        int from = offset == null ? 0 : Integer.parseInt(offset), count = limit == null ? 100 : Integer.parseInt(limit);
        reply(exchange, 200, route.endsWith("/files") ? current.files(from, count) : current.exclusions(from, count)); return;
      }
      if (!method.equals("POST")) { reply(exchange, 405, Map.of("error", "Mirror controls require POST")); return; }
      byte[] bytes = RequestBodies.read(exchange, 65_536);
      if (bytes.length > 65_536) throw new IllegalArgumentException("Mirror body exceeds 64 KiB");
      JsonObject body;
      try { body = org.triplehelix.wpilogmcp.read.internal.ReadWire.parse(bytes); }
      catch (IOException invalid) { throw new IllegalArgumentException("Invalid mirror JSON", invalid); }
      if (route.equals(PATH + "/configure")) {
        configure(MirrorConfig.parse(body, s -> s)); reply(exchange, 202, status()); return;
      }
      var current = selected(selectedId);
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
        case PATH + "/cleanup", PATH + "/reinclude" -> {
          boolean cleanup = route.endsWith("/cleanup");
          var fields = cleanup ? java.util.Set.of("request_id", "store_id", "catalog_epoch", "catalog_revision", "policy_revision", "keys")
              : java.util.Set.of("request_id", "catalog_epoch", "catalog_revision", "policy_revision", "keys");
          if (!body.keySet().equals(fields)) throw new IllegalArgumentException("Expected " + fields);
          String request = uuid(body, "request_id"), epoch = uuid(body, "catalog_epoch");
          String policy = text(body, "policy_revision"); if (!policy.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid policy revision");
          if (!body.get("catalog_revision").isJsonPrimitive() || !body.getAsJsonPrimitive("catalog_revision").isNumber()) throw new IllegalArgumentException("Invalid catalog revision");
          long revision = body.get("catalog_revision").getAsBigDecimal().longValueExact(); if (revision < 1) throw new IllegalArgumentException("Invalid catalog revision");
          if (!body.get("keys").isJsonArray()) throw new IllegalArgumentException("keys array is required");
          var keys = new java.util.ArrayList<String>();
          for (var key : body.getAsJsonArray("keys")) {
            if (!key.isJsonPrimitive() || !key.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid replica key");
            String value = key.getAsString(); if (!java.util.UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid replica key"); keys.add(value);
          }
          if (keys.isEmpty() || keys.size() > 32 || keys.stream().distinct().count() != keys.size()) throw new IllegalArgumentException("Select 1 to 32 distinct keys");
          if (cleanup) current.cleanup(request, uuid(body, "store_id"), epoch, revision, policy, keys);
          else current.reinclude(request, epoch, revision, policy, keys);
          reply(exchange, 202, current.status());
        }
        case PATH + "/sync" -> {
          if (current.active()) { reply(exchange, 409, Map.of("error", "Mirror sync is already running")); return; }
          current.syncNow(); reply(exchange, 202, current.status());
        }
        case PATH + "/pin_session", PATH + "/unpin_session" -> {
          var id = body.get("session_id");
          if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("session_id must be a string");
          if (current.active()) throw new IOException("Mirror operation is already running; retry the pin change");
          // Publication is asynchronous; format-2 pins share the bounded replica owner.
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
  private static String text(JsonObject body, String key) {
    if (!body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid " + key);
    return body.get(key).getAsString();
  }
  private static String uuid(JsonObject body, String key) {
    String value = text(body, key);
    if (!java.util.UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid " + key);
    return value;
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
    return disable(null);
  }
  private Map<String, Object> disable(String id) throws IOException {
    if (!configuring.compareAndSet(false, true)) throw new IOException(LiveSourceEndpoint.BUSY);
    try {
      if (!services.isEmpty()) {
        var selected = selected(id); var all = new java.util.LinkedHashMap<>(services);
        all.values().removeIf(service -> service == selected); services = Map.copyOf(all); selected.close();
      }
    } finally { configuring.set(false); }
    return Map.of("state", "disabled");
  }
  @Override public void close() {
    var previous = services; services = Map.of(); previous.values().forEach(MirrorService::close);
  }
}
