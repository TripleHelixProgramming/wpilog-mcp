/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;
import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A capture's output keeps its records in memory until a drain writes them at once. WPILib's own
 * reader checks the files, and an output that writes each record as it comes is the reference
 * for bytes and offsets.
 *
 * <p>WPILib's iterator ends a for-each loop once fewer than 16 bytes remain, which skips a short
 * last record, so the records are read with {@code forEachRemaining}, which reads to the end.
 */
class WpilogOutputBufferTest {
  @TempDir Path directory;

  private static byte[] raw(int size, int seed) {
    byte[] bytes = new byte[size];
    for (int i = 0; i < size; i++) bytes[i] = (byte) (seed + 31 * i);
    return bytes;
  }

  /** Records of every kind, one larger than a small buffer; each step's records and size. */
  private static List<Object> script(WpilogOutput out) throws IOException {
    var steps = new ArrayList<Object>();
    int a = out.start("/a", "int64", "", 1_000); steps.add(out.size());
    int b = out.start("/b", "raw", "{\"source\":\"test\"}", 1_000); steps.add(out.size());
    for (int i = 0; i < 40; i++) {
      steps.add(out.append(a, 1_000 + i * 20_000L, WpilogOutput.payload(2, i * 1_000_003L))); steps.add(out.size());
      if (i % 7 == 0) { steps.add(out.append(b, 1_000 + i * 20_000L, raw(i * 3, i))); steps.add(out.size()); }
    }
    out.setMetadata(b, "{\"source\":\"later\"}", 900_000); steps.add(out.size());
    steps.add(out.append(b, 900_000, raw(300, 9))); steps.add(out.size());
    steps.add(out.append(a, 900_001, WpilogOutput.payload(2, -5L))); steps.add(out.size());
    out.finish(a, 1_000_000); out.finish(b, 1_000_000); steps.add(out.size());
    return steps;
  }

