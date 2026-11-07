/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

class CaptureFlushTest {
  @TempDir Path directory;

  @Test void aSlowForceDoesNotDelayTheNextLoopTaskAndPublishesOnlyAfterCompletion() throws Exception {
    var loop = new ManualScheduler(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var forces = new AtomicInteger(); var published = new AtomicInteger(); var next = new CompletableFuture<Void>();
    var runner = Executors.newSingleThreadExecutor();
    var observer = new CaptureWriter.Observer() {
      public Path create(String address, java.time.Instant start) { return directory.resolve("capture.wpilog"); }
      public void flushed(CaptureWriter.Session session) { published.incrementAndGet(); }
    };
    try (var writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL, observer, 4096,
        (path, id, resume) -> new WpilogOutput(path, id, resume) {
          @Override public void flush() throws java.io.IOException {
            if (forces.incrementAndGet() == 1) {
              entered.countDown();
              try { assertTrue(release.await(5, TimeUnit.SECONDS), "Test releases the planted slow disk"); }
              catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
            }
            super.flush();
          }
        })) {
      writer.connected(URI.create("ws://127.0.0.1:5810/nt/test"), "test"); writer.timeSync(1_000_000, 0);
      var topic = new Announce("/x", 1, "int", null, new JsonObject()); writer.announce(topic);
      writer.value(topic, new ValueFrame(1, 1_000_000, 2, 7L), 0);
      loop.execute(() -> { writer.value(topic, new ValueFrame(1, 1_250_000, 2, 8L), 250_000); next.complete(null); });
      var tick = runner.submit(() -> loop.advance(250_000));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS)); loop.elapse(1_500_000);
        next.get(1, TimeUnit.SECONDS); tick.get(1, TimeUnit.SECONDS);
        assertEquals(0, published.get()); assertEquals(0, writer.session().observedAtUs());
        loop.advance(250_000); assertEquals(1, forces.get(), "No backlog of overlapping force calls");
      } finally { release.countDown(); tick.get(5, TimeUnit.SECONDS); }
      loop.until(() -> published.get() == 1);
      assertEquals(1, forces.get(), "Ticks during the pending force must not queue another one");
      assertEquals(2_000_000, writer.session().observedAtUs(), "The clock belongs to force completion");
      assertEquals(2, writer.session().statistics().records());
    } finally { release.countDown(); runner.shutdownNow(); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void closeAndRolloverWaitForThePendingForceBeforeClosingItsFile(boolean rollover) throws Exception {
    var loop = new ManualScheduler(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var forcing = new java.util.concurrent.atomic.AtomicBoolean(); var calls = new AtomicInteger();
    var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
    var executor = Executors.newSingleThreadExecutor(r -> { var thread = new Thread(r); worker.set(thread); return thread; });
    var observer = new CaptureWriter.Observer() {
      public Path create(String address, java.time.Instant start) { return directory.resolve("capture.wpilog"); }
    };
    try (var writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL, observer, 450,
        (path, id, resume) -> new WpilogOutput(path, id, resume) {
          @Override public void flush() throws java.io.IOException {
            if (calls.incrementAndGet() == 1) {
              forcing.set(true); entered.countDown();
              try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
              catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
              finally { forcing.set(false); }
            }
            super.flush();
          }
          @Override public void close() throws java.io.IOException {
            assertFalse(forcing.get(), "No close, final force, or rollover may race an outstanding force"); super.close();
          }
        })) {
      writer.connected(URI.create("ws://127.0.0.1:5810/nt/test"), "test"); writer.timeSync(1_000_000, 0);
      var topic = new Announce("/x", 1, "raw", null, new JsonObject()); writer.announce(topic);
      writer.value(topic, new ValueFrame(1, 1_000_000, 5, new byte[200]), 0);
      loop.advance(250_000); assertTrue(entered.await(5, TimeUnit.SECONDS));
      var before = writer.session().path();
      var closing = executor.submit(() -> {
        if (rollover) writer.value(topic, new ValueFrame(1, 1_250_000, 5, new byte[200]), 250_000);
        else writer.close();
      });
      try {
        // Wait for the actual barrier, not an arbitrary sleep or a slow test machine.
        org.triplehelix.wpilogmcp.harness.HarnessHttp.await("force barrier", 5, () -> closing.isDone()
            || java.util.Arrays.stream(worker.get().getStackTrace()).anyMatch(f -> f.getMethodName().equals("finishForce")));
        assertFalse(closing.isDone(), "The pending disk operation still owns the file");
      } finally { release.countDown(); }
      closing.get(5, TimeUnit.SECONDS); loop.drain();
      if (rollover) { assertNotEquals(before, writer.session().path()); assertEquals(2, writer.session().statistics().records()); }
      else assertFalse(writer.session().open());
    } finally { release.countDown(); executor.shutdownNow(); }
  }
}
