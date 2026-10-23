/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
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
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** The pit's clock records, read back with expected pairs worked out from the fixture alone. */
class ClockRecordsTest {
  @TempDir Path temp;
  LogManager manager; Set<Path> previous; LogStore pit;
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); manager = LogManager.getInstance(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    pit = manager.stores().store(temp.resolve("pit"));
    pit.importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
  }
  @AfterEach void cleanup() {
    manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
  StoreFiles io() throws Exception { return new StoreFiles(pit.root(), manager.testGetSecurityValidator()); }
  Path folder(String id) { return pit.root().resolve("robots/RIO/sessions/2026-03-07").resolve(id); }

  /** Uptime 100 + 2i seconds at robot time 10 + 2i seconds: the pit's sampling every 2 s. */
  Path writeCapture(String id, int samples) throws Exception {
    var path = folder(id).resolve("capture.wpilog"); Files.createDirectories(path.getParent());
    try (var writer = new WpilogWriter(path, "clock fixture")) {
      int uptime = writer.start(ClockRecords.UPTIME_ENTRY, "double", "{\"source\":\"ssh\"}", 10_000_000);
      for (int i = 0; i < samples; i++) writer.append(uptime, 10_000_000L + 2_000_000L * i, WpilogWriter.encodeDouble(100 + 2 * i));
    }
    return path;
  }
  static List<double[]> expected(int samples) {
    var pairs = new ArrayList<double[]>(); for (int i = 0; i < samples; i++) pairs.add(new double[] {100 + 2 * i, 10 + 2 * i}); return pairs;
  }
  void record(String id, Path path, boolean open, int samples) throws Exception {
    var provenance = new Provenance(Provenance.CAPTURED, null, "capture.wpilog", "2026-03-07T14:22:33Z", false);
    double end = 10 + 2 * Math.max(0, samples - 1);
    var files = open ? List.<LogFile>of() : List.of(new LogFile("capture.wpilog", StoreFiles.hash(path), Files.size(path), "wpilog",
        provenance, true, 10, end, "2026-03-07T14:22:33Z", "2026-03-07T14:22:43Z", "pit_clock", false, null));
    var openCapture = open ? new OpenCapture("capture.wpilog", provenance, Files.size(path), 10, end) : null;
    var manifest = folder(id).resolve("session.json");
    var clocks = Files.exists(manifest) ? read(id).clocks() : List.<ClockRecord>of();
    var session = new Session(id, "2026-03-07T14:22:33Z", "2026-03-07T14:22:43Z", "pit_clock", null, null, null, null, files, openCapture)
        .withClocks(clocks);
    pit.capture(io -> {
      io.write(pit.root().resolve("robots/RIO/robot.json"), new Robot("RIO", "RIO", "RIO", "fixture", "device"));
      io.write(manifest, session); return null;
    });
  }
  Session read(String id) throws Exception { return StoreJson.JSON.fromJson(Files.readString(folder(id).resolve("session.json")), Session.class); }
  static void assertPairs(List<double[]> expected, List<double[]> actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), actual.get(i), "pair " + i);
  }

  @Test void aClosedCaptureGetsARecordOfExactlyItsUptimeSamples() throws Exception {
    var path = writeCapture("boot", 5); record("boot", path, false, 5);
    assertEquals(1, pit.keepClocks().get(20, TimeUnit.SECONDS));
    var session = read("boot");
    assertEquals(1, session.clocks().size()); var receipt = session.clocks().get(0);
    assertEquals("clock/capture.wpilog.json", receipt.path()); assertEquals("capture.wpilog", receipt.capture());
    assertEquals(StoreFiles.hash(path), receipt.captureSha256());
    var record = folder("boot").resolve("clock").resolve("capture.wpilog.json");
    assertEquals(receipt.sha256(), StoreFiles.hash(record)); assertEquals(receipt.sizeBytes(), Files.size(record));
    var facts = ClockRecords.read(io(), record, receipt);
    assertPairs(expected(5), facts.uptime());
    assertEquals(10, facts.minTimestampSec()); assertEquals(18, facts.maxTimestampSec());
    String store = StoreCatalog.read(pit.root(), manager.testGetSecurityValidator()).header().id();
    assertEquals(store, facts.collectingStoreId());
    assertEquals("2026-03-07T14:22:33Z", facts.recordingStartedAt());
    assertEquals(new CaptureRecording(store, "2026-03-07T14:22:33Z"), receipt.recording(), "a copy without the capture learns its recording here");
    // A capture that has not changed is neither read nor written again.
    String before = Files.readString(folder("boot").resolve("session.json"));
    assertEquals(0, pit.keepClocks().get(20, TimeUnit.SECONDS));
    assertEquals(before, Files.readString(folder("boot").resolve("session.json")));
    assertEquals(List.of(), StoreCatalog.read(pit.root(), manager.testGetSecurityValidator()).unmanaged(), "A record is a store file, not a stray");
  }

  @Test void replicaProtocolCarriesClosedClockBytesAndTheirRecordingRelationship() throws Exception {
    for (boolean afterMigration : List.of(false, true)) {
      String name = afterMigration ? "later" : "first";
      if (afterMigration) pit.migrateReplicas(pit.replicaHeader().id()).get();
      var capture = writeCapture(name, 5); record(name, capture, false, 5);
      assertEquals(1, pit.keepClocks().get(20, TimeUnit.SECONDS));
      if (!afterMigration) pit.migrateReplicas(pit.replicaHeader().id()).get();
      var receipt = read(name).clocks().get(0);
      var clock = pit.replicaHeader().replicas().files().stream().filter(f -> f.kind().equals("clock_record") && f.sha256().equals(receipt.sha256())).findFirst();
      assertTrue(clock.isPresent(), "closed clock companions need independent immutable replica identities");
    }
    var server = new org.triplehelix.wpilogmcp.mcp.HttpTransport(new org.triplehelix.wpilogmcp.mcp.ToolRegistry(), 0);
    server.setStoreDirectories(Set.of(pit.root())); server.start();
    var mirror = manager.stores().store(temp.resolve("mirror"));
    var policy = new org.triplehelix.wpilogmcp.config.MirrorConfig("http://127.0.0.1:" + server.getPort(), mirror.root(), 14,
        20_000_000, List.of(), List.of(), 30, 0, "all", 1_048_576, List.of(), false);
    try {
      mirror.mirror(policy, p -> {}).get(); mirror.migrateReplicas(mirror.replicaHeader().id()).get();
      assertEquals("synchronized", mirror.mirror(policy, p -> {}).get(20, TimeUnit.SECONDS).state());
      // The mirror copies each capture's clock record, never the capture.
      var copies = mirror.replicaHeader().replicas().files(); assertEquals(2, copies.size());
      assertTrue(copies.stream().allMatch(f -> f.kind().equals("clock_record")), copies.toString());
      for (var entry : copies.stream().filter(f -> f.kind().equals("clock_record")).toList()) {
        var original = pit.replicaHeader().replicas().files().stream().filter(f -> f.identity().equals(entry.identity())).findFirst().orElseThrow();
        assertEquals("verified", entry.verificationBasis());
        assertArrayEquals(Files.readAllBytes(pit.root().resolve(original.path())), Files.readAllBytes(mirror.root().resolve(entry.path())));
        var receipt = read(entry.sessionId()).clocks().get(0);
        var facts = ClockRecords.read(new StoreFiles(mirror.root(), manager.testGetSecurityValidator()), mirror.root().resolve(entry.path()), receipt);
        assertPairs(expected(5), facts.uptime()); assertEquals(10, facts.minTimestampSec()); assertEquals(18, facts.maxTimestampSec());
        var relationships = StoreJson.JSON.toJsonTree(entry.placement()).getAsJsonObject().getAsJsonObject("relationships");
        assertEquals(receipt.captureSha256(), relationships.getAsJsonObject("clock").get("capture_sha256").getAsString());
        assertEquals(pit.replicaHeader().id(), relationships.getAsJsonObject("capture").get("master_store_id").getAsString());
      }
      assertEquals(0, StoreCatalog.read(mirror.root(), manager.testGetSecurityValidator()).files().size(), "clock JSON is managed but never presented as a log");
      assertEquals(List.of(), StoreCatalog.read(mirror.root(), manager.testGetSecurityValidator()).unmanaged());
    } finally { server.stop(); }
  }

  /**
   * A format-2 mirror copies what the pit pulled or was given, and its captures' clock records, never
   * a capture: a laptop records live data from the pit's gateway itself. The pit still publishes
   * the capture in its own catalog, whose hash its own reads are checked against.
   */
  @Test void aFormatTwoMirrorCopiesImportsAndClockRecordsButNeverACapture() throws Exception {
    pit.migrateReplicas(pit.replicaHeader().id()).get();
    var capture = writeCapture("boot", 5); record("boot", capture, false, 5);
    assertEquals(1, pit.keepClocks().get(20, TimeUnit.SECONDS));
    pit.importPaths(new LogStore.Request(List.of(org.triplehelix.wpilogmcp.fixtures.ImportFixture.write(temp.resolve("upload.wpilog"), 7)),
        false, "RIO"), p -> {}).get();
    var published = pit.replicaHeader().replicas().files();
    assertTrue(published.stream().anyMatch(f -> f.completionBasis().equals("closed_capture")), "the pit publishes its capture");
    var server = new org.triplehelix.wpilogmcp.mcp.HttpTransport(new org.triplehelix.wpilogmcp.mcp.ToolRegistry(), 0);
    server.setStoreDirectories(Set.of(pit.root())); server.start();
    var mirror = manager.stores().store(temp.resolve("mirror"));
    var policy = new org.triplehelix.wpilogmcp.config.MirrorConfig("http://127.0.0.1:" + server.getPort(), mirror.root(), 14,
        20_000_000, List.of(), List.of(), 30, 0, "all", 1_048_576, List.of(), false);
    try {
      mirror.mirror(policy, p -> {}).get(); mirror.migrateReplicas(mirror.replicaHeader().id()).get();
      assertEquals("synchronized", mirror.mirror(policy, p -> {}).get(20, TimeUnit.SECONDS).state());
      var held = mirror.replicaHeader().replicas().files();
      assertEquals(Set.of("completed_import", "closed_clock"),
          held.stream().map(ReplicaCatalog.Entry::completionBasis).collect(java.util.stream.Collectors.toSet()));
      for (var entry : held) {
        var original = published.stream().filter(f -> f.identity().equals(entry.identity())).findFirst().orElseThrow();
        assertArrayEquals(Files.readAllBytes(pit.root().resolve(original.path())), Files.readAllBytes(mirror.root().resolve(entry.path())));
      }
      var logs = StoreCatalog.read(mirror.root(), manager.testGetSecurityValidator()).files();
      assertEquals(1, logs.size()); assertEquals("imported", logs.get(0).file().provenance().kind());
      // Another pass copies nothing more: the capture is not a missing file.
      assertEquals("synchronized", mirror.mirror(policy, p -> {}).get(20, TimeUnit.SECONDS).state());
      assertEquals(held, mirror.replicaHeader().replicas().files());
    } finally { server.stop(); }
  }

  @Test void anEntryThatDoesNotNameItsRecordingIsWrittenAgainAndADisagreeingOneIsRefused() throws Exception {
    var path = writeCapture("boot", 3); record("boot", path, false, 3);
    assertEquals(1, pit.keepClocks().get(20, TimeUnit.SECONDS));
    var receipt = read("boot").clocks().get(0);
    var older = new ClockRecord(receipt.path(), receipt.sha256(), receipt.sizeBytes(), receipt.capture(), receipt.captureSha256());
    var rewritten = read("boot").withClocks(List.of(older));
    pit.capture(io -> { io.write(folder("boot").resolve("session.json"), rewritten); return null; });
    assertEquals(1, pit.keepClocks().get(20, TimeUnit.SECONDS));
    assertEquals(receipt, read("boot").clocks().get(0));
    assertEquals(0, pit.keepClocks().get(20, TimeUnit.SECONDS));
    var record = folder("boot").resolve("clock").resolve("capture.wpilog.json");
    var disagreeing = new ClockRecord(receipt.path(), receipt.sha256(), receipt.sizeBytes(), receipt.capture(), receipt.captureSha256(),
        new CaptureRecording(receipt.recording().collectingStoreId(), "2026-03-07T15:00:00Z"));
    assertThrows(java.io.IOException.class, () -> ClockRecords.read(io(), record, disagreeing));
  }

  @Test void aRecordFollowsItsCaptureWhenTheCaptureResumesAndClosesAgain() throws Exception {
    var path = writeCapture("boot", 3); record("boot", path, false, 3);
    assertEquals(1, pit.keepClocks().get(20, TimeUnit.SECONDS));
    var record = folder("boot").resolve("clock").resolve("capture.wpilog.json"); assertTrue(Files.exists(record));
    // The robot's clock continued, so the pit reopened the file: its record no longer describes it.
    record("boot", path, true, 3);
    assertEquals(List.of(), StoreCatalog.read(pit.root(), manager.testGetSecurityValidator()).unmanaged(),
        "A record its session still lists is the store's own until the keeper drops it");
    assertEquals(0, pit.keepClocks().get(20, TimeUnit.SECONDS));
    assertEquals(List.of(), read("boot").clocks()); assertFalse(Files.exists(record));
    // Closed again, longer: a new record of the new bytes.
    writeCapture("boot", 6); record("boot", path, false, 6);
    assertEquals(1, pit.keepClocks().get(20, TimeUnit.SECONDS));
    var receipt = read("boot").clocks().get(0);
    assertEquals(StoreFiles.hash(path), receipt.captureSha256());
    assertPairs(expected(6), ClockRecords.read(io(), record, receipt).uptime());
  }

  @Test void aCaptureResumedWhileItsFactsAreReadGetsNoRecord() throws Exception {
    var path = writeCapture("boot", 3); record("boot", path, false, 3);
    var manifest = folder("boot").resolve("session.json");
    var provenance = new Provenance(Provenance.CAPTURED, null, "capture.wpilog", "2026-03-07T14:22:33Z", false);
    var reopened = read("boot").withFiles(List.of());
    reopened = new Session(reopened.id(), reopened.startedAt(), reopened.endedAt(), reopened.startBasis(), null, null, null, null, List.of(),
        new OpenCapture("capture.wpilog", provenance, Files.size(path), 10, 14));
    var resumed = reopened;
    // Hold the store queue. The pass reads the catalog and the capture without it, then waits to write.
    var release = new java.util.concurrent.CountDownLatch(1);
    var holding = pit.captureAsync(io -> {
      try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("test hold timed out"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
      // The robot's clock continued: the pit reopened the file while the keeper was reading it.
      io.write(manifest, resumed); return null;
    });
    try {
      var pass = pit.keepClocks();
      org.triplehelix.wpilogmcp.harness.HarnessHttp.await("the keeper read the capture", 10, () -> manager.testIsLogLoaded(path.toString()));
      release.countDown(); holding.get(10, TimeUnit.SECONDS);
      assertEquals(0, pass.get(20, TimeUnit.SECONDS), "A record checked against the capture before its write is never published for a reopened file");
      assertEquals(List.of(), read("boot").clocks()); assertNotNull(read("boot").openCapture());
    } finally { release.countDown(); }
  }

  @Test void aCaptureWithoutUptimeSamplesGetsAnEmptyRecordSoItIsReadOnce() throws Exception {
    var path = writeCapture("laptop", 0); record("laptop", path, false, 0);
    assertEquals(1, pit.keepClocks().get(20, TimeUnit.SECONDS));
    var receipt = read("laptop").clocks().get(0);
    assertEquals(List.of(), ClockRecords.read(io(), folder("laptop").resolve(receipt.path()), receipt).uptime());
    assertEquals(0, pit.keepClocks().get(20, TimeUnit.SECONDS));
  }

  @Test void anUnreadableCaptureGetsNoRecordAndIsNotReadAgain() throws Exception {
    var path = writeCapture("broken", 3); record("broken", path, false, 3);
    // Damage the bytes behind the manifest's back: the capture cannot be read as a log.
    byte[] bytes = Files.readAllBytes(path); bytes[0] ^= 1; Files.write(path, bytes);
    assertEquals(0, pit.keepClocks().get(20, TimeUnit.SECONDS));
    assertEquals(List.of(), read("broken").clocks()); assertFalse(Files.exists(folder("broken").resolve("clock")));
    assertEquals(0, pit.keepClocks().get(20, TimeUnit.SECONDS));
  }

  @Test void aMirrorWritesNoClockRecordsOfItsOwn() throws Exception {
    var path = writeCapture("boot", 3); record("boot", path, false, 3);
    pit.capture(io -> {
      var header = io.read(pit.root().resolve("store.json"), Header.class);
      io.write(pit.root().resolve("store.json"), header.withOrigin(new MirrorOrigin("origin-id", "http://origin.invalid", null, List.of(), java.util.Map.of())));
      return null;
    });
    assertEquals(0, pit.keepClocks().get(20, TimeUnit.SECONDS));
    assertEquals(List.of(), read("boot").clocks());
  }

  @Test void aRecordWaitingForItsListingIsTheStoresButAnOrphanedOneIsAStray() throws Exception {
    var path = writeCapture("boot", 3); record("boot", path, false, 3);
    // The keeper writes a record just before listing it; a reader in between must not call it a stray.
    var pending = folder("boot").resolve("clock").resolve("capture.wpilog.json"); Files.createDirectories(pending.getParent());
    Files.writeString(pending, "{}");
    var orphan = folder("boot").resolve("clock").resolve("gone.wpilog.json"); Files.writeString(orphan, "{}");
    assertEquals(List.of(orphan), StoreCatalog.read(pit.root(), manager.testGetSecurityValidator()).unmanaged());
  }

  @Test void aManifestBeingWrittenIsNotAStrayButACrashsLeftoverIs() throws Exception {
    var path = writeCapture("boot", 3); record("boot", path, false, 3);
    var writing = folder("boot").resolve(".manifest-123.tmp"); Files.writeString(writing, "{");
    assertEquals(List.of(), StoreCatalog.read(pit.root(), manager.testGetSecurityValidator()).unmanaged());
    Files.setLastModifiedTime(writing, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(120)));
    assertEquals(List.of(writing), StoreCatalog.read(pit.root(), manager.testGetSecurityValidator()).unmanaged());
  }

  @Test void requestsDuringAPassShareOnePassAfterIt() throws Exception {
    var path = writeCapture("boot", 3); record("boot", path, false, 3);
    // Hold the store queue, so the first pass's write waits and the pass is certainly still running.
    var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
    var holding = pit.captureAsync(io -> {
      entered.countDown();
      try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("test hold timed out"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
      return null;
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var first = pit.keepClocks(); var second = pit.keepClocks(); var third = pit.keepClocks();
      assertNotSame(first, second); assertSame(second, third, "Requests made during a pass share the next one");
      release.countDown(); holding.get(10, TimeUnit.SECONDS);
      assertEquals(1, first.get(20, TimeUnit.SECONDS));
      assertEquals(0, second.get(20, TimeUnit.SECONDS), "The shared pass starts after the first and finds its record");
    } finally { release.countDown(); }
  }
}
