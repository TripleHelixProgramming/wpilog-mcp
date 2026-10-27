/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ChildJvms;

/** Payload admission and publication checks belong to sync's real store adapter. */
class StoreSyncHardeningTest {
  @TempDir Path directory;

  @Test void cancellingTheResponseDoesNotReleaseTheSyncOwnerBeforePayloadUnwind() throws Exception {
    try (var peer = new StoreSyncHardeningFixture(directory, true)) {
      var first = peer.sync();
      peer.awaitPayload();
      try {
        assertTrue(first.cancel(false));
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> peer.anotherSync().get(200, TimeUnit.MILLISECONDS),
            "A canceled response must still refuse a second sync while payload I/O owns the journal");
        assertTrue(failure.getCause().getMessage().contains("already running"), failure.toString());
        assertEquals(peer.sizeBytes(), peer.reservedBytes(),
            "Canceling a response cannot return the reservation of an unfinished payload");
      } finally {
        peer.releasePayload();
        peer.target.awaitImports();
      }
    }
  }

  @Test void aSyncOneByteIntoTheCaptureReserveReadsNoPayload() throws Exception {
    try (var peer = new StoreSyncHardeningFixture(directory, false)) {
      long freeBytes = StoreSyncHardeningFixture.RESERVE_BYTES + peer.sizeBytes() - 1;
      peer.freeBytes(freeBytes);
      var result = peer.sync().get(10, TimeUnit.SECONDS);
      assertEquals(0, peer.payloadReads(),
          "An insufficient disk reservation must refuse before the peer's first payload read");
      assertEquals(List.of(), result.filesCopied(), "A refused payload cannot be published");
      assertEquals(List.of(), result.stopped(), "A capacity refusal is an explained file outcome");
      assertEquals(1, result.refusals().size(), result.toString());
      String reason = result.refusals().get(0).reason();
      assertTrue(reason.contains("free_bytes=" + freeBytes), reason);
      assertTrue(reason.contains("reserved_bytes=0"), reason);
      assertTrue(reason.contains("capture_reserve_bytes=100"), reason);
      assertTrue(reason.contains("requested_bytes=" + peer.sizeBytes()), reason);
      assertEquals(0, peer.targetCatalog().files().size());
      assertEquals(0, peer.reservedBytes(), "A refusal must release every copy reservation");
    }
  }

  @Test void anImportDuringThePayloadInvalidatesDuplicateFactsBeforePlacement() throws Exception {
    try (var peer = new StoreSyncHardeningFixture(directory, true)) {
      var syncing = peer.sync();
      peer.awaitPayload();
      try (var other = peer.competingOwner()) {
        var importing = other.importPaths(
            new LogStore.Request(List.of(peer.source), false, null), progress -> {});
        var imported = assertDoesNotThrow(() -> importing.get(2, TimeUnit.SECONDS),
            "Another store owner's import must complete while sync's real peer payload is held");
        assertEquals("imported", imported.files().get(0).status(), imported.toString());
        peer.releasePayload();
        var result = syncing.get(10, TimeUnit.SECONDS);
        assertEquals(List.of(), result.filesCopied(), "A stale download cannot add a duplicate placement");
        assertEquals(List.of(), result.stopped(), result.toString());
        assertTrue(result.refusals().stream().anyMatch(refusal -> {
          String reason = refusal.reason().toLowerCase(Locale.ROOT);
          return reason.contains("changed") || reason.contains("stale")
              || reason.contains("duplicate") || reason.contains("already");
        }), "The outcome must explain the changed duplicate facts: " + result);
        var files = peer.targetCatalog().files();
        assertEquals(1, files.size(), "Only the concurrent import's file belongs in the catalog");
        assertEquals(peer.sha256, files.get(0).file().sha256());
        assertEquals(0, peer.reservedBytes(), "Stale placement releases its transfer reservation");
      } finally {
        peer.releasePayload();
      }
    }
  }

  @Test void aSelectedSessionChangeAfterStagingCannotReceiveThePreparedFile() throws Exception {
    try (var peer = new StoreSyncHardeningFixture(directory, false)) {
      var stagedPath = new AtomicReference<Path>();
      var destinationPath = new AtomicReference<Path>();
      var result = peer.sync((boundary, staged, destination, manifest) -> {
        if (boundary != StoreSync.Boundary.STAGED) return;
        assertTrue(stagedPath.compareAndSet(null, staged), "This fixture has one staged file");
        destinationPath.set(destination);
        peer.target.capture(io -> {
          var changed = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
          changed.addProperty("id", "changed-after-staging");
          io.write(manifest, changed);
          return null;
        });
      }).get(10, TimeUnit.SECONDS);
      assertNotNull(stagedPath.get(), "The file must finish staging before its selected session changes");
      assertEquals(List.of(), result.filesCopied(),
          "The prepared file cannot be attached to a session created after its selection");
      assertEquals(List.of(), result.stopped(), result.toString());
      assertEquals(1, result.refusals().size(), result.toString());
      assertTrue(result.refusals().get(0).reason().contains("session"), result.toString());
      assertTrue(result.refusals().get(0).reason().contains("changed"), result.toString());
      var catalog = peer.targetCatalog();
      assertEquals(1, catalog.sessions().size());
      assertEquals("changed-after-staging", catalog.sessions().get(0).session().id());
      assertEquals(0, catalog.files().size());
      assertFalse(Files.exists(destinationPath.get()));
      assertArrayEquals(Files.readAllBytes(peer.source), Files.readAllBytes(stagedPath.get()),
          "Refusing changed metadata preserves the independently specified resumable bytes");
      assertEquals(0, peer.reservedBytes());
    }
  }

  @Test void anotherStoreOwnerCannotEnterTheSyncJournalDuringAPayload() throws Exception {
    try (var peer = new StoreSyncHardeningFixture(directory, true)) {
      var first = peer.sync();
      peer.awaitPayload();
      try (var other = peer.competingOwner()) {
        var opens = new AtomicInteger();
        var second = other.sync("http://synthetic.invalid", 0, progress -> {},
            StoreSyncHardeningFixture.CLOCK, address -> {
              opens.incrementAndGet();
              throw new IOException("The second owner must not contact its peer");
            });
        var failure = assertThrows(ExecutionException.class,
            () -> second.get(2, TimeUnit.SECONDS),
            "Distinct LogStore owners must share the filesystem sync exclusion");
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("already running"), failure.toString());
        assertEquals(0, opens.get());
        assertFalse(first.isDone(), "The first owner must still hold its real payload read");
        assertEquals(peer.sizeBytes(), peer.reservedBytes());
      } finally {
        peer.releasePayload();
      }
      var result = first.get(10, TimeUnit.SECONDS);
      assertEquals(List.of(), result.refusals(), result.toString());
      assertEquals(List.of(), result.stopped(), result.toString());
      assertEquals(1, result.filesCopied().size());
      assertEquals(peer.sha256, result.filesCopied().get(0).sha256());
      assertEquals(0, peer.reservedBytes());
    }
  }

  @Test void anotherProcessHoldingTheSyncFileLockRefusesBeforePeerContact() throws Exception {
    try (var peer = new StoreSyncHardeningFixture(directory, false)) {
      var marker = peer.target.root().resolve(".sync").resolve(SyncPublication.LOCK_NAME);
      Files.createDirectories(marker.getParent());
      var ready = directory.resolve("lock-ready");
      var output = directory.resolve("lock-child.log");
      var process = new ProcessBuilder(ChildJvms.JAVA, ChildJvms.HEADLESS,
          "-cp", System.getProperty("java.class.path"), SyncLockProcess.class.getName(),
          marker.toString(), ready.toString())
          .redirectErrorStream(true).redirectOutput(output.toFile()).start();
      try {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < until)
          Thread.sleep(10);
        assertTrue(Files.exists(ready), () -> {
          try { return "The child must hold its independent FileChannel: " + Files.readString(output); }
          catch (IOException failure) { return failure.toString(); }
        });
        var opens = new AtomicInteger();
        var refused = peer.target.sync("http://synthetic.invalid", 0, progress -> {},
            StoreSyncHardeningFixture.CLOCK, address -> {
              opens.incrementAndGet();
              throw new IOException("The locked owner must not contact its peer");
            });
        var failure = assertThrows(ExecutionException.class,
            () -> refused.get(2, TimeUnit.SECONDS),
            "An independent process's lock must refuse before any network work");
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("already running"), failure.toString());
        assertEquals(0, opens.get());
        assertEquals(0, peer.payloadReads());
        assertEquals(0, peer.reservedBytes());
        assertTrue(Files.exists(marker), "The permanent lock inode must remain in place");
      } finally {
        process.getOutputStream().close();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
      }
    }
  }

  /** A second JVM exercises tryLock's null result rather than Java's overlapping-lock exception. */
  public static final class SyncLockProcess {
    public static void main(String[] args) throws Exception {
      try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE,
          StandardOpenOption.WRITE);
           var held = channel.lock()) {
        Files.writeString(Path.of(args[1]), "ready");
        System.in.read();
      }
    }
  }
}
