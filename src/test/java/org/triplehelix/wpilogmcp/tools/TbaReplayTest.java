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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogDirectory.LogFileInfo;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tba.TbaClient;
import org.triplehelix.wpilogmcp.tba.TbaEnrichment;
import org.triplehelix.wpilogmcp.tba.TbaUnavailableException;

/**
 * get_tba_match_data, get_tba_status, and the listing's enrichment against a local server
 * replaying The Blue Alliance's v3 API (responses in TBA's documented schema, hand-written: no
 * API key is needed to run this).
 */
@DisplayName("TBA tools on replayed TBA responses")
class TbaReplayTest {
  static HttpServer server;
  static ToolRegistry registry;
  static final String KEY = "replay-key";

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
  static final String EVENT_2022 = EVENT.replace("2026", "2022");

  /** 2026-03-21, in seconds since the epoch: the team's three playoff matches. */
  static final long SF4_TIME = 1774116000L; // 18:00 UTC
  static final long SF8_TIME = 1774120800L; // 19:20 UTC
  static final long F1_TIME = 1774125000L; // 20:30 UTC

  /** A playoff match with team 2363 on the winning red alliance. */
  static String playoff(String key, String level, int set, int number, long actualTime) {
    return "{\"key\": \"" + key + "\", \"comp_level\": \"" + level + "\", \"set_number\": "
        + set + ", \"match_number\": " + number + ", \"event_key\": \""
        + key.substring(0, key.indexOf('_')) + "\", \"time\": " + (actualTime - 300)
        + ", \"actual_time\": " + actualTime + ", \"winning_alliance\": \"red\", "
        + "\"alliances\": {\"red\": {\"score\": 120, \"team_keys\": [\"frc2363\", \"frc401\", "
        + "\"frc612\"], \"surrogate_team_keys\": [], \"dq_team_keys\": []}, "
        + "\"blue\": {\"score\": 95, \"team_keys\": [\"frc1418\", \"frc5567\", \"frc1234\"], "
        + "\"surrogate_team_keys\": [], \"dq_team_keys\": []}}}";
  }

  static final String SF4 = playoff("2026vache_sf4m1", "sf", 4, 1, SF4_TIME);
  static final String SF8 = playoff("2026vache_sf8m1", "sf", 8, 1, SF8_TIME);
  static final String F1 = playoff("2026vache_f1m1", "f", 1, 1, F1_TIME);
  static final String STATUS = """
      {"current_season": 2026, "max_season": 2026, "is_datafeed_down": false,
       "down_events": [], "ios": {"min_app_version": 1, "latest_app_version": 2},
       "android": {"min_app_version": 1, "latest_app_version": 2}}
      """;

  static final Map<String, Object[]> ROUTES = Map.ofEntries(
      Map.entry("/api/v3/match/2026vache_qm10", new Object[] {200, MATCH}),
      Map.entry("/api/v3/match/2026vache_sf4m1", new Object[] {200, SF4}),
      Map.entry("/api/v3/event/2026vache", new Object[] {200, EVENT}),
      Map.entry("/api/v3/event/2026vache/matches",
          new Object[] {200, "[" + SF4 + "," + SF8 + "," + F1 + "]"}),
      Map.entry("/api/v3/event/2022vache", new Object[] {200, EVENT_2022}),
      Map.entry("/api/v3/event/2022vache/matches", new Object[] {200, "["
          + playoff("2022vache_qf1m1", "qf", 1, 1, 1650000000L) + ","
          + playoff("2022vache_sf1m1", "sf", 1, 1, 1650003600L) + ","
          + playoff("2022vache_f1m1", "f", 1, 1, 1650007200L) + "]"}),
      Map.entry("/api/v3/events/2026", new Object[] {200, "[" + EVENT + "]"}),
      Map.entry("/api/v3/status", new Object[] {200, STATUS}),
      Map.entry("/api/v3/match/2026rejected_qm1", new Object[] {401, "{\"Error\": \"bad key\"}"}));

  @BeforeAll
  static void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      var route = KEY.equals(exchange.getRequestHeaders().getFirst("X-TBA-Auth-Key"))
          ? ROUTES.getOrDefault(exchange.getRequestURI().getPath(),
              new Object[] {404, "{\"Error\": \"not found\"}"})
          : new Object[] {401, "{\"Error\": \"bad key\"}"};
      var body = ((String) route[1]).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders((int) route[0], body.length);
      try (var out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.start();
    var client = TbaClient.getInstance();
    client.configure(KEY);
    client.setBaseUrl(replayUrl());
    registry = new ToolRegistry();
    WpilogTools.registerAll(registry); // list_available_logs
    TbaTools.registerAll(registry);
  }

