/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class MetricsEndpointTest {
  @Test void everyHttpServerOffersMetricsWithoutCaptureAndKeepsTheOriginCheck() throws Exception {
    var server = new HttpTransport(new ToolRegistry(), 0); server.start();
    try {
      var client = HttpClient.newHttpClient();
      var uri = URI.create("http://127.0.0.1:" + server.getPort() + "/metrics");
      var response = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("text/plain; version=0.0.4; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
      assertTrue(response.body().contains("wpilog_nt_connected 0\n"));
      assertTrue(response.body().contains("wpilog_jvm_memory_used_bytes{area=\"heap\"}"));
      assertEquals(403, client.send(HttpRequest.newBuilder(uri).header("Origin", "http://untrusted.test").build(),
          HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(405, client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(404, client.send(HttpRequest.newBuilder(uri.resolve("/metrics/stray")).build(),
          HttpResponse.BodyHandlers.ofString()).statusCode());
      var health = client.send(HttpRequest.newBuilder(uri.resolve("/health")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, health.statusCode()); assertEquals(1, health.body().lines().count());
      assertEquals("ok", com.google.gson.JsonParser.parseString(health.body()).getAsJsonObject().get("status").getAsString());
    } finally { server.stop(); }
  }
}
