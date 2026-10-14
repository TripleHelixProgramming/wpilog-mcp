/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.LocalIngestion;
import org.triplehelix.wpilogmcp.store.StoreJson;

class LocalIngestionEndpointTest {
  @TempDir Path temp;
  final HttpClient client = HttpClient.newHttpClient();
  HttpResponse<String> request(HttpTransport server, String method, String route, String session, String body, String origin) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + route))
        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (session != null) builder.header("Mcp-Session-Id", session);
    if (origin != null) builder.header("Origin", origin);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
  String session(HttpTransport server) throws Exception {
    var reply = request(server, "POST", "/mcp", null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
    assertEquals(200, reply.statusCode(), reply.body());
    return reply.headers().firstValue("Mcp-Session-Id").orElseThrow();
  }
  @Test void enrollmentNeedsALiveLocalSessionPersistsAndDetectsStaleProfiles() throws Exception {
    temp = temp.toRealPath();
    var logs = LogManager.getInstance(); var old = logs.getConfiguredDirectories();
    logs.addAllowedDirectory(temp);
    var policyFile = temp.resolve("state/policy.json");
    var server = new HttpTransport(new ToolRegistry(), 0, "127.0.0.1", null, null, "http");
    server.configureLocalIngestion(policyFile);
    try {
      server.start(); var session = session(server);
      var response = request(server, "GET", "/store/local-ingestion", session, null, null);
      assertEquals(200, response.statusCode(), "Named server exposes setup status: " + response.body());
      assertEquals(0, JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("policy").get("revision").getAsInt());
      assertEquals(400, request(server, "GET", "/store/local-ingestion", null, null, null).statusCode());
      assertEquals(403, request(server, "GET", "/store/local-ingestion", session, null, "https://evil.invalid").statusCode());
      var source = Files.createDirectory(temp.resolve("source"));
      var policy = new LocalIngestion.Policy(0, true, Files.createDirectory(temp.resolve("store")).toString(), null,
          java.util.List.of(new LocalIngestion.Source(source.toString(), "copy", true, false, null)));
      var body = StoreJson.JSON.toJson(policy);
      var saved = request(server, "PUT", "/store/local-ingestion", session, body, null);
      assertEquals(200, saved.statusCode(), saved.body());
      assertTrue(Files.exists(policyFile));
      assertEquals(409, request(server, "PUT", "/store/local-ingestion", session, body, null).statusCode());
      assertEquals(202, request(server, "POST", "/store/local-ingestion/scan", session, "{}", null).statusCode());
      assertEquals(200, request(server, "GET", "/store/local-ingestion/files?offset=0&limit=10", session, null, null).statusCode());
      var catalog = request(server, "GET", "/read/v1/catalog", null, null, null);
      assertEquals(200, catalog.statusCode(), catalog.body());
      assertTrue(JsonParser.parseString(catalog.body()).getAsJsonObject().has("preferred_store_id"), catalog.body());
      request(server, "DELETE", "/mcp", session, null, null);
      assertEquals(404, request(server, "GET", "/store/local-ingestion", session, null, null).statusCode());
      assertTrue(logs.stores().localIngestion().enabled(), "Closing the editor must not stop approved syncing");
      var exited = new java.util.concurrent.atomic.AtomicBoolean();
      server.setIdleExit(Duration.ofNanos(1), () -> exited.set(true)); server.exitIfIdle();
      assertFalse(exited.get(), "An enrolled server must stay awake without editors");
      server.stop();
      server = new HttpTransport(new ToolRegistry(), 0, "127.0.0.1", null, null, "http");
      server.configureLocalIngestion(policyFile); server.start();
      assertTrue(logs.stores().localIngestion().enabled(), "Restart must restore policy and its grants");
    } finally {
      server.stop(); logs.clearAllowedDirectories(); old.forEach(logs::addAllowedDirectory);
    }
  }
  @Test void aNetworkBindAndAnUnnamedServerCannotEnroll() throws Exception {
    for (var bind : java.util.List.of("0.0.0.0", "127.0.0.1")) {
      var server = new HttpTransport(new ToolRegistry(), 0, bind, null, null);
      try { server.start(); var id = session(server);
        assertEquals(bind.equals("0.0.0.0") ? 403 : 409,
            request(server, "GET", "/store/local-ingestion", id, null, null).statusCode());
      } finally { server.stop(); }
    }
  }
}