  static String replayUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3";
  }

  @AfterAll
  static void stop() {
    var client = TbaClient.getInstance();
    client.setBaseUrl(null);
    client.configure(null);
    server.stop(0);
  }

  static JsonObject call(String event, String type, int number, Integer team) throws Exception {
    return call(2026, event, type, number, team);
  }

  static JsonObject call(int year, String event, String type, int number, Integer team)
      throws Exception {
    var args = new JsonObject();
    args.addProperty("year", year);
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
    assertEquals("direct", r.get("lookup_method").getAsString());
    assertFalse(r.has("lookup_basis"));
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

  @Test
  @DisplayName("Elimination 4 in 2026 is double-elimination bracket match 4: sf4m1, labeled")
  void eliminationBracket() throws Exception {
    var r = call("vache", "Elimination", 4, 2363);
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    assertEquals("2026vache_sf4m1", r.get("match_key").getAsString());
    assertEquals("double_elimination_bracket", r.get("lookup_method").getAsString());
    assertTrue(r.get("lookup_basis").getAsString().contains("bracket match 4"), r.toString());
    assertTrue(r.getAsJsonObject("alliances").getAsJsonObject("red").get("your_alliance")
        .getAsBoolean());
  }

  @Test
  @DisplayName("Elimination 14 in 2026 names no bracket match: no_match, pointing at the finals")
  void eliminationFinalsNeedTheirNumber() throws Exception {
    var r = call("vache", "Elimination", 14, 2363);
    assertEquals("no_match", r.get("status").getAsString(), r.toString());
    var reason = r.get("reason").getAsString();
    assertTrue(reason.contains("finals carry no bracket number"), reason);
    assertTrue(reason.contains("match_type f"), reason);
  }

  @Test
  @DisplayName("a bracket match the event does not have: no_match names the key that was tried")
  void bracketMatchMissing() throws Exception {
    var r = call("vache", "Elimination", 5, 2363); // the replay has sf4m1, not sf5m1
    assertEquals("no_match", r.get("status").getAsString(), r.toString());
    var reason = r.get("reason").getAsString();
    assertTrue(reason.contains("bracket match 5 (TBA key sf5m1), which this event does not have"),
        reason);
  }

  @Test
  @DisplayName("before 2023, Elimination N without team_number: the reason asks for the team")
  void playOrderNeedsTheTeam() throws Exception {
    var r = call(2022, "vache", "Elimination", 2, null);
    assertEquals("no_match", r.get("status").getAsString(), r.toString());
    var reason = r.get("reason").getAsString();
    assertTrue(reason.contains("before 2023"), reason);
    assertTrue(reason.contains("pass team_number"), reason);
    assertTrue(reason.contains("play_order"), reason);
  }

  @Test
  @DisplayName("an unreachable TBA: get_tba_status is not available and says why")
  void statusUnreachable() throws Exception {
    var client = TbaClient.getInstance();
    client.setBaseUrl("http://127.0.0.1:1/api/v3"); // nothing listens there
    try {
      var r = registry.getTool("get_tba_status").execute(new JsonObject()).getAsJsonObject();
      assertFalse(r.get("available").getAsBoolean(), r.toString());
      assertEquals("configured", r.get("configuration").getAsString());
      var check = r.getAsJsonObject("key_check");
      assertFalse(check.get("valid").getAsBoolean());
      assertTrue(check.get("detail").getAsString().contains("could not be reached"), check.toString());
    } finally {
      client.setBaseUrl(replayUrl());
    }
  }

  @Test
  @DisplayName("enrichment: a 2026 finals log gets the team's playoff match nearest its time")
  void enrichmentNearestTime() {
    var info = new LogFileInfo("/logs/x.wpilog", "x.wpilog", "VACHE", "Elimination", 14, 2363,
        0, 1, (F1_TIME - 90) * 1000L);
    var tba = TbaEnrichment.getInstance().enrichLogOrThrow(info).orElseThrow();
    assertEquals("2026vache_f1m1", tba.get("match_key").getAsString(), tba.toString());
    assertEquals("nearest_time", tba.get("lookup_method").getAsString());
    assertTrue(tba.get("lookup_basis").getAsString().contains("nearest the log's time"));
    assertEquals(120, tba.get("score").getAsInt());
    assertEquals(95, tba.get("opponent_score").getAsInt());
    assertTrue(tba.get("won").getAsBoolean());
  }

  @Test
  @DisplayName("enrichment: before 2023, Elimination N is the Nth playoff match played, labeled")
  void enrichmentPlayOrderBefore2023() {
    var info = new LogFileInfo("/logs/y.wpilog", "y.wpilog", "2022VACHE", "Elimination", 2,
        2363, 0, 1, 1650003600_000L + 30_000L);
    var tba = TbaEnrichment.getInstance().enrichLogOrThrow(info).orElseThrow();
    assertEquals("2022vache_sf1m1", tba.get("match_key").getAsString(), tba.toString());
    assertEquals("play_order", tba.get("lookup_method").getAsString());
    assertTrue(tba.get("lookup_basis").getAsString().contains("heuristic"));
  }

  @Test
  @DisplayName("get_tba_status checks the key against TBA")
  void statusChecksTheKey() throws Exception {
    var r = registry.getTool("get_tba_status").execute(new JsonObject()).getAsJsonObject();
    assertTrue(r.get("available").getAsBoolean(), r.toString());
    assertEquals("configured", r.get("configuration").getAsString());
    var check = r.getAsJsonObject("key_check");
    assertTrue(check.get("valid").getAsBoolean());
    assertEquals(2026, check.get("current_season").getAsInt());
    assertFalse(check.get("datafeed_down").getAsBoolean());
  }

  @Test
  @DisplayName("a rejected key: get_tba_status says so and is not available")
  void statusRejectedKey() throws Exception {
    var client = TbaClient.getInstance();
    client.configure("wrong-key");
    try {
      var r = registry.getTool("get_tba_status").execute(new JsonObject()).getAsJsonObject();
      assertFalse(r.get("available").getAsBoolean(), r.toString());
      assertEquals("configured", r.get("configuration").getAsString());
      var check = r.getAsJsonObject("key_check");
      assertFalse(check.get("valid").getAsBoolean());
      assertTrue(check.get("detail").getAsString().contains("rejected"), check.toString());
    } finally {
      client.configure(KEY);
    }
  }

  @Test
  @DisplayName("list_available_logs reports an outage at the top level, not as missing data")
  void listingReportsOutage(@TempDir Path dir) throws Exception {
    // An empty file: its metadata comes from the name (event VAALE, qualification 5) and the
    // default team number, which makes it eligible for enrichment
    Files.createFile(dir.resolve("frc_26-03-21_18-50-00_vaale_qm5.wpilog"));
    var logDir = LogDirectory.getInstance();
    var savedDir = logDir.getLogDirectory();
    var client = TbaClient.getInstance();
    logDir.setLogDirectory(dir.toString());
    logDir.setDefaultTeamNumber(2363);
    client.setBaseUrl("http://127.0.0.1:1/api/v3");
    try {
      var r = registry.getTool("list_available_logs").execute(new JsonObject()).getAsJsonObject();
      assertEquals("ok", r.get("status").getAsString(), r.toString());
      var tba = r.getAsJsonObject("tba_enrichment");
      assertFalse(tba.get("available").getAsBoolean(), r.toString());
      assertTrue(tba.get("reason").getAsString().contains("could not be reached"), r.toString());
      assertTrue(tba.get("reason").getAsString().contains("not because"), r.toString());
      assertFalse(r.getAsJsonArray("logs").get(0).getAsJsonObject().has("tba"));
    } finally {
      client.setBaseUrl(replayUrl());
      logDir.setDefaultTeamNumber(null);
      logDir.setLogDirectory(savedDir == null ? null : savedDir.toString());
      logDir.clearCache();
    }
  }

  @Test
  @DisplayName("an outage propagates from enrichLogOrThrow; enrichLog swallows it")
  void outageIsNotNoData() {
    var client = TbaClient.getInstance();
    client.setBaseUrl("http://127.0.0.1:1/api/v3"); // nothing listens there
    try {
      var info = new LogFileInfo("/logs/z.wpilog", "z.wpilog", "VAALE", "Qualification", 5,
          2363, 0, 1, F1_TIME * 1000L);
      var e = assertThrows(TbaUnavailableException.class,
          () -> TbaEnrichment.getInstance().enrichLogOrThrow(info));
      assertTrue(e.getMessage().contains("could not be reached"), e.getMessage());
      assertTrue(TbaEnrichment.getInstance().enrichLog(info).isEmpty());
    } finally {
      client.setBaseUrl(replayUrl());
    }
  }
}
