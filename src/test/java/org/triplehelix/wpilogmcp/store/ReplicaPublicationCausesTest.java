/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

/** Durable capture receipts already survive a full catalog; their missing explanation must too. */
class ReplicaPublicationCausesTest {
  @TempDir Path directory;
  private LogManager manager;
  private LogStore store;
  private Header seed;
  private Set<Path> allowed;
  private boolean diskCacheEnabled;
  private boolean syncCacheEnabled;

  @BeforeEach void setup() throws Exception {
    directory = directory.toRealPath();
    manager = LogManager.getInstance();
    allowed = manager.getAllowedDirectories();
    diskCacheEnabled = manager.getDiskCache().isEnabled();
    syncCacheEnabled = manager.getSyncDiskCache().isEnabled();
    manager.addAllowedDirectory(directory);
    manager.getDiskCache().setEnabled(false);
    manager.getSyncDiskCache().setEnabled(false);
    store = manager.stores().store(directory.resolve("master"));
    var source = ImportFixture.write(directory.resolve("input.wpilog"), 7);
    var imported = store.importPaths(new LogStore.Request(List.of(source), false, "RIO"), p -> {})
        .get(20, TimeUnit.SECONDS);
    assertEquals("imported", imported.files().get(0).status());
    store.migrateReplicas(store.replicaHeader().id()).get(20, TimeUnit.SECONDS);
    seed = store.replicaHeader();
    assertEquals(1, seed.replicas().files().size());
  }

  @AfterEach void close() {
    if (store != null) store.close();
    if (manager == null) return;
    manager.unloadAllLogs();
    manager.clearAllowedDirectories();
    allowed.forEach(manager::addAllowedDirectory);
    manager.getDiskCache().setEnabled(diskCacheEnabled);
    manager.getSyncDiskCache().setEnabled(syncCacheEnabled);
  }

