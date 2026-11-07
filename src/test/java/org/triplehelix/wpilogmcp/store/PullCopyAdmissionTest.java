/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.capture.pull.FakeRobot;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.sync.FileTransfer;
import org.triplehelix.wpilogmcp.sync.PullManifest;

class PullCopyAdmissionTest {
  @TempDir Path temp;

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void actualPullAdapterRefusesBeforePayloadCreationOrRemoteRead(boolean system) throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(temp);
    try (var space = new DiskSpaceBudgets(filesystem -> 500);
        var registry = new StoreRegistry(security, LogManager.getInstance(), StoreCatalog::discover, space)) {
      var store = registry.store(temp.resolve("store"));
      store.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(100, 0, 0));
      var robot = new FakeRobot(); robot.files.put("/u/logs/fixture.revlog", new byte[401]);
      FileTransfer.Local local;
      PullManifest initial;
      if (system) {
        var adapter = store.systemPulls(robot.device, Clock.systemUTC());
        local = adapter; initial = adapter.manifest();
      } else {
        var adapter = store.pulls(robot.device, Clock.systemUTC());
        local = adapter; initial = adapter.manifest();
      }
      try (var transfer = new FileTransfer(robot, local, initial, 1_000_000, () -> 0, () -> true)) {
        var refused = assertThrows(DiskSpaceBudgets.Refused.class, transfer::step);
        assertEquals("CAPTURE_DISK_RESERVE", refused.code());
        assertEquals(500L, refused.snapshot().freeBytes()); assertEquals(100, refused.snapshot().captureReserveBytes());
        assertEquals(401, refused.requestedBytes()); assertTrue(robot.reads.isEmpty());
        try (var files = Files.walk(store.root())) {
          assertTrue(files.noneMatch(path -> path.toString().endsWith(".part")
              || path.toString().endsWith(".revlog")), "Physical admission precedes payload creation");
        }
        assertEquals(0, store.diskBudget().snapshot().reservedBytes());
      }
    }
  }

  @Test void interruptedPullCheckpointKeepsSpaceUntilItsAdmittedStoreWriteCompletes() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(temp);
    try (var space = new DiskSpaceBudgets(filesystem -> 1_000_000);
        var registry = new StoreRegistry(security, LogManager.getInstance(), StoreCatalog::discover, space)) {
      var store = registry.store(temp.resolve("store"));
      store.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(0, 0, 0));
      var robot = new FakeRobot(); byte[] expected = new byte[FileTransfer.BLOCK_BYTES];
      java.util.Arrays.fill(expected, (byte) 79); robot.files.put("/u/logs/fixture.revlog", expected);
      var local = store.pulls(robot.device, Clock.systemUTC());
      var transfer = new FileTransfer(robot, local, local.manifest(), 1_000_000, () -> 0, () -> true);
      var blocked = new CountDownLatch(1); var release = new CountDownLatch(1);
      var worker = new AtomicReference<Thread>();
      var executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "pull-checkpoint-response"); thread.setDaemon(true); return thread;
      });
      robot.onRead = () -> {
        store.captureAsync(io -> {
          blocked.countDown();
          try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("fixture queue barrier expired"); }
          catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
          return null;
        });
        try { assertTrue(blocked.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
      };
      try {
        var response = executor.submit(() -> { worker.set(Thread.currentThread()); return transfer.step(); });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean queued = false;
        while (System.nanoTime() < deadline) {
          var thread = worker.get();
          if (thread != null && thread.getState() == Thread.State.WAITING
              && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                  frame.getClassName().equals(LogStore.class.getName()) && frame.getMethodName().equals("capture")
                  || frame.getClassName().equals("org.triplehelix.wpilogmcp.store.CopyLeaseGroup") && frame.getMethodName().equals("await"))) {
            queued = true; break;
          }
          Thread.sleep(1);
        }
        assertTrue(queued, "The response must reach its admitted checkpoint behind the queue barrier");
        assertEquals(expected.length, store.diskBudget().snapshot().reservedBytes());
        worker.get().interrupt();
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals(expected.length, store.diskBudget().snapshot().reservedBytes(),
            "The queued append still owns the interrupted response's reservation");
        release.countDown(); store.awaitMutations();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.diskBudget().snapshot().reservedBytes() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(0, store.diskBudget().snapshot().reservedBytes());
        var saved = local.manifest().files().get(0);
        assertEquals(expected.length, saved.bytesCopied());
        assertArrayEquals(expected, Files.readAllBytes(store.root().resolve(saved.localName())));
      } finally {
        release.countDown(); executor.shutdownNow(); transfer.close();
      }
    }
  }
}
