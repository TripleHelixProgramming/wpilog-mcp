/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.util.datalog.DataLogReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

/** Selecting a window may inspect record headers, never copy excluded payloads to find it. */
class HeaderIndexTest {
  @TempDir Path temp;

  @Test void headerLookupsCrossWindowsWithoutReadingThePayload() throws Exception {
    var path = temp.resolve("header.wpilog");
    int offset = 4094;
    int payloadSize = 16_000;
    long timestampUs = -9_000_000_001L;
    var bytes = ByteBuffer.allocate(offset + 17 + payloadSize).order(ByteOrder.LITTLE_ENDIAN);
    bytes.position(offset).put((byte) 0x7f).putInt(1025).putInt(payloadSize).putLong(timestampUs);
    Files.write(path, bytes.array());
    try (var mapped = new MappedLogBytes(path, 4096)) {
      var counted = new CountingBytes(mapped);
      var reader = new LogReader(counted);
      assertEquals(timestampUs, reader.recordTimestampUs(offset));
      assertEquals(payloadSize, reader.recordPayloadSize(offset));
      assertEquals(1025, reader.recordEntry(offset));
      assertEquals(0, counted.views, "Header lookup must not request a record-sized view");
      assertTrue(counted.lastRead < offset + 17, "Only the 17 header bytes may be touched");
      assertTrue(counted.reads <= 3 * 34, "Header cost does not scale with the payload");
    }
    var legacy = LogReader.of(new DataLogReader(ByteBuffer.wrap(bytes.array())));
    assertEquals(timestampUs, legacy.recordTimestampUs(offset));
    assertEquals(payloadSize, legacy.recordPayloadSize(offset));
    assertEquals(1025, legacy.recordEntry(offset));
  }

  @Test void headerWidthsAndSignedTimestampsAgreeWithTheUpstreamReader() {
    for (int entryWidth = 1; entryWidth <= 4; entryWidth++) {
      for (int sizeWidth = 1; sizeWidth <= 4; sizeWidth++) {
        for (int timestampWidth = 1; timestampWidth <= 8; timestampWidth++) {
          int flags = entryWidth - 1 | (sizeWidth - 1) << 2 | (timestampWidth - 1) << 4;
          var bytes = ByteBuffer.allocate(17 + 3);
          bytes.put((byte) flags);
          long entry = entryWidth == 4 ? 0x80000001L : 1L << ((entryWidth - 1) * 8);
          long timestamp = timestampWidth == 8 ? Long.MIN_VALUE : 1L << ((timestampWidth - 1) * 8);
          putLittleEndian(bytes, entry, entryWidth);
          putLittleEndian(bytes, 3, sizeWidth);
          putLittleEndian(bytes, timestamp, timestampWidth);
          bytes.put(new byte[3]);
          byte[] encoded = Arrays.copyOf(bytes.array(), bytes.position());
          var reader = new LogReader(array(encoded));
          var reference = LogReader.of(new DataLogReader(ByteBuffer.wrap(encoded))).getRecord(0);
          assertEquals(reference.getEntry(), reader.recordEntry(0));
          assertEquals(reference.getTimestamp(), reader.recordTimestampUs(0));
          assertEquals(reference.getSize(), reader.recordPayloadSize(0));
          var framed = reader.getRecord(0);
          assertEquals(reference.getEntry(), framed.getEntry());
          assertEquals(reference.getTimestamp(), framed.getTimestamp());
          assertArrayEquals(reference.getRaw(), framed.getRaw());
        }
      }
    }
  }

  @Test void incompleteHeaderOrPayloadHasNoUsableHeaderAndWideLengthsStayUnsigned() {
    byte[] complete = {0, 1, 3, 2, 9, 8, 7};
    for (int length = 0; length < complete.length; length++) {
      var reader = new LogReader(array(Arrays.copyOf(complete, length)));
      assertThrows(NoSuchElementException.class, () -> reader.recordTimestampUs(0));
      assertThrows(NoSuchElementException.class, () -> reader.recordPayloadSize(0));
      assertThrows(NoSuchElementException.class, () -> reader.recordEntry(0));
      assertThrows(NoSuchElementException.class, () -> reader.getRecord(0));
    }
    long offset = (1L << 31) + 4094;
    var header = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        .put((byte) 0x7f).putInt(1).putInt(-1).putLong(-1).array();
    var source = new LogBytes() {
      public long size() { return offset + header.length + 0xffff_ffffL; }
      public byte get(long at) { return header[Math.toIntExact(at - offset)]; }
      public ByteBuffer view(long at, int length) { throw new AssertionError("A header needs no payload view"); }
    };
    var reader = new LogReader(source);
    assertEquals(0xffff_ffffL, reader.recordPayloadSize(offset));
    assertEquals(-1L, reader.recordTimestampUs(offset));
    assertEquals(1, reader.recordEntry(offset));
    assertThrows(ArithmeticException.class, () -> reader.getRecord(offset),
        "The complete-record size bound must be checked before requesting any payload view");
  }

  @Test void scanningALargeStraddlingPayloadReadsOnlyItsHeaderAndBoundedAnchors() throws Exception {
    var path = temp.resolve("large.wpilog");
    try (var writer = new WpilogWriter(path, "x".repeat(4050))) {
      int id = writer.start("/raw", "raw", "", 0);
      writer.append(id, -2, new byte[200_000]);
      writer.append(id, -2, new byte[200_000]);
    }
    try (var mapped = new MappedLogBytes(path, 4096)) {
      var counted = new CountingBytes(mapped);
      var scan = LogScan.of(new LogReader(counted), path);
      assertEquals(2, scan.dataRecords());
      assertFalse(scan.truncated());
      assertTrue(counted.largestView <= 65536, "Data records must not be copied to build their index");
      assertTrue(scan.offsets().get("/raw").monotonic(), "Duplicate negative timestamps stay ordered");
    }
  }

