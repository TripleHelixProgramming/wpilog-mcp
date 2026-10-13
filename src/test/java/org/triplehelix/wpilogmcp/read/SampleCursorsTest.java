/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import edu.wpi.first.util.datalog.DataLogReader;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.DecodeProblem;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogBytes;
import org.triplehelix.wpilogmcp.log.LogReader;

/** Independent record functions pin selection; source guards prevent a second decoding loop. */
class SampleCursorsTest {
  @TempDir Path temporary;

  @Test void onlySelectedRecordsDecodeAcrossDisjointWindowsAndDuplicateTimestamps() throws Exception {
    var source = new Series(-10, 5, 5, 2, 12);
    var query = new ReadQuery("/x", List.of(new ReadQuery.Window(2, 5, false),
        new ReadQuery.Window(12, 12, true)), ReadQuery.Order.TIME, 2, 16);
    try (var cursor = SampleCursors.open(source, query, new int[]{0, 3, 1, 2, 4}, 100)) {
      var batch = cursor.next();
      assertEquals(List.of(3, 4), batch.samples().stream().map(Sample::ordinal).toList());
      assertEquals(List.of(6, 8), batch.samples().stream().map(Sample::value).toList());
      assertEquals(List.of(3, 4), source.decoded);
      assertEquals(2, batch.recordsVisited());
      assertEquals(16, batch.payloadBytes());
      assertTrue(batch.complete());
    }
  }

  @Test void malformedRecordsCountAsWorkWithoutErasingTheirOriginalPositions() throws Exception {
    var source = new Series(0, 1, 2);
    source.failed = 1;
    var query = new ReadQuery("/x", List.of(), ReadQuery.Order.FILE, 2, 16);
    try (var cursor = SampleCursors.open(source, query, null, 100)) {
      var first = cursor.next();
      assertEquals(List.of(0), first.samples().stream().map(Sample::ordinal).toList());
      assertEquals(1, first.problems().size());
      assertEquals(28, first.problems().get(0).recordOffset());
      assertEquals("synthetic record 1 failed", first.problems().get(0).message());
      assertEquals(2, first.recordsVisited());
      assertEquals(16, first.payloadBytes());
      assertFalse(first.complete());
      var last = cursor.next();
      assertEquals(List.of(2), last.samples().stream().map(Sample::ordinal).toList());
      assertEquals(List.of(4), last.samples().stream().map(Sample::value).toList());
      assertTrue(last.complete());
    }
  }

  @Test void unreadableHeadersUseTheSameProblemPolicyAndDoNotHideFollowingSamples() throws Exception {
    for (boolean failTimestamp : new boolean[]{true, false}) {
      Series source = new Series(0, 1, 2) {
        @Override public long timestampUs(int index) {
          if (failTimestamp && index == 1) throw new IllegalArgumentException("timestamp header failed");
          return super.timestampUs(index);
        }
        @Override public long payloadBytes(int index) {
          if (!failTimestamp && index == 1) throw new IllegalArgumentException("size header failed");
          return super.payloadBytes(index);
        }
      };
      var query = new ReadQuery("/x", List.of(), ReadQuery.Order.FILE, 3, 24);
      try (var cursor = SampleCursors.open(source, query, null, 100)) {
        var batch = cursor.next();
        assertEquals(List.of(0, 2), batch.samples().stream().map(Sample::ordinal).toList());
        assertEquals(List.of(0, 4), batch.samples().stream().map(Sample::value).toList());
        assertEquals(List.of(new ReadProblem(ReadProblem.Code.MALFORMED_RECORD, "/x", 28,
            "synthetic record 1 failed")), batch.problems());
        assertEquals(3, batch.recordsVisited());
        assertEquals(16, batch.payloadBytes(), "An unreadable header contributes no guessed payload size");
        assertEquals(List.of(0, 2), source.decoded);
        assertTrue(batch.complete());
      }
    }
  }

