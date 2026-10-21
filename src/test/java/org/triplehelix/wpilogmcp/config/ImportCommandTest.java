/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.store.ImportCommand;

/** The import owner is the named answering server, even when a supervisor owns its lifecycle. */
class ImportCommandTest {
  @TempDir Path temp;
  private Set<Path> previous;
  private Path source, destination;
  private DaemonManager daemon;
  private HttpTransport server;
  private String serverHost = "127.0.0.1";
  private final ByteArrayOutputStream output = new ByteArrayOutputStream();

  @BeforeEach void prepare() throws Exception {
    temp = temp.toRealPath();
    var logs = LogManager.getInstance();
    previous = logs.getAllowedDirectories();
    logs.clearAllowedDirectories();
    logs.addAllowedDirectory(temp);
    source = ImportFixture.write(temp.resolve("source.wpilog"), 31);
    destination = temp.resolve("store");
    daemon = new DaemonManager(temp.resolve("run"));
  }

  @AfterEach void restore() {
    if (server != null) server.stop();
    var logs = LogManager.getInstance();
    logs.unloadAllLogs();
    logs.clearAllowedDirectories();
    previous.forEach(logs::addAllowedDirectory);
  }

  private void start(String name, boolean managed) throws IOException {
    start(name, managed, "127.0.0.1");
  }

  private void start(String name, boolean managed, String host) throws IOException {
    var registry = new ToolRegistry();
    registry.setManaged(managed);
    serverHost = host.contains(":") ? "[" + host + "]" : host;
    server = new HttpTransport(registry, 0, host, null, null, name);
    server.setStoreDirectories(Set.of(temp));
    server.start();
  }

  private static ServerConfig config(Path directory, int port) {
    return new ServerConfig("pit", List.of(directory.toString()), null, null, "http", port,
        null, null, true, false, null, null);
  }

  private static ImportCommand.Options options(Path source, Path destination) {
    return new ImportCommand.Options("pit", null, destination, false, "practice", List.of(source));
  }

  private int run(int port) throws Exception {
    try (var out = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      return ImportCommand.run(options(source, destination), config(temp, port), daemon, out);
    }
  }

  private String text() { return output.toString(StandardCharsets.UTF_8); }

