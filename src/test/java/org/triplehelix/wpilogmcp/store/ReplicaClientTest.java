/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;

class ReplicaClientTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
  static final String MASTER = "11111111-1111-4111-8111-111111111111", SESSION = "22222222-2222-4222-8222-222222222222";
  static String hello() { return "{\"protocol_version\":1,\"session_id\":\"" + SESSION + "\",\"capabilities\":[\"finalized_offers\",\"immutable_ranges\"],\"stores\":[{\"store_id\":\"" + MASTER + "\",\"master_store_id\":\"" + MASTER + "\",\"master\":true,\"format_version\":2}]}"; }
  @Test void structuredRefusalsRetainTheSuppliersCodeAndRemedy() throws Exception {
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/replicas/v1/handshake", exchange -> {
      byte[] bytes = "{\"code\":\"SESSION_EXPIRED\",\"message\":\"Handshake again to resume\",\"retryable\":true}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(409, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }); http.start();
    try {
      var failure = assertThrows(IOException.class, () -> new ReplicaClient("http://127.0.0.1:" + http.getAddress().getPort()));
      assertEquals("SESSION_EXPIRED: Handshake again to resume (HTTP 409)", failure.getMessage());
      var refusal = assertInstanceOf(ReplicaClient.Refusal.class, failure);
      assertEquals("SESSION_EXPIRED", refusal.code()); assertEquals(409, refusal.status()); assertTrue(refusal.retryable());
    } finally { http.stop(0); }
  }
  @Test void malformedAndOversizedRefusalBodiesKeepTheBoundedHttpFallback() throws Exception {
    for (String body : List.of("not json", "x".repeat(5000), "{\"code\":\"C:\\\\private\",\"message\":\"bad\"}")) {
      var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      http.createContext("/replicas/v1/handshake", exchange -> {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(409, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
      }); http.start();
      try {
        var refusal = assertThrows(ReplicaClient.Refusal.class, () -> new ReplicaClient("http://127.0.0.1:" + http.getAddress().getPort()));
        assertEquals("REPLICA_HTTP_409: supplier refused request (HTTP 409)", refusal.getMessage());
        assertFalse(refusal.retryable());
      } finally { http.stop(0); }
    }
  }
  @Test void aPeerCannotHideItsGrowingFlagDuringWireDecoding() throws Exception {
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/replicas/v1", exchange -> {
      String value = exchange.getRequestURI().getPath().endsWith("handshake") ? hello() : exchange.getRequestURI().getPath().endsWith("close") ? "{}"
          : "{\"inventory_id\":\"" + SESSION + "\",\"catalog_epoch\":\"" + MASTER + "\",\"catalog_revision\":1,\"total\":1,\"next_cursor\":null,\"offers\":[{\"identity\":{\"master_store_id\":\"" + MASTER + "\",\"file_id\":\"" + MASTER + "\",\"file_version\":\"" + SESSION + "\"},\"name\":\"capture.wpilog\",\"sha256\":\"" + "a".repeat(64) + "\",\"size_bytes\":100,\"kind\":\"wpilog\",\"completion_basis\":\"closed_capture\",\"verification_claim\":\"verified\",\"growing\":true}]}";
      exchange.getRequestBody().readAllBytes(); byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length); try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }); http.start();
    try (var client = new ReplicaClient("http://127.0.0.1:" + http.getAddress().getPort())) {
      var wire = StoreJson.JSON.toJsonTree(client.page(MASTER, null)).getAsJsonObject().getAsJsonArray("offers").get(0).getAsJsonObject();
      assertTrue(wire.has("growing") && wire.get("growing").getAsBoolean(), "the admission owner must receive, and refuse, a known growing member");
      var manager = org.triplehelix.wpilogmcp.log.LogManager.getInstance(); var previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
      try (var mirror = new LogStore(temp, manager.testGetSecurityValidator(), manager, StoreCatalog::read)) {
        String address = "http://127.0.0.1:" + http.getAddress().getPort();
        var header = new StoreManifest.Header(2, "2026-10-19T00:00:00Z", UUID.randomUUID().toString(), List.of(), Map.of(), true, List.of(),
            new StoreManifest.MirrorOrigin(MASTER, address, null, List.of(), Map.of()), new ReplicaCatalog(1, UUID.randomUUID().toString(), 1, List.of(), List.of(), List.of()));
        var io = new StoreFiles(temp, manager.testGetSecurityValidator()); io.write(temp.resolve("store.json"), header);
        var policy = new org.triplehelix.wpilogmcp.config.MirrorConfig(address, temp, 14, 20_000_000, List.of(), List.of(), 30, 0, "all", 1_048_576, List.of(), false);
        assertEquals("partial", mirror.mirror(policy, p -> {}).get().state());
        assertEquals(0, ReplicaJournal.read(temp, io).confirmations().size(), "known growing bytes cannot establish master finalization before transfer refusal");
        assertEquals(0, mirror.replicaHeader().replicas().files().size());
      } finally { manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory); }
    } finally { http.stop(0); }
  }
  @Test void aTricklingControlReplyHasAnAbsoluteDeadline() throws Exception {
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/replicas/v1/handshake", exchange -> {
      exchange.sendResponseHeaders(200, 0);
      try (var output = exchange.getResponseBody()) {
        output.write(hello().getBytes(StandardCharsets.UTF_8)); output.flush();
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < end) { output.write(' '); output.flush(); try { Thread.sleep(25); } catch (InterruptedException e) { break; } }
      } catch (IOException disconnected) { /* Client deadline closes its connection. */ }
    }); http.start();
    try {
      long start = System.nanoTime();
      var failure = assertThrows(IOException.class, () -> new ReplicaClient("http://127.0.0.1:" + http.getAddress().getPort()));
      assertTrue(failure.getMessage().contains("DEADLINE"), failure.toString());
      assertTrue(System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(11));
    } finally { http.stop(0); }
  }
}
