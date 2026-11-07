/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.client.*;

class CaptureSystemPullTest {
  @TempDir Path temp;
  @Test void systemFilesCanOptInWithoutEnablingRobotLogPulls() throws Exception {
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    try {
      var system = new SystemPullConfig(true, SystemPullConfig.Kernel.DMESG, List.of(), false, List.of(), List.of());
      var pull = new PullConfig(false, List.of(), 0, 1_000_000, PullConfig.DISABLED.ssh(), system);
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", 5810, "synthetic")), temp.resolve("store"), .02,
          CapturePolicy.ALL, 600_000_000, 1_000_000_000, pull, 0, ProviderConfig.DISABLED);
      var creates = new java.util.concurrent.atomic.AtomicInteger();
      try (var service = new CaptureService(config, manager, java.time.Clock.systemUTC(), new ManualScheduler(),
          WpilogOutput::new, Duration.ofSeconds(1), (settings, gate, store, wall, identity) -> { creates.incrementAndGet(); return null; })) {
        assertEquals(1, creates.get(), "The system-only opt-in must start the shared pull worker");
      }
    } finally { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
}