  private static List<DataLogRecord> records(Path file) throws IOException {
    var records = new ArrayList<DataLogRecord>();
    new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file))).iterator().forEachRemaining(records::add);
    return records;
  }

  private static List<Long> integers(Path file, String name) throws IOException {
    var values = new ArrayList<Long>(); int id = -1;
    for (var record : records(file)) {
      if (record.isStart() && record.getStartData().name.equals(name)) id = record.getStartData().entry;
      else if (!record.isControl() && record.getEntry() == id) values.add(record.getInteger());
    }
    return values;
  }

  @ParameterizedTest @ValueSource(ints = {64, 1024, WpilogOutput.CAPTURE_BUFFER_BYTES})
  void aBufferedOutputWritesTheBytesAndOffsetsOfOneThatWritesEachRecord(int buffer) throws Exception {
    var direct = directory.resolve("direct.wpilog"); var buffered = directory.resolve("buffered.wpilog");
    List<Object> expected, actual;
    try (var out = new WpilogOutput(direct)) { expected = script(out); }
    try (var out = new WpilogOutput(buffered, 1, false, buffer)) { actual = script(out); }
    byte[] bytes = Files.readAllBytes(buffered);
    assertArrayEquals(Files.readAllBytes(direct), bytes);
    assertEquals(expected, actual, "Each record's offsets and each step's size");
    // Each record's payload is where its offsets say, and the records follow one another.
    long next = -1;
    for (var step : actual) {
      if (!(step instanceof WpilogOutput.Written written)) continue;
      if (next >= 0) assertTrue(written.offset() >= next);
      assertEquals(written.offset() + written.size(), written.payloadOffset() + written.payloadSize());
      next = written.offset() + written.size();
    }
    var large = (WpilogOutput.Written) actual.get(actual.size() - 5);
    assertArrayEquals(raw(300, 9), Arrays.copyOfRange(bytes, (int) large.payloadOffset(), (int) (large.payloadOffset() + 300)));
    var values = new ArrayList<Long>();
    for (int i = 0; i < 40; i++) values.add(i * 1_000_003L);
    values.add(-5L);
    assertEquals(values, integers(buffered, "/a"));
  }

  @Test void recordsReachTheFileWhenDrainedOrWhenTheBufferFills() throws Exception {
    var file = directory.resolve("drain.wpilog");
    try (var out = new WpilogOutput(file, 1, false, 256)) {
      assertEquals(12, Files.size(file)); assertEquals(12, out.size()); assertEquals(12, out.writtenBytes());
      int id = out.start("/a", "int64", "", 1_000_000);
      out.append(id, 1_000_000, WpilogOutput.payload(2, 1L));
      assertEquals(12, Files.size(file), "The records wait in memory");
      assertEquals(12, out.writtenBytes()); assertTrue(out.size() > 12);
      out.drain();
      assertEquals(out.size(), Files.size(file)); assertEquals(out.size(), out.writtenBytes());
      long before = out.size(); WpilogOutput.Written last;
      int count = 0;
      do { last = out.append(id, 2_000_000 + count, WpilogOutput.payload(2, 2L + count)); count++; }
      while (last.offset() + last.size() <= before + 256);
      assertEquals(last.offset(), Files.size(file), "A full buffer is written before the record that did not fit");
      assertEquals(last.offset(), out.writtenBytes());
      assertEquals(last.offset() + last.size(), out.size());
    }
    var values = integers(file, "/a");
    assertEquals(1L, values.get(0)); assertEquals(values.size() - 1L, values.get(values.size() - 1) - 1);
  }

  @Test void aFailedDrainLeavesTheFileAtItsLastWriteAndTheOutputGoesOn() throws Exception {
    var file = directory.resolve("failed.wpilog"); var armed = new AtomicBoolean();
    try (var out = new WpilogOutput(file, 1, false, 4096) {
      @Override protected void write(ByteBuffer bytes) throws IOException {
        if (armed.getAndSet(false)) {
          // Half the batch reaches the disk before it fills.
          var half = bytes.duplicate(); half.limit(half.position() + half.remaining() / 2); super.write(half);
          throw new IOException("planted disk full");
        }
        super.write(bytes);
      }
    }) {
      int id = out.start("/a", "int64", "", 1_000_000);
      out.append(id, 1_000_000, WpilogOutput.payload(2, 1L)); out.drain();
      byte[] durable = Files.readAllBytes(file);
      for (long v = 2; v <= 4; v++) out.append(id, v * 1_000_000, WpilogOutput.payload(2, v));
      armed.set(true);
      assertEquals("planted disk full", assertThrows(IOException.class, out::drain).getMessage());
      assertArrayEquals(durable, Files.readAllBytes(file), "Nothing of the failed batch stays in the file");
      assertEquals(durable.length, out.size()); assertEquals(durable.length, out.writtenBytes());
      assertEquals(3, out.lostRecords());
      var next = out.append(id, 5_000_000, WpilogOutput.payload(2, 5L));
      assertEquals(durable.length, next.offset(), "The next record starts where the file ends");
    }
    assertEquals(List.of(1L, 5L), integers(file, "/a"));
  }

  @Test void closeForcesWhatTheFileHoldsWhenTheLastWriteFails() throws Exception {
    var file = directory.resolve("close.wpilog"); var armed = new AtomicBoolean(); var forces = new AtomicInteger();
    var out = new WpilogOutput(file, 1, false, 4096) {
      @Override protected void write(ByteBuffer bytes) throws IOException {
        if (armed.getAndSet(false)) throw new IOException("planted disk full");
        super.write(bytes);
      }
      @Override public void flush() throws IOException { forces.incrementAndGet(); super.flush(); }
    };
    int id = out.start("/a", "int64", "", 1_000_000);
    out.append(id, 1_000_000, WpilogOutput.payload(2, 1L)); out.drain();
    out.append(id, 2_000_000, WpilogOutput.payload(2, 2L));
    armed.set(true);
    assertEquals("planted disk full", assertThrows(IOException.class, out::close).getMessage(), "The first failure is the one reported");
    assertEquals(1, forces.get(), "What the file holds is forced although the last write failed");
    assertEquals(List.of(1L), integers(file, "/a"));
    // The channel and the file's lease are released, so the file can be opened to write again.
    try (var again = new WpilogOutput(file, 2, true)) { assertEquals(Files.size(file), again.size()); }
  }

  @Test void aRecordLargerThanTheBufferFollowsTheRecordsWaitingForTheFile() throws Exception {
    var file = directory.resolve("large.wpilog");
    WpilogOutput.Written small, large, after;
    try (var out = new WpilogOutput(file, 1, false, 64)) {
      int id = out.start("/r", "raw", "", 1_000_000);
      small = out.append(id, 1_000_000, raw(8, 1));
      large = out.append(id, 2_000_000, raw(200, 2));
      assertEquals(out.size(), Files.size(file), "The large record went to the file, after the small one");
      after = out.append(id, 3_000_000, raw(8, 3));
    }
    assertEquals(small.offset() + small.size(), large.offset()); assertEquals(large.offset() + large.size(), after.offset());
    var payloads = new ArrayList<byte[]>();
    for (var record : records(file)) if (!record.isControl()) payloads.add(record.getRaw());
    assertEquals(3, payloads.size());
    assertArrayEquals(raw(8, 1), payloads.get(0)); assertArrayEquals(raw(200, 2), payloads.get(1)); assertArrayEquals(raw(8, 3), payloads.get(2));
  }

  @Test void aResumedOutputAppendsAfterTheBytesTheFileHolds() throws Exception {
    var file = directory.resolve("resumed.wpilog"); int next;
    try (var out = new WpilogOutput(file, 1, false, 1024)) {
      int id = out.start("/a", "int64", "", 1_000_000);
      out.append(id, 1_000_000, WpilogOutput.payload(2, 1L));
      next = out.nextEntry();
    }
    long closed = Files.size(file);
    try (var out = new WpilogOutput(file, next, true, 1024)) {
      assertEquals(closed, out.size()); assertEquals(closed, out.writtenBytes());
      int id = out.start("/a", "int64", "", 2_000_000);
      assertNotEquals(1, id);
      var written = out.append(id, 2_000_000, WpilogOutput.payload(2, 2L));
      assertEquals(closed + WpilogOutput.startSize("/a", "int64", "", 2_000_000), written.offset());
    }
    var values = new ArrayList<Long>();
    for (var record : records(file)) if (!record.isControl()) values.add(record.getInteger());
    assertEquals(List.of(1L, 2L), values);
  }
}