  @Test void aFullCatalogRecordsBothCompletedCapturesAndTheirDurableSafeCauses() throws Exception {
    store.publicationClock(Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    assertEquals(4096, store.replicaHeader().replicas().files().size());
    var first = completeCapture(0, 23);
    var second = completeCapture(1, 24);
    assertNotEquals(first.getParent(), second.getParent(), "Two completed receipts are independent pending publications");
    for (var marker : List.of(first, second)) assertCapacityCause(marker);
    assertEquals(4096, store.replicaHeader().replicas().files().size());
    var snapshot = store.publicationStatus();
    assertEquals(seed.id(), snapshot.storeId());
    assertEquals(ReplicaPublications.State.READY, snapshot.state());
    assertEquals(2, snapshot.knownPendingCount());
    assertEquals(Integer.valueOf(2), snapshot.totalPendingCount());
    assertEquals("2026-10-20T12:00:00Z", snapshot.oldestFailedAt());
    assertTrue(snapshot.oldestTimeComplete());
    for (var entry : snapshot.entries()) {
      assertEquals("RESOURCE_LIMIT", entry.cause().code());
      assertEquals("Replica catalog metadata capacity is exhausted.", entry.cause().message());
      assertEquals("2026-10-20T12:00:00Z", entry.cause().firstFailedAt());
      assertEquals("2026-10-20T12:00:00Z", entry.cause().failedAt());
      assertTrue(entry.cause().durable());
    }
  }

  @Test void retriesPreserveFirstFailureThenClearMarkerAndOwnerTogether() throws Exception {
    var a = Instant.parse("2026-10-20T12:00:00Z");
    var b = a.plusSeconds(30);
    store.publicationClock(Clock.fixed(a, ZoneOffset.UTC));
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    var marker = completeCapture(0, 47);
    store.publicationClock(Clock.fixed(b, ZoneOffset.UTC));
    var failed = assertThrows(java.util.concurrent.ExecutionException.class,
        () -> store.recoverMasterPublication().get(20, TimeUnit.SECONDS));
    assertInstanceOf(PublicationPendingException.class, failed.getCause());
    var cause = store.publicationStatus().entries().get(0).cause();
    assertEquals(a.toString(), cause.firstFailedAt());
    assertEquals(b.toString(), cause.failedAt());
    assertEquals(a.toString(), json(marker).get("first_failed_at").getAsString());
    assertEquals(b.toString(), json(marker).get("failed_at").getAsString());
    store.captureAsync(io -> { io.writeDurable(store.root().resolve("store.json"), seed); return null; })
        .get(20, TimeUnit.SECONDS);
    store.recoverMasterPublication().get(20, TimeUnit.SECONDS);
    assertFalse(Files.exists(marker));
    assertEquals(ReplicaPublications.State.READY, store.publicationStatus().state());
    assertEquals(Integer.valueOf(0), store.publicationStatus().totalPendingCount());
    assertTrue(store.publicationStatus().entries().isEmpty());
    var published = store.replicaHeader().replicas().files();
    assertEquals(2, published.size());
    store.recoverMasterPublication().get(20, TimeUnit.SECONDS);
    assertEquals(published, store.replicaHeader().replicas().files(), "Retry must reuse immutable identities");
  }

  @Test void recoveryRecordsEveryFailureInsteadOfStoppingAtTheFirstFullCatalogReceipt() throws Exception {
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    var first = completeCapture(0, 31);
    var second = completeCapture(1, 32);
    // Recreate the exact previous-version record: a new failed attempt must add its own cause.
    for (var marker : List.of(first, second)) {
      var old = StoreFiles.JSON.fromJson(Files.readString(marker), PreviousPending.class);
      Files.writeString(marker, StoreFiles.JSON.toJson(old));
      assertFalse(json(marker).has("failure_code"));
    }
    assertThrows(Exception.class, () -> store.recoverMasterPublication().get(20, TimeUnit.SECONDS));
    for (var marker : List.of(first, second)) assertCapacityCause(marker);
    assertEquals(4096, store.replicaHeader().replicas().files().size());
  }

  @Test void previousMarkerParserIgnoresAdditiveDiagnosticFields() {
    var old = new PreviousPending("robots/RIO/sessions/s/session.json", "a".repeat(64),
        "session", "b".repeat(64));
    var annotated = StoreFiles.JSON.toJsonTree(old).getAsJsonObject();
    annotated.addProperty("failure_code", "RESOURCE_LIMIT");
    annotated.addProperty("failure_message", "Replica catalog metadata capacity is exhausted.");
    annotated.addProperty("failed_at", "2026-10-20T12:00:30Z");
    annotated.addProperty("first_failed_at", "2026-10-20T12:00:00Z");
    assertEquals(old, StoreFiles.JSON.fromJson(annotated, PreviousPending.class),
        "The previous four-field parser must accept a new marker without changing its receipt witnesses");
  }

  @Test void aWellFormedUncommittedIntentStillRollsBackWithoutPublishingIt() throws Exception {
    var sessionDirectory = Files.createDirectories(store.root().resolve("robots/RIO/sessions/uncommitted"));
    var receipt = sessionDirectory.resolve("session.json");
    var session = new Session("uncommitted", "2026-10-20T12:00:00Z", null,
        "captured", null, null, null, null, List.of());
    Files.writeString(receipt, StoreFiles.JSON.toJson(session));
    String committed = independentHash(receipt);
    var marker = sessionDirectory.resolve(ReplicaMasters.CAPTURE_PENDING);
    Files.writeString(marker, StoreFiles.JSON.toJson(new PreviousPending(
        StoreFiles.relative(store.root(), receipt), "c".repeat(64), "session", "d".repeat(64))));
    store.recoverMasterPublication().get(20, TimeUnit.SECONDS);
    assertFalse(Files.exists(marker));
    assertEquals(committed, independentHash(receipt));
    assertEquals(seed.replicas(), store.replicaHeader().replicas());
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void initializationCannotEraseALivePublicationAfterItsOffOwnerSelection(boolean previousStore) throws Exception {
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    var marker = completeCapture(0, 51);
    var full = store.replicaHeader();
    var receipt = marker.resolveSibling("session.json");
    var session = new StoreFiles(store.root(), manager.testGetSecurityValidator()).read(receipt, Session.class);
    var otherRoot = Files.createDirectories(directory.resolve("initializing"));
    try (var other = new LogStore(otherRoot, manager.testGetSecurityValidator(), manager, StoreCatalog::read)) {
      other.publicationsSettled().get(20, TimeUnit.SECONDS);
      if (previousStore) other.captureAsync(io -> {
        io.writeDurable(otherRoot.resolve("store.json"), new Header(1, "2026-10-20T00:00:00Z",
            java.util.UUID.randomUUID().toString(), List.of())); return null;
      }).get(20, TimeUnit.SECONDS);
      var selected = new CountDownLatch(1); var resume = new CountDownLatch(1);
      other.publicationStartProbe(identity -> { selected.countDown(); await(resume); });
      var recovery = other.recoverMasterPublication();
      assertTrue(selected.await(10, TimeUnit.SECONDS));
      try {
        var target = otherRoot.resolve("robots/RIO/sessions/live/session.json");
        Files.createDirectories(target.getParent());
        for (var file : session.files()) Files.copy(receipt.getParent().resolve(file.path()), target.getParent().resolve(file.path()));
        other.captureAsync(io -> { io.writeDurable(otherRoot.resolve("store.json"), full); return null; }).get(20, TimeUnit.SECONDS);
        other.recordingAsync(io -> { io.writeDurable(target, session); return null; }).get(20, TimeUnit.SECONDS);
        assertEquals(1, other.publicationStatus().knownPendingCount());
      } finally { resume.countDown(); }
      try { recovery.get(20, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException stale) { /* Refusal is allowed. */ }
      var result = other.publicationStatus();
      assertEquals(1, result.knownPendingCount(), "An off-owner initialization observation must not erase a newer live pending receipt");
      assertEquals(full.id(), result.storeId(), "An old or missing header cannot rewind the publication owner's identity");
    }
  }

  @Test void annotationFailureRetainsTheFirstInProcessFailureOnTheNextAttempt() throws Exception {
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    var a = Instant.parse("2026-10-20T12:00:00Z");
    store.publicationClock(Clock.fixed(a, ZoneOffset.UTC));
    var attempts = new AtomicInteger();
    store.publicationAnnotationProbe(marker -> {
      if (attempts.incrementAndGet() == 1) throw new java.io.IOException("Synthetic annotation failure");
    });
    var marker = completeCapture(0, 52);
    var first = store.publicationStatus().entries().get(0).cause();
    assertFalse(first.durable());
    assertEquals(a.toString(), first.firstFailedAt());
    assertEquals("CAUSE_NOT_RECORDED", json(marker).get("failure_code").getAsString(),
        "The original recovery witnesses remain intact after annotation failure");
    store.publicationClock(Clock.fixed(a.plusSeconds(30), ZoneOffset.UTC));
    assertThrows(java.util.concurrent.ExecutionException.class,
        () -> store.recoverMasterPublication().get(20, TimeUnit.SECONDS));
    var recovered = store.publicationStatus().entries().get(0).cause();
    assertEquals(a.toString(), recovered.firstFailedAt(),
        "An in-process retry must preserve the first failure even when its first annotation was not durable");
    assertEquals(a.plusSeconds(30).toString(), json(marker).get("failed_at").getAsString());
    assertEquals(a.toString(), json(marker).get("first_failed_at").getAsString());
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void failedHeaderInspectionCannotLeaveAnAuthoritativeTotal(boolean missing) throws Exception {
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    completeCapture(0, 53);
    var header = store.root().resolve("store.json");
    byte[] original = Files.readAllBytes(header);
    try {
      if (missing) Files.delete(header); else Files.writeString(header, "not a store header");
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> store.recoverMasterPublication().get(20, TimeUnit.SECONDS));
      var result = store.publicationStatus();
      assertEquals(ReplicaPublications.State.UNAVAILABLE, result.state(),
          "A failed header inspection must invalidate completeness without forgetting known pending receipts");
      assertEquals(1, result.knownPendingCount());
      assertNull(result.totalPendingCount());
      assertEquals("SOURCE_UNAVAILABLE", result.failureCode());
    } finally { Files.write(header, original); }
  }

  @Test void reopeningWithoutCaptureServiceRebuildsBothPendingReceiptsThenTheClearedState() throws Exception {
    var a = Instant.parse("2026-10-20T12:00:00Z");
    store.publicationClock(Clock.fixed(a, ZoneOffset.UTC));
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    completeCapture(0, 54); completeCapture(1, 55);
    var root = store.root(); store.close();
    try (var reopened = new LogStore(root, manager.testGetSecurityValidator(), manager, StoreCatalog::read)) {
      var failure = assertThrows(java.util.concurrent.ExecutionException.class,
          () -> reopened.publicationsSettled().get(20, TimeUnit.SECONDS));
      assertInstanceOf(PublicationPendingException.class, failure.getCause());
      assertEquals(2, reopened.publicationStatus().knownPendingCount());
      assertEquals(Integer.valueOf(2), reopened.publicationStatus().totalPendingCount());
      assertEquals(a.toString(), reopened.publicationStatus().oldestFailedAt());
      assertTrue(reopened.publicationStatus().entries().stream().allMatch(e -> e.cause().firstFailedAt().equals(a.toString())));
      reopened.captureAsync(io -> { io.writeDurable(root.resolve("store.json"), seed); return null; }).get(20, TimeUnit.SECONDS);
      reopened.recoverMasterPublication().get(20, TimeUnit.SECONDS);
      assertEquals(3, reopened.replicaHeader().replicas().files().size());
    }
    try (var reopened = new LogStore(root, manager.testGetSecurityValidator(), manager, StoreCatalog::read)) {
      reopened.publicationsSettled().get(20, TimeUnit.SECONDS);
      assertEquals(ReplicaPublications.State.READY, reopened.publicationStatus().state());
      assertEquals(Integer.valueOf(0), reopened.publicationStatus().totalPendingCount());
      assertEquals(3, reopened.replicaHeader().replicas().files().size());
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void anOlderSweepCannotOverwriteALaterLiveOutcome(boolean succeeded) throws Exception {
    var a = Instant.parse("2026-10-20T12:00:00Z"); var b = a.plusSeconds(30);
    store.publicationClock(Clock.fixed(a, ZoneOffset.UTC));
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    var marker = completeCapture(0, 56);
    var receipt = marker.resolveSibling("session.json");
    var session = new StoreFiles(store.root(), manager.testGetSecurityValidator()).read(receipt, Session.class);
    var inspected = new CountDownLatch(1); var resume = new CountDownLatch(1);
    store.publicationRecoveryProbe(path -> { inspected.countDown(); await(resume); });
    var recovery = store.recoverMasterPublication();
    assertTrue(inspected.await(10, TimeUnit.SECONDS));
    try {
      if (succeeded) store.captureAsync(io -> { io.writeDurable(store.root().resolve("store.json"), seed); return null; }).get(20, TimeUnit.SECONDS);
      store.publicationClock(Clock.fixed(b, ZoneOffset.UTC));
      store.recordingAsync(io -> { io.writeDurable(receipt, session); return null; }).get(20, TimeUnit.SECONDS);
    } finally { resume.countDown(); }
    if (succeeded) recovery.get(20, TimeUnit.SECONDS);
    else assertInstanceOf(PublicationPendingException.class, assertThrows(java.util.concurrent.ExecutionException.class,
        () -> recovery.get(20, TimeUnit.SECONDS)).getCause());
    var result = store.publicationStatus();
    assertEquals(ReplicaPublications.State.READY, result.state(), "A stale candidate must not invalidate a newer ordered outcome");
    assertEquals(Integer.valueOf(succeeded ? 0 : 1), result.totalPendingCount());
    assertEquals(!succeeded, Files.exists(marker));
    if (!succeeded) {
      assertEquals(a.toString(), result.entries().get(0).cause().firstFailedAt());
      assertEquals(b.toString(), result.entries().get(0).cause().failedAt());
    }
  }

  @Test void concurrentRefreshesShareOneFollowUpSweep() throws Exception {
    store.publicationsSettled().get(20, TimeUnit.SECONDS);
    var selected = new CountDownLatch(1); var resume = new CountDownLatch(1); var starts = new AtomicInteger();
    store.publicationStartProbe(identity -> { if (starts.incrementAndGet() == 1) { selected.countDown(); await(resume); } });
    long before = store.publicationWalks();
    var first = store.recoverMasterPublication();
    assertTrue(selected.await(10, TimeUnit.SECONDS));
    var second = store.recoverMasterPublication(); var third = store.recoverMasterPublication();
    assertSame(second, third, "Overlapping refreshes must share one bounded follow-up");
    resume.countDown(); first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
    assertEquals(2, starts.get());
    assertEquals(2, store.publicationWalks() - before);
  }

  @Test void anUnsafeMarkerMakesTheSweepIncompleteWithoutDiscardingOtherPendingReceipts() throws Exception {
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    completeCapture(0, 57);
    var broken = Files.createDirectories(store.root().resolve("robots/RIO/sessions/broken"))
        .resolve(ReplicaMasters.CAPTURE_PENDING);
    Files.writeString(broken, "{\"kind\":\"unsafe\"}");
    var failure = assertThrows(java.util.concurrent.ExecutionException.class,
        () -> store.recoverMasterPublication().get(20, TimeUnit.SECONDS));
    assertFalse(failure.getCause() instanceof PublicationPendingException);
    assertEquals(ReplicaPublications.State.UNAVAILABLE, store.publicationStatus().state());
    assertEquals(1, store.publicationStatus().knownPendingCount());
    assertNull(store.publicationStatus().totalPendingCount());
    assertTrue(Files.exists(broken));
  }

  @Test void aMissingReceiptFailureCannotBeMistakenForARemovedMarker() throws Exception {
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    var marker = completeCapture(0, 58);
    var receipt = marker.resolveSibling("session.json");
    store.publicationRecoveryProbe(path -> {
      Files.delete(receipt);
      Files.size(receipt); // The actual failed read is of the previously hash-proven receipt, not the marker.
    });
    var failure = assertThrows(java.util.concurrent.ExecutionException.class,
        () -> store.recoverMasterPublication().get(20, TimeUnit.SECONDS));
    assertFalse(failure.getCause() instanceof PublicationPendingException);
    assertTrue(Files.exists(marker));
    assertEquals(ReplicaPublications.State.UNAVAILABLE, store.publicationStatus().state());
    assertEquals(1, store.publicationStatus().knownPendingCount());
    assertNull(store.publicationStatus().totalPendingCount());
  }

  @Test void theActualRecoveryWalkerStopsAfterItsAdvertisedPathBound() throws Exception {
    store.awaitImports();
    store.publicationsSettled().get(20, TimeUnit.SECONDS);
    long baselinePaths;
    try (var paths = Files.walk(store.root())) { baselinePaths = paths.count(); }
    var unrelated = Files.createDirectory(store.root().resolve("walker-bound"));
    // Exactly 32,769 new paths: one directory and 32,768 empty synthetic files.
    for (int i = 0; i < 32768; i++) Files.createFile(unrelated.resolve("file-" + i));
    try (var paths = Files.walk(store.root())) {
      assertEquals(baselinePaths + 32769L, paths.count(), "The synthetic fixture's path count is independently known");
    }
    long before = store.publicationWalks();
    var failure = assertThrows(java.util.concurrent.ExecutionException.class,
        () -> store.recoverMasterPublication().get(30, TimeUnit.SECONDS));
    assertTrue(failure.getCause().getMessage().startsWith("RESOURCE_LIMIT:"));
    assertFalse(failure.getCause() instanceof PublicationPendingException);
    assertEquals(1, store.publicationWalks() - before);
    var result = store.publicationStatus();
    assertEquals(ReplicaPublications.State.UNAVAILABLE, result.state());
    assertEquals("RESOURCE_LIMIT", result.failureCode());
    assertEquals("Pending publication inventory exceeds its metadata bound.", result.failureMessage());
    assertEquals(0, result.knownPendingCount());
    assertNull(result.totalPendingCount(), "Exceeding the traversal bound cannot prove that no pending receipts exist");
  }

  private static void await(CountDownLatch latch) throws java.io.IOException {
    try { if (!latch.await(15, TimeUnit.SECONDS)) throw new java.io.IOException("Synthetic publication boundary timed out"); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException(interrupted); }
  }

  private Path completeCapture(int minute, long value) throws Exception {
    var clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z").plusSeconds(60L * minute), ZoneOffset.UTC);
    var placement = store.captures(clock);
    var loop = new ManualScheduler();
    var status = new AtomicReference<CaptureStore.Status>();
    placement.onStatus(status::set);
    Path payload;
    try (var writer = new CaptureWriter(clock, loop, CapturePolicy.ALL, placement)) {
      // Manual listener events only: this writer has no client or network connection.
      writer.connected(RobotAddress.uri("127.0.0.1", 5810, "synthetic"), "networktables.first.wpi.edu");
      writer.timeSync(10_000_000, 0);
      var topic = new ControlMessage.Announce("/Value", 1, "int", null, new JsonObject());
      writer.announce(topic);
      loop.until(writer::ready);
      writer.value(topic, new ValueFrame(1, 10_000_001, 2, value), 0);
      payload = writer.session().path();
    }
    assertDoesNotThrow(() -> placement.completion().get(20, TimeUnit.SECONDS),
        "A publication-capacity refusal must not fail an already-recorded capture close");
    store.awaitImports();
    assertNotNull(status.get());
    assertNotNull(status.get().replicaPublicationFailure());
    try (var read = manager.acquireReadOnly(payload.toString())) {
      var samples = read.log().values().get("NT:/Value");
      assertEquals(1, samples.size());
      assertEquals(value, samples.get(0).value());
    }
    var receipt = new StoreFiles(store.root(), manager.testGetSecurityValidator())
        .read(payload.resolveSibling("session.json"), Session.class);
    assertNull(receipt.openCapture());
    assertEquals(1, receipt.files().size());
    assertEquals(independentHash(payload), receipt.files().get(0).sha256());
    var marker = payload.resolveSibling(ReplicaMasters.CAPTURE_PENDING);
    assertTrue(Files.isRegularFile(marker));
    return marker;
  }

  private static void assertCapacityCause(Path marker) throws Exception {
    var value = json(marker);
    assertTrue(value.has("failure_code"), "A deferred publication must retain its cause on disk");
    assertEquals("RESOURCE_LIMIT", value.get("failure_code").getAsString());
    assertEquals("Replica catalog metadata capacity is exhausted.", value.get("failure_message").getAsString());
    assertTrue(value.has("failed_at") && !value.get("failed_at").isJsonNull());
    assertTrue(value.has("first_failed_at") && !value.get("first_failed_at").isJsonNull());
    var first = Instant.parse(value.get("first_failed_at").getAsString());
    var latest = Instant.parse(value.get("failed_at").getAsString());
    assertFalse(first.isAfter(latest));
  }

  private static JsonObject json(Path path) throws Exception {
    return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
  }

  private static String independentHash(Path path) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  /** Exact previous-version marker shape, used as a compatibility control rather than production reflection. */
  private record PreviousPending(String path, String sha256, String kind, String previousSha256) {}
}
