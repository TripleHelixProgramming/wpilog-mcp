/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

/** An off-queue inventory read must not overwrite newer ordered capture publications. */
class StorePublicationOrderingTest {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
  @TempDir Path directory;

  @Test void aDelayedImportInventoryCannotReplaceANewerCaptureManifest() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var snapshotRead = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      var live = new LiveCapture(store.root(), new ManualScheduler());
      var armed = new AtomicBoolean();
      try (var registration = store.observe(new LogStore.Observer() {
        @Override public void manifest(Path path, Object value) { live.manifest(path, value); }
        @Override public void inventory(StoreCatalog.Snapshot snapshot) {
          if (armed.compareAndSet(true, false)) {
            snapshotRead.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Inventory release timed out");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
            }
          }
          live.inventory(snapshot);
        }
      })) {
        Path capture = store.captures(Clock.fixed(START, ZoneOffset.UTC)).create("synthetic-capture", START);
        Path manifest = capture.getParent().resolve("session.json");
        String sessionId = live.sessions().get(0).session().id();
        Path source = ImportFixture.write(directory.resolve("source.wpilog"), 23);
        armed.set(true);
        var importing = store.importPaths(new LogStore.Request(List.of(source), false, "OTHER_ROBOT"), ignored -> {});
        assertTrue(snapshotRead.await(10, TimeUnit.SECONDS), "The import must reach its inventory publication");
        var update = store.recordingAsync(io -> {
          var old = io.read(manifest, Session.class);
          io.write(manifest, new Session(old.id(), old.startedAt(), START.plusSeconds(2).toString(), old.startBasis(),
              old.event(), old.matchType(), old.matchNumber(), old.teamNumber(), old.files()));
          return null;
        });
        try {
          // The old implementation publishes the newer capture while the stale snapshot is held.
          // An ordered publisher may queue it until the inventory callback finishes instead.
          update.get(250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException orderedBehindInventory) {
          // Release below before waiting for the ordered capture turn.
        } finally { release.countDown(); }
        importing.get(10, TimeUnit.SECONDS);
        update.get(10, TimeUnit.SECONDS);
        var observed = live.sessions().stream().filter(view -> view.session().id().equals(sessionId))
            .findFirst().orElseThrow().session();
        assertEquals(START.plusSeconds(2).toString(), observed.endedAt(),
            "An import inventory must not erase the newer capture's independently specified +2 second update");
        assertEquals(START.plusSeconds(2).toString(), new StoreFiles(store.root(), security).read(manifest, Session.class).endedAt());
      } finally { release.countDown(); }
    }
  }

  @Test void captureTakingPriorityInvalidatesAnInventoryAlreadyWaitingToPublish() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var builder = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
      var thread = new Thread(task, "inventory-builder-fixture"); thread.setDaemon(true); return thread;
    });
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      var live = new LiveCapture(store.root(), new ManualScheduler());
      var inventoryOnOwner = new AtomicBoolean();
      var queueField = LogStore.class.getDeclaredField("queue"); queueField.setAccessible(true);
      var queue = (StoreQueue) queueField.get(store);
      try (var registration = store.observe(new LogStore.Observer() {
        @Override public void manifest(Path path, Object value) { live.manifest(path, value); }
        @Override public void inventory(StoreCatalog.Snapshot snapshot) {
          inventoryOnOwner.set(queue.ownsCurrentThread()); live.inventory(snapshot);
        }
      })) {
        Path capture = store.captures(Clock.fixed(START, ZoneOffset.UTC)).create("synthetic-capture", START);
        Path manifest = capture.getParent().resolve("session.json");
        String sessionId = live.sessions().get(0).session().id();
        queue.execute(() -> {
          entered.countDown();
          try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Owner release timed out"); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var publish = LogStore.class.getDeclaredMethod("publishInventory"); publish.setAccessible(true);
        var reading = builder.submit(() -> {
          try { publish.invoke(store); }
          catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
        });
        var imports = StoreQueue.class.getDeclaredField("imports"); imports.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean queued = false;
        while (!reading.isDone() && System.nanoTime() < deadline) {
          synchronized (queue) { queued = !((java.util.Deque<?>) imports.get(queue)).isEmpty(); }
          if (queued) break;
          Thread.sleep(1);
        }
        assertTrue(queued || reading.isDone(), "Inventory did not reach its publication boundary");
        var update = store.recordingAsync(io -> {
          var old = io.read(manifest, Session.class);
          io.write(manifest, new Session(old.id(), old.startedAt(), START.plusSeconds(2).toString(), old.startBasis(),
              old.event(), old.matchType(), old.matchNumber(), old.teamNumber(), old.files()));
          return null;
        });
        release.countDown(); update.get(10, TimeUnit.SECONDS); reading.get(10, TimeUnit.SECONDS);
        stores.awaitImports();
        assertTrue(inventoryOnOwner.get(), "An off-owner snapshot must publish in the store owner's order");
        var observed = live.sessions().stream().filter(view -> view.session().id().equals(sessionId)).findFirst().orElseThrow().session();
        assertEquals(START.plusSeconds(2).toString(), observed.endedAt(),
            "A higher-priority capture update must invalidate an older queued inventory snapshot");
      } finally { release.countDown(); }
    } finally { release.countDown(); builder.shutdownNow(); }
  }

}
