/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.triplehelix.wpilogmcp.store.LocalIngestion;
import org.triplehelix.wpilogmcp.store.StoreJson;

/**
 * Local enrollment is an explicit user action, outside the tool catalog. Admission claims a
 * live session before disk work; the resulting permanent policy deliberately outlives it.
 * The transport owns the loopback/Origin gate and bounded store worker/progress deadline.
 */
final class LocalIngestionEndpoint {
  static final String PATH = "/store/local-ingestion";
  private final SessionManager sessions;
  private final Supplier<LocalIngestion> service;
  LocalIngestionEndpoint(SessionManager sessions, Supplier<LocalIngestion> service) {
    this.sessions = sessions; this.service = service;
  }
  void handle(HttpExchange exchange) throws IOException {
    String id = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
    if (id == null) { error(exchange, 400, "MISSING_SESSION", "Missing Mcp-Session-Id header"); return; }
    if (sessions.getSession(id) == null) { error(exchange, 404, "SESSION_EXPIRED", "Session not found or expired"); return; }
    var ingestion = service.get();
    if (ingestion == null) {
      error(exchange, 409, "FEATURE_DISABLED", "Data store setup is experimental and disabled. Set experimental_data_store: true for the named server and restart it.");
      return;
    }
    String route = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
    try {
      if (route.equals(PATH) && method.equals("GET")) send(exchange, 200, ingestion.snapshot());
      else if (route.equals(PATH) && method.equals("PUT")) {
        var bytes = exchange.getRequestBody().readNBytes(65537);
        if (bytes.length > 65536) { error(exchange, 413, "RESOURCE_LIMIT", "Ingestion policy exceeds 64 KiB"); return; }
        LocalIngestion.Policy policy;
        try {
          var object = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
          if (!object.has("revision") || !object.has("enabled") || !object.has("sources"))
            throw new IllegalArgumentException();
          policy = StoreJson.JSON.fromJson(object, LocalIngestion.Policy.class);
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Expected a policy object with revision, enabled and sources"); }
        // No I/O under the session lock. Deletion before this claim refuses the operation;
        // deletion afterwards cannot revoke a policy the user has already submitted.
        if (!sessions.update(id, ignored -> {})) { error(exchange, 404, "SESSION_EXPIRED", "Session expired before enrollment"); return; }
        send(exchange, 200, ingestion.replace(policy));
      } else if (route.equals(PATH + "/scan") && method.equals("POST")) {
        ingestion.scanNow(); send(exchange, 202, ingestion.snapshot());
      } else if (route.equals(PATH + "/files") && method.equals("GET")) {
        int offset = 0, limit = 100;
        var query = exchange.getRequestURI().getRawQuery();
        if (query != null) for (var part : query.split("&")) {
          var pair = part.split("=", 2);
          if (pair.length != 2) throw new IllegalArgumentException("Expected offset and limit integers");
          switch (pair[0]) {
            case "offset" -> offset = Integer.parseInt(pair[1]);
            case "limit" -> limit = Integer.parseInt(pair[1]);
            default -> throw new IllegalArgumentException("Unknown files query parameter");
          }
        }
        send(exchange, 200, ingestion.files(offset, limit));
      } else error(exchange, route.equals(PATH) || route.equals(PATH + "/scan") || route.equals(PATH + "/files") ? 405 : 404,
          "INVALID_REQUEST", "Unknown local ingestion route or method");
    } catch (LocalIngestion.ConflictException failure) { error(exchange, 409, "CONFLICT", failure.getMessage()); }
    catch (IllegalArgumentException failure) { error(exchange, 400, "INVALID_REQUEST", failure.getMessage()); }
    catch (IOException failure) { error(exchange, 400, "IO_ERROR", failure.getMessage()); }
  }
  static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
    var object = new JsonObject(); object.addProperty("code", code); object.addProperty("error", message);
    send(exchange, status, object);
  }
  private static void send(HttpExchange exchange, int status, Object value) throws IOException {
    var bytes = StoreJson.JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) { output.write(bytes); }
  }
}
