/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class ReplicaTransfersTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  Set<Path> previous;
  LogStore store;
  MirrorConfig policy;
  String master = id();
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    Files.createDirectories(temp.resolve("mirror"));
    store = new LogStore(temp.resolve("mirror"), manager.testGetSecurityValidator(), manager, StoreCatalog::read);
    var header = new Header(2, "2026-10-19T00:00:00Z", id(), List.of(), Map.of(), true, List.of(),
        new MirrorOrigin(master, "http://127.0.0.1:9", null, List.of(), Map.of()),
        new ReplicaCatalog(1, id(), 1, List.of(), List.of(), List.of()));
    new StoreFiles(store.root(), manager.testGetSecurityValidator()).write(store.root().resolve("store.json"), header);
    policy = new MirrorConfig("http://127.0.0.1:9", store.root(), 14, 10_000_000,
        List.of(), List.of(), 30, 0, "all", 1_048_576);
  }
  @AfterEach void cleanup() {
    store.close(); manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
  static String id() { return UUID.randomUUID().toString(); }
  static String hash(byte[] bytes) throws Exception {
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }
  byte[] payload(int tag) throws Exception { return Files.readAllBytes(ImportFixture.write(temp.resolve("input-" + tag + ".wpilog"), tag)); }
  ReplicaTransfers.Offer offer(byte[] bytes) throws Exception {
    return new ReplicaTransfers.Offer(new ReplicaCatalog.Identity(master, id(), id()), "original.wpilog",
        hash(bytes), bytes.length, "wpilog", "completed_import", "peer-A", false);
  }
  ReplicaTransfers.Source source(byte[] bytes) { return offset -> new ByteArrayInputStream(bytes, (int) offset, bytes.length - (int) offset); }

  @Test void finalizedBytesPublishOneImmutableGenerationAndExactCatalogFacts() throws Exception {
    byte[] bytes = payload(17); var offer = offer(bytes); String request = id();
    var answer = store.replicaTransfers().transfer(policy, request, offer, source(bytes)).get(10, TimeUnit.SECONDS);
    assertEquals("published", answer.state()); assertEquals(2, answer.catalogRevision());
    var entry = answer.file(); assertEquals(offer.identity(), entry.identity());
    assertEquals(hash(bytes), entry.sha256()); assertEquals(bytes.length, entry.sizeBytes());
    assertEquals("provisional", entry.verificationBasis()); assertNull(entry.confirmation());
    var path = store.root().resolve(entry.path()); assertArrayEquals(bytes, Files.readAllBytes(path));
    var inventory = StoreCatalog.read(store.root(), manager.testGetSecurityValidator());
    assertEquals(List.of(path), inventory.files().stream().map(StoreCatalog.StoredFile::path).toList());
    assertTrue(inventory.unmanaged().isEmpty(), inventory.unmanaged().toString());
    try (var use = manager.acquireReadOnly(path.toString())) {
      var values = use.log().values().get("/Value"); assertEquals(2, values.size());
      assertEquals(17.0, values.get(0).value()); assertEquals(18.0, values.get(1).value());
    }
    assertEquals(answer, store.replicaTransfers().transfer(policy, request, offer,
        offset -> { fail("A receipt retry must not fetch bytes"); return null; }).get(10, TimeUnit.SECONDS));
    assertEquals(2, store.replicaHeader().replicas().revision());
  }

  ReplicaCatalog.Confirmation confirmation(ReplicaTransfers.Offer offer) {
    return new ReplicaCatalog.Confirmation(new ReplicaCatalog.MasterRecord(offer.identity(), id(), 8,
        offer.sha256(), offer.sizeBytes(), offer.completionBasis()), policy.origin(), "2026-10-19T00:00:00Z");
  }
  @Test void analysisWarnsWhenTheMasterHasDisprovedTheHeldBytes() throws Exception {
    byte[] bytes = payload(17), correct = payload(90); var candidate = offer(bytes);
    var held = store.replicaTransfers().transfer(policy, id(), candidate, source(bytes)).get().file();
    var authority = new ReplicaCatalog.Confirmation(new ReplicaCatalog.MasterRecord(candidate.identity(), id(), 9,
        hash(correct), correct.length, "completed_import"), policy.origin(), "2026-10-19T00:00:00Z");
    store.replicaTransfers().confirm(policy, id(), authority).get();
    var tools = new org.triplehelix.wpilogmcp.mcp.ToolRegistry(); org.triplehelix.wpilogmcp.tools.StatisticsTools.registerAll(tools);
    var args = new com.google.gson.JsonObject(); args.addProperty("path", store.root().resolve(held.path()).toString()); args.addProperty("name", "/Value");
    var result = tools.getTool("get_statistics").execute(args).getAsJsonObject();
    assertEquals("ok", result.get("status").getAsString()); assertEquals(17.5, result.get("mean").getAsDouble(), 0);
    assertEquals("authoritative_mismatch", result.getAsJsonObject("inputs").getAsJsonObject("replica").get("authority_state").getAsString());
    assertTrue(result.getAsJsonArray("warnings").asList().stream().map(com.google.gson.JsonElement::getAsString)
        .anyMatch(w -> w.contains("master") && w.contains("differs") && w.contains(held.sha256()) && w.contains(hashUnchecked(correct))), result.toString());
  }
  private static String hashUnchecked(byte[] bytes) { try { return hash(bytes); } catch (Exception failure) { throw new AssertionError(failure); } }

  @Test void unconfirmedAnalysisNeverClaimsMasterAuthority() throws Exception {
    byte[] bytes = payload(17); var candidate = offer(bytes);
    var file = store.replicaTransfers().transfer(policy, id(), candidate, source(bytes)).get().file();
    var tools = new org.triplehelix.wpilogmcp.mcp.ToolRegistry(); org.triplehelix.wpilogmcp.tools.StatisticsTools.registerAll(tools);
    var args = new com.google.gson.JsonObject(); args.addProperty("path", store.root().resolve(file.path()).toString()); args.addProperty("name", "/Value");
    for (String basis : List.of("provisional", "legacy_unconfirmed")) {
      if (basis.equals("legacy_unconfirmed")) {
        var header = store.replicaHeader();
        var legacy = new ReplicaCatalog.Entry(file.key(), null, file.path(), file.sha256(), file.sizeBytes(), file.kind(), file.sessionId(),
            file.completionBasis(), basis, true, null, file.descriptor(), file.placement());
        new StoreFiles(store.root(), manager.testGetSecurityValidator()).write(store.root().resolve("store.json"), header.withReplicas(
            new ReplicaCatalog(1, header.replicas().epoch(), header.replicas().revision() + 1, List.of(legacy), List.of(), List.of())));
      }
      var result = tools.getTool("get_statistics").execute(args).getAsJsonObject();
      assertEquals(17.5, result.get("mean").getAsDouble(), 0);
      assertEquals(basis, result.getAsJsonObject("inputs").getAsJsonObject("replica").get("authority_state").getAsString());
    }
  }

  @Test void repeatedDirectConfirmationKeepsItsOriginalEvidenceAndRevision() throws Exception {
    byte[] bytes = payload(17); var candidate = offer(bytes); var first = confirmation(candidate);
    store.replicaTransfers().confirm(policy, id(), first).get();
    store.replicaTransfers().transfer(policy, id(), candidate, source(bytes)).get();
    var before = store.replicaHeader().replicas();
    var later = new ReplicaCatalog.Confirmation(first.record(), first.obtainedFrom(), "2026-10-20T00:00:00Z");
    String request = id(); var receipt = store.replicaTransfers().confirm(policy, request, later).get();
    assertEquals(before, store.replicaHeader().replicas(), "a repeated statement is not a new authority event");
    assertEquals(receipt, store.replicaTransfers().confirm(policy, request, later).get());
  }

  @Test void corruptedVerifiedContentRepairsIntoANewGenerationWithoutChangingAuthority() throws Exception {
    byte[] bytes = payload(71); var candidate = offer(bytes);
    store.replicaTransfers().confirm(policy, id(), confirmation(candidate)).get();
    var first = store.replicaTransfers().transfer(policy, id(), candidate, source(bytes)).get().file();
    var old = store.root().resolve(first.path()); byte[] corrupt = bytes.clone(); corrupt[corrupt.length - 1] ^= 1;
    Files.write(old, corrupt);
    assertThrows(Exception.class, () -> manager.acquireReadOnly(old.toString()));
    store.replicaTransfers().confirm(policy, id(), confirmation(candidate)).get();
    var repaired = store.replicaTransfers().transfer(policy, id(), candidate, source(bytes)).get().file();
    assertNotEquals(first.path(), repaired.path()); assertEquals(first.identity(), repaired.identity());
    assertEquals(first.sha256(), repaired.sha256()); assertEquals("verified", repaired.verificationBasis());
    assertArrayEquals(bytes, Files.readAllBytes(store.root().resolve(repaired.path())));
    assertArrayEquals(corrupt, Files.readAllBytes(old), "unexpected changed bytes remain charged for inspection rather than silently deleted");
    assertEquals("already_present", store.replicaTransfers().transfer(policy, id(), candidate, source(bytes)).get().state());
  }
  String reason(java.util.concurrent.CompletableFuture<?> operation) {
    var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> operation.get(10, TimeUnit.SECONDS));
    var messages = new StringBuilder();
    for (Throwable cause = error; cause != null; cause = cause.getCause()) messages.append(cause.getMessage()).append("\n");
    return messages.toString();
  }
  void until(java.util.function.BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), "Condition did not complete within five seconds");
  }
  @Test void confirmationPromotesWithoutRewritingAndConflictingVerifiedBytesNeverReplace() throws Exception {
    byte[] original = payload(1); var candidate = offer(original);
    var first = store.replicaTransfers().transfer(policy, id(), candidate, source(original)).get();
    var path = store.root().resolve(first.file().path()); var before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
    var promoted = store.replicaTransfers().confirm(policy, id(), confirmation(candidate)).get();
    assertEquals("verified", promoted.file().verificationBasis()); assertEquals(first.file().path(), promoted.file().path());
    assertEquals(before.fileKey(), Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
    assertEquals(before.lastModifiedTime(), Files.getLastModifiedTime(path));
    byte[] changed = payload(2); var different = new ReplicaTransfers.Offer(candidate.identity(), candidate.name(), hash(changed), changed.length,
        candidate.kind(), candidate.completionBasis(), "peer-B", false);
    assertTrue(reason(store.replicaTransfers().confirm(policy, id(), confirmation(different))).contains("AUTHORITY_CONFLICT"));
    assertTrue(reason(store.replicaTransfers().transfer(policy, id(), different, source(changed))).contains("MASTER_MISMATCH"));
    assertArrayEquals(original, Files.readAllBytes(path));
  }

  @Test void replacementKeepsAdmittedOldReaderAndRetiresOnlyAfterItsLeaseCloses() throws Exception {
    byte[] original = payload(3); var candidate = offer(original);
    var first = store.replicaTransfers().transfer(policy, id(), candidate, source(original)).get();
    var oldPath = store.root().resolve(first.file().path());
    try (var reader = manager.acquireReadOnly(oldPath.toString())) {
      byte[] replacement = payload(4); var next = new ReplicaTransfers.Offer(candidate.identity(), candidate.name(), hash(replacement), replacement.length,
          candidate.kind(), candidate.completionBasis(), "master", false);
      store.replicaTransfers().confirm(policy, id(), confirmation(next)).get();
      var second = store.replicaTransfers().transfer(policy, id(), next, source(replacement)).get();
      assertNotEquals(first.file().path(), second.file().path()); assertEquals(first.file().key(), second.file().key());
      assertEquals("verified", second.file().verificationBasis()); assertTrue(Files.exists(oldPath));
      assertEquals(3.0, reader.log().values().get("/Value").get(0).value());
      assertThrows(java.io.IOException.class, () -> manager.acquireReadOnly(oldPath.toString()));
      try (var fresh = manager.acquireReadOnly(store.root().resolve(second.file().path()).toString())) {
        assertEquals(4.0, fresh.log().values().get("/Value").get(0).value());
      }
      assertEquals(original.length + replacement.length, store.replicaTransfers().capacity().heldBytes());
    }
    store.replicaTransfers().recover(policy).get(); assertFalse(Files.exists(oldPath));
    assertEquals(payload(4).length, store.replicaTransfers().capacity().heldBytes());
  }

  @Test void cleanupPersistsExactExclusionBeforeRetirementAndRetrySurvivesRestart() throws Exception {
    byte[] bytes = payload(5); var candidate = offer(bytes); var owner = store.replicaTransfers();
    var first = owner.transfer(policy, id(), candidate, source(bytes)).get();
    var header = store.replicaHeader(); var preview = header.replicas().preview(header.id(), List.of(first.file().key()));
    var path = store.root().resolve(first.file().path()); String request = id();
    try (var reader = manager.acquireReadOnly(path.toString())) {
      var result = owner.cleanup(policy, request, preview).get(); assertEquals("cleanup", result.state());
      assertTrue(store.replicaHeader().replicas().files().isEmpty()); assertTrue(Files.exists(path));
      assertEquals(1, store.replicaHeader().replicas().exclusions().size());
      assertEquals(5.0, reader.log().values().get("/Value").get(0).value());
      assertEquals(result, owner.cleanup(policy, request, preview).get());
      assertTrue(reason(owner.transfer(policy, id(), candidate, source(bytes))).contains("EXCLUDED"));
    }
    owner.recover(policy).get(); assertFalse(Files.exists(path));
    assertTrue(StoreCatalog.read(store.root(), manager.testGetSecurityValidator()).files().isEmpty());
    var catalog = store.replicaHeader().replicas();
    owner.reinclude(policy, id(), catalog.epoch(), catalog.revision(), List.of(first.file().key())).get();
    assertTrue(store.replicaHeader().replicas().exclusions().isEmpty());
    assertEquals("published", owner.transfer(policy, id(), candidate, source(bytes)).get().state());
    assertTrue(reason(owner.cleanup(policy, id(), preview)).contains("STALE_PREVIEW"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"intent", "chunk_flushed", "checkpoint", "verified", "object", "manifest", "receipt"})
  void everyPublicationBoundaryRecoversIdempotently(String boundary) throws Exception {
    byte[] bytes = payload(6); var candidate = offer(bytes); String request = id();
    var fired = new java.util.concurrent.atomic.AtomicBoolean();
    store.replicaTransfers().boundary(name -> { if (name.equals(boundary) && fired.compareAndSet(false, true)) throw new java.io.IOException("injected crash " + name); });
    assertTrue(reason(store.replicaTransfers().transfer(policy, request, candidate, source(bytes))).contains("injected crash"));
    assertTrue(fired.get()); store.close();
    store = new LogStore(policy.folder(), manager.testGetSecurityValidator(), manager, StoreCatalog::read);
    store.replicaTransfers().recover(policy).get(); store.replicaTransfers().recover(policy).get();
    var answer = store.replicaTransfers().transfer(policy, request, candidate, source(bytes)).get();
    assertEquals(2, answer.catalogRevision()); assertEquals(1, store.replicaHeader().replicas().files().size());
    assertArrayEquals(bytes, Files.readAllBytes(store.root().resolve(answer.file().path())));
    assertTrue(StoreCatalog.read(store.root(), manager.testGetSecurityValidator()).unmanaged().isEmpty());
  }

  @Test void blockedReadTimesOutButRemainsChargedAndCannotPublishWhenItUnwinds() throws Exception {
    byte[] bytes = payload(7); var candidate = offer(bytes); String request = id();
    var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);

    var future = store.replicaTransfers().transfer(policy, request, candidate, offset -> new ByteArrayInputStream(bytes) {
      @Override public synchronized int read(byte[] buffer, int start, int count) {
        entered.countDown(); while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
        return super.read(buffer, start, count);
      }
    });
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      store.replicaTransfers().idleTimeout(java.time.Duration.ofMillis(100));
      assertTrue(reason(future).contains("TRANSFER_TIMEOUT")); assertTrue(store.replicaTransfers().active());
      assertTrue(store.replicaTransfers().capacity().reservedBytes() >= bytes.length);
      assertTrue(reason(store.replicaTransfers().transfer(policy, id(), candidate, source(bytes))).contains("BUSY"));
      store.pin("still-responsive", true).get(1, TimeUnit.SECONDS);
      var stage = store.root().resolve(".replicas/staging/" + request + ".part");
      assertThrows(java.io.IOException.class, () -> manager.acquireReadOnly(stage.toString()));
      assertTrue(StoreCatalog.read(store.root(), manager.testGetSecurityValidator()).files().isEmpty());
    } finally { release.countDown(); }
    until(() -> !store.replicaTransfers().active()); assertTrue(store.replicaHeader().replicas().files().isEmpty());
    assertEquals(0, store.replicaTransfers().capacity().reservedBytes());
  }

  @Test void malformedHashFormatActiveAndForeignOffersCannotPublish() throws Exception {
    byte[] bytes = payload(8); var candidate = offer(bytes);
    byte[] wrong = bytes.clone(); wrong[wrong.length - 1] ^= 1;
    String request = id(); assertTrue(reason(store.replicaTransfers().transfer(policy, request, candidate, source(wrong))).contains("HASH_MISMATCH"));
    store.replicaTransfers().discard(policy, request).get();
    byte[] invalid = new byte[]{1, 2, 3}; var malformed = offer(invalid);
    assertNotNull(reason(store.replicaTransfers().transfer(policy, request = id(), malformed, source(invalid))));
    store.replicaTransfers().discard(policy, request).get();
    var active = new ReplicaTransfers.Offer(candidate.identity(), candidate.name(), candidate.sha256(), candidate.sizeBytes(), candidate.kind(), candidate.completionBasis(), candidate.supplier(), true);
    assertTrue(reason(store.replicaTransfers().transfer(policy, id(), active, source(bytes))).contains("NOT_FINALIZED"));
    var foreign = new ReplicaTransfers.Offer(new ReplicaCatalog.Identity(id(), id(), id()), candidate.name(), candidate.sha256(), candidate.sizeBytes(), candidate.kind(), candidate.completionBasis(), candidate.supplier(), false);
    assertTrue(reason(store.replicaTransfers().transfer(policy, id(), foreign, source(bytes))).contains("FOREIGN_MASTER"));
    assertTrue(store.replicaHeader().replicas().files().isEmpty());
  }

  @Test void resumeReadsOnlyTheCheckpointedPrefixAndRejectsChangedStaging() throws Exception {
    var file = temp.resolve("many.wpilog");
    try (var writer = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(file, "independent resume fixture")) {
      int entry = writer.start("/Value", "double", "", 0);
      for (int i = 0; i < 20_000; i++) writer.append(entry, i * 20_000L, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeDouble(i));
    }
    byte[] bytes = Files.readAllBytes(file); var candidate = offer(bytes); String request = id();
    var failed = new java.util.concurrent.atomic.AtomicBoolean();
    store.replicaTransfers().boundary(name -> { if (name.equals("checkpoint") && failed.compareAndSet(false, true)) throw new java.io.IOException("interrupted"); });
    assertTrue(reason(store.replicaTransfers().transfer(policy, request, candidate, source(bytes))).contains("interrupted"));
    var stage = store.root().resolve(".replicas/staging/" + request + ".part"); assertEquals(65_536, Files.size(stage));
    assertEquals(65_536, store.replicaTransfers().capacity().heldBytes());
    byte[] prefix = Files.readAllBytes(stage); prefix[0] ^= 1; Files.write(stage, prefix); store.replicaTransfers().boundary(name -> {});
    assertTrue(reason(store.replicaTransfers().transfer(policy, request, candidate, offset -> {
      fail("Corrupt staging must be refused before fetching any more bytes"); return null;
    })).contains("CORRUPT_PARTIAL"));
    prefix[0] ^= 1; Files.write(stage, prefix);
    var offsets = new java.util.ArrayList<Long>();
    var completed = store.replicaTransfers().transfer(policy, request, candidate, offset -> {
      offsets.add(offset); return source(bytes).open(offset);
    }).get();
    assertEquals(List.of(65_536L), offsets); assertArrayEquals(bytes, Files.readAllBytes(store.root().resolve(completed.file().path())));
    try (var reader = manager.acquireReadOnly(store.root().resolve(completed.file().path()).toString())) {
      assertEquals(20_000, reader.log().sampleCount("/Value")); assertEquals(19_999.0, reader.log().values().get("/Value").get(19_999).value());
    }
  }

  @Test void replacementMustFitAlongsideIncumbentAndMetadataHeadroom() throws Exception {
    byte[] bytes = payload(9); var candidate = offer(bytes); var first = store.replicaTransfers().transfer(policy, id(), candidate, source(bytes)).get();
    byte[] changed = payload(10); var replacement = new ReplicaTransfers.Offer(candidate.identity(), candidate.name(), hash(changed), changed.length, candidate.kind(), candidate.completionBasis(), "master", false);
    store.replicaTransfers().confirm(policy, id(), confirmation(replacement)).get();
    var capped = new MirrorConfig(policy.origin(), policy.folder(), policy.days(), 1_048_576 + bytes.length + changed.length - 1,
        policy.robots(), policy.events(), policy.intervalSec(), 0, "all", 1_048_576);
    assertTrue(reason(store.replicaTransfers().transfer(capped, id(), replacement, offset -> { fail("No download can begin without its full reservation"); return null; })).contains("CAPACITY_PAUSED"));
    assertArrayEquals(bytes, Files.readAllBytes(store.root().resolve(first.file().path())));
    var retained = store.replicaHeader().replicas().files().get(0);
    assertEquals(first.file().path(), retained.path()); assertEquals(first.file().sha256(), retained.sha256());
    assertEquals(hash(changed), retained.confirmation().record().sha256());
    assertEquals("authoritative_mismatch", manager.replicaFacts(store.root().resolve(retained.path())).authorityState());
  }

  @Test void metadataFailureAndPolicyRevisionChangeCannotReplaceTheIncumbent() throws Exception {
    byte[] bytes = payload(11); var candidate = offer(bytes); var first = store.replicaTransfers().transfer(policy, id(), candidate, source(bytes)).get();
    byte[] changed = payload(12); var replacement = new ReplicaTransfers.Offer(candidate.identity(), candidate.name(), hash(changed), changed.length, candidate.kind(), candidate.completionBasis(), "master", false);
    store.replicaTransfers().confirm(policy, id(), confirmation(replacement)).get();
    var disproved = store.replicaHeader().replicas().files().get(0);
    assertEquals(first.file().path(), disproved.path()); assertEquals(first.file().sha256(), disproved.sha256());
    assertEquals(hash(changed), disproved.confirmation().record().sha256());
    String request = id();
    store.replicaTransfers().boundary(name -> { if (name.equals("before_manifest")) throw new java.nio.file.FileSystemException("synthetic store", null, "No space left on device"); });
    assertTrue(reason(store.replicaTransfers().transfer(policy, request, replacement, source(changed))).contains("No space left"));
    assertEquals(disproved, store.replicaHeader().replicas().files().get(0));
    assertArrayEquals(bytes, Files.readAllBytes(store.root().resolve(first.file().path())));
    store.replicaTransfers().boundary(name -> { if (name.equals("before_manifest")) try { store.pin("changed-policy", true).get(1, TimeUnit.SECONDS); } catch (Exception e) { throw new java.io.IOException(e); } });
    assertTrue(reason(store.replicaTransfers().transfer(policy, request, replacement, source(changed))).contains("STALE_INTENT"));
    assertEquals(disproved, store.replicaHeader().replicas().files().get(0));
    store.replicaTransfers().boundary(name -> {}); store.replicaTransfers().discard(policy, request).get();
  }

  @Test void twoGlobalWorkersHaveNoWaitingQueueAndShutdownCannotPublishALateRead() throws Exception {
    byte[] bytes = payload(13); var entered = new java.util.concurrent.CountDownLatch(2); var release = new java.util.concurrent.CountDownLatch(1);
    Files.createDirectories(temp.resolve("other")); Files.createDirectories(temp.resolve("third"));
    var other = new LogStore(temp.resolve("other"), manager.testGetSecurityValidator(), manager, StoreCatalog::read);
    var third = new LogStore(temp.resolve("third"), manager.testGetSecurityValidator(), manager, StoreCatalog::read);
    var original = store.replicaHeader();
    for (var destination : List.of(other, third)) new StoreFiles(destination.root(), manager.testGetSecurityValidator()).write(destination.root().resolve("store.json"), original);
    var otherPolicy = new MirrorConfig(policy.origin(), other.root(), 14, policy.maxSizeBytes(), List.of(), List.of(), 30, 0, "all", policy.metadataReserveBytes());
    var thirdPolicy = new MirrorConfig(policy.origin(), third.root(), 14, policy.maxSizeBytes(), List.of(), List.of(), 30, 0, "all", policy.metadataReserveBytes());
    ReplicaTransfers.Source blocked = offset -> new ByteArrayInputStream(bytes) {
      @Override public synchronized int read(byte[] buffer, int start, int count) {
        entered.countDown(); while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
        return super.read(buffer, start, count);
      }
    };
    var first = store.replicaTransfers().transfer(policy, id(), offer(bytes), blocked);
    var second = other.replicaTransfers().transfer(otherPolicy, id(), offer(bytes), blocked);
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(reason(third.replicaTransfers().transfer(thirdPolicy, id(), offer(bytes), source(bytes))).contains("two replica workers"));
      long before = System.nanoTime(); store.close(); assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1));
      assertTrue(first.isCancelled()); assertTrue(store.replicaTransfers().active());
      assertTrue(reason(third.replicaTransfers().transfer(thirdPolicy, id(), offer(bytes), source(bytes))).contains("two replica workers"));
    } finally { release.countDown(); }
    second.get(5, TimeUnit.SECONDS); until(() -> !store.replicaTransfers().active());
    assertTrue(store.replicaHeader().replicas().files().isEmpty()); other.close(); third.close();
  }

  @Test void continuationsAndNotificationsRunAfterBothStoreAndTransferLocksRelease() throws Exception {
    byte[] bytes = payload(14); var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    try (var observation = store.observe(new LogStore.Observer() {
      @Override public void inventory(StoreCatalog.Snapshot snapshot) { }
      @Override public void manifest(Path path, Object value) {
        try (var lock = StoreLock.acquire(store.root(), manager.testGetSecurityValidator());
            var channel = java.nio.channels.FileChannel.open(store.root().resolve(".replicas/owner.lock"), java.nio.file.StandardOpenOption.WRITE);
            var owner = channel.tryLock()) { assertNotNull(owner); }
        catch (Throwable failure) { observed.set(failure); }
      }
    })) {
      store.replicaTransfers().transfer(policy, id(), offer(bytes), source(bytes)).thenApply(result -> {
        assertDoesNotThrow(() -> { try (var lock = StoreLock.acquire(store.root(), manager.testGetSecurityValidator())) { } }); return result;
      }).get();
      assertNull(observed.get(), String.valueOf(observed.get()));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"intent", "manifest", "receipt", "retired"})
  void cleanupCrashBoundariesKeepExclusionAndNeverDeleteUnmanagedFiles(String boundary) throws Exception {
    byte[] bytes = payload(15); var first = store.replicaTransfers().transfer(policy, id(), offer(bytes), source(bytes)).get();
    var stray = store.root().resolve("manual.wpilog"); Files.write(stray, bytes);
    var header = store.replicaHeader(); var preview = header.replicas().preview(header.id(), List.of(first.file().key())); String request = id();
    var fired = new java.util.concurrent.atomic.AtomicBoolean();
    store.replicaTransfers().boundary(name -> { if (name.equals(boundary) && fired.compareAndSet(false, true)) throw new java.io.IOException("cleanup crash"); });
    assertTrue(reason(store.replicaTransfers().cleanup(policy, request, preview)).contains("cleanup crash")); assertTrue(fired.get());
    store.replicaTransfers().boundary(name -> {}); store.replicaTransfers().recover(policy).get();
    assertEquals("cleanup", store.replicaTransfers().cleanup(policy, request, preview).get().state());
    store.replicaTransfers().recover(policy).get(); assertTrue(store.replicaHeader().replicas().files().isEmpty());
    assertEquals(1, store.replicaHeader().replicas().exclusions().size()); assertArrayEquals(bytes, Files.readAllBytes(stray));
    assertEquals(List.of(stray), StoreCatalog.read(store.root(), manager.testGetSecurityValidator()).unmanaged());
  }

  @Test void admittedVerifiedBytesAreRecheckedWhenTheirFilesystemBoundaryChanges() throws Exception {
    byte[] bytes = payload(16); var candidate = offer(bytes); var result = store.replicaTransfers().transfer(policy, id(), candidate, source(bytes)).get();
    store.replicaTransfers().confirm(policy, id(), confirmation(candidate)).get();
    var path = store.root().resolve(result.file().path());
    try (var warm = manager.acquireReadOnly(path.toString())) { assertEquals(16.0, warm.log().values().get("/Value").get(0).value()); }
    org.triplehelix.wpilogmcp.fixtures.LogRewrites.flipLastByte(path);
    Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));
    var failure = assertThrows(java.io.IOException.class, () -> manager.acquireReadOnly(path.toString()));
    assertTrue(failure.getMessage().contains("hash"), failure.getMessage());
    assertEquals(result.file().sha256(), store.replicaHeader().replicas().files().get(0).sha256());
  }

  @Test void occupiedPrivateNamesAreNotAdoptedOrDeletedByDiscard() throws Exception {
    byte[] bytes = payload(18); var candidate = offer(bytes); String request = id();
    var occupied = new java.util.concurrent.atomic.AtomicReference<Path>();
    store.replicaTransfers().boundary(name -> {
      if (!name.equals("verified")) return;
      var pending = ReplicaJournal.read(store.root(), new StoreFiles(store.root(), manager.testGetSecurityValidator())).pending();
      var destination = store.root().resolve(pending.target().path()); Files.createDirectories(destination.getParent());
      Files.write(destination, new byte[]{9, 8, 7}); occupied.set(destination);
    });
    reason(store.replicaTransfers().transfer(policy, request, candidate, source(bytes)));
    assertNotNull(occupied.get()); store.replicaTransfers().boundary(name -> {});
    store.replicaTransfers().discard(policy, request).get();
    assertTrue(Files.exists(occupied.get()), "Discard must retain an unowned destination");
    assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(occupied.get()));
    assertTrue(StoreCatalog.read(store.root(), manager.testGetSecurityValidator()).unmanaged().contains(occupied.get()));
  }
  @Test void preexistingStagingNameIsRefusedWithoutTruncatingItsBytes() throws Exception {
    byte[] bytes = payload(19); var candidate = offer(bytes); String request = id();
    var stage = store.root().resolve(".replicas/staging/" + request + ".part"); Files.createDirectories(stage.getParent());
    Files.write(stage, new byte[]{9, 8, 7});
    assertTrue(reason(store.replicaTransfers().transfer(policy, request, candidate, source(bytes))).contains("OCCUPIED"));
    assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(stage)); assertTrue(store.replicaHeader().replicas().files().isEmpty());
  }

  @Test void boundedRetryHistoryDoesNotCapLifetimeAcquisitionsAt128() throws Exception {
    byte[] bytes = payload(20); var candidate = offer(bytes); String initial = id();
    var first = store.replicaTransfers().transfer(policy, initial, candidate, source(bytes)).get();
    for (int i = 0; i < 130; i++) {
      var reply = assertDoesNotThrow(() -> store.replicaTransfers().transfer(policy, id(), candidate, offset -> { fail("An existing candidate never downloads again"); return null; }).get());
      assertEquals("already_present", reply.state()); assertEquals(first.file(), reply.file());
    }
    var state = ReplicaJournal.read(store.root(), new StoreFiles(store.root(), manager.testGetSecurityValidator()));
    assertEquals(128, state.receipts().size()); assertEquals(2, store.replicaHeader().replicas().revision());
    assertEquals("already_present", store.replicaTransfers().transfer(policy, initial, candidate, source(bytes)).get().state());
    assertArrayEquals(bytes, Files.readAllBytes(store.root().resolve(first.file().path())));
  }

  @Test void formatTwoPinsReserveMetadataAndPublishMatchingCapacity() throws Exception {
    var small = new MirrorConfig(policy.origin(), policy.folder(), 14, 4095,
        List.of(), List.of(), 30, 0, "all", 4096);
    var before = Files.readAllBytes(store.root().resolve("store.json"));
    try (var service = new MirrorService(store, small)) {
      assertTrue(reason(service.pin("session", true)).contains("CAPACITY_PAUSED"));
      assertEquals("error", service.status().state());
      assertNotNull(service.status().operation());
      assertTrue(service.status().operation().error().contains("CAPACITY_PAUSED"));
      assertArrayEquals(before, Files.readAllBytes(store.root().resolve("store.json")));
    }
    try (var service = new MirrorService(store, policy)) {
      service.syncNow().get(); service.pin("session", true).get();
      assertEquals(List.of("session"), service.status().origin().pinnedSessions());
      assertEquals(2, service.status().replicas().catalogRevision());
      assertEquals(store.mirrorCapacity(policy), service.status().capacity());
    }
  }
  @Test void legacyCleanupExcludesItsBytesWithoutGuessingAnIdentity() throws Exception {
    byte[] bytes = payload(21); var candidate = offer(bytes);
    var first = store.replicaTransfers().transfer(policy, id(), candidate, source(bytes)).get();
    var entry = first.file(); var header = store.replicaHeader(); var catalog = header.replicas();
    var legacy = new ReplicaCatalog.Entry(entry.key(), null, entry.path(), entry.sha256(), entry.sizeBytes(),
        entry.kind(), null, entry.completionBasis(), "legacy_unconfirmed", true, null, entry.descriptor());
    new StoreFiles(store.root(), manager.testGetSecurityValidator()).write(store.root().resolve("store.json"),
        header.withReplicas(new ReplicaCatalog(1, catalog.epoch(), catalog.revision(), List.of(legacy), List.of(), List.of())));
    store.replicaTransfers().cleanup(policy, id(), store.replicaHeader().replicas().preview(header.id(), List.of(entry.key()))).get();
    assertNull(store.replicaHeader().replicas().exclusions().get(0).identity());
    assertTrue(reason(store.replicaTransfers().transfer(policy, id(), candidate, source(bytes))).contains("EXCLUDED"));
  }

  @Test void recoveryMetadataKeepsTheSameBudgetAsInitialPublication() throws Exception {
    byte[] bytes = payload(22); String request = id(); var candidate = offer(bytes);
    store.replicaTransfers().boundary(name -> { if (name.equals("manifest")) throw new java.io.IOException("restart before receipt"); });
    assertTrue(reason(store.replicaTransfers().transfer(policy, request, candidate, source(bytes))).contains("restart before receipt"));
    store.close(); store = new LogStore(policy.folder(), manager.testGetSecurityValidator(), manager, StoreCatalog::read);
    var before = Files.readAllBytes(store.root().resolve(ReplicaJournal.JOURNAL));
    var small = new MirrorConfig(policy.origin(), policy.folder(), 14, 4095,
        List.of(), List.of(), 30, 0, "all", 4096);
    assertTrue(reason(store.replicaTransfers().recover(small)).contains("CAPACITY_PAUSED"));
    assertArrayEquals(before, Files.readAllBytes(store.root().resolve(ReplicaJournal.JOURNAL)));
    store.replicaTransfers().recover(policy).get();
    assertEquals("published", store.replicaTransfers().transfer(policy, request, candidate, source(bytes)).get().state());
    assertEquals(2, store.replicaHeader().replicas().revision());
  }
  @Test void aFinishedJobNoLongerHoldsAWorkerWhileItsThreadReturnsToThePool() throws Exception {
    // A job's result wakes its caller while the job's thread is still finishing. A dependent that blocks
    // keeps that thread there, as a busy machine can; with two such threads, the next job used to be
    // refused as if two transfers were running.
    var hold = new java.util.concurrent.CountDownLatch(1);
    try {
      for (int i = 0; i < 2; i++) {
        byte[] bytes = payload(40 + i);
        var gate = new java.util.concurrent.CountDownLatch(1); var entered = new java.util.concurrent.CountDownLatch(1);
        var job = store.replicaTransfers().transfer(policy, id(), offer(bytes), offset -> new ByteArrayInputStream(bytes, (int) offset, bytes.length - (int) offset) {
          @Override public synchronized int read(byte[] buffer, int start, int count) {
            entered.countDown(); while (gate.getCount() > 0) try { gate.await(); } catch (InterruptedException ignored) { }
            return super.read(buffer, start, count);
          }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var finishing = new java.util.concurrent.CountDownLatch(1);
        job.whenComplete((receipt, failure) -> {
          finishing.countDown();
          try { hold.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        gate.countDown();
        assertTrue(finishing.await(5, TimeUnit.SECONDS), "the job's own thread runs the dependent and stays there");
        assertEquals("published", job.getNow(null).state());
      }
      assertEquals("recovered", store.replicaTransfers().recover(policy).get(5, TimeUnit.SECONDS).state());
    } finally { hold.countDown(); }
  }
  @Test void aRecheckThatMustFirstRecoverReservesForTheRecoveryUnderItsOwnBudget() throws Exception {
    // A recheck only reads and measures nothing, but finishing a transfer it finds interrupted writes.
    byte[] bytes = payload(17), next = payload(22); var held = offer(bytes);
    store.replicaTransfers().transfer(policy, id(), held, source(bytes)).get(10, TimeUnit.SECONDS);
    store.replicaTransfers().confirm(policy, id(), confirmation(held)).get(10, TimeUnit.SECONDS);
    store.replicaTransfers().boundary(name -> { if (name.equals("manifest")) throw new java.io.IOException("restart before receipt"); });
    assertTrue(reason(store.replicaTransfers().transfer(policy, id(), offer(next), source(next))).contains("restart before receipt"));
    store.close(); store = new LogStore(policy.folder(), manager.testGetSecurityValidator(), manager, StoreCatalog::read);
    var owner = store.replicaTransfers(); var journal = store.root().resolve(ReplicaJournal.JOURNAL); var before = Files.readAllBytes(journal);
    var small = new MirrorConfig(policy.origin(), policy.folder(), 14, 4095,
        List.of(), List.of(), 30, 0, "all", 4096);
    var refused = assertThrows(java.util.concurrent.ExecutionException.class,
        () -> owner.reconcile(owner.reconciliationEpoch(small), () -> owner.unchanged(small, held)));
    assertTrue(String.valueOf(refused.getCause().getMessage()).contains("CAPACITY_PAUSED"), refused.toString());
    assertArrayEquals(before, Files.readAllBytes(journal));
    assertTrue(owner.reconcile(owner.reconciliationEpoch(policy), () -> owner.unchanged(policy, held)));
    var recovered = ReplicaJournal.read(store.root(), new StoreFiles(store.root(), manager.testGetSecurityValidator()));
    assertNull(recovered.pending()); assertTrue(recovered.retired().isEmpty(), recovered.retired().toString());
  }

}
