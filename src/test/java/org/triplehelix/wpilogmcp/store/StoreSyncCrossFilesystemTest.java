/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Model the filesystem boundary without needing a mounted test volume on Windows CI. */
class StoreSyncCrossFilesystemTest {
  @TempDir Path directory;

  private static class CrossVolume extends LogStore.Mover {
    final AtomicInteger copies = new AtomicInteger();
    @Override boolean sameFileStore(Path source, Path destination) { return false; }
    @Override LogStore.CopyAttributes copy(Path source, Path destination, long bytes) throws IOException {
      copies.incrementAndGet();
      return super.copy(source, destination, bytes);
    }
  }

  @Test void aSecondFilesystemAllocationMustFitBesideTheDownloadReservation() throws Exception {
    var mover = new CrossVolume();
    try (var peer = new StoreSyncHardeningFixture(directory, false, mover)) {
      peer.freeBytes(StoreSyncHardeningFixture.RESERVE_BYTES + 2 * peer.sizeBytes() - 1);
      var result = peer.sync().get(10, TimeUnit.SECONDS);
      assertEquals(List.of(), result.filesCopied(),
          "Cross-filesystem publication cannot silently bypass its second allocation");
      assertEquals(List.of(), result.stopped(), result.toString());
      assertEquals(1, result.refusals().size(), result.toString());
      assertTrue(result.refusals().get(0).reason().contains("requested_bytes=" + peer.sizeBytes()),
          result.toString());
      assertEquals(0, mover.copies.get(), "Refuse the second allocation before copying a byte");
      assertEquals(0, peer.reservedBytes());
      assertEquals(0, peer.targetCatalog().files().size());
    }
  }

  @Test void aCrossFilesystemCopyLeavesTheCaptureOwnerAvailable() throws Exception {
    var copying = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var mover = new CrossVolume() {
      @Override LogStore.CopyAttributes copy(Path source, Path destination, long bytes) throws IOException {
        copying.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Synthetic copy was not released");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt(); throw new IOException(interrupted);
        }
        return super.copy(source, destination, bytes);
      }
    };
    try (var peer = new StoreSyncHardeningFixture(directory, false, mover)) {
      var syncing = peer.sync();
      try {
        assertTrue(copying.await(5, TimeUnit.SECONDS),
            "Different filesystems must use the independently reserved staging copy");
        assertEquals(2 * peer.sizeBytes(), peer.reservedBytes());
        assertEquals(42, peer.target.recordingAsync(io -> 42,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)).get(200, TimeUnit.MILLISECONDS),
            "The real capture lane must progress while the placement copy is held");
      } finally { release.countDown(); }
      var result = syncing.get(10, TimeUnit.SECONDS);
      assertEquals(List.of(), result.stopped(), result.toString());
      assertEquals(List.of(), result.refusals(), result.toString());
      assertEquals(1, result.filesCopied().size());
      assertEquals(peer.sha256, result.filesCopied().get(0).sha256());
      assertEquals(1, mover.copies.get());
      assertEquals(0, peer.reservedBytes());
    } finally { release.countDown(); }
  }
}
