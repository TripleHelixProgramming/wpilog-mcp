/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** Payload read/write observations pin admission to the real store adapters, not a mock copy. */
class StoreCopyAdmissionTest {
  @TempDir Path temp;
  final AtomicLong free = new AtomicLong(10_000_000_000L);
  final DiskSpaceBudgets space = new DiskSpaceBudgets(filesystem -> free.get());
  SecurityValidator security;
  StoreRegistry registry;
  LogStore store;
  java.util.Set<Path> allowed;

  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); security = new SecurityValidator(); security.addAllowedDirectory(temp);
    // An empty grant list admits every path, but a test that ran earlier in this JVM may have
    // granted its own folder, and an import here would then be refused when it releases a file.
    allowed = LogManager.getInstance().getAllowedDirectories(); LogManager.getInstance().addAllowedDirectory(temp);
    registry = new StoreRegistry(security, LogManager.getInstance(), StoreCatalog::discover, space);
    store = registry.store(temp.resolve("store"));
    store.capture(files -> null);
    store.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(100, 20, 30));
  }
  @AfterEach void close() {
    try { registry.close(); }
    finally { LogManager.getInstance().clearAllowedDirectories(); allowed.forEach(LogManager.getInstance()::addAllowedDirectory); }
  }
  static String id() { return UUID.randomUUID().toString(); }
  static String hash(byte[] bytes) throws Exception {
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }
  long reserved() { return space.snapshots().stream().mapToLong(DiskSpaceBudgets.Snapshot::reservedBytes).sum(); }

  @Test void twoConfiguredStoresShareTheActualFilesystemAndTheLargestStandingPolicy() throws Exception {
    var other = registry.store(temp.resolve("other"));
    other.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(200, 25, 40));
    assertEquals(Files.getFileStore(store.root()), Files.getFileStore(other.root()));
    assertEquals(store.diskBudget().snapshot().filesystemId(), other.diskBudget().snapshot().filesystemId());
    free.set(300);
    try (var held = store.reserveDiskCopy(store.root(), 80)) {
      var refusal = assertThrows(DiskSpaceBudgets.Refused.class, () -> other.reserveDiskCopy(other.root(), 21));
      assertEquals(300, refusal.snapshot().freeBytes());
      assertEquals(80, refusal.snapshot().reservedBytes());
      assertEquals(200, refusal.snapshot().captureReserveBytes());
      assertEquals(21, refusal.requestedBytes());
    }
    assertEquals(0, reserved());
  }

  @Test void uploadRefusesBeforeReadingTheFirstBodyByteOrCreatingAnOwnedTransfer() throws Exception {
    free.set(103); var reads = new AtomicInteger();
    var input = new InputStream() { @Override public int read() { reads.incrementAndGet(); return -1; } };
    var refusal = assertThrows(DiskSpaceBudgets.Refused.class,
        () -> store.receiveUpload("sample.wpilog", 4, hash(new byte[4]), input));
    assertEquals("CAPTURE_DISK_RESERVE", refusal.code());
    assertEquals(0, reads.get()); assertEquals(0, reserved());
    try (var files = Files.list(store.root().resolve("inbox"))) { assertEquals(0, files.count()); }
  }

  @Test void uploadOwnsItsReservationThroughTheImporterAndReleasesIdempotently() throws Exception {
    byte[] bytes = Files.readAllBytes(ImportFixture.write(temp.resolve("input.wpilog"), 31));
    free.set(bytes.length + 100L);
    var upload = store.receiveUpload("sample.wpilog", bytes.length, hash(bytes), new ByteArrayInputStream(bytes));
    assertEquals(bytes.length, reserved()); assertArrayEquals(bytes, Files.readAllBytes(upload.path()));
    // Import moves this owned upload; a same-filesystem rename requires no second payload allocation.
    var result = store.importUpload(upload, null, progress -> {}).get(10, TimeUnit.SECONDS);
    assertEquals(1, result.files().size()); assertNotEquals("refused", result.files().get(0).status());
    assertEquals(0, reserved()); upload.close(); assertEquals(0, reserved());
  }

  @Test void aTimedOutUploadResponseCannotReleaseBytesWhileTheBodyReadRemainsBlocked() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var unwound = new CountDownLatch(1);
    var input = new InputStream() {
      @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        entered.countDown();
        boolean interrupted = false;
        while (true) try { release.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
        throw new IOException("Synthetic body failure after the blocked read unwinds");
      }
      @Override public int read() { throw new AssertionError("Expected buffered body read"); }
    };
    var response = new CompletableFuture<Void>();
    var worker = new Thread(() -> {
      try { store.receiveUpload("sample.wpilog", 8, "0".repeat(64), input); response.complete(null); }
      catch (Exception failure) { response.completeExceptionally(failure); }
      finally { unwound.countDown(); }
    }, "synthetic-upload"); worker.setDaemon(true); worker.start();
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(8, reserved());
      response.cancel(true); worker.interrupt(); assertEquals(8, reserved());
    } finally { release.countDown(); }
    assertTrue(unwound.await(5, TimeUnit.SECONDS)); assertEquals(0, reserved());
  }

  @Test void copiedImportRefusesBeforeMoverCopyAndKeepsTheOriginalBytes() throws Exception {
    byte[] bytes = Files.readAllBytes(ImportFixture.write(temp.resolve("input.wpilog"), 17));
    var source = temp.resolve("input.wpilog"); var copies = new AtomicInteger();
    var mover = new LogStore.Mover() {
      @Override boolean sameFileStore(Path from, Path to) { return false; }
      @Override LogStore.CopyAttributes copy(Path from, Path to, long admittedBytes) throws IOException {
        copies.incrementAndGet(); return super.copy(from, to, admittedBytes);
      }
    };
    try (var selected = new LogStore(store.root(), security, LogManager.getInstance(), StoreCatalog::read,
        mover, store.diskBudget())) {
      free.set(bytes.length + 99L);
      var result = selected.importPaths(new LogStore.Request(List.of(source), true, null), progress -> {})
          .get(10, TimeUnit.SECONDS);
      assertEquals("refused", result.files().get(0).status());
      assertTrue(result.files().get(0).reason().contains("capture's reserve"), result.toString());
      assertEquals(0, copies.get()); assertArrayEquals(bytes, Files.readAllBytes(source)); assertEquals(0, reserved());
    }
  }

  @Test void legacyMigrationRefusesItsSecondAllocationAndPreservesTheExistingManifest() throws Exception {
    byte[] bytes = Files.readAllBytes(ImportFixture.write(temp.resolve("old.wpilog"), 29));
    String digest = hash(bytes);
    var bucket = Files.createDirectories(store.root().resolve("unassigned").resolve(digest.substring(0, 16)));
    var legacy = Files.move(temp.resolve("old.wpilog"), bucket.resolve("old.wpilog"));
    var io = new StoreFiles(store.root(), security);
    var provenance = new Provenance("imported", legacy.toString(), "old.wpilog", "2026-10-19T00:00:00Z", false);
    var original = new LogFile("old.wpilog", digest, bytes.length, "wpilog", provenance, true,
        0, 1, null, null, null, false, null);
    io.write(bucket.resolve("import.json"), original);
    free.set(bytes.length + 99L);
    var failure = assertThrows(java.util.concurrent.ExecutionException.class,
        () -> store.importPaths(new LogStore.Request(List.of(), false, null), progress -> {}).get(10, TimeUnit.SECONDS));
    assertInstanceOf(DiskSpaceBudgets.Refused.class, failure.getCause());
    assertArrayEquals(bytes, Files.readAllBytes(legacy));
    assertEquals(original, io.read(bucket.resolve("import.json"), LogFile.class));
    assertFalse(Files.exists(bucket.resolve("robot").resolve("old.wpilog"))); assertEquals(0, reserved());
  }

  record Mirror(LogStore store, MirrorConfig policy, String master) {}
  Mirror mirror() throws Exception {
    var selected = registry.store(temp.resolve("mirror"));
    selected.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(100, 20, 30));
    String master = id();
    new StoreFiles(selected.root(), security).write(selected.root().resolve("store.json"),
        new Header(2, "2026-10-19T00:00:00Z", id(), List.of(), Map.of(), true, List.of(),
            new MirrorOrigin(master, "http://127.0.0.1:9", null, List.of(), Map.of()),
            new ReplicaCatalog(1, id(), 1, List.of(), List.of(), List.of())));
    return new Mirror(selected, new MirrorConfig("http://127.0.0.1:9", selected.root(), 14, 10_000_000,
        List.of(), List.of(), 30, 0, "all", 1_048_576), master);
  }
  ReplicaTransfers.Offer offer(Mirror mirror, byte[] bytes) throws Exception {
    return new ReplicaTransfers.Offer(new ReplicaCatalog.Identity(mirror.master(), id(), id()), "original.wpilog",
        hash(bytes), bytes.length, "wpilog", "completed_import", "peer-A", false);
  }

  @Test void mirrorRefusesBeforeSourceOpenOrCreatingItsPayloadStage() throws Exception {
    var mirror = mirror(); byte[] bytes = Files.readAllBytes(ImportFixture.write(temp.resolve("input.wpilog"), 19));
    free.set(bytes.length + 99L); var opens = new AtomicInteger();
    var failure = assertThrows(java.util.concurrent.ExecutionException.class,
        () -> mirror.store().replicaTransfers().transfer(mirror.policy(), id(), offer(mirror, bytes), offset -> {
          opens.incrementAndGet(); return new ByteArrayInputStream(bytes);
        }).get(10, TimeUnit.SECONDS));
    assertInstanceOf(DiskSpaceBudgets.Refused.class, failure.getCause()); assertEquals(0, opens.get());
    assertEquals(0, reserved());
    try (var files = Files.walk(mirror.store().root())) {
      assertFalse(files.anyMatch(path -> path.toString().endsWith(".part")));
    }
  }

  @Test void expiredMirrorWorkerKeepsPhysicalAndLogicalChargesUntilTheOsReadUnwinds() throws Exception {
    var mirror = mirror(); byte[] bytes = Files.readAllBytes(ImportFixture.write(temp.resolve("input.wpilog"), 23));
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var future = mirror.store().replicaTransfers().transfer(mirror.policy(), id(), offer(mirror, bytes), offset -> new InputStream() {
      @Override public int read(byte[] target, int start, int length) throws IOException {
        entered.countDown();
        while (true) try { release.await(); break; } catch (InterruptedException ignored) { }
        throw new IOException("Synthetic blocked mirror read unwound");
      }
      @Override public int read() { throw new AssertionError("Expected buffered mirror read"); }
    });
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      // Idleness counts from the job's start, so a short timeout set earlier could expire a slow
      // runner's job during its setup, before it ever read; only the blocked read is meant to expire.
      mirror.store().replicaTransfers().idleTimeout(Duration.ofMillis(100));
      var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
      assertTrue(failure.getCause().getMessage().contains("TRANSFER_TIMEOUT"));
      assertEquals(bytes.length, reserved()); assertTrue(mirror.store().replicaTransfers().active());
      assertTrue(mirror.store().replicaTransfers().capacity().reservedBytes() >= bytes.length);
    } finally { release.countDown(); }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (mirror.store().replicaTransfers().active() && System.nanoTime() < deadline) Thread.sleep(5);
    assertFalse(mirror.store().replicaTransfers().active()); assertEquals(0, reserved());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aSourceGrowingAfterAdmissionCannotExceedItsCopyReservation(boolean move) throws Exception {
    var source = ImportFixture.write(temp.resolve("growing.wpilog"), 91);
    long admittedBytes = Files.size(source);
    var grew = new java.util.concurrent.atomic.AtomicBoolean();
    var copiedBytes = new AtomicLong(-1);
    var growingSpace = new DiskSpaceBudgets(filesystem -> {
      if (Thread.currentThread().getName().equals("store-import-staging") && grew.compareAndSet(false, true))
        Files.write(source, new byte[31], java.nio.file.StandardOpenOption.APPEND);
      return 10_000_000_000L;
    });
    try (var selectedRegistry = new StoreRegistry(security, LogManager.getInstance(), StoreCatalog::discover, growingSpace)) {
      var registered = selectedRegistry.store(temp.resolve("growing-store"));
      var mover = new LogStore.Mover() {
        @Override boolean sameFileStore(Path from, Path to) { return false; }
      };
      try (var selected = new LogStore(registered.root(), security, LogManager.getInstance(),
          StoreCatalog::read, mover, registered.diskBudget())) {
        var result = selected.importPaths(new LogStore.Request(List.of(source), move, null), progress -> {
          if (progress.phase().equals("verifying")) try { copiedBytes.set(Files.size(progress.path())); }
          catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }).get(10, TimeUnit.SECONDS);
        assertTrue(grew.get(), "The source grows inside physical admission, after its inspection snapshot");
        assertEquals(admittedBytes, copiedBytes.get(),
            "A stale-source refusal must not first write bytes beyond its physical reservation");
        assertEquals("refused", result.files().get(0).status());
        assertEquals(admittedBytes + 31, Files.size(source), "The externally changed original survives refusal");
      }
    }
  }

  @Test void blockedImportCopyKeepsItsReservationUntilItsWorkerUnwinds() throws Exception {
    var source = ImportFixture.write(temp.resolve("blocked.wpilog"), 92);
    byte[] original = Files.readAllBytes(source);
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var mover = new LogStore.Mover() {
      @Override boolean sameFileStore(Path from, Path to) { return false; }
      @Override LogStore.CopyAttributes copy(Path from, Path to, long admittedBytes) throws IOException {
        entered.countDown();
        boolean interrupted = false;
        while (true) try { release.await(); break; }
        catch (InterruptedException ignored) { interrupted = true; }
        try { return super.copy(from, to, admittedBytes); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
      }
    };
    try (var selected = new LogStore(store.root(), security, LogManager.getInstance(),
        StoreCatalog::read, mover, store.diskBudget())) {
      var imported = selected.importPaths(new LogStore.Request(List.of(source), true, null), progress -> {});
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(original.length, reserved());
        assertTimeoutPreemptively(Duration.ofMillis(1500), selected::close);
        assertFalse(imported.isDone(), "An active copy retains its upload/grant/space ownership while unwinding");
        assertEquals(original.length, reserved(), "A response or shutdown cannot release a still-copying worker's bytes");
      } finally { release.countDown(); }
      assertThrows(java.util.concurrent.ExecutionException.class, () -> imported.get(10, TimeUnit.SECONDS));
      assertEquals(0, reserved()); assertArrayEquals(original, Files.readAllBytes(source));
    }
  }
}
