/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Small buffers make the independently counted byte budget exercise the same owner as production. */
class BufferedLogOutputTest {
  private static final int CAPACITY = 256;
  // The first line holds two bytes in flight, leaving 253 payload bytes plus one LF.
  private static final String FULL_QUEUED_LINE = "q".repeat(253) + "\n";

  @Test void fullBufferDropsWholeUtf8LinesAndReportsLossWithoutAnotherProducer() throws Exception {
    try (var rig = new Rig()) {
      holdFirstLine(rig, "h\n");
      write(rig.output, FULL_QUEUED_LINE);
      assertEquals(256, rig.output.retainedBytes(),
          "The bounded budget includes the two bytes held in the sink, not just queued bytes");
      write(rig.output, "abc\n");
      byte[] utf8 = "é\r\n".getBytes(StandardCharsets.UTF_8);
      assertEquals(4, utf8.length, "Two UTF-8 bytes, one CR and one LF are four bytes");
      rig.output.write(utf8, 0, 1);
      rig.output.write(utf8, 1, 2);
      rig.output.write(utf8, 3, 1);
      assertEquals(2, rig.output.lostLines());
      assertEquals(8, rig.output.lostBytes(),
          "The two discarded complete lines have four bytes apiece");
      assertEquals(256, rig.output.retainedBytes());

      rig.sink.release();
      assertTrue(rig.output.awaitDrained(2, TimeUnit.SECONDS),
          "A loss report must drain even when no later producer writes a line");
      assertEquals("h\n" + FULL_QUEUED_LINE + loss(2, 8), rig.sink.text());
      write(rig.output, "after\n");
      assertTrue(rig.output.awaitDrained(2, TimeUnit.SECONDS));
      assertEquals("h\n" + FULL_QUEUED_LINE + loss(2, 8) + "after\n", rig.sink.text(),
          "Exactly one loss report precedes output accepted after the dropped run");
      assertEquals(0, rig.output.retainedBytes());
    }
  }

  @Test void overflownPartialLineNeverLeaksItsAcceptedPrefix() throws Exception {
    try (var rig = new Rig()) {
      holdFirstLine(rig, "h\n");
      write(rig.output, "p".repeat(200));
      assertEquals(202, rig.output.retainedBytes());
      write(rig.output, "p".repeat(55));
      assertEquals(2, rig.output.retainedBytes(),
          "Overflow must release the entire 255-byte partial line, including its accepted prefix");
      write(rig.output, "\n");
      assertEquals(1, rig.output.lostLines());
      assertEquals(256, rig.output.lostBytes());
      rig.sink.release();
      assertTrue(rig.output.awaitDrained(2, TimeUnit.SECONDS));
      assertEquals("h\n" + loss(1, 256), rig.sink.text(),
          "A dropped line must not expose either its prefix or its final LF");
    }
  }

  @Test void inFlightBytesCannotBeReusedByEvenAnEmptyLine() throws Exception {
    try (var rig = new Rig()) {
      holdFirstLine(rig, "h\n");
      write(rig.output, FULL_QUEUED_LINE);
      write(rig.output, "\n");
      assertEquals(256, rig.output.retainedBytes(), "An empty line still consumes one byte");
      assertEquals(1, rig.output.lostLines());
      assertEquals(1, rig.output.lostBytes());
      rig.sink.release();
      assertTrue(rig.output.awaitDrained(2, TimeUnit.SECONDS));
      assertEquals("h\n" + FULL_QUEUED_LINE + loss(1, 1), rig.sink.text());
    }
  }

  @Test void resumedProducerPlacesTheLossReportBeforeItsLineWhileOutputIsStillHeld() throws Exception {
    try (var rig = new Rig()) {
      holdFirstLine(rig, "h\n");
      write(rig.output, "p".repeat(255) + "\n");
      assertEquals(1, rig.output.lostLines());
      assertEquals(256, rig.output.lostBytes());
      write(rig.output, "after\n");
      // The held two bytes, 48-byte loss report and six-byte accepted line all stay charged.
      assertEquals(56, rig.output.retainedBytes());
      rig.sink.release();
      assertTrue(rig.output.awaitDrained(2, TimeUnit.SECONDS));
      assertEquals("h\n" + loss(1, 256) + "after\n", rig.sink.text(),
          "The producer that resumes must put exactly one loss report before its accepted line");
    }
  }

  @Test void overflowingTheResumedLineRollsBackItsLossReportWithoutResettingTheLossRun() throws Exception {
    try (var rig = new Rig()) {
      holdFirstLine(rig, "h\n");
      write(rig.output, "p".repeat(255) + "\n");
      // A 48-byte pending report plus this 231-byte line cannot fit the 254 free bytes.
      write(rig.output, "r".repeat(230) + "\n");
      assertEquals(2, rig.output.lostLines());
      assertEquals(487, rig.output.lostBytes(), "256 discarded bytes plus 231 discarded bytes");
      assertEquals(2, rig.output.retainedBytes(),
          "Both the candidate's prefix and its uncommitted loss report must be rolled back");
      write(rig.output, "ok\n");
      assertEquals(53, rig.output.retainedBytes(), "Two held bytes, 48 report bytes and three line bytes");
      rig.sink.release();
      assertTrue(rig.output.awaitDrained(2, TimeUnit.SECONDS));
      assertEquals("h\n" + loss(2, 487) + "ok\n", rig.sink.text(),
          "The first loss run cannot be forgotten or reported separately when its resumed line overflows");
    }
  }

