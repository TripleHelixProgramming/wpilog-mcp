/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GuardedSampleCursorTest {
  private static final SampleBatch EMPTY = new SampleBatch(List.of(), List.of(), 0, 0, true);

  @Test void aCloseDuringReadingReleasesOnlyAfterTheBatchStopsUsingTheSource() throws Exception {
    var entered = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    var releases = new AtomicInteger();
    var closes = new AtomicInteger();
    var source = new SampleCursor() {
      public SampleBatch next() throws IOException {
        entered.countDown();
        try {
          if (!finish.await(30, TimeUnit.SECONDS)) throw new IOException("Test did not release its source");
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IOException(failure);
        }
        assertEquals(0, releases.get(), "Source must remain retained while a batch reads it");
        return EMPTY;
      }
      public void close() { closes.incrementAndGet(); }
    };
    var cursor = new GuardedSampleCursor(source, () -> {}, releases::incrementAndGet, "fixture");
    var reading = CompletableFuture.supplyAsync(() -> {
      try { return cursor.next(); } catch (IOException failure) { throw new RuntimeException(failure); }
    });
    reading.whenComplete((value, failure) -> { if (failure != null) entered.countDown(); });
    try {
      assertTrue(entered.await(30, TimeUnit.SECONDS));
      assertThrows(IllegalStateException.class, cursor::next);
      cursor.close();
      assertEquals(0, releases.get());
      assertEquals(1, closes.get());
    } finally {
      finish.countDown();
    }
    assertEquals(EMPTY, reading.get(30, TimeUnit.SECONDS));
    assertEquals(1, releases.get());
    cursor.close();
    assertEquals(1, releases.get());
    assertEquals(ReadProblem.Code.CLOSED, assertThrows(ReadException.class, cursor::next).code());
  }

  @Test void aResultIsDiscardedWhenTheSourceChangedDuringItsRead() throws Exception {
    var checks = new AtomicInteger();
    var releases = new AtomicInteger();
    var cursor = new GuardedSampleCursor(new Immediate(), () -> {
      if (checks.incrementAndGet() == 2)
        throw new ReadException(ReadProblem.Code.FILE_CHANGED, "fixture grew while reading");
    }, releases::incrementAndGet, "fixture");
    assertEquals("fixture grew while reading", assertThrows(ReadException.class, cursor::next).getMessage());
    assertEquals(2, checks.get());
    cursor.close();
    assertEquals(1, releases.get());
  }

  @Test void mappingErrorsExplainTheFileAndKeepCloseAvailable() throws Exception {
    var releases = new AtomicInteger();
    var source = new Immediate() {
      @Override public SampleBatch next() { throw new InternalError("mapping was truncated"); }
    };
    var cursor = new GuardedSampleCursor(source, () -> {}, releases::incrementAndGet, "/fixture/log.wpilog");
    var failure = assertThrows(ReadException.class, cursor::next);
    assertEquals(ReadProblem.Code.FILE_CHANGED, failure.code());
    assertEquals("Mapped log could not be read; the file may have changed: /fixture/log.wpilog. Reopen it.",
        failure.getMessage());
    cursor.close();
    assertEquals(1, releases.get());
  }

  @Test void failingDelegateCloseStillReleasesItsSource() throws Exception {
    var releases = new AtomicInteger();
    var source = new Immediate() {
      @Override public void close() throws IOException { throw new IOException("close failed"); }
    };
    var cursor = new GuardedSampleCursor(source, () -> {}, releases::incrementAndGet, "fixture");
    assertEquals("close failed", assertThrows(IOException.class, cursor::close).getMessage());
    assertEquals(1, releases.get());
  }

  private static class Immediate implements SampleCursor {
    public SampleBatch next() { return EMPTY; }
    public void close() throws IOException {}
  }
}
