/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.rev;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader;

class RevReadCacheTest {
  @TempDir Path temp;
  private Path fixture(String name, double output) throws Exception {
    var file = temp.resolve(name);
    try (var writer = new WpilogWriter(file, "")) {
      int id = writer.start("CAN/3/Periodic Status 0", "raw", "", 0);
      writer.append(id, 1_000_000, FixtureLogs.sparkStatus0(output, 12, 2, 30, false));
      writer.finish(id, 1_000_001);
    }
    return file;
  }

  @Test void selectiveDecodeRetainsOnlyTheNamedSignalWithTheSameValuesAndLessStorage() throws Exception {
    var file = fixture("selected.revlog", .25); var dbc = new DbcLoader().loadEmbedded();
    var whole = RevLogParser.compact(dbc, 1 << 20, 1 << 20).parse(file);
    var selected = RevLogParser.selected(dbc, 1 << 20, 1 << 20, java.util.Set.of("SparkMax_3/AppliedOutput")).parse(file);
    assertEquals(java.util.Set.of("SparkMax_3/AppliedOutput"), selected.signals().keySet());
    assertEquals(whole.signals().get("SparkMax_3/AppliedOutput"), selected.signals().get("SparkMax_3/AppliedOutput"));
    assertTrue(RevLogParser.retainedValueBytes(selected.signals()) * 8 < RevLogParser.retainedValueBytes(whole.signals()));
    var absent = RevLogParser.selected(dbc, 1 << 20, 1 << 20, java.util.Set.of("SparkMax_4/AppliedOutput")).parse(file);
    assertTrue(absent.signals().isEmpty(), "A different device must never supply a requested signal");
  }