  @Test void aChangedIndexedHeaderKeepsLegacyNullRecordDiagnosticAndContinues() throws Exception {
    var path = temporary.resolve("header-changed.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int entry = writer.start("/number", "int64", "", 0);
      writer.append(entry, 1, WpilogWriter.encodeInt64(10));
      writer.append(entry, 2, WpilogWriter.encodeInt64(20));
      writer.append(entry, 3, WpilogWriter.encodeInt64(30));
    }
    byte[] bytes = Files.readAllBytes(path);
    var source = new LogBytes() {
      public long size() { return bytes.length; }
      public byte get(long offset) { return bytes[Math.toIntExact(offset)]; }
      public ByteBuffer view(long offset, int length) {
        return ByteBuffer.wrap(bytes).slice(Math.toIntExact(offset), length);
      }
    };
    try (var log = new LazyParsedLog(path.toString(), new LogReader(source), 1 << 20)) {
      long corruptOffset = log.records("/number", false).offset(1);
      // This writer uses one byte each for this entry id and payload size. Changing only the
      // size after indexing models a rewrite; the following record remains at its old offset.
      bytes[Math.toIntExact(corruptOffset) + 2] = (byte) 255;
      assertEquals(List.of(10L, 30L), log.values().get("/number").stream().map(v -> v.value()).toList());
      assertEquals(new DecodeProblem("record could not be read as int64 (Incomplete WPILOG record at byte "
          + corruptOffset + ")", 1, 3), log.decodeProblem("/number").orElseThrow(),
          "Header failure must not borrow the preceding valid record's eight-byte size");
    }
  }

  @Test void byteBudgetAndFileOrderFilteringRemainBoundedWithoutDecodingExcludedValues() throws Exception {
    var source = new Series(0, 1, 2, 3);
    var query = new ReadQuery("/x", List.of(new ReadQuery.Window(1, 3, true)), ReadQuery.Order.FILE, 2, 8);
    try (var cursor = SampleCursors.open(source, query, null, 100)) {
      var first = cursor.next();
      assertEquals(List.of(1), first.samples().stream().map(Sample::ordinal).toList());
      assertEquals(2, first.recordsVisited());
      assertEquals(8, first.payloadBytes());
      assertFalse(first.complete());
      assertEquals(List.of(2), cursor.next().samples().stream().map(Sample::ordinal).toList());
      var last = cursor.next();
      assertEquals(List.of(3), last.samples().stream().map(Sample::ordinal).toList());
      assertTrue(last.complete());
      assertEquals(List.of(1, 2, 3), source.decoded);
    }
  }

  @Test void sizeRefusalHappensBeforeDecodeAndCloseCancelsTheCursor() throws Exception {
    var source = new Series(0);
    var cursor = SampleCursors.open(source, ReadQuery.all("/x"), null, 7);
    assertEquals(ReadProblem.Code.RESOURCE_LIMIT, assertThrows(ReadException.class, cursor::next).code());
    assertTrue(source.decoded.isEmpty());
    cursor.close();
    assertEquals(ReadProblem.Code.CLOSED, assertThrows(ReadException.class, cursor::next).code());
  }

  @Test void aSourceMayRefuseToSkipAnExceptionAsTheLiveCompatibilityViewDoes() throws Exception {
    var source = new Series(0);
    source.failed = 0;
    source.rethrow = true;
    try (var cursor = SampleCursors.open(source, ReadQuery.all("/x"), null, 100)) {
      assertEquals("synthetic failure", assertThrows(IllegalArgumentException.class, cursor::next).getMessage());
    }
  }

  @Test void legacyMaterializationKeepsWholeEntryDiagnosticsAndDoubleTimestampTies() {
    var source = new Series(9_007_199_254_740_993L, 9_007_199_254_740_992L, 9_007_199_254_740_994L);
    source.failed = 2;
    var result = LegacyMaterializer.read("/x", source);
    assertEquals(List.of(0, 2), result.values().stream().map(v -> v.value()).toList(),
        "Distinct integer times that round to the same legacy double keep their file order");
    assertEquals(result.values().get(0).timestamp(), result.values().get(1).timestamp());
    assertEquals(1, result.problem().failedRecords());
    assertEquals(3, result.problem().totalRecords());
    assertEquals("synthetic record 2 failed", result.problem().message());
    assertEquals(List.of(0, 1, 2), source.decoded);
  }

  @Test void legacyMaterializationStillUsesStableChronologicalOrderForOrdinaryTimes() {
    var result = LegacyMaterializer.read("/x", new Series(3_000_000, 1_000_000, 1_000_000));
    assertEquals(List.of(2, 4, 0), result.values().stream().map(v -> v.value()).toList());
    assertEquals(List.of(1.0, 1.0, 3.0), result.values().stream().map(v -> v.timestamp()).toList());
    assertNull(result.problem());
  }

