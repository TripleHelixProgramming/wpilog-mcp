/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.PullGate;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

/**
 * A fresh JVM gives SimpleLogger the real blocked stderr without changing the test runner's
 * streams. Rollover makes a due cost report reachable from close's pending-force barrier.
 */
class CaptureBlockedLoggingTest {
  @TempDir Path directory;

  @Test void aCostReportDuringCloseCannotHoldTheNextListenerValues() throws Exception {
    var output = directory.resolve("child-output.txt");
    var process = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=info", "-cp",
        System.getProperty("java.class.path"), CaptureProcess.class.getName(), directory.toString())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Blocked-output capture process timed out");
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally {
      process.destroyForcibly();
    }
  }

  public static final class CaptureProcess {
    private static final URI PEER = URI.create("ws://127.0.0.1:0/nt/synthetic");
    private static final Announce TOPIC = new Announce("/value", 1, "raw", null, new JsonObject());

    public static void main(String[] args) {
      var result = System.out;
      try {
        exercise(Path.of(args[0]));
        result.println("PASS: all five payloads survived a close while stderr was blocked");
      } catch (Throwable failure) {
        failure.printStackTrace(result);
        System.exit(1);
      }
    }

    private static void exercise(Path directory) throws Exception {
      var sink = new HeldOutput();
      System.setErr(new PrintStream(sink, true, StandardCharsets.UTF_8));
      // The initial regression compiles before the buffering owner exists and exercises today's
      // direct stderr. Its absence cannot make this test pass: the close then blocks below.
      Class<?> buffered = null;
      try {
        buffered = Class.forName("org.triplehelix.wpilogmcp.logging.BufferedLogOutput");
        buffered.getMethod("install").invoke(null);
      } catch (ClassNotFoundException notImplemented) {
        // Failing-first baseline.
      }

      var loop = new ManualScheduler();
      var forced = new CountDownLatch(1);
      var releaseForce = new CountDownLatch(1);
      var heldOnce = new AtomicBoolean();
      var listenerThread = new AtomicReference<Thread>();
      var executor = Executors.newSingleThreadExecutor(action -> {
        var thread = new Thread(action, "synthetic-nt4-listener");
        thread.setDaemon(true);
        listenerThread.set(thread);
        return thread;
      });
      var opened = new ArrayList<Path>();
      var writer = new CaptureWriter(
          Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC),
          loop, CapturePolicy.ALL, new CaptureWriter.Observer() {
            @Override public Path create(String address, Instant start) {
              return directory.resolve("capture.wpilog");
            }
            @Override public void opened(CaptureWriter.Session session) {
              opened.add(session.path());
            }
          }, 450, (path, id, resume) -> new WpilogOutput(path, id, resume) {
            @Override public void flush() throws IOException {
              // Closing a member also flushes it, on the listener. Hold only the first
              // asynchronous force so rollover can establish the due report before close.
              if (Thread.currentThread().getName().equals("capture-flush")
                  && heldOnce.compareAndSet(false, true)) {
                forced.countDown();
                try {
                  if (!releaseForce.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Synthetic force was not released");
                  }
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                  throw new IOException(failure);
                }
              }
              super.flush();
            }
          });
      var listener = CaptureService.listener(writer, new PullGate(loop::nowUs, 0));
      java.util.concurrent.Future<?> following = null;
      try {
        executor.submit(() -> {
          listener.connected(PEER, "networktables.first.wpi.edu");
          listener.timeSync(1_000_000, 0);
          listener.announce(TOPIC);
          listener.value(TOPIC, frame(1_000_000, 1), 0);
          loop.elapse(CaptureWriter.REPORT_PERIOD_US);
          listener.value(TOPIC, frame(301_000_000, 2), CaptureWriter.REPORT_PERIOD_US);
          assertEquals(2, opened.size(), "The second 200-byte payload rolls into a new member");
          loop.advance(CaptureWriter.FLUSH_PERIOD_US);
        }).get(5, TimeUnit.SECONDS);
        assertTrue(forced.await(5, TimeUnit.SECONDS), "The new member must have a pending force");

        following = executor.submit(() -> {
          listener.disconnected();
          listener.connected(PEER, "networktables.first.wpi.edu");
          listener.timeSync(301_250_000, 300_250_000);
          listener.announce(TOPIC);
          for (int value = 3; value <= 5; value++) {
            listener.value(TOPIC, frame(301_250_000 + value, value), 300_250_000 + value);
          }
          listener.disconnected();
        });
        // The force is still held, so reaching this actual method proves the cost report will
        // run through close, not the scheduled finish callback left in ManualScheduler's queue.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Arrays.stream(listenerThread.get().getStackTrace())
            .noneMatch(frame -> frame.getMethodName().equals("finishForce"))) {
          assertTrue(System.nanoTime() < deadline, "Close did not reach its pending-force barrier");
          Thread.sleep(1);
        }
        releaseForce.countDown();
        assertTrue(sink.entered.await(5, TimeUnit.SECONDS),
            "The real cost-summary output must reach the held stderr sink");
        var completed = following;
        assertDoesNotThrow(() -> completed.get(200, TimeUnit.MILLISECONDS),
            "Blocked stderr prevented the close and following listener values from completing");
      } finally {
        releaseForce.countDown();
        sink.release.countDown();
        if (following != null) following.get(5, TimeUnit.SECONDS);
        executor.submit(writer::close).get(5, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        if (buffered != null) buffered.getMethod("drainOnExit").invoke(null);
      }

      String logged = sink.text();
      assertEquals(1, logged.lines().filter(line -> line.contains("Capture cost:")).count(),
          "The test must encounter exactly one real cost summary during close");
      assertTrue(logged.contains("records=2"), "The close summary includes the first two payloads");
      var values = new ArrayList<Integer>();
      var times = new ArrayList<Long>();
      for (var path : new LinkedHashSet<>(opened)) {
        for (var record : org.triplehelix.wpilogmcp.fixtures.CaptureRecords.robot(path)) {
          byte[] payload = record.getRaw();
          assertEquals(200, payload.length);
          int expected = values.size() + 1;
          for (byte value : payload) assertEquals(expected, Byte.toUnsignedInt(value));
          values.add(expected);
          times.add(record.getTimestamp());
        }
      }
      assertEquals(java.util.List.of(1, 2, 3, 4, 5), values,
          "Both values before close and all three after it are independently checked");
      assertEquals(java.util.List.of(1_000_000L, 301_000_000L, 301_250_003L,
          301_250_004L, 301_250_005L), times);
    }

    private static ValueFrame frame(long timestampUs, int value) {
      byte[] bytes = new byte[200];
      Arrays.fill(bytes, (byte) value);
      return new ValueFrame(1, timestampUs, 5, bytes);
    }

    private static final class HeldOutput extends OutputStream {
      private final CountDownLatch entered = new CountDownLatch(1);
      private final CountDownLatch release = new CountDownLatch(1);
      private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

      @Override public void write(int value) throws IOException {
        entered.countDown();
        try {
          if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("Synthetic output remained held");
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IOException(failure);
        }
        synchronized (bytes) { bytes.write(value); }
      }

      String text() {
        synchronized (bytes) { return bytes.toString(StandardCharsets.UTF_8); }
      }
    }
  }
}
