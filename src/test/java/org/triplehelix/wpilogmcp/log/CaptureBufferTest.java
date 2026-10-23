/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CaptureIndex;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureService;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

/**
 * A capture keeps its records in memory until the flush tick writes them at once. The live index
 * holds a value until its record is in the file, the size the store publishes counts only the
 * file, and a write that fails takes the index with it, so no call answers from records the file
 * lost.
 */
class CaptureBufferTest {
  @TempDir Path directory;
  private static final Clock WALL = Clock.fixed(Instant.parse("2026-03-07T14:22:33Z"), ZoneOffset.UTC);
  private static final Announce RAW = new Announce("/r", 1, "raw", null, new JsonObject());

  private static byte[] raw(int seed) { return new byte[] {(byte) seed, (byte) (seed + 1), (byte) (seed + 2)}; }

  private static void connect(CaptureWriter writer, ManualScheduler loop) throws InterruptedException {
    writer.connected(URI.create("ws://127.0.0.1:5810/nt/test"), "test"); writer.timeSync(1_000_000, 0);
    writer.announce(RAW); loop.until(writer::ready);
  }

  /** WPILib's for-each loop skips a short last record; forEachRemaining reads to the end. */
  private static List<DataLogRecord> records(Path file) throws IOException {
    var records = new ArrayList<DataLogRecord>();
    new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file))).iterator().forEachRemaining(records::add);
    return records;
  }

  private static List<byte[]> payloads(Path file, String name) throws IOException {
    var values = new ArrayList<byte[]>(); int id = -1;
    for (var record : records(file)) {
      if (record.isStart() && record.getStartData().name.equals(name)) id = record.getStartData().entry;
      else if (!record.isControl() && record.getEntry() == id) values.add(record.getRaw());
    }
    return values;
  }

  /** A read that waits on an index it can no longer use fails here rather than hanging the suite. */
  private static List<byte[]> read(LogManager manager, Path file, String name) {
    return assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () -> {
      try (var use = manager.acquire(file.toString())) {
        var values = use.log().values().get(name);
        return values == null ? null : values.stream().map(v -> (byte[]) v.value()).toList();
      }
    }, "A read of the capture must not wait on its index");
  }

  private static CaptureWriter.Observer placement(Path file, AtomicInteger flushes) {
    return new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return file; }
      @Override public void flushed(CaptureWriter.Session session) { flushes.incrementAndGet(); }
    };
  }

  /** A capture's buffered output, whose next write fails once when armed. */
  private static CaptureWriter.OutputFactory failing(AtomicBoolean fail) {
    return (path, id, resume) -> new WpilogOutput(path, id, resume, WpilogOutput.CAPTURE_BUFFER_BYTES) {
      @Override protected void write(ByteBuffer bytes) throws IOException {
        if (fail.getAndSet(false)) throw new IOException("planted disk full");
        super.write(bytes);
      }
    };
  }

  private static List<Path> captures(Path store) {
    try (var files = Files.walk(store)) {
      return files.filter(f -> f.getFileName().toString().endsWith(".wpilog")).toList();
    } catch (IOException e) { return List.of(); }
  }

  private static int indexed(LogManager manager, Path file, String name) {
    try (var use = manager.acquire(file.toString())) {
      var values = use.log().values().get(name);
      return values == null ? 0 : values.size();
    } catch (IOException e) { return 0; }
  }

  /** The recorder the server runs, over a real NT4 connection, writes its records at the tick. */
  @Test void theServersRecorderWritesItsRecordsAtTheFlushTick() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var loop = new ManualScheduler(); var store = directory.resolve("store");
    try (var robot = new Nt4Gateway(new java.net.InetSocketAddress("127.0.0.1", 0), () -> 1_000_000)) {
      robot.start().get(30, TimeUnit.SECONDS); robot.announce("/r", "raw", new JsonObject()).join();
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", robot.port(), "buffer")), store, .01, CapturePolicy.ALL, 0);
      var service = new CaptureService(config, manager, WALL, loop);
      try {
        service.start();
        loop.until(() -> captures(store).size() == 1);
        var file = captures(store).get(0);
        robot.value("/r", 1_000_000, 5, raw(1)).join();
        loop.until(() -> indexed(manager, file, "NT:/r") == 1);
        assertEquals(12, Files.size(file), "The value is recorded, and its records wait for the flush tick");
        loop.advance(250_000);
        var recorded = payloads(file, "NT:/r");
        assertEquals(1, recorded.size()); assertArrayEquals(raw(1), recorded.get(0));
      } finally {
        // This thread is the service's loop, so it runs the loop's work while the service closes.
        var closing = java.util.concurrent.CompletableFuture.runAsync(service::close);
        while (!closing.isDone()) { loop.drain(); Thread.sleep(1); }
        closing.get();
      }
    }
  }

  @Test void aValueStaysInTheIndexUntilItsRecordIsInTheFile() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var file = directory.resolve("capture.wpilog"); var flushes = new AtomicInteger();
    var loop = new ManualScheduler(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var forces = new AtomicInteger();
    // A zero hot window lets every value go cold at the next flush, as far as its record allows.
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, new CaptureIndex(placement(file, flushes), manager, 0),
        CaptureWriter.DEFAULT_MAX_FILE_BYTES, (path, id, resume) -> new WpilogOutput(path, id, resume, WpilogOutput.CAPTURE_BUFFER_BYTES) {
          @Override public void flush() throws IOException {
            if (forces.incrementAndGet() == 1) {
              entered.countDown();
              try { assertTrue(release.await(10, TimeUnit.SECONDS), "The test releases the planted slow disk"); }
              catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
            super.flush();
          }
        })) {
      connect(writer, loop);
      writer.value(RAW, new ValueFrame(1, 1_000_000, 5, raw(1)), 0);
      assertEquals(12, Files.size(file), "The records wait in memory for the flush tick");
      assertEquals(12, writer.session().sizeBytes(), "The size counts only what the file holds");
      try {
        loop.advance(250_000); assertTrue(entered.await(10, TimeUnit.SECONDS));
        long drained = Files.size(file);
        assertTrue(drained > 12, "The tick wrote its records before forcing them");
        // This value's record is in the buffer only when the force completes and values go cold.
        writer.value(RAW, new ValueFrame(1, 1_250_000, 5, raw(2)), 250_000);
        assertEquals(drained, Files.size(file)); assertEquals(drained, writer.session().sizeBytes());
        release.countDown();
        loop.until(() -> flushes.get() == 1 || !writer.session().open());
        assertTrue(writer.session().open(), "No value goes cold before its record is in the file: " + writer.session().endReason());
        assertEquals(drained, writer.session().sizeBytes());
        var values = read(manager, file, "NT:/r");
        assertEquals(2, values.size()); assertArrayEquals(raw(1), values.get(0)); assertArrayEquals(raw(2), values.get(1));
      } finally { release.countDown(); }
      loop.advance(250_000); loop.until(() -> flushes.get() == 2 || !writer.session().open());
      assertTrue(writer.session().open(), writer.session().endReason());
      assertEquals(Files.size(file), writer.session().sizeBytes(), "A flush publishes the size the tick wrote");
      var values = read(manager, file, "NT:/r");
      assertEquals(2, values.size()); assertArrayEquals(raw(2), values.get(1));
      var recorded = payloads(file, "NT:/r");
      assertEquals(2, recorded.size()); assertArrayEquals(raw(2), recorded.get(1));
    }
  }

  @Test void aFailedWriteOfTheBufferEndsTheSessionAndCallsReadTheFile() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var file = directory.resolve("capture.wpilog"); var flushes = new AtomicInteger(); var fail = new AtomicBoolean();
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL,
        new CaptureIndex(placement(file, flushes), manager, 600_000_000), CaptureWriter.DEFAULT_MAX_FILE_BYTES, failing(fail))) {
      connect(writer, loop);
      writer.value(RAW, new ValueFrame(1, 1_000_000, 5, raw(1)), 0);
      loop.advance(250_000); loop.until(() -> flushes.get() == 1);
      var later = new Announce("/s", 2, "raw", null, new JsonObject()); writer.announce(later);
      writer.value(RAW, new ValueFrame(1, 1_100_000, 5, raw(2)), 100_000);
      writer.value(later, new ValueFrame(2, 1_100_000, 5, raw(3)), 100_000);
      assertEquals(2, read(manager, file, "NT:/r").size(), "The index holds the values the buffer holds");
      fail.set(true);
      loop.advance(250_000);
      assertFalse(writer.session().open()); assertTrue(writer.session().writeFailed());
      assertEquals("Capture write failed: planted disk full", writer.session().endReason());
      // The file ends where its last write ended, and no record names an entry whose start it lost.
      var started = new HashSet<Integer>(); var names = new HashSet<String>();
      for (var record : records(file)) {
        if (record.isStart()) { started.add(record.getStartData().entry); names.add(record.getStartData().name); }
        else if (record.isFinish()) assertTrue(started.contains(record.getFinishEntry()), "A finish for an entry the file never started");
        else if (!record.isControl()) assertTrue(started.contains(record.getEntry()));
      }
      assertFalse(names.contains("NT:/s"));
      var recorded = payloads(file, "NT:/r");
      assertEquals(1, recorded.size()); assertArrayEquals(raw(1), recorded.get(0));
      var values = read(manager, file, "NT:/r");
      assertEquals(1, values.size(), "A call reads the file, not the index of what the file lost");
      assertArrayEquals(raw(1), values.get(0));
      assertNull(read(manager, file, "NT:/s"));
    }
  }

  @Test void aFailedWriteAtCloseTakesTheIndexWithIt() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var file = directory.resolve("capture.wpilog"); var flushes = new AtomicInteger(); var fail = new AtomicBoolean();
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL,
        new CaptureIndex(placement(file, flushes), manager, 600_000_000), CaptureWriter.DEFAULT_MAX_FILE_BYTES, failing(fail))) {
      connect(writer, loop);
      writer.value(RAW, new ValueFrame(1, 1_000_000, 5, raw(1)), 0);
      loop.advance(250_000); loop.until(() -> flushes.get() == 1);
      writer.value(RAW, new ValueFrame(1, 1_100_000, 5, raw(2)), 100_000);
      fail.set(true);
      writer.disconnected();
      assertFalse(writer.session().open()); assertTrue(writer.session().writeFailed());
      assertEquals("Capture write failed: planted disk full", writer.session().endReason());
      assertEquals(Files.size(file), writer.session().sizeBytes());
      var values = read(manager, file, "NT:/r");
      assertEquals(1, values.size(), "The closed capture's index must not answer for the lost record");
      assertArrayEquals(raw(1), values.get(0));
    }
  }
}