  @Test void aRealFileKeepsLegacyUnknownTypePreviewsBesideExactSourceBytes() throws Exception {
    var path = temporary.resolve("opaque.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int entry = writer.start("/opaque", "future-format", "", 0);
      writer.append(entry, 1, new byte[]{1, 2, 3});
      writer.append(entry, 2, new byte[101]);
    }
    try (var log = new LazyParsedLog(path.toString(), new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(path))), 1 << 20)) {
      var legacy = LegacyMaterializer.read("/opaque", log.records("/opaque", false));
      assertEquals(List.of("010203", "<101 bytes>"), legacy.values().stream().map(v -> v.value()).toList());
      assertNull(legacy.problem());
      var source = log.records("/opaque", true);
      assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) source.decode(0));
      assertArrayEquals(new byte[101], (byte[]) source.decode(1));
    }
  }

  @Test void realMalformedPrimitiveAndStructDiagnosticsStayByteForByteCompatible() throws Exception {
    var path = temporary.resolve("malformed.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int schema = writer.start("/.schema/struct:Tiny", "structschema", "", 0);
      writer.append(schema, 0, WpilogWriter.encodeString("double x;"));
      int number = writer.start("/number", "double", "", 0);
      int structure = writer.start("/structure", "struct:Tiny", "", 0);
      writer.append(number, 2_000_000, WpilogWriter.encodeDouble(6));
      writer.append(number, 1_000_000, new byte[2]);
      writer.append(structure, 1_000_000, new byte[4]);
    }
    try (var log = new LazyParsedLog(path.toString(), new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(path))), 1 << 20)) {
      assertEquals(List.of(6.0), log.values().get("/number").stream().map(v -> v.value()).toList());
      assertEquals(new DecodeProblem("record of 2 bytes cannot be read as double", 1, 2),
          log.decodeProblem("/number").orElseThrow());
      var structure = LegacyMaterializer.read("/structure", log.records("/structure", false));
      assertTrue(structure.values().isEmpty());
      assertEquals(new DecodeProblem("record is 4 bytes; the Tiny schema (logged) is 8 bytes", 1, 1), structure.problem());
    }
  }

  @Test void fullMaterializationFramesEachRecordOnlyOnce() throws Exception {
    var path = temporary.resolve("one-framing.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int entry = writer.start("/number", "int64", "", 0);
      for (int i = 1; i <= 3; i++) writer.append(entry, i, WpilogWriter.encodeInt64(10L * i));
    }
    byte[] bytes = Files.readAllBytes(path);
    var headerReads = new java.util.concurrent.atomic.AtomicInteger();
    var viewBytes = new java.util.concurrent.atomic.AtomicInteger();
    var source = new LogBytes() {
      public long size() { return bytes.length; }
      public byte get(long offset) { headerReads.incrementAndGet(); return bytes[Math.toIntExact(offset)]; }
      public ByteBuffer view(long offset, int length) {
        viewBytes.addAndGet(length);
        return ByteBuffer.wrap(bytes).slice(Math.toIntExact(offset), length);
      }
    };
    try (var log = new LazyParsedLog(path.toString(), new LogReader(source), 1 << 20)) {
      headerReads.set(0);
      viewBytes.set(0);
      try (var reads = new edu.wpi.first.util.datalog.RecordReads()) {
        assertEquals(List.of(10L, 20L, 30L), log.values().get("/number").stream().map(v -> v.value()).toList());
        assertEquals(36, reads.bytes(), "Read instrumentation must still include the complete records");
      }
      assertEquals(24, viewBytes.get(), "Only three eight-byte payloads need buffer views;"
          + " already-read headers must not be sliced and framed again by a second reader");
      // One byte each for flags, entry id, size and timestamp. No header is read twice.
      assertEquals(12, headerReads.get(), "Three records need one framing pass each");
    }
  }

  @Test void directConsumptionKeepsFileOrderAndTheSameMalformedRecordPolicy() throws Exception {
    var source = new Series(3, 1, 2);
    source.failed = 1;
    var ordinals = new ArrayList<Integer>();
    var times = new ArrayList<Long>();
    var values = new ArrayList<Object>();
    var problems = new ArrayList<ReadProblem>();
    SampleCursors.consume(source, "/x", new SampleCursors.Sink() {
      public void sample(long time, long offset, int ordinal, Object value) {
        assertEquals(12L + 16L * ordinal, offset);
        ordinals.add(ordinal); times.add(time); values.add(value);
      }
      public void problem(ReadProblem problem) { problems.add(problem); }
    });
    assertEquals(List.of(0, 2), ordinals);
    assertEquals(List.of(3L, 2L), times);
    assertEquals(List.of(0, 4), values);
    assertEquals(List.of(new ReadProblem(ReadProblem.Code.MALFORMED_RECORD, "/x", 28,
        "synthetic record 1 failed")), problems);
  }

  @Test void wholeAndSelectiveReadsUseOneFusedBackendReadPerSelectedRecord() throws Exception {
    for (boolean whole : new boolean[]{true, false}) {
      var reads = new ArrayList<Integer>();
      var source = new Series(1, 2, 3) {
        @Override public void readRange(int start, int end, String entry, SampleCursors.Sink sink) {
          for (int index = start; index < end; index++) reads.add(index);
          super.readRange(start, end, entry, sink);
        }
      };
      var values = new ArrayList<Object>();
      if (whole) {
        SampleCursors.consume(source, "/x", new SampleCursors.Sink() {
          public void sample(long time, long offset, int ordinal, Object value) {
            assertEquals(ordinal + 1, time);
            values.add(value);
          }
          public void problem(ReadProblem problem) { fail(problem.toString()); }
        });
        assertEquals(List.of(0, 1, 2), reads,
            "Whole-entry decoding must not split framing, timestamp and value across backend calls");
        assertEquals(List.of(0, 2, 4), values);
      } else {
        var query = new ReadQuery("/x", List.of(new ReadQuery.Window(2, 3, true)),
            ReadQuery.Order.TIME, 8, 64);
        try (var cursor = SampleCursors.open(source, query, null, 100)) {
          var batch = cursor.next();
          assertEquals(List.of(2L, 3L), batch.samples().stream().map(Sample::timestampUs).toList());
          assertEquals(List.of(2, 4), batch.samples().stream().map(Sample::value).toList());
          assertEquals(List.of(1, 2), reads,
              "Selective decoding must share the fused read, after header-only selection");
        }
      }
    }
  }

  @Test void aConsumerFailureIsNotReportedAsARecordDecodeFailure() {
    var source = new Series(0);
    var failure = assertThrows(IllegalStateException.class, () -> SampleCursors.consume(source, "/x",
        new SampleCursors.Sink() {
          public void sample(long time, long offset, int ordinal, Object value) {
            throw new IllegalStateException("consumer failed");
          }
          public void problem(ReadProblem problem) { fail("Consumer failure was blamed on the log"); }
        }));
    assertEquals("consumer failed", failure.getMessage());
  }

  @Test void wholeEntryAdmitsOneRangeRatherThanDispatchingEveryRecordThroughTheQueryEngine() throws Exception {
    var ranges = new ArrayList<List<Integer>>();
    var source = new Series(1, 2, 3) {
      @Override public void readRange(int start, int end, String entry, SampleCursors.Sink sink) {
        ranges.add(List.of(start, end));
        super.readRange(start, end, entry, sink);
      }
    };
    var result = LegacyMaterializer.read("/x", source);
    assertEquals(List.of(0, 2, 4), result.values().stream().map(v -> v.value()).toList());
    assertEquals(List.of(List.of(0, 3)), ranges,
        "Whole-entry materialization must dispatch once, with framing and decode inside the backend range");
  }

  @Test void bothCompatibilityAndSelectiveReadsUseTheSameCursorOwner() throws IOException {
    var root = Path.of("src/main/java/org/triplehelix/wpilogmcp");
    var lazy = Files.readString(root.resolve("log/LazyParsedLog.java"));
    var local = Files.readString(root.resolve("read/LocalSampleCursor.java"));
    var materializer = Files.readString(root.resolve("read/LegacyMaterializer.java"));
    assertTrue(lazy.contains("LegacyMaterializer.read("), "Lazy decoding must delegate to the shared materializer");
    assertTrue(local.contains("SampleCursors.open("), "Selective decoding must delegate to the shared cursor");
    assertTrue(materializer.contains("SampleCursors.consume("),
        "Whole-entry decoding must use the shared engine without a transient Sample per value");
    var decodeEntry = lazy.substring(lazy.indexOf("private List<TimestampedValue> decodeEntry("),
        lazy.indexOf("private int estimateMemoryBytes("));
    assertFalse(decodeEntry.contains("for ("), "The compatibility adapter must not keep another record loop");
  }

  private static class Series implements RecordSeries {
    private final long[] times;
    private final List<Integer> decoded = new ArrayList<>();
    int failed = -1;
    boolean rethrow;
    Series(long... times) { this.times = times; }
    public int size() { return times.length; }
    public long timestampUs(int index) { return times[index]; }
    public long offset(int index) { return 12L + index * 16L; }
    public long payloadBytes(int index) { return 8; }
    public boolean monotonic() {
      for (int i = 1; i < times.length; i++) if (times[i] < times[i - 1]) return false;
      return true;
    }
    public Object decode(int index) {
      decoded.add(index);
      if (index == failed) throw new IllegalArgumentException("synthetic failure");
      return 2 * index;
    }
    public String problemMessage(int index, Exception failure) {
      if (rethrow) throw (RuntimeException) failure;
      return "synthetic record " + index + " failed";
    }
  }
}
