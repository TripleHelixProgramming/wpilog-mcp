/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;

class ReplayCaptureTest {
  @TempDir Path directory;
  @Test void reconnectBeforeReplayWaitsForTheNewSubscriptionsAnnouncements() throws Exception {
    var loop = new ManualScheduler();
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 1_000_000L, java.util.List.of(Nt4Client.V40))) {
      gateway.start().get(10, TimeUnit.SECONDS);
      gateway.announce("/one", "int", new com.google.gson.JsonObject()).get();
      gateway.announce("/two", "int", new com.google.gson.JsonObject()).get();
      try (var capture = new ReplayCapture(RobotAddress.uri("127.0.0.1", gateway.port(), "readiness"),
          directory.resolve("unused.wpilog"), directory.resolve("capture"), Clock.systemUTC(), null, 1L << 20, loop)) {
        capture.ready(2); assertEquals(2, capture.announcements.get());
        gateway.dropClients().get(10, TimeUnit.SECONDS); loop.until(() -> !capture.connected());
        assertEquals(0, capture.announcements.get(), "A disconnected generation cannot satisfy a new subscription");
        loop.advance(1_000_000);
        capture.ready(2); assertEquals(2, capture.announcements.get()); assertEquals(0, capture.received.get());
      }
    }
  }
}
