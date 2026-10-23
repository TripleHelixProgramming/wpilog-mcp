/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class ReplicaMasterRecoveryTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  Set<Path> previous; LogStore store;
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    Files.createDirectories(temp.resolve("master"));
    store = new LogStore(temp.resolve("master"), manager.testGetSecurityValidator(), manager, StoreCatalog::read);
    store.importPaths(new LogStore.Request(List.of(ImportFixture.write(temp.resolve("input.wpilog"), 7)), false, "RIO"), p -> {}).get();
    store.migrateReplicas(store.replicaHeader().id()).get();
  }
  @AfterEach void cleanup() { store.close(); manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory); }
  Header filled(int count) throws Exception {
    var header = store.replicaHeader(); var seed = header.replicas().files().get(0);
    var entries = new ArrayList<ReplicaCatalog.Entry>(); entries.add(seed);
    for (int i = 1; i < count; i++) {
      var identity = new ReplicaCatalog.Identity(header.id(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
      entries.add(new ReplicaCatalog.Entry(UUID.randomUUID().toString(), identity, "synthetic-" + i + ".wpilog", seed.sha256(), seed.sizeBytes(),
          "wpilog", null, "completed_import", "master_local", true, new ReplicaCatalog.Confirmation(new ReplicaCatalog.MasterRecord(identity,
              header.replicas().epoch(), 2, seed.sha256(), seed.sizeBytes(), "completed_import"), "local_master_catalog", "2026-10-19T00:00:00Z"), null, seed.placement()));
    }
    return header.withReplicas(new ReplicaCatalog(1, header.replicas().epoch(), 2, entries, List.of(), List.of()));
  }
  @Test void fullCatalogIsExternalAndAnOverLimitImportCannotCommitItsReceipt() throws Exception {
    var header = filled(4096); var io = new StoreFiles(store.root(), manager.testGetSecurityValidator());
    io.writeDurable(store.root().resolve("store.json"), header);
    assertTrue(Files.size(store.root().resolve("store.json")) < 4096, "the header must not grow with replica rows");
    assertEquals(header, store.replicaHeader());
    var before = StoreCatalog.read(store.root(), manager.testGetSecurityValidator());
    var manifests = new LinkedHashMap<Path, String>();
    for (var session : before.sessions()) manifests.put(session.path().resolve("session.json"), StoreFiles.hash(session.path().resolve("session.json")));
    var outcome = store.importPaths(new LogStore.Request(List.of(ImportFixture.write(temp.resolve("too-many.wpilog"), 71)), false, "RIO"), p -> {}).get();
    assertEquals("refused", outcome.files().get(0).status());
    assertTrue(outcome.files().get(0).reason().contains("RESOURCE_LIMIT"), outcome.toString());
    for (var entry : manifests.entrySet()) assertEquals(entry.getValue(), StoreFiles.hash(entry.getKey()), "a refused import cannot commit a session receipt");
    assertFalse(Files.exists(store.root().resolve(ReplicaMasters.PENDING)));
    assertEquals(before.unmanaged(), StoreCatalog.read(store.root(), manager.testGetSecurityValidator()).unmanaged(), "refusal leaves no copied payload strays");
    assertEquals(header, store.replicaHeader());
    var capture = store.captures(java.time.Clock.systemUTC()).create("127.0.0.1", java.time.Instant.parse("2026-10-19T00:00:00Z"));
    ImportFixture.write(capture, 23);
    try (var read = manager.acquireReadOnly(capture.toString())) { assertEquals(23.0, read.log().values().get("/Value").get(0).value()); }
    store.refreshStatus().get();
    assertEquals(4096, store.replicaHeader().replicas().files().size());
  }
  @Test void legacyInlineCatalogRemainsReadableAndMigratesOnTheNextPublication() throws Exception {
    var header = store.replicaHeader();
    Files.writeString(store.root().resolve("store.json"), StoreFiles.JSON.toJson(header));
    assertEquals(header, store.replicaHeader());
    new StoreFiles(store.root(), manager.testGetSecurityValidator()).write(store.root().resolve("store.json"), header);
    var disk = com.google.gson.JsonParser.parseString(Files.readString(store.root().resolve("store.json"))).getAsJsonObject();
    assertTrue(disk.has("replica_catalog"), "new writes publish a bounded catalog reference");
    assertTrue(!disk.has("replicas") || disk.get("replicas").isJsonNull());
    assertEquals(header, store.replicaHeader());
  }

  @Test void repeatedAdmissionReusesOneParsedHeaderAndRechecksChangesAndGrants() throws Exception {
    var header = store.replicaHeader(); var file = header.replicas().files().get(0); var path = store.root().resolve(file.path());
    try (var warm = manager.readAccess().acquire(path.toString())) { assertNotNull(warm.replica()); }
    long before = StoreFiles.headerReads.get();
    for (int i = 0; i < 3; i++) try (var read = manager.readAccess().acquire(path.toString())) {
      assertEquals(file.sha256(), read.replica().contentSha256());
      assertEquals(file.sizeBytes(), read.replica().sizeBytes());
    }
    assertTrue(StoreFiles.headerReads.get() - before <= 1, "one unchanged header is parsed at most once across admission and facts");
    var changed = header.withPeers(List.of("http://127.0.0.1:99"));
    new StoreFiles(store.root(), manager.testGetSecurityValidator()).write(store.root().resolve("store.json"), changed);
    assertEquals(changed, store.replicaHeader(), "cache invalidates on an atomic header replacement");
    manager.clearAllowedDirectories(); manager.addAllowedDirectory(Files.createDirectories(temp.resolve("unrelated")));
    assertThrows(Exception.class, () -> manager.replicaFacts(path), "cached metadata never carries a directory grant");
  }

  @Test void captureContinuesAfterCatalogCapacityIsExhaustedIncludingAfterRestart() throws Exception {
    var io = new StoreFiles(store.root(), manager.testGetSecurityValidator());
    io.writeDurable(store.root().resolve("store.json"), filled(4096));
    for (int boot = 0; boot < 2; boot++) {
      var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-19T00:00:00Z").plusSeconds(boot * 60), java.time.ZoneOffset.UTC);
      var placement = store.captures(clock); var loop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
      var status = new java.util.concurrent.atomic.AtomicReference<CaptureStore.Status>(); placement.onStatus(status::set);
      Path path;
      try (var writer = new org.triplehelix.wpilogmcp.capture.CaptureWriter(clock, loop, org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, placement)) {
        writer.connected(org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1", 5810, "synthetic"), "networktables.first.wpi.edu");
        writer.timeSync(10_000_000, 0);
        var value = new org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce("/Value", 1, "int", null, new com.google.gson.JsonObject());
        writer.announce(value); loop.until(writer::ready);
        writer.value(value, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 10_000_001, 2, 23L + boot), 0); path = writer.session().path();
      }
      assertDoesNotThrow(() -> placement.completion().get(), "publication capacity cannot fail the capture close barrier");
      store.awaitImports();
      assertNotNull(status.get().replicaPublicationFailure(), "capture status reports deferred publication");
      assertTrue(Files.exists(path.resolveSibling(ReplicaMasters.CAPTURE_PENDING)));
      try (var read = manager.acquireReadOnly(path.toString())) { assertEquals(23L + boot, read.log().values().get("NT:/Value").get(0).value()); }
      var session = io.read(path.resolveSibling("session.json"), Session.class);
      assertNull(session.openCapture()); assertEquals(1, session.files().size());
      assertEquals(StoreFiles.hash(path), session.files().get(0).sha256());
      assertThrows(Exception.class, () -> store.refreshStatus().get(), "the publication limit must be reported explicitly");
      var root = store.root(); store.close();
      store = new LogStore(root, manager.testGetSecurityValidator(), manager, StoreCatalog::read);
    }
  }

  /**
   * A capture closed on a disconnect is published with its hash. The same boot's reconnect used to
   * append to that file, so every read of it was refused for a hash mismatch and its second
   * publication stayed pending as an authority conflict through every recovery; the reconnect now
   * continues the session in the next file, and the published file keeps its bytes.
   */
  @Test void aSameBootReconnectAfterAPublishedCloseContinuesInTheNextFile() throws Exception {
    var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-19T00:00:00Z"), java.time.ZoneOffset.UTC);
    var placement = store.captures(clock); var loop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
    var status = new java.util.concurrent.atomic.AtomicReference<CaptureStore.Status>(); placement.onStatus(status::set);
    var robot = org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1", 5810, "synthetic");
    var value = new org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce("/Value", 1, "int", null, new com.google.gson.JsonObject());
    Path first, next; String published;
    try (var writer = new org.triplehelix.wpilogmcp.capture.CaptureWriter(clock, loop, org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, placement)) {
      writer.connected(robot, "networktables.first.wpi.edu"); writer.timeSync(10_000_000, 0);
      writer.announce(value); loop.until(writer::ready);
      writer.value(value, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 10_000_001, 2, 23L), 0); first = writer.session().path();
      writer.disconnected(); placement.completion().get(); store.awaitImports();
      published = StoreFiles.hash(first);
      assertEquals(published, catalogHash(first), "The close published the file");
      // The robot comes back on the same boot one second later.
      writer.connected(robot, "networktables.first.wpi.edu"); writer.timeSync(11_000_000, 1_000_000);
      writer.announce(value); loop.until(writer::ready);
      next = writer.session().path();
      assertEquals(first.resolveSibling("capture-2.wpilog"), next);
      writer.value(value, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 11_000_001, 2, 24L), 1_000_000);
      loop.advance(250_000);
      assertEquals(List.of(23L), values(first), "The published file stays readable while the next one records");
      writer.disconnected();
    }
    placement.completion().get(); store.awaitImports();
    assertEquals(published, StoreFiles.hash(first), "A published file is never written again");
    assertEquals(StoreFiles.hash(next), catalogHash(next), "The next file is published when it closes");
    assertEquals(published, catalogHash(first));
    assertNull(status.get().replicaPublicationFailure());
    assertFalse(Files.exists(first.resolveSibling(ReplicaMasters.CAPTURE_PENDING)));
    assertEquals(List.of(23L), values(first)); assertEquals(List.of(24L), values(next));
    var session = new StoreFiles(store.root(), manager.testGetSecurityValidator()).read(first.resolveSibling("session.json"), Session.class);
    assertEquals(List.of("capture.wpilog", "capture-2.wpilog"), session.files().stream().map(LogFile::path).toList());
    assertNull(session.openCapture());
    assertDoesNotThrow(() -> store.recoverMasterPublication().get());
    assertDoesNotThrow(() -> store.keepClocks().get(20, java.util.concurrent.TimeUnit.SECONDS));
  }

  private String catalogHash(Path file) throws Exception {
    return store.replicaHeader().replicas().files().stream().filter(e -> store.root().resolve(e.path()).equals(file))
        .map(ReplicaCatalog.Entry::sha256).findFirst().orElse(null);
  }

  private List<Object> values(Path file) throws Exception {
    try (var read = manager.acquireReadOnly(file.toString())) {
      return read.log().values().get("NT:/Value").stream().map(v -> v.value()).toList();
    }
  }

  @Test void changedCatalogPartsAreRefusedEvenWhenTheHeaderAndCacheAreUnchanged() throws Exception {
    var header = store.replicaHeader();
    var disk = com.google.gson.JsonParser.parseString(Files.readString(store.root().resolve("store.json"))).getAsJsonObject();
    String index = disk.getAsJsonObject("replica_catalog").get("index").getAsString();
    var directory = store.root().resolve(ReplicaCatalogFiles.DIRECTORY);
    var manifest = com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("index-" + index + ".json"))).getAsJsonObject();
    String hash = manifest.getAsJsonArray("parts").get(0).getAsJsonObject().get("hash").getAsString();
    var part = directory.resolve("part-" + hash + ".json"); byte[] before = Files.readAllBytes(part);
    Files.writeString(part, "[]");
    assertThrows(IOException.class, store::replicaHeader, "cached parsed rows cannot mask changed catalog bytes");
    Files.write(part, before); assertEquals(header, store.replicaHeader());
  }
  @Test void aFailedCaptureReceiptReplacementRetainsThePreviousPublicationIntent() throws Exception {
    var directory = Files.createDirectories(store.root().resolve("robots/RIO/sessions/recovery"));
    var path = ImportFixture.write(directory.resolve("capture.wpilog"), 31);
    var file = new LogFile("capture.wpilog", StoreFiles.hash(path), Files.size(path), "wpilog",
        new Provenance("captured", path.toString(), "capture.wpilog", "2026-10-19T00:00:00Z", false),
        true, 0, 1, null, null, "unknown", false, null);
    var receipt = directory.resolve("session.json");
    var session = new Session("recovery", "2026-10-19T00:00:00Z", null, "captured", null, null, null, null, List.of(file));
    var failReceipt = new java.util.concurrent.atomic.AtomicBoolean();
    var io = new StoreFiles(store.root(), manager.testGetSecurityValidator(), (p, v) -> {}, (from, to) -> {
      if (to.equals(store.root().resolve("store.json")) || failReceipt.get() && to.equals(receipt))
        throw new IOException("injected atomic replacement failure");
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }, Thread::sleep, false);
    io.recording(reason -> {});
    io.write(receipt, session); // The capture receipt commits; its catalog publication fails.
    String committed = StoreFiles.hash(receipt);
    failReceipt.set(true);
    assertThrows(IOException.class, () -> io.write(receipt, session.withFiles(List.of(file, file))));
    assertEquals(committed, StoreFiles.hash(receipt));
    store.recoverMasterPublication().get();
    assertEquals(2, store.replicaHeader().replicas().files().size(), "recovery must publish the still-committed capture receipt");
    assertFalse(Files.exists(directory.resolve(ReplicaMasters.CAPTURE_PENDING)));
    try (var read = manager.acquireReadOnly(path.toString())) { assertEquals(31.0, read.log().values().get("/Value").get(0).value()); }
  }
  @Test void aFailedPartWriteCannotPublishAHeaderAndRetryPreservesTheOldCatalog() throws Exception {
    var before = store.replicaHeader(); String hash = StoreFiles.hash(store.root().resolve("store.json"));
    var next = filled(1024);
    var io = new StoreFiles(store.root(), manager.testGetSecurityValidator(), (p, v) -> {}, (from, to) -> {
      if (to.getParent().equals(store.root().resolve(ReplicaCatalogFiles.DIRECTORY))) throw new IOException("injected part publication failure");
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }, Thread::sleep, false);
    assertThrows(IOException.class, () -> io.writeDurable(store.root().resolve("store.json"), next));
    assertEquals(hash, StoreFiles.hash(store.root().resolve("store.json"))); assertEquals(before, store.replicaHeader());
    new StoreFiles(store.root(), manager.testGetSecurityValidator()).writeDurable(store.root().resolve("store.json"), next);
    assertEquals(next, store.replicaHeader());
  }
  @Test void aggregateCatalogLimitRefusesBeforeWritingAnyPublicationFiles() throws Exception {
    var before = store.replicaHeader(); String hash = StoreFiles.hash(store.root().resolve("store.json"));
    var large = filled(2000); String text = "界".repeat(1024);
    var rows = large.replicas().files().stream().map(f -> new ReplicaCatalog.Entry(f.key(), f.identity(), f.path(), f.sha256(), f.sizeBytes(), f.kind(),
        f.sessionId(), f.completionBasis(), f.verificationBasis(), f.transferVerified(), f.confirmation(), f.descriptor(),
        new ReplicaCatalog.Placement(text, text, text, text, text, text, text))).toList();
    var candidate = large.withReplicas(new ReplicaCatalog(1, large.replicas().epoch(), 3, rows, List.of(), List.of()));
    long count;
    try (var paths = Files.walk(store.root())) { count = paths.count(); }
    var failure = assertThrows(IOException.class, () -> new StoreFiles(store.root(), manager.testGetSecurityValidator()).write(store.root().resolve("store.json"), candidate));
    assertTrue(failure.getMessage().contains("RESOURCE_LIMIT"));
    assertInstanceOf(ReplicaCatalogFiles.CapacityRefused.class, failure,
        "encoded capacity refusal must retain its pre-publication classification for owned-stage cleanup");
    assertEquals(hash, StoreFiles.hash(store.root().resolve("store.json"))); assertEquals(before, store.replicaHeader());
    try (var paths = Files.walk(store.root())) { assertEquals(count, paths.count(), "preflight must leave no catalog parts or temporary files"); }
  }

  @Test void aCommittedReceiptRecoversItsIdentityAfterTheHeaderWriteFails() throws Exception {
    var input = ImportFixture.write(temp.resolve("second.wpilog"), 17);
    var destination = store.root().resolve("unassigned/second.wpilog"); Files.createDirectories(destination.getParent()); Files.copy(input, destination);
    var file = new LogFile("second.wpilog", StoreFiles.hash(input), Files.size(input), "wpilog",
        new Provenance("imported", input.toString(), "second.wpilog", "2026-10-19T00:00:00Z", false), true, 0, 1, null, null, "unknown", false, null);
    var io = new StoreFiles(store.root(), manager.testGetSecurityValidator(), (p, v) -> {}, (from, to) -> {
      if (to.equals(store.root().resolve("store.json"))) throw new IOException("injected header failure");
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }, Thread::sleep, false);
    assertThrows(IOException.class, () -> io.write(destination.resolveSibling("second.wpilog.json"), file));
    assertTrue(Files.exists(store.root().resolve(".replicas/master-pending.json")), "the completed receipt must retain a bounded recovery intent");
    assertEquals(1, store.replicaHeader().replicas().files().size());
    store.refreshStatus().get();
    var recovered = store.replicaHeader().replicas().files(); assertEquals(2, recovered.size());
    assertFalse(Files.exists(store.root().resolve(".replicas/master-pending.json")));
    store.refreshStatus().get(); assertEquals(recovered, store.replicaHeader().replicas().files());
  }
  @Test void anExplicitMasterMoveRetainsIdentityAcrossAPathChange() throws Exception {
    var header = store.replicaHeader(); var entry = header.replicas().files().get(0);
    var old = store.root().resolve(entry.path()); var moved = old.resolveSibling("renamed.wpilog"); Files.move(old, moved);
    var io = new StoreFiles(store.root(), manager.testGetSecurityValidator());
    io.write(store.root().resolve("store.json"), header.withMoves(List.of(new Move(old.toString(), StoreFiles.relative(store.root(), moved), "2026-10-19T00:00:00Z"))));
    var after = store.replicaHeader().replicas().files(); assertEquals(1, after.size());
    assertEquals(entry.identity(), after.get(0).identity()); assertEquals(StoreFiles.relative(store.root(), moved), after.get(0).path());
    assertEquals(moved, manager.resolveReplica(store.root(), entry.identity()));
  }
}
