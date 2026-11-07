/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.sync.HttpRemoteFiles;

/** Only synthetic proxy credentials; the same lease serves the mirror and secret-free URL bridge. */
class PitCredentialTest {
  @Test void registrationRefusesCredentialsEmbeddedInTheUrl() throws Exception {
    var leases = org.triplehelix.wpilogmcp.config.ClientLeases.getInstance();
    var error = assertThrows(IllegalArgumentException.class, () -> leases.registerPit(
        "synthetic-session", "http://synthetic:password@127.0.0.1:9000/mcp", "Basic eDp5"));
    assertTrue(error.getMessage().contains("without credentials"), error.getMessage());
    assertFalse(error.getMessage().contains("synthetic:password"));
    var local = new HttpTransport(new ToolRegistry(), 0); local.start();
    try {
      var refused = request(local, "POST", "/pit-credential", initialize(local),
          body("http://synthetic:password@127.0.0.1:9000/mcp", "Basic eDp5"));
      assertEquals(400, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("without credentials"), refused.body());
      assertFalse(refused.body().contains("synthetic:password"));
    } finally { local.stop(); }
  }
  final HttpClient http = HttpClient.newHttpClient();
  HttpResponse<String> request(HttpTransport server, String method, String path, String session, String body, String... headers) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path)).timeout(Duration.ofSeconds(10))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (session != null) builder.header("Mcp-Session-Id", session);
    for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
  String initialize(HttpTransport server) throws Exception {
    return request(server, "POST", "/mcp", null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
        .headers().firstValue("Mcp-Session-Id").orElseThrow();
  }
  String body(String url, String authorization) {
    var b = new JsonObject(); b.addProperty("url", url); b.addProperty("authorization", authorization); return b.toString();
  }
  @Test void aSessionOwnsTheSecretAndMirrorAndBridgeUseItOnlyForThatOrigin() throws Exception {
    String secret = "Basic " + java.util.Base64.getEncoder().encodeToString("synthetic:password".getBytes(StandardCharsets.UTF_8));
    var seen = new CopyOnWriteArrayList<String>();
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/", exchange -> {
      seen.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      String response = exchange.getRequestURI().getPath().equals("/store")
          ? "{\"id\":\"synthetic-store\",\"format_version\":1,\"mirror\":false}"
          : exchange.getRequestMethod() + ":" + exchange.getRequestHeaders().getFirst("Mcp-Session-Id") + ":" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Mcp-Session-Id", "upstream-session");
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.getResponseHeaders().set("Set-Cookie", "must-not-forward");
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }); upstream.start();
    var local = new HttpTransport(new ToolRegistry(), 0); local.start();
    String origin = "http://127.0.0.1:" + upstream.getAddress().getPort(), endpoint = origin + "/mcp";
    String route = "/pit-mcp?url=" + URLEncoder.encode(endpoint, StandardCharsets.UTF_8);
    try {
      String session = initialize(local);
      assertEquals(403, request(local, "POST", route, null, "{}" ).statusCode());
      assertEquals(200, request(local, "POST", "/pit-credential", session, body(endpoint, secret)).statusCode());
      try (var remote = new HttpRemoteFiles(origin)) { assertEquals("synthetic-store", remote.description().id()); }
      var response = request(local, "POST", route, "remote-session", "hello");
      assertEquals(200, response.statusCode()); assertEquals("POST:remote-session:hello", response.body());
      assertEquals("upstream-session", response.headers().firstValue("Mcp-Session-Id").orElseThrow());
      assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
      assertEquals(List.of(secret, secret), seen);
      assertFalse(response.body().contains(secret));
      assertEquals(403, request(local, "GET", "/pit-mcp?url=" + URLEncoder.encode(origin + "/another", StandardCharsets.UTF_8), null, null).statusCode());
      String later = initialize(local);
      assertEquals(200, request(local, "POST", "/pit-credential", later, body(endpoint, "Basic eDp5")).statusCode());
      assertEquals(200, request(local, "GET", route, null, null).statusCode()); assertEquals("Basic eDp5", seen.get(2));
      request(local, "DELETE", "/mcp", later, null);
      request(local, "GET", route, null, null); assertEquals(secret, seen.get(3));
      assertEquals(200, request(local, "POST", "/pit-credential", session, body(endpoint, null)).statusCode());
      assertEquals(403, request(local, "GET", route, null, null).statusCode());
      try (var remote = new HttpRemoteFiles(origin)) { remote.description(); } assertEquals("null", seen.get(4));
      request(local, "POST", "/pit-credential", session, body(endpoint, secret));
      // Expire strictly past the last access even if Windows returns the same wall-clock tick.
      assertEquals(1, local.expireSessions(Duration.ofNanos(-1)));
      assertEquals(403, request(local, "GET", route, null, null).statusCode());
    } finally { local.stop(); upstream.stop(0); }
  }
  @Test void redirectsCannotForwardAPitSecretToAnotherServer() throws Exception {
    var reached = new java.util.concurrent.atomic.AtomicInteger();
    var other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    other.createContext("/", exchange -> { reached.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); }); other.start();
    var redirect = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    redirect.createContext("/", exchange -> {
      exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/mcp");
      exchange.sendResponseHeaders(302, -1); exchange.close();
    }); redirect.start();
    var local = new HttpTransport(new ToolRegistry(), 0); local.start();
    String endpoint = "http://127.0.0.1:" + redirect.getAddress().getPort() + "/mcp";
    try {
      assertEquals(200, request(local, "POST", "/pit-credential", initialize(local), body(endpoint, "Basic eDp5")).statusCode());
      assertEquals(502, request(local, "GET", "/pit-mcp?url=" + URLEncoder.encode(endpoint, StandardCharsets.UTF_8), null, null).statusCode());
      try (var remote = new HttpRemoteFiles(endpoint.replace("/mcp", ""))) {
        assertThrows(java.io.IOException.class, remote::description);
      }
      assertNull(org.triplehelix.wpilogmcp.config.ClientLeases.getInstance().pitAuthorization(URI.create("http://127.0.0.1:" + other.getAddress().getPort())));
      assertEquals(0, reached.get());
      assertThrows(IllegalArgumentException.class, () -> new HttpRemoteFiles(endpoint, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()));
    } finally { local.stop(); redirect.stop(0); other.stop(0); }
  }

  @Test void registrationAndForwardingRejectOriginAndNetworkBindingsWithoutEchoingSecrets() throws Exception {
    var local = new HttpTransport(new ToolRegistry(), 0); local.start();
    try {
      String session = initialize(local);
      String secret = "never-echo-this";
      var malformed = request(local, "POST", "/pit-credential", session, body("http://127.0.0.1/mcp", secret));
      assertEquals(400, malformed.statusCode()); assertFalse(malformed.body().contains(secret));
      assertEquals(404, request(local, "POST", "/pit-credential", "expired", body("http://127.0.0.1/mcp", "Basic eDp5")).statusCode());
      for (String route : List.of("/pit-credential", "/pit-mcp"))
        assertEquals(403, request(local, "POST", route, session, "{}", "Origin", "https://untrusted.example").statusCode());
    } finally { local.stop(); }
    var network = new HttpTransport(new ToolRegistry(), 0, "0.0.0.0", java.util.Set.of(), "/mcp"); network.start();
    try {
      for (String route : List.of("/pit-credential", "/pit-mcp")) assertEquals(403, request(network, "POST", route, null, "{}").statusCode());
    } finally { network.stop(); }
  }
}
