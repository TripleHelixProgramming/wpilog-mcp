/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** Explicit clocks and queue barriers prove ingestion without real logs, timers or sleeps. */
class LocalIngestionTest {
  @TempDir Path temp;
  Path source, destination, policyFile;
  SecurityValidator security;
  StoreRegistry registry;
  LocalIngestion service;
  final AtomicLong now = new AtomicLong();
  Set<Path> previous;

  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath();
    source = Files.createDirectory(temp.resolve("source"));
    destination = Files.createDirectory(temp.resolve("store"));
    policyFile = temp.resolve("state/policy.json");
    security = new SecurityValidator(); security.addAllowedDirectory(temp);
    previous = LogManager.getInstance().getAllowedDirectories();
    LogManager.getInstance().addAllowedDirectory(temp);
    registry = new StoreRegistry(security);
    service = new LocalIngestion(registry, security, policyFile);
    service.clock = now::get;
  }
  @AfterEach void cleanup() {
    service.close(); registry.close();
    LogManager.getInstance().clearAllowedDirectories();
    previous.forEach(LogManager.getInstance()::addAllowedDirectory);
  }
  private LocalIngestion.Policy policy(String mode, boolean handoff) {
    return new LocalIngestion.Policy(service.snapshot().policy().revision(), true,
        destination.toString(), null, List.of(new LocalIngestion.Source(source.toString(), mode, true, handoff, "practice")));
  }
  private void poll(long seconds) {
    now.set(Duration.ofSeconds(seconds).toNanos()); service.poll(now.get());
    try { service.currentWork().get(30, TimeUnit.SECONDS); }
    catch (Exception e) { throw new AssertionError(e); }
    registry.awaitImports();
  }
  private StoreCatalog.Snapshot catalog() throws Exception { return StoreCatalog.read(destination, security); }

  @Test void receiptPublicationDoesNotReadUnrelatedSessionManifestsOnTheOwner() throws Exception {
    service.close();
    registry.close();
    var watched = new java.util.concurrent.atomic.AtomicReference<Path>();
    var ownerReads = new AtomicInteger();
    security = new SecurityValidator() {
      @Override public void validate(Path path) throws IOException {
        super.validate(path);
        if (path.equals(watched.get()) && Thread.currentThread().getName().equals("store-owner"))
          ownerReads.incrementAndGet();
      }
    };
    security.addAllowedDirectory(temp);
    registry = new StoreRegistry(security);
    service = new LocalIngestion(registry, security, policyFile);
    service.clock = now::get;
    var unrelated = ImportFixture.write(temp.resolve("unrelated.wpilog"), 71,
        "SYNTHETIC-OTHER", 1_767_225_600_000_000L);
    registry.store(destination).importPaths(new LogStore.Request(List.of(unrelated), false, "other"), progress -> {})
        .get(10, TimeUnit.SECONDS);
    watched.set(catalog().files().get(0).manifestPath());
    var original = ImportFixture.write(source.resolve("original.wpilog"), 72,
        "SYNTHETIC-CURRENT", 1_767_312_000_000_000L);
    String expectedHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(original)));
    service.replace(policy("copy", false));
    poll(0);
    ownerReads.set(0);
    poll(3);
    assertEquals(0, ownerReads.get(), "A receipt turn must read only its outcome's owning manifest");
    var receipt = service.files(0, 100).files().get(0);
    assertEquals("imported", receipt.state());
    assertEquals(expectedHash, receipt.sha256());
    assertEquals(2, catalog().files().size());
  }

  @Test void settledCopiesKeepOriginalsAndUseExistingDuplicateDetection() throws Exception {
    var original = ImportFixture.write(source.resolve("original.wpilog"), 1);
    var duplicate = Files.copy(original, source.resolve("duplicate.wpilog"));
    byte[] bytes = Files.readAllBytes(original);
    service.replace(policy("copy", false));
    poll(0); poll(2);
    assertTrue(catalog().files().isEmpty(), "two nearby observations do not prove the quiet interval");
    poll(3);
    assertEquals(1, catalog().files().size(), "one content identity has one payload");
    assertArrayEquals(bytes, Files.readAllBytes(catalog().files().get(0).path()));
    assertTrue(Files.exists(original)); assertTrue(Files.exists(duplicate));
    assertEquals(Set.of("imported", "present"), service.files(0, 100).files().stream()
        .map(LocalIngestion.FileState::state).collect(java.util.stream.Collectors.toSet()));
    assertFalse(service.visible(original)); assertFalse(service.visible(duplicate));
    assertFalse(catalog().files().get(0).file().provenance().moved());
  }

  @Test void receiptsAreMetadataNotUnmanagedImports() throws Exception {
    ImportFixture.write(source.resolve("original.wpilog"), 1);
    service.replace(policy("copy", false)); poll(0); poll(3);
    assertTrue(catalog().unmanaged().isEmpty(), "local ingestion receipts must not appear as unmanaged source files");
  }

  @Test void restartRestoresPolicyAndSkipsUnchangedSuccessfulVersions() throws Exception {
    var original = ImportFixture.write(source.resolve("original.wpilog"), 1);
    service.replace(policy("copy", false)); poll(0); poll(3);
    var imports = new AtomicInteger();
    try (var observer = registry.store(destination).observe(new LogStore.Observer() {
      @Override public void manifest(Path path, Object value) {}
      @Override public void inventory(StoreCatalog.Snapshot snapshot) { imports.incrementAndGet(); }
    })) {
      service.close();
      service = new LocalIngestion(registry, security, policyFile); service.clock = now::get;
      assertTrue(service.enabled());
      for (int n = 1; n <= 10; n++) poll(30L * n);
      assertEquals(0, imports.get(), "idle and restarted ingestion must not rehash/reimport retained copies");
      assertFalse(service.visible(original));
      assertEquals("imported", service.files(0, 100).files().get(0).state());
    }
  }

  @Test void changedVersionWaitsAgainAndBothImmutableVersionsRemain() throws Exception {
    var file = ImportFixture.write(source.resolve("growing.wpilog"), 1);
    service.replace(policy("copy", false)); poll(0); poll(3);
    var first = catalog().files().get(0); byte[] old = Files.readAllBytes(first.path());
    ImportFixture.write(file, 2);
    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 1000));
    assertTrue(service.visible(file), "a current version must not be hidden by an old receipt");
    service.scanNow(); poll(4); poll(6);
    assertEquals(1, catalog().files().size());
    poll(7);
    assertEquals(2, catalog().files().size());
    assertArrayEquals(old, Files.readAllBytes(first.path()), "a previous imported version is immutable");
  }

  @Test void moveRequiresHandoffAndRetainsDuplicateSources() throws Exception {
    assertTrue(assertThrows(IOException.class, () -> service.replace(policy("move", false)))
        .getMessage().contains("completed-file handoff"));
    var first = ImportFixture.write(source.resolve("a.wpilog"), 1);
    var duplicate = Files.copy(first, source.resolve("b.wpilog"));
    service.replace(policy("move", true)); poll(0); poll(3);
    assertFalse(Files.exists(first)); assertTrue(Files.exists(duplicate));
    var held = catalog().files().get(0).file();
    assertTrue(held.provenance().moved()); assertEquals(first.toString(), held.provenance().originalPath());
    assertEquals("present", service.files(0, 100).files().stream()
        .filter(f -> f.path().equals(duplicate.toString())).findFirst().orElseThrow().state());
  }

  @Test void successfulMoveStaysImportedAfterDiscoveryUntilItsStoredCopyIsMissing() throws Exception {
    var original = ImportFixture.write(source.resolve("a.wpilog"), 1);
    var duplicate = Files.copy(original, source.resolve("b.wpilog"));
    service.replace(policy("move", true)); poll(0); poll(3);
    var stored = catalog().files().get(0).path();
    assertFalse(Files.exists(original)); assertTrue(Files.exists(duplicate));
    poll(30);
    assertEquals("imported", service.files(0, 100).files().stream()
        .filter(file -> file.path().equals(original.toString())).findFirst().orElseThrow().state(),
        "a completed Move must not become an unavailable-source warning during ordinary discovery");
    assertEquals(1, service.snapshot().sources().get(0).imported());
    assertEquals("present", service.files(0, 100).files().stream()
        .filter(file -> file.path().equals(duplicate.toString())).findFirst().orElseThrow().state());
    assertTrue(Files.exists(duplicate), "Move always preserves duplicate originals");
    Files.delete(stored);
    poll(60);
    assertEquals("unavailable", service.files(0, 100).files().stream()
        .filter(file -> file.path().equals(original.toString())).findFirst().orElseThrow().state(),
        "a successful Move receipt cannot hide a missing stored copy");
    assertEquals(0, service.snapshot().sources().get(0).imported());
  }

  @Test void versionChangedWhileQueuedIsDeferredWithoutAnImport() throws Exception {
    var file = ImportFixture.write(source.resolve("queued.wpilog"), 1);
    service.replace(policy("copy", false)); poll(0);
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var block = registry.store(destination).importPaths(new LogStore.Request(List.of(), false, null), progress -> {
      if (!progress.phase().equals("starting")) return;
      entered.countDown();
      try { assertTrue(release.await(30, TimeUnit.SECONDS)); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    });
    try {
      assertTrue(entered.await(30, TimeUnit.SECONDS));
      now.set(Duration.ofSeconds(3).toNanos()); service.poll(now.get());
      ImportFixture.write(file, 2);
      Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 1000));
      assertEquals(1, service.snapshot().active());
      service.poll(now.get());
      assertEquals(1, service.snapshot().active(), "one queued import per store");
    } finally { release.countDown(); }
    block.get(30, TimeUnit.SECONDS); service.currentWork().get(30, TimeUnit.SECONDS); registry.awaitImports();
    assertTrue(catalog().files().isEmpty());
    poll(6); assertEquals(1, catalog().files().size());
  }

  @Test void staleRevisionCannotOverwriteAnotherEditorAndPauseKeepsStoreReadable() throws Exception {
    var proposal = policy("copy", false);
    service.replace(proposal);
    assertThrows(LocalIngestion.ConflictException.class, () -> service.replace(proposal));
    security.clearAllowedDirectories();
    var current = service.snapshot().policy();
    service.replace(new LocalIngestion.Policy(current.revision(), false, current.store(), current.storeId(), current.sources()));
    assertFalse(service.enabled());
    assertTrue(security.getAllowedDirectories().contains(destination));
    assertFalse(security.getAllowedDirectories().contains(source));
  }

  @Test void policyIdentityDistinguishesConfigurationsAndNamesNotVersions() throws Exception {
    var a = Files.writeString(temp.resolve("a.yaml"), "fixture");
    var b = Files.writeString(temp.resolve("b.yaml"), "fixture");
    assertEquals(LocalIngestion.policyFileFor(temp, a, "http"), LocalIngestion.policyFileFor(temp, a, "http"));
    assertNotEquals(LocalIngestion.policyFileFor(temp, a, "http"), LocalIngestion.policyFileFor(temp, a, "other"));
    assertNotEquals(LocalIngestion.policyFileFor(temp, a, "http"), LocalIngestion.policyFileFor(temp, b, "http"));
  }

  @Test void reopeningThePolicyRestoresItsGrantsWithoutConfiguredParents() throws Exception {
    service.replace(policy("copy", false));
    security.clearAllowedDirectories();
    service.close();
    assertFalse(security.getAllowedDirectories().contains(source));
    service = new LocalIngestion(registry, security, policyFile); service.clock = now::get;
    assertTrue(security.getAllowedDirectories().contains(source));
    assertTrue(security.getAllowedDirectories().contains(destination));
    security.validate(source.resolve("new.wpilog"));
  }

  @Test void rejectOverlapsAndSkipNestedStoresAndLinks() throws Exception {
    var nested = Files.createDirectory(source.resolve("nested"));
    var overlap = new LocalIngestion.Policy(0, true, destination.toString(), null, List.of(
        new LocalIngestion.Source(source.toString(), "copy", true, false, null),
        new LocalIngestion.Source(nested.toString(), "copy", true, false, null)));
    assertEquals(1, service.replace(overlap).policy().sources().size(), "identical overlapping policies collapse to the parent");
    var conflict = new LocalIngestion.Policy(service.snapshot().policy().revision(), true, destination.toString(), null, List.of(
        new LocalIngestion.Source(source.toString(), "copy", true, false, null),
        new LocalIngestion.Source(nested.toString(), "move", true, true, null)));
    assertTrue(assertThrows(IOException.class, () -> service.replace(conflict)).getMessage().contains("conflicting"));
    registry.store(nested).importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
    ImportFixture.write(nested.resolve("inside-store.wpilog"), 1);
    var outside = ImportFixture.write(temp.resolve("outside.wpilog"), 2);
    try { Files.createSymbolicLink(source.resolve("linked.wpilog"), outside); }
    catch (IOException | UnsupportedOperationException e) { Assumptions.abort("Platform refuses symbolic links: " + e.getMessage()); }
    service.replace(policy("copy", false)); poll(0); poll(3);
    assertTrue(catalog().files().isEmpty());
  }

  @Test void boundedBatchesEventuallyDrainAndUnchangedRefusalsDoNotStorm() throws Exception {
    for (int n = 0; n <= LocalIngestion.BATCH_FILES; n++) ImportFixture.write(source.resolve(n + ".wpilog"), n);
    Files.writeString(source.resolve("invalid.revlog"), "not a log");
    service.replace(policy("copy", false)); poll(0); poll(3);
    assertEquals(LocalIngestion.BATCH_FILES, catalog().files().size());
    poll(6); assertEquals(LocalIngestion.BATCH_FILES + 1, catalog().files().size());
    assertEquals(1, service.snapshot().sources().get(0).refused());
    var imports = new AtomicInteger();
    try (var observer = registry.store(destination).observe(new LogStore.Observer() {
      @Override public void manifest(Path path, Object value) {}
      @Override public void inventory(StoreCatalog.Snapshot snapshot) { imports.incrementAndGet(); }
    })) {
      poll(30); poll(60); assertEquals(0, imports.get());
    }
  }

  @Test void missingDestinationMakesSourcesVisibleAndMissingRootIsReported() throws Exception {
    var original = ImportFixture.write(source.resolve("original.wpilog"), 1);
    service.replace(policy("copy", false)); poll(0); poll(3);
    Files.delete(catalog().files().get(0).path());
    assertTrue(service.visible(original));
    Files.move(source, temp.resolve("temporarily-unmounted"));
    service.scanNow(); poll(4);
    assertEquals("unavailable", service.snapshot().sources().get(0).state());
    assertTrue(service.snapshot().sources().get(0).reason().contains("unavailable"));
  }

  @Test void oversizedAndLinkedPolicyFilesAreRefused() throws Exception {
    service.replace(policy("copy", false)); service.close();
    Files.write(policyFile, new byte[LocalIngestion.MAX_POLICY_BYTES + 1], StandardOpenOption.TRUNCATE_EXISTING);
    assertTrue(assertThrows(IOException.class, () -> new LocalIngestion(registry, security, policyFile)).getMessage().contains("64 KiB"));
    var target = Files.move(policyFile, temp.resolve("external-policy"));
    try { Files.createSymbolicLink(policyFile, target); }
    catch (IOException | UnsupportedOperationException e) { Assumptions.abort("Platform refuses symbolic links: " + e.getMessage()); }
    assertTrue(assertThrows(IOException.class, () -> new LocalIngestion(registry, security, policyFile)).getMessage().contains("symbolic link"));
  }

  @Test void destinationWithinSourceIsExcludedAndAStoreCannotBecomeASource() throws Exception {
    destination = Files.createDirectory(source.resolve("organized"));
    ImportFixture.write(source.resolve("original.wpilog"), 1);
    service.replace(policy("copy", false)); poll(0); poll(3); poll(30); poll(33);
    assertEquals(1, catalog().files().size());
    assertEquals(1, service.files(0, 100).total(), "never recursively ingest the destination's own payloads");
    var other = Files.createDirectory(temp.resolve("other-store"));
    var proposed = new LocalIngestion.Policy(service.snapshot().policy().revision(), true, other.toString(), null,
        List.of(new LocalIngestion.Source(destination.toString(), "copy", true, false, null)));
    assertTrue(assertThrows(IOException.class, () -> service.replace(proposed)).getMessage().contains("another data store"));
  }

  @Test void corruptedReceiptIsRevalidatedInsteadOfStandingTheSourceDown() throws Exception {
    var original = ImportFixture.write(source.resolve("original.wpilog"), 1);
    service.replace(policy("copy", false)); poll(0); poll(3);
    Path receipt;
    try (var paths = Files.walk(destination.resolve(".local-ingestion"))) {
      receipt = paths.filter(p -> p.getFileName().toString().equals("current.json")).findFirst().orElseThrow();
    }
    Files.writeString(receipt, "{broken");
    service.close(); service = new LocalIngestion(registry, security, policyFile); service.clock = now::get;
    poll(30); assertTrue(service.visible(original));
    assertEquals("watching", service.snapshot().sources().get(0).state());
    poll(33); assertEquals("present", service.files(0, 100).files().get(0).state());
    assertEquals(1, catalog().files().size());
  }

  @Test void nullSourceReceiptIsRevalidatedInsteadOfBreakingDiscovery() throws Exception {
    var original = ImportFixture.write(source.resolve("original.wpilog"), 1);
    service.replace(policy("copy", false)); poll(0); poll(3);
    Path receipt;
    try (var paths = Files.walk(destination.resolve(".local-ingestion"))) {
      receipt = paths.filter(p -> p.getFileName().toString().equals("current.json")).findFirst().orElseThrow();
    }
    var json = com.google.gson.JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
    json.add("source", com.google.gson.JsonNull.INSTANCE); Files.writeString(receipt, json.toString());
    service.close(); service = new LocalIngestion(registry, security, policyFile); service.clock = now::get;
    poll(30);
    assertNull(service.snapshot().error(), "incomplete receipt metadata must trigger revalidation, not abort discovery");
    assertTrue(service.visible(original));
    assertEquals("waiting", service.files(0, 100).files().get(0).state());
    poll(33); assertEquals("present", service.files(0, 100).files().get(0).state());
  }

  @Test void explicitScanRetriesAnUnchangedRefusalButIdleAndRestartDoNot() throws Exception {
    var original = ImportFixture.write(source.resolve("original.wpilog"), 1);
    service.replace(policy("copy", false)); poll(0);
    try (var reservation = org.triplehelix.wpilogmcp.log.LogFileAccess.reserveMove(List.of(original))) {
      poll(3);
    }
    assertEquals("refused", service.files(0, 100).files().get(0).state());
    poll(30); assertTrue(catalog().files().isEmpty(), "automatic scans must not retry an unchanged refusal");
    service.close(); service = new LocalIngestion(registry, security, policyFile); service.clock = now::get;
    poll(60); assertEquals("refused", service.files(0, 100).files().get(0).state());
    service.scanNow(); poll(61);
    assertEquals("waiting", service.files(0, 100).files().get(0).state(), "an explicit scan retries the unchanged refused version");
    poll(63); assertTrue(catalog().files().isEmpty(), "an explicit retry must still establish the quiet interval");
    poll(64); assertEquals("imported", service.files(0, 100).files().get(0).state());
    assertEquals(1, catalog().files().size());
  }

  @Test void forgedReceiptCannotHideAnUnmanifestedDestination() throws Exception {
    var original = ImportFixture.write(source.resolve("original.wpilog"), 1);
    service.replace(policy("copy", false)); poll(0); poll(3);
    Path receipt;
    try (var paths = Files.walk(destination.resolve(".local-ingestion"))) {
      receipt = paths.filter(p -> p.getFileName().toString().equals("current.json")).findFirst().orElseThrow();
    }
    var stray = Files.copy(original, destination.resolve("stray.wpilog"));
    var json = com.google.gson.JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
    json.addProperty("destination", stray.toString()); Files.writeString(receipt, json.toString());
    service.close(); service = new LocalIngestion(registry, security, policyFile); service.clock = now::get;
    poll(30);
    assertTrue(service.visible(original), "a receipt is not authority to adopt an unmanifested destination");
    assertEquals("waiting", service.files(0, 100).files().get(0).state());
  }

  @Test void aPolicyChangeDuringDiscoveryCannotRetainTheOldSourceMode() throws Exception {
    var original = ImportFixture.write(source.resolve("original.wpilog"), 1);
    service.close(); registry.close();
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var armed = new java.util.concurrent.atomic.AtomicBoolean();
    security = new SecurityValidator() {
      @Override public void validate(Path path) throws IOException {
        super.validate(path);
        if (path.equals(source) && armed.compareAndSet(true, false)) {
          entered.countDown();
          try { assertTrue(release.await(30, TimeUnit.SECONDS)); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        }
      }
    };
    security.addAllowedDirectory(temp);
    registry = new StoreRegistry(security);
    service = new LocalIngestion(registry, security, policyFile); service.clock = now::get;
    service.replace(policy("copy", false));
    armed.set(true);
    var discovering = java.util.concurrent.CompletableFuture.runAsync(() -> service.poll(0));
    try {
      assertTrue(entered.await(30, TimeUnit.SECONDS));
      service.replace(policy("move", true));
    } finally { release.countDown(); }
    discovering.get(30, TimeUnit.SECONDS); service.currentWork().get(30, TimeUnit.SECONDS);
    poll(3); poll(6);
    assertFalse(Files.exists(original), "discovery from the old policy cannot leave its Copy source waiting forever under Move");
    assertEquals(1, catalog().files().size());
    assertTrue(catalog().files().get(0).file().provenance().moved());
  }

  @Test void registryShutdownRetainsIngestionGrantsUntilBlockedStagingUnwinds() throws Exception {
    var original = ImportFixture.write(source.resolve("blocked.wpilog"), 43);
    service.close(); registry.close();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var armed = new java.util.concurrent.atomic.AtomicBoolean();
    security = new SecurityValidator() {
      @Override public void validate(Path path) throws IOException {
        super.validate(path);
        if (!path.equals(original) || !Thread.currentThread().getName().equals("store-import-staging")
            || !armed.compareAndSet(true, false)) return;
        entered.countDown();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
          while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Fixture staging was not released");
            try {
              if (!release.await(remaining, TimeUnit.NANOSECONDS))
                throw new IOException("Fixture staging was not released");
              break;
            } catch (InterruptedException ignored) { interrupted = true; }
          }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
      }
    };
    security.addAllowedDirectory(temp);
    registry = new StoreRegistry(security);
    service = new LocalIngestion(registry, security, policyFile);
    service.clock = now::get;
    registry.setLocalIngestion(service);
    service.replace(policy("copy", false));
    poll(0);
    security.clearAllowedDirectories();
    armed.set(true);
    now.set(TimeUnit.SECONDS.toNanos(3));
    service.poll(now.get());
    var closer = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
      var thread = new Thread(task, "ingestion-shutdown-fixture");
      thread.setDaemon(true);
      return thread;
    });
    java.util.concurrent.CompletableFuture<Void> stopped = null;
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      stopped = java.util.concurrent.CompletableFuture.runAsync(registry::close, closer);
      try { stopped.get(1500, TimeUnit.MILLISECONDS); }
      catch (java.util.concurrent.TimeoutException timeout) {
        fail("Registry shutdown must not join ingestion's blocked staging operation");
      }
      assertFalse(service.currentWork().isDone(),
          "An admitted ingestion's lifetime must include its blocked staging unwind");
      assertTrue(security.getAllowedDirectories().contains(source),
          "Ingestion retains its source grant while the admitted worker still owns it");
    } finally {
      release.countDown();
      if (stopped != null) stopped.get(10, TimeUnit.SECONDS);
      closer.shutdownNow();
      try { service.currentWork().get(10, TimeUnit.SECONDS); }
      catch (java.util.concurrent.ExecutionException stoppedImport) { /* Shutdown refuses placement. */ }
    }
    assertFalse(security.getAllowedDirectories().contains(source),
        "The stopped ingestion releases its grant after actual worker completion");
    assertTrue(Files.exists(original), "Shutdown never consumes an unplaced source");
  }

  @Test void destinationIdentityIsRecheckedAfterQueueing() throws Exception {
    ImportFixture.write(source.resolve("queued.wpilog"), 1);
    service.replace(policy("copy", false)); poll(0);
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var blocker = registry.store(destination).importPaths(new LogStore.Request(List.of(), false, null), progress -> {
      if (!progress.phase().equals("starting")) return;
      entered.countDown();
      try { assertTrue(release.await(30, TimeUnit.SECONDS)); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    });
    try {
      assertTrue(entered.await(30, TimeUnit.SECONDS));
      now.set(Duration.ofSeconds(3).toNanos()); service.poll(now.get());
      var path = destination.resolve("store.json");
      var json = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
      json.addProperty("id", "replacement-store"); Files.writeString(path, json.toString());
    } finally { release.countDown(); }
    blocker.get(30, TimeUnit.SECONDS);
    var failure = assertThrows(java.util.concurrent.ExecutionException.class,
        () -> service.currentWork().get(30, TimeUnit.SECONDS));
    assertTrue(failure.getCause().getMessage().contains("identity changed while import was queued"));
    assertTrue(catalog().files().isEmpty());
  }
}
