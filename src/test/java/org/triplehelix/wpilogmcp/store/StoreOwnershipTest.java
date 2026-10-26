/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** One store object owns a folder in a process, from when it is made until its queue's last turn. */
class StoreOwnershipTest {
  @TempDir Path temp;

  @Test void aSecondStoreObjectForAFolderIsRefusedUntilTheFirstHasClosed() throws Exception {
    var root = temp.toRealPath(); var folder = Files.createDirectory(root.resolve("store"));
    var security = new SecurityValidator(); security.addAllowedDirectory(root);
    var first = new StoreRegistry(security);
    try (var second = new StoreRegistry(security)) {
      var owner = first.store(folder);
      var refused = assertThrows(IllegalStateException.class, () -> second.store(folder));
      assertTrue(refused.getMessage().startsWith("STORE_OWNED:"), refused.getMessage());
      assertThrows(IllegalStateException.class, () -> new LogStore(folder, security), "a store made directly is refused too");
      assertSame(owner, first.store(folder), "the registry that holds the folder still hands out its one store");
      first.close();
      assertNotSame(owner, second.store(folder), "the folder is free once the registry holding it has closed");
    } finally { first.close(); }
  }

  @Test void aClosingRegistryKeepsItsFoldersUntilItsStoresLastTurnsHaveEnded() throws Exception {
    var root = temp.toRealPath(); var folder = Files.createDirectory(root.resolve("store"));
    var security = new SecurityValidator(); security.addAllowedDirectory(root);
    var first = new StoreRegistry(security);
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var turn = first.store(folder).captureAsync(files -> {
      entered.countDown();
      try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
      return null;
    });
    try (var second = new StoreRegistry(security)) {
      assertTrue(entered.await(10, TimeUnit.SECONDS), "a turn holds the folder's lock");
      var closing = CompletableFuture.runAsync(first::close);
      // Its imports' barrier gives up after a second; the close still waits for the turn after that.
      assertThrows(TimeoutException.class, () -> closing.get(3, TimeUnit.SECONDS), "the registry's close waits for its stores' last turns");
      assertThrows(IllegalStateException.class, () -> second.store(folder), "the folder stays owned while that turn runs");
      release.countDown();
      closing.get(10, TimeUnit.SECONDS);
      turn.get(10, TimeUnit.SECONDS);
      second.store(folder);
    } finally { release.countDown(); first.close(); }
  }

  @Test void aClosedStoreKeepsItsFolderUntilTheTurnQueuedBeforeTheCloseHasEnded() throws Exception {
    var root = temp.toRealPath(); var folder = Files.createDirectory(root.resolve("store"));
    var security = new SecurityValidator(); security.addAllowedDirectory(root);
    var store = new LogStore(folder, security);
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var turn = store.captureAsync(files -> {
      entered.countDown();
      try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
      return null;
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS), "a turn holds the folder's lock");
      var closing = CompletableFuture.runAsync(store::close);
      assertThrows(TimeoutException.class, () -> closing.get(300, TimeUnit.MILLISECONDS), "close waits for the turn queued before it");
      // The turn still takes the lock after close was called, so the folder is not yet free.
      assertThrows(IllegalStateException.class, () -> new LogStore(folder, security));
      release.countDown();
      closing.get(10, TimeUnit.SECONDS);
      turn.get(10, TimeUnit.SECONDS);
      new LogStore(folder, security).close();
    } finally { release.countDown(); store.close(); }
  }
}
