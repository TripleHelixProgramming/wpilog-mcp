/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;

/** A laptop's live source: the local server records the pit's NT4 gateway into a laptop store,
 * configured at run time through a loopback control, so a window's live views read only local
 * files. The robot fixture is the same gateway class the pit serves, and the recorded file is
 * read back by the independent reader, so every value and robot timestamp is checked against
 * what was published rather than against what the server says it recorded. */
class LiveSourceTest {
  @TempDir Path temp;
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  final long offset = 10_000_000 - System.nanoTime() / 1000;
  LogManager logs; Set<Path> allowed; HttpTransport transport;
  Nt4Gateway robot, second;

  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); logs = LogManager.getInstance(); allowed = logs.getAllowedDirectories();
    logs.addAllowedDirectory(temp);
    robot = robot(); second = robot();
    var registry = new ToolRegistry(); transport = new HttpTransport(registry, 0);
    org.triplehelix.wpilogmcp.tools.LiveTools.registerFollowing(registry, transport.liveCapture()); transport.start();
  }
  @AfterEach void stop() throws Exception {
    try { if (transport != null) transport.stop(); } finally {
      if (robot != null) robot.close(); if (second != null) second.close();
      logs.release(temp); logs.clearAllowedDirectories(); allowed.forEach(logs::addAllowedDirectory);
    }
  }
  /** A gateway whose clock reads robot time from 10 s, with one double topic announced. */
  Nt4Gateway robot() throws Exception {
    var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> System.nanoTime() / 1000 + offset);
    gateway.start().get(10, TimeUnit.SECONDS); gateway.announce("/value", "double", new JsonObject()).join(); return gateway;
  }
  HttpResponse<String> send(String method, String route, String body, String... headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + transport.getPort() + route)).timeout(Duration.ofSeconds(60))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) request.header("Content-Type", "application/json");
    for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
  static String source(Nt4Gateway gateway, Path store) {
    return "{\"robot\":{\"host\":\"127.0.0.1\",\"port\":" + gateway.port() + "},\"store\":" + new JsonPrimitive(store.toString()) + "}";
  }
  JsonObject status() throws Exception {
    var reply = send("GET", "/store/live", null); assertEquals(200, reply.statusCode(), reply.body());
    return JsonParser.parseString(reply.body()).getAsJsonObject();
  }
  String state() throws Exception { return status().get("state").getAsString(); }
  /** One MCP tool call over HTTP, as an agent makes it, returning the tool's result object. */
  JsonObject tool(String name, String arguments) throws Exception {
    var initialized = send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
    String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
    var reply = send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
        + "\",\"arguments\":" + arguments + "}}", "Mcp-Session-Id", session);
    assertEquals(200, reply.statusCode(), reply.body());
    var text = JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    return JsonParser.parseString(text).getAsJsonObject();
  }
  long records() throws Exception {
    var session = status().get("session"); return session == null || session.isJsonNull() ? -1 : session.getAsJsonObject().get("records").getAsLong();
  }

  /** Equal timestamps, a repeated value and NaN all arrive as their own records, at the robot's
   * own times: the laptop's file has the robot's timeline, the same as the pit's. */
  @Test void aLiveSourceRecordsWhatTheGatewayPublishesAtTheRobotsTimes() throws Exception {
    assertEquals("disabled", state());
    var configured = send("POST", "/store/live/configure", source(robot, temp.resolve("live")));
    assertEquals(202, configured.statusCode(), configured.body());
    HarnessHttp.await("recording from the gateway", 15, () -> "recording".equals(state()));
    long[] times = {10_100_000, 10_200_000, 10_200_000, 10_300_000, 10_400_000};
    double[] values = {0.5, -2.25, 3e-3, 3e-3, Double.NaN};
    for (int i = 0; i < times.length; i++) robot.value("/value", times[i], 1, values[i]).join();
    HarnessHttp.await("every published value recorded", 15, () -> records() == times.length);
    var file = Path.of(status().getAsJsonObject("session").get("file").getAsString());
    assertTrue(file.startsWith(temp.resolve("live")), file.toString());
    var stopped = send("DELETE", "/store/live", null);
    assertEquals(200, stopped.statusCode(), stopped.body()); assertEquals("disabled", state());
    var series = IndependentLog.read(file, Set.of("NT:/value")).series.get("NT:/value");
    assertNotNull(series, "the recording declares NT:/value");
    assertEquals(times.length, series.n);
    for (int i = 0; i < times.length; i++) {
      assertEquals(times[i] / 1e6, series.times[i], "record " + i + " keeps the robot's timestamp");
      assertEquals(0, Double.compare(values[i], series.values[i]), "record " + i + " keeps its value");
    }
  }

  /** The control belongs to a person at this laptop: only a direct loopback caller may use it, it
   * records only, into a folder the server already admits, and never into the mirror's folder. */
  @Test void theLiveSourceIsTrustedLocalConfigurationOnly() throws Exception {
    var forwarded = send("POST", "/store/live/configure", source(robot, temp.resolve("live")), "X-Forwarded-For", "192.0.2.10");
    assertEquals(403, forwarded.statusCode(), forwarded.body());
    for (var extra : List.of("\"gateway\":{\"port\":5810}", "\"pull\":{\"enabled\":true}")) {
      var body = source(robot, temp.resolve("live")); body = body.substring(0, body.length() - 1) + "," + extra + "}";
      var refused = send("POST", "/store/live/configure", body);
      assertEquals(400, refused.statusCode(), extra + ": " + refused.body());
    }
    var outside = Files.createTempDirectory("live-outside").toRealPath();
    var unadmitted = send("POST", "/store/live/configure", source(robot, outside.resolve("live")));
    assertEquals(409, unadmitted.statusCode(), unadmitted.body());
    assertEquals("disabled", state());

    var mirror = temp.resolve("mirror");
    var mirrored = send("POST", "/store/mirror/configure", "{\"origin\":\"http://127.0.0.1:9\",\"folder\":" + new JsonPrimitive(mirror.toString()) + ",\"interval_sec\":3600}");
    assertEquals(202, mirrored.statusCode(), mirrored.body());
    var intoMirror = send("POST", "/store/live/configure", source(robot, mirror));
    assertEquals(409, intoMirror.statusCode(), intoMirror.body()); assertTrue(intoMirror.body().contains("mirror"), intoMirror.body());
    assertEquals(200, send("DELETE", "/store/mirror", null).statusCode());
    var live = temp.resolve("live-only");
    assertEquals(202, send("POST", "/store/live/configure", source(robot, live)).statusCode());
    var mirrorIntoLive = send("POST", "/store/mirror/configure", "{\"origin\":\"http://127.0.0.1:9\",\"folder\":" + new JsonPrimitive(live.toString()) + ",\"interval_sec\":3600}");
    assertEquals(409, mirrorIntoLive.statusCode(), mirrorIntoLive.body()); assertTrue(mirrorIntoLive.body().contains("live"), mirrorIntoLive.body());
  }

  /** A new gateway address moves the recording there; the old recording ends with its file whole. */
  @Test void reconfiguringFinishesTheOldRecordingAndRecordsTheNewSource() throws Exception {
    assertEquals(202, send("POST", "/store/live/configure", source(robot, temp.resolve("live"))).statusCode());
    HarnessHttp.await("recording the first gateway", 15, () -> "recording".equals(state()));
    robot.value("/value", 10_500_000, 1, 1.0).join();
    HarnessHttp.await("first value", 15, () -> records() == 1);
    var first = Path.of(status().getAsJsonObject("session").get("file").getAsString());
    var again = send("POST", "/store/live/configure", source(robot, temp.resolve("live")));
    assertEquals(202, again.statusCode(), again.body()); assertEquals(1, records(), "an unchanged configuration keeps its recording");

    assertEquals(202, send("POST", "/store/live/configure", source(second, temp.resolve("live"))).statusCode());
    HarnessHttp.await("recording the second gateway", 15, () -> "recording".equals(state())
        && status().getAsJsonObject("configuration").getAsJsonObject("robot").get("port").getAsInt() == second.port());
    // The old capture stopped: its gateway lost its only client, and only the new one is connected.
    HarnessHttp.await("first gateway released", 15, () -> robot.clientCount() == 0 && second.clientCount() == 1);
    second.value("/value", 10_600_000, 1, 2.0).join();
    HarnessHttp.await("second value", 15, () -> records() == 1 && !first.toString().equals(status().getAsJsonObject("session").get("file").getAsString()));
    var old = IndependentLog.read(first, Set.of("NT:/value")).series.get("NT:/value");
    assertEquals(1, old.n); assertEquals(1.0, old.values[0]); assertEquals(10.5, old.times[0]);
  }

  /** Agents read live values from whichever capture is current: none, then the live source, then
   * none again once it stops, without restarting the server or re-registering a tool. */
  @Test void theLiveToolsAnswerFromTheLiveSourceWhileItRuns() throws Exception {
    var before = tool("get_latest_values", "{\"entries\":[\"/value\"]}");
    assertEquals("not_applicable", before.get("status").getAsString(), before.toString());
    assertTrue(before.toString().contains("Capture is not enabled"), before.toString());
    assertEquals(202, send("POST", "/store/live/configure", source(robot, temp.resolve("live"))).statusCode());
    HarnessHttp.await("recording", 15, () -> "recording".equals(state()));
    robot.value("/value", 10_700_000, 1, 4.5).join();
    HarnessHttp.await("value recorded", 15, () -> records() == 1);
    var during = tool("get_latest_values", "{\"entries\":[\"/value\"]}");
    assertEquals("ok", during.get("status").getAsString(), during.toString());
    var value = during.getAsJsonArray("values").get(0).getAsJsonObject();
    assertEquals(4.5, value.get("value").getAsDouble()); assertEquals(10.7, value.get("timestamp_sec").getAsDouble());
    assertEquals(200, send("DELETE", "/store/live", null).statusCode());
    var after = tool("get_latest_values", "{\"entries\":[\"/value\"]}");
    assertTrue(after.toString().contains("Capture is not enabled"), after.toString());
  }

  /** A capture servers.yaml configured is this server's recording: the control reports it, and
   * neither replaces nor stops it, so a pit's robot capture cannot be redirected from an editor. */
  @Test void theMirrorRefusesTheFolderOfACaptureFromServersYaml() throws Exception {
    // Before its robot connects the capture's store is still empty, which a mirror would take over.
    var folder = temp.resolve("configured");
    var config = new org.triplehelix.wpilogmcp.config.CaptureConfig(List.of(org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1", 9, "configured")),
        folder, 0.01, org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, 600_000_000);
    try (var capture = new org.triplehelix.wpilogmcp.capture.CaptureService(config, logs)) {
      transport.useConfiguredCapture(capture);
      var mirrored = send("POST", "/store/mirror/configure", "{\"origin\":\"http://127.0.0.1:9\",\"folder\":" + new JsonPrimitive(folder.toString()) + ",\"interval_sec\":3600}");
      assertEquals(409, mirrored.statusCode(), mirrored.body()); assertTrue(mirrored.body().contains("live"), mirrored.body());
      assertEquals("disabled", JsonParser.parseString(send("GET", "/store/mirror", null).body()).getAsJsonObject().get("state").getAsString());
    }
  }
  @Test void aCaptureFromServersYamlCannotBeReplacedOrStoppedByTheControl() throws Exception {
    var config = new org.triplehelix.wpilogmcp.config.CaptureConfig(List.of(org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1", robot.port(), "configured")),
        temp.resolve("configured"), 0.01, org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, 600_000_000);
    try (var capture = new org.triplehelix.wpilogmcp.capture.CaptureService(config, logs)) {
      transport.useConfiguredCapture(capture); capture.start().get(10, TimeUnit.SECONDS);
      HarnessHttp.await("configured capture recording", 15, () -> "recording".equals(state()));
      assertEquals("servers.yaml", status().get("configured_by").getAsString());
      var replaced = send("POST", "/store/live/configure", source(second, temp.resolve("live")));
      assertEquals(409, replaced.statusCode(), replaced.body()); assertTrue(replaced.body().contains("servers.yaml"), replaced.body());
      var stopped = send("DELETE", "/store/live", null);
      assertEquals(409, stopped.statusCode(), stopped.body());
      robot.value("/value", 10_800_000, 1, 8.25).join();
      HarnessHttp.await("configured capture still recording", 15, () -> records() == 1);
    }
  }
}
