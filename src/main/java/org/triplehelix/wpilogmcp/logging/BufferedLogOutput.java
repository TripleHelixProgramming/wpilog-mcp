/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.logging;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A stopped journal must not stop the thread recording the match. Only the daemon touches the
 * original stderr; producers copy bounded bytes or discard whole lines. The byte allowance
 * includes incomplete lines and the drain's in-flight bytes, not just the waiting queue.
 */
public final class BufferedLogOutput extends OutputStream {
  public static final int CAPACITY_BYTES = 1_048_576;
  private static volatile BufferedLogOutput installed;
  private static volatile Runnable shutdownCleanup = () -> {};
  private final PrintStream sink;
  private final byte[] ring;
  private final Thread drain;
  private int head, size, committed, inFlight, prefixBytes, lineBytes;
  private long inputBytes, pendingLines, pendingBytes, droppedLines, droppedBytes;
  private boolean discarding, closing, terminated;

  BufferedLogOutput(PrintStream sink, int capacity) {
    if (capacity < 256) throw new IllegalArgumentException("Log buffer must hold its loss report");
    this.sink = Objects.requireNonNull(sink);
    ring = new byte[capacity];
    drain = new Thread(this::drain, "server-log-output");
    drain.setDaemon(true);
    drain.start();
  }

  /** Install before the first logger can cache stderr; stdout keeps its existing protocol owner. */
  public static synchronized BufferedLogOutput install() {
    if (installed != null) return installed;
    var output = new BufferedLogOutput(System.err, CAPACITY_BYTES);
    System.setErr(new PrintStream(output, true, StandardCharsets.UTF_8));
    installed = output;
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try { shutdownCleanup.run(); }
      finally { drainOnExit(); }
    }, "server-log-shutdown"));
    return output;
  }

  /** Cleanup runs before the final drain so shutdown messages cannot race an earlier log hook. */
  public static void onShutdown(Runnable cleanup) {
    shutdownCleanup = Objects.requireNonNull(cleanup);
  }

  public static long droppedLines() {
    var output = installed;
    return output == null ? 0 : output.lostLines();
  }

  /** A blocked daemon sink may lose remaining logs, but cannot extend this wait beyond two seconds. */
  public static void drainOnExit() {
    var output = installed;
    if (output != null) output.finish(2, TimeUnit.SECONDS);
  }

  @Override public synchronized void write(int value) {
    if (!closing) accept((byte) value);
  }

  @Override public synchronized void write(byte[] bytes, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    if (closing) return;
    for (int i = offset; i < offset + length; i++) accept(bytes[i]);
  }

  // PrintStream flushes after each line. It must never pass that flush to the real sink.
  @Override public void flush() {}

  private void accept(byte value) {
    if (inputBytes == 0 && pendingLines != 0) {
      byte[] report = lossReport();
      if (room(report.length)) {
        append(report);
        prefixBytes = report.length;
      } else discarding = true;
    }
    inputBytes++;
    if (!discarding) {
      if (room(1)) { append(value); lineBytes++; }
      else {
        size -= prefixBytes + lineBytes;
        prefixBytes = lineBytes = 0;
        discarding = true;
      }
    }
    if (value == '\n') completeLine();
  }

  private void completeLine() {
    if (discarding) {
      droppedLines++;
      droppedBytes += inputBytes;
      pendingLines++;
      pendingBytes += inputBytes;
    } else {
      committed += prefixBytes + lineBytes;
      if (prefixBytes != 0) pendingLines = pendingBytes = 0;
    }
    prefixBytes = lineBytes = 0;
    inputBytes = 0;
    discarding = false;
    notifyAll();
  }

  private boolean room(int bytes) { return bytes <= ring.length - size - inFlight; }
  private void append(byte value) { ring[(head + size++) % ring.length] = value; }
  private void append(byte[] values) { for (byte value : values) append(value); }
  private byte[] lossReport() {
    return ("[wpilog] Dropped log output: lines=" + pendingLines + ", bytes=" + pendingBytes + "\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private void drain() {
    byte[] block = new byte[Math.min(8192, ring.length)];
    for (;;) {
      int count;
      synchronized (this) {
        // A resumed sink reports a completed drop episode even if no caller logs again.
        if (inputBytes == 0 && pendingLines != 0) {
          byte[] report = lossReport();
          if (room(report.length)) {
            append(report);
            committed += report.length;
            pendingLines = pendingBytes = 0;
          }
        }
        while (committed == 0) {
          if (closing && size == 0 && pendingLines == 0) {
            terminated = true;
            notifyAll();
            return;
          }
          try { wait(); }
          catch (InterruptedException ignored) { /* A producer never interrupts output I/O. */ }
          // Re-evaluate a newly completed loss episode before waiting again.
          if (inputBytes == 0 && pendingLines != 0) {
            byte[] report = lossReport();
            if (room(report.length)) {
              append(report);
              committed += report.length;
              pendingLines = pendingBytes = 0;
            }
          }
        }
        count = Math.min(committed, block.length);
        for (int i = 0; i < count; i++) block[i] = ring[(head + i) % ring.length];
        head = (head + count) % ring.length;
        size -= count;
        committed -= count;
        inFlight = count;
      }
      sink.write(block, 0, count);
      sink.flush();
      synchronized (this) {
        inFlight = 0;
        notifyAll();
      }
    }
  }

  synchronized long lostLines() { return droppedLines; }
  synchronized long lostBytes() { return droppedBytes; }
  synchronized int retainedBytes() { return size + inFlight; }
  boolean daemonThread() { return drain.isDaemon(); }

  /** Waiting is reserved for exit and tests, never a log producer or a periodic flush. */
  synchronized boolean awaitDrained(long timeout, TimeUnit unit) {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (size != 0 || inFlight != 0 || pendingLines != 0 || inputBytes != 0) {
      long left = deadline - System.nanoTime();
      if (left <= 0) return false;
      try { TimeUnit.NANOSECONDS.timedWait(this, left); }
      catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return true;
  }

  synchronized boolean finish(long timeout, TimeUnit unit) {
    if (!closing) {
      closing = true;
      if (inputBytes != 0) {
        if (!discarding && room(1)) { append((byte) '\n'); lineBytes++; }
        else if (!discarding) {
          size -= prefixBytes + lineBytes;
          prefixBytes = lineBytes = 0;
          discarding = true;
        }
        completeLine();
      }
      notifyAll();
    }
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (!terminated) {
      long left = deadline - System.nanoTime();
      if (left <= 0) return false;
      try { TimeUnit.NANOSECONDS.timedWait(this, left); }
      catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return true;
  }
}
