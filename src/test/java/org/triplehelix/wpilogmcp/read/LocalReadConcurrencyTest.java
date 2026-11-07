/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogFileAccess;

class LocalReadConcurrencyTest {
  @TempDir Path temp;

  @Test void concurrentCursorsShareOneBoundedPermutationAndItsBudgetIsReleased() throws Exception {
    var path = temp.resolve("unordered.wpilog");
    try (var w = new WpilogWriter(path, "")) {
      int id = w.start("/x", "int64", "", 0);
      for (int i = 999; i >= 0; i--) w.append(id, i, WpilogWriter.encodeInt64(i));
    }
    var pool = Executors.newFixedThreadPool(2); var begin = new CountDownLatch(1);
    // Exactly one sort fits. Duplicate concurrent construction would exceed this budget.
    try (var access = LogAccess.local(new ReadOptions(List.of(temp), 12_000, 1024, 4096))) {
      var snapshot = access.open(path);
      java.util.concurrent.Callable<List<Sample>> read = () -> {
        assertTrue(begin.await(10, TimeUnit.SECONDS));
        try (var cursor = snapshot.read(ReadQuery.all("/x"))) { return cursor.next().samples(); }
      };
      var a = pool.submit(read); var b = pool.submit(read); begin.countDown();
      var first = a.get(10, TimeUnit.SECONDS); var second = b.get(10, TimeUnit.SECONDS);
      assertEquals(first, second); assertEquals(1000, first.size());
      for (int i = 0; i < first.size(); i++) {
        assertEquals(i, first.get(i).timestampUs()); assertEquals((long) i, first.get(i).value());
        assertEquals(999 - i, first.get(i).ordinal());
      }
      snapshot.close();
      try (var next = access.open(path); var cursor = next.read(ReadQuery.all("/x"))) {
        assertEquals(1000, cursor.next().samples().size());
      }
    } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    try (var move = LogFileAccess.move(List.of(path))) { assertNotNull(move); }
  }

  @Test void closeRacingOpensNeverPublishesOrLeaksAnUnownedMapping() throws Exception {
    var path = temp.resolve("open.wpilog");
    try (var w = new WpilogWriter(path, "")) {
      int id = w.start("/x", "int64", "", 0);
      for (int i = 0; i < 10_000; i++) w.append(id, i, WpilogWriter.encodeInt64(i));
    }
    var pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 10; i++) {
        var access = LogAccess.local(List.of(temp)); var begin = new CountDownLatch(1);
        var opened = pool.submit(() -> {
          begin.await();
          try (var snapshot = access.open(path)) { assertEquals(10_000, snapshot.sampleCount("/x")); }
          catch (ReadException e) { assertEquals(ReadProblem.Code.CLOSED, e.code()); }
          return null;
        });
        var closed = pool.submit(() -> { begin.await(); access.close(); return null; });
        begin.countDown(); opened.get(10, TimeUnit.SECONDS); closed.get(10, TimeUnit.SECONDS);
        try (var move = LogFileAccess.move(List.of(path))) { assertNotNull(move); }
      }
    } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
  }
}
