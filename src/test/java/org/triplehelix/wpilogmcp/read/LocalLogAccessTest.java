/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogFileAccess;

class LocalLogAccessTest {
  @TempDir Path temp;

  private Path log(long... timestamps) throws Exception {
    var file = temp.resolve("samples.wpilog");
    try (var w = new WpilogWriter(file, "")) {
      int id = w.start("/integer", "int64", "{\"unit\":\"count\"}", 0);
      for (int i = 0; i < timestamps.length; i++)
        w.append(id, timestamps[i], WpilogWriter.encodeInt64(9_007_199_254_740_993L + i));
    }
    return file;
  }

  @Test void appendPositionUsesOrdinalsSoRepeatedOrBackwardTimestampsAreNotLost() throws Exception {
    var file = log(20, 20, 10);
    var query = org.triplehelix.wpilogmcp.read.internal.ReadWire.query(com.google.gson.JsonParser.parseString(
        "{\"entry\":\"/integer\",\"order\":\"file\",\"first_ordinal\":1}").getAsJsonObject());
    try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(file); var cursor = snapshot.read(query)) {
      var batch = cursor.next(); assertEquals(List.of(1,2), batch.samples().stream().map(Sample::ordinal).toList());
      assertEquals(2, batch.recordsVisited(), "The held prefix must not be visited again");
    }
  }

  @Test void exactTimeOrderRetainsLargeIntegersAndOriginalOrdinals() throws Exception {
    var file = log(30, -10, 20, 20, 40);
    try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(file);
        var cursor = snapshot.read(ReadQuery.window("/integer", -10, 30, false))) {
      var batch = cursor.next();
      assertEquals(List.of(-10L, 20L, 20L), batch.samples().stream().map(Sample::timestampUs).toList());
      assertEquals(List.of(1, 2, 3), batch.samples().stream().map(Sample::ordinal).toList());
      assertEquals(List.of(9_007_199_254_740_994L, 9_007_199_254_740_995L, 9_007_199_254_740_996L),
          batch.samples().stream().map(Sample::value).toList());
      assertEquals(3, batch.recordsVisited()); assertEquals(24, batch.payloadBytes());
      assertTrue(batch.complete()); assertTrue(batch.problems().isEmpty());
      assertEquals(20, snapshot.indexBytes(), "Ordinary offsets remain four bytes, without a timestamp array");
      assertEquals("{\"unit\":\"count\"}", snapshot.entries().get("/integer").metadata());
    }
  }

  @Test void batchingRespectsBothBoundsAndNeverLosesABoundarySample() throws Exception {
    var file = log(0, 1, 2, 3, 4);
    try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(file);
        var cursor = snapshot.read(new ReadQuery("/integer", List.of(), ReadQuery.Order.TIME, 2, 9))) {
      var seen = new ArrayList<Sample>(); SampleBatch batch;
      do {
        batch = cursor.next(); assertEquals(1, batch.samples().size());
        assertEquals(8, batch.payloadBytes()); seen.addAll(batch.samples());
      } while (!batch.complete());
      assertEquals(List.of(0, 1, 2, 3, 4), seen.stream().map(Sample::ordinal).toList());
      assertTrue(cursor.next().complete());
    }
  }

  @Test void scopedProblemsDoNotDecodeMalformedRecordsOutsideTheWindow() throws Exception {
    var file = temp.resolve("malformed.wpilog");
    try (var w = new WpilogWriter(file, "")) {
      int id = w.start("/x", "double", "", 0);
      w.append(id, 0, new byte[]{1});
      w.append(id, 10, WpilogWriter.encodeDouble(4));
    }
    try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(file)) {
      try (var cursor = snapshot.read(ReadQuery.window("/x", 10, 10, true))) {
        var batch = cursor.next(); assertEquals(1, batch.recordsVisited());
        assertEquals(4., batch.samples().get(0).value()); assertTrue(batch.problems().isEmpty());
      }
      try (var cursor = snapshot.validate("/x")) {
        var batch = cursor.next(); assertEquals(2, batch.recordsVisited());
        assertEquals(1, batch.problems().size());
        assertEquals(ReadProblem.Code.MALFORMED_RECORD, batch.problems().get(0).code());
        assertTrue(batch.problems().get(0).message().contains("1 bytes"));
      }
    }
  }

  @Test void disjointWindowsKeepExplicitEndsWithoutHoldsOrCoercion() throws Exception {
    var file = log(-1, 0, 1, 2, 3, 4);
    var query = new ReadQuery("/integer", List.of(new ReadQuery.Window(0, 1, false),
        new ReadQuery.Window(2, 3, true)), ReadQuery.Order.TIME, 4096, 4 << 20);
    try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(file);
        var cursor = snapshot.read(query)) {
      assertEquals(List.of(0L, 2L, 3L), cursor.next().samples().stream().map(Sample::timestampUs).toList());
    }
    assertThrows(IllegalArgumentException.class, () -> new ReadQuery("/integer",
        List.of(new ReadQuery.Window(0, 1, true), new ReadQuery.Window(1, 2, true)),
        ReadQuery.Order.TIME, 1, 1));
  }

  @Test void opaqueTypesRetainAllBytesAndOversizedRecordIsRefusedBeforeDecode() throws Exception {
    var file = temp.resolve("opaque.wpilog"); var payload = new byte[101]; payload[100] = 42;
    try (var w = new WpilogWriter(file, "")) {
      int id = w.start("/opaque", "custom:bytes", "", 0); w.append(id, 1, payload);
    }
    try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(file);
        var cursor = snapshot.read(new ReadQuery("/opaque", List.of(), ReadQuery.Order.FILE, 1, 10))) {
      assertArrayEquals(payload, (byte[]) cursor.next().samples().get(0).value());
    }
    try (var access = LogAccess.local(new ReadOptions(List.of(temp), 1024, 100, 4096));
        var snapshot = access.open(file); var cursor = snapshot.read(ReadQuery.all("/opaque"))) {
      var error = assertThrows(ReadException.class, cursor::next);
      assertEquals(ReadProblem.Code.RESOURCE_LIMIT, error.code());
      assertTrue(error.getMessage().contains("101"));
    }
  }

  @Test void identicalSnapshotsShareThePermutationAndItsBudgetUntilTheLastCursorCloses() throws Exception {
    var file = log(3, 1, 2);
    try (var access = LogAccess.local(new ReadOptions(List.of(temp), 36, 1024, 4096));
         var first = access.open(file); var second = access.open(file)) {
      var a = first.read(ReadQuery.all("/integer"));
      try (var b = assertDoesNotThrow(() -> second.read(ReadQuery.all("/integer")),
          "The second snapshot must reuse the first 12-byte permutation, not allocate another 36-byte workspace")) {
        first.close(); a.close();
        assertEquals(List.of(1L, 2L, 3L), b.next().samples().stream().map(Sample::timestampUs).toList());
      }
    }
  }

  @Test void sortWorkspaceIsBoundedAndFileOrderRemainsAvailable() throws Exception {
    var file = log(3, 1, 2);
    try (var access = LogAccess.local(new ReadOptions(List.of(temp), 35, 1024, 4096));
        var snapshot = access.open(file)) {
      var error = assertThrows(ReadException.class, () -> snapshot.read(ReadQuery.all("/integer")));
      assertEquals(ReadProblem.Code.RESOURCE_LIMIT, error.code());
      try (var cursor = snapshot.validate("/integer")) {
        assertEquals(List.of(3L, 1L, 2L), cursor.next().samples().stream().map(Sample::timestampUs).toList());
      }
    }
  }

  @Test void admittedCursorOutlivesClosedOwnersButLastCloseReleasesMoveLease() throws Exception {
    var file = log(1, 2);
    var access = LogAccess.local(List.of(temp)); var snapshot = access.open(file);
    var cursor = snapshot.read(ReadQuery.all("/integer"));
    snapshot.close(); access.close();
    assertThrows(ReadException.class, () -> snapshot.read(ReadQuery.all("/integer")));
    assertThrows(ReadException.class, () -> access.open(file));
    assertThrows(java.io.IOException.class, () -> LogFileAccess.move(List.of(file)));
    assertEquals(2, cursor.next().samples().size());
    cursor.close(); cursor.close(); snapshot.close(); access.close();
    try (var move = LogFileAccess.move(List.of(file))) {
      Files.move(file, temp.resolve("moved.wpilog"));
    }
  }

  @Test void growthInvalidatesOldReadsAndReopenSeesTheNewBoundary() throws Exception {
    var file = log(1);
    try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(file);
        var cursor = snapshot.read(ReadQuery.all("/integer"))) {
      Files.write(file, new byte[]{0, 1, 8, 2, 7, 0, 0, 0, 0, 0, 0, 0}, java.nio.file.StandardOpenOption.APPEND);
      assertEquals(ReadProblem.Code.FILE_CHANGED, assertThrows(ReadException.class, cursor::next).code());
      try (var newer = access.open(file); var updated = newer.read(ReadQuery.all("/integer"))) {
        assertEquals(2, updated.next().samples().size()); assertTrue(newer.boundary().size() > snapshot.boundary().size());
      }
    }
  }

  @Test void explicitRootsCannotBeEmptyOrEscapeThroughSymlinks() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> LogAccess.local(List.of()));
    var root = Files.createDirectory(temp.resolve("root")); var outside = log(1);
    try (var access = LogAccess.local(List.of(root))) {
      assertEquals(ReadProblem.Code.ACCESS_DENIED, assertThrows(ReadException.class, () -> access.open(outside)).code());
      try { Files.createSymbolicLink(root.resolve("escape.wpilog"), outside); }
      catch (UnsupportedOperationException | java.nio.file.FileSystemException e) { return; }
      assertEquals(ReadProblem.Code.ACCESS_DENIED, assertThrows(ReadException.class,
          () -> access.open(root.resolve("escape.wpilog"))).code());
    }
  }

  @Test void fileOrderFilteringBoundsVisitedWorkEvenWhenTheFirstMatchIsFarAway() throws Exception {
    var file = log(0, 1, 2, 3, 4);
    try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(file);
        var cursor = snapshot.read(new ReadQuery("/integer", List.of(new ReadQuery.Window(4, 4, true)),
            ReadQuery.Order.FILE, 1, 1024))) {
      for (int i = 0; i < 4; i++) {
        var batch = cursor.next(); assertTrue(batch.samples().isEmpty());
        assertEquals(1, batch.recordsVisited()); assertFalse(batch.complete());
      }
      var last = cursor.next(); assertEquals(4, last.samples().get(0).timestampUs());
      assertTrue(last.complete());
    }
  }

  @Test void timestampsAboveDoubleIntegerPrecisionKeepTheirDistinctWindowBoundaries() throws Exception {
    long time = 9_007_199_254_740_992L;
    var file = log(time, time + 1, time + 2);
    try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(file);
        var cursor = snapshot.read(ReadQuery.window("/integer", time + 1, time + 1, true))) {
      var batch = cursor.next(); assertEquals(1, batch.samples().size());
      assertEquals(time + 1, batch.samples().get(0).timestampUs()); assertEquals(1, batch.samples().get(0).ordinal());
    }
  }

  @Test void invalidOpenReleasesItsMappingAndNeverCreatesSettingsOrCacheFiles() throws Exception {
    var invalid = Files.writeString(temp.resolve("invalid.wpilog"), "not a WPILOG");
    try (var access = LogAccess.local(List.of(temp))) {
      assertThrows(java.io.IOException.class, () -> access.open(invalid));
      try (var move = LogFileAccess.move(List.of(invalid))) { Files.delete(invalid); }
    }
    try (var files = Files.list(temp)) { assertEquals(0, files.count()); }
  }
}
