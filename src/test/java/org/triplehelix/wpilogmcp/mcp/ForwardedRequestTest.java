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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.ClientLeases;
import org.triplehelix.wpilogmcp.visualization.ServerIdentity;

/** A same-host reverse proxy connects from loopback, so the pit guide's nginx block once made
 * everyone past its login a local caller: directory leases, pit forwarding, imports by server
 * path, sync and mirror controls, local ingestion, the visualization routes and stop. A request
 * carrying a forwarding header must reach none of them, and the same request sent directly must
 * still pass each gate. */
class ForwardedRequestTest {
  @TempDir Path temp;
  static final List<String[]> FORWARDING = List.of(
      new String[] {"Forwarded", "for=192.0.2.7;proto=https"}, new String[] {"X-Forwarded-For", "192.0.2.7"},
      new String[] {"X-Forwarded-Host", "pit.example"}, new String[] {"X-Forwarded-Proto", "https"},
      new String[] {"X-Real-IP", "192.0.2.7"}, new String[] {"Via", "1.1 pit-proxy"});
  record Route(String method, String path, String body, boolean session) {}

  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  HttpResponse<String> send(String base, Route route, String session, String[] header) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + route.path())).timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/json").header(HttpTransport.STOP_TOKEN_HEADER, "fixture-token")
        .method(route.method(), route.body() == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(route.body()));
    if (route.session()) builder.header("Mcp-Session-Id", session);
    if (header != null) builder.header(header[0], header[1]);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test void forwardedRequestsReachNoLocalControlWhileDirectOnesPassEveryGate() throws Exception {
    var registry = new ToolRegistry(); registry.serverIdentity(ServerIdentity.ephemeral());
    org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    var transport = new HttpTransport(registry, 0, "127.0.0.1", null, null);
    var stopped = new AtomicBoolean();
    transport.setStop("fixture-token", () -> stopped.set(true));
    var lease = temp.toRealPath();
    try {
      transport.start(); String base = "http://127.0.0.1:" + transport.getPort();
      var init = http.send(HttpRequest.newBuilder(URI.create(base + "/mcp")).POST(HttpRequest.BodyPublishers.ofString(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")).build(), HttpResponse.BodyHandlers.ofString());
      String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
      String visualizationCall = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"visualization_capabilities_get\",\"arguments\":{\"api_version\":1}}}";
      // A lease for the bridge target lets a direct /pit-mcp reach its forwarding, not a missing lease.
      assertEquals(200, send(base, new Route("POST", "/pit-credential", "{\"url\":\"http://127.0.0.1:9/mcp\",\"authorization\":\"Basic Zm9vOmJhcg==\"}", true), session, null).statusCode());
      var routes = List.of(
          new Route("GET", "/pit-mcp?url=http%3A%2F%2F127.0.0.1%3A9%2Fmcp", null, true),
          new Route("POST", "/directories", "{\"paths\":[" + new com.google.gson.JsonPrimitive(lease.toString()) + "]}", true),
          new Route("POST", "/pit-credential", "{\"url\":\"http://127.0.0.1:9/other\",\"authorization\":\"Basic Zm9vOmJhcg==\"}", true),
          new Route("POST", "/store/import", "{\"paths\":[],\"move\":false}", false),
          new Route("POST", "/store/sync", "{}", false),
          new Route("GET", "/store/mirror", null, false),
          new Route("GET", "/store/local-ingestion", null, true),
          new Route("GET", "/visualizations/v1/capabilities", null, true),
          new Route("POST", "/read/v1/follows", "{\"view_binding\":{\"view_id\":\"33333333-3333-4333-8333-333333333333\",\"expected_revision\":1}}", true),
          new Route("POST", "/mcp", visualizationCall, true),
          new Route("POST", "/stop", null, false));
      var checks = new ArrayList<Executable>();
      for (var route : routes) for (var header : FORWARDING) {
        var reply = send(base, route, session, header); String name = route.method() + " " + route.path() + " with " + header[0];
        if (route.path().equals("/mcp")) {
          var text = JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("result")
              .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
          checks.add(() -> assertTrue(text.contains("ACCESS_DENIED"), name + " ran a local-only tool: " + text));
        } else checks.add(() -> assertTrue(reply.statusCode() == 403 && (reply.body().contains("forwarded by a proxy") || reply.body().contains("ACCESS_DENIED")),
            name + " passed a local gate: " + reply.statusCode() + " " + reply.body()));
      }
      checks.add(() -> assertFalse(ClientLeases.getInstance().directories().stream().anyMatch(d -> d.path().equals(lease)),
          "a forwarded registration leased a directory"));
      checks.add(() -> assertFalse(stopped.get(), "a forwarded request stopped the server"));
      assertAll(checks);

      // Sent directly, every route still passes its local gate; only its own validation remains.
      for (var route : routes) {
        if (route.path().equals("/stop")) continue;
        var reply = send(base, route, session, null); String name = route.method() + " " + route.path() + " sent directly";
        assertFalse(reply.body().contains("forwarded by a proxy"), name + " was treated as forwarded: " + reply.body());
        if (route.path().equals("/mcp")) assertFalse(reply.body().contains("ACCESS_DENIED"), name + ": " + reply.body());
        if (List.of("/directories", "/pit-credential", "/store/mirror", "/visualizations/v1/capabilities").contains(route.path()))
          assertEquals(200, reply.statusCode(), name + ": " + reply.body());
      }
      assertTrue(ClientLeases.getInstance().directories().stream().anyMatch(d -> d.path().equals(lease)), "the direct registration leased its directory");
      assertEquals(200, send(base, routes.get(routes.size() - 1), session, null).statusCode());
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (!stopped.get() && System.nanoTime() < deadline) Thread.sleep(10);
      assertTrue(stopped.get(), "a direct stop request with its token still stops the server");
    } finally { transport.stop(); }
  }
}
