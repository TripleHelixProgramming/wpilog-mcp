/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.store.StoreJson;

/** A real packaged bridge and daemon, isolated from the user's home, project, and network services. */
class BridgeLeaseTest {
  @TempDir Path temp;

  private int port() throws Exception {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Test void homeDefinesTheDaemonAndProjectAndFlagsOnlyLeaseDirectories() throws Exception {
    temp = temp.toRealPath();
    var home = Files.createDirectory(temp.resolve("home"));
    var install = Files.createDirectories(home.resolve(".wpilog-mcp"));
    var project = Files.createDirectory(temp.resolve("project"));
    var projectLogs = Files.createDirectory(project.resolve("logs"));
    var flagLogs = Files.createDirectory(project.resolve("flaglogs"));
    var permanent = Files.createDirectory(temp.resolve("permanent"));
    ImportFixture.write(projectLogs.resolve("project.wpilog"), 1);
    ImportFixture.write(flagLogs.resolve("flag.wpilog"), 2);
    int homePort = port();
    int projectPort;
    do { projectPort = port(); } while (projectPort == homePort);
    Files.writeString(install.resolve("servers.yaml"), "logdir: " + StoreJson.JSON.toJson(permanent.toString())
        + "\nservers:\n  http:\n    transport: http\n    port: " + homePort + "\n");
    Files.writeString(project.resolve(".wpilog-mcp.yaml"), "logdir: logs\nteam: 11\nservers:\n  http:\n    transport: http\n    port: "
        + projectPort + "\n");
    var builder = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"),
        "-jar", System.getProperty("install.testJar"), "connect", "http", "--logdir", "flaglogs", "--team", "22")
        .directory(project.toFile()).redirectError(temp.resolve("bridge.log").toFile());
    for (var name : List.of("WPILOG_HTTP_BIND", "WPILOG_HTTP_PATH", "TBA_API_KEY", "WPILOG_DIR", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
      builder.environment().remove(name);
    }
    // Inherited by the daemon as well; no process in this test writes the real home.
    builder.environment().put("JAVA_TOOL_OPTIONS", "-Duser.home=\"" + home + "\"");
    builder.environment().put("WPILOG_MAX_HEAP", "256m");
    var child = builder.start();
    var pool = Executors.newSingleThreadExecutor();
    var reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
    var pidFile = install.resolve("run").resolve("http.pid");
    try {
      send(child, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
      var initialized = pool.submit(reader::readLine).get(20, TimeUnit.SECONDS);
      assertNotNull(initialized, Files.readString(temp.resolve("bridge.log")));
      assertFalse(JsonParser.parseString(initialized).getAsJsonObject().has("error"), initialized);
      assertEquals(homePort, Integer.parseInt(Files.readAllLines(pidFile).get(1)));
      send(child, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_available_logs\",\"arguments\":{}}}");
      var listing = result(pool.submit(reader::readLine).get(10, TimeUnit.SECONDS));
      assertEquals(List.of(permanent.toString(), flagLogs.toString(), projectLogs.toString()),
          listing.getAsJsonArray("log_directory_paths").asList().stream().map(v -> v.getAsString()).toList());
      assertEquals(List.of(22, 22), listing.getAsJsonArray("logs").asList().stream()
          .map(log -> log.getAsJsonObject().get("team_number").getAsInt()).toList());
      assertEquals(List.of("configured", "leased", "leased"), listing.getAsJsonArray("log_directories").asList().stream()
          .map(dir -> dir.getAsJsonObject().get("origin").getAsString()).toList());
      child.getOutputStream().close();
      assertTrue(child.waitFor(15, TimeUnit.SECONDS));
      assertEquals(0, child.exitValue(), Files.readString(temp.resolve("bridge.log")));
      // A second bridge joins by URL, takes its team from the project, and re-registers on
      // re-initialization (the same startup path a reconnect after a restart takes).
      builder.command(List.of(ProcessHandle.current().info().command().orElse("java"),
          "-jar", System.getProperty("install.testJar"), "connect", "--url",
          "http://127.0.0.1:" + homePort, "--logdir", "flaglogs"));
      var byUrl = builder.start();
      try (var lines = new BufferedReader(new InputStreamReader(byUrl.getInputStream(), StandardCharsets.UTF_8))) {
        for (int round = 0; round < 2; round++) {
          send(byUrl, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
          assertNotNull(pool.submit(lines::readLine).get(10, TimeUnit.SECONDS));
          send(byUrl, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_available_logs\",\"arguments\":{}}}");
          var fromProject = result(pool.submit(lines::readLine).get(10, TimeUnit.SECONDS));
          assertEquals(List.of(11, 11), fromProject.getAsJsonArray("logs").asList().stream()
              .map(log -> log.getAsJsonObject().get("team_number").getAsInt()).toList());
        }
        byUrl.getOutputStream().close();
        assertTrue(byUrl.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, byUrl.exitValue());
      } finally {
        byUrl.destroyForcibly();
      }
      var log = Files.readString(install.resolve("logs").resolve("http.log"));
      assertEquals(3, log.lines().filter(line -> line.contains("ignored the project file's servers section")).count(), log);
      var client = HttpClient.newHttpClient();
      var endpoint = URI.create("http://127.0.0.1:" + homePort + "/mcp");
      var init = client.send(HttpRequest.newBuilder(endpoint).POST(HttpRequest.BodyPublishers.ofString(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
          .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
      String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var listed = client.send(HttpRequest.newBuilder(endpoint).header("Mcp-Session-Id", session)
          .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_available_logs\",\"arguments\":{}}}"))
          .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(List.of(permanent.toString()), result(listed.body()).getAsJsonArray("log_directory_paths")
          .asList().stream().map(v -> v.getAsString()).toList());
    } finally {
      child.destroyForcibly();
      pool.shutdownNow();
      if (Files.exists(pidFile)) {
        long pid = Long.parseLong(Files.readAllLines(pidFile).get(0));
        var daemon = ProcessHandle.of(pid);
        if (daemon.isPresent() && pid != ProcessHandle.current().pid()) {
          daemon.get().destroy();
          daemon.get().onExit().get(15, TimeUnit.SECONDS);
        }
      }
    }
  }

  private static void send(Process child, String line) throws Exception {
    child.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
    child.getOutputStream().flush();
  }

  private static JsonObject result(String message) {
    return JsonParser.parseString(JsonParser.parseString(message).getAsJsonObject().getAsJsonObject("result")
        .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
  }
}
