/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.mcp.*;
import org.triplehelix.wpilogmcp.tools.VisualizationTools;

class LibraryTransportCapacityTest {
  @TempDir Path temp;
  final HttpClient http = HttpClient.newHttpClient();
  HttpRequest request(String base, String route, String body, String session) {
    var request = HttpRequest.newBuilder(URI.create(base + route)).timeout(Duration.ofSeconds(5));
    if (session != null) request.header("Mcp-Session-Id", session);
    return body == null ? request.GET().build() : request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
  }
  JsonObject rpc(String tool, JsonObject arguments) {
    var rpc = new JsonObject(); rpc.addProperty("jsonrpc", "2.0"); rpc.addProperty("id", 1); rpc.addProperty("method", "tools/call");
    var params = new JsonObject(); params.addProperty("name", tool); params.add("arguments", arguments); rpc.add("params", params); return rpc;
  }
  @Test void sharedHttpMcpLibraryPressureKeepsControlUsableAndChargesBlockedDiskWork() throws Exception {
    var registry = new ToolRegistry(); registry.serverIdentity(ServerIdentity.open(temp.toRealPath().resolve("state"))); VisualizationTools.registerAll(registry);
    var workspace = registry.visualizations(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    workspace.library.beforeWrite = () -> { entered.countDown(); try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } };
    var server = new HttpTransport(registry, 0);
    try {
      server.start(); String base = "http://127.0.0.1:" + server.getPort();
      var init = http.send(request(base, "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"library\",\"version\":\"1\"}}}", null), HttpResponse.BodyHandlers.ofString());
      String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var calls = new ArrayList<CompletableFuture<HttpResponse<String>>>();
      for (int i = 0; i < 4; i++) {
        var create = new JsonObject(); create.addProperty("api_version", 2); create.addProperty("request_id", UUID.randomUUID().toString()); create.addProperty("name", "Synthetic " + i); create.addProperty("description", "");
        create.addProperty("configuration_json", VisualizationContracts.blank().toString());
        calls.add(http.sendAsync(request(base, i < 2 ? "/visualizations/v2/templates/create" : "/mcp", i < 2 ? create.toString() : rpc("visualization_template_create", create).toString(), session), HttpResponse.BodyHandlers.ofString()));
      }
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      // A request reserves its reply before its library call charges input, so the fourth may still be between them.
      RecordingInventoriesTest.await(() -> workspace.inventories.responseBytes() == 8 << 20
          && workspace.library.reservedInputBytes() == VisualizationLibrary.MAX_INPUT_BYTES);
      assertEquals(8 << 20, workspace.inventories.responseBytes()); assertEquals(VisualizationLibrary.MAX_INPUT_BYTES, workspace.library.reservedInputBytes());
      var list = new JsonObject(); list.addProperty("api_version", 2); list.addProperty("kind", "view");
      var refused = http.send(request(base, "/visualizations/v2/library/list", list.toString(), session), HttpResponse.BodyHandlers.ofString());
      assertEquals(413, refused.statusCode()); assertTrue(JsonParser.parseString(refused.body()).getAsJsonObject().get("retryable").getAsBoolean());
      var oversized = rpc("visualization_library_list", list); oversized.addProperty("id", "x".repeat(80 << 10));
      var large = http.send(request(base, "/mcp", oversized.toString(), session), HttpResponse.BodyHandlers.ofString());
      assertTrue(large.body().length() < 4096); assertTrue(JsonParser.parseString(large.body()).getAsJsonObject().get("id").isJsonNull());
      assertEquals(200, http.send(request(base, "/health", null, null), HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(200, http.send(request(base, "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", session), HttpResponse.BodyHandlers.ofString()).statusCode());
      release.countDown(); for (var call : calls) assertEquals(200, call.get(5, TimeUnit.SECONDS).statusCode());
      RecordingInventoriesTest.await(() -> workspace.inventories.responseBytes() == 0 && workspace.library.reservedInputBytes() == 0);
      var padded = http.send(request(base, "/visualizations/v2/library/list", list + " ".repeat(70 << 10), session), HttpResponse.BodyHandlers.ofString());
      assertEquals(413, padded.statusCode());
      for (String value : List.of("x".repeat(80 << 10), "界".repeat(40 << 10))) {
        var create = new JsonObject(); create.addProperty("api_version", 2); create.addProperty("request_id", UUID.randomUUID().toString());
        create.addProperty("name", "Oversized ID"); create.addProperty("description", ""); create.addProperty("configuration_json", VisualizationContracts.blank().toString());
        var message = rpc("visualization_template_create", create); message.addProperty("id", value);
        var refusedId = http.send(request(base, "/mcp", message.toString(), session), HttpResponse.BodyHandlers.ofString());
        assertTrue(refusedId.body().length() < 4096); assertTrue(JsonParser.parseString(refusedId.body()).getAsJsonObject().get("id").isJsonNull());
      }
      assertEquals(4, workspace.library.list(CallerContext.Authority.STDIO_LOCAL, VisualizationLibraryTest.listRequest("template", 256)).get().get("total_matching").getAsInt());
    } finally { release.countDown(); server.stop(); workspace.close(); }
  }
}
