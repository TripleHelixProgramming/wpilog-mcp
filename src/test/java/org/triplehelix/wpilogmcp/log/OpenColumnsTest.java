/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.columns.ColumnFileFormat.Kind;

/**
 * An open capture's columns read back every record exactly as the writer appended it, from its
 * file or from the heap, across blocks and a partial tail, out of time order and at every
 * publication bound; and a block the file cannot take stays in the heap, saying why.
 */
class OpenColumnsTest {
  @TempDir Path directory;

  /** One appended record, as the writer handed it over. */
  private record Appended(long sequence, long timeUs, long offset, int size, long bits) {}

  private static OpenColumns.Placement file(Path path, AtomicBoolean reserveShort) {
    return new OpenColumns.Placement() {
      @Override public Optional<Path> file(String captureIdentity) { return Optional.of(path); }
      @Override public boolean reserveShort() { return reserveShort.get(); }
    };
  }

  /** Values a column must keep to the bit: NaN payloads, signed zeros, extremes. */
  private static long bits(Kind kind, int i, Random random) {
    return switch (kind) {
      case F64 -> switch (i % 7) {
        case 0 -> 0x7ff8_0000_0000_0123L;
        case 1 -> Double.doubleToRawLongBits(-0.0);
        case 2 -> Double.doubleToRawLongBits(Double.MAX_VALUE);
        default -> Double.doubleToRawLongBits(random.nextGaussian());
      };
      case F32 -> (i % 5 == 0 ? 0x7fc0_0123 : Float.floatToRawIntBits((float) random.nextGaussian())) & 0xFFFF_FFFFL;
      case I64 -> i % 3 == 0 ? Long.MIN_VALUE + i : (1L << 53) + i;
      case BOOL -> i % 2;
      case ENUM -> throw new AssertionError();
    };
  }

  /** Appends {@code n} records to an entry of each kind, interleaved by one global sequence. */
  private static List<List<Appended>> fill(OpenColumns columns, List<OpenColumns.Series> series, int n) throws Exception {
    var random = new Random(5);
    var appended = new ArrayList<List<Appended>>();
    for (int s = 0; s < series.size(); s++) appended.add(new ArrayList<>());
    long sequence = 0, offset = 12;
    for (int i = 0; i < n; i++) {
      for (int s = 0; s < series.size(); s++) {
        var entry = series.get(s);
        sequence++;
        // Out of time order now and then, and sizes that vary for the entry without a value column.
        long time = 1_000_000L + i * 20_000L + (i % 97 == 50 ? -45_000 : 0);
        int size = entry.kind == null ? 3 + random.nextInt(40) : 8;
        long bits = entry.kind == null ? 0 : bits(entry.kind, i, random);
        entry.append(sequence, time, offset, size, bits);
        appended.get(s).add(new Appended(sequence, time, offset, size, bits));
        offset += 17 + size;
      }
    }
    return appended;
  }

  private static List<OpenColumns.Series> entries(OpenColumns columns) {
    var out = new ArrayList<OpenColumns.Series>();
    int id = 1;
    for (var type : List.of("double", "float", "int64", "boolean", "string")) {
      out.add(columns.new Series(new EntryInfo(id++, "/" + type, type, "")));
    }
    return out;
  }

