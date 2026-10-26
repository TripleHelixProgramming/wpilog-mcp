/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.*;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class ReplicaNetworkTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  Set<Path> previous;
  final List<HttpTransport> servers = new ArrayList<>();
  final List<LogStore> stores = new ArrayList<>();
  @BeforeEach void setup() throws Exception { temp = temp.toRealPath(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp); }
  @AfterEach void cleanup() throws Exception {
    servers.forEach(HttpTransport::stop); for (var store : stores) manager.stores().release(store.root()); manager.unloadAllLogs();
    manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
  String id() { return UUID.randomUUID().toString(); }
  /** A served store is the one its transport finds through the process's registry, as in a server. */
  LogStore store(String name) throws Exception {
    var store = manager.stores().store(temp.resolve(name)); stores.add(store); return store;
  }
  HttpTransport serve(LogStore store, int port) throws Exception {
    var registry = new ToolRegistry(); org.triplehelix.wpilogmcp.tools.StatisticsTools.registerAll(registry);
    var server = new HttpTransport(registry, port); server.setStoreDirectories(Set.of(store.root())); server.start(); servers.add(server); return server;
  }
  String url(HttpTransport server) { return "http://127.0.0.1:" + server.getPort(); }
  MirrorConfig mirror(LogStore store, String master, String origin, List<String> peers) throws Exception {
    var header = new Header(2, "2026-10-19T00:00:00Z", id(), List.of(), Map.of(), true, List.of(),
        new MirrorOrigin(master, origin, null, List.of(), Map.of()), new ReplicaCatalog(1, id(), 1, List.of(), List.of(), List.of()));
    new StoreFiles(store.root(), manager.testGetSecurityValidator()).write(store.root().resolve("store.json"), header);
    return new MirrorConfig(origin, store.root(), 14, 20_000_000, List.of(), List.of(), 30, 0, "all", 1_048_576, peers, false);
  }
  @Test void unchangedPassRetainsConfirmationsRevisionAndReceiptsWithoutHashingAgain() throws Exception {
    var master = store("unchanged-master");
    for (int i = 0; i < 8; i++) master.importPaths(new LogStore.Request(List.of(ImportFixture.write(temp.resolve("unchanged-" + i + ".wpilog"), i)), false, "RIO"), p -> {}).get();
    master.migrateReplicas(master.replicaHeader().id()).get();
    var http = serve(master, 0); var mirror = store("unchanged-mirror");
    var policy = mirror(mirror, master.replicaHeader().id(), url(http), List.of());
    assertEquals("synchronized", mirror.mirror(policy, p -> {}).get(30, TimeUnit.SECONDS).state());
    var catalog = mirror.replicaHeader().replicas();
    assertEquals("synchronized", mirror.mirror(policy, p -> {}).get(30, TimeUnit.SECONDS).state());
    assertEquals(catalog, mirror.replicaHeader().replicas(), "an unchanged pass cannot replace confirmation times or catalog revision");
    var io = new StoreFiles(mirror.root(), manager.testGetSecurityValidator()); var before = ReplicaJournal.read(mirror.root(), io);
    long hashed = mirror.replicaTransfers().verificationBytes();
    assertEquals("synchronized", mirror.mirror(policy, p -> {}).get(30, TimeUnit.SECONDS).state());
    var after = ReplicaJournal.read(mirror.root(), io);
    assertEquals(before.receipts(), after.receipts()); assertEquals(before.confirmations(), after.confirmations());
    assertEquals(before.observations(), after.observations(), "unchanged files need no per-file journal churn");
    assertEquals(hashed, mirror.replicaTransfers().verificationBytes(), "unchanged boundaries reuse verified hash admission");
    assertEquals(8, after.coverage().selectedFiles()); assertEquals(8, after.coverage().heldMatchingFiles());
  }

  @Test void threePeerHopsStayProvisionalUntilDirectMasterCorrectionAndNeverSubmitUpstream() throws Exception {
    var master = store("master"); var correctPath = ImportFixture.write(temp.resolve("correct.wpilog"), 10);
    master.importPaths(new LogStore.Request(List.of(correctPath), false, "RIO"), p -> {}).get();
    master.migrateReplicas(master.replicaHeader().id()).get();
    var authoritative = master.replicaHeader().replicas().files().get(0);
    var origin = serve(master, 0); int port = origin.getPort(); String address = url(origin); origin.stop();
    var a = store("A"); var aPolicy = mirror(a, master.replicaHeader().id(), address, List.of());
    byte[] candidate = Files.readAllBytes(ImportFixture.write(temp.resolve("candidate.wpilog"), 90));
    String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(candidate));
    var offer = new ReplicaTransfers.Offer(authoritative.identity(), "candidate.wpilog", hash, candidate.length, "wpilog", "completed_import", "initial_peer", false);
    a.replicaTransfers().transfer(aPolicy, id(), offer, offset -> new ByteArrayInputStream(candidate, (int) offset, candidate.length - (int) offset)).get();
    var aHttp = serve(a, 0);
    var b = store("B"); var configured = mirror(b, master.replicaHeader().id(), address, List.of());
    var bPolicy = new MirrorConfig(configured.origin(), configured.folder(), configured.days(), configured.maxSizeBytes(), configured.robots(), configured.events(),
        configured.intervalSec(), configured.rateBytes(), configured.scope(), configured.metadataReserveBytes(), List.of(), true);
    var first = b.mirror(bPolicy, p -> {}, java.time.Clock.systemUTC(), StoreSync::http, selectedMaster -> {
      assertEquals(authoritative.identity().masterStoreId(), selectedMaster); return List.of(url(aHttp));
    }).get(20, TimeUnit.SECONDS);
    assertEquals("partial", first.state()); assertEquals(candidate.length, first.bytesCopied()); assertNull(first.lastSync());
    var bFile = b.replicaHeader().replicas().files().get(0); assertEquals("provisional", bFile.verificationBasis()); assertNull(bFile.confirmation());
    var bHttp = serve(b, 0);
    var c = store("C"); var cPolicy = mirror(c, master.replicaHeader().id(), address, List.of(url(bHttp)));
    var cHttp = serve(c, 0);
    assertEquals(candidate.length, c.mirror(cPolicy, p -> {}).get(20, TimeUnit.SECONDS).bytesCopied());
    var cFile = c.replicaHeader().replicas().files().get(0);
    assertEquals("provisional", cFile.verificationBasis()); assertNull(cFile.confirmation()); assertEquals(authoritative.identity(), cFile.identity());
    assertArrayEquals(candidate, Files.readAllBytes(c.root().resolve(cFile.path())));
    var provisionalAnswer = read(cHttp, c, cFile, 90);
    assertEquals(0, c.mirror(cPolicy, p -> {}).get(20, TimeUnit.SECONDS).bytesCopied());
    // The configured origin returns at the same address. A direct receipt corrects provisional
    // content, and the peer's contrary candidate is then refused without changing that generation.
    serve(master, port);
    var reconciled = c.mirror(cPolicy, p -> {}).get(20, TimeUnit.SECONDS);
    var corrected = c.replicaHeader().replicas().files().get(0);
    assertEquals("verified", corrected.verificationBasis()); assertEquals(authoritative.sha256(), corrected.sha256());
    assertNotEquals(cFile.path(), corrected.path()); assertEquals(cFile.identity(), corrected.identity());
    assertEquals(address, corrected.confirmation().obtainedFrom());
    assertArrayEquals(Files.readAllBytes(correctPath), Files.readAllBytes(c.root().resolve(corrected.path())));
    assertEquals(List.of(authoritative), master.replicaHeader().replicas().files(), "peer candidates never submit or rewrite master content");
    assertTrue(reconciled.refusals().stream().anyMatch(s -> s.contains("MASTER_MISMATCH")), reconciled.toString());
    read(cHttp, c, corrected, 10);
    assertEquals(cFile.sha256(), provisionalAnswer.getAsJsonObject("inputs").getAsJsonObject("replica").get("content_sha256").getAsString(), "returned answers retain their admitted basis");
    manager.unloadAllLogs();
    Files.write(c.root().resolve(corrected.path()), candidate);
    var args = new com.google.gson.JsonObject(); args.addProperty("path", c.root().resolve(corrected.path()).toString());
    args.addProperty("scope_id", c.replicaHeader().id()); args.addProperty("name", "/Value");
    var tools = new ToolRegistry(); org.triplehelix.wpilogmcp.tools.StatisticsTools.registerAll(tools);
    var refused = tools.getTool("get_statistics").execute(args).getAsJsonObject();
    assertEquals("error", refused.get("status").getAsString(), "changed bytes cannot retain a verified label in ordinary analysis");
  }
  com.google.gson.JsonObject read(HttpTransport http, LogStore store, ReplicaCatalog.Entry file, int first) throws Exception {
    var client = java.net.http.HttpClient.newHttpClient(); String scope = store.replicaHeader().id();
    var init = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(http) + "/mcp"))
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"replica-test\",\"version\":\"1\"}}}" )).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    var args = new com.google.gson.JsonObject(); args.addProperty("path", store.root().resolve(file.path()).toString()); args.addProperty("scope_id", scope); args.addProperty("name", "/Value");
    var request = new com.google.gson.JsonObject(); request.addProperty("jsonrpc", "2.0"); request.addProperty("id", 2); request.addProperty("method", "tools/call");
    var params = new com.google.gson.JsonObject(); params.addProperty("name", "get_statistics"); params.add("arguments", args); request.add("params", params);
    var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(http) + "/mcp"))
        .header("Mcp-Session-Id", init.headers().firstValue("Mcp-Session-Id").orElseThrow()).POST(java.net.http.HttpRequest.BodyPublishers.ofString(request.toString())).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    var result = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result");
    var answer = com.google.gson.JsonParser.parseString(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
    assertEquals("ok", answer.get("status").getAsString(), answer.toString());
    assertEquals(first + 0.5, answer.get("mean").getAsDouble(), 0);
    var facts = answer.getAsJsonObject("inputs").getAsJsonObject("replica");
    assertEquals(file.sha256(), facts.get("content_sha256").getAsString()); assertEquals(file.verificationBasis(), facts.get("authority_state").getAsString());
    assertEquals(scope, facts.get("serving_scope_id").getAsString());
    String query = "path=" + java.net.URLEncoder.encode(store.root().resolve(file.path()).toString(), java.nio.charset.StandardCharsets.UTF_8) + "&scope_id=" + scope + "&names=%2FValue";
    var data = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(http) + "/data/entries?" + query)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, data.statusCode(), new String(data.body(), java.nio.charset.StandardCharsets.UTF_8));
    var arrow = org.triplehelix.wpilogmcp.data.ArrowSpecReader.read(data.body());
    assertEquals(List.of((double) first, first + 1.0), arrow.batches().stream().flatMap(b -> b.columns().get(1).stream()).toList());
    assertEquals(facts, com.google.gson.JsonParser.parseString(arrow.metadata().get("inputs")).getAsJsonObject().getAsJsonObject("replica"));
    return answer;
  }
  @Test void eachMirrorRetainsOneFairRetryWhenBothCoordinatorsAreOccupied() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(2); var release = new java.util.concurrent.CountDownLatch(1);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var remote = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var executor = java.util.concurrent.Executors.newCachedThreadPool(r -> { var t = new Thread(r); t.setDaemon(true); return t; }); remote.setExecutor(executor);
    remote.createContext("/replicas/v1/handshake", exchange -> {
      calls.incrementAndGet(); entered.countDown();
      try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      exchange.sendResponseHeaders(503, -1); exchange.close();
    }); remote.start();
    try {
      String origin = "http://127.0.0.1:" + remote.getAddress().getPort(), master = id();
      var a = store("first"); var b = store("second"); var c = store("third");
      var first = a.mirror(mirror(a, master, origin, List.of()), p -> {});
      var second = b.mirror(mirror(b, master, origin, List.of()), p -> {});
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var third = c.mirror(mirror(c, master, origin, List.of()), p -> {});
      assertThrows(java.util.concurrent.TimeoutException.class, () -> third.get(200, java.util.concurrent.TimeUnit.MILLISECONDS), "the third mirror keeps one coalesced retry");
      release.countDown();
      for (var future : List.of(first, second, third)) assertEquals("partial", future.get(10, TimeUnit.SECONDS).state());
      assertEquals(3, calls.get());
    } finally { release.countDown(); remote.stop(0); executor.shutdownNow(); }
  }
  @Test void canonicalAndAliasedStorePathsHaveIdenticalAdmissionAndFacts() throws Exception {
    var master = store("canonical"); var source = ImportFixture.write(temp.resolve("alias-input.wpilog"), 7);
    master.importPaths(new LogStore.Request(List.of(source), false, "RIO"), p -> {}).get();
    master.migrateReplicas(master.replicaHeader().id()).get();
    var file = master.replicaHeader().replicas().files().get(0);
    Path alias = temp.resolve("alias");
    try { Files.createSymbolicLink(alias, master.root()); }
    catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
      org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: " + unavailable.getMessage());
    }
    var expected = manager.replicaFacts(master.root().resolve(file.path()));
    assertEquals(expected, manager.replicaFacts(alias.resolve(file.path())), "an alias must retain the master receipt");
    var caseAlias = master.root().resolveSibling("CANONICAL").resolve(file.path());
    if (Files.exists(caseAlias) && Files.isSameFile(caseAlias, master.root().resolve(file.path())))
      assertEquals(expected, manager.replicaFacts(caseAlias), "case aliases retain exactly the same authority");
    Files.write(master.root().resolve(file.path()), Files.readAllBytes(ImportFixture.write(temp.resolve("alias-changed.wpilog"), 99)));
    assertThrows(java.io.IOException.class, () -> manager.acquire(alias.resolve(file.path()).toString()), "aliases cannot skip master byte admission");
    var mirror = store("mirror-alias"); var policy = mirror(mirror, master.replicaHeader().id(), "http://127.0.0.1:1", List.of());
    byte[] bytes = Files.readAllBytes(source);
    mirror.replicaTransfers().transfer(policy, id(), new ReplicaTransfers.Offer(file.identity(), "alias-input.wpilog", file.sha256(), bytes.length,
        "wpilog", "completed_import", "peer", false), offset -> new ByteArrayInputStream(bytes, (int) offset, bytes.length - (int) offset)).get();
    var held = mirror.replicaHeader().replicas().files().get(0);
    var mirrorAlias = Files.createSymbolicLink(temp.resolve("mirror-link"), mirror.root());
    assertEquals(held, StoreCatalog.requirePublishedMirror(mirrorAlias.resolve(held.path()), manager.testGetSecurityValidator()));
    assertEquals(manager.replicaFacts(mirror.root().resolve(held.path())), manager.replicaFacts(mirrorAlias.resolve(held.path())));
    var mirrorCase = mirror.root().resolveSibling("MIRROR-ALIAS").resolve(held.path());
    if (Files.exists(mirrorCase) && Files.isSameFile(mirrorCase, mirror.root().resolve(held.path())))
      assertEquals(held, StoreCatalog.requirePublishedMirror(mirrorCase, manager.testGetSecurityValidator()));
  }

  @Test void aChangedMasterFileCannotLabelOrdinaryAnalysisWithItsOldHash() throws Exception {
    var master = store("master"); var source = ImportFixture.write(temp.resolve("input.wpilog"), 7);
    master.importPaths(new LogStore.Request(List.of(source), false, "RIO"), p -> {}).get(); master.migrateReplicas(master.replicaHeader().id()).get();
    var file = master.replicaHeader().replicas().files().get(0);
    Files.write(master.root().resolve(file.path()), Files.readAllBytes(ImportFixture.write(temp.resolve("changed.wpilog"), 99)));
    var args = new com.google.gson.JsonObject(); args.addProperty("path", master.root().resolve(file.path()).toString());
    args.addProperty("scope_id", master.replicaHeader().id()); args.addProperty("name", "/Value");
    var registry = new ToolRegistry(); org.triplehelix.wpilogmcp.tools.StatisticsTools.registerAll(registry);
    var result = registry.getTool("get_statistics").execute(args).getAsJsonObject();
    assertEquals("error", result.get("status").getAsString(), "an immutable master receipt also requires current byte admission");
  }
}
