/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopyLeaseGroupTest {
  @TempDir Path directory;
  @Test void interruptedStoreWaiterCannotReleaseAStillWritingQueueTurn() throws Exception {
    try (var owner = new DiskSpaceBudgets(filesystem -> 500)) {
      var budget = owner.store(directory); budget.configureCapture(new DiskSpaceBudgets.Policy(0, 0, 0));
      var group = new CopyLeaseGroup(); var parent = group.own(budget.reserveCopy(80));
      var underlying = new CompletableFuture<Void>();
      var entered = new CountDownLatch(1); var writeStarted = new CountDownLatch(1);
      var release = new CountDownLatch(1); var stopped = new CountDownLatch(1);
      var failure = new AtomicReference<Throwable>();
      var writer = new Thread(() -> {
        try {
          writeStarted.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
          assertEquals(80, budget.snapshot().reservedBytes());
          Files.write(directory.resolve("copied.bin"), new byte[80]);
          underlying.complete(null);
        } catch (Throwable problem) { underlying.completeExceptionally(problem); }
      }, "copy-unwind-fixture");
      writer.setDaemon(true);
      var waiter = new Thread(() -> {
        try {
          entered.countDown(); group.await(underlying);
          failure.set(new AssertionError("Interrupted waiter unexpectedly succeeded"));
        } catch (IOException expected) {
          if (!(expected.getCause() instanceof InterruptedException)) failure.set(expected);
        } finally { parent.close(); stopped.countDown(); }
      }, "copy-response-fixture");
      waiter.setDaemon(true);
      try {
        writer.start(); assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
        waiter.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); waiter.interrupt();
        assertTrue(stopped.await(5, TimeUnit.SECONDS)); assertNull(failure.get());
        assertEquals(80, budget.snapshot().reservedBytes(), "The queued writer still owns the interrupted response's reservation");
        release.countDown(); underlying.get(5, TimeUnit.SECONDS); writer.join(5000);
        assertEquals(80, Files.size(directory.resolve("copied.bin"))); assertEquals(0, budget.snapshot().reservedBytes());
        parent.close(); assertEquals(0, budget.snapshot().reservedBytes());
      } finally { release.countDown(); waiter.interrupt(); writer.join(5000); waiter.join(5000); parent.close(); }
    }
  }
}
