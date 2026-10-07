/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.triplehelix.wpilogmcp.config.ClientLeases;

/**
 * Claude's registration keeps a URL only. Its local bridge forwards to exactly the leased MCP
 * endpoint; passwords live in SecretStorage and memory, never a command, URL, result or config.
 * Redirects and arbitrary target paths are refused. The transport enforces loopback and Origin.
 */
final class PitMcpEndpoint {
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private static final List<String> REQUEST_HEADERS = List.of("Content-Type", "Accept", "Mcp-Session-Id", "MCP-Protocol-Version", "Last-Event-ID");
  private static final List<String> RESPONSE_HEADERS = List.of("Content-Type", "Mcp-Session-Id", "MCP-Protocol-Version", "Cache-Control");
  private PitMcpEndpoint() {}
  static void handle(HttpExchange exchange) throws IOException {
    URI endpoint;
    try {
      String query = exchange.getRequestURI().getRawQuery();
      if (!exchange.getRequestURI().getPath().equals("/pit-mcp") || query == null || !query.startsWith("url=") || query.contains("&")) throw new IllegalArgumentException();
      endpoint = URI.create(URLDecoder.decode(query.substring(4), StandardCharsets.UTF_8));
    } catch (RuntimeException e) { reply(exchange, 400, "A registered pit MCP URL is required"); return; }
    String auth = ClientLeases.getInstance().pitMcpAuthorization(endpoint);
    if (auth == null) { reply(exchange, 403, "No active pit credential lease; open VS Code and register the credential"); return; }
    String method = exchange.getRequestMethod();
    if (!List.of("POST", "GET", "DELETE").contains(method)) { reply(exchange, 405, "Method not allowed"); return; }
    byte[] body = exchange.getRequestBody().readNBytes(4 * 1024 * 1024 + 1);
    if (body.length > 4 * 1024 * 1024) { reply(exchange, 413, "MCP request exceeds 4 MiB"); return; }
    var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMinutes(10)).header("Authorization", auth)
        .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
    REQUEST_HEADERS.forEach(name -> {
      String value = exchange.getRequestHeaders().getFirst(name); if (value != null) request.header(name, value);
    });
    try {
      var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        int status = response.statusCode();
        if (status >= 300 && status < 400) { reply(exchange, 502, "Pit proxy redirected; configure its final URL"); return; }
        RESPONSE_HEADERS.forEach(name -> response.headers().firstValue(name).ifPresent(value -> exchange.getResponseHeaders().set(name, value)));
        exchange.sendResponseHeaders(status, status == 204 ? -1 : 0);
        try (var output = exchange.getResponseBody()) { if (status != 204) input.transferTo(output); }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); reply(exchange, 502, "Pit request interrupted");
    } catch (IOException e) {
      // An exception may include a proxy's message. Never echo request headers or its body.
      if (exchange.getResponseCode() == -1) reply(exchange, 502, "Pit server could not be reached");
    } finally { exchange.close(); }
  }
  private static void reply(HttpExchange exchange, int status, String message) throws IOException {
    byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) { output.write(bytes); }
  }
}
