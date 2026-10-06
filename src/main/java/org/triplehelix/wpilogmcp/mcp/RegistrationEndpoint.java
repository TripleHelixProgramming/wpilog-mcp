/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.config.ClientLeases;

/**
 * Permission changes belong to a person, not to a model's tool catalog. The transport checks
 * Origin and its loopback binding first; this handler ties every change to a still-live session.
 * JSON parsing errors deliberately omit the body: a malformed key must never enter a log.
 */
final class RegistrationEndpoint {
  private static final int MAX_BODY = 65536;
  private final SessionManager sessions;
  private final ClientLeases leases;

  RegistrationEndpoint(SessionManager sessions, ClientLeases leases) {
    this.sessions = sessions;
    this.leases = leases;
  }

  void handle(HttpExchange exchange) throws IOException {
    var route = exchange.getRequestURI().getPath();
    boolean directories = route.equals("/directories");
    if (!directories && !route.equals("/tba-key")) {
      reply(exchange, 404, "Unknown registration endpoint");
      return;
    }
    var method = exchange.getRequestMethod();
    if (!method.equals("POST") && !(directories && method.equals("DELETE"))) {
      reply(exchange, 405, "Method not allowed");
      return;
    }
    var id = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
    if (id == null) {
      reply(exchange, 400, "Missing Mcp-Session-Id header");
      return;
    }
    if (sessions.getSession(id) == null) {
      reply(exchange, 404, "Session not found or expired");
      return;
    }
    try {
      boolean updated;
      if (method.equals("DELETE")) {
        updated = sessions.update(id, session -> leases.removeDirectories(id));
      } else {
        var body = readBody(exchange);
        if (directories) {
          var values = new ArrayList<ClientLeases.Directory>();
          if (!body.has("paths") || !body.get("paths").isJsonArray()) {
            throw new IllegalArgumentException("paths must be an array");
          }
          Integer defaultTeam = team(body.get("team"));
          for (var entry : body.getAsJsonArray("paths")) {
            var item = entry.isJsonObject() ? entry.getAsJsonObject() : null;
            var value = item == null ? entry : item.get("path");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
              throw new IllegalArgumentException("Each path must be a string or an object with path and team");
            }
            String name = value.getAsString();
            Path path;
            try {
              path = Path.of(name);
              if (!path.isAbsolute() || !Files.isDirectory(path)) {
                throw new IOException();
              }
              path = path.toRealPath();
            } catch (IOException | InvalidPathException e) {
              throw new IllegalArgumentException("Not an existing absolute directory: " + name);
            }
            values.add(new ClientLeases.Directory(path,
                item != null && item.has("team") ? team(item.get("team")) : defaultTeam));
          }
          updated = sessions.update(id, session -> leases.replaceDirectories(id, values));
          if (updated && body.has("project_servers_ignored")
              && body.get("project_servers_ignored").equals(new JsonPrimitive(true))) {
            LoggerFactory.getLogger(RegistrationEndpoint.class).info(
                "connect ignored the project file's servers section; the shared server uses its home configuration");
          }
        } else {
          if (!body.has("key")) {
            throw new IllegalArgumentException("key must be a string or null");
          }
          var value = body.get("key");
          if (!value.isJsonNull() && (!value.isJsonPrimitive()
              || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())) {
            throw new IllegalArgumentException("key must be a nonempty string or null");
          }
          String key = value.isJsonNull() ? null : value.getAsString();
          if (key != null && key.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("key must contain only visible ASCII characters");
          }
          updated = sessions.update(id, session -> leases.registerKey(id, key));
        }
      }
      reply(exchange, updated ? 200 : 404, updated ? null : "Session not found or expired");
    } catch (IllegalArgumentException e) {
      reply(exchange, 400, e.getMessage());
    }
  }

  private static JsonObject readBody(HttpExchange exchange) throws IOException {
    var bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
    if (bytes.length > MAX_BODY) {
      throw new IllegalArgumentException("Registration body is too large");
    }
    try {
      return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Malformed registration body: expected a JSON object");
    }
  }

  private static Integer team(JsonElement value) {
    if (value == null || value.isJsonNull()) {
      return null;
    }
    try {
      if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
        int number = value.getAsBigDecimal().intValueExact();
        if (number > 0) return number;
      }
    } catch (ArithmeticException | NumberFormatException e) {
      // Never include a supplied value in this diagnostic (this route also accepts secrets).
    }
    throw new IllegalArgumentException("team must be a positive integer or null");
  }

  private static void reply(HttpExchange exchange, int status, String error) throws IOException {
    var body = new JsonObject();
    if (error == null) body.addProperty("status", "ok");
    else body.addProperty("error", error);
    var bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
