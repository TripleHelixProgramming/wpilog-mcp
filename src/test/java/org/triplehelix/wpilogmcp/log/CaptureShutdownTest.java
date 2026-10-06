/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.*;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.nt4.client.*;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.store.*;

class CaptureShutdownTest {
  @TempDir Path directory;

  @Test void shutdownWaitsForTheCloseManifestOutsideTheNt4Loop() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var importFile = directory.resolve("import.wpilog");
    try (var fixture = new WpilogWriter(importFile, "synthetic shutdown fixture")) {
      int id = fixture.start("/value", "int64", "", 0);
      fixture.append(id, 1_000_000, WpilogWriter.encodeInt64(1));
    }
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var threads = Executors.newSingleThreadExecutor();
    var loop = new ManualScheduler(); var fileClosed = new AtomicInteger(); var values = new AtomicInteger();
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000)) {
      gateway.start().get(10, TimeUnit.SECONDS); gateway.announce("/x", "int", new JsonObject()).join();
      var root = directory.resolve("store"); var store = manager.stores().store(root);
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", gateway.port(), "shutdown")),
          root, 0.001, CapturePolicy.ALL, 0, 256);
      var service = new CaptureService(config, manager, Clock.systemUTC(), loop,
          (path, id, resume) -> new WpilogOutput(path, id, resume) {
            @Override public Written append(int entry, long timestamp, byte[] payload) throws java.io.IOException {
              var written = super.append(entry, timestamp, payload); values.incrementAndGet(); return written;
            }
            @Override public void close() throws java.io.IOException { try { super.close(); } finally { fileClosed.incrementAndGet(); } }
          });
      try {
        service.start(); gateway.value("/x", 10_000_000, 2, 42L).join(); loop.until(() -> values.get() == 1);
        for (int i = 1; i <= 30; i++) gateway.value("/x", 10_000_000 + i, 2, (long) i).join();
        loop.until(() -> values.get() == 31);
        int alreadyClosed = fileClosed.get();
        var importing = store.importPaths(new LogStore.Request(List.of(importFile), false, null), progress -> {
          if (!progress.phase().equals("starting")) return;
          entered.countDown();
          try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        loop.advance(250_000);
        var closing = threads.submit(service::close);
        loop.until(() -> fileClosed.get() > alreadyClosed);
        try { assertThrows(TimeoutException.class, () -> closing.get(200, TimeUnit.MILLISECONDS)); }
        finally { release.countDown(); }
        closing.get(10, TimeUnit.SECONDS); importing.get(10, TimeUnit.SECONDS);
        var catalog = StoreCatalog.read(root, security);
        assertTrue(catalog.openCaptures().isEmpty());
        var captured = catalog.files().stream().filter(f -> f.file().provenance().kind().equals("captured")).toList();
        assertTrue(captured.size() > 2, "CaptureService must pass the configured file bound to its writer");
        var capturedValues = new java.util.TreeMap<Long, Long>();
        for (var file : captured) {
          assertNotNull(file.file().sha256()); assertTrue(file.file().sizeBytes() <= 256);
          try (var use = manager.acquire(file.path().toString())) {
            for (var value : use.log().values().get("NT:/x")) capturedValues.put(Math.round(value.timestamp() * 1_000_000), ((Number) value.value()).longValue());
          }
        }
        assertEquals(31, capturedValues.size()); assertEquals(42L, capturedValues.get(10_000_000L));
        for (int i = 1; i <= 30; i++) assertEquals((long) i, capturedValues.get(10_000_000L + i));
      } finally {
        release.countDown();
        var stopped = threads.submit(service::close); loop.drain(); stopped.get(20, TimeUnit.SECONDS);
      }
    } finally { release.countDown(); threads.shutdownNow(); manager.shutdown(); }
  }
}
