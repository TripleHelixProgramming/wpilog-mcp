/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.FakeRobot;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

class SystemInventoryTest {
  @TempDir Path temp;
  @Test void threeHundredManifestsAreReadOnceAcrossTenIdlePasses() throws Exception {
    var root = temp.toRealPath().resolve("store");
    for (int i = 0; i < 300; i++) create(root, "session-" + i, i == 299, i + 1);
    var security = new SecurityValidator(); security.addAllowedDirectory(temp);
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(root); var reads = new AtomicInteger();
      var pull = new SystemPullStore(store, security, FakeRobot.device(SERIAL, "SHA256:fixture"), WALL,
          (io, path) -> { reads.incrementAndGet(); return io.read(path, StoreManifest.Session.class); });
      assertTrue(pull.beginPass()); assertEquals(300, reads.get());
      long start = System.nanoTime();
      for (int i = 0; i < 10; i++) assertTrue(pull.beginPass());
      double perPassMs = (System.nanoTime() - start) / 10_000_000.0;
      var report = Path.of("build/reports/round15/inventory-timing.txt"); Files.createDirectories(report.getParent());
      Files.writeString(report, "sessions=300 idle_passes=10 session_reads=" + reads.get() + " idle_pass_ms=" + perPassMs + "\n");
      assertEquals(300, reads.get(), "Ten idle passes must reuse unchanged manifests");
      // A placement changes the receipt; it is visible at the next pass without a timer.
      pull.kernel(java.util.List.of("[100] synthetic"), 110); assertTrue(pull.beginPass());
      assertEquals(301, reads.get()); assertEquals(1, pull.state().files().size());
      var before = root.resolve("robots").resolve(SERIAL).resolve("sessions/2026-03-07/session-299/session.json");
      store.capture(io -> { var old = io.read(before, StoreManifest.Session.class);
        io.write(before, new StoreManifest.Session(old.id(), old.startedAt(), old.endedAt(), old.startBasis(), null, null, null, null,
            java.util.List.of(), null, "closed", old.deviceIdentity(), java.util.List.of(), java.util.List.of(), old.captureStats(), old.systemLogs())); return null; });
      create(root, "next", true, 301); assertTrue(pull.beginPass());
      assertEquals("next", pull.sessionId()); assertEquals(303, reads.get());
    }
  }
}
