/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.store.DiskSpaceBudgets;

class FileTransferReservationTest {
  @TempDir Path directory;
  private static final int BLOCK = FileTransfer.BLOCK_BYTES;

  private static final class Reserved extends FileTransferBatchTest.Batched {
    final DiskSpaceBudgets.StoreBudget budget;
    final AtomicInteger reservations = new AtomicInteger();
    final AtomicInteger releases = new AtomicInteger();
    final java.util.List<Long> requested = new java.util.ArrayList<>();
    Reserved(DiskSpaceBudgets.StoreBudget budget) { this.budget = budget; }
    @Override public FileTransfer.Reservation reserve(String remote, long bytes) throws IOException {
      var held = budget.reserveCopy(bytes); reservations.incrementAndGet(); requested.add(bytes);
      return () -> { held.close(); releases.incrementAndGet(); };
    }
    @Override public void append(String name, long offset, byte[] bytes) {
      assertTrue(budget.snapshot().reservedBytes() >= bytes.length,
          "Payload writes must stay inside an outstanding physical-space reservation");
      super.append(name, offset, bytes);
    }
  }
  private DiskSpaceBudgets.StoreBudget store(DiskSpaceBudgets owner) throws IOException {
    var budget = owner.store(directory);
    budget.configureCapture(new DiskSpaceBudgets.Policy(0, 0, 0));
    return budget;
  }

  @Test void oneFullRemainingLeaseSurvivesCheckpointsAndGatePausesUntilVerified() throws Exception {
    try (var owner = new DiskSpaceBudgets(filesystem -> 1_000_000)) {
      var budget = store(owner); var f = new Reserved(budget); f.input(8);
      try (var transfer = f.engine()) {
        assertEquals(FileTransfer.Status.COPIED, f.step(transfer).status());
        assertEquals(8L * BLOCK, budget.snapshot().reservedBytes(), "Reserve the whole listing, not this 64 KiB step");
        assertEquals(0, f.checkpoints);
        for (int i = 0; i < 3; i++) f.step(transfer);
        assertEquals(1, f.checkpoints); assertEquals(8L * BLOCK, budget.snapshot().reservedBytes());
        f.gate.set(false); assertEquals(FileTransfer.Status.PAUSED, f.step(transfer).status());
        assertEquals(8L * BLOCK, budget.snapshot().reservedBytes(), "A pause keeps the transfer's future allocation admitted");
        f.gate.set(true); f.finish(transfer);
        assertEquals(0, budget.snapshot().reservedBytes());
        assertEquals(List.of(8L * BLOCK), f.requested); assertEquals(1, f.releases.get());
        assertArrayEquals(FileTransferTest.bytes(8 * BLOCK, 42), f.local.get(f.saved.files().get(0).localName()));
      }
      assertEquals(1, f.releases.get(), "Close must not return a verified file's lease twice");
    }
  }

  @Test void anExistingPrefixReservesOnlyTheRemainderAndGrowthAddsWithoutDroppingTheOldLease() throws Exception {
    try (var owner = new DiskSpaceBudgets(filesystem -> 1_000_000)) {
      var budget = store(owner); var f = new Reserved(budget); byte[] full = FileTransferTest.bytes(4 * BLOCK, 42);
      f.put("robot.revlog", Arrays.copyOf(full, 3 * BLOCK), 1);
      String held = f.create("robot.revlog"); f.local.put(held, Arrays.copyOf(full, BLOCK));
      f.saved = new PullManifest(PullManifest.FORMAT_VERSION, "SYNTHETIC-A",
          List.of(new PullManifest.Entry("robot.revlog", 3L * BLOCK, 1, BLOCK, false, held, 0, null)), List.of());
      try (var transfer = f.engine()) {
        f.step(transfer); assertEquals(2L * BLOCK, budget.snapshot().reservedBytes());
        f.put("robot.revlog", full, 2); f.now.addAndGet(FileTransfer.LISTING_PERIOD_US);
        f.step(transfer);
        assertEquals(List.of(2L * BLOCK, (long) BLOCK), f.requested);
        assertEquals(3L * BLOCK, budget.snapshot().reservedBytes(), "Already admitted bytes remain charged while growth is acquired");
        f.finish(transfer); assertEquals(0, budget.snapshot().reservedBytes());
        assertArrayEquals(full, f.local.get(f.saved.files().get(0).localName()));
      }
    }
  }