  @Test void concurrentReadsShareOneDecodeButReplacementDoesNotReuseIt() throws Exception {
    var file = fixture("bus.revlog", .25);
    try (var cache = new RevReadCache()) {
      var ready = new java.util.concurrent.CountDownLatch(1);
      var tasks = new java.util.ArrayList<CompletableFuture<RecordedRevRead>>();
      for (int i = 0; i < 8; i++) tasks.add(CompletableFuture.supplyAsync(() -> {
        try { ready.await(); return RecordedRevRead.open(file, List.of(temp), null, cache); }
        catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
      }));
      ready.countDown();
      var reads = tasks.stream().map(CompletableFuture::join).toList();
      try {
        for (var read : reads) assertSame(reads.get(0).signals(), read.signals());
        var replacement = fixture("replacement.revlog", .5);
        Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var changed = RecordedRevRead.open(file, List.of(temp), null, cache)) {
          assertNotSame(reads.get(0).signals(), changed.signals());
          assertNotEquals(reads.get(0).rawValues("SparkMax_3/AppliedOutput"), changed.rawValues("SparkMax_3/AppliedOutput"));
        }
      } finally { reads.forEach(RecordedRevRead::close); }
    }
  }

  @Test void aLongPracticeSessionCanExceedFourMillionValuesWithinTheSameBudget() throws Exception {
    var file = temp.resolve("long.revlog"); int frames = 363_637; // 11 signals per frame: 4,000,007 values.
    try (var writer = new WpilogWriter(file, "")) {
      int id = writer.start("CAN/3/Periodic Status 0", "raw", "", 0);
      byte[] payload = FixtureLogs.sparkStatus0(.25, 12, 2, 30, false);
      for (int i = 0; i < frames; i++) writer.append(id, i + 1, payload);
      writer.finish(id, frames + 1);
    }
    try (var read = RecordedRevRead.open(file, List.of(temp), null)) {
      assertEquals(4_000_007L, read.signals().values().stream().mapToLong(s -> s.values().size()).sum());
      var values = read.rawValues("SparkMax_3/AppliedOutput");
      assertEquals(frames, values.size()); assertEquals(frames / 1_000_000.0, values.get(frames - 1).timestamp());
      assertEquals(.25, ((Number) values.get(frames - 1).value()).doubleValue(), 1e-4);
      assertThrows(UnsupportedOperationException.class, () -> values.remove(0));
    }
  }

  @Test void inputIsRefusedBySizeBeforeAnInvalidFileCanReachTheParser() throws Exception {
    var file = temp.resolve("oversize.revlog");
    try (var sparse = new java.io.RandomAccessFile(file.toFile(), "rw")) { sparse.setLength(RevReadCache.MAX_FILE_BYTES + 1); }
    try (var cache = new RevReadCache()) {
      var failure = assertThrows(ReadException.class, () -> RecordedRevRead.open(file, List.of(temp), null, cache));
      assertEquals(ReadProblem.Code.RESOURCE_LIMIT, failure.code());
      assertTrue(failure.getMessage().contains("file exceeds"));
    }
  }

  @Test void decodingStopsBeforeExpandingMoreSamplesThanAdmissionReserved() throws Exception {
    var file = fixture("expands.revlog", .25);
    var parser = new RevLogParser(new DbcLoader().loadEmbedded(), 2);
    assertThrows(RevLogParser.SampleLimitException.class, () -> parser.parse(file),
        "One CAN frame expands to several signals; a byte cap alone cannot bound decoded heap");
  }

  @Test void compactStorageRefusesItsFirstChunkBeforeExceedingTheByteBudget() throws Exception {
    var file = fixture("packed-expands.revlog", .25);
    var parser = RevLogParser.compact(new DbcLoader().loadEmbedded(), 1024, 1 << 20);
    var failure = assertThrows(RevLogParser.SampleLimitException.class, () -> parser.parse(file));
    assertTrue(failure.getMessage().contains("memory limit exceeded: 1024 bytes"));
  }

  @Test void theNativeInputReadItselfIsBoundedEvenAfterSizeAdmission() throws Exception {
    var file = Files.write(temp.resolve("native.revlog"), new byte[100]);
    var parser = new RevLogParser(new DbcLoader().loadEmbedded(), 100, 32);
    assertTrue(assertThrows(java.io.IOException.class, () -> parser.parse(file)).getMessage().contains("exceeds 32 bytes"));
  }

  @Test void activeReadsStayChargedAndClosingOneMakesRoomForAnotherFile() throws Exception {
    var a = fixture("a.revlog", .25); var b = fixture("b.revlog", .5); var c = fixture("c.revlog", .75);
    // The fixture has exactly eleven DBC signals, one sample each. Each admission also charges
    // two input buffers, 1 MiB metadata, and one packed 64-sample chunk per signal.
    long keys;
    try (var initial = RecordedRevRead.open(a, List.of(temp), null)) {
      keys = initial.signals().keySet().stream().mapToLong(k -> 256 + 2L * k.length()).sum();
    }
    long one = (1L << 20) + 2 * Files.size(a) + keys + 11 * (64 * 16L + 80);
    try (var cache = new RevReadCache(2 * one);
         var first = RecordedRevRead.open(a, List.of(temp), null, cache);
         var second = RecordedRevRead.open(b, List.of(temp), null, cache)) {
      assertEquals(11, first.signals().size());
      var full = assertThrows(ReadException.class, () -> RecordedRevRead.open(c, List.of(temp), null, cache));
      assertEquals(ReadProblem.Code.RESOURCE_LIMIT, full.code());
      first.close();
      try (var third = RecordedRevRead.open(c, List.of(temp), null, cache)) {
        assertEquals(11, third.signals().size());
        assertEquals(11, second.signals().size(), "Reclaiming an idle parse cannot invalidate an active reader");
      }
    }
  }

  @Test void activeVersionCountIsBoundedAsWellAsDecodedBytes() throws Exception {
    try (var cache = new RevReadCache()) {
      var reads = new java.util.ArrayList<RecordedRevRead>();
      try {
        for (int i = 0; i < 8; i++) reads.add(RecordedRevRead.open(fixture("v" + i + ".revlog", .25), List.of(temp), null, cache));
        var ninth = fixture("v8.revlog", .25);
        assertEquals(ReadProblem.Code.RESOURCE_LIMIT,
            assertThrows(ReadException.class, () -> RecordedRevRead.open(ninth, List.of(temp), null, cache)).code());
      } finally { reads.forEach(RecordedRevRead::close); }
    }
  }
}
