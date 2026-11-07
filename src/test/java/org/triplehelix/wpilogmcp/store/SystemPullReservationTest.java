/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.capture.pull.FakeRobot;
import org.triplehelix.wpilogmcp.capture.pull.RobotRemote;
import org.triplehelix.wpilogmcp.capture.pull.SystemPullPass;
import org.triplehelix.wpilogmcp.capture.pull.SystemRemote;
import org.triplehelix.wpilogmcp.config.SystemPullConfig;
import org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.ssh.SshConnection;
import org.triplehelix.wpilogmcp.sync.FileTransfer;

class SystemPullReservationTest {
  @TempDir Path temp;
  private static final class Robot implements RobotRemote, SystemRemote {
    private final FakeRobot delegate = new FakeRobot();
    Robot() {
      delegate.device = FakeRobot.device(SystemSessionFixture.SERIAL, "SHA256:fixture");
      delegate.files.put("/var/log/program.log", new byte[2 * FileTransfer.BLOCK_BYTES]);
    }
    @Override public DeviceIdentity identity() { return delegate.identity(); }
    @Override public List<File> list() { return delegate.list(); }
    @Override public byte[] read(String name, long offset, int count) throws IOException { return delegate.read(name, offset, count); }
    @Override public Optional<String> prefixHash(String name, long length) throws IOException { return delegate.prefixHash(name, length); }
    @Override public List<SourceFile> systemFiles(SystemPullConfig config) { return list().stream().map(file -> new SourceFile(file, "program")).toList(); }
    @Override public SshConnection.Command systemCommand(String command) { throw new AssertionError("This fixture never runs a command"); }
    @Override public void close() { delegate.close(); }
  }

  @Test void closingTheSystemPassReturnsItsPartialFileReservation() throws Exception {
    var root = temp.resolve("store"); SystemSessionFixture.create(root, "first", true, 42);
    var security = new SecurityValidator(); security.addAllowedDirectory(temp);
    try (var space = new DiskSpaceBudgets(filesystem -> 1_000_000);
        var registry = new StoreRegistry(security, LogManager.getInstance(), StoreCatalog::discover, space)) {
      var store = registry.store(root); store.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(0, 0, 0));
      var robot = new Robot(); var local = store.systemPulls(robot.identity(), SystemSessionFixture.WALL);
      var config = new SystemPullConfig(true, SystemPullConfig.Kernel.OFF, List.of(), false, List.of("/var/log"), List.of());
      try (var pass = new SystemPullPass(robot, local, config, 1_000_000, () -> 0, () -> true)) {
        assertEquals(FileTransfer.Status.COPIED, pass.step().status());
        assertEquals(2L * FileTransfer.BLOCK_BYTES, store.diskBudget().snapshot().reservedBytes());
        pass.close();
        assertEquals(0, store.diskBudget().snapshot().reservedBytes(),
            "Closing the system pass owns and closes its partial FileTransfer");
        var saved = local.manifest().files().get(0);
        assertEquals(FileTransfer.BLOCK_BYTES, saved.bytesCopied());
      }
    }
  }

  @Test void interruptedSystemPublicationKeepsItsParentLeaseUntilItsQueuedCopyCompletes() throws Exception {
    var root = temp.resolve("store"); SystemSessionFixture.create(root, "first", true, 42);
    var security = new SecurityValidator(); security.addAllowedDirectory(temp);
    try (var space = new DiskSpaceBudgets(filesystem -> 1_000_000);
        var registry = new StoreRegistry(security, LogManager.getInstance(), StoreCatalog::discover, space)) {
      var store = registry.store(root); store.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(0, 0, 0));
      var robot = new Robot(); var local = store.systemPulls(robot.identity(), SystemSessionFixture.WALL);
      assertTrue(local.beginPass()); local.manifest(); local.sources(java.util.Map.of("program.log", "program"));
      String name = local.create("program.log"); byte[] expected = "synthetic system text\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      local.append(name, 0, expected);
      var entry = new org.triplehelix.wpilogmcp.sync.PullManifest.Entry("program.log", expected.length, 0, expected.length, false, name, 0, null);
      var parent = local.reserve("program.log", expected.length);
      var blocked = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
      var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
      var executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "system-publication-response"); thread.setDaemon(true); return thread;
      });
      var barrier = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
      local.copier(new LogStore.Mover() {
        @Override LogStore.CopyAttributes copy(Path from, Path to, long bytes) throws IOException {
          var attributes = super.copy(from, to, bytes);
          barrier.set(store.<Void>captureAsync(io -> {
            blocked.countDown();
            try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture queue barrier expired"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
            return null;
          }));
          try {
            if (!blocked.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture queue barrier did not start");
          } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
          return attributes;
        }
      });
      try {
        var response = executor.submit(() -> { worker.set(Thread.currentThread()); return local.verified(entry); });
        assertTrue(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        boolean queued = false;
        while (System.nanoTime() < deadline) {
          var thread = worker.get();
          if (thread != null && thread.getState() == Thread.State.WAITING
              && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                  frame.getClassName().equals(LogStore.class.getName()) && frame.getMethodName().equals("capture")
                  || frame.getClassName().equals(CopyLeaseGroup.class.getName()) && frame.getMethodName().equals("await"))) {
            queued = true; break;
          }
          Thread.sleep(1);
        }
        assertTrue(queued, "The system publication must be admitted behind the queue barrier");
        worker.get().interrupt();
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> response.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause()); parent.close();
        assertEquals(2L * expected.length, store.diskBudget().snapshot().reservedBytes(),
            "The admitted immutable publication still owns both its interrupted parent's lease and its second copy");
        release.countDown(); barrier.get().get(5, java.util.concurrent.TimeUnit.SECONDS); store.awaitMutations();
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (store.diskBudget().snapshot().reservedBytes() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(0, store.diskBudget().snapshot().reservedBytes());
        assertEquals(1, local.state().files().size());
      } finally { release.countDown(); executor.shutdownNow(); parent.close(); }
    }
  }
}
