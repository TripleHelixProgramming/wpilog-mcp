/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class MainBackgroundTest {
  @Test void maintenanceCannotKeepAClosedStdioProcessAlive() throws Exception {
    var release = new CountDownLatch(1);
    var thread = Main.startCacheCleanup(() -> {
      try { release.await(); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    });
    try { assertTrue(thread.isDaemon(), "Cache cleanup must not retain a closed stdio JVM"); }
    finally { release.countDown(); thread.join(5000); }
    assertFalse(thread.isAlive());
  }
}
