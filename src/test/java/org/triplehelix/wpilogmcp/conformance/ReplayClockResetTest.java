/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;

/** A clock reset crosses a real socket disconnect; neither the writer nor client is replaced. */
class ReplayClockResetTest {
  @TempDir Path directory;

  @Test void twoBootsMakeSeparateSessionsAndNeverCrossTheirPullMatches() throws Exception {
    var kind = ReplaySource.Kind.ADVANTAGEKIT;
    try (var first = new ReplaySource(ReplayPullTest.fixture(directory.resolve("first"), kind, 0));
         var second = new ReplaySource(ReplayPullTest.fixture(directory.resolve("second"), kind, 1))) {
      pair(first, second, directory, false);
    }
  }

  static java.util.List<Map<String, Object>> pair(ReplaySource first, ReplaySource second, Path directory, boolean nativeServer) throws Exception {
    var results = new java.util.ArrayList<Map<String, Object>>();
    try (var remote = new ReplayPull(first, directory.resolve("rio"))) {
      var robot = new AtomicLong(first.minUs); var loop = new ManualScheduler();
      var wall = new ReplayClock(first.calendar().orElseThrow().start(), first.minUs);
      try (var gateway = nativeServer ? null : new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), robot::get,
          java.util.List.of(org.triplehelix.wpilogmcp.nt4.client.Nt4Client.V40))) {
        if (gateway != null) gateway.start().get(10, TimeUnit.SECONDS);
        int port = nativeServer ? NativeReplayProcess.freePort() : gateway.port();
        var a = nativeServer ? null : new LogReplayer(first, gateway, robot::set);
        if (a != null) a.announce();
        try (var nativeFirst = nativeServer ? new NativeReplayProcess(first, 0, directory.resolve("boot-1"), port) : null;
             var capture = new ReplayCapture(RobotAddress.uri("127.0.0.1", port, "replay"), first.path,
                 directory.resolve("store"), wall, remote.device, 1L << 30, loop)) {
          capture.manager.addAllowedDirectory(second.path.getParent());
          capture.ready(nativeServer ? nativeFirst.topics : a.topicCount());
          if (nativeServer) nativeFirst.consume(capture);
          else a.replay(0, 0, ignored -> fail("Fast replay paced"), capture::receivedThrough, capture::propertiesThrough);
          robot.set(first.maxUs);
          if (nativeServer) for (int i = 0; i < 15; i++) { loop.advance(200_000); loop.receive(); }
          else loop.advance(3_000_000);
          loop.until(() -> capture.synchronizedServerUs.get() == first.maxUs);
          if (nativeServer) nativeFirst.finish(); else gateway.dropClients().get(10, TimeUnit.SECONDS);
          loop.until(() -> !capture.connected());
          if (a != null) a.unannounce(); robot.set(second.minUs);
          wall.reset(second.calendar().orElseThrow().start(), second.minUs);
          var b = nativeServer ? null : new LogReplayer(second, gateway, robot::set);
          if (b != null) b.announce();
          long received = capture.received.get(), properties = capture.receivedProperties.get();
          try (var nativeSecond = nativeServer ? new NativeReplayProcess(second, 0, directory.resolve("boot-2"), port) : null) {
            loop.advance(1_000_000);
            capture.ready(nativeServer ? nativeSecond.topics : b.topicCount());
            if (nativeServer) nativeSecond.consume(capture);
            else b.replay(0, 0, ignored -> fail("Fast replay paced"), count -> capture.receivedThrough(received + count),
                count -> capture.propertiesThrough(properties + count));
            capture.stop(); if (nativeServer) nativeSecond.finish();
          }
          assertEquals(2, capture.sessions.size(), first.path.toString());
          for (int boot = 0; boot < 2; boot++) {
            var source = boot == 0 ? first : second; var session = capture.sessions.get(boot);
            var files = session.files().stream().map(f -> session.path().resolveSibling(f.name())).toList();
            assertEquals(source.calendar().orElseThrow().start(), session.startedAt(), source.path.toString());
            var row = new ReplayAudit().verify(source, capture, 0, files, session);
            if (boot == 1) Files.copy(second.path, remote.rio.logs().resolve(second.path.getFileName()));
            row.put("pull", remote.verify(source, capture, wall, 0, files.get(0)));
            RealNtcoreReplayTest.checkResult(source.path, 0, row);
            results.add(row);
          }
        }
      }
    }
    return results;
  }
}
