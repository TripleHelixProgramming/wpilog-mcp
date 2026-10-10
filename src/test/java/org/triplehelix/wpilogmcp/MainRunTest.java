/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A supervisor needs a foreground child, its exit status and stderr, never a PID claim. */
class MainRunTest {
  @TempDir Path temp;
  private Path config(String body) throws Exception {
    var config = temp.resolve("servers.yaml");
    Files.writeString(config, "diskcachedisable: true\nservers:\n  pit:\n" + body);
    return config;
  }
  private record Output(int code, String out, String err) {}
  private Output run(List<String> args) throws Exception {
    var command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow(),
        "-Duser.home=" + temp, "-cp", System.getProperty("java.class.path"), Main.class.getName()));
    command.addAll(args);
    var out = temp.resolve("stdout"); var err = temp.resolve("stderr");
    var builder = new ProcessBuilder(command).directory(temp.toFile())
        .redirectOutput(out.toFile()).redirectError(err.toFile());
    for (String name : List.of("WPILOG_DIR", "WPILOG_DEBUG", "TBA_API_KEY", "INVOCATION_ID")) builder.environment().remove(name);
    var child = builder.start(); child.getOutputStream().close();
    try {
      assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Foreground child did not stop: " + Files.readString(err));
      return new Output(child.exitValue(), Files.readString(out), Files.readString(err));
    } finally { child.destroyForcibly(); }
  }
  @Test void runUsesTheNamedConfigurationStderrAndCleanEofWithoutPidFiles() throws Exception {
    var output = run(List.of("run", "pit", "--config", config("    transport: stdio\n").toString()));
    assertEquals(0, output.code(), output.err());
    assertTrue(output.err().contains("Loaded configuration 'pit'"), output.err());
    assertEquals("", output.out(), "Startup logs belong on stderr");
    assertFalse(Files.exists(temp.resolve(".wpilog-mcp/run/pit.pid")));
  }
  @Test void failedBindAndBadConfigurationExitNonzeroWithTheReason() throws Exception {
    try (var held = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
      var output = run(List.of("run", "pit", "--config", config("    transport: http\n    port: " + held.getLocalPort() + "\n").toString()));
      assertNotEquals(0, output.code());
      assertTrue(output.err().contains("Fatal HTTP server error") && output.err().contains("BindException"), output.err());
    }
    var output = run(List.of("run", "missing", "--config", config("    transport: stdio\n").toString()));
    assertNotEquals(0, output.code()); assertTrue(output.err().contains("Unknown server configuration 'missing'"), output.err());
  }

  @Test void foregroundOwnershipRequiresTheFlagEvenWithAnInheritedSystemdEnvironment() throws Exception {
    for (String mode : List.of("ordinary", "flag", "environment")) {
      int port;
      try (var reserve = new java.net.ServerSocket(0)) { port = reserve.getLocalPort(); }
      var path = config("    transport: http\n    port: " + port + "\n");
      var command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow(), "-Xmx256m",
          "-Duser.home=" + temp, "-jar", System.getProperty("install.testJar"), "run", "pit", "--config", path.toString()));
      if (mode.equals("flag")) command.add("--managed");
      var err = temp.resolve(mode + ".stderr"); var out = temp.resolve(mode + ".stdout");
      var builder = new ProcessBuilder(command).directory(temp.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
      for (String key : List.of("INVOCATION_ID", "WPILOG_HTTP_BIND", "WPILOG_HTTP_PATH", "TBA_API_KEY")) builder.environment().remove(key);
      if (mode.equals("environment")) builder.environment().put("INVOCATION_ID", "synthetic-invocation");
      var child = builder.start();
      try {
        var http = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/health"))
            .timeout(java.time.Duration.ofSeconds(2)).build();
        var health = new java.util.concurrent.atomic.AtomicReference<com.google.gson.JsonObject>();
        org.triplehelix.wpilogmcp.harness.HarnessHttp.await("foreground HTTP", 10, () -> {
          assertTrue(child.isAlive(), Files.readString(err));
          var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() != 200) return false;
          health.set(com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject()); return true;
        });
        boolean expected = mode.equals("flag");
        assertEquals(expected, health.get().get("managed").getAsBoolean());
        assertEquals(Version.VERSION, health.get().get("version").getAsString());
        assertEquals("pit", health.get().get("name").getAsString(), "health names the running configuration");
        assertEquals(child.pid(), health.get().get("pid").getAsLong(), "run must not spawn a daemon");
        var mcp = new org.triplehelix.wpilogmcp.harness.HarnessHttp(port); mcp.initialize();
        assertEquals(expected, mcp.call("list_sessions", new com.google.gson.JsonObject()).get("managed").getAsBoolean());
        assertEquals("", Files.readString(out));
        assertFalse(Files.exists(temp.resolve(".wpilog-mcp/run/pit.pid")));
      } finally {
        child.destroy();
        if (!child.waitFor(10, TimeUnit.SECONDS)) { child.destroyForcibly(); fail("Foreground shutdown did not finish"); }
        if (child.supportsNormalTermination()) assertEquals(0, child.exitValue(), "A clean signal stop exits zero");
      }
    }
  }

  @Test void inheritedSystemdEnvironmentStillLetsTheDaemonManagerStartAdoptAndStop() throws Exception {
    int port;
    try (var reserve = new java.net.ServerSocket(0)) { port = reserve.getLocalPort(); }
    var path = config("    transport: http\n    port: " + port + "\n");
    var pidFile = temp.resolve(".wpilog-mcp/run/pit.pid");
    ProcessHandle daemon = null;
    try {
      var started = daemonCommand("start", path);
      // Capture the process even when old ownership detection makes start fail, so cleanup owns it.
      if (Files.exists(pidFile)) daemon = ProcessHandle.of(Long.parseLong(Files.readAllLines(pidFile).get(0))).orElse(null);
      assertEquals(0, started.code(), started.err());
      assertNotNull(daemon, "start must record its child");
      long pid = daemon.pid();
      var health = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
          java.net.URI.create("http://127.0.0.1:" + port + "/health")).timeout(java.time.Duration.ofSeconds(2)).build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, health.statusCode());
      assertFalse(com.google.gson.JsonParser.parseString(health.body()).getAsJsonObject().get("managed").getAsBoolean(),
          "An inherited INVOCATION_ID is not ownership");
      Files.delete(pidFile);
      var adopted = daemonCommand("start", path);
      assertEquals(0, adopted.code(), adopted.err());
      assertEquals(pid, Long.parseLong(Files.readAllLines(pidFile).get(0)), "start must adopt the same process");
      var stopped = daemonCommand("stop", path);
      assertEquals(0, stopped.code(), stopped.err());
      daemon.onExit().get(10, TimeUnit.SECONDS);
      assertFalse(Files.exists(pidFile), "stop must release its PID record");
    } finally {
      if (daemon != null && daemon.isAlive()) {
        daemon.destroyForcibly();
        daemon.onExit().get(10, TimeUnit.SECONDS);
      }
    }
  }

  private Output daemonCommand(String verb, Path config) throws Exception {
    return daemonCommand(verb, "pit", config);
  }

  @Test void startAndConnectRefuseAnotherNamedConfigurationOnTheSamePort() throws Exception {
    int port;
    try (var reserve = new java.net.ServerSocket(0)) { port = reserve.getLocalPort(); }
    var path = Files.writeString(temp.resolve("servers.yaml"), """
        diskcachedisable: true
        servers:
          http: {transport: http, port: %1$d}
          pit: {transport: http, port: %1$d}
        """.formatted(port));
    var pidFile = temp.resolve(".wpilog-mcp/run/http.pid");
    ProcessHandle daemon = null;
    try {
      var started = daemonCommand("start", "http", path);
      if (Files.exists(pidFile)) daemon = ProcessHandle.of(Long.parseLong(Files.readAllLines(pidFile).get(0))).orElse(null);
      assertEquals(0, started.code(), started.err());
      assertNotNull(daemon);
      for (String verb : List.of("start", "connect")) {
        var refused = daemonCommand(verb, "pit", path);
        assertEquals(1, refused.code(), "Do not adopt http as pit: " + refused.err());
        for (String detail : List.of("Port " + port, "'http'", "PID " + daemon.pid(),
            "wpilog-mcp stop http", "give 'pit' its own port")) {
          assertTrue(refused.err().contains(detail), refused.err());
        }
        assertFalse(Files.exists(temp.resolve(".wpilog-mcp/run/pit.pid")), "Refusal releases the claim");
        var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
            java.net.URI.create("http://127.0.0.1:" + port + "/health")).timeout(java.time.Duration.ofSeconds(2)).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "The first server keeps serving");
        var health = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("http", health.get("name").getAsString());
        assertEquals(daemon.pid(), health.get("pid").getAsLong());
      }
    } finally {
      if (daemon != null && daemon.isAlive()) {
        daemonCommand("stop", "http", path);
        if (daemon.isAlive()) daemon.destroyForcibly();
        daemon.onExit().get(10, TimeUnit.SECONDS);
      }
    }
  }

  private Output daemonCommand(String verb, String name, Path config) throws Exception {
    var out = temp.resolve(verb + ".stdout"); var err = temp.resolve(verb + ".stderr");
    var builder = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "-jar", System.getProperty("install.testJar"), verb, name, "--config", config.toString())
        .directory(temp.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
    for (String key : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "WPILOG_HTTP_BIND", "WPILOG_HTTP_PATH", "TBA_API_KEY")) {
      builder.environment().remove(key);
    }
    // Both the CLI and the JVM it spawns inherit the synthetic supervisor's environment.
    builder.environment().put("INVOCATION_ID", "synthetic-invocation");
    builder.environment().put("JAVA_TOOL_OPTIONS", "-Duser.home=\"" + temp + "\"");
    builder.environment().put("WPILOG_MAX_HEAP", "256m");
    var child = builder.start(); child.getOutputStream().close();
    try {
      assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Daemon command did not finish: " + Files.readString(err));
      return new Output(child.exitValue(), Files.readString(out), Files.readString(err));
    } finally { child.destroyForcibly(); }
  }
}
