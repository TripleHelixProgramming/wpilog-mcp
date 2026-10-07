/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

class StoreSyncTest {
  @TempDir Path temp;
  LogManager manager;
  Set<Path> previous;
  LogStore a, b;
  HttpTransport httpA, httpB;
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); manager = LogManager.getInstance(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    a = manager.stores().store(temp.resolve("a")); b = manager.stores().store(temp.resolve("b"));
    for (var store : List.of(a, b)) store.importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
    httpA = new HttpTransport(new ToolRegistry(), 0); httpA.setStoreDirectories(Set.of(a.root())); httpA.start();
    httpB = new HttpTransport(new ToolRegistry(), 0); httpB.setStoreDirectories(Set.of(b.root())); httpB.start();
  }
  @AfterEach void stop() {
    if (httpA != null) httpA.stop(); if (httpB != null) httpB.stop();
    manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
  String url(HttpTransport http) { return "http://127.0.0.1:" + http.getPort(); }
  StoreCatalog.Snapshot catalog(LogStore store) throws Exception { return StoreCatalog.read(store.root(), manager.testGetSecurityValidator()); }
  StoreSync.Result sync(LogStore target, HttpTransport peer) throws Exception {
    var result = target.sync(url(peer), 0, p -> {}, CLOCK, StoreSync::http).get(30, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(List.of(), result.stopped(), result.toString()); assertEquals(List.of(), result.refusals(), result.toString()); return result;
  }
  Path fixture(String name, int tag, String serial) throws Exception {
    return fixture(name, tag, serial, 100);
  }
  Path fixture(String name, int tag, String serial, int records) throws Exception {
    return fixtureAt(name, tag, serial, records, 1_767_225_600_000_000L);
  }
  Path fixtureAt(String name, int tag, String serial, int records, long epochUs) throws Exception {
    var path = ImportFixture.write(temp.resolve(name), tag);
    try (var writer = new WpilogWriter(path, "sync fixture")) {
      int clock = writer.start("systemTime", "int64", "", 0);
      int id = writer.start("/SystemStats/SerialNumber", "string", "", 0);
      int value = writer.start("/Value", "double", "", 0);
      writer.append(clock, 0, WpilogWriter.encodeInt64(epochUs));
      writer.append(id, 0, WpilogWriter.encodeString(serial));
      for (int i = 0; i < records; i++) writer.append(value, i * 10_000L, WpilogWriter.encodeDouble(tag + i));
    }
    return path;
  }
  void put(LogStore store, Path file) throws Exception {
    var result = store.importPaths(new LogStore.Request(List.of(file), false, null), p -> {}).get();
    assertEquals("imported", result.files().get(0).status(), result.toString());
  }
  @ParameterizedTest @ValueSource(booleans = {false, true})
  void bothOrdersAndAThirdRoundConvergeOnOneBootAndHashUnion(boolean reverse) throws Exception {
    put(a, fixture("first.wpilog", 1, "SERIAL")); put(b, fixture("second.wpilog", 2, "SERIAL"));
    String firstId = catalog(a).sessions().get(0).session().id(), secondId = catalog(b).sessions().get(0).session().id();
    String canonical = firstId.compareTo(secondId) < 0 ? firstId : secondId;
    if (reverse) { sync(b, httpA); sync(a, httpB); } else { sync(a, httpB); sync(b, httpA); }
    for (var store : List.of(a, b)) {
      var got = catalog(store); assertEquals(1, got.sessions().size()); assertEquals(canonical, got.sessions().get(0).session().id());
      assertEquals(2, got.files().size()); assertEquals(2, got.sessions().get(0).session().files().size());
      assertEquals(List.of(), got.unmanaged());
    }
    assertEquals(catalog(a).files().stream().map(f -> f.file().sha256()).collect(java.util.stream.Collectors.toSet()),
        catalog(b).files().stream().map(f -> f.file().sha256()).collect(java.util.stream.Collectors.toSet()));
    var again = sync(a, httpB); assertTrue(again.filesCopied().isEmpty()); assertEquals(2, again.filesPresent().size());
    assertEquals(List.of(url(httpB)), catalog(a).header().peers());
    assertTrue(a.sync(null, 0, p -> {}).get().filesCopied().isEmpty());
  }
  @Test void provenanceAndHumanConflictsSurviveTheHopWithoutOverwritingLocalNames() throws Exception {
    put(a, fixture("first.wpilog", 1, "SERIAL")); put(b, fixture("second.wpilog", 2, "SERIAL"));
    for (var store : List.of(a, b)) store.capture(io -> {
      var path = store.root().resolve("robots/SERIAL/robot.json"); var old = io.read(path, StoreManifest.Robot.class);
      io.write(path, new StoreManifest.Robot(old.id(), old.serialNumber(), store == a ? "Alice" : "Bob", store == a ? "" : "Peer comment", old.basis(), old.contacts())); return null;
    });
    var before = catalog(b).files().get(0).file(); String peerId = catalog(b).header().id();
    var result = sync(a, httpB); assertEquals(1, result.conflicts().size());
    var got = catalog(a); assertEquals("Alice", got.robots().get(0).robot().name()); assertEquals("Peer comment", got.robots().get(0).robot().comments());
    var conflict = got.sessions().get(0).session().conflicts().get(0);
    assertEquals(new StoreManifest.Conflict("robot.name", "Alice", "Bob", peerId), conflict);
    var file = got.files().stream().filter(f -> f.file().sha256().equals(before.sha256())).findFirst().orElseThrow().file();
    assertEquals(before.provenance().kind(), file.provenance().kind()); assertEquals(before.provenance().originalPath(), file.provenance().originalPath());
    assertEquals(before.provenance().importedAt(), file.provenance().importedAt());
    assertEquals(List.of(new StoreManifest.PeerCopy(peerId, url(httpB), CLOCK.instant().toString())), file.provenance().copiedFrom());
    assertEquals(before.sizeBytes(), result.filesCopied().get(0).bytes());
  }

  @Test void interruptedCopyResumesOnlyAfterItsHeldPrefixWasProvedOverHttp() throws Exception {
    var source = fixture("large.wpilog", 8, "SERIAL", 20_000); put(b, source);
    var stop = a.sync(url(httpB), 0, p -> {
      if (p.phase().equals("copied")) throw new IllegalStateException("test interruption between blocks");
    }).get();
    assertEquals(1, stop.stopped().size()); assertEquals(65_536, stop.stopped().get(0).bytesCopied());
    assertTrue(catalog(a).files().isEmpty()); assertTrue(catalog(a).unmanaged().isEmpty());
    var hashes = new java.util.ArrayList<Long>(); var offsets = new java.util.ArrayList<Long>();
    StoreSync.Source observed = address -> {
      var peer = StoreSync.http(address); var remote = peer.remote();
      return new StoreSync.Peer(peer.url(), peer.description(), peer.robots(), peer.sessions(), new org.triplehelix.wpilogmcp.sync.RemoteFiles() {
        @Override public List<File> list() throws java.io.IOException { return remote.list(); }
        @Override public byte[] read(String name, long offset, int count) throws java.io.IOException {
          assertTrue(hashes.contains(65_536L), "Resume needs a content proof before the first Range read"); offsets.add(offset);
          return remote.read(name, offset, count);
        }
        @Override public java.util.Optional<String> prefixHash(String name, long length) throws java.io.IOException {
          hashes.add(length); return remote.prefixHash(name, length);
        }
      });
    };
    var completed = a.sync(url(httpB), 0, p -> {}, CLOCK, observed).get();
    assertTrue(completed.stopped().isEmpty(), completed.toString()); assertTrue(completed.refusals().isEmpty(), completed.toString());
    assertEquals(65_536L, offsets.get(0)); assertEquals(1, completed.filesCopied().size());
    assertEquals(Files.size(source) - 65_536, completed.filesCopied().get(0).bytes());
    assertEquals(StoreFiles.hash(source), catalog(a).files().get(0).file().sha256());
  }

  @Test void aDisappearingPeerReportsItsOffsetAndLeavesOnlyOwnedPartialBytes() throws Exception {
    put(b, fixture("large.wpilog", 9, "SERIAL", 20_000));
    var server = httpB; String address = url(server);
    var stopped = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
    var result = a.sync(address, 0, p -> {
      if (!p.phase().equals("copied") || stopped.get() != null) return;
      stopped.set(java.util.concurrent.CompletableFuture.runAsync(server::stop));
      var client = java.net.http.HttpClient.newHttpClient();
      long until = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
      while (System.nanoTime() < until) {
        try {
          client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(address + "/health"))
              .timeout(java.time.Duration.ofSeconds(1)).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (java.io.IOException expected) { return; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
      }
      fail("Peer socket did not close");
    }).get(30, java.util.concurrent.TimeUnit.SECONDS);
    stopped.get().get(10, java.util.concurrent.TimeUnit.SECONDS); httpB = null;
    assertEquals(1, result.stopped().size()); assertEquals(65_536, result.stopped().get(0).bytesCopied());
    assertNotNull(result.stopped().get(0).remotePath()); assertTrue(catalog(a).files().isEmpty()); assertTrue(catalog(a).unmanaged().isEmpty());
    httpB = new HttpTransport(new ToolRegistry(), server.getPort()); httpB.setStoreDirectories(Set.of(b.root())); httpB.start();
    var again = a.sync(null, 0, p -> {}).get(); assertTrue(again.stopped().isEmpty(), again.toString());
    assertEquals(1, again.filesCopied().size()); assertEquals(1, catalog(a).files().size());
  }

  @Test void mirrorsAreRefusedBothWaysAndAnotherSyncCannotEnterTheSameStore() throws Exception {
    put(b, fixture("source.wpilog", 1, "SERIAL"));
    b.capture(io -> {
      var header = io.read(b.root().resolve("store.json"), StoreManifest.Header.class);
      io.write(b.root().resolve("store.json"), new StoreManifest.Header(header.formatVersion(), header.createdAt(), header.id(), header.moves(), header.addresses(), true, header.peers())); return null;
    });
    var sourceRefused = a.sync(url(httpB), 0, p -> {}).get();
    assertTrue(sourceRefused.refusals().get(0).reason().contains("mirror")); assertTrue(catalog(a).header().peers().isEmpty());
    var targetRefused = b.sync(url(httpA), 0, p -> fail("A mirror target must refuse before contacting a peer")).get();
    assertTrue(targetRefused.refusals().get(0).reason().contains("mirror"));
    var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
    var first = a.sync(url(httpB), 0, p -> {
      entered.countDown(); try { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    });
    try {
      assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
      var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> a.sync(url(httpB), 0, p -> {}).get());
      assertTrue(error.getCause().getMessage().contains("already running"));
    } finally { release.countDown(); first.get(); }
  }

  @Test void newSessionsKeepPeerIdsAndDifferentSerialsNeverCoalesce() throws Exception {
    put(a, fixture("local.wpilog", 1, "LOCAL")); put(b, fixture("peer.wpilog", 2, "PEER"));
    String id = catalog(b).sessions().get(0).session().id();
    var result = sync(a, httpB); assertEquals(1, result.sessionsCreated().size());
    assertEquals(id, result.sessionsCreated().get(0).sessionId()); assertEquals(2, catalog(a).sessions().size());
    assertEquals(Set.of("LOCAL", "PEER"), catalog(a).robots().stream().map(r -> r.robot().serialNumber()).collect(java.util.stream.Collectors.toSet()));
  }

  @Test void aPeerWindowJoiningTwoLocalPiecesConsolidatesBothAndKeepsOldPathsWorking() throws Exception {
    put(a, fixture("early.wpilog", 1, "SERIAL"));
    put(a, fixtureAt("late.wpilog", 2, "SERIAL", 100, 1_767_225_602_000_000L));
    var before = catalog(a); assertEquals(2, before.sessions().size());
    var oldPaths = before.files().stream().map(StoreCatalog.StoredFile::path).toList();
    put(b, fixtureAt("bridge.wpilog", 3, "SERIAL", 201, 1_767_225_600_500_000L));
    sync(a, httpB); sync(b, httpA);
    for (var store : List.of(a, b)) {
      var got = catalog(store); assertEquals(1, got.sessions().size()); assertEquals(3, got.files().size());
      assertEquals(List.of(), got.unmanaged());
    }
    assertEquals(catalog(a).sessions().get(0).session().id(), catalog(b).sessions().get(0).session().id());
    for (var path : oldPaths) try (var use = manager.acquire(path.toString())) {
      assertEquals(100, use.log().sampleCount("/Value"));
    }
  }

  @Test void aPeerHashDisagreementNeverAdmitsACopyAndACorrectedManifestCanResume() throws Exception {
    put(b, fixture("source.wpilog", 1, "SERIAL")); var original = catalog(b).files().get(0);
    String saved = Files.readString(original.manifestPath());
    var json = com.google.gson.JsonParser.parseString(saved).getAsJsonObject();
    json.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("sha256", "0".repeat(64));
    Files.writeString(original.manifestPath(), json.toString());
    var rejected = a.sync(url(httpB), 0, p -> {}).get();
    assertEquals(1, rejected.stopped().size()); assertTrue(rejected.stopped().get(0).reason().contains("peer manifest"));
    assertTrue(catalog(a).files().isEmpty()); assertTrue(catalog(a).unmanaged().isEmpty());
    Files.writeString(original.manifestPath(), saved);
    var recovered = sync(a, httpB); assertEquals(1, recovered.filesCopied().size());
    assertEquals(0, recovered.filesCopied().get(0).bytes(), "The valid held bytes were content-checked again, not downloaded again");
  }

  @Test void aHashedButUnreadablePeerFileIsRetriedOnceAndNeverManifested() throws Exception {
    put(b, fixture("source.wpilog", 1, "SERIAL")); var original = catalog(b).files().get(0);
    byte[] bytes = Files.readAllBytes(original.path()); Files.write(original.path(), java.util.Arrays.copyOf(bytes, bytes.length - 2));
    var json = com.google.gson.JsonParser.parseString(Files.readString(original.manifestPath())).getAsJsonObject();
    var file = json.getAsJsonArray("files").get(0).getAsJsonObject();
    file.addProperty("size_bytes", Files.size(original.path())); file.addProperty("sha256", StoreFiles.hash(original.path()));
    Files.writeString(original.manifestPath(), json.toString());
    var retries = new java.util.concurrent.atomic.AtomicInteger();
    var rejected = a.sync(url(httpB), 0, p -> { if (p.phase().equals("retried")) retries.incrementAndGet(); }).get();
    assertEquals(1, retries.get()); assertTrue(rejected.stopped().isEmpty(), rejected.toString());
    assertEquals(1, rejected.refusals().size()); assertTrue(rejected.refusals().get(0).reason().contains("EOF"));
    assertTrue(catalog(a).files().isEmpty()); assertTrue(catalog(a).unmanaged().isEmpty());
  }

  @Test void theDaemonJobReportsTheSameSyncResultAndRejectsBadRequests() throws Exception {
    put(b, fixture("peer.wpilog", 2, "SERIAL"));
    var client = java.net.http.HttpClient.newHttpClient();
    var payload = new com.google.gson.JsonObject(); payload.addProperty("url", url(httpB));
    var post = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(httpA) + "/store/sync"))
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload.toString())).build();
    var accepted = client.send(post, java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(202, accepted.statusCode(), accepted.body());
    String job = com.google.gson.JsonParser.parseString(accepted.body()).getAsJsonObject().get("url").getAsString();
    assertEquals(job, accepted.headers().firstValue("Location").orElseThrow());
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
    com.google.gson.JsonObject state;
    do {
      var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(httpA) + job)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body()); state = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
      if (state.get("state").getAsString().equals("done")) break;
      assertTrue(System.nanoTime() < deadline, state.toString());
    } while (true);
    var result = state.getAsJsonObject("result");
    assertEquals(1, result.getAsJsonArray("files_copied").size()); assertEquals(1, result.getAsJsonArray("sessions_created").size());
    assertEquals(0, result.getAsJsonArray("refusals").size()); assertEquals(0, result.getAsJsonArray("stopped").size());
    for (String bad : List.of("null", "{\"url\":3}", "{\"rate_bytes\":-1}", "{\"rate_bytes\":0.5}", "{\"url\":\"file:///tmp/log\"}")) {
      var response = client.send(java.net.http.HttpRequest.newBuilder(post.uri()).POST(java.net.http.HttpRequest.BodyPublishers.ofString(bad)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(400, response.statusCode(), bad + " " + response.body());
      if (bad.contains("rate_bytes")) assertTrue(response.body().contains("rate_bytes"), response.body());
    }
    var denied = client.send(java.net.http.HttpRequest.newBuilder(post.uri()).header("Origin", "https://untrusted.example")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload.toString())).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(403, denied.statusCode());
  }

  @Test void aQueuedDaemonSyncExcludesASecondJobAndCanBePolled() throws Exception {
    put(b, fixture("peer.wpilog", 2, "SERIAL"));
    var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
    var busy = a.captureAsync(io -> {
      entered.countDown();
      try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("test queue barrier timed out"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
      return null;
    });
    try {
      assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
      var client = java.net.http.HttpClient.newHttpClient();
      var payload = new com.google.gson.JsonObject(); payload.addProperty("url", url(httpB));
      var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(httpA) + "/store/sync"))
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload.toString())).build();
      var accepted = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()); assertEquals(202, accepted.statusCode());
      var refused = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(409, refused.statusCode()); assertTrue(refused.body().contains("already running"));
      String job = com.google.gson.JsonParser.parseString(accepted.body()).getAsJsonObject().get("url").getAsString();
      var state = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(httpA) + job)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, state.statusCode()); assertEquals("queued", com.google.gson.JsonParser.parseString(state.body()).getAsJsonObject().get("state").getAsString());
    } finally { release.countDown(); busy.get(); a.awaitImports(); }
  }

  @Test void aNetworkBoundDoorAcceptsOnlyLoopbackSyncJobs() throws Exception {
    httpA.stop(); httpA = new HttpTransport(new ToolRegistry(), 0, "0.0.0.0", null, null); httpA.setStoreDirectories(Set.of(a.root())); httpA.start();
    var client = java.net.http.HttpClient.newHttpClient();
    var loopback = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(httpA) + "/store/sync"))
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(202, loopback.statusCode(), loopback.body());
    var address = java.net.NetworkInterface.networkInterfaces().flatMap(java.net.NetworkInterface::inetAddresses)
        .filter(a -> a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()).findFirst();
    org.junit.jupiter.api.Assumptions.assumeTrue(address.isPresent(), "No nonloopback interface available to exercise a peer connection");
    String network = "http://" + address.orElseThrow().getHostAddress() + ":" + httpA.getPort();
    var read = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(network + "/store")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(200, read.statusCode());
    for (String path : List.of("/store/sync", "/store/sync/not-a-job")) {
      var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(network + path));
      if (path.equals("/store/sync")) request.POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")); else request.GET();
      var denied = client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(403, denied.statusCode()); assertTrue(denied.body().contains("loopback"));
    }
  }

  @Test void anImportedPowerCutTailCopiesExactlyWithItsExistingTruncationNote() throws Exception {
    var source = org.triplehelix.wpilogmcp.fixtures.FixtureLogs.generateAll(temp.resolve("fixtures")).stream()
        .filter(f -> f.id().equals("truncated")).findFirst().orElseThrow().path();
    var imported = b.importPaths(new LogStore.Request(List.of(source), false, null), p -> {}).get();
    assertEquals("unassigned", imported.files().get(0).status());
    assertTrue(catalog(b).files().get(0).file().truncated());
    sync(a, httpB);
    var copy = catalog(a).files().get(0); assertTrue(copy.file().truncated());
    assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(copy.path()));
    try (var original = manager.acquire(source.toString()); var loaded = manager.acquire(copy.path().toString())) {
      assertEquals(original.log().minTimestamp(), loaded.log().minTimestamp());
      assertEquals(original.log().maxTimestamp(), loaded.log().maxTimestamp());
      assertEquals(original.log().truncationMessage(), loaded.log().truncationMessage());
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void aPlacementReceiptRecoversBeforeContactingAnOfflinePeer(boolean moved) throws Exception {
    put(b, fixture("source.wpilog", 6, "SERIAL")); sync(a, httpB);
    var file = catalog(a).files().get(0);
    Path receipt;
    try (var paths = Files.walk(a.root().resolve(".sync"))) {
      receipt = paths.filter(p -> p.getFileName().toString().equals("placing.json")).findFirst().orElseThrow();
    }
    var pending = com.google.gson.JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
    pending.addProperty("committed", false); Files.writeString(receipt, pending.toString());
    a.capture(io -> { io.write(file.manifestPath(), file.session().withFiles(List.of())); return null; });
    if (!moved) {
      var staged = a.root().resolve(pending.get("staged").getAsString()); Files.createDirectories(staged.getParent());
      Files.move(file.path(), staged);
    }
    var result = a.sync(url(httpB), 0, p -> {}, CLOCK, address -> { throw new java.io.IOException("peer offline"); }).get();
    assertEquals(1, result.stopped().size()); assertEquals("peer offline", result.stopped().get(0).reason());
    assertEquals(List.of(file.file().sha256()), catalog(a).files().stream().map(f -> f.file().sha256()).toList());
    assertTrue(catalog(a).unmanaged().isEmpty());
    assertTrue(com.google.gson.JsonParser.parseString(Files.readString(receipt)).getAsJsonObject().get("committed").getAsBoolean());
  }

  @Test void aSameContentStrayAtTheDestinationIsNeverAdoptedOrOverwritten() throws Exception {
    put(a, fixture("local.wpilog", 4, "SERIAL")); put(b, fixture("peer.wpilog", 5, "SERIAL"));
    var source = catalog(b).files().get(0); var parent = catalog(a).sessions().get(0).path();
    var stray = parent.resolve(source.file().path()); Files.createDirectories(stray.getParent()); Files.copy(source.path(), stray);
    var result = sync(a, httpB); assertEquals(1, result.filesCopied().size());
    assertNotEquals(stray, result.filesCopied().get(0).path());
    assertEquals(List.of(stray), catalog(a).unmanaged());
    assertArrayEquals(Files.readAllBytes(source.path()), Files.readAllBytes(stray));
  }

  @Test void aPeerIdCannotCollideWithTheLocalMergeJournalDirectory() throws Exception {
    put(b, fixture("source.wpilog", 1, "SERIAL"));
    b.capture(io -> {
      var h = io.read(b.root().resolve("store.json"), StoreManifest.Header.class);
      io.write(b.root().resolve("store.json"), new StoreManifest.Header(h.formatVersion(), h.createdAt(), "merges", h.moves(), h.addresses(), false, h.peers())); return null;
    });
    sync(a, httpB); assertTrue(catalog(a).unmanaged().isEmpty());
    assertEquals(1, sync(a, httpB).filesPresent().size());
  }

  @Test void aLiveCaptureFlushKeepsThePeersCapturedFileAndMatchFacts() throws Exception {
    var identity = new org.triplehelix.wpilogmcp.capture.context.DeviceIdentity("SERIAL", "", "127.0.0.1", "SHA256:synthetic", java.util.Map.of());
    var firstLoop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
    var secondLoop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
    var first = a.captures(CLOCK); var second = b.captures(CLOCK);
    var value = new org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce("/value", 1, "int", null, new com.google.gson.JsonObject());
    try (var writer = new org.triplehelix.wpilogmcp.capture.CaptureWriter(CLOCK, firstLoop, org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, first)) {
      try (var peer = new org.triplehelix.wpilogmcp.capture.CaptureWriter(CLOCK, secondLoop, org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, second)) {
        int counter = 0;
        for (var w : List.of(writer, peer)) {
          w.identity(identity); w.connected(org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1", 5810, "test"), "networktables.first.wpi.edu");
          w.timeSync(1_000_000, 0); w.announce(value);
          w.value(value, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 1_000_000, 2, (long) ++counter), 0);
        }
        var event = new org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce("/FMSInfo/EventName", 2, "string", null, new com.google.gson.JsonObject());
        peer.announce(event); peer.value(event, new org.triplehelix.wpilogmcp.nt4.ValueFrame(2, 1_000_000, 4, "SYNTHETIC"), 0);
      }
      second.completion().get(); first.completion().get();
      var copied = sync(a, httpB).filesCopied().get(0); assertEquals(1, catalog(a).files().size());
      firstLoop.advance(5_000_000); first.completion().get();
      var during = catalog(a); assertEquals(1, during.files().size());
      assertEquals(copied.sha256(), during.files().get(0).file().sha256());
      assertEquals("SYNTHETIC", during.sessions().get(0).session().event());
    }
    first.completion().get(); assertEquals(2, catalog(a).files().size()); assertTrue(catalog(a).unmanaged().isEmpty());
  }
}
