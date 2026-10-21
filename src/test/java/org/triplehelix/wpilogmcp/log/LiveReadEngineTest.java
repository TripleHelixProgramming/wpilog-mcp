/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;

/** Shared cursor admission preserves the live view's fixed prefix and exact timestamp ordering,
 * even when distinct robot timestamps share a rounded legacy double timestamp. */
class LiveReadEngineTest {
  @TempDir Path temp;

  @Test void nativeColdDecodeReportsMalformedPayloadAndKeepsTheFollowingOrdinal() throws Exception {
    var path = temp.resolve("cold-malformed.wpilog"); var live = new LiveLog(path, 0);
    try (var out = new WpilogOutput(path)) {
      int id = out.start("/x", "int64", "", 0); var entry = new EntryInfo(id, "/x", "int64", ""); live.announce(entry);
      append(out, live, entry, 10, 1);
      var bad = out.append(id, 10, new byte[] {1}); live.append(entry, new ValueFrame(id, 10, 2, 2L), bad);
      append(out, live, entry, 9, 3); live.expire();
      assertEquals(0, live.hotRecordCount());
      try (var view = live.retainView(); var cursor = org.triplehelix.wpilogmcp.read.SampleCursors.open(view.records("/x", true),
          new org.triplehelix.wpilogmcp.read.ReadQuery("/x", List.of(), org.triplehelix.wpilogmcp.read.ReadQuery.Order.FILE, 4096, 4 << 20), null, 64 << 20)) {
        var batch = cursor.next();
        assertEquals(3, batch.recordsVisited()); assertTrue(batch.complete());
        assertEquals(List.of(1L, 3L), batch.samples().stream().map(s -> s.value()).toList());
        assertEquals(List.of(0, 2), batch.samples().stream().map(s -> s.ordinal()).toList());
        assertEquals(1, batch.problems().size()); var problem = batch.problems().get(0);
        assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.MALFORMED_RECORD, problem.code());
        assertEquals(bad.offset(), problem.recordOffset()); assertEquals("record of 1 bytes cannot be read as int64", problem.message());
        assertThrows(java.util.InputMismatchException.class, () -> view.values().get("/x"), "Legacy materialization keeps its existing failure policy");
      }
    } finally { live.finish(); live.close(); }
  }

  @Test void anInversionAfterThePublishedPrefixCannotChangeItsFirstReadOrMonotonicity() throws Exception {
    var path = temp.resolve("prefix.wpilog");
    var live = new LiveLog(path, 0);
    try (var out = new WpilogOutput(path)) {
      int id = out.start("/x", "int64", "original", 0);
      var entry = new EntryInfo(id, "/x", "int64", "original");
      live.announce(entry);
      var first = append(out, live, entry, 10, 1);
      var second = append(out, live, entry, 20, 2);
      try (var before = live.retainView()) {
        // Do not ask this view for length or values until after the writer publishes its inversion.
        var third = append(out, live, entry, 15, 3);
        live.metadata(new EntryInfo(id, "/x", "int64", "changed"));
        live.expire();
        assertEquals(0, live.hotRecordCount());
        try (var after = live.retainView()) {
          var oldRecords = before.records("/x", false);
          var newRecords = after.records("/x", false);
          assertEquals(2, oldRecords.size());
          assertEquals(3, newRecords.size());
          assertEquals(16, before.indexBytes());
          assertEquals(24, after.indexBytes());
          assertThrows(IndexOutOfBoundsException.class, () -> oldRecords.timestampUs(2),
              "A record beyond this view must stay inaccessible after the writer fills its slot");
          assertTrue(oldRecords.monotonic(), "An inversion beyond a fixed boundary belongs only to later views");
          assertFalse(newRecords.monotonic());
          assertEquals(first.offset(), oldRecords.offset(0));
          assertEquals(second.offset(), oldRecords.offset(1));
          assertEquals(third.offset(), newRecords.offset(2));
          assertEquals(8, oldRecords.payloadBytes(0));
          assertEquals(15, newRecords.timestampUs(2));
          assertEquals("original", before.entries().get("/x").metadata());
          assertEquals("changed", after.entries().get("/x").metadata());
          long reads = live.mappedReadCount();
          assertEquals(List.of(1L, 2L), values(before));
          assertEquals(2, live.mappedReadCount() - reads, "The shared materializer reads each admitted cold record once");
          assertEquals(List.of(1L, 3L, 2L), values(after));
          assertEquals(List.of(1L, 2L), values(before));
        }
      }
    } finally { live.finish(); live.close(); }
  }

  @Test void indexedAddressBytesKeepTheOldPrefixCompactAfterTheFirstWideRecord() throws Exception {
    var path = temp.resolve("address-width.wpilog");
    java.nio.file.Files.write(path, new byte[12]);
    var live = new LiveLog(path, 100);
    var entry = new EntryInfo(1, "/x", "int64", "");
    live.announce(entry);
    try {
      // Address accounting needs no giant file: these records remain hot and are never mapped.
      live.append(entry, new ValueFrame(1, 10, 2, 1L), new WpilogOutput.Written(32, 12, 36, 8));
      try (var compact = live.retainView()) {
        long wide = (1L << 31) + 32;
        live.append(entry, new ValueFrame(1, 20, 2, 2L), new WpilogOutput.Written(wide, 12, wide + 4, 8));
        try (var mixed = live.retainView()) {
          assertEquals(8, compact.indexBytes());
          assertEquals(8 + 24, mixed.indexBytes());
          assertEquals(wide, mixed.records("/x", true).offset(1));
          assertEquals(8, mixed.records("/x", true).payloadBytes(1));
        }
      }
    } finally { live.finish(); live.close(); }
  }

  @Test void legacyDoubleTimestampTiesUseExactMicrosecondOrderForHotAndColdReads() throws Exception {
    long low = 1L << 53;
    long high = low + 1;
    assertEquals(low / 1_000_000.0, high / 1_000_000.0,
        "The two exact microsecond timestamps intentionally share a legacy double timestamp");
    var path = temp.resolve("timestamp-ties.wpilog");
    var live = new LiveLog(path, 0);
    try (var out = new WpilogOutput(path)) {
      int id = out.start("/x", "int64", "", 0);
      var entry = new EntryInfo(id, "/x", "int64", "");
      live.announce(entry);
      append(out, live, entry, high, 1);
      append(out, live, entry, low, 2);
      try (var hot = live.retainView()) {
        assertEquals(List.of(2L, 1L), values(hot));
        assertEquals(List.of(low, high), hot.values().get("/x").stream().map(TimestampedValue::timestampUs).toList());
        var records = hot.records("/x", true);
        assertFalse(records.monotonic());
        assertEquals(high, records.timestampUs(0));
        assertEquals(low, records.timestampUs(1));
      }
      live.advance(high); live.expire();
      assertEquals(0, live.hotRecordCount());
      try (var cold = live.retainView()) {
        assertEquals(List.of(2L, 1L), values(cold));
        assertEquals(List.of(low, high), cold.values().get("/x").stream().map(TimestampedValue::timestampUs).toList());
        assertFalse(cold.records("/x", false).monotonic());
      }
    } finally { live.finish(); live.close(); }
  }

  @Test void opaqueSourceBytesAndLegacyPresentationAgreeAcrossHotToColdTransition() throws Exception {
    var path = temp.resolve("opaque.wpilog");
    var live = new LiveLog(path, 0);
    try (var out = new WpilogOutput(path)) {
      int id = out.start("/opaque", "custom", "", 0);
      var entry = new EntryInfo(id, "/opaque", "custom", "");
      live.announce(entry);
      byte[] bytes = {1, 2, (byte) 255};
      var written = out.append(id, 10, bytes);
      live.append(entry, new ValueFrame(id, 10, 5, bytes), written);
      try (var hot = live.retainView()) {
        byte[] captured = (byte[]) hot.records("/opaque", true).decode(0);
        assertArrayEquals(bytes, captured);
        captured[0] = 42;
        assertArrayEquals(bytes, (byte[]) hot.records("/opaque", true).decode(0),
            "Public values must not expose the writer's retained wire buffer");
        assertEquals("0102ff", hot.values().get("/opaque").get(0).value());
      }
      live.expire();
      try (var cold = live.retainView()) {
        assertArrayEquals(bytes, (byte[]) cold.records("/opaque", true).decode(0));
        assertEquals("0102ff", cold.values().get("/opaque").get(0).value());
        assertEquals(bytes.length, cold.records("/opaque", true).payloadBytes(0));
      }
    } finally { live.finish(); live.close(); }
  }

  @Test void legacyNonStructRuntimeFailuresStillEscapeRatherThanBecomingDecodeProblems() throws Exception {
    var path = temp.resolve("runtime.wpilog");
    var live = new LiveLog(path, 0);
    try (var out = new WpilogOutput(path)) {
      int id = out.start("/array", "boolean[]", "", 0);
      var entry = new EntryInfo(id, "/array", "boolean[]", "");
      live.announce(entry);
      // A deliberate internal mismatch between a valid string frame and its announced array type.
      // Legacy View counted only StructDecodeException; a broad catch in the shared materializer
      // must not silently turn this bug into no data.
      var written = out.append(id, 10, new byte[] {1});
      live.append(entry, new ValueFrame(id, 10, 4, "not a list"), written);
      try (var view = live.retainView()) {
        assertThrows(ClassCastException.class, () -> view.values().get("/array"));
      }
    } finally { live.finish(); live.close(); }
  }

  private static WpilogOutput.Written append(WpilogOutput out, LiveLog live, EntryInfo entry,
      long timestampUs, long value) throws Exception {
    byte[] bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    var written = out.append(entry.id(), timestampUs, bytes);
    live.append(entry, new ValueFrame(entry.id(), timestampUs, 2, value), written);
    return written;
  }

  private static List<Object> values(LiveLog.View view) {
    return view.values().get("/x").stream().map(TimestampedValue::value).toList();
  }
}
