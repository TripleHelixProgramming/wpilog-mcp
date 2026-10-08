/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreJson;

class StoreImportEndpointTest {
  @TempDir Path temp;
  Path root;
  Set<Path> previous;
  HttpTransport transport;
  final HttpClient client = HttpClient.newHttpClient();
  final LogManager manager = LogManager.getInstance();

  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath();
    root = temp.resolve("store");
    previous = manager.getAllowedDirectories();
    manager.clearAllowedDirectories();
    manager.addAllowedDirectory(temp);
    transport = new HttpTransport(new ToolRegistry(), 0);
    transport.start();
  }

  @AfterEach void stop() {
    transport.stop();
    manager.clearAllowedDirectories();
    previous.forEach(manager::addAllowedDirectory);
  }

  private HttpResponse<String> request(String path, String body, String... headers) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + transport.getPort() + path))
        .timeout(Duration.ofSeconds(10));
    if (body == null) builder.GET();
    else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonObject body(Path store, boolean move, Path... paths) {
    var body = StoreJson.JSON.toJsonTree(new LogStore.Request(List.of(paths), move, "practice")).getAsJsonObject();
    body.addProperty("store", store.toString());
    return body;
  }

  private String submit(Path store, boolean move, Path... paths) throws Exception {
    var response = request("/store/import", body(store, move, paths).toString());
    assertEquals(202, response.statusCode(), response.body());
    var accepted = JsonParser.parseString(response.body()).getAsJsonObject();
    String url = accepted.get("url").getAsString();
    assertEquals("/store/import/" + accepted.get("job_id").getAsString(), url);
    assertEquals(url, response.headers().firstValue("Location").orElseThrow());
    return url;
  }

  private JsonObject get(String url) throws Exception {
    var response = request(url, null);
    assertEquals(200, response.statusCode(), response.body());
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  private JsonObject await(String url, String state) throws Exception {
    var observed = new java.util.concurrent.atomic.AtomicReference<JsonObject>();
    org.triplehelix.wpilogmcp.harness.HarnessHttp.await("import job " + state, 10, () -> {
      var job = get(url); observed.set(job); return job.get("state").getAsString().equals(state);
    });
    return observed.get();
  }

  @Test void idleExitWaitsForAnHttpImportWithoutAnySession() throws Exception {
    transport.stop();
    var exited = new CountDownLatch(1);
    transport = new HttpTransport(new ToolRegistry(), 0);
    var idle = new HttpIdleProbe(transport);
    transport.setIdleExit(Duration.ofMillis(400), exited::countDown);
    transport.start();
    var input = ImportFixture.write(temp.resolve("idle.wpilog"), 15);
    String url;
    try (var reader = LogFileAccess.read(input)) {
      url = submit(root, true, input);
      await(url, "running");
      assertEquals(0, transport.sessionCount());
      idle.advance(Duration.ofMillis(900));
      assertEquals(1, exited.getCount(), "An import outlives the idle deadline");
      assertEquals(200, request("/health", null).statusCode());
    }
    var job = await(url, "done");
    assertEquals("imported", job.getAsJsonObject("result").getAsJsonArray("files").get(0)
        .getAsJsonObject().get("status").getAsString());
    idle.advance(Duration.ofMillis(900));
    assertEquals(0, exited.getCount(), "The daemon exits once its import finishes");
  }

  @Test void assignmentMovesOnlyUnassignedFilesAndKeepsOriginalProvenance() throws Exception {
    var original = ImportFixture.write(temp.resolve("unassigned.wpilog"), 20);
    var store = manager.stores().store(root);
    var unassigned = store.importPaths(new LogStore.Request(List.of(original), true, null), p -> {}).get()
        .files().get(0).path();
    var payload = body(root, true, unassigned);
    payload.remove("move");
    var response = request("/store/assign", payload.toString());
    assertEquals(202, response.statusCode(), response.body());
    var job = await(JsonParser.parseString(response.body()).getAsJsonObject().get("url").getAsString(), "done");
    var file = job.getAsJsonObject("result").getAsJsonArray("files").get(0).getAsJsonObject();
    assertEquals("imported", file.get("status").getAsString(), job.toString());
    var destination = Path.of(file.get("path").getAsString());
    assertTrue(destination.startsWith(root.resolve("robots").resolve("practice")));
    assertFalse(Files.exists(unassigned));
    assertFalse(Files.exists(unassigned.getParent().resolve("import.json")));
    var manifest = JsonParser.parseString(Files.readString(destination.getParent().getParent().resolve("session.json")))
        .getAsJsonObject().getAsJsonArray("files").get(0).getAsJsonObject();
    assertEquals(original.toString(), manifest.getAsJsonObject("provenance").get("original_path").getAsString());
    var denied = request("/store/assign", body(root, true, destination).toString());
    var refused = await(JsonParser.parseString(denied.body()).getAsJsonObject().get("url").getAsString(), "done");
    assertEquals("refused", refused.getAsJsonObject("result").getAsJsonArray("files").get(0)
        .getAsJsonObject().get("status").getAsString());
    assertTrue(Files.exists(destination));
    var missingRobot = payload.deepCopy(); missingRobot.remove("stated_robot");
    assertEquals(400, request("/store/assign", missingRobot.toString()).statusCode());
    assertEquals(403, request("/store/assign", payload.toString(), "Origin", "https://untrusted.example").statusCode());
  }

  @Test void fullResultAndFinalProgressEqualTheJavaImporter() throws Exception {
    var input = ImportFixture.write(temp.resolve("one.wpilog"), 1);
    var reference = temp.resolve("reference");
    var expected = manager.stores().store(reference).importPaths(
        new LogStore.Request(List.of(input), false, "practice"), p -> {}).get();
    var job = await(submit(root, false, input), "done");
    var normalized = JsonParser.parseString(StoreJson.JSON.toJson(expected)
        .replace(StoreJson.JSON.toJson(reference.toString()).substring(1,
            StoreJson.JSON.toJson(reference.toString()).length() - 1),
            StoreJson.JSON.toJson(root.toString()).substring(1, StoreJson.JSON.toJson(root.toString()).length() - 1)));
    assertEquals(normalized, job.get("result"));
    assertEquals("complete", job.getAsJsonObject("progress").get("phase").getAsString());
    assertEquals(root.toString(), job.getAsJsonObject("progress").get("path").getAsString());
    assertEquals(1, job.getAsJsonObject("progress").get("completed").getAsInt());
    assertEquals(1, job.getAsJsonObject("progress").get("total").getAsInt());
    assertTrue(Files.exists(input));
  }

  @Test void secondHttpRequestStaysQueuedBehindRunningMove() throws Exception {
    var first = ImportFixture.write(temp.resolve("first.wpilog"), 2);
    var second = ImportFixture.write(temp.resolve("second.wpilog"), 3);
    String a;
    String b;
    try (var reader = LogFileAccess.read(first)) {
      a = submit(root, true, first);
      await(a, "running");
      b = submit(root, true, second);
      assertEquals("queued", get(b).get("state").getAsString());
      assertTrue(get(b).get("progress").isJsonNull());
      assertTrue(Files.exists(second));
    }
    assertEquals("imported", await(a, "done").getAsJsonObject("result").getAsJsonArray("files")
        .get(0).getAsJsonObject().get("status").getAsString());
    await(b, "done");
    assertFalse(Files.exists(first));
    assertFalse(Files.exists(second));
  }

  @Test void wholeDirectoryImportExcludesStoreControlFiles() throws Exception {
    var input = ImportFixture.write(root.resolve("one.wpilog"), 10);
    var job = await(submit(root, false, root), "done");
    var files = job.getAsJsonObject("result").getAsJsonArray("files");
    assertEquals(1, files.size(), job.toString());
    assertEquals(input.toString(), files.get(0).getAsJsonObject().get("original_path").getAsString());
    assertEquals("imported", files.get(0).getAsJsonObject().get("status").getAsString());
    assertEquals(1, job.getAsJsonObject("progress").get("total").getAsInt());
    assertTrue(Files.exists(root.resolve("store.lock")));
    assertTrue(Files.exists(root.resolve("store.json")));
  }

  @Test void malformedBodiesAre400() {
    var valid = body(root, false);
    var badMove = valid.deepCopy(); badMove.addProperty("move", "false");
    var badPaths = valid.deepCopy(); badPaths.addProperty("paths", "x");
    var badRobot = valid.deepCopy(); badRobot.addProperty("stated_robot", 42);
    var badStore = valid.deepCopy(); badStore.addProperty("store", true);
    var tooLarge = valid.deepCopy(); tooLarge.addProperty("padding", "x".repeat(1024 * 1024));
    var badName = valid.deepCopy(); badName.addProperty("stated_robot", "../escape");
    assertAll(List.of("{", "[]", "null", "{}", valid.toString().replace('"', '\''),
        valid + " trailing", badMove.toString(), badPaths.toString(), badName.toString(),
        badRobot.toString(), badStore.toString(), tooLarge.toString()).stream()
        .map(value -> (org.junit.jupiter.api.function.Executable) () -> {
          var response = request("/store/import", value);
          assertEquals(400, response.statusCode());
          var refused = JsonParser.parseString(response.body()).getAsJsonObject();
          assertTrue(refused.has("error"));
          assertTrue(refused.has("hint"));
        }));
    assertFalse(Files.exists(root.resolve("store.json")));
  }

  @Test void outsidePathsAndStoresAre403WithInboxHintAndNoWrites() throws Exception {
    manager.clearAllowedDirectories();
    Files.createDirectories(root);
    manager.addAllowedDirectory(root);
    var outside = ImportFixture.write(temp.resolve("outside.wpilog"), 4);
    assertAll(List.of(body(root, true, outside), body(temp.resolve("outside-store"), false)).stream()
        .map(value -> (org.junit.jupiter.api.function.Executable) () -> {
          var response = request("/store/import", value.toString());
          assertEquals(403, response.statusCode(), response.body());
          assertTrue(JsonParser.parseString(response.body()).getAsJsonObject().get("hint").getAsString().contains("inbox"));
        }));
    assertTrue(Files.exists(outside));
    assertFalse(Files.exists(root.resolve("store.json")));
    manager.clearAllowedDirectories();
    assertEquals(403, request("/store/import", body(root, false).toString()).statusCode());
  }

  @Test void symlinkEscapeIs403() throws Exception {
    manager.clearAllowedDirectories();
    Files.createDirectories(root);
    manager.addAllowedDirectory(root);
    var outside = ImportFixture.write(temp.resolve("outside.wpilog"), 5);
    var link = root.resolve("alias.wpilog");
    try { Files.createSymbolicLink(link, outside); }
    catch (UnsupportedOperationException | java.io.IOException e) {
      org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable: " + e.getMessage());
    }
    assertEquals(403, request("/store/import", body(root, true, link).toString()).statusCode());
    assertTrue(Files.exists(outside));
  }

  @ParameterizedTest
  @CsvSource({"/store/import, false", "/store/import, true", "/store/assign, false", "/store/assign, true"})
  void symlinkLockIsRefusedBeforeJobAdmission(String route, boolean targetExists) throws Exception {
    Files.createDirectories(root);
    manager.clearAllowedDirectories();
    manager.addAllowedDirectory(root);
    manager.stores().store(root); // Admission must recheck even a previously registered store.
    var outside = temp.resolve("outside-lock");
    if (targetExists) Files.writeString(outside, "untouched sentinel");
    var link = root.resolve("store.lock");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("Symlinks unavailable: " + e.getMessage());
    }
    var response = request(route, body(root, true).toString());
    assertTrue(response.statusCode() >= 400 && response.statusCode() < 500, response.toString());
    var refused = JsonParser.parseString(response.body()).getAsJsonObject();
    assertTrue(refused.get("error").getAsString().contains("store.lock"), response.body());
    assertTrue(refused.get("error").getAsString().contains("symbolic link"), response.body());
    assertTrue(refused.has("hint"));
    assertEquals(targetExists, Files.exists(outside), "a dangling lock must not create its target");
    if (targetExists) assertEquals("untouched sentinel", Files.readString(outside));
    assertTrue(Files.isSymbolicLink(link));
    assertFalse(Files.exists(root.resolve("store.json")));
    assertFalse(Files.exists(root.resolve("inbox")));
  }

  @Test void unknownJobAndOriginRefusalsMatchTransport() throws Exception {
    var unknown = request("/store/import/missing", null);
    assertEquals(404, unknown.statusCode());
    assertTrue(JsonParser.parseString(unknown.body()).getAsJsonObject().has("hint"));
    var mcp = request("/mcp", "{}", "Origin", "https://untrusted.example");
    assertAll(List.of("/store/import", "/store/import/missing").stream()
        .map(path -> (org.junit.jupiter.api.function.Executable) () -> {
          var response = request(path, path.endsWith("missing") ? null : body(root, false).toString(),
              "Origin", "https://untrusted.example");
          assertEquals(403, response.statusCode());
          assertEquals(mcp.body(), response.body());
        }));
    assertFalse(Files.exists(root));
  }

  @Test void heldLockFailsTheJobWithoutCreatingAStore() throws Exception {
    Files.createDirectories(root);
    try (var channel = FileChannel.open(root.resolve("store.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
         var lock = channel.lock()) {
      var job = await(submit(root, false), "failed");
      assertTrue(job.get("error").getAsString().contains("Store lock is held"));
      assertTrue(job.get("result").isJsonNull());
      assertFalse(Files.exists(root.resolve("store.json")));
    }
    await(submit(root, false), "done");
  }

  @Test void boundedHistoryKeepsActiveJobsAndDisappearsAtRestart() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var blocking = manager.stores().store(root).importPaths(new LogStore.Request(List.of(), false, null), p -> {
      if (!p.phase().equals("starting")) return;
      entered.countDown();
      try { if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out"); }
      catch (InterruptedException e) { throw new IllegalStateException(e); }
    });
    var jobs = new ArrayList<String>();
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      for (int i = 0; i < StoreImportEndpoint.HISTORY_SIZE; i++) jobs.add(submit(root, false));
      assertEquals(503, request("/store/import", body(root, false).toString()).statusCode());
      assertEquals("queued", get(jobs.get(0)).get("state").getAsString());
    } finally { release.countDown(); }
    blocking.get(10, TimeUnit.SECONDS);
    await(jobs.get(jobs.size() - 1), "done");
    String newest = submit(root, false);
    await(newest, "done");
    assertEquals(404, request(jobs.get(0), null).statusCode());
    transport.stop();
    transport = new HttpTransport(new ToolRegistry(), 0);
    transport.start();
    assertEquals(404, request(newest, null).statusCode());
  }
}