  @Test void aRefusedCopyDoesNotCreateOrReadItsPayload() throws Exception {
    try (var owner = new DiskSpaceBudgets(filesystem -> BLOCK - 1)) {
      var f = new Reserved(store(owner)); f.input(1);
      try (var transfer = f.engine()) {
        var refused = assertThrows(DiskSpaceBudgets.Refused.class, transfer::step);
        assertEquals("CAPTURE_DISK_RESERVE", refused.code()); assertEquals(BLOCK, refused.requestedBytes());
        assertEquals(0, f.unique, "Refusal occurs before creating the staging payload");
        assertTrue(f.reads.isEmpty()); assertTrue(f.local.isEmpty());
      }
    }
  }

  @Test void aTimedOutCallerAndBusyCloseKeepTheLeaseUntilTheBlockedRemoteReadUnwinds() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var worker = Executors.newSingleThreadExecutor(task -> {
      var thread = new Thread(task, "reservation-remote-read"); thread.setDaemon(true); return thread;
    });
    try (var owner = new DiskSpaceBudgets(filesystem -> 1_000_000)) {
      var budget = store(owner); var f = new Reserved(budget); f.input(8);
      f.reading = () -> {
        entered.countDown();
        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
      };
      var transfer = f.engine();
      try {
        var response = worker.submit(transfer::step);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> response.get(10, TimeUnit.MILLISECONDS));
        assertTimeoutPreemptively(Duration.ofMillis(500), transfer::close);
        assertEquals(8L * BLOCK, budget.snapshot().reservedBytes()); assertEquals(0, f.releases.get());
        assertEquals(0, f.closes, "Closing a response does not own the blocked stream");
        release.countDown(); response.get(5, TimeUnit.SECONDS);
        assertEquals(0, budget.snapshot().reservedBytes()); assertEquals(1, f.releases.get());
        assertEquals(BLOCK, f.saved.files().get(0).bytesCopied(), "The in-flight owner checkpoints before relinquishing its lease");
        assertEquals(1, f.closes);
      } finally { release.countDown(); transfer.close(); }
    } finally { release.countDown(); worker.shutdownNow(); }
  }

  @Test void aFailedStepReturnsSpaceOnlyAfterItsCleanupAndTheNextAttemptReacquires() throws Exception {
    try (var owner = new DiskSpaceBudgets(filesystem -> 1_000_000)) {
      var budget = store(owner); var f = new Reserved(budget); f.input(8);
      try (var transfer = f.engine()) {
        f.step(transfer);
        f.reading = () -> { assertEquals(8L * BLOCK, budget.snapshot().reservedBytes()); throw new IllegalStateException("synthetic remote read failure"); };
        assertThrows(IllegalStateException.class, () -> f.step(transfer));
        assertEquals(0, budget.snapshot().reservedBytes()); assertEquals(1, f.releases.get());
        f.reading = () -> {}; f.finish(transfer);
        assertEquals(2, f.reservations.get()); assertEquals(2, f.releases.get());
        assertArrayEquals(FileTransferTest.bytes(8 * BLOCK, 42), f.local.get(f.saved.files().get(0).localName()));
      }
    }
  }

  @Test void aFailedCloseRetainsBufferedDataButMustReacquireBeforeRetryingItsWrite() throws Exception {
    try (var owner = new DiskSpaceBudgets(filesystem -> 1_000_000)) {
      var budget = store(owner); var f = new Reserved(budget); f.input(8); var transfer = f.engine();
      f.step(transfer); f.checkpointFailure = new IOException("synthetic checkpoint failure");
      assertThrows(IOException.class, transfer::close);
      assertEquals(0, budget.snapshot().reservedBytes()); assertEquals(1, f.releases.get());
      assertEquals(0, f.saved.files().get(0).bytesCopied());
      f.checkpointFailure = null; transfer.close();
      assertEquals(List.of(8L * BLOCK, 8L * BLOCK), f.requested);
      assertEquals(BLOCK, f.saved.files().get(0).bytesCopied()); assertEquals(0, budget.snapshot().reservedBytes());
      assertEquals(2, f.releases.get());
    }
  }
}
