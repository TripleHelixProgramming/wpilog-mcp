/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tba.TbaClient;

/**
 * get_tba_match_data against a local server replaying The Blue Alliance's v3 API (responses in
 * TBA's documented schema, hand-written: no API key is needed to run this).
 */
@DisplayName("get_tba_match_data on replayed TBA responses")
class TbaReplayTest {

  static HttpServer server;
  static ToolRegistry registry;

  static final String MATCH = """
      {"key": "2026vache_qm10", "comp_level": "qm", "set_number": 1, "match_number": 10,
       "event_key": "2026vache", "time": 1774112400, "actual_time": 1774112520,
       "winning_alliance": "blue",
       "alliances": {
         "red": {"score": 88, "team_keys": ["frc2363", "frc1234B", "frc5567"],
                 "surrogate_team_keys": [], "dq_team_keys": []},
         "blue": {"score": 104, "team_keys": ["frc401", "frc612", "frc1418"],
                  "surrogate_team_keys": [], "dq_team_keys": []}},
       "score_breakdown": {
         "red": {"autoTowerPoints": 15, "teleopFuelPoints": 48, "endGameTowerPoints": 20,
                 "foulPoints": 5, "totalPoints": 88, "autoTowerLevel": "Level1"},
         "blue": {"autoTowerPoints": 15, "teleopFuelPoints": 60, "endGameTowerPoints": 29,
                  "foulPoints": 0, "totalPoints": 104, "autoTowerLevel": "Level2"}}}
      """;
  static final String EVENT = """
      {"key": "2026vache", "event_code": "vache", "name": "FIRST Chesapeake Event",
       "year": 2026, "timezone": "America/New_York"}
      """;

  static final Map<String, Object[]> ROUTES = Map.of(
      "/api/v3/match/2026vache_qm10", new Object[] {200, MATCH},
      "/api/v3/event/2026vache", new Object[] {200, EVENT},
      "/api/v3/events/2026", new Object[] {200, "[" + EVENT + "]"},
      "/api/v3/match/2026rejected_qm1", new Object[] {401, "{\"Error\": \"bad key\"}"});

  @BeforeAll
  static void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      var route = ROUTES.getOrDefault(exchange.getRequestURI().getPath(),
          new Object[] {404, "{\"Error\": \"not found\"}"});
      var body = ((String) route[1]).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders((int) route[0], body.length);
      try (var out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.start();
    var client = TbaClient.getInstance();
    client.configure("replay-key");
    client.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3");
    registry = new ToolRegistry();
    TbaTools.registerAll(registry);
  }

  @AfterAll
  static void stop() {
    var client = TbaClient.getInstance();
    client.setBaseUrl(null);
    client.configure(null);
    server.stop(0);
  }

  static JsonObject call(String event, String type, int number, Integer team) throws Exception {
    var args = new JsonObject();
    args.addProperty("year", 2026);
    args.addProperty("event_code", event);
    args.addProperty("match_type", type);
    args.addProperty("match_number", number);
    if (team != null) args.addProperty("team_number", team);
    return registry.getTool("get_tba_match_data").execute(args).getAsJsonObject();
  }

  @Test
  @DisplayName("a match: alliances (a B team kept as '1234B'), your alliance, points subtotals")
  void found() throws Exception {
    var r = call("vache", "qm", 10, 2363);
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    assertEquals("2026vache_qm10", r.get("match_key").getAsString());
    assertEquals("blue", r.get("winning_alliance").getAsString());
    var red = r.getAsJsonObject("alliances").getAsJsonObject("red");
    assertEquals(88, red.get("score").getAsInt());
    assertEquals("[2363,\"1234B\",5567]", red.getAsJsonArray("teams").toString());
    assertTrue(red.get("your_alliance").getAsBoolean());
    assertFalse(red.get("won").getAsBoolean());
    // Every ...Points subtotal of any season's breakdown, and nothing else
    var breakdown = r.getAsJsonObject("score_breakdown").getAsJsonObject("blue");
    assertEquals(60, breakdown.get("teleopFuelPoints").getAsInt());
    assertEquals(104, breakdown.get("totalPoints").getAsInt());
    assertFalse(breakdown.has("autoTowerLevel"));
  }

  @Test
  @DisplayName("a match the event does not have: no_match, with suggestions")
  void matchNotFound() throws Exception {
    var r = call("vache", "qm", 99, null);
    assertEquals("no_match", r.get("status").getAsString(), r.toString());
    assertFalse(r.get("success").getAsBoolean());
    assertTrue(r.get("reason").getAsString().contains("exists but match qm 99"));
    assertTrue(r.has("suggestions"));
  }

  @Test
  @DisplayName("an event TBA does not know: no_match, saying the event code is wrong")
  void eventNotFound() throws Exception {
    var r = call("nope", "qm", 1, null);
    assertEquals("no_match", r.get("status").getAsString(), r.toString());
    assertTrue(r.get("reason").getAsString().contains("Event 'nope' not found"));
  }

  @Test
  @DisplayName("a rejected API key is an error, not a missing match")
  void rejectedKey() throws Exception {
    var r = call("rejected", "qm", 1, null);
    assertEquals("error", r.get("status").getAsString(), r.toString());
    assertTrue(r.get("error").getAsString().contains("HTTP 401 (the API key was rejected)"),
        r.toString());
  }
}
