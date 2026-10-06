/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.store.LogStore;

/** A real JVM exit releases ownership and loses a queued final manifest, as a daemon kill does. */
public final class CaptureRecoveryProcess {
  public static void main(String[] args) throws Exception {
    var path = Path.of(args[1]);
    if (args[0].equals("hold")) {
      try (var output = new WpilogOutput(path, 2, true)) {
        if (System.in.read() != 1) throw new AssertionError("Missing handshake");
        Files.createFile(Path.of(System.getProperty("user.home")).resolve("owned.signal"));
        System.in.read();
      }
      return;
    }
    var manager = LogManager.getInstance(); manager.addAllowedDirectory(path.getParent());
    var loop = new ManualScheduler(); var received = new AtomicInteger();
    var entered = new CountDownLatch(1); var neverReleased = new CountDownLatch(1);
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000)) {
      gateway.start().get(10, TimeUnit.SECONDS); gateway.announce("/x", "int", new JsonObject()).join();
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", gateway.port(), "timeout")),
          path, 0.001, CapturePolicy.ALL, 0);
      var service = new CaptureService(config, manager, CaptureRecoveryTest.WALL, loop,
          (file, id, resume) -> new WpilogOutput(file, id, resume) {
            @Override public Written append(int entry, long timestamp, byte[] payload) throws java.io.IOException {
              var written = super.append(entry, timestamp, payload); received.incrementAndGet(); return written;
            }
          }, Duration.ZERO);
      service.start(); gateway.value("/x", 10_000_000, 2, 1L).join(); loop.until(() -> received.get() == 1);
      gateway.value("/x", 12_000_000, 2, 2L).join(); loop.until(() -> received.get() == 2);
      var store = manager.stores().store(path);
      var fixture = path.getParent().resolve("queued-import.wpilog");
      try (var output = new WpilogWriter(fixture, "synthetic blocked queue")) {
        int id = output.start("/x", "int64", "", 0); output.append(id, 1, WpilogWriter.encodeInt64(1));
      }
      // Import is queued after the open manifest and holds the same queue as the close update.
      store.importPaths(new LogStore.Request(List.of(fixture), false, null), progress -> {
        if (!progress.phase().equals("starting")) return;
        entered.countDown();
        try { neverReleased.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      });
      if (!entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("Import never occupied the queue");
      if (!CaptureService.CLOSE_TIMEOUT.equals(Duration.ofSeconds(30))) throw new AssertionError("Expected a 30 second default shutdown bound");
      service.close(); loop.drain();
      // Do not drain the queue or run shutdown hooks: the daemon kill can lose that work too.
      Runtime.getRuntime().halt(0);
    }
  }
}
