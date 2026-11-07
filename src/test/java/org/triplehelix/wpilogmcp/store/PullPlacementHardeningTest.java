/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeDouble;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.capture.pull.FakeRobot;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.sync.PullManifest;

/** A filesystem seam models the provider's hidden copy without requiring another mounted disk. */
class PullPlacementHardeningTest {
  @TempDir Path temporary;
  private static final Clock WALL = Clock.fixed(Instant.parse("2026-03-07T14:22:33Z"), ZoneOffset.UTC);
  private static final String SERIAL = "SYNTHETIC-PULL-PLACEMENT";
  private static final long RESERVE = 1_000;

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void crossFilesystemCopyRefusesBeforeItsFirstByte(boolean verified) throws Exception {
    try (var rig = new Rig()) {
      var entry = rig.staged();
      rig.free.set(RESERVE + rig.bytes.length - 1);
      var refused = assertThrows(IOException.class, () -> rig.place(entry, verified));
      assertTrue(refused.getMessage().contains("free_bytes=" + rig.free.get()), refused::getMessage);
      assertTrue(refused.getMessage().contains("reserved_bytes=0"), refused::getMessage);
      assertTrue(refused.getMessage().contains("capture_reserve_bytes=1000"), refused::getMessage);
      assertTrue(refused.getMessage().contains("requested_bytes=" + rig.bytes.length), refused::getMessage);
      assertEquals(0, rig.mover.copies, "A refused placement cannot read or write a payload byte");
      assertArrayEquals(rig.bytes, Files.readAllBytes(rig.root.resolve(entry.localName())));
      assertEquals(0, rig.store.diskBudget().snapshot().reservedBytes());
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void blockedCrossFilesystemCopyLeavesCaptureAdmissionAndItsSourceAvailable(boolean verified) throws Exception {
    try (var rig = new Rig()) {
      var entry = rig.staged();
      var source = rig.root.resolve(entry.localName());
      var copying = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      rig.mover.beforeCopy = (from, to) -> {
        copying.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Fixture copy was not released");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
      };
      rig.mover.beforeDelete = from -> {
        assertNotEquals("store-owner", Thread.currentThread().getName());
        var io = new StoreFiles(rig.root, rig.security);
        assertTrue(io.read(rig.root.resolve("store.json"), StoreManifest.Header.class).moves().stream()
            .anyMatch(move -> Path.of(move.originalPath()).equals(source)),
            "The original may be deleted only after its committed alias exists");
        if (verified) assertEquals(1, StoreCatalog.readManaged(rig.root, rig.security).files().size(),
            "The verified destination must be committed before deleting the original");
      };
      var worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "pull-placement-test"); thread.setDaemon(true); return thread;
      });
      try {
        var placement = worker.submit(() -> rig.place(entry, verified));
        assertTrue(copying.await(5, TimeUnit.SECONDS), "The synthetic cross-filesystem copy did not start");
        assertArrayEquals(rig.bytes, Files.readAllBytes(source));
        assertEquals(rig.bytes.length, rig.store.diskBudget().snapshot().reservedBytes(),
            "The complete destination allocation remains reserved during blocked copy I/O");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        var capture = rig.store.recordingAsync(io -> "admitted", deadline);
        assertEquals("admitted", capture.get(200, TimeUnit.MILLISECONDS),
            "Capture admission cannot wait behind a cross-filesystem copy");
        release.countDown();
        var destination = rig.root.resolve(placement.get(5, TimeUnit.SECONDS));
        assertArrayEquals(rig.bytes, Files.readAllBytes(destination));
        assertFalse(Files.exists(source));
        assertEquals(1, rig.mover.copies);
        assertEquals(1, rig.mover.forces);
        assertEquals(1, rig.mover.deletions);
        assertEquals("pull-placement-test", rig.mover.copyThread);
        assertEquals(0, rig.store.diskBudget().snapshot().reservedBytes());
        rig.noDisposableCopies();
      } finally {
        release.countDown();
        worker.shutdownNow();
        worker.awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void sourceChangesDuringCopyAreRefusedBeforePublication(boolean verified) throws Exception {
    try (var rig = new Rig()) {
      var entry = rig.staged();
      rig.mover.afterCopy = (from, to) -> Files.write(from, new byte[] {99}, StandardOpenOption.APPEND);
      var refused = assertThrows(IOException.class, () -> rig.place(entry, verified));
      assertTrue(refused.getMessage().contains("changed"), refused::getMessage);
      assertTrue(Files.exists(rig.root.resolve(entry.localName())));
      assertEquals(0, rig.mover.deletions);
      assertEquals(0, StoreCatalog.readManaged(rig.root, rig.security).files().size());
      assertEquals(0, rig.store.diskBudget().snapshot().reservedBytes());
      rig.noDisposableCopies();
    }
  }

  @Test void aCopyThatCannotBeForcedNeverDeletesTheOriginal() throws Exception {
    try (var rig = new Rig()) {
      var entry = rig.staged();
      rig.mover.forceFailure = true;
      var refused = assertThrows(IOException.class, () -> rig.place(entry, true));
      assertTrue(refused.getMessage().contains("fixture force failure"), refused::getMessage);
      assertArrayEquals(rig.bytes, Files.readAllBytes(rig.root.resolve(entry.localName())));
      assertEquals(0, rig.mover.deletions);
      assertEquals(0, StoreCatalog.readManaged(rig.root, rig.security).files().size());
      assertEquals(0, rig.store.diskBudget().snapshot().reservedBytes());
      rig.noDisposableCopies();
    }
  }

  @Test void aSameSizeCopyWithDifferentBytesIsRefusedBeforePublication() throws Exception {
    try (var rig = new Rig()) {
      var entry = rig.staged();
      rig.mover.afterCopy = (from, to) -> {
        var changed = Files.readAllBytes(to);
        changed[changed.length - 1] ^= 1;
        Files.write(to, changed);
      };
      var refused = assertThrows(IOException.class, () -> rig.place(entry, true));
      assertTrue(refused.getMessage().contains("differs from its verified source"), refused::getMessage);
      assertArrayEquals(rig.bytes, Files.readAllBytes(rig.root.resolve(entry.localName())));
      assertEquals(0, rig.mover.deletions);
      assertEquals(0, StoreCatalog.readManaged(rig.root, rig.security).files().size());
      assertEquals(0, rig.store.diskBudget().snapshot().reservedBytes());
      rig.noDisposableCopies();
    }
  }

  @Test void transferProgressChangedAfterCopyCannotBePublished() throws Exception {
    try (var rig = new Rig()) {
      var entry = rig.staged();
      rig.local.beforePlacement(source -> {
        try {
          rig.local.save(new PullManifest(PullManifest.FORMAT_VERSION, SERIAL, List.of(
              new PullManifest.Entry(entry.remoteName(), entry.size(), entry.mtimeMillis(),
                  entry.bytesCopied() - 1, false, entry.localName(), 0, null)), List.of()));
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
      });
      var refused = assertThrows(IOException.class, () -> rig.place(entry, true));
      assertTrue(refused.getMessage().contains("Pull progress changed before placement"), refused::getMessage);
      assertEquals(1, rig.mover.copies, "The change occurs after preparing the destination copy");
      assertArrayEquals(rig.bytes, Files.readAllBytes(rig.root.resolve(entry.localName())));
      assertEquals(0, rig.mover.deletions);
      assertEquals(0, StoreCatalog.readManaged(rig.root, rig.security).files().size());
      assertEquals(0, rig.store.diskBudget().snapshot().reservedBytes());
      rig.noDisposableCopies();
    }
  }

  @Test void aPreparedCopyChangedBeforePublicationIsRefused() throws Exception {
    try (var rig = new Rig()) {
      var entry = rig.staged();
      rig.local.beforePlacement(source -> {
        try {
          Files.write(rig.mover.destinations.get(0), new byte[] {99}, StandardOpenOption.APPEND);
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
      });
      var refused = assertThrows(IOException.class, () -> rig.place(entry, true));
      assertTrue(refused.getMessage().contains("Prepared pull copy changed before placement"),
          refused::getMessage);
      assertEquals(1, rig.mover.copies);
      assertArrayEquals(rig.bytes, Files.readAllBytes(rig.root.resolve(entry.localName())));
      assertEquals(0, rig.mover.deletions);
      assertEquals(0, StoreCatalog.readManaged(rig.root, rig.security).files().size());
      assertEquals(0, rig.store.diskBudget().snapshot().reservedBytes());
      rig.noDisposableCopies();
    }
  }

  @Test void anOriginalChangedAfterCommitIsRetainedForRecovery() throws Exception {
    try (var rig = new Rig()) {
      var entry = rig.staged();
      var original = rig.root.resolve(entry.localName());
      rig.mover.afterAtomicMove = (from, to) ->
          Files.write(original, new byte[] {99}, StandardOpenOption.APPEND);
      var refused = assertThrows(IOException.class, () -> rig.place(entry, true));
      assertTrue(refused.getMessage().contains("Pulled source changed after placement; original retained"),
          refused::getMessage);
      var changed = Arrays.copyOf(rig.bytes, rig.bytes.length + 1);
      changed[changed.length - 1] = 99;
      assertArrayEquals(changed, Files.readAllBytes(original));
      assertEquals(0, rig.mover.deletions);
      var stored = StoreCatalog.readManaged(rig.root, rig.security).files();
      assertEquals(1, stored.size(), "The verified copy was already committed before cleanup");
      assertArrayEquals(rig.bytes, Files.readAllBytes(stored.get(0).path()));
      assertEquals(0, rig.store.diskBudget().snapshot().reservedBytes());
      rig.noDisposableCopies();
    }
  }

  private final class Rig implements AutoCloseable {
    final LogManager manager = LogManager.getInstance();
    final Set<Path> allowed = manager.getAllowedDirectories();
    final SecurityValidator security = new SecurityValidator();
    final Path root;
    final AtomicLong free = new AtomicLong(1_000_000);
    final DiskSpaceBudgets budgets = new DiskSpaceBudgets(filesystem -> free.get());
    final CrossFilesystem mover = new CrossFilesystem();
    final LogStore store;
    final PullStore local;
    final byte[] bytes;

    Rig() throws Exception {
      var base = temporary.toRealPath();
      root = Files.createDirectories(base.resolve("store"));
      manager.addAllowedDirectory(base); security.addAllowedDirectory(base);
      var budget = budgets.store(root);
      budget.configureCapture(new DiskSpaceBudgets.Policy(RESERVE, 0, 0));
      store = new LogStore(root, security, manager, StoreCatalog::read, mover, budget);
      var device = FakeRobot.device(SERIAL, "SHA256:synthetic");
      store.identify(device, WALL).get(5, TimeUnit.SECONDS);
      local = new PullStore(store, manager, security, device, WALL, mover);
      var fixture = base.resolve("fixture.wpilog");
      try (var writer = new WpilogWriter(fixture, "synthetic cross-filesystem pull")) {
        int entry = writer.start("/number", "double", "", 0);
        for (int i = 0; i < 100; i++) writer.append(entry, i * 20_000L, encodeDouble(i));
      }
      bytes = Files.readAllBytes(fixture);
    }

    PullManifest.Entry staged() throws Exception {
      local.manifest();
      String name = local.create("/u/logs/pulled.wpilog");
      local.append(name, 0, bytes);
      var entry = new PullManifest.Entry("/u/logs/pulled.wpilog", bytes.length, WALL.millis(),
          bytes.length, false, name, 0, null);
      local.save(new PullManifest(PullManifest.FORMAT_VERSION, SERIAL, List.of(entry), List.of()));
      return entry;
    }

    String place(PullManifest.Entry entry, boolean verified) throws Exception {
      if (!verified) return local.rename(entry.localName(), "/u/logs/renamed.wpilog");
      local.verify(entry.localName());
      return local.verified(entry);
    }

    void noDisposableCopies() {
      for (var copied : mover.destinations) assertFalse(Files.exists(copied),
          "A disposable placement copy remains: " + copied);
    }

    @Override public void close() throws Exception {
      store.close(); budgets.close();
      manager.release(temporary); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
    }
  }

  @FunctionalInterface private interface CopyAction { void run(Path source, Path destination) throws IOException; }
  @FunctionalInterface private interface DeleteAction { void run(Path source) throws IOException; }
  private static final class CrossFilesystem extends LogStore.Mover {
    CopyAction beforeCopy = (source, destination) -> {};
    CopyAction afterCopy = (source, destination) -> {};
    CopyAction afterAtomicMove = (source, destination) -> {};
    DeleteAction beforeDelete = source -> {};
    final List<Path> destinations = new ArrayList<>();
    int copies, forces, deletions;
    String copyThread;
    boolean forceFailure;
    @Override boolean sameFileStore(Path source, Path destination) { return false; }
    @Override void move(Path source, Path destination, CopyOption... options) throws IOException {
      if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
        super.move(source, destination, options);
        afterAtomicMove.run(source, destination);
      } else {
        // Model Files.move's provider fallback: a cross-volume move copies on its caller.
        copy(source, destination, Files.size(source));
        Files.delete(source);
      }
    }
    @Override LogStore.CopyAttributes copy(Path source, Path destination, long admittedBytes) throws IOException {
      copies++; copyThread = Thread.currentThread().getName(); destinations.add(destination);
      beforeCopy.run(source, destination);
      var copied = super.copy(source, destination, admittedBytes);
      afterCopy.run(source, destination);
      return copied;
    }
    @Override void force(Path destination) throws IOException {
      forces++;
      if (forceFailure) throw new IOException("fixture force failure");
      super.force(destination);
    }
    @Override void delete(Path source) throws IOException {
      beforeDelete.run(source); deletions++; super.delete(source);
    }
  }
}
