/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.wpi.first.util.datalog.DataLogReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.store.StoreJson;

/** The installed Java-only start path, isolated from the user's home and any real robot. */
class CaptureStartTest {
  @TempDir Path temp;
  private int port() throws Exception {
    try (var socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", 0)); return socket.getLocalPort(); }
  }
  private Process run(String... arguments) throws Exception {
    var command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow(),
        "-jar", System.getProperty("install.testJar")));
    command.addAll(List.of(arguments));
    var builder = new ProcessBuilder(command).directory(temp.toFile()).redirectErrorStream(true)
        .redirectOutput(Files.createTempFile(temp, "command", ".log").toFile());
    for (String key : List.of("WPILOG_DIR", "TBA_API_KEY", "WPILOG_HTTP_BIND", "WPILOG_HTTP_PATH", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) builder.environment().remove(key);
    builder.environment().put("JAVA_TOOL_OPTIONS", "-Duser.home=\"" + temp + "\" -Djava.library.path=\"" + temp.resolve("no-natives") + "\"");
    builder.environment().put("WPILOG_MAX_HEAP", "256m");
    return builder.start();
  }

  @Test void startServesHttpWithoutARobotThenCapturesAndFlushesOnStop() throws Exception {
    temp = temp.toRealPath(); int httpPort = port(); int ntPort;
    do { ntPort = port(); } while (ntPort == httpPort);
    var root = temp.resolve("store"); var config = temp.resolve("servers.yaml");
    Files.writeString(config, "servers:\n  pit:\n    transport: http\n    port: " + httpPort
        + "\n    capture:\n      robot: {host: 127.0.0.1, port: " + ntPort + "}\n      store: "
        + StoreJson.JSON.toJson(root.toString()) + "\n");
    var pidFile = temp.resolve(".wpilog-mcp/run/pit.pid");
    var start = run("start", "pit", "--config", config.toString());
    try {
      assertTrue(start.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, start.exitValue());
      var http = HttpClient.newHttpClient(); var endpoint = URI.create("http://127.0.0.1:" + httpPort + "/mcp");
      var init = http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
          .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
          .build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, init.statusCode()); String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var tools = http.send(HttpRequest.newBuilder(endpoint).header("Mcp-Session-Id", session)
          .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\"}")).build(), HttpResponse.BodyHandlers.ofString());
      var names = JsonParser.parseString(tools.body()).getAsJsonObject().getAsJsonObject("result")
          .getAsJsonArray("tools").asList().stream().map(t -> t.getAsJsonObject().get("name").getAsString()).toList();
      assertTrue(names.containsAll(List.of("list_sessions", "get_latest_values", "wait_for_change")), names.toString());
      var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Mcp-Session-Id", session)
          .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_available_logs\",\"arguments\":{}}}")).build();
      var empty = result(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
      assertEquals(List.of(root.toString()), empty.getAsJsonArray("log_directory_paths").asList().stream().map(v -> v.getAsString()).toList());
      assertEquals(0, empty.get("log_count").getAsInt());
      Path captured = null;
      try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", ntPort), () -> 10_000_000)) {
        gateway.start().get(5, TimeUnit.SECONDS);
        gateway.announce("/x", "int", new JsonObject()).join(); gateway.value("/x", 10_000_000, 2, 42L).join();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
          var listing = result(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
          if (listing.has("logs") && !listing.getAsJsonArray("logs").isEmpty()) {
            var row = listing.getAsJsonArray("logs").get(0).getAsJsonObject();
            assertTrue(row.getAsJsonObject("session").get("open").getAsBoolean());
            captured = Path.of(row.get("path").getAsString());
            var records = new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(captured)));
            boolean found = false;
            for (long offset = edu.wpi.first.util.datalog.DataLogAccess.firstRecordOffset(captured);
                offset < edu.wpi.first.util.datalog.DataLogAccess.size(records);) {
              long end = edu.wpi.first.util.datalog.DataLogAccess.recordEnd(records, offset);
              if (end < 0) break;
              var record = edu.wpi.first.util.datalog.DataLogAccess.getRecord(records, offset);
              if (!record.isControl()) found |= record.getInteger() == 42;
              offset = end;
            }
            if (found) break;
          }
        }
        assertNotNull(captured, "The start path must attach the capture listener when the robot appears");
        var stop = run("stop", "pit"); assertTrue(stop.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, stop.exitValue());
      }
      var records = new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(captured)));
      var values = new ArrayList<Long>();
      for (var record : records) if (!record.isControl()) { assertEquals(10_000_000, record.getTimestamp()); values.add(record.getInteger()); }
      assertEquals(List.of(42L), values);
      var manifest = JsonParser.parseString(Files.readString(captured.getParent().resolve("session.json"))).getAsJsonObject();
      assertTrue(manifest.get("open_capture").isJsonNull()); assertEquals(Files.size(captured), manifest.getAsJsonArray("files").get(0).getAsJsonObject().get("size_bytes").getAsLong());
    } finally {
      start.destroyForcibly();
      if (Files.exists(pidFile)) {
        var daemon = ProcessHandle.of(Long.parseLong(Files.readAllLines(pidFile).get(0)));
        if (daemon.isPresent()) { daemon.get().destroy(); daemon.get().onExit().get(15, TimeUnit.SECONDS); }
      }
    }
  }
  private static JsonObject result(String response) {
    return JsonParser.parseString(JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("result")
        .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
  }
}
