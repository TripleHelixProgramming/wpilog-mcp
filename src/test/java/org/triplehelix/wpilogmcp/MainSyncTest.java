/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreJson;

class MainSyncTest {
  @TempDir Path temp;
  Set<Path> previous;
  HttpTransport peer;
  Path target, config, original;
  record Ran(int code, String output) {}
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    original = ImportFixture.write(temp.resolve("original.wpilog"), 51);
    var source = manager.stores().store(temp.resolve("peer"));
    source.importPaths(new LogStore.Request(List.of(original), false, "practice"), p -> {}).get();
    peer = new HttpTransport(new ToolRegistry(), 0); peer.setStoreDirectories(Set.of(source.root())); peer.start();
    target = Files.createDirectory(temp.resolve("target")); config = temp.resolve("servers.yaml");
    writeConfig(2363);
  }
  @AfterEach void stop() {
    peer.stop(); var manager = LogManager.getInstance(); manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
  private void writeConfig(int port) throws Exception {
    Files.writeString(config, "servers:\n  default:\n    transport: http\n    port: " + port + "\n    logdir: " + StoreJson.JSON.toJson(target) + "\n");
  }
  private ProcessBuilder process(List<String> args, Path output) {
    var command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), "-Duser.home=" + temp,
        "-cp", System.getProperty("java.class.path"), Main.class.getName())); command.addAll(args);
    var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
    for (String key : List.of("WPILOG_DIR", "TBA_API_KEY", "WPILOG_HTTP_BIND", "WPILOG_HTTP_PATH", "WPILOG_DEBUG")) builder.environment().remove(key);
    return builder;
  }
  private Ran run(String... args) throws Exception {
    var command = new ArrayList<>(List.of("sync", "--config", config.toString())); command.addAll(List.of(args));
    var output = Files.createTempFile(temp, "sync-", ".txt"); var child = process(command, output).start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), Files.readString(output)); return new Ran(child.exitValue(), Files.readString(output));
    } finally { child.destroyForcibly(); }
  }
  private JsonObject result(Ran ran) {
    assertEquals(0, ran.code(), ran.output());
    return ran.output().lines().filter(l -> l.startsWith("result ")).map(l -> JsonParser.parseString(l.substring(7)).getAsJsonObject()).reduce((a, b) -> b).orElseThrow();
  }
  private String url() { return "http://127.0.0.1:" + peer.getPort(); }
  @Test void offlineCopiesAndRemembersPeersWhileTheCrossProcessLockPreventsASecondWriter() throws Exception {
    var first = run(url()); assertTrue(first.output().contains("No running daemon"), first.output()); assertTrue(first.output().contains("store.lock"));
    var copied = result(first).getAsJsonArray("files_copied"); assertEquals(1, copied.size());
    var path = Path.of(copied.get(0).getAsJsonObject().get("path").getAsString());
    assertTrue(path.startsWith(target)); assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(path));
    var again = result(run()); assertEquals(0, again.getAsJsonArray("files_copied").size()); assertEquals(1, again.getAsJsonArray("files_present").size());
    try (var channel = java.nio.channels.FileChannel.open(target.resolve("store.lock"), java.nio.file.StandardOpenOption.WRITE);
         var held = channel.lock()) {
      var blocked = run(url()); assertEquals(1, blocked.code()); assertTrue(blocked.output().contains("store.lock"), blocked.output());
    }
    var outside = run("--store", temp.resolve("outside").toString(), url()); assertEquals(1, outside.code()); assertFalse(Files.exists(temp.resolve("outside")));
  }
  @Test void aRunningDaemonOwnsTheJobAndTheCommandPollsItsResult() throws Exception {
    int port;
    try (var socket = new java.net.ServerSocket()) { socket.bind(new java.net.InetSocketAddress("127.0.0.1", 0)); port = socket.getLocalPort(); }
    writeConfig(port); var output = temp.resolve("daemon.txt");
    var child = process(List.of("--internal-daemon", "default", "--config", config.toString()), output).start();
    try {
      var client = java.net.http.HttpClient.newHttpClient();
      org.triplehelix.wpilogmcp.harness.HarnessHttp.await("daemon health", 15, () -> {
        assertTrue(child.isAlive(), Files.readString(output));
        return client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/health"))
              .timeout(java.time.Duration.ofSeconds(1)).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
      });
      var run = Files.createDirectories(temp.resolve(".wpilog-mcp/run")); Files.writeString(run.resolve("default.pid"), child.pid() + "\n" + port + "\n");
      var command = run(url()); assertTrue(command.output().contains("Sync through running daemon"), command.output());
      assertTrue(command.output().contains("job "), command.output()); assertEquals(1, result(command).getAsJsonArray("files_copied").size());
      assertEquals(1, result(run()).getAsJsonArray("files_present").size());
    } finally { child.destroy(); if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly(); }
  }
  @Test void usageRateAndEmptyPeerHistoryHaveExplainedExitCodes() throws Exception {
    for (var args : List.of(new String[]{"--rate-bytes", "-1"}, new String[]{"--rate-bytes", "0.5"},
        new String[]{"--store"}, new String[]{"--bogus"}, new String[]{"file:///tmp/log"}, new String[]{url(), url()})) {
      assertEquals(2, run(args).code());
    }
    var noPeers = run(); assertEquals(1, noPeers.code()); assertTrue(noPeers.output().contains("No remembered peers"), noPeers.output());
  }

  @Test void aDaemonRefusalNeverFallsBackToAnOfflineWriter() throws Exception {
    var daemon = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    daemon.createContext("/health", exchange -> {
      byte[] body = "{\"status\":\"ok\",\"sessions\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length); try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    daemon.createContext("/store/sync", exchange -> {
      byte[] body = "test daemon stopped accepting jobs".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(503, body.length); try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    daemon.start();
    try {
      int port = daemon.getAddress().getPort(); writeConfig(port);
      var run = Files.createDirectories(temp.resolve(".wpilog-mcp/run"));
      Files.writeString(run.resolve("default.pid"), ProcessHandle.current().pid() + "\n" + port + "\n");
      var response = run(url()); assertEquals(1, response.code(), response.output());
      assertTrue(response.output().contains("Sync HTTP 503"), response.output());
      assertFalse(response.output().contains("No running daemon"), response.output());
      assertFalse(Files.exists(target.resolve("store.json")));
    } finally { daemon.stop(0); }
  }
}
