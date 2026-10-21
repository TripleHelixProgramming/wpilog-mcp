/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.store.*;

/** Read-only replica door. Its transport owns four request slots (two MiB each through final
 * socket completion); control close uses separate bounded workers so slow readers cannot trap it. */
final class ReplicaEndpoint implements AutoCloseable {
  static final String PATH = "/replicas/v1";
  private final ReplicaProtocol protocol;
  ReplicaEndpoint(StoreDoor door, StoreRegistry stores) {
    protocol = new ReplicaProtocol(door, stores);
  }
  void handle(HttpExchange exchange) throws IOException {
    byte[] input = RequestBodies.read(exchange, 64 << 10);
    if (input.length > 64 << 10) { send(exchange, 413, error("RESOURCE_LIMIT", "Request exceeds 64 KiB")); return; }
    String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
    String session = exchange.getRequestHeaders().getFirst("Replica-Session");
    if (session != null && !session.matches("[a-f0-9-]{36}")) { send(exchange, 400, error("INVALID_ARGUMENT", "Invalid peer session")); return; }
    try {
      if (exchange.getRequestURI().getRawQuery() != null) throw new IllegalArgumentException("Replica controls do not accept query parameters");
      if (path.equals(PATH + "/handshake") && method.equals("GET") && input.length == 0) {
        send(exchange, 200, protocol.handshake(), ReplicaProtocol.HANDSHAKE_BYTES); return;
      }
      if (!method.equals("POST")) { send(exchange, 405, error("METHOD_NOT_ALLOWED", "Use POST for replica controls and immutable range reads")); return; }
      JsonObject body;
      try { body = ReadWire.parse(input); }
      catch (IOException malformed) { throw new IllegalArgumentException("Invalid replica JSON", malformed); }
      switch (path.substring(PATH.length())) {
        case "/catalog" -> {
          fields(body, Set.of("store_id", "limit", "inventory_id", "cursor"));
          int limit = integer(body, "limit", 256);
          send(exchange, 200, protocol.page(session, text(body, "store_id"), limit, optional(body, "inventory_id"), optional(body, "cursor")));
        }
        case "/status" -> {
          fields(body, Set.of("store_id", "session_id", "limit"));
          send(exchange, 200, protocol.status(session, text(body, "store_id"),
              optional(body, "session_id"), integer(body, "limit", 64)));
        }
        case "/close" -> {
          fields(body, Set.of("inventory_id"));
          String inventory = optional(body, "inventory_id");
          if (inventory == null) protocol.closeSession(session); else protocol.closeInventory(session, inventory);
          send(exchange, 200, java.util.Map.of("status", "ok"));
        }
        case "/file" -> {
          fields(body, Set.of("store_id", "master_store_id", "file_id", "file_version", "sha256", "offset"));
          var identity = new ReplicaCatalog.Identity(text(body, "master_store_id"), text(body, "file_id"), text(body, "file_version"));
          long offset = longInteger(body, "offset", 0);
          try (var payload = protocol.payload(session, text(body, "store_id"), identity, text(body, "sha256"))) {
            if (offset < 0 || offset > payload.sizeBytes()) throw new IllegalArgumentException("offset outside immutable file");
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("X-Content-SHA256", text(body, "sha256"));
            exchange.getResponseHeaders().set("X-Replica-Offset", Long.toString(offset));
            long remaining = payload.sizeBytes() - offset;
            exchange.sendResponseHeaders(200, remaining == 0 ? -1 : remaining);
            try (var channel = FileChannel.open(payload.path(), StandardOpenOption.READ); var out = exchange.getResponseBody()) {
              channel.position(offset); var buffer = ByteBuffer.allocate(65_536);
              while (remaining > 0) {
                buffer.clear().limit((int) Math.min(remaining, buffer.capacity()));
                int count = channel.read(buffer);
                if (count < 0) throw new IOException("Immutable replica shortened during response");
                out.write(buffer.array(), 0, count); remaining -= count;
              }
            }
          }
        }
        default -> send(exchange, 404, error("NOT_FOUND", "Unknown replica route"));
      }
    } catch (ReplicaProtocol.StatusFailure failure) {
      var result = failure.result();
      boolean busy = result.get("code").getAsString().equals("BUSY");
      if (result.get("retryable").getAsBoolean()) exchange.getResponseHeaders().set("Retry-After", "1");
      send(exchange, busy ? 503 : 409, result);
    } catch (IllegalArgumentException failure) { send(exchange, 400, error("INVALID_ARGUMENT", failure.getMessage())); }
    catch (IOException failure) {
      if (exchange.getResponseCode() >= 0) throw failure;
      var refusal = refusal(failure);
      String code = (String) refusal.get("code");
      int status = code.equals("BUSY") ? 503 : code.equals("RESOURCE_LIMIT") ? 413 : code.equals("DEADLINE") ? 504 : 409;
      if (status == 503) exchange.getResponseHeaders().set("Retry-After", "1");
      send(exchange, status, refusal);
    }
  }
  /** Filesystem messages can contain drive letters and private paths, neither of which is a
   * protocol code. Only explicit symbolic refusals may cross this network boundary. */
  static java.util.Map<String, Object> refusal(IOException failure) {
    String message = failure.getMessage();
    int colon = message == null ? -1 : message.indexOf(':');
    if (!(failure instanceof java.nio.file.FileSystemException) && colon > 0
        && message.substring(0, colon).matches("[A-Z_]{2,64}"))
      return error(message.substring(0, colon), message);
    return error("SOURCE_UNAVAILABLE", "Replica source is unavailable");
  }
  private static java.util.Map<String, Object> error(String code, String message) {
    return java.util.Map.of("status", "error", "code", code, "message", message, "retryable", code.equals("BUSY") || code.equals("DEADLINE"));
  }
  static void busy(HttpExchange exchange) throws IOException {
    RequestBodies.read(exchange, 64 << 10);
    exchange.getResponseHeaders().set("Retry-After", "1");
    send(exchange, 503, error("BUSY", "Replica response capacity is busy; retry after 1000 ms"));
  }
  static void fields(JsonObject body, Set<String> allowed) {
    if (!allowed.containsAll(body.keySet())) throw new IllegalArgumentException("Unknown replica field");
  }
  static String optional(JsonObject body, String key) { return !body.has(key) || body.get(key).isJsonNull() ? null : text(body, key); }
  static String text(JsonObject body, String key) {
    var value = body.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
        || value.getAsString().isBlank() || value.getAsString().length() > 1024) throw new IllegalArgumentException("Invalid " + key);
    return value.getAsString();
  }
  private static long longInteger(JsonObject body, String key, long fallback) {
    if (!body.has(key)) return fallback;
    var value = body.get(key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid " + key);
    try { return value.getAsBigDecimal().longValueExact(); }
    catch (ArithmeticException invalid) { throw new IllegalArgumentException("Invalid " + key); }
  }
  private static int integer(JsonObject body, String key, int fallback) {
    try { return Math.toIntExact(longInteger(body, key, fallback)); }
    catch (ArithmeticException invalid) { throw new IllegalArgumentException("Invalid " + key); }
  }
  private static void send(HttpExchange exchange, int status, Object body) throws IOException { send(exchange, status, body, ReplicaProtocol.PAGE_BYTES); }
  private static void send(HttpExchange exchange, int status, Object body, int maximum) throws IOException {
    byte[] bytes = ReadWire.bytes(StoreJson.JSON.toJsonTree(body), maximum);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) { out.write(bytes); }
  }
  @Override public void close() { protocol.close(); }
}