  @Test void monotonicityIsPerEntryAndResumeDoesNotMutateTheOldPrefix() throws Exception {
    var path = temp.resolve("resume.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int x = writer.start("/x", "double", "", 0);
      int y = writer.start("/y", "double", "", 0);
      writer.append(x, -2, WpilogWriter.encodeDouble(1));
      writer.append(x, 5, WpilogWriter.encodeDouble(2));
      writer.append(y, -100, WpilogWriter.encodeDouble(3));
      writer.append(x, 4, WpilogWriter.encodeDouble(4));
    }
    byte[] whole = Files.readAllBytes(path);
    var complete = scan(path);
    int split = Math.toIntExact(complete.offsets().get("/x").get(2));
    Files.write(path, Arrays.copyOf(whole, split));
    var before = scan(path);
    assertTrue(before.offsets().get("/x").monotonic());
    assertTrue(before.offsets().get("/y").monotonic());
    Files.write(path, Arrays.copyOfRange(whole, split, whole.length), StandardOpenOption.APPEND);
    try (var scoped = new ScopedLogReader(path, 64)) {
      var after = LogScan.resume(before, scoped.reader(), path);
      assertEquals(split, after.scannedFrom());
      assertFalse(after.offsets().get("/x").monotonic());
      assertTrue(after.offsets().get("/y").monotonic());
      assertTrue(before.offsets().get("/x").monotonic(), "The earlier reader owns an unchanged ordered prefix");
      assertEquals(2, before.offsets().get("/x").size());
      assertEquals(complete.offsets().get("/x").monotonic(), after.offsets().get("/x").monotonic());
    }
  }

  @Test void rollbackDropsTheInversionInsteadOfMakingTheRemainingEntryUnordered() throws Exception {
    var path = temp.resolve("rollback.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int id = writer.start("/x", "double", "", 0);
      writer.append(id, 100_000_000, WpilogWriter.encodeDouble(1));
      writer.append(id, 101_000_000, WpilogWriter.encodeDouble(2));
      writer.append(id, -100_000_000, WpilogWriter.encodeDouble(3));
      writer.append(id + 1, 102_000_000, WpilogWriter.encodeDouble(4));
    }
    var scan = scan(path);
    assertEquals(2, scan.dataRecords());
    assertTrue(scan.offsets().get("/x").monotonic());
  }

  @Test void compactOffsetsKeepOrderMetadataAcrossCopiesPopsAndLongPromotion() {
    var original = new RecordOffsets();
    original.add(12, Long.MIN_VALUE);
    original.add(16, -2);
    original.add(20, -2);
    assertTrue(original.monotonic());
    assertEquals(12, original.compact().storageBytes(), "Timestamp tracking adds no per-record array");
    var changed = original.copy();
    changed.add((1L << 31) + 2, -3);
    assertFalse(changed.monotonic());
    assertTrue(original.monotonic());
    assertEquals(32, changed.compact().storageBytes());
    changed.removeLast(-2);
    assertTrue(changed.monotonic());
    changed.add((1L << 31) + 4, -2);
    assertTrue(changed.monotonic());
    changed.add((1L << 31) + 8, -4);
    assertFalse(changed.monotonic());
    changed.removeLast();
    assertTrue(changed.monotonic(), "Popping the only inversion restores the ordered prefix");
    changed.add((1L << 31) + 12, -5);
    assertFalse(changed.monotonic(), "An append after an unknown previous timestamp cannot prove ordering");
  }

  @Test void legacyOffsetsWithoutTimestampsNeverClaimAnOrderedMultiRecordEntry() {
    var offsets = new RecordOffsets();
    offsets.add(12);
    assertTrue(offsets.monotonic());
    offsets.add(20);
    assertFalse(offsets.monotonic());
    offsets.removeLast();
    assertTrue(offsets.monotonic());
    offsets.add(24, -5);
    assertFalse(offsets.monotonic());
  }

  @Test void copiesRetainBothOrderedAndUnorderedTimestampHistory() {
    var ordered = new RecordOffsets();
    ordered.add(12, -2);
    ordered.add(16, -1);
    var continued = ordered.copy();
    continued.add(20, -1);
    ordered.add(20, -3);
    var unordered = ordered.copy();
    assertAll(
        () -> assertTrue(continued.monotonic(), "An ordered copy must remember its last timestamp"),
        () -> assertFalse(unordered.monotonic(), "An unordered copy must remember its first inversion"));
  }

  private static LogScan scan(Path path) throws Exception {
    try (var scoped = new ScopedLogReader(path, 64)) { return LogScan.of(scoped.reader(), path); }
  }

  private static void putLittleEndian(ByteBuffer bytes, long value, int length) {
    for (int i = 0; i < length; i++) bytes.put((byte) (value >>> (i * 8)));
  }

  private static LogBytes array(byte[] bytes) {
    return new LogBytes() {
      public long size() { return bytes.length; }
      public byte get(long offset) { return bytes[Math.toIntExact(offset)]; }
      public ByteBuffer view(long offset, int length) { return ByteBuffer.wrap(bytes).slice(Math.toIntExact(offset), length); }
    };
  }

  private static final class CountingBytes implements LogBytes {
    private final LogBytes delegate;
    long reads, lastRead = -1;
    int views, largestView;
    CountingBytes(LogBytes delegate) { this.delegate = delegate; }
    public long size() { return delegate.size(); }
    public byte get(long offset) { reads++; lastRead = Math.max(lastRead, offset); return delegate.get(offset); }
    public ByteBuffer view(long offset, int length) {
      views++; largestView = Math.max(largestView, length); return delegate.view(offset, length);
    }
  }
}