  @Test void aHugeUnterminatedLineStaysBoundedAndFinishCountsOnlyWrittenBytes() throws Exception {
    try (var rig = new Rig()) {
      holdFirstLine(rig, "h\n");
      byte[] block = "p".repeat(1024).getBytes(StandardCharsets.US_ASCII);
      for (int i = 0; i < 64; i++) {
        rig.output.write(block);
        assertTrue(rig.output.retainedBytes() <= CAPACITY,
            "An unterminated producer cannot enlarge the retained byte budget");
      }
      rig.output.write('p');
      assertEquals(2, rig.output.retainedBytes());
      rig.sink.release();
      assertTrue(rig.output.finish(2, TimeUnit.SECONDS));
      assertEquals(1, rig.output.lostLines());
      assertEquals(65_537, rig.output.lostBytes(),
          "64 blocks of 1024 bytes plus one byte were written; no LF was written");
      assertEquals("h\n" + loss(1, 65_537), rig.sink.text());
    }
  }

  @Test void separateDropEpisodesEachHaveOneLossLineBeforeResumedOutput() throws Exception {
    try (var rig = new Rig()) {
      holdFirstLine(rig, "h\n");
      write(rig.output, FULL_QUEUED_LINE);
      write(rig.output, "abc\n");
      rig.sink.release();
      assertTrue(rig.output.awaitDrained(2, TimeUnit.SECONDS));
      String first = "h\n" + FULL_QUEUED_LINE + loss(1, 4);
      assertEquals(first, rig.sink.text());

      rig.sink.holdNextWrite();
      holdFirstLine(rig, "j\n");
      write(rig.output, FULL_QUEUED_LINE);
      write(rig.output, "xyz\n");
      rig.sink.release();
      assertTrue(rig.output.awaitDrained(2, TimeUnit.SECONDS));
      write(rig.output, "resumed\n");
      assertTrue(rig.output.awaitDrained(2, TimeUnit.SECONDS));
      assertEquals(first + "j\n" + FULL_QUEUED_LINE + loss(1, 4) + "resumed\n", rig.sink.text(),
          "Each report names only its own loss run, in order, and is emitted once");
      assertEquals(2, rig.output.lostLines(), "Lifetime counters span both loss runs");
      assertEquals(8, rig.output.lostBytes());
    }
  }

  @Test void finishAddsOneDelimiterToRetainedPartialText() throws Exception {
    try (var rig = new Rig()) {
      rig.sink.release();
      write(rig.output, "partial");
      assertEquals(7, rig.output.retainedBytes());
      assertTrue(rig.output.finish(2, TimeUnit.SECONDS));
      assertEquals("partial\n", rig.sink.text());
      assertEquals(0, rig.output.lostLines());
      assertEquals(0, rig.output.lostBytes());
      assertEquals(0, rig.output.retainedBytes());
    }
  }

  @Test void finishDropsAnExactlyFullPartialLineWithoutInventingALostDelimiter() throws Exception {
    try (var rig = new Rig()) {
      rig.sink.release();
      write(rig.output, "p".repeat(256));
      assertEquals(256, rig.output.retainedBytes());
      assertTrue(rig.output.finish(2, TimeUnit.SECONDS));
      assertEquals(1, rig.output.lostLines());
      assertEquals(256, rig.output.lostBytes(),
          "Finishing cannot fit the LF and drops the 256 bytes actually supplied");
      assertEquals(loss(1, 256), rig.sink.text());
    }
  }

  @Test void blockedSinkCannotHoldFinishPastItsBudgetAndDrainIsDaemon() throws Exception {
    try (var rig = new Rig()) {
      holdFirstLine(rig, "h\n");
      assertTrue(rig.output.daemonThread(), "A blocked output device must never keep the JVM alive");
      long start = System.nanoTime();
      assertFalse(rig.output.finish(40, TimeUnit.MILLISECONDS),
          "A blocked sink cannot claim its in-flight bytes were drained");
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(elapsedMillis < 500, "A 40 ms drain budget took " + elapsedMillis + " ms");
      assertEquals(2, rig.output.retainedBytes(), "Timed-out output stays charged until its write unwinds");
      rig.sink.release();
      assertTrue(rig.output.awaitDrained(2, TimeUnit.SECONDS));
      assertEquals("h\n", rig.sink.text());
      assertEquals(0, rig.output.retainedBytes());
    }
  }

  private static void write(BufferedLogOutput output, String text) throws IOException {
    output.write(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String loss(long lines, long bytes) {
    return "[wpilog] Dropped log output: lines=" + lines + ", bytes=" + bytes + "\n";
  }

  private static void holdFirstLine(Rig rig, String line) throws Exception {
    write(rig.output, line);
    assertTrue(rig.sink.entered.await(2, TimeUnit.SECONDS), "The sink never held an in-flight line");
  }

  private static final class Rig implements AutoCloseable {
    final HeldSink sink = new HeldSink();
    final BufferedLogOutput output = new BufferedLogOutput(
        new PrintStream(sink, true, StandardCharsets.UTF_8), CAPACITY);

    @Override public void close() {
      sink.release();
      output.finish(2, TimeUnit.SECONDS);
    }
  }

  /** The held write owns no monitor needed by the test that releases it or reads completed output. */
  private static final class HeldSink extends OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private volatile CountDownLatch gate = new CountDownLatch(1);
    volatile CountDownLatch entered = new CountDownLatch(1);

    void holdNextWrite() {
      entered = new CountDownLatch(1);
      gate = new CountDownLatch(1);
    }

    void release() {
      gate.countDown();
    }

    String text() {
      synchronized (bytes) { return bytes.toString(StandardCharsets.UTF_8); }
    }

    @Override public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override public void write(byte[] value, int offset, int length) throws IOException {
      var held = gate;
      entered.countDown();
      try { held.await(); }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Held test output was interrupted", e);
      }
      synchronized (bytes) { bytes.write(value, offset, length); }
    }
  }
}
