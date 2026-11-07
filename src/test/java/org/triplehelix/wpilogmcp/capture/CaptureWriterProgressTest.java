/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

/** Health follows actual writer transitions and completed writes, not a predicted file path. */
class CaptureWriterProgressTest {
  @TempDir Path directory;
  private static final Clock WALL = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private static final Announce TOPIC = new Announce("/synthetic", 1, "double", null, new JsonObject());

  @Test void pendingAdmissionThenCompletedWriteAndDisconnectReportTheActualState() throws Exception {
    var loop = new ManualScheduler();
    var placed = new CompletableFuture<Path>();
    var observer = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { throw new AssertionError("Async admission only"); }
      @Override public CompletableFuture<Path> createAsync(String address, Instant start,
          CaptureWriter.Session previous, DeviceIdentity identity) { return placed; }
    };
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, observer)) {
      start(writer);
      assertEquals("waiting_admission", writer.progress(0, 0).state());
      assertNull(writer.progress(0, 0).lastWriteAgeMs(), "No file bytes have completed yet");
      loop.elapse(80_000);
      placed.complete(directory.resolve("recording.wpilog"));
      loop.drain();
      assertEquals("recording", writer.progress(0, 0).state());
      assertEquals(0.0, writer.progress(0, 0).lastWriteAgeMs(),
          "Admission replay uses actual completion time, not its buffered receipt time");
      loop.elapse(20_000);
      writer.value(TOPIC, new ValueFrame(1, 20_000, 1, 7.0), loop.nowUs());
      loop.elapse(125_000);
      assertEquals(125.0, writer.progress(0, 0).lastWriteAgeMs());
      writer.disconnected();
      assertEquals("stopped", writer.progress(0, 0).state());
      assertEquals("disconnected", writer.progress(0, 0).reason());
      assertFalse(writer.progress(0, 0).json().toString().contains(directory.toString()));
    }
  }

  @Test void admissionFailureDoesNotPublishItsPathIdentityOrSecretText() throws Exception {
    var loop = new ManualScheduler();
    var placed = new CompletableFuture<Path>();
    var observer = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { throw new AssertionError("Async admission only"); }
      @Override public CompletableFuture<Path> createAsync(String address, Instant start,
          CaptureWriter.Session previous, DeviceIdentity identity) { return placed; }
    };
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, observer)) {
      start(writer);
      placed.completeExceptionally(new IOException(directory + " robot-secret-serial key=synthetic-secret"));
      loop.drain();
      var result = writer.progress(0, 0);
      assertEquals("stopped", result.state());
      assertEquals("admission_failed", result.reason());
      assertNull(result.lastWriteAgeMs());
      String json = result.json().toString();
      for (String privateText : new String[] {directory.toString(), "robot-secret", "synthetic-secret", "key="})
        assertFalse(json.contains(privateText), "Public health leaked private failure text");
    }
  }

  @Test void lowDiskUnknownAndResumePublishTheActualStateAndCompletedWriteAge() throws Exception {
    var loop = new ManualScheduler();
    var count = new java.util.concurrent.atomic.AtomicInteger();
    var observer = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) {
        return directory.resolve("capture-" + count.getAndIncrement() + ".wpilog");
      }
    };
    var policy = new org.triplehelix.wpilogmcp.store.DiskSpaceBudgets.Policy(200, 100, 50);
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, observer)) {
      writer.diskSpace(space(500L), policy); start(writer);
      assertEquals("recording", writer.progress(0, 0).state());
      loop.elapse(50_000); writer.diskSpace(space(100L), policy);
      assertEquals("stopped", writer.progress(0, 0).state());
      assertEquals("low_disk", writer.progress(0, 0).reason());
      assertEquals(0.0, writer.progress(0, 0).lastWriteAgeMs(), "The clean close wrote end records now");
      loop.elapse(125_000); writer.diskSpace(space(null), policy);
      writer.timeSync(175_000, loop.nowUs());
      assertEquals("low_disk", writer.progress(0, 0).reason(), "Unknown space and another clock sample do not erase the pause");
      assertEquals(125.0, writer.progress(0, 0).lastWriteAgeMs());
      writer.diskSpace(space(150L), policy);
      assertEquals("low_disk", writer.progress(0, 0).reason(), "The resume threshold is strict");
      writer.diskSpace(space(151L), policy);
      assertEquals("recording", writer.progress(0, 0).state());
      assertNull(writer.progress(0, 0).reason());
      assertEquals(0.0, writer.progress(0, 0).lastWriteAgeMs());
      assertEquals(2, count.get(), "Resuming opens exactly one new file without a clock reset");
    }
  }

  @Test void synchronousAdmissionRefusalAndOutputFailureHaveDifferentPublicReasons() throws Exception {
    var loop = new ManualScheduler();
    var refusal = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) throws IOException {
        throw new IOException(directory + " private-refusal-key");
      }
    };
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, refusal)) {
      start(writer);
      assertEquals("stopped", writer.progress(0, 0).state());
      assertEquals("admission_failed", writer.progress(0, 0).reason());
      assertNull(writer.progress(0, 0).lastWriteAgeMs());
      assertFalse(writer.progress(0, 0).json().toString().contains("private-refusal-key"));
    }
    var admitted = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return directory.resolve("unopened.wpilog"); }
    };
    try (var writer = new CaptureWriter(WALL, new ManualScheduler(), CapturePolicy.ALL, admitted,
        CaptureWriter.DEFAULT_MAX_FILE_BYTES, (path, next, resume) -> { throw new IOException("private-output-key"); })) {
      start(writer);
      assertEquals("write_failed", writer.progress(0, 0).reason());
      assertNull(writer.progress(0, 0).lastWriteAgeMs());
      assertFalse(writer.progress(0, 0).json().toString().contains("private-output-key"));
    }
  }

  @Test void closingWithoutAnotherRecordDoesNotInventANewWriteTime() throws Exception {
    var loop = new ManualScheduler();
    var observer = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return directory.resolve("idle-close.wpilog"); }
    };
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, observer,
        CaptureWriter.DEFAULT_MAX_FILE_BYTES, (path, next, resume) -> new WpilogOutput(path, next, resume) {
          @Override public void close() throws IOException { loop.elapse(125_000); super.close(); }
        })) {
      start(writer); writer.unannounce(new org.triplehelix.wpilogmcp.nt4.ControlMessage.Unannounce(TOPIC.name(), TOPIC.id()));
      loop.elapse(500_000); writer.close();
      assertEquals("closed", writer.progress(0, 0).reason());
      assertEquals(625.0, writer.progress(0, 0).lastWriteAgeMs(),
          "Forcing and closing a file does not append a record or reset its last-write age");
    }
  }

  private static org.triplehelix.wpilogmcp.store.DiskSpaceBudgets.Snapshot space(Long bytes) {
    return new org.triplehelix.wpilogmcp.store.DiskSpaceBudgets.Snapshot("synthetic-filesystem",
        bytes == null ? org.triplehelix.wpilogmcp.store.DiskSpaceBudgets.State.UNKNOWN
            : org.triplehelix.wpilogmcp.store.DiskSpaceBudgets.State.AVAILABLE,
        bytes, 0, 200, 1);
  }

  private static void start(CaptureWriter writer) {
    writer.connected(java.net.URI.create("ws://synthetic.invalid:5810"), "v4.1.networktables.first.wpi.edu");
    writer.timeSync(0, 0);
    writer.announce(TOPIC);
  }
}
