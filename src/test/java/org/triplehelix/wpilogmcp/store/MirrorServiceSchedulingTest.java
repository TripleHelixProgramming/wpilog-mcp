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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** A mirror's timer keeps synchronizing whatever one tick meets, and a finished job never refuses the next. */
class MirrorServiceSchedulingTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  Set<Path> previous;
  LogStore store;
  MirrorConfig policy;
  final String master = UUID.randomUUID().toString();

  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    Files.createDirectories(temp.resolve("mirror"));
    store = new LogStore(temp.resolve("mirror"), manager.testGetSecurityValidator(), manager, StoreCatalog::read);
    var header = new Header(2, "2026-10-19T00:00:00Z", UUID.randomUUID().toString(), List.of(), Map.of(), true, List.of(),
        new MirrorOrigin(master, "http://127.0.0.1:9", null, List.of(), Map.of()),
        new ReplicaCatalog(1, UUID.randomUUID().toString(), 1, List.of(), List.of(), List.of()));
    new StoreFiles(store.root(), manager.testGetSecurityValidator()).write(store.root().resolve("store.json"), header);
    policy = mirror(30);
  }
  @AfterEach void cleanup() {
    store.close(); manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
  MirrorConfig mirror(int intervalSec) {
    return new MirrorConfig("http://127.0.0.1:9", store.root(), 14, 10_000_000, List.of(), List.of(), intervalSec, 0, "all", 1_048_576);
  }
  static MirrorSync.Result synchronizedResult() {
    return new MirrorSync.Result("synchronized", null, 0, List.of(), List.of(), List.of(), "test synchronization");
  }

  /** Starts a transfer whose source blocks, so the mirror's transfer owner stays busy until released. */
  CompletableFuture<ReplicaTransfers.Receipt> holdTransferOwner(CountDownLatch entered, CountDownLatch release) throws Exception {
    byte[] bytes = Files.readAllBytes(ImportFixture.write(temp.resolve("held.wpilog"), 5));
    String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    var offer = new ReplicaTransfers.Offer(new ReplicaCatalog.Identity(master, UUID.randomUUID().toString(), UUID.randomUUID().toString()),
        "held.wpilog", hash, bytes.length, "wpilog", "completed_import", "peer-A", false);
    return store.replicaTransfers().transfer(policy, UUID.randomUUID().toString(), offer, offset -> new ByteArrayInputStream(bytes, (int) offset, bytes.length - (int) offset) {
      @Override public synchronized int read(byte[] buffer, int start, int count) {
        entered.countDown(); while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
        return super.read(buffer, start, count);
      }
    });
  }

  @Test void aTickThatFindsTheTransferOwnerBusyChangesNothingAndTheNextTickSynchronizes() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    try (var service = new MirrorService(store, policy)) {
      var held = holdTransferOwner(entered, release);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var refused = assertThrows(ExecutionException.class, () -> service.syncNow().get(5, TimeUnit.SECONDS));
      assertInstanceOf(ReplicaTransfers.NotStarted.class, refused.getCause(), refused.toString());
      assertEquals("waiting", service.status().state(), "a tick that started nothing leaves the status as it was");
      release.countDown(); held.get(10, TimeUnit.SECONDS);
      // Admitted again: the origin is unreachable, so this synchronization runs to an error.
      var next = service.syncNow();
      try { next.get(30, TimeUnit.SECONDS); } catch (ExecutionException ranToAnError) { /* it ran */ }
      assertNotEquals("waiting", service.status().state(), "the next tick was not admitted");
      assertNotEquals("synchronizing", service.status().state());
    } finally { release.countDown(); }
  }

  @Test void aSynchronizationThatThrowsKeepsTheTimerRunning() throws Exception {
    var calls = new AtomicInteger();
    try (var service = new MirrorService(store, mirror(1))) {
      service.synchronizer(progress -> {
        if (calls.incrementAndGet() == 1) throw new IllegalStateException("planted synchronous failure");
        return CompletableFuture.completedFuture(synchronizedResult());
      });
      service.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!"synchronized".equals(service.status().state()) && System.nanoTime() < deadline) Thread.sleep(20);
      assertEquals("synchronized", service.status().state(), "after a tick that threw, " + calls.get() + " tick(s) ran");
      assertTrue(calls.get() >= 2);
    }
  }

  @Test void aMaintenanceRequestSentTheInstantASyncCompletesIsAdmitted() throws Exception {
    try (var service = new MirrorService(store, policy)) {
      var finishSync = new CompletableFuture<MirrorSync.Result>();
      service.synchronizer(progress -> finishSync);
      var sync = service.syncNow();
      // Send a maintenance request the moment the completed state appears.
      var refusal = new CompletableFuture<Throwable>();
      var watcher = new Thread(() -> {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"synchronized".equals(service.status().state()) && System.nanoTime() < deadline) Thread.onSpinWait();
        try { service.pin(UUID.randomUUID().toString(), true).get(10, TimeUnit.SECONDS); refusal.complete(null); }
        catch (Exception e) { refusal.complete(e.getCause() == null ? e : e.getCause()); }
      }, "maintenance-watcher");
      watcher.start();
      finishSync.complete(synchronizedResult());
      sync.get(10, TimeUnit.SECONDS);
      var failure = refusal.get(20, TimeUnit.SECONDS);
      assertFalse(failure != null && String.valueOf(failure.getMessage()).contains("already running"),
          "a request sent once the sync reported completion was refused as if it still ran: " + failure);
    }
  }
}
