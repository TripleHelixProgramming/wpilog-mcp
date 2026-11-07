/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Blocking the actual sink tests the owner boundary without forbidding an ordinary log call. */
class StoreQueueBlockedOutputTest {
  @TempDir Path directory;

  @Test void anOverBudgetReportCannotDelayTheNextCaptureTurn() throws Exception {
    var output = directory.resolve("process.log");
    var process = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "-cp", System.getProperty("java.class.path"), Probe.class.getName())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Blocked-output child must terminate");
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally { process.destroyForcibly(); }
  }

  public static class Probe {
    public static void main(String[] args) throws Exception {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      System.setErr(new PrintStream(new OutputStream() {
        @Override public void write(int value) {
          entered.countDown();
          try { release.await(); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
      }, true, StandardCharsets.UTF_8));
      // This regression compiles and runs before the new owner exists.
      try { Class.forName("org.triplehelix.wpilogmcp.logging.BufferedLogOutput")
          .getMethod("install").invoke(null); }
      catch (ClassNotFoundException oldImplementation) { /* Direct stderr is the failing baseline. */ }
      // Disable automatic observation so completion deterministically wins the report claim.
      // The consumer is a real SimpleLogger call, never a test-owned blocking callback.
      var queue = new StoreQueue(turn -> LoggerFactory.getLogger(StoreQueue.class).warn(
          "Store {} turn exceeded its budget: duration_us={}, budget_us={}",
          turn.kind(), turn.durationUs(), StoreQueue.TURN_BUDGET_US), false);
      var ended = new CountDownLatch(1);
      var started = new CountDownLatch(1);
      var capture = new CountDownLatch(1);
      try {
        queue.importTurn("held-import", () -> {
          started.countDown();
          try { Thread.sleep(1_050); }
          catch (InterruptedException failure) { throw new AssertionError(failure); }
          finally { ended.countDown(); }
        });
        if (!started.await(2, TimeUnit.SECONDS)) throw new AssertionError("Import did not start");
        queue.capture("admission", capture::countDown);
        if (!ended.await(3, TimeUnit.SECONDS)) throw new AssertionError("Long turn did not end");
        if (!entered.await(1, TimeUnit.SECONDS)) throw new AssertionError("Real stderr was not reached");
        if (!capture.await(200, TimeUnit.MILLISECONDS)) {
          throw new AssertionError("Capture must start within 200 ms after the long turn ends while stderr is blocked");
        }
        if (queue.metrics().overBudgetTurns() != 1) throw new AssertionError("Exactly one over-budget turn");
      } catch (Throwable failure) {
        System.out.println(failure.getMessage());
        throw failure;
      } finally {
        release.countDown();
        queue.shutdown();
        if (!queue.awaitTermination(2, TimeUnit.SECONDS)) throw new AssertionError("Store owner did not close");
      }
    }
  }
}
