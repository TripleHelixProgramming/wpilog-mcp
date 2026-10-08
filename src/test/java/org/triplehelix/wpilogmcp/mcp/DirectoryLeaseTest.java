/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.tba.TbaClient;
import org.triplehelix.wpilogmcp.tools.CoreTools;
import org.triplehelix.wpilogmcp.tools.TbaTools;

/** Requests cross the actual HTTP and tool boundaries, including reads from an already cached log. */
class DirectoryLeaseTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance();
  final LogDirectory directory = LogDirectory.getInstance();
  final HttpClient client = HttpClient.newHttpClient();
  Set<Path> previous;
  List<String> previousListing;
  Integer previousTeam;
  HttpTransport transport;

  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath();
    previous = logs.getAllowedDirectories();
    previousListing = directory.getLogDirectories().stream().map(Path::toString).toList();
    previousTeam = directory.getDefaultTeamNumber();
    logs.clearAllowedDirectories();
    directory.setLogDirectories(List.of());
    directory.setDefaultTeamNumber(99);
    var registry = new ToolRegistry();
    CoreTools.registerAll(registry);
    TbaTools.registerAll(registry);
    transport = new HttpTransport(registry, 0);
    transport.start();
  }

  @AfterEach void stop() {
    transport.stop();
    logs.unloadAllLogs();
    logs.clearAllowedDirectories();
    previous.forEach(logs::addAllowedDirectory);
    directory.setLogDirectories(previousListing);
    directory.setDefaultTeamNumber(previousTeam);
    directory.clearCache();
  }

  HttpResponse<String> request(String method, String route, String session, String body,
      String... headers) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + transport.getPort() + route))
        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    if (session != null) builder.header("Mcp-Session-Id", session);
    for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  String session() throws Exception {
    var response = request("POST", "/mcp", null,
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    assertEquals(200, response.statusCode());
    return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
  }

  String registration(Path path, int team) {
    var value = new JsonObject();
    var paths = new JsonArray();
    paths.add(path.toString());
    value.add("paths", paths);
    value.addProperty("team", team);
    return value.toString();
  }

  JsonObject tool(String session, String name, JsonObject args) throws Exception {
    var params = new JsonObject();
    params.addProperty("name", name);
    params.add("arguments", args);
    var message = new JsonObject();
    message.addProperty("jsonrpc", "2.0");
    message.addProperty("id", 2);
    message.addProperty("method", "tools/call");
    message.add("params", params);
    var response = request("POST", "/mcp", session, message.toString());
    assertEquals(200, response.statusCode(), response.body());
    var result = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result");
    return JsonParser.parseString(result.getAsJsonArray("content").get(0).getAsJsonObject()
        .get("text").getAsString()).getAsJsonObject();
  }

  JsonObject listing(String session) throws Exception {
    return tool(session, "list_available_logs", new JsonObject());
  }

  JsonObject entries(String session, Path file) throws Exception {
    var args = new JsonObject();
    args.addProperty("path", file.toString());
    return tool(session, "list_entries", args);
  }

  @Test void directoriesAreSharedAndMetadataUsesEachPathsTeamWithoutCachingIt() throws Exception {
    var configured = Files.createDirectory(temp.resolve("configured"));
    directory.setLogDirectory(configured.toString());
    logs.addAllowedDirectory(configured);
    var first = Files.createDirectory(temp.resolve("first"));
    var second = Files.createDirectory(temp.resolve("second"));
    ImportFixture.write(first.resolve("first.wpilog"), 1);
    ImportFixture.write(second.resolve("second.wpilog"), 2);
    String a = session();
    String b = session();
    String observer = session();
    assertEquals(200, request("POST", "/directories", a, registration(first, 11)).statusCode());
    assertEquals(200, request("POST", "/directories", b, registration(second, 22)).statusCode());
    var result = listing(observer);
    var origins = result.getAsJsonArray("log_directories");
    assertEquals(3, origins.size(), result.toString());
    assertEquals("configured", origins.get(0).getAsJsonObject().get("origin").getAsString());
    assertEquals(99, origins.get(0).getAsJsonObject().get("team").getAsInt());
    assertEquals(first.toString(), origins.get(1).getAsJsonObject().get("path").getAsString());
    assertEquals("leased", origins.get(1).getAsJsonObject().get("origin").getAsString());
    assertEquals(11, origins.get(1).getAsJsonObject().get("team").getAsInt());
    assertEquals(List.of(11, 22), result.getAsJsonArray("logs").asList().stream()
        .map(log -> log.getAsJsonObject().get("team_number").getAsInt()).sorted().toList());
    assertEquals(List.of(configured.toString(), first.toString(), second.toString()),
        result.getAsJsonArray("log_directory_paths").asList().stream().map(v -> v.getAsString()).toList());
    var replacement = JsonParser.parseString(registration(first, 33)).getAsJsonObject();
    var perPath = new JsonObject();
    perPath.addProperty("path", second.toString());
    perPath.addProperty("team", 44);
    replacement.getAsJsonArray("paths").add(perPath);
    assertEquals(200, request("POST", "/directories", a, replacement.toString()).statusCode());
    assertEquals(List.of(33, 44), listing(observer).getAsJsonArray("logs").asList().stream()
        .map(log -> log.getAsJsonObject().get("team_number").getAsInt()).sorted().toList());
    var nested = Files.createDirectory(first.resolve("nested"));
    ImportFixture.write(nested.resolve("nested.wpilog"), 4);
    assertEquals(200, request("POST", "/directories", b, registration(nested, 55)).statusCode());
    assertEquals(200, request("POST", "/directories", a, replacement.toString()).statusCode());
    assertEquals(List.of(33, 44, 55), listing(observer).getAsJsonArray("logs").asList().stream()
        .map(log -> log.getAsJsonObject().get("team_number").getAsInt()).sorted().toList());
    request("DELETE", "/mcp", b, null);
    assertEquals(List.of(33, 33, 44), listing(observer).getAsJsonArray("logs").asList().stream()
        .map(log -> log.getAsJsonObject().get("team_number").getAsInt()).sorted().toList());
  }

  @ParameterizedTest @ValueSource(strings = {"DELETE_SESSION", "EXPIRE", "DELETE_DIRECTORIES", "REPLACE"})
  void endingALeaseRevokesCachedReadsAndTheListing(String mode) throws Exception {
    var file = ImportFixture.write(temp.resolve("leased.wpilog"), 3);
    String owner = session();
    assertEquals(200, request("POST", "/directories", owner, registration(temp, 12)).statusCode());
    assertEquals("ok", entries(owner, file).get("status").getAsString());
    switch (mode) {
      case "DELETE_SESSION" -> assertEquals(200, request("DELETE", "/mcp", owner, null).statusCode());
      case "EXPIRE" -> assertEquals(1, transport.expireSessions(Duration.ofNanos(-1)));
      case "DELETE_DIRECTORIES" -> assertEquals(200, request("DELETE", "/directories", owner, null).statusCode());
      case "REPLACE" -> assertEquals(200, request("POST", "/directories", owner, "{\"paths\":[]}").statusCode());
      default -> fail(mode);
    }
    String observer = session();
    var refused = entries(observer, file);
    assertEquals("error", refused.get("status").getAsString(), refused.toString());
    assertTrue(refused.get("error").getAsString().contains("outside configured log directories"));
    assertEquals("error", listing(observer).get("status").getAsString());
  }

  @Test void aLoggedTeamWinsAndRegistrationResolvesSymlinks() throws Exception {
    var root = Files.createDirectory(temp.resolve("logs"));
    try (var writer = new WpilogWriter(root.resolve("logged.wpilog"), "lease test")) {
      int team = writer.start("/SystemStats/TeamNumber", "int64", "", 0);
      writer.append(team, 0, WpilogWriter.encodeInt64(77));
    }
    Path alias = temp.resolve("alias");
    try {
      Files.createSymbolicLink(alias, root);
    } catch (UnsupportedOperationException | FileSystemException e) {
      Assumptions.abort("Symlinks unavailable: " + e);
    }
    var outside = ImportFixture.write(temp.resolve("outside.wpilog"), 4);
    Files.createSymbolicLink(root.resolve("outside.wpilog"), outside);
    String session = session();
    assertEquals(200, request("POST", "/directories", session, registration(alias, 11)).statusCode());
    var result = listing(session);
    assertEquals(root.toString(), result.getAsJsonArray("log_directory_paths").get(0).getAsString());
    assertEquals(1, result.getAsJsonArray("logs").size(), "An outside symlink is never read or listed");
    assertEquals(77, result.getAsJsonArray("logs").get(0).getAsJsonObject().get("team_number").getAsInt());
  }

  @ParameterizedTest @ValueSource(strings = {"/directories", "/tba-key"})
  void routesNeedALiveSessionAndTheOriginGateAndLoopbackBinding(String route) throws Exception {
    String body = route.equals("/directories") ? registration(temp, 1) : "{\"key\":\"test-key\"}";
    assertEquals(400, request("POST", route, null, body).statusCode());
    assertEquals(404, request("POST", route, "gone", body).statusCode());
    String session = session();
    assertEquals(403, request("POST", route, session, body, "Origin", "https://attacker.example").statusCode());
    assertEquals(200, request("POST", route, session, body, "Origin", "http://localhost").statusCode());
    transport.stop();
    transport = new HttpTransport(new ToolRegistry(), 0, "0.0.0.0", Set.of(), null);
    transport.start();
    assertEquals(403, request("POST", route, session(), body).statusCode());
  }

  @Test void invalidDirectoriesAreAtomicAndNameTheRefusedPath() throws Exception {
    String session = session();
    var missing = temp.resolve("missing");
    var body = JsonParser.parseString(registration(temp, 1)).getAsJsonObject();
    body.getAsJsonArray("paths").add(missing.toString());
    var response = request("POST", "/directories", session, body.toString());
    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains(missing.toString().replace("\\", "\\\\")), response.body());
    assertTrue(directory.getLogDirectories().isEmpty());
    var file = Files.writeString(temp.resolve("file"), "x");
    assertEquals(400, request("POST", "/directories", session, registration(file, 1)).statusCode());
    assertEquals(400, request("POST", "/directories", session, registration(Path.of("relative"), 1)).statusCode());
    for (String invalid : List.of("[]", "null", "{", "{}", "{\"paths\":[false]}",
        "{\"paths\":[],\"team\":1.5}", "{\"paths\":[],\"team\":0}", "{\"paths\":[],\"team\":\"2\"}")) {
      assertEquals(400, request("POST", "/directories", session, invalid).statusCode(), invalid);
    }
  }

  @Test void leasedStoresImportAndWatchTheInboxAndStopWhenTheLeaseEnds() throws Exception {
    transport.stop();
    try (var poller = new org.triplehelix.wpilogmcp.store.StorePollerProbe(logs.stores())) {
    var registry = new ToolRegistry(); CoreTools.registerAll(registry); TbaTools.registerAll(registry);
    transport = new HttpTransport(registry, 0); transport.start();
    var root = Files.createDirectory(temp.resolve("store"));
    var source = ImportFixture.write(root.resolve("source.wpilog"), 5);
    String owner = session();
    assertEquals(200, request("POST", "/directories", owner, registration(root, 11)).statusCode());
    var body = JsonParser.parseString(registration(source, 11)).getAsJsonObject();
    body.remove("team");
    body.addProperty("store", root.toString());
    body.addProperty("move", true);
    body.addProperty("stated_robot", "practice");
    var submitted = request("POST", "/store/import", null, body.toString());
    assertEquals(202, submitted.statusCode(), submitted.body());
    logs.stores().awaitImports();
    var result = listing(owner);
    assertEquals(root.toString(), result.getAsJsonArray("stores").get(0).getAsJsonObject().get("path").getAsString());
    var dropped = ImportFixture.write(root.resolve("inbox").resolve("drop.wpilog"), 6);
    var receipt = root.resolve("inbox").resolve("imported.log");
    poller.advance(); poller.advance(); logs.stores().awaitImports();
    assertTrue(Files.exists(receipt), "The live poller must watch a leased store");
    assertFalse(Files.exists(dropped));
    var receiptEntry = JsonParser.parseString(Files.readAllLines(receipt).get(0)).getAsJsonObject();
    assertEquals("unassigned", receiptEntry.get("status").getAsString());
    assertTrue(Files.isRegularFile(Path.of(receiptEntry.get("path").getAsString())));
    request("DELETE", "/directories", owner, null);
    assertEquals(403, request("POST", "/store/import", null, body.toString()).statusCode());
    }
  }

  @Test void keyOverridesTheFileWithoutEnteringResultsOrDiagnosticsAndFallsBackOnRemoval() throws Exception {
    var seen = new AtomicReference<String>();
    var api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    api.createContext("/status", exchange -> {
      seen.set(exchange.getRequestHeaders().getFirst("X-TBA-Auth-Key"));
      byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var out = exchange.getResponseBody()) { out.write(body); }
    });
    api.start();
    var tba = TbaClient.getInstance();
    tba.configure("file-test-key");
    tba.setBaseUrl("http://127.0.0.1:" + api.getAddress().getPort());
    var stderr = System.err;
    var captured = new ByteArrayOutputStream();
    try (var output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setErr(output);
      String first = session();
      String second = session();
      for (String key : List.of("lease-first-secret", "lease-second-secret")) {
        var response = request("POST", "/tba-key", key.contains("first") ? first : second,
            "{\"key\":\"" + key + "\"}");
        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains(key));
        var result = tool(first, "get_tba_status", new JsonObject());
        assertEquals(key, seen.get());
        assertFalse(result.toString().contains(key), result.toString());
      }
      for (String invalid : List.of("{}", "[]", "{\"key\":false}", "{\"key\":3}", "{\"key\":\"\"}",
          "{\"key\":\"lease-secret\\nbad\"}")) {
        var refused = request("POST", "/tba-key", first, invalid);
        assertEquals(400, refused.statusCode(), refused.body());
        assertFalse(refused.body().contains("lease-secret"));
      }
      var malformed = request("POST", "/tba-key", first, "{\"key\":\"lease-secret-malformed");
      assertEquals(400, malformed.statusCode());
      assertFalse(malformed.body().contains("lease-secret-malformed"));
      request("DELETE", "/mcp", second, null);
      tool(first, "get_tba_status", new JsonObject());
      assertEquals("lease-first-secret", seen.get());
      assertEquals(200, request("POST", "/tba-key", first, "{\"key\":null}").statusCode());
      tool(first, "get_tba_status", new JsonObject());
      assertEquals("file-test-key", seen.get());
      assertTrue(captured.toString(StandardCharsets.UTF_8).contains("Created session"),
          "The test must capture the server's real logger, not an unused stream");
      assertFalse(captured.toString(StandardCharsets.UTF_8).contains("secret"));
    } finally {
      System.setErr(stderr);
      api.stop(0);
      tba.setBaseUrl(null);
      tba.configure(null);
    }
  }
}
