/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AccessDeniedException;
import org.junit.jupiter.api.Test;

class CallerContextTest {
  private ToolRegistry registry() {
    var registry = new ToolRegistry();
    registry.registerTool(new ToolRegistry.Tool() {
      public String name() { return "authority_probe"; }
      public String description() { return "Synthetic transport authority probe"; }
      public JsonObject inputSchema() { return new JsonObject(); }
      public JsonElement execute(JsonObject args) {
        var result = new JsonObject(); result.addProperty("authority", CallerContext.current().name()); return result;
      }
    });
    return registry;
  }
  private static final String CALL = """
      {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"authority_probe","arguments":{"trusted_local":true}}}
      """;

  @Test void omittedContextAndSpoofedArgumentsCannotGrantTrust() throws Exception {
    assertThrows(AccessDeniedException.class, () -> CallerContext.current().requireLocal());
    var handler = new McpMessageHandler(registry());
    assertTrue(handler.handleMessage(JsonParser.parseString(CALL).getAsJsonObject()).response().toString().contains("UNTRUSTED"));
  }

  @Test void requestScopeIsClearedOnSuccessAndInvalidRequestsIncludingNullSessions() {
    var handler = new McpMessageHandler(registry());
    var call = JsonParser.parseString(CALL).getAsJsonObject();
    assertTrue(handler.handleMessage(call, null, CallerContext.Authority.STDIO_LOCAL).response().toString().contains("STDIO_LOCAL"));
    assertEquals(CallerContext.Authority.UNTRUSTED, CallerContext.current());
    assertNull(SessionContext.current());
    handler.handleMessage(new JsonObject(), null, CallerContext.Authority.STDIO_LOCAL);
    assertEquals(CallerContext.Authority.UNTRUSTED, CallerContext.current());
    assertTrue(handler.handleMessage(call).response().toString().contains("UNTRUSTED"));
  }

  @Test void directStdioSuppliesExplicitLocalAuthority() throws Exception {
    var input = System.in; var output = System.out;
    var bytes = new java.io.ByteArrayOutputStream();
    try {
      System.setIn(new java.io.ByteArrayInputStream(CALL.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      System.setOut(new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8));
      new McpServer(registry()).run();
    } finally { System.setIn(input); System.setOut(output); }
    assertTrue(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("STDIO_LOCAL"));
    assertEquals(CallerContext.Authority.UNTRUSTED, CallerContext.current());
  }

  @Test void authorityRequiresEveryTransportFact() throws Exception {
    var loopback = InetAddress.getByName("127.0.0.1");
    var remote = InetAddress.getByName("192.0.2.1");
    var wildcard = InetAddress.getByName("0.0.0.0");
    assertEquals(CallerContext.Authority.HTTP_LOCAL, CallerContext.http(loopback, loopback, true, false));
    for (var authority : java.util.List.of(CallerContext.http(loopback, loopback, false, false),
        CallerContext.http(null, loopback, true, false)))
      assertEquals(CallerContext.Authority.UNTRUSTED, authority);
    for (var authority : java.util.List.of(CallerContext.http(wildcard, loopback, true, false),
        CallerContext.http(loopback, remote, true, false), CallerContext.http(loopback, loopback, true, true))) {
      assertEquals(CallerContext.Authority.HTTP_REMOTE, authority);
      assertThrows(java.nio.file.AccessDeniedException.class, authority::requireLocal);
    }
  }

  @Test void realLoopbackSingleAndBatchRequestsCarryAuthorityButWildcardListenerDoesNot() throws Exception {
    for (String bind : java.util.List.of("127.0.0.1", "0.0.0.0")) {
      var transport = new HttpTransport(registry(), 0, bind, null, null);
      try {
        transport.start();
        var client = HttpClient.newHttpClient();
        var uri = URI.create("http://127.0.0.1:" + transport.getPort() + "/mcp");
        var init = client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")).build(), HttpResponse.BodyHandlers.ofString());
        var session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        for (var body : java.util.List.of(CALL, "[" + CALL + "," + CALL + "]")) {
          var response = client.send(HttpRequest.newBuilder(uri).header("Mcp-Session-Id", session)
              .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          assertTrue(response.body().contains(bind.equals("127.0.0.1") ? "HTTP_LOCAL" : "HTTP_REMOTE"), response.body());
          // A same-host proxy connects from loopback, so a forwarding header, even one naming
          // loopback, withdraws local trust and never grants it.
          var forwarded = client.send(HttpRequest.newBuilder(uri).header("Mcp-Session-Id", session)
              .header("X-Forwarded-For", "127.0.0.1").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
              HttpResponse.BodyHandlers.ofString());
          assertEquals(200, forwarded.statusCode());
          assertTrue(forwarded.body().contains("HTTP_REMOTE") && !forwarded.body().contains("HTTP_LOCAL"), forwarded.body());
        }
        var rejected = client.send(HttpRequest.newBuilder(uri).header("Mcp-Session-Id", session)
            .header("Origin", "https://untrusted.invalid").POST(HttpRequest.BodyPublishers.ofString(CALL)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(403, rejected.statusCode());
      } finally { transport.stop(); }
    }
  }
}
