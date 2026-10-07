/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.store.StoreDoor;
import org.triplehelix.wpilogmcp.store.StoreJson;

/** Raw store bytes use manifest membership, independently of the broader MCP log allowlist. */
final class StoreEndpoint {
  private final StoreDoor door;
  StoreEndpoint(StoreDoor door) { this.door = door; }

  void handle(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("GET")) { send(exchange, 405, Map.of("error", "GET required")); return; }
    try {
      var query = DataEndpoint.query(exchange.getRequestURI().getRawQuery());
      String selector = one(query, "store"), route = exchange.getRequestURI().getPath();
      if (route.equals("/store") && selector == null) {
        var inventory = door.inventory();
        var stores = inventory.stores().stream().map(StoreDoor.Selected::description).toList();
        send(exchange, 200, stores.size() == 1 && inventory.unreadable().isEmpty() ? stores.get(0)
            : Map.of("stores", stores, "unreadable", inventory.unreadable())); return;
      }
      var store = door.select(selector);
      switch (route) {
        case "/store" -> send(exchange, 200, store.description());
        case "/store/robots" -> send(exchange, 200, Map.of("robots", StoreCatalog.readManaged(store.root(), store.security())
            .robots().stream().map(StoreCatalog.RobotDirectory::robot).toList()));
        case "/store/sessions" -> send(exchange, 200, StoreDoor.sessions(store, one(query, "since"), one(query, "robot"), one(query, "event")));
        default -> {
          if (!route.startsWith("/store/files/")) { send(exchange, 404, Map.of("error", "Unknown store route")); return; }
          String relative = route.substring("/store/files/".length());
          // A classified log may itself be named prefix-hash. The required query field
          // distinguishes the hash operation from downloading that catalog payload.
          boolean hash = relative.endsWith("/prefix-hash") && query.containsKey("bytes");
          if (hash) relative = relative.substring(0, relative.length() - "/prefix-hash".length());
          var path = StoreCatalog.file(store.root(), relative, store.security()).toRealPath();
          store.security().validate(path);
          try (var lease = LogFileAccess.read(path);
               var file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = file.size();
            if (hash) hash(exchange, file, size, one(query, "bytes"));
            else bytes(exchange, file, size);
          }
        }
      }
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      send(exchange, 400, Map.of("error", "Invalid store request: " + e.getMessage()));
    } catch (IOException e) {
      if (exchange.getResponseCode() < 0) send(exchange, 404, Map.of("error", e.getMessage()));
      else exchange.close(); // A truncated stream is an error to the peer, never a successful shorter file.
    }
  }

  static String one(Map<String, List<String>> query, String name) {
    var values = query.get(name);
    if (values == null) return null;
    if (values.size() != 1 || values.get(0).isBlank()) throw new IllegalArgumentException("Expected one " + name);
    return values.get(0);
  }

  private static void hash(HttpExchange exchange, FileChannel file, long size, String count) throws IOException {
    if (count == null) throw new IllegalArgumentException("bytes is required");
    long length = Long.parseLong(count);
    if (length < 0) throw new IllegalArgumentException("bytes must be nonnegative");
    if (length > size) { unsatisfied(exchange, size); return; }
    try {
      var digest = MessageDigest.getInstance("SHA-256"); var block = ByteBuffer.allocate(64 * 1024);
      for (long remaining = length; remaining > 0;) {
        block.clear().limit((int) Math.min(block.capacity(), remaining));
        int n = file.read(block); if (n < 0) throw new IOException("Store file shortened during prefix hash");
        remaining -= n; block.flip(); digest.update(block);
      }
      send(exchange, 200, Map.of("bytes", length, "size_bytes", size, "sha256", HexFormat.of().formatHex(digest.digest())));
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("JDK must provide SHA-256", e); }
  }

  private static void bytes(HttpExchange exchange, FileChannel file, long size) throws IOException {
    String range = exchange.getRequestHeaders().getFirst("Range"); long first = 0, last = size - 1;
    if (range != null) {
      try {
        if (!range.matches("bytes=[0-9]*-[0-9]*")) throw new IllegalArgumentException();
        var pieces = range.substring(6).split("-", -1);
        if (pieces[0].isEmpty()) {
          long suffix = Long.parseLong(pieces[1]); if (suffix <= 0) throw new IllegalArgumentException();
          first = Math.max(0, size - suffix);
        } else {
          first = Long.parseLong(pieces[0]);
          if (!pieces[1].isEmpty()) last = Math.min(last, Long.parseLong(pieces[1]));
        }
        if (first > last || first >= size) throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) { unsatisfied(exchange, size); return; }
      exchange.getResponseHeaders().set("Content-Range", "bytes " + first + "-" + last + "/" + size);
    }
    exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    long length = last - first + 1;
    exchange.sendResponseHeaders(range == null ? 200 : 206, length == 0 ? -1 : length);
    try (var output = exchange.getResponseBody()) {
      file.position(first); var block = ByteBuffer.allocate(64 * 1024);
      for (long remaining = length; remaining > 0;) {
        block.clear().limit((int) Math.min(block.capacity(), remaining));
        int n = file.read(block); if (n < 0) throw new IOException("Store file shortened during transfer");
        output.write(block.array(), 0, n); remaining -= n;
      }
    }
  }
  private static void unsatisfied(HttpExchange exchange, long size) throws IOException {
    exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
    send(exchange, 416, Map.of("error", "Requested bytes exceed current file length"));
  }
  static void send(HttpExchange exchange, int code, Object body) throws IOException {
    byte[] bytes = StoreJson.JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(code, bytes.length);
    try (var output = exchange.getResponseBody()) { output.write(bytes); }
  }
}
