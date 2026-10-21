/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskSpaceBudgetsTest {
  @TempDir Path directory;

  @Test void siblingStoresShareReservationsAndRefusalNumbers() throws Exception {
    long reserve = 2L << 30;
    var reads = new AtomicInteger();
    try (var owner = new DiskSpaceBudgets(filesystem -> {
      reads.incrementAndGet();
      return reserve + 300;
    })) {
      var first = owner.store(Files.createDirectory(directory.resolve("first")));
      var second = owner.store(Files.createDirectory(directory.resolve("second")));
      assertEquals(reserve, first.snapshot().captureReserveBytes(), "The default protects a store before any capture starts");
      try (var held = first.reserveCopy(201)) {
        assertEquals(first.snapshot().filesystemId(), second.snapshot().filesystemId());
        assertEquals(1, owner.snapshots().size());
        assertEquals(201, second.snapshot().reservedBytes());
        var refused = assertThrows(DiskSpaceBudgets.Refused.class, () -> second.reserveCopy(100));
        assertEquals("CAPTURE_DISK_RESERVE", refused.code());
        assertEquals(reserve + 300, refused.snapshot().freeBytes());
        assertEquals(reserve, refused.snapshot().captureReserveBytes());
        assertEquals(201, refused.snapshot().reservedBytes());
        assertEquals(100, refused.requestedBytes());
        assertTrue(refused.getMessage().contains("requested_bytes=100"));
        held.close();
        held.close();
        try (var allowed = second.reserveCopy(100)) {
          assertEquals(100, first.snapshot().reservedBytes());
        }
        assertEquals(0, first.snapshot().reservedBytes());
      }
      assertEquals(3, reads.get());
    }
  }

  @Test void reserveIsMaximumConfiguredPolicyAndFloorsRemainPerCapture() throws Exception {
    try (var owner = new DiskSpaceBudgets(filesystem -> 500)) {
      var first = owner.store(Files.createDirectory(directory.resolve("first")));
      var second = owner.store(Files.createDirectory(directory.resolve("second")));
      var smaller = new DiskSpaceBudgets.Policy(100, 40, 20);
      var larger = new DiskSpaceBudgets.Policy(200, 80, 50);
      first.configureCapture(smaller); second.configureCapture(larger);
      {
        assertEquals(200, first.snapshot().captureReserveBytes());
        var sample = new DiskSpaceBudgets.Snapshot("fixture", DiskSpaceBudgets.State.AVAILABLE,
            60L, 0, 200, 1);
        assertEquals(DiskSpaceBudgets.CaptureDecision.KEEP_STATE, smaller.decide(sample, false));
        assertEquals(DiskSpaceBudgets.CaptureDecision.PAUSE, larger.decide(sample, false));
        assertEquals(DiskSpaceBudgets.CaptureDecision.KEEP_STATE, smaller.decide(sample, true));
        assertEquals(DiskSpaceBudgets.CaptureDecision.RESUME, smaller.decide(
            new DiskSpaceBudgets.Snapshot("fixture", DiskSpaceBudgets.State.AVAILABLE, 61L, 0, 200, 2), true));
        second.configureCapture(smaller);
        assertEquals(200, first.snapshot().captureReserveBytes(), "A second service cannot lower the standing reserve");
      }
      assertEquals(200, owner.store(directory.resolve("second")).snapshot().captureReserveBytes(),
          "Looking up an existing store neither removes configuration nor restores its default");
      owner.close(); assertEquals(200, first.snapshot().captureReserveBytes());
    }
  }

  @Test void nestedDestinationCarriesTheOwningStoresPolicyRatherThanRegisteringAnotherDefault() throws Exception {
    try (var owner = new DiskSpaceBudgets(filesystem -> 250)) {
      var root = Files.createDirectory(directory.resolve("store-destination"));
      var nested = Files.createDirectories(root.resolve("nested/stage"));
      var store = owner.store(root); store.configureCapture(new DiskSpaceBudgets.Policy(100, 20, 30));
      var destination = store.destination(nested);
      assertEquals(store.snapshot().filesystemId(), destination.snapshot().filesystemId());
      assertEquals(100, destination.snapshot().captureReserveBytes());
      try (var held = destination.reserveCopy(150)) { assertEquals(150, store.snapshot().reservedBytes()); }
      store.configureCapture(new DiskSpaceBudgets.Policy(180, 20, 30));
      assertEquals(180, destination.snapshot().captureReserveBytes());
      assertThrows(DiskSpaceBudgets.Refused.class, () -> destination.reserveCopy(71));
    }
  }

  @Test void measurementFailureIsUnknownNotAnInventedZeroOrFloorCrossing() throws Exception {
    try (var owner = new DiskSpaceBudgets(filesystem -> { throw new IOException("secret /mount/private"); })) {
      var store = owner.store(directory);
      var sample = store.refresh();
      assertEquals(DiskSpaceBudgets.State.UNKNOWN, sample.state());
      assertNull(sample.freeBytes());
      assertNull(sample.availableBytes());
      assertEquals(DiskSpaceBudgets.CaptureDecision.UNKNOWN,
          DiskSpaceBudgets.Policy.DEFAULT.decide(sample, false));
      assertEquals(DiskSpaceBudgets.CaptureDecision.UNKNOWN,
          DiskSpaceBudgets.Policy.DEFAULT.decide(sample, true));
      var refused = assertThrows(DiskSpaceBudgets.Refused.class, () -> store.reserveCopy(1));
      assertEquals("DISK_SPACE_UNKNOWN", refused.code());
      assertFalse(refused.getMessage().contains("/mount"));
      assertFalse(owner.snapshots().toString().contains("/mount"));
      assertEquals(0, store.snapshot().reservedBytes());
    }
  }

  @Test void staleFreeSpaceCannotSurviveAnotherWritersRelease() throws Exception {
    var reads = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var space = new AtomicLong(200);
    var executor = Executors.newSingleThreadExecutor(r -> {
      var thread = new Thread(r, "disk-budget-race");
      thread.setDaemon(true);
      return thread;
    });
    try (var owner = new DiskSpaceBudgets(filesystem -> {
      long measured = space.get();
      if (reads.incrementAndGet() == 2) {
        entered.countDown();
        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
      }
      return measured;
    })) {
      var store = owner.store(directory);
      store.configureCapture(new DiskSpaceBudgets.Policy(100, 10, 10));
      try (var held = store.reserveCopy(50)) {
        var pending = executor.submit(() -> assertThrows(DiskSpaceBudgets.Refused.class, () -> store.reserveCopy(75)));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        // The first writer has physically used its 50 bytes before returning its lease.
        space.set(150);
        held.close();
        release.countDown();
        var refused = pending.get(5, TimeUnit.SECONDS);
        assertEquals(150, refused.snapshot().freeBytes());
        assertEquals(0, refused.snapshot().reservedBytes());
        assertEquals(3, reads.get(), "The stale 200-byte sample must be discarded and read again");
      }
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test void spaceSamplingHoldsNeitherOwnerNorBudgetMonitor() throws Exception {
    var reference = new AtomicReference<DiskSpaceBudgets>();
    var registered = new AtomicReference<DiskSpaceBudgets.StoreBudget>();
    var calls = new AtomicInteger();
    var blockedObservation = new AtomicReference<Throwable>();
    var executor = Executors.newSingleThreadExecutor(r -> {
      var thread = new Thread(r, "disk-budget-observer");
      thread.setDaemon(true);
      return thread;
    });
    try (var owner = new DiskSpaceBudgets(filesystem -> {
      if (calls.incrementAndGet() == 1) {
        try {
          executor.submit(() -> {
            assertEquals(1, reference.get().snapshots().size());
            registered.get().configureCapture(new DiskSpaceBudgets.Policy(0, 0, 0));
            return null;
          }).get(2, TimeUnit.SECONDS);
        } catch (Exception failure) {
          blockedObservation.set(failure);
          throw new IOException("Another thread could not observe during sampling", failure);
        }
      }
      return 100;
    })) {
      reference.set(owner);
      var store = owner.store(directory);
      registered.set(store);
      try (var reservation = store.reserveCopy(50)) {
        assertEquals(50, store.snapshot().reservedBytes());
      }
      assertNull(blockedObservation.get(), "Sampling must let another thread observe and configure before the query returns");
      assertEquals(2, calls.get(), "The outside policy change invalidates the first sample");
    } finally { executor.shutdownNow(); }
  }

  @Test void snapshotsNeverSampleAndShutdownDoesNotReleaseOutstandingIo() throws Exception {
    var calls = new AtomicInteger();
    try (var owner = new DiskSpaceBudgets(filesystem -> { calls.incrementAndGet(); return 500; })) {
      var store = owner.store(directory);
      store.configureCapture(new DiskSpaceBudgets.Policy(0, 0, 0));
      var held = store.reserveCopy(100);
      assertEquals(1, calls.get());
      owner.snapshots();
      store.snapshot();
      assertEquals(1, calls.get(), "Health/metrics views must not call the filesystem");
      owner.close();
      assertEquals(100, store.snapshot().reservedBytes());
      assertThrows(IOException.class, () -> store.reserveCopy(1));
      held.close();
      held.close();
      assertEquals(0, store.snapshot().reservedBytes());
      assertEquals(1, calls.get());
    }
  }

  @Test void cancellationDoesNotReturnSpaceWhileTheIoOwnerIsStillBlocked() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var unwound = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var executor = Executors.newSingleThreadExecutor(r -> {
      var thread = new Thread(r, "disk-budget-blocked-io");
      thread.setDaemon(true);
      return thread;
    });
    try (var owner = new DiskSpaceBudgets(filesystem -> 500)) {
      var store = owner.store(directory);
      store.configureCapture(new DiskSpaceBudgets.Policy(0, 0, 0));
      var response = executor.submit(() -> {
        try (var held = store.reserveCopy(100)) {
          entered.countDown();
          assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (Throwable problem) { failure.set(problem); }
        finally { unwound.countDown(); }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(response.cancel(false));
      assertEquals(100, store.snapshot().reservedBytes(), "A canceled response does not own the blocked I/O's lease");
      release.countDown();
      assertTrue(unwound.await(5, TimeUnit.SECONDS));
      assertNull(failure.get());
      assertEquals(0, store.snapshot().reservedBytes());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test void byteArithmeticDoesNotOverflowAtTheLargestRepresentableReservation() throws Exception {
    try (var owner = new DiskSpaceBudgets(filesystem -> Long.MAX_VALUE)) {
      var store = owner.store(directory);
      store.configureCapture(new DiskSpaceBudgets.Policy(Long.MAX_VALUE - 5, 0, 0));
      {
        assertThrows(DiskSpaceBudgets.Refused.class, () -> store.reserveCopy(6));
        try (var held = store.reserveCopy(5)) {
          assertEquals(5, store.snapshot().reservedBytes());
          assertThrows(DiskSpaceBudgets.Refused.class, () -> store.reserveCopy(1));
        }
      }
      assertThrows(IllegalArgumentException.class, () -> store.reserveCopy(-1));
      assertThrows(IllegalArgumentException.class,
          () -> new DiskSpaceBudgets.Policy(0, Long.MAX_VALUE, 1));
    }
  }

  @Test void simultaneousRequestsCannotBothConsumeTheSameRemainingSpace() throws Exception {
    var arrived = new CountDownLatch(2);
    var released = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2, r -> {
      var thread = new Thread(r, "disk-budget-simultaneous");
      thread.setDaemon(true);
      return thread;
    });
    try (var owner = new DiskSpaceBudgets(filesystem -> {
      if (calls.incrementAndGet() <= 2) {
        arrived.countDown();
        try { assertTrue(released.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
      }
      return 200;
    })) {
      var store = owner.store(directory);
      store.configureCapture(new DiskSpaceBudgets.Policy(100, 10, 10));
      {
        var a = executor.submit(() -> reserveOrRefusal(store));
        var b = executor.submit(() -> reserveOrRefusal(store));
        assertTrue(arrived.await(5, TimeUnit.SECONDS));
        released.countDown();
        var first = a.get(5, TimeUnit.SECONDS);
        var second = b.get(5, TimeUnit.SECONDS);
        assertNotEquals(first instanceof DiskSpaceBudgets.Reservation, second instanceof DiskSpaceBudgets.Reservation,
            "Exactly one 75-byte reservation fits beside the 100-byte reserve");
        assertEquals(75, store.snapshot().reservedBytes());
        if (first instanceof DiskSpaceBudgets.Reservation held) held.close();
        if (second instanceof DiskSpaceBudgets.Reservation held) held.close();
        assertEquals(0, store.snapshot().reservedBytes());
      }
    } finally {
      released.countDown();
      executor.shutdownNow();
    }
  }

  private static Object reserveOrRefusal(DiskSpaceBudgets.StoreBudget store) throws IOException {
    try { return store.reserveCopy(75); }
    catch (DiskSpaceBudgets.Refused refused) { return refused; }
  }
}
