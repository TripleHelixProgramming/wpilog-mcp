/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

class StoreRegistryTest {
  @TempDir Path temp;
  Set<Path> previousAllowed;

  @BeforeEach void allowFixture() {
    var manager = LogManager.getInstance();
    previousAllowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(temp);
  }

  @AfterEach void restoreDirectories() {
    var manager = LogManager.getInstance();
    manager.clearAllowedDirectories();
    previousAllowed.forEach(manager::addAllowedDirectory);
  }

  @Test void directoryDiscoveryIsBoundedWhileKnownInboxesStillGetEveryPoll() throws Exception {
    temp = temp.toRealPath();
    var security = new SecurityValidator();
    security.addAllowedDirectory(temp);
    var walks = new AtomicInteger();
    try (var registry = new StoreRegistry(security, LogManager.getInstance(), directory -> {
      walks.incrementAndGet();
      return StoreCatalog.discover(directory);
    })) {
      registry.poll(0);
      assertEquals(1, walks.get());
      // Another writer creates a store after discovery; a listing supplies that discovery now.
      var root = temp.resolve("nested").resolve("store");
      try (var owner = new StoreRegistry(security)) {
        owner.store(root).importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
      }
      registry.discovered(root);
      var file = ImportFixture.write(root.resolve("inbox").resolve("one.wpilog"), 30);
      for (int seconds = 3; seconds < 60; seconds += 3) registry.poll(Duration.ofSeconds(seconds).toNanos());
      registry.awaitImports();
      assertFalse(Files.exists(file), "Known inboxes are polled even between discoveries");
      assertEquals(1, walks.get(), "Nineteen inbox polls must not re-walk configured directories");
      registry.poll(Duration.ofMinutes(1).toNanos());
      assertEquals(2, walks.get(), "A new discovery is due at one minute");
    }
  }

  @Test void watchingDiscoversAtStartupAndAgainAfterBeingStopped() throws Exception {
    var security = new SecurityValidator();
    security.addAllowedDirectory(temp);
    var walks = new AtomicInteger();
    var first = new CountDownLatch(1);
    var second = new CountDownLatch(1);
    try (var registry = new StoreRegistry(security, LogManager.getInstance(), directory -> {
      if (walks.incrementAndGet() == 1) first.countDown();
      else second.countDown();
      return List.of();
    })) {
      registry.startWatching();
      assertTrue(first.await(2, TimeUnit.SECONDS));
      registry.stopWatching();
      registry.startWatching();
      assertTrue(second.await(2, TimeUnit.SECONDS));
      registry.stopWatching();
      assertEquals(2, walks.get());
    }
  }
}
