/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.log.LogManager;

/** Historical fixture writes must share the real owner, including its startup recovery turns. */
class LegacyMirrorFixtureOwnershipTest {
  @TempDir Path directory;
  private final LogManager manager = LogManager.getInstance();
  private Set<Path> previous;
  private LogStore store;

  @BeforeEach void setup() throws Exception {
    directory = directory.toRealPath(); previous = manager.getAllowedDirectories();
    manager.addAllowedDirectory(directory);
    store = manager.stores().store(directory.resolve("mirror"));
    store.publicationsSettled().get(10, TimeUnit.SECONDS);
  }

  @AfterEach void cleanup() {
    if (store != null) store.close();
    manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }

  @Test void aLegacyFixtureQueuesBehindAnAdmittedOwnerInsteadOfCompetingForItsLock() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var held = store.captureAsync(io -> {
      entered.countDown();
      try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Synthetic owner hold timed out"); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
      return null;
    });
    assertTrue(entered.await(10, TimeUnit.SECONDS));
    try {
      var config = new MirrorConfig("http://127.0.0.1:1", store.root(), 14, 1 << 20,
          List.of(), List.of(), 30, 0);
      // Inline scheduling proves the fixture's admission has run before examining its future.
      // The injected source never opens a socket, even after the held owner is released.
      var legacy = LegacyMirrorFixture.sync(store, config, ignored -> {}, Clock.systemUTC(), address -> {
        throw new IOException("Synthetic offline origin");
      }, Runnable::run);
      assertFalse(legacy.isDone(), "Historical fixture admission must queue behind its store owner, not fail with StoreLock.Held");
      release.countDown(); held.get(10, TimeUnit.SECONDS);
      var result = legacy.get(10, TimeUnit.SECONDS);
      assertEquals("offline", result.state());
      assertEquals("Synthetic offline origin", result.reason());
      assertEquals(0, result.bytesCopied());
    } finally { release.countDown(); held.get(10, TimeUnit.SECONDS); }
  }
}
