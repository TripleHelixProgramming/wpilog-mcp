/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class ReplicaContractsTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  final Clock clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
  Set<Path> previous;
  LogStore store;
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    store = manager.stores().store(temp.resolve("store"));
  }
  @AfterEach void cleanup() {
    manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
  StoreFiles io() throws IOException { return new StoreFiles(store.root(), manager.testGetSecurityValidator()); }
  Path imported() throws Exception {
    var input = ImportFixture.write(temp.resolve("fixture.wpilog"), 1);
    return store.importPaths(new LogStore.Request(List.of(input), false, "RIO"), p -> {}).get().files().get(0).path();
  }
  String hash(Path path) throws Exception {
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
  static String id() { return UUID.randomUUID().toString(); }

  @Test void masterMigrationAssignsStableDistinctIdentityAndExactFinalizationReceipt() throws Exception {
    var path = imported(); byte[] bytes = Files.readAllBytes(path);
    var before = store.replicaHeader();
    var records = store.migrateReplicas(before.id()).get();
    assertEquals(1, records.files().size()); var entry = records.files().get(0);
    assertEquals(before.id(), entry.identity().masterStoreId());
    assertNotEquals(entry.identity().fileId(), entry.identity().fileVersion());
    assertEquals(hash(path), entry.sha256()); assertEquals(bytes.length, entry.sizeBytes());
    assertEquals("master_local", entry.verificationBasis()); assertTrue(entry.transferVerified());
    assertEquals("completed_import", entry.completionBasis());
    assertEquals(entry.identity(), entry.confirmation().record().identity());
    assertEquals(records.epoch(), entry.confirmation().record().catalogEpoch());
    assertEquals(1, entry.confirmation().record().catalogRevision());
    assertEquals(hash(path), entry.confirmation().record().sha256());
    assertEquals(bytes.length, entry.confirmation().record().sizeBytes());
    assertEquals("local_master_catalog", entry.confirmation().obtainedFrom());
    assertEquals(records, store.migrateReplicas(before.id()).get(), "retry preserves all assigned UUIDs");
    assertArrayEquals(bytes, Files.readAllBytes(path));
    assertEquals(before, io().read(store.root().resolve(".replicas/format-1-store.json"), Header.class));
    assertEquals(2, StoreCatalog.read(store.root(), manager.testGetSecurityValidator()).header().formatVersion());
    assertTrue(StoreCatalog.read(store.root(), manager.testGetSecurityValidator()).unmanaged().isEmpty());
  }

  @Test void legacyMirrorRetainsBytesPinsJournalsAndStraysWithoutInventingMasterProof() throws Exception {
    var path = imported(); byte[] bytes = Files.readAllBytes(path);
    var original = store.replicaHeader();
    var header = original.withOrigin(new MirrorOrigin(id(), "http://127.0.0.1:9", null, List.of("pinned"), Map.of()));
    io().write(store.root().resolve("store.json"), header);
    var partial = store.root().resolve(".mirror/files/partial.bin"); Files.createDirectories(partial.getParent());
    Files.write(partial, new byte[]{1, 2, 3});
    io().write(store.root().resolve(".mirror/pull.json"), new org.triplehelix.wpilogmcp.sync.PullManifest(1, null,
        List.of(new org.triplehelix.wpilogmcp.sync.PullManifest.Entry("unfinished", 9, 0, 3, false,
            ".mirror/files/partial.bin", 0, null)), List.of()));
    var stray = store.root().resolve("someone.wpilog"); Files.write(stray, new byte[]{8, 9});
    byte[] journal = Files.readAllBytes(store.root().resolve(".mirror/pull.json"));
    var records = store.migrateReplicas(header.id()).get(); var entry = records.files().get(0);
    assertEquals("legacy_unconfirmed", entry.verificationBasis());
    assertNull(entry.identity()); assertNull(entry.confirmation()); assertTrue(entry.transferVerified());
    assertEquals(List.of("pinned"), records.includedSessions());
    assertEquals(hash(path), entry.sha256()); assertArrayEquals(bytes, Files.readAllBytes(path));
    assertArrayEquals(journal, Files.readAllBytes(store.root().resolve(".mirror/pull.json")));
    assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(partial)); assertArrayEquals(new byte[]{8, 9}, Files.readAllBytes(stray));
    assertEquals(List.of(stray), StoreCatalog.read(store.root(), manager.testGetSecurityValidator()).unmanaged());
    var result = store.mirror(new MirrorConfig("http://127.0.0.1:9", store.root(), 0, 1, List.of(), List.of(), 30, 0), p -> {}).get();
    assertEquals("partial", result.state()); assertTrue(result.evicted().isEmpty());
    assertFalse(result.refusals().isEmpty(), "unreachable master must not imply complete synchronization");
  }

  @Test void stalePreparationAndCorruptionCannotPublishTheFormatMarker() throws Exception {
    var path = imported(); var before = store.replicaHeader();
    try (var prepared = ReplicaMigration.prepare(store.root(), manager.testGetSecurityValidator(), clock)) {
      Files.write(path, new byte[]{1, 2, 3});
      assertTrue(assertThrows(IOException.class, () -> ReplicaMigration.publish(store.root(), io(), prepared)).getMessage().contains("STALE_MIGRATION"));
    }
    assertEquals(before, store.replicaHeader());
    assertThrows(IOException.class, () -> ReplicaMigration.prepare(store.root(), manager.testGetSecurityValidator(), clock));
    assertFalse(Files.exists(store.root().resolve(".replicas/format-1-store.json")));
  }

  @Test void failedMarkerWriteKeepsReadableBackupAndRetryIsIdempotent() throws Exception {
    var path = imported(); var before = store.replicaHeader(); var expected = hash(path);
    try (var prepared = ReplicaMigration.prepare(store.root(), manager.testGetSecurityValidator(), clock)) {
      var failing = new StoreFiles(store.root(), manager.testGetSecurityValidator(), (p, v) -> {}, (from, to) -> {
        if (to.equals(store.root().resolve("store.json"))) throw new IOException("planted marker failure");
        Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }, ms -> {}, false);
      assertTrue(assertThrows(IOException.class, () -> ReplicaMigration.publish(store.root(), failing, prepared)).getMessage().contains("planted marker"));
    }
    assertEquals(before, store.replicaHeader()); assertEquals(expected, hash(path));
    assertEquals(before, io().read(store.root().resolve(".replicas/format-1-store.json"), Header.class));
    var records = store.migrateReplicas(before.id()).get();
    assertEquals(records, store.migrateReplicas(before.id()).get()); assertEquals(expected, hash(path));
  }

  @Test void cleanupPreviewNamesExactBytesAndRevisionButDoesNotDelete() throws Exception {
    var path = imported(); var records = store.migrateReplicas(store.replicaHeader().id()).get();
    var entry = records.files().get(0); var preview = records.preview(store.replicaHeader().id(), List.of(entry.key()));
    assertEquals(List.of(entry), preview.files()); assertEquals(Files.size(path), preview.payloadBytes());
    assertEquals(List.of(new ReplicaCatalog.Exclusion(entry.key(), entry.identity(), hash(path))), preview.acquisitionExclusions());
    assertEquals("writer_not_ready", preview.writerState());
    var excluded = records.excluding(preview); assertEquals(2, excluded.revision());
    assertThrows(IllegalArgumentException.class, () -> excluded.excluding(preview));
    assertThrows(IllegalArgumentException.class, () -> records.preview(store.replicaHeader().id(), List.of("../someone.wpilog")));
    assertEquals(hash(path), entry.sha256()); assertTrue(Files.exists(path));
  }

  @Test void peerBooleanOrMismatchingMasterRecordCannotCreateVerification() {
    var identity = new ReplicaCatalog.Identity(id(), id(), id()); String hash = "a".repeat(64);
    var wrong = new ReplicaCatalog.Confirmation(new ReplicaCatalog.MasterRecord(identity, id(), 1, "b".repeat(64), 100, "closed_capture"),
        "http://127.0.0.1:9", clock.instant().toString());
    assertThrows(IllegalArgumentException.class, () -> new ReplicaCatalog.Entry(id(), identity, "record.wpilog", hash, 100, "wpilog", null,
        "closed_capture", "verified", true, null));
    assertThrows(IllegalArgumentException.class, () -> new ReplicaCatalog.Entry(id(), identity, "record.wpilog", hash, 100, "wpilog", null,
        "closed_capture", "verified", true, wrong));
  }

  @Test void formatTwoHeaderRejectsVerificationFromAnotherMaster() {
    var identity = new ReplicaCatalog.Identity(id(), id(), id()); String hash = "a".repeat(64);
    var proof = new ReplicaCatalog.Confirmation(new ReplicaCatalog.MasterRecord(identity, id(), 1, hash, 100, "closed_capture"),
        "http://127.0.0.1:9", clock.instant().toString());
    var entry = new ReplicaCatalog.Entry(id(), identity, "record.wpilog", hash, 100, "wpilog", null,
        "closed_capture", "verified", true, proof);
    var catalog = new ReplicaCatalog(1, id(), 1, List.of(entry), List.of(), List.of());
    assertThrows(IllegalArgumentException.class, () -> new Header(2, clock.instant().toString(), id(), List.of(), Map.of(), true,
        List.of(), new MirrorOrigin(id(), "http://127.0.0.1:9", null, List.of(), Map.of()), catalog));
  }

  @Test void capacityAccountsIncumbentStagingAndMetadataWithCheckedArithmetic() throws Exception {
    var capacity = new MirrorCapacity(600, 80, 1000, 100);
    var first = capacity.reserve(200); assertEquals(200, capacity.snapshot().reservedBytes());
    assertThrows(IOException.class, () -> capacity.reserve(101));
    var second = capacity.reserve(100); assertEquals(300, capacity.snapshot().reservedBytes());
    first.close(); first.close(); assertEquals(100, capacity.snapshot().reservedBytes());
    second.publish(); second.close(); assertEquals(700, capacity.snapshot().heldBytes());
    assertEquals(0, capacity.snapshot().reservedBytes());
    assertThrows(IllegalStateException.class, second::publish);
    assertThrows(IOException.class, () -> capacity.reserve(201));
    assertTrue(new MirrorCapacity(1001, 0, 1000, 100).snapshot().capacityPaused());
    assertThrows(IOException.class, () -> new MirrorCapacity(Long.MAX_VALUE - 1, 0, Long.MAX_VALUE, 100).reserve(10));
    var full = new MirrorCapacity(900, 10, 1000, 100);
    assertTrue(full.snapshot().capacityPaused());
    try (var metadata = full.reserveMetadata(90)) {
      assertEquals(90, full.snapshot().reservedBytes());
      assertThrows(IOException.class, () -> full.reserveMetadata(1));
      assertThrows(IOException.class, () -> full.reserve(1));
    }
    assertEquals(0, full.snapshot().reservedBytes());
  }

  @Test void metadataAndCleanupLimitsRefuseWithoutDroppingExistingRecords() {
    var files = new java.util.ArrayList<ReplicaCatalog.Entry>();
    for (int i = 0; i < 4096; i++) files.add(new ReplicaCatalog.Entry(id(), null, "file-" + i + ".wpilog",
        "a".repeat(64), 100, "wpilog", null, "completed_import", "legacy_unconfirmed", true, null));
    var catalog = new ReplicaCatalog(1, id(), 1, files, List.of(), List.of());
    assertEquals(3200, catalog.preview(id(), files.subList(0, 32).stream().map(ReplicaCatalog.Entry::key).toList()).payloadBytes());
    assertThrows(IllegalArgumentException.class, () -> catalog.preview(id(), files.subList(0, 33).stream().map(ReplicaCatalog.Entry::key).toList()));
    files.add(new ReplicaCatalog.Entry(id(), null, "overflow.wpilog", "a".repeat(64), 100, "wpilog", null,
        "completed_import", "legacy_unconfirmed", true, null));
    assertThrows(IllegalArgumentException.class, () -> new ReplicaCatalog(1, id(), 1, files, List.of(), List.of()));
    var exclusions = files.stream().map(file -> new ReplicaCatalog.Exclusion(file.key(), null, file.sha256())).toList();
    assertThrows(IllegalArgumentException.class, () -> new ReplicaCatalog(1, id(), 1, catalog.files(), List.of(), exclusions));
    assertEquals(4096, catalog.files().size()); assertTrue(catalog.exclusions().isEmpty());
  }

  @Test void finalizedRolloverMembersRemainEligibleWhileTheNextMemberIsGrowing() throws Exception {
    var path = imported();
    var snapshot = StoreCatalog.readManaged(store.root(), manager.testGetSecurityValidator());
    var session = snapshot.sessions().get(0).session(); var original = session.files().get(0);
    var provenance = new Provenance("captured", null, "capture-1.wpilog", clock.instant().toString(), false);
    var closed = new LogFile(original.path(), original.sha256(), original.sizeBytes(), "wpilog", provenance,
        true, 0, 1, session.startedAt(), session.endedAt(), "pit_server_clock", false, null);
    var second = new LogFile("capture-2.wpilog", "b".repeat(64), 123, "wpilog", provenance,
        true, 1, 2, session.startedAt(), session.endedAt(), "pit_server_clock", false, null);
    var mixed = new Session(session.id(), session.startedAt(), session.endedAt(), session.startBasis(), null, null, null, null,
        List.of(closed, second), new OpenCapture("capture-3.wpilog", provenance, 37, 2, 3));
    assertEquals(List.of(closed, second), MirrorSync.finalized(mixed));
    var inconsistent = new Session(session.id(), session.startedAt(), session.endedAt(), session.startBasis(), null, null, null, null,
        List.of(closed, second), new OpenCapture(second.path(), provenance, 123, 1, 2));
    assertEquals(List.of(closed), MirrorSync.finalized(inconsistent));
    assertTrue(Files.exists(path));
  }

  @Test void migrationRechecksNewDirectoriesAndStoreIdentity() throws Exception {
    imported(); var before = store.replicaHeader();
    try (var prepared = ReplicaMigration.prepare(store.root(), manager.testGetSecurityValidator(), clock)) {
      Files.createDirectories(store.root().resolve("robots/new-robot"));
      assertThrows(IOException.class, () -> ReplicaMigration.publish(store.root(), io(), prepared));
    }
    assertEquals(before, store.replicaHeader());
    assertThrows(java.util.concurrent.ExecutionException.class, () -> store.migrateReplicas(id()).get());
    assertEquals(before, store.replicaHeader());
  }

  @Test void twoBuildersHaveNoWaitingQueueAndCancellationKeepsItsSlotUntilUnwind() throws Exception {
    imported();
    var other = manager.stores().store(temp.resolve("other"));
    var third = manager.stores().store(temp.resolve("third"));
    other.importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
    third.importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
    var entered = new java.util.concurrent.CountDownLatch(2); var release = new java.util.concurrent.CountDownLatch(1);
    LogStore.ReplicaPreparation slow = () -> {
      entered.countDown();
      try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture timeout"); }
      catch (InterruptedException e) { throw new IOException(e); }
      return ReplicaMigration.prepare(store.root(), manager.testGetSecurityValidator(), clock);
    };
    var one = store.migrateReplicas(store.replicaHeader().id(), null, slow);
    var two = other.migrateReplicas(other.replicaHeader().id(), null, () -> {
      entered.countDown();
      try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture timeout"); }
      catch (InterruptedException e) { throw new IOException(e); }
      return ReplicaMigration.prepare(other.root(), manager.testGetSecurityValidator(), clock);
    });
    try {
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      assertThrows(java.util.concurrent.ExecutionException.class, () -> store.migrateReplicas(store.replicaHeader().id()).get());
      one.cancel(false);
      var refusal = assertThrows(java.util.concurrent.ExecutionException.class, () -> third.migrateReplicas(third.replicaHeader().id()).get());
      assertTrue(refusal.getCause().getMessage().contains("two replica migrations"));
    } finally { release.countDown(); }
    two.get(10, java.util.concurrent.TimeUnit.SECONDS);
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (store.importing() && System.nanoTime() < deadline) Thread.onSpinWait();
    assertFalse(store.importing()); assertEquals(1, store.replicaHeader().formatVersion());
    assertEquals(2, other.replicaHeader().formatVersion());
  }

  @Test void diskAccountingIncludesPartialAndMetadataButDoesNotAdoptStrays() throws Exception {
    var path = imported(); var header = store.replicaHeader().withOrigin(new MirrorOrigin(id(), "http://127.0.0.1:9", null, List.of(), Map.of()));
    io().write(store.root().resolve("store.json"), header);
    var partial = store.root().resolve(".mirror/files/partial"); Files.createDirectories(partial.getParent()); Files.write(partial, new byte[]{1, 2, 3});
    io().write(store.root().resolve(".mirror/pull.json"), new org.triplehelix.wpilogmcp.sync.PullManifest(1, null,
        List.of(new org.triplehelix.wpilogmcp.sync.PullManifest.Entry("unfinished", 9, 0, 3, false, ".mirror/files/partial", 0, null)), List.of()));
    var stray = store.root().resolve("stray.wpilog"); Files.write(stray, new byte[77]);
    var config = new MirrorConfig(header.origin().url(), store.root(), 14, 1, List.of(), List.of(), 30, 0);
    var capacity = store.mirrorCapacity(config);
    assertEquals(Files.size(path) + 3, capacity.heldBytes()); assertEquals(0, capacity.reservedBytes());
    long metadata = 0;
    try (var paths = Files.walk(store.root())) {
      for (var file : paths.filter(Files::isRegularFile).toList()) if (!List.of(path, partial, stray, store.root().resolve("store.lock")).contains(file)) metadata += Files.size(file);
    }
    assertEquals(metadata, capacity.metadataBytes()); assertTrue(capacity.capacityPaused());
    assertThrows(java.util.concurrent.ExecutionException.class, () -> store.migrateReplicas(header.id(), config).get());
    assertEquals(1, store.replicaHeader().formatVersion()); assertArrayEquals(new byte[77], Files.readAllBytes(stray));
  }

  @Test void acquisitionScopeUsesEvidenceAndNeverTreatsAClockAdvanceAsRemoval() {
    var config = new MirrorConfig("http://127.0.0.1:9", temp, 14, 1000, List.of("RIO"), List.of("District"), 30, 0);
    assertTrue(MirrorSync.acquires(config, "RIO", null, "2026-10-18T00:00:00Z", "logged", clock.instant(), false));
    assertFalse(MirrorSync.acquires(config, "OTHER", "District", "2026-10-18T00:00:00Z", "logged", clock.instant(), false));
    assertTrue(MirrorSync.acquires(config, "RIO", "District", null, null, clock.instant(), false));
    assertFalse(MirrorSync.acquires(config, "RIO", null, null, null, clock.instant(), false));
    assertFalse(MirrorSync.acquires(config, "RIO", null, "2026-10-18T00:00:00Z", "file_mtime", clock.instant(), false));
    assertFalse(MirrorSync.acquires(config, "RIO", null, "2026-10-18T00:00:00Z", "logged", clock.instant().plusSeconds(365L * 86400), false));
    var all = new MirrorConfig(config.origin(), temp, 0, 1000, List.of(), List.of(), 30, 0, "all", 4096);
    assertTrue(MirrorSync.acquires(all, "OTHER", null, null, null, clock.instant(), false));
    assertTrue(MirrorSync.acquires(config, "OTHER", null, null, null, clock.instant(), true));
  }
}
