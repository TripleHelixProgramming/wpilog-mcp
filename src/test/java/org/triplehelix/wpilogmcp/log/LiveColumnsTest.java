/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.read.FieldPath;
import org.triplehelix.wpilogmcp.read.SecondsWindow;

/**
 * An open capture's index keeps its records outside the heap: the heap does not grow with the
 * session, a window reads only the blocks that hold it, and the columns' file lives as long as
 * the index may still be resumed.
 */
class LiveColumnsTest {
  @TempDir Path directory;

  private OpenColumns.Placement file(Path path) {
    return new OpenColumns.Placement() {
      @Override public Optional<Path> file(String captureIdentity) { return Optional.of(path); }
      @Override public boolean reserveShort() { return false; }
    };
  }

  private static long heapAfterCollection() {
    for (int i = 0; i < 3; i++) System.gc();
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  /** A view claims its WPILOG, which numbers never need read, so a header stands in for it. */
  private Path wpilog(String name) throws Exception {
    var path = directory.resolve(name);
    Files.write(path, new byte[12]);
    return path;
  }

  /** Numbers need no WPILOG to be read back, so the records' positions are made up here. */
  private static void append(LiveLog live, EntryInfo entry, long timeUs, long offset, double value) throws Exception {
    live.append(entry, new ValueFrame(entry.id(), timeUs, 1, value), new WpilogOutput.Written(offset, 17, offset + 9, 8));
  }

  /** Counted, not timed: an object for each record would keep about 520 MB for these values. */
  @Test
  void tenMillionValuesKeepTheHeapFlat() throws Exception {
    var live = new LiveLog(wpilog("flat.wpilog"), 0, () -> null, UUID.randomUUID().toString(),
        file(directory.resolve("flat-open.col")));
    var entries = new EntryInfo[100];
    for (int e = 0; e < entries.length; e++) {
      entries[e] = new EntryInfo(e + 1, "/e" + e, "double", "");
      live.announce(entries[e]);
    }
    long offset = 12, time = 0;
    // Every tail grown to a full block first, so only what grows with the session remains.
    for (int i = 0; i < 1024; i++, time += 20_000) for (var entry : entries) append(live, entry, time, offset += 17, i);
    long before = heapAfterCollection();
    // Checked as it goes, so a heap that grows fails at once instead of thrashing toward its limit.
    for (int quarter = 1; quarter <= 4; quarter++) {
      for (int i = 0; i < 25_000; i++, time += 20_000) for (var entry : entries) append(live, entry, time, offset += 17, i);
      long grown = heapAfterCollection() - before;
      assertTrue(grown < 16L << 20, "heap grew by " + grown + " bytes over " + quarter * 2.5 + " million values");
    }
    assertTrue(live.columnsInHeap().isEmpty(), "the file took every block");
    try (var view = live.retainView()) {
      assertEquals(101_024, view.sampleCount("/e0"));
      assertTrue(view.indexBytes() < 8L << 20, "index heap: " + view.indexBytes());
    }
    live.finish(); live.close(); live.discard();
  }

  /** Two hours of one 50 Hz entry: ten seconds read through its column decode a few dozen of its 704 blocks. */
  @Test
  void aTenSecondWindowReadsOnlyTheBlocksThatHoldIt() throws Exception {
    var live = new LiveLog(wpilog("window.wpilog"), 0, () -> null, UUID.randomUUID().toString(),
        file(directory.resolve("window-open.col")));
    var entry = new EntryInfo(1, "/v", "double", "");
    live.announce(entry);
    int records = 2 * 3600 * 50;
    for (int i = 0; i < records; i++) append(live, entry, i * 20_000L, 12 + 17L * i, i * 0.5);
    try (var view = live.retainView()) {
      var samples = view.numericColumn("/v", FieldPath.ROOT).orElseThrow().samples();
      long before = live.columnBlocksRead();
      var window = new SecondsWindow(3600.0, 3610.0);
      int from = window.from(samples), to = window.to(samples);
      double sum = 0;
      for (int i = from; i < to; i++) sum += samples.number(i);
      assertEquals(501, to - from);
      assertEquals(0.5 * (180_000 + 180_500) * 501 / 2, sum, 1e-6);
      long read = live.columnBlocksRead() - before;
      assertTrue(read <= 40, "blocks decoded for the window: " + read + " of " + (records / OpenColumns.BLOCK));
    } finally {
      live.finish(); live.close(); live.discard();
    }
  }

  /**
   * A client that sends integers on a double topic all session long makes every such record one
   * its column does not hold. Their list doubles as it fills: the writer copies it a few times a
   * session, not once a record, and each record is still read from where it is held.
   */
  @Test
  void offTypeRecordsGrowTheirListByDoubling() throws Exception {
    var live = new LiveLog(wpilog("odd.wpilog"), 0, () -> null, UUID.randomUUID().toString(),
        file(directory.resolve("odd-open.col")));
    var entry = new EntryInfo(1, "/odd", "double", "");
    live.announce(entry);
    int records = 100_000;
    for (int i = 0; i < records; i++) {
      boolean integer = i % 10 != 0;
      Object value = integer ? (Object) (long) i : (Object) (i * 0.5);
      live.append(entry, new ValueFrame(1, i * 1000L, integer ? 2 : 1, value),
          new WpilogOutput.Written(12 + 17L * i, 17, 12 + 17L * i + 9, 8));
    }
    assertTrue(live.otherListGrowths("/odd") <= 20, "the list was copied " + live.otherListGrowths("/odd") + " times");
    try (var view = live.retainView()) {
      assertEquals(records, view.sampleCount("/odd"));
      assertTrue(view.numericColumn("/odd", FieldPath.ROOT).isEmpty(), "a column that misses records serves no numeric read");
      var series = view.records("/odd", true);
      for (int i = 0; i < records; i++) {
        var value = series.decode(i);
        if (i % 10 == 0) assertEquals(i * 0.5, (Double) value, "record " + i + " comes from its column");
        else assertEquals((long) i, value, "record " + i + " is hot, as the frame carried it");
      }
    } finally {
      live.finish(); live.close(); live.discard();
    }
  }

  /** Retirement keeps the file, since the writer may resume the index; discarding it deletes the file after the last reader. */
  @Test
  void theFileOutlivesRetirementUntilTheIndexIsDiscarded() throws Exception {
    var wpilog = directory.resolve("resume.wpilog");
    var columns = directory.resolve("resume-open.col");
    var out = new WpilogOutput(wpilog);
    var live = new LiveLog(wpilog, 0, () -> null, UUID.randomUUID().toString(), file(columns));
    var entry = new EntryInfo(out.start("/v", "double", "", 0), "/v", "double", "");
    live.announce(entry);
    live.resume();
    for (int i = 0; i < 1500; i++) {
      var written = out.append(entry.id(), i * 1000L, WpilogOutput.payload(1, i * 0.25));
      live.append(entry, new ValueFrame(entry.id(), i * 1000L, 1, i * 0.25), written);
    }
    assertTrue(Files.size(columns) > 0);
    live.finish();
    var held = live.retainView();
    live.close();
    assertTrue(Files.exists(columns), "a retired index may be resumed");
    live.discard();
    assertTrue(Files.exists(columns), "a reader still holds the index");
    assertEquals(0.25 * 1499, (Double) held.values().get("/v").get(1499).value());
    held.close();
    assertFalse(Files.exists(columns), "the last reader gone, the discarded index's file is deleted");
    out.close();
  }

  /** A file left at the name is never read: the index keeps its blocks in the heap instead. */
  @Test
  void aLeftoverFileIsNeverRead() throws Exception {
    var columns = directory.resolve("leftover-open.col");
    var junk = new byte[] {9, 9, 9, 9};
    Files.write(columns, junk);
    var live = new LiveLog(wpilog("leftover.wpilog"), 0, () -> null, UUID.randomUUID().toString(), file(columns));
    var entry = new EntryInfo(1, "/v", "double", "");
    live.announce(entry);
    for (int i = 0; i < 1500; i++) append(live, entry, i * 1000L, 12 + 17L * i, i * 0.5);
    assertTrue(live.columnsInHeap().orElseThrow().contains("could not be written"), live.columnsInHeap().toString());
    try (var view = live.retainView()) {
      var values = view.values().get("/v");
      for (int i = 0; i < 1500; i++) assertEquals(i * 0.5, (Double) values.get(i).value());
    }
    assertArrayEquals(junk, Files.readAllBytes(columns), "the leftover is left alone");
    live.finish(); live.close();
  }
}
