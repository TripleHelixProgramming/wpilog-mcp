/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.config.ProviderConfig;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.ssh.JschConnection;
import org.triplehelix.wpilogmcp.ssh.SharedSsh;

class ContextProviderStateTest {
  @TempDir Path temp;
  @Test void unsupportedStatsStandDownAcrossAResumeAndRetryOnlyForANewSession() throws Exception {
    var manager = LogManager.getInstance(); var saved = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    try (var rio = new FakeRoboRio(temp.resolve("remote"), "STATS-STATE", "")) {
      var calls = new AtomicInteger(); var files = new AtomicInteger();
      rio.script(StatsCommand.sample(PullConfig.DISABLED.directories()), out -> {
        calls.incrementAndGet(); out.write("unsupported proc format\n".getBytes(StandardCharsets.UTF_8));
      });
      var loop = new ManualScheduler(); var worker = new ManualScheduler(); var connections = new ManualScheduler();
      var ssh = new PullConfig.Ssh("lvuser", "", null, false, rio.port());
      var address = RobotAddress.uri("127.0.0.1", 5810, "provider-state"); var wall = Clock.systemUTC();
      var config = new CaptureConfig(List.of(address), temp, .01, CapturePolicy.ALL, 0, 1 << 20,
          new PullConfig(false, PullConfig.DISABLED.directories(), 5_000_000, 1_000_000, ssh), 0,
          new ProviderConfig(true, new ProviderConfig.Stats(true, 2_000_000, 100_000), List.of()));
      var live = new LiveCapture(temp, loop);
      try (var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL,
          (host, start) -> temp.resolve("capture-" + files.incrementAndGet() + ".wpilog"))) {
        var pool = new SharedSsh(host -> CompletableFuture.completedFuture(null), JschConnection::connect, host -> connections);
        var providers = new ContextProviders(config, writer, live, loop, manager.stores().store(temp), wall, pool, worker);
        try {
          providers.connected("127.0.0.1"); connections.drain(); providers.start(); worker.drain(); loop.drain();
          assertEquals("stand_down", live.providers().get(0).state());
          assertTrue(live.providers().get(0).reason().contains("Unsupported SSH stats output"));
          loop.advance(10_000_000); worker.advance(10_000_000); assertEquals(1, calls.get());
          writer.connected(address, "networktables.first.wpi.edu"); writer.timeSync(20_000_000, loop.nowUs());
          writer.announce(new Announce("/anchor", 1, "int", null, new JsonObject())); providers.sessionChanged();
          var session = writer.session();
          loop.advance(2_000_000); worker.advance(2_000_000); assertEquals(1, calls.get());
          for (long serverTime : new long[] {23_000_000, 1_000_000}) {
            providers.disconnected(); writer.disconnected(); connections.advance(250_000); loop.elapse(1_000_000);
            writer.connected(address, "networktables.first.wpi.edu"); providers.connected("127.0.0.1");
            writer.timeSync(serverTime, loop.nowUs()); writer.announce(new Announce("/anchor", 1, "int", null, new JsonObject()));
            providers.sessionChanged(); connections.advance(250_000); worker.advance(250_000); loop.advance(0);
            assertEquals("stand_down", live.providers().get(0).state());
            if (serverTime > 1_000_000) { assertSame(session, writer.session()); assertEquals(1, calls.get()); }
            else { assertNotSame(session, writer.session()); assertEquals(2, calls.get()); }
          }
        } finally { var closed = providers.closeAsync(); connections.advance(250_000); assertTrue(closed.isDone()); }
      }
    } finally { manager.release(temp); manager.clearAllowedDirectories(); saved.forEach(manager::addAllowedDirectory); }
  }
}
