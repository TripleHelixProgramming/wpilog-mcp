/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** Staging facts cannot outlive their sources or the store which admitted the work. */
class ImportStagingHardeningTest {
  @TempDir Path directory;

  @Test void captureRunsBeforeImportTurnsAlreadyWaitingBehindTheCurrentTurn() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      // Submit owner turns directly so staging speed cannot accidentally prove lane priority.
      var field = LogStore.class.getDeclaredField("queue");
      field.setAccessible(true);
      var queue = (java.util.concurrent.ExecutorService) field.get(store);
      var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
      queue.execute(() -> {
        entered.countDown();
        try { release.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      });
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      for (int i = 0; i < 3; i++) {
        String name = "import-" + i;
        queue.execute(() -> order.add(name));
      }
      var capture = store.recordingAsync(io -> { order.add("capture"); return null; });
      release.countDown();
      capture.get(10, TimeUnit.SECONDS);
      store.awaitImports();
      assertEquals(List.of("capture", "import-0", "import-1", "import-2"), order,
          "Capture must take the next turn without changing order within the import lane");
    } finally { release.countDown(); }
  }

  @Test void aSourceChangedAfterItsCopyIsVerifiedIsNotPublishedFromStaleFacts() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      var source = ImportFixture.write(directory.resolve("source.wpilog"), 29);
      var result = store.importPaths(new LogStore.Request(List.of(source), false, "SYNTHETIC"), progress -> {
        if (progress.phase().equals("verifying")) {
          try { Files.write(source, new byte[] {0}, StandardOpenOption.APPEND); }
          catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
      }).get(10, TimeUnit.SECONDS);
      assertEquals("refused", result.files().get(0).status(),
          "Publication must recheck the original after copy verification, before trusting staged facts");
      assertTrue(result.files().get(0).reason().contains("changed"), result.toString());
      assertTrue(StoreCatalog.read(store.root(), security).files().isEmpty(),
          "A changed source must not gain a manifest receipt from its old inspection");
      assertTrue(Files.exists(source), "Refusal never removes the caller's source");
    }
  }

  @Test void shutdownStopsStagingWithoutWaitingForTheBlockedImport() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    var stores = new StoreRegistry(security);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closer = Executors.newSingleThreadExecutor(task -> {
      var thread = new Thread(task, "import-shutdown-fixture");
      thread.setDaemon(true);
      return thread;
    });
    CompletableFuture<Void> closed = null;
    try {
      var store = stores.store(directory.resolve("store"));
      var source = ImportFixture.write(directory.resolve("source.wpilog"), 31);
      store.importPaths(new LogStore.Request(List.of(source), false, "SYNTHETIC"), progress -> {
        if (!progress.phase().equals("inspecting")) return;
        entered.countDown();
        try { release.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      });
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      closed = CompletableFuture.runAsync(stores::close, closer);
      var shutdown = closed;
      assertTimeoutPreemptively(Duration.ofSeconds(2),
          () -> shutdown.get(1500, TimeUnit.MILLISECONDS),
          "Shutdown must stop staging without joining a blocked payload inspection");
      assertTrue(Files.exists(source), "An unplaced source survives bounded shutdown");
    } finally {
      release.countDown();
      if (closed != null) closed.get(10, TimeUnit.SECONDS);
      else stores.close();
      closer.shutdownNow();
    }
  }

  @Test void aPublicationTurnKeepsItsStagedBytesUntilItUnwindsAfterShutdown() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    var stores = new StoreRegistry(security);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var staged = new java.util.concurrent.atomic.AtomicReference<Path>();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try {
      var store = stores.store(directory.resolve("store"));
      var source = ImportFixture.write(directory.resolve("source.wpilog"), 41);
      store.importPrepared(new LogStore.Request(List.of(source), false, "SYNTHETIC"), progress -> {
        if (progress.phase().equals("verifying")) staged.set(progress.path());
      }, request -> {
        if (calls.incrementAndGet() > 1) {
          entered.countDown();
          try { release.await(10, TimeUnit.SECONDS); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
          finally { exited.countDown(); }
        }
        return request;
      }, result -> {});
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      stores.close();
      assertNotNull(staged.get());
      assertTrue(Files.exists(staged.get()),
          "An interrupted staging wait must not delete bytes still owned by a running publication turn");
      release.countDown();
      assertTrue(exited.await(10, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (Files.exists(staged.get()) && System.nanoTime() < deadline) Thread.sleep(5);
      assertFalse(Files.exists(staged.get()), "Unplaced staged bytes are removed after their owner unwinds");
      assertTrue(Files.exists(source), "Shutdown never cleans up the original");
      assertTrue(StoreCatalog.read(store.root(), security).files().isEmpty(),
          "A stopped import cannot publish after its preparation callback returns");
    } finally {
      release.countDown();
      stores.close();
    }
  }

  @Test void nextImportRemovesAbandonedStagesButPreservesLiveOwnersAndUnmanagedFiles() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      store.importPaths(new LogStore.Request(List.of(), false, null), progress -> {}).get(10, TimeUnit.SECONDS);
      var parent = Files.createDirectories(store.root().resolve("unassigned").resolve("synthetic").resolve("robot"));
      var abandoned = Files.createDirectory(parent.resolve(".import-staging-abandoned"));
      Files.writeString(abandoned.resolve(".owner.lock"), "wpilog-import-stage-v1");
      Files.writeString(abandoned.resolve("partial.wpilog"), "synthetic partial bytes");
      var unmanaged = Files.createDirectory(parent.resolve(".import-staging-unmanaged"));
      var unmanagedFile = Files.writeString(unmanaged.resolve("keep.wpilog"), "not owned by import staging");
      var live = Files.createDirectory(parent.resolve(".import-staging-live"));
      var marker = Files.writeString(live.resolve(".owner.lock"), "wpilog-import-stage-v1");
      var liveFile = Files.writeString(live.resolve("live.wpilog"), "still owned");
      try (var channel = java.nio.channels.FileChannel.open(marker, StandardOpenOption.WRITE);
           var held = channel.lock()) {
        store.importPaths(new LogStore.Request(List.of(), false, null), progress -> {}).get(10, TimeUnit.SECONDS);
        assertFalse(Files.exists(abandoned), "A subsequent import removes only proven abandoned staging");
        assertEquals("still owned", Files.readString(liveFile));
        assertEquals("not owned by import staging", Files.readString(unmanagedFile));
      }
    }
  }

  @Test void shutdownRetainsAnUploadUntilItsStagingWorkerUnwinds() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    var stores = new StoreRegistry(security);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    CompletableFuture<LogStore.Result> imported = null;
    try {
      var store = stores.store(directory.resolve("store"));
      store.importPaths(new LogStore.Request(List.of(), false, null), progress -> {}).get(10, TimeUnit.SECONDS);
      var original = ImportFixture.write(directory.resolve("source.wpilog"), 51);
      byte[] bytes = Files.readAllBytes(original);
      String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
      var upload = store.receiveUpload("source.wpilog", bytes.length, hash, new java.io.ByteArrayInputStream(bytes));
      imported = store.importUpload(upload, "SYNTHETIC", progress -> {
        if (!progress.phase().equals("inspecting")) return;
        entered.countDown();
        boolean interrupted = false;
        while (release.getCount() > 0) {
          try { release.await(10, TimeUnit.SECONDS); }
          catch (InterruptedException stop) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
      });
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      stores.close();
      assertFalse(imported.isDone(), "An active worker's completion must retain its upload and grants");
      assertTrue(Files.exists(upload.path()), "The upload belongs to the still-running staging read");
      release.countDown();
      var completed = imported;
      assertThrows(java.util.concurrent.ExecutionException.class, () -> completed.get(10, TimeUnit.SECONDS));
      assertFalse(Files.exists(upload.path()), "Upload cleanup follows worker completion");
      assertArrayEquals(bytes, Files.readAllBytes(original));
    } finally {
      release.countDown();
      stores.close();
      if (imported != null) try { imported.get(10, TimeUnit.SECONDS); }
      catch (java.util.concurrent.ExecutionException expected) { /* Shutdown refuses unfinished work. */ }
    }
  }

  @Test void recoveryRechecksRetirementAfterAcquiringTheStageLock() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    // Isolate the recovery seam: constructing a store would launch another recovery worker
    // which could legitimately collect this abandoned fixture before the injected retirement.
    var root = Files.createDirectories(directory.resolve("store"));
    var stage = Files.createDirectories(root.resolve("unassigned").resolve("synthetic")
        .resolve("robot").resolve(".import-staging-retiring"));
    Files.writeString(stage.resolve(".owner.lock"), "wpilog-import-stage-v1");
    var retained = Files.writeString(stage.resolve("invalid.wpilog"), "retained failed ordinary copy");
    ImportStage.recover(new StoreFiles(root, security),
        marker -> Files.writeString(marker, "retired"));
    assertTrue(Files.exists(retained), "Retiring a stage between discovery and lock acquisition preserves its unmanaged copy");
  }

  @Test void reopeningAStoreRecoversAbandonedStagesWithoutSubmittingAnImport() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    Path root;
    try (var first = new StoreRegistry(security)) {
      var store = first.store(directory.resolve("store"));
      store.importPaths(new LogStore.Request(List.of(), false, null), progress -> {}).get(10, TimeUnit.SECONDS);
      root = store.root();
    }
    var stage = Files.createDirectories(root.resolve("unassigned").resolve("synthetic")
        .resolve("robot").resolve(".import-staging-abandoned"));
    Files.writeString(stage.resolve(".owner.lock"), "wpilog-import-stage-v1");
    Files.writeString(stage.resolve("partial.wpilog"), "synthetic interrupted copy");
    try (var reopened = new StoreRegistry(security)) {
      var store = reopened.store(root);
      store.awaitImports();
      assertFalse(Files.exists(stage), "Store startup cleans an abandoned stage without another import request");
      assertEquals(0, store.queueMetrics().overBudgetTurns(), "Recovery does not consume a store-owner turn");
    }
  }

  @Test void anUnrelatedCaptureUpdateDoesNotInvalidateVerifiedImportBytes() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    var start = java.time.Instant.parse("2026-01-01T00:00:00Z");
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      var recording = store.captures(java.time.Clock.fixed(start, java.time.ZoneOffset.UTC))
          .create("SYNTHETIC-CAPTURE", start);
      var captureManifest = recording.getParent().resolve("session.json");
      var source = ImportFixture.write(directory.resolve("source.wpilog"), 61);
      byte[] expected = Files.readAllBytes(source);
      var changed = new java.util.concurrent.atomic.AtomicBoolean();
      var result = store.importPaths(new LogStore.Request(List.of(source), false, "OTHER-ROBOT"), progress -> {
        if (!progress.phase().equals("verifying") || !changed.compareAndSet(false, true)) return;
        try {
          store.recordingAsync(io -> {
            var old = io.read(captureManifest, StoreManifest.Session.class);
            io.write(captureManifest, new StoreManifest.Session(old.id(), old.startedAt(),
                start.plusSeconds(2).toString(), old.startBasis(), old.event(), old.matchType(),
                old.matchNumber(), old.teamNumber(), old.files()));
            return null;
          }).get(5, TimeUnit.SECONDS);
        } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
      }).get(10, TimeUnit.SECONDS);
      assertEquals("imported", result.files().get(0).status(),
          "Unrelated capture metadata must not invalidate a verified import");
      assertArrayEquals(expected, Files.readAllBytes(result.files().get(0).path()));
      assertEquals(start.plusSeconds(2).toString(),
          new StoreFiles(store.root(), security).read(captureManifest, StoreManifest.Session.class).endedAt());
    }
  }

  @Test void aStageBecomesRecoverableOnlyAfterItsCreatorOwnsTheMarkerLock() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      var io = new StoreFiles(store.root(), security);
      var parent = Files.createDirectories(store.root().resolve("unassigned").resolve("synthetic").resolve("robot"));
      try (var stage = ImportStage.create(io, parent, marker -> {
        assertEquals(0, Files.size(marker), "Recovery must not recognize a stage before its creator holds the lock");
        ImportStage.recover(io);
        assertTrue(Files.exists(marker), "A concurrent recovery cannot steal a stage during initialization");
      })) {
        var staged = stage.path(Path.of("partial.wpilog"));
        Files.writeString(staged, "synthetic staged bytes");
        ImportStage.recover(io);
        assertEquals("synthetic staged bytes", Files.readString(staged));
        Files.delete(staged);
      }
    }
  }

  @Test void aCaptureTurnBetweenRefreshAndPublicationRetriesOnlyMetadataPreparation() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    var start = java.time.Instant.parse("2026-01-01T00:00:00Z");
    var ownerEntered = new CountDownLatch(1);
    var releaseOwner = new CountDownLatch(1);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      var recording = store.captures(java.time.Clock.fixed(start, java.time.ZoneOffset.UTC))
          .create("SYNTHETIC-CAPTURE", start);
      var manifest = recording.getParent().resolve("session.json");
      var source = ImportFixture.write(directory.resolve("source.wpilog"), 71);
      byte[] expected = Files.readAllBytes(source);
      var queueField = LogStore.class.getDeclaredField("queue");
      queueField.setAccessible(true);
      var queue = (StoreQueue) queueField.get(store);
      var importsField = StoreQueue.class.getDeclaredField("imports");
      importsField.setAccessible(true);
      var queuedImports = (java.util.ArrayDeque<?>) importsField.get(queue);
      var queuedCapture = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
      var inspections = new java.util.concurrent.atomic.AtomicInteger();
      var verifications = new java.util.concurrent.atomic.AtomicInteger();
      var imported = store.importPaths(new LogStore.Request(List.of(source), false, "OTHER-ROBOT"), progress -> {
        if (progress.phase().equals("inspecting")) inspections.incrementAndGet();
        if (!progress.phase().equals("verifying") || verifications.incrementAndGet() != 1) return;
        queue.execute(() -> {
          ownerEntered.countDown();
          try { releaseOwner.await(10, TimeUnit.SECONDS); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
          assertTrue(ownerEntered.await(10, TimeUnit.SECONDS));
          queuedCapture.set(store.recordingAsync(io -> {
            var old = io.read(manifest, StoreManifest.Session.class);
            io.write(manifest, new StoreManifest.Session(old.id(), old.startedAt(),
                start.plusSeconds(3).toString(), old.startBasis(), old.event(), old.matchType(),
                old.matchNumber(), old.teamNumber(), old.files()));
            return null;
          }));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new java.util.concurrent.CompletionException(interrupted);
        }
      });
      assertTrue(ownerEntered.await(10, TimeUnit.SECONDS));
      // Observe admission under the queue's own monitor. The capture is deliberately ahead
      // of the pending publication, after that publication's off-owner metadata refresh.
      boolean publicationQueued = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!publicationQueued && System.nanoTime() < deadline) {
        synchronized (queue) { publicationQueued = !queuedImports.isEmpty(); }
        if (!publicationQueued) Thread.sleep(1);
      }
      assertTrue(publicationQueued, "The final publication turn must be queued behind the held owner");
      releaseOwner.countDown();
      var result = imported.get(10, TimeUnit.SECONDS);
      queuedCapture.get().get(10, TimeUnit.SECONDS);
      assertEquals("imported", result.files().get(0).status(),
          "An unrelated capture winning the next turn requires metadata refresh, not import refusal");
      assertArrayEquals(expected, Files.readAllBytes(result.files().get(0).path()));
      assertEquals(1, inspections.get(), "A metadata race does not repeat payload inspection");
      assertEquals(1, verifications.get(), "A metadata race reuses the verified staged copy");
      assertEquals(start.plusSeconds(3).toString(),
          new StoreFiles(store.root(), security).read(manifest, StoreManifest.Session.class).endedAt());
    } finally { releaseOwner.countDown(); }
  }

  @Test void aNewEligibleSessionAfterCopyCannotReuseAnEarlierPlacementDecision() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      var seed = ImportFixture.write(directory.resolve("seed.wpilog"), 81, "PROOF-ROBOT", 1_767_225_600_000_000L);
      assertEquals("imported", store.importPaths(new LogStore.Request(List.of(seed), false, null), progress -> {})
          .get(10, TimeUnit.SECONDS).files().get(0).status());
      var first = StoreCatalog.readManaged(store.root(), security).sessions().get(0);
      var source = ImportFixture.write(directory.resolve("source.wpilog"), 82, "PROOF-ROBOT", 1_767_225_600_000_000L);
      var result = store.importPaths(new LogStore.Request(List.of(source), false, null), progress -> {
        if (!progress.phase().equals("verifying")) return;
        try {
          store.recordingAsync(io -> {
            var previous = first.session();
            var other = first.path().resolveSibling("second").resolve("session.json");
            io.write(other, new StoreManifest.Session(java.util.UUID.randomUUID().toString(),
                previous.startedAt(), previous.endedAt(), previous.startBasis(), previous.event(),
                previous.matchType(), previous.matchNumber(), previous.teamNumber(), List.of()));
            return null;
          }).get(5, TimeUnit.SECONDS);
        } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
      }).get(10, TimeUnit.SECONDS);
      assertEquals("refused", result.files().get(0).status(),
          "A newly eligible session invalidates the earlier placement decision even when source bytes are unchanged");
      assertTrue(result.files().get(0).reason().contains("candidates"), result.toString());
      assertEquals(1, StoreCatalog.readManaged(store.root(), security).files().size(),
          "The unproven source never receives a manifest receipt");
      assertTrue(Files.exists(source), "Changed candidate facts never consume the caller's source");
    }
  }

  @Test void aRobotNameBeginningWithTheStagingPrefixStillHasRecheckedMetadata() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    String robot = ".import-staging-synthetic";
    var pairDirectory = Files.createDirectory(directory.resolve("pair"));
    var wpilog = ImportFixture.sameClockRevPair(pairDirectory, "capture.wpilog");
    Path rev;
    try (var paths = Files.list(pairDirectory)) {
      rev = paths.filter(path -> path.toString().endsWith(".revlog")).findFirst().orElseThrow();
    }
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      assertEquals("imported", store.importPaths(new LogStore.Request(List.of(wpilog), false, robot), progress -> {})
          .get(10, TimeUnit.SECONDS).files().get(0).status());
      var identity = store.root().resolve("robots").resolve(robot).resolve("robot.json");
      var result = store.importPaths(new LogStore.Request(List.of(rev), false, robot), progress -> {
        if (!progress.phase().equals("pairing")) return;
        try {
          var json = com.google.gson.JsonParser.parseString(Files.readString(identity)).getAsJsonObject();
          json.addProperty("serial_number", "CHANGED-ROBOT");
          // Replace content in place so only this manifest's own stamp detects the change.
          Files.writeString(identity, json.toString());
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
      }).get(10, TimeUnit.SECONDS);
      assertEquals("refused", result.files().get(0).status(),
          "A portable robot name must not bypass identity rechecks by resembling a staging directory");
      assertTrue(result.files().get(0).reason().contains("candidates"), result.toString());
      assertEquals(1, StoreCatalog.readManaged(store.root(), security).files().size());
      assertTrue(Files.exists(rev));
    }
  }
}
