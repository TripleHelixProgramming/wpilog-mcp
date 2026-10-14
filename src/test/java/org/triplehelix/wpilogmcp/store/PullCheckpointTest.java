/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.FakeRobot;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.sync.FileTransfer;
import org.triplehelix.wpilogmcp.sync.PullManifest;

class PullCheckpointTest {
  @TempDir Path temp;
  static final int BLOCK = 65536;

  @Test void actualStoreCoalescesBlocksAndItsManifestNeverGetsAheadOfStaging() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(temp);
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(temp.resolve("store")); var robot = new FakeRobot();
      var ahead = new java.util.concurrent.CopyOnWriteArrayList<String>();
      store.observe(new LogStore.Observer() {
        public void inventory(StoreCatalog.Snapshot snapshot) { }
        public void manifest(Path path, Object value) {
          if (value instanceof PullManifest progress) for (var entry : progress.files()) {
            try { if (entry.bytesCopied() > Files.size(store.root().resolve(entry.localName()))) ahead.add(entry.localName()); }
            catch (java.io.IOException e) { ahead.add(e.toString()); }
          }
        }
      });
      byte[] bytes = new byte[16 * BLOCK]; new java.util.Random(32).nextBytes(bytes);
      robot.files.put("/u/logs/robot.revlog", bytes);
      var local = store.pulls(robot.device, Clock.systemUTC()); var now = new AtomicLong();
      var transfer = new FileTransfer(robot, local, local.manifest(), 1_000_000, now::get, () -> true);
      for (int i = 0; i < 16; i++) {
        var step = transfer.step(); now.addAndGet(step.waitUs());
        var saved = local.manifest().files().get(0);
        assertEquals(i == 15 ? bytes.length : 0, saved.bytesCopied(), "1 MiB or one-second checkpoints");
        assertEquals(saved.bytesCopied(), Files.size(store.root().resolve(saved.localName())));
      }
      assertArrayEquals(bytes, Files.readAllBytes(store.root().resolve(local.manifest().files().get(0).localName())));
      assertTrue(ahead.isEmpty(), "a published manifest counted bytes before their append: " + ahead);
    }
  }

  @Test void aLongerStagedFileIsTruncatedAndContinuesAtTheSavedCount() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(temp);
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(temp.resolve("store")); var robot = new FakeRobot();
      byte[] bytes = new byte[4 * BLOCK]; new java.util.Random(37).nextBytes(bytes);
      String remote = "/u/logs/robot.revlog"; robot.files.put(remote, bytes);
      var local = store.pulls(robot.device, Clock.systemUTC()); local.manifest(); String name = local.create(remote);
      local.append(name, 0, Arrays.copyOf(bytes, BLOCK));
      var manifest = new PullManifest(1, robot.device.serialNumber(), List.of(new PullManifest.Entry(remote,
          bytes.length, robot.mtime, BLOCK, false, name, 0, null)), List.of()); local.save(manifest);
      local.append(name, BLOCK, Arrays.copyOfRange(bytes, BLOCK, 2 * BLOCK)); // Crash before the manifest replacement.
      var now = new AtomicLong(); var transfer = new FileTransfer(robot, local, local.manifest(), 1_000_000, now::get, () -> true);
      for (int i = 0; i < 3; i++) { var step = transfer.step(); now.addAndGet(step.waitUs()); }
      assertEquals(BLOCK, robot.reads.get(0).offset());
      assertTrue(local.manifest().history().isEmpty()); assertEquals(name, local.manifest().files().get(0).localName());
      assertArrayEquals(bytes, Files.readAllBytes(store.root().resolve(name)));
    }
  }
  @Test void aStaleTransferCannotTruncateBytesThatANewerManifestAlreadyCounts() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(temp);
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(temp.resolve("store")); var robot = new FakeRobot();
      byte[] bytes = new byte[4 * BLOCK]; String remote = "/u/logs/robot.revlog"; robot.files.put(remote, bytes);
      var local = store.pulls(robot.device, Clock.systemUTC()); local.manifest(); String name = local.create(remote);
      local.append(name, 0, Arrays.copyOf(bytes, 2 * BLOCK));
      var stale = new PullManifest(1, robot.device.serialNumber(), List.of(new PullManifest.Entry(remote,
          bytes.length, robot.mtime, BLOCK, false, name, 0, null)), List.of());
      var current = new PullManifest(1, robot.device.serialNumber(), List.of(new PullManifest.Entry(remote,
          bytes.length, robot.mtime, 2 * BLOCK, false, name, 0, null)), List.of()); local.save(current);
      var transfer = new FileTransfer(robot, local, stale, 1_000_000, () -> 0, () -> true);
      var error = assertThrows(java.io.IOException.class, transfer::step);
      assertTrue(error.getMessage().contains("checkpoint changed"));
      assertEquals(current, local.manifest()); assertEquals(2 * BLOCK, Files.size(store.root().resolve(name)));
    }
  }

}