  private static void assertReadsBack(List<OpenColumns.Series> series, List<List<Appended>> appended) {
    for (int s = 0; s < series.size(); s++) {
      var entry = series.get(s);
      var want = appended.get(s);
      for (int limit : new int[] {0, 1, 511, 512, 513, 1024, want.size()}) {
        if (limit > want.size()) continue;
        var reader = entry.reader(limit);
        assertEquals(limit, reader.size());
        // A second reader starts at the latest record, as a latest-value read does, so its first
        // block is decoded after it has read the tail.
        var latestFirst = entry.reader(limit);
        if (limit > 0) latestFirst.sequence(limit - 1);
        // Forward, backward and forward again: a block, the tail and a block again, each read
        // after another, so a decode can never write into the arrays the writer fills.
        for (int pass = 0; pass < 4; pass++) {
          var reading = pass == 3 ? latestFirst : reader;
          for (int k = 0; k < limit; k++) {
            int i = pass == 1 ? limit - 1 - k : k;
            var record = want.get(i);
            var where = entry.info.name() + " record " + i + " of " + limit + ", pass " + pass;
            assertEquals(record.sequence(), reading.sequence(i), where);
            assertEquals(record.timeUs(), reading.timeUs(i), where);
            assertEquals(record.offset(), reading.offset(i), where);
            assertEquals(record.size(), reading.payloadBytes(i), where);
            if (entry.kind != null) assertEquals(record.bits(), reading.bits(i), where);
          }
        }
      }
    }
  }

  @Test
  void everyRecordReadsBackFromTheFileAsWritten() throws Exception {
    var path = directory.resolve("capture-open.col");
    var columns = new OpenColumns(file(path, new AtomicBoolean()), "capture");
    var series = entries(columns);
    var appended = fill(columns, series, 1500);
    assertNull(columns.heapReason(), "the file takes every block");
    assertTrue(Files.size(path) > 0);
    assertReadsBack(series, appended);
    for (var entry : series) {
      assertEquals(entry.kind == null ? null : entry.kind, OpenColumns.kindOf(entry.info.type()));
      assertFalse(entry.monotonic(1500), "records 50, 147, ... were logged out of time order");
      assertTrue(entry.monotonic(50));
      assertTrue(entry.heapBytes() < 64 * 1024, "only the tail and block references stay in the heap: " + entry.heapBytes());
    }
    columns.close();
    assertFalse(Files.exists(path), "closing deletes the scratch file");
  }

