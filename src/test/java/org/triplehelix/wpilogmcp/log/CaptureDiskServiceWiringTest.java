/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureService;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.store.DiskSpaceBudgets;

/** A service protects capture's reserve while waiting, before it connects or starts sampling. */
class CaptureDiskServiceWiringTest {
  @TempDir Path directory;

  @Test void startupRecoveryDoesNotWaitForTheListenerToDrain() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var loop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
    var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", 9, "disk-startup")),
        directory.resolve("store"), .02, CapturePolicy.ALL, 0);
    var capture = new CaptureService(config, manager, java.time.Clock.systemUTC(), loop);
    try {
      var started = capture.start();
      assertDoesNotThrow(() -> started.get(2, java.util.concurrent.TimeUnit.SECONDS),
          "Store recovery must complete without joining the NT4 listener's initial disk publication");
    } finally {
      var closing = java.util.concurrent.CompletableFuture.runAsync(capture::close);
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (!closing.isDone() && System.nanoTime() < deadline) { loop.drain(); Thread.sleep(1); }
      try { closing.get(1, java.util.concurrent.TimeUnit.SECONDS); }
      finally { manager.shutdown(); }
    }
  }

  @Test void ordinaryServiceConstructorRegistersPolicyBeforeStartAndKeepsItAfterStop() throws Exception {
    var manager = new LogManager();
    manager.addAllowedDirectory(directory);
    try {
      var store = manager.stores().store(directory.resolve("store"));
      var budget = store.diskBudget();
      // A prior explicit service may have selected a lower reserve. Starting a default capture
      // raises the same owner; a separately created private monitor would leave this at zero.
      budget.configureCapture(new DiskSpaceBudgets.Policy(0, 0, 0));
      assertEquals(0, budget.snapshot().captureReserveBytes());
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", 9, "disk-wiring")),
          store.root(), .02, CapturePolicy.ALL, 0);
      try (var capture = new CaptureService(config, manager)) {
        assertEquals(2_147_483_648L, budget.snapshot().captureReserveBytes(),
            "The ordinary constructor must configure the shared store owner before capture starts");
      }
      assertEquals(2_147_483_648L, budget.snapshot().captureReserveBytes(),
          "Stopping a capture does not release its standing reserve");
    } finally {
      manager.shutdown();
    }
  }
}
