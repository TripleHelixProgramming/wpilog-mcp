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
      // A probe-and-release ephemeral port can be claimed by another process before this
      // child binds. Let the server own port selection and read its actual listening port.
      var path = config("    transport: http\n    port: 0\n");
      var command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow(), "-Xmx256m",
          "-Duser.home=" + temp, "-jar", System.getProperty("install.testJar"), "run", "pit", "--config", path.toString()));
      if (mode.equals("flag")) command.add("--managed");
      var err = temp.resolve(mode + ".stderr"); var out = temp.resolve(mode + ".stdout");
      var builder = new ProcessBuilder(command).directory(temp.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
      for (String key : List.of("INVOCATION_ID", "WPILOG_HTTP_BIND", "WPILOG_HTTP_PATH", "TBA_API_KEY")) builder.environment().remove(key);
      if (mode.equals("environment")) builder.environment().put("INVOCATION_ID", "synthetic-invocation");
      var child = builder.start();
      try {
        var actualPort = new java.util.concurrent.atomic.AtomicInteger();
        var listening = java.util.regex.Pattern.compile("MCP HTTP server listening on http://127\\.0\\.0\\.1:(\\d+)/mcp");
        org.triplehelix.wpilogmcp.harness.HarnessHttp.await("foreground bind", 10, () -> {
          String messages = Files.readString(err);
          assertTrue(child.isAlive(), messages);
          var match = listening.matcher(messages);
          if (!match.find()) return false;
          actualPort.set(Integer.parseInt(match.group(1))); return true;
        });
        int port = actualPort.get();
        assertTrue(port > 0, "The bound port, not the requested zero, is published");
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
        child.destroy();
        if (!child.waitFor(10, TimeUnit.SECONDS)) { child.destroyForcibly(); fail("Foreground shutdown did not finish"); }
        if (child.supportsNormalTermination()) assertEquals(0, child.exitValue(), "A clean signal stop exits zero");
      } finally {
        // Cleanup must not replace a startup failure with a misleading shutdown assertion.
        child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

  @Test void daemonCommandsStillRequireAFixedPort() throws Exception {
    var path = config("    transport: http\n    port: 0\n");
    for (String verb : List.of("start", "connect", "--internal-daemon")) {
      var output = run(List.of(verb, "pit", "--config", path.toString()));
      assertNotEquals(0, output.code(), verb + " cannot discover an ephemeral daemon");
      assertTrue(output.err().contains("Invalid port 0"), output.err());
      assertFalse(Files.exists(temp.resolve(".wpilog-mcp/run/pit.pid")));
    }
  }

  @Test void inheritedSystemdEnvironmentStillLetsTheDaemonManagerStartAdoptAndStop() throws Exception {
    try (var started = startWithBindRetries(attempt -> startAttempt("pit", port ->
        "diskcachedisable: true\nservers:\n  pit: {transport: http, port: " + port + "}\n", false))) {
      int port = started.port();
      var path = started.config();
      var pidFile = started.pidFile();
      var daemon = started.daemon();
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
    }
  }

  private Output daemonCommand(String verb, Path config) throws Exception {
    return daemonCommand(verb, "pit", config);
  }

  @Test void startAndConnectRefuseAnotherNamedConfigurationOnTheSamePort() throws Exception {
    try (var started = startWithBindRetries(attempt -> startAttempt("http", port -> """
        diskcachedisable: true
        servers:
          http: {transport: http, port: %1$d}
          pit: {transport: http, port: %1$d}
        """.formatted(port), false))) {
      int port = started.port();
      var path = started.config();
      var daemon = started.daemon();
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
      daemonCommand("stop", "http", path);
    }
  }

  private static final int START_ATTEMPTS = 3;

  /** Only startup is retried; a failure of adoption, refusal, or stop is never retried. */
  @FunctionalInterface private interface StartupAttempt {
    Startup start(int attempt) throws Exception;
  }

  private record Startup(String name, int port, Path config, Path pidFile, Output command,
      String daemonStderr, ProcessHandle daemon) implements AutoCloseable {
    boolean bindFailed() {
      // Use Java's exception class, not the OS-dependent message (Windows adds ": bind").
      boolean childBind = daemonStderr.contains("Fatal HTTP server error:")
          && daemonStderr.lines().anyMatch(line -> line.startsWith("java.net.BindException:"));
      // A port taken before the manager probes never reaches the daemon. The CLI child
      // reports this exact occupied-port refusal; a different named server is NOT retried.
      boolean occupiedBeforeSpawn = command.err().contains("Port " + port
          + " is in use by another program, which does not answer as wpilog-mcp. "
          + "Choose another port for server '" + name + "'.");
      return childBind || occupiedBeforeSpawn;
    }

    String diagnostics(int attempt) {
      return "Attempt " + attempt + " (" + name + " on port " + port + ")\n"
          + "Command stderr:\n" + command.err() + "\nDaemon stderr:\n" + daemonStderr;
    }

    @Override public void close() throws Exception {
      if (daemon != null && daemon.isAlive()) {
        daemon.destroyForcibly();
        daemon.onExit().get(10, TimeUnit.SECONDS);
      }
    }
  }

  private static Startup startWithBindRetries(StartupAttempt attempts) throws Exception {
    var diagnostics = new StringBuilder();
    for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
      var started = attempts.start(attempt);
      if (started.command().code() == 0) return started;
      diagnostics.append(started.diagnostics(attempt)).append('\n');
      try (started) {
        if (!started.bindFailed()) {
          throw new AssertionError("Startup failed without a bind collision; not retried.\n" + diagnostics);
        }
        if (attempt == START_ATTEMPTS) {
          throw new AssertionError("All " + START_ATTEMPTS + " startup attempts collided.\n" + diagnostics);
        }
      } catch (Exception cleanup) {
        throw new AssertionError("Could not clean up a failed startup.\n" + diagnostics, cleanup);
      }
    }
    throw new AssertionError("Startup attempts exhausted without a result");
  }

  private Startup startAttempt(String name, java.util.function.IntFunction<String> yaml,
      boolean collideAfterProbe) throws Exception {
    int port;
    try (var socket = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    return startAtPort(name, yaml, collideAfterProbe, port);
  }

  private Startup startAtPort(String name, java.util.function.IntFunction<String> yaml,
      boolean collideAfterProbe, int port) throws Exception {
    var config = Files.writeString(temp.resolve("servers.yaml"), yaml.apply(port));
    var log = temp.resolve(".wpilog-mcp/logs/" + name + ".log");
    int previousLength = Files.exists(log) ? Files.readString(log).length() : 0;
    var command = collideAfterProbe
        ? daemonProcess(List.of("-cp", System.getProperty("java.class.path"),
            org.triplehelix.wpilogmcp.config.DaemonPortCollision.class.getName(),
            System.getProperty("install.testJar"), name, config.toString(), Integer.toString(port)), "collision")
        : daemonCommand("start", name, config);
    var pidFile = temp.resolve(".wpilog-mcp/run/" + name + ".pid");
    // Even a failed start can have a child; own it before making any startup assertion.
    var daemon = Files.exists(pidFile)
        ? ProcessHandle.of(Long.parseLong(Files.readAllLines(pidFile).get(0))).orElse(null) : null;
    return new Startup(name, port, config, pidFile, command,
        Files.exists(log) ? Files.readString(log).substring(previousLength) : "", daemon);
  }

  @Test void heldPortRetriesOnlyStartupAndTheNextDaemonKeepsItsPid() throws Exception {
    var attempts = new ArrayList<Startup>();
    try (var started = startWithBindRetries(attempt -> {
      var result = startAttempt("pit", port ->
          "servers:\n  pit: {transport: http, port: " + port + "}\n", attempt == 1);
      if (attempt == 1) {
        assertTrue(result.daemonStderr().contains("java.net.BindException:"), result.daemonStderr());
        System.err.println("Forced child collision:\n" + result.daemonStderr());
      }
      attempts.add(result);
      return result;
    })) {
      assertEquals(2, attempts.size(), "Only the injected collision requires another attempt");
      assertTrue(attempts.get(0).daemonStderr().contains("java.net.BindException:"));
      assertTrue(attempts.get(0).daemonStderr().contains("Fatal HTTP server error:"));
      assertNotNull(started.daemon());
      assertTrue(started.daemon().isAlive());
      assertEquals(started.daemon().pid(), Long.parseLong(Files.readAllLines(started.pidFile()).get(0)));
      var stopped = daemonCommand("stop", "pit", started.config());
      assertEquals(0, stopped.code(), stopped.err());
      started.daemon().onExit().get(10, TimeUnit.SECONDS);
    }
  }

  @Test void startupDoesNotRetryANonBindFailure() {
    for (String stderr : List.of("Invalid configuration: deliberately invalid transport",
        "Address already in use", "Port 12345 is served by 'http' (PID 1234); stop it")) {
      int[] calls = {0};
      var failure = assertThrows(AssertionError.class, () -> startWithBindRetries(attempt -> {
        calls[0]++;
        return new Startup("pit", 12345, null, null, new Output(1, "", stderr), "", null);
      }));
      assertEquals(1, calls[0], "A non-bind startup failure must fail on its first attempt");
      assertTrue(failure.getMessage().contains(stderr), failure.getMessage());
    }
  }

  @Test void bindClassificationUsesTheJavaClassWithEitherLineEnding() {
    for (String message : List.of("Address already in use", "Address already in use: bind",
        "Only one usage of each socket address (protocol/network address/port) is normally permitted")) {
      for (String newline : List.of("\n", "\r\n")) {
        var startup = new Startup("pit", 12345, null, null, new Output(1, "", "Check logs"),
            "[main] ERROR Main - Fatal HTTP server error: " + message + newline
                + "java.net.BindException: " + message + newline, null);
        assertTrue(startup.bindFailed(), startup.daemonStderr());
      }
    }
    assertFalse(new Startup("pit", 12345, null, null, new Output(1, "", "Check logs"),
        "java.net.BindException: unrelated exception without a fatal HTTP error", null).bindFailed());
  }

  @Test void aPortTakenBeforeTheProbeRetriesOnlyTheExplicitOccupiedPortRefusal() throws Exception {
    int[] calls = {0};
    try (var held = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        var started = startWithBindRetries(attempt -> {
          calls[0]++;
          java.util.function.IntFunction<String> yaml = port ->
              "servers:\n  pit: {transport: http, port: " + port + "}\n";
          return attempt == 1 ? startAtPort("pit", yaml, false, held.getLocalPort())
              : startAttempt("pit", yaml, false);
        })) {
      assertEquals(2, calls[0]);
      assertNotEquals(held.getLocalPort(), started.port());
      assertNotNull(started.daemon());
      assertTrue(started.daemon().isAlive());
    }
  }

  @Test void anEarlierBindFailureDoesNotHideALaterConfigurationError() {
    int[] calls = {0};
    var failure = assertThrows(AssertionError.class, () -> startWithBindRetries(attempt -> {
      calls[0]++;
      return startAttempt("pit", port -> attempt == 1
          ? "servers:\n  pit: {transport: http, port: " + port + "}\n"
          : "servers:\n  other: {transport: http, port: " + port + "}\n", attempt == 1);
    }));
    assertEquals(2, calls[0], "An old bind error cannot turn a later configuration failure into a collision");
    assertTrue(failure.getMessage().contains("not retried"), failure.getMessage());
    assertTrue(failure.getMessage().contains("Unknown server configuration 'pit'"), failure.getMessage());
  }

  @Test void exhaustedCollisionsNameTheBindErrorAndEveryAttemptsStderr() {
    var attempts = new ArrayList<Startup>();
    var failure = assertThrows(AssertionError.class, () -> startWithBindRetries(attempt -> {
      var result = startAttempt("pit", port ->
          "servers:\n  pit: {transport: http, port: " + port + "}\n", true);
      attempts.add(result);
      return result;
    }));
    assertTrue(failure.getMessage().contains("java.net.BindException:"),
        "Startup must report the child's bind error, not an exit-code or cleanup assertion: " + failure.getMessage());
    assertEquals(START_ATTEMPTS, attempts.size(), "Collisions have a bounded retry count");
    for (int i = 0; i < attempts.size(); i++) {
      assertTrue(failure.getMessage().contains("Attempt " + (i + 1) + " (pit on port " + attempts.get(i).port() + ")"), failure.getMessage());
      assertTrue(failure.getMessage().contains(attempts.get(i).command().err()), failure.getMessage());
      assertTrue(failure.getMessage().contains(attempts.get(i).daemonStderr()), failure.getMessage());
    }
  }

  private Output daemonCommand(String verb, String name, Path config) throws Exception {
    return daemonProcess(List.of("-jar", System.getProperty("install.testJar"), verb, name,
        "--config", config.toString()), verb);
  }

  private Output daemonProcess(List<String> arguments, String label) throws Exception {
    var out = temp.resolve(label + ".stdout"); var err = temp.resolve(label + ".stderr");
    var command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow()));
    command.addAll(arguments);
    var builder = new ProcessBuilder(command)
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