  /**
   * A server sharing the cache deletes, as it starts, an open file last written before then, as an
   * idle index kept for resumption may be: only the name goes, and the writer's channel goes on
   * writing and reading the file.
   */
  @Test
  void aFileDeletedUnderTheWriterGoesOnServing() throws Exception {
    var path = directory.resolve("shared-open.col");
    var columns = new OpenColumns(file(path, new AtomicBoolean()), "capture");
    var entry = columns.new Series(new EntryInfo(1, "/v", "double", ""));
    int n = 3000;
    for (int i = 0; i < n; i++) {
      if (i == 1100) {
        try {
          Files.delete(path);
        } catch (java.nio.file.FileSystemException refused) {
          // A system that keeps an open file refuses, as the sweep's quiet delete then is.
        }
      }
      entry.append(i + 1, 1000L * i, 12 + 25L * i, 8, Double.doubleToRawLongBits(i * 0.5));
    }
    assertNull(columns.heapReason(), "the writer goes on writing to its channel");
    var reader = entry.reader(n);
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < n; i++) {
        assertEquals(i + 1, reader.sequence(i), "record " + i);
        assertEquals(1000L * i, reader.timeUs(i), "record " + i);
        assertEquals(12 + 25L * i, reader.offset(i), "record " + i);
        assertEquals(Double.doubleToRawLongBits(i * 0.5), reader.bits(i), "record " + i);
      }
    }
    columns.close();
    assertFalse(Files.exists(path));
  }

  @Test
  void everyRecordReadsBackFromTheHeapWhenThereIsNoFile() throws Exception {
    var columns = new OpenColumns(OpenColumns.Placement.HEAP, "capture");
    var series = entries(columns);
    var appended = fill(columns, series, 1500);
    assertNotNull(columns.heapReason());
    assertReadsBack(series, appended);
    long bytes = series.get(0).heapBytes();
    assertTrue(bytes > 1024 * 10 && bytes < 1500 * 32, "a double's packed blocks stay in the heap: " + bytes);
    columns.close();
  }

  @Test
  void eachBoundCountsTheRecordsPublishedByThen() throws Exception {
    var columns = new OpenColumns(file(directory.resolve("bound-open.col"), new AtomicBoolean()), "bound");
    var series = entries(columns);
    var appended = fill(columns, series, 1100);
    for (int s = 0; s < series.size(); s++) {
      var want = appended.get(s);
      for (long sequence = 0; sequence <= want.get(want.size() - 1).sequence() + 3; sequence += 7) {
        final long at = sequence;
        long expected = want.stream().filter(record -> record.sequence() <= at).count();
        assertEquals(expected, series.get(s).bound(sequence), series.get(s).info.name() + " at " + sequence);
      }
    }
    columns.close();
  }

  @Test
  void aShortReserveKeepsBlocksInTheHeapUntilItRecovers() throws Exception {
    var short_ = new AtomicBoolean(true);
    var path = directory.resolve("reserve-open.col");
    var columns = new OpenColumns(file(path, short_), "reserve");
    var entry = columns.new Series(new EntryInfo(1, "/d", "double", ""));
    var random = new Random(3);
    var want = new ArrayList<Long>();
    for (int i = 0; i < 2000; i++) {
      if (i == 700) short_.set(false);
      long bits = Double.doubleToRawLongBits(random.nextDouble());
      entry.append(i + 1, i * 1000L, 12 + 17L * i, 8, bits);
      want.add(bits);
      if (i == 600) assertTrue(columns.heapReason().contains("reserve"), columns.heapReason());
    }
    assertNull(columns.heapReason(), "the file takes blocks again once the reserve recovers");
    var reader = entry.reader(2000);
    for (int i = 0; i < 2000; i++) assertEquals(want.get(i), reader.bits(i), "record " + i);
    columns.close();
  }

  @Test
  void aFileThatCannotBeWrittenLeavesEveryLaterBlockInTheHeap() throws Exception {
    var missing = directory.resolve("no-such-folder").resolve("open.col");
    var columns = new OpenColumns(file(missing, new AtomicBoolean()), "failing");
    var entry = columns.new Series(new EntryInfo(1, "/i", "int64", ""));
    for (int i = 0; i < 1300; i++) entry.append(i + 1, i * 1000L, 12 + 17L * i, 8, -i);
    assertTrue(columns.heapReason().contains("could not be written"), columns.heapReason());
    var reader = entry.reader(1300);
    for (int i = 0; i < 1300; i++) assertEquals(-i, reader.bits(i));
    columns.close();
  }

  /** Readers racing the writer see, below each length they read, exactly what was appended. */
  @Test
  void readersRacingTheWriterSeeOnlyCompleteRecords() throws Exception {
    var columns = new OpenColumns(file(directory.resolve("race-open.col"), new AtomicBoolean()), "race");
    var entry = columns.new Series(new EntryInfo(1, "/d", "double", ""));
    var failure = new AtomicReference<Throwable>();
    var done = new AtomicBoolean();
    var readers = new ArrayList<Thread>();
    for (int r = 0; r < 3; r++) {
      var thread = new Thread(() -> {
        try {
          while (!done.get()) {
            int limit = entry.length();
            var reader = entry.reader(limit);
            for (int i = Math.max(0, limit - 700); i < limit; i++) {
              if (reader.sequence(i) != i + 1 || reader.timeUs(i) != i * 1000L || reader.bits(i) != Double.doubleToRawLongBits(i * 0.5))
                throw new AssertionError("record " + i + " of " + limit);
            }
          }
        } catch (Throwable t) {
          failure.compareAndSet(null, t);
        }
      });
      thread.start();
      readers.add(thread);
    }
    for (int i = 0; i < 200_000; i++) entry.append(i + 1, i * 1000L, 12 + 17L * i, 8, Double.doubleToRawLongBits(i * 0.5));
    done.set(true);
    for (var thread : readers) thread.join(10_000);
    if (failure.get() != null) throw new AssertionError(failure.get());
    columns.close();
  }
}