  /** An HTTP job retained by this server proves routing; a successful offline copy does not. */
  private void assertServerJob(String printed) throws Exception {
    var accepted = printed.lines().filter(line -> line.startsWith("job "))
        .map(line -> JsonParser.parseString(line.substring(4)).getAsJsonObject())
        .findFirst().orElseThrow(() -> new AssertionError(
            "A named answering server must own the import job; command output:\n" + printed));
    String jobId = accepted.get("job_id").getAsString();
    assertEquals("/store/import/" + jobId, accepted.get("url").getAsString());
    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
        URI.create("http://" + serverHost + ":" + server.getPort() + accepted.get("url").getAsString()))
        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    var job = JsonParser.parseString(response.body()).getAsJsonObject();
    assertEquals(jobId, job.get("job_id").getAsString());
    assertEquals("done", job.get("state").getAsString(), response.body());
    var files = job.getAsJsonObject("result").getAsJsonArray("files");
    assertEquals(1, files.size(), response.body());
    var receipt = files.get(0).getAsJsonObject();
    assertEquals("imported", receipt.get("status").getAsString(), response.body());
    assertEquals(source, Path.of(receipt.get("original_path").getAsString()));
    var held = Path.of(receipt.get("path").getAsString());
    assertTrue(held.startsWith(destination), held.toString());
    assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(held));
    assertFalse(printed.contains("No running daemon"), printed);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void namedServerWithoutPidRecordOwnsTheImport(boolean managed) throws Exception {
    start("pit", managed);
    assertFalse(Files.exists(daemon.pidFilePath("pit")));
    assertEquals(0, run(server.getPort()), text());
    assertServerJob(text());
    assertFalse(Files.exists(daemon.pidFilePath("pit")),
        "Import must not adopt the answering server into launcher lifecycle ownership");
  }

  @Test void anotherNamedServerRefusesBeforeAnOfflineWriterStarts() throws Exception {
    start("other-pit", true);
    var refused = assertThrows(IOException.class, () -> run(server.getPort()),
        "A different named server answering on the configured port must refuse offline fallback");
    assertTrue(refused.getMessage().contains("pit"), refused.getMessage());
    assertTrue(refused.getMessage().contains("other-pit"), refused.getMessage());
    assertFalse(Files.exists(destination.resolve("store.json")), text());
    assertTrue(Files.exists(source));
  }

  @Test void strangerResponseRefusesBeforeAnOfflineWriterStarts() throws Exception {
    var healthRequests = new AtomicInteger();
    var stranger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stranger.createContext("/health", exchange -> {
      healthRequests.incrementAndGet();
      byte[] bytes = "another service".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (var body = exchange.getResponseBody()) { body.write(bytes); }
    });
    stranger.start();
    try {
      assertThrows(IOException.class, () -> run(stranger.getAddress().getPort()),
          "An answering port with an unrecognized health response must refuse offline fallback");
      assertTrue(healthRequests.get() > 0, "The configured port must be probed before refusing");
      assertFalse(Files.exists(destination.resolve("store.json")), text());
      assertTrue(Files.exists(source));
    } finally { stranger.stop(0); }
  }

  @Test void noAnswerExplicitlyUsesTheOfflineImporter() throws Exception {
    int port;
    try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    assertEquals(0, run(port), text());
    assertTrue(text().contains("No running daemon"), text());
    assertFalse(text().lines().anyMatch(line -> line.startsWith("job ")), text());
    var receipt = text().lines().filter(line -> line.startsWith("result "))
        .map(line -> JsonParser.parseString(line.substring(7)).getAsJsonObject())
        .reduce((first, last) -> last).orElseThrow();
    assertEquals("imported", receipt.getAsJsonArray("files").get(0).getAsJsonObject()
        .get("status").getAsString(), text());
    assertTrue(Files.exists(destination.resolve("store.json")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.0.0.0", "::"})
  void wildcardBindUsesLoopbackToReachTheNamedServer(String bind) throws Exception {
    start("pit", true);
    var ran = child(bind, server.getPort());
    assertEquals(0, ran.code(), ran.output());
    assertServerJob(ran.output());
  }

  @Test void ipv6LoopbackBindUsesTheConfiguredAddress() throws Exception {
    try {
      start("pit", true, "::1");
    } catch (java.net.BindException unavailable) {
      org.junit.jupiter.api.Assumptions.abort("IPv6 loopback is unavailable: " + unavailable.getMessage());
    }
    var ran = child("::1", server.getPort());
    assertEquals(0, ran.code(), ran.output());
    assertServerJob(ran.output());
  }

  @Test void specificNonLoopbackBindRefusesInsteadOfCreatingASecondWriter() throws Exception {
    int port;
    try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    var ran = child("192.0.2.1", port);
    assertEquals(1, ran.code(),
        "A specific non-loopback bind cannot safely use the loopback-only path-import route:\n"
            + ran.output());
    assertTrue(ran.output().contains("WPILOG_HTTP_BIND"), ran.output());
    assertTrue(ran.output().contains("loopback"), ran.output());
    assertFalse(Files.exists(destination.resolve("store.json")), ran.output());
    assertTrue(Files.exists(source));
  }

  private record Ran(int code, String output) {}

  /** Environment belongs to a child process, so these cases never alter another test's bind. */
  private Ran child(String bind, int port) throws Exception {
    var log = Files.createTempFile(temp, "import-command-", ".txt");
    var builder = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"),
        "-Duser.home=" + temp, "-cp", System.getProperty("java.class.path"),
        Child.class.getName(), temp.toString(), Integer.toString(port), source.toString(),
        destination.toString()).redirectErrorStream(true).redirectOutput(log.toFile());
    for (String name : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
        "WPILOG_DIR", "TBA_API_KEY", "WPILOG_HTTP_PATH", "WPILOG_DEBUG")) {
      builder.environment().remove(name);
    }
    builder.environment().put("WPILOG_HTTP_BIND", bind);
    var child = builder.start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Import command hung: " + Files.readString(log));
      return new Ran(child.exitValue(), Files.readString(log));
    } finally { child.destroyForcibly(); }
  }

  /** Invokes the public command API with isolated profile and daemon records. */
  public static final class Child {
    public static void main(String[] args) throws Exception {
      var root = Path.of(args[0]);
      int result;
      try {
        result = ImportCommand.run(options(Path.of(args[2]), Path.of(args[3])),
            config(root, Integer.parseInt(args[1])), new DaemonManager(root.resolve("child-run")),
            System.out);
      } catch (IOException refused) {
        System.out.println("refused: " + refused.getMessage());
        result = 1;
      }
      System.exit(result);
    }
  }
}
