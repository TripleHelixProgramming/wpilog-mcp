/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A previous turn's cancellation is not permission to interrupt another caller's filesystem I/O. */
class StoreQueueInterruptIsolationTest {
  @TempDir Path directory;

  @Test void anInterruptedTurnCannotPoisonAlreadyQueuedFilesystemWork() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var inherited = new CompletableFuture<Boolean>(); var written = new CompletableFuture<Void>();
    var target = directory.resolve("independent-turn.bin"); var queue = new StoreQueue();
    try {
      queue.importTurn("interrupted-caller", () -> {
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException unexpected) { throw new AssertionError(unexpected); }
        Thread.currentThread().interrupt();
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      // Queue this before releasing the first turn, so idle wait cannot accidentally clear it.
      queue.capture("independent-capture", () -> {
        inherited.complete(Thread.currentThread().isInterrupted());
        try (var output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
          output.write(ByteBuffer.wrap(new byte[] {11, 22, 33}));
          written.complete(null);
        } catch (Throwable failure) { written.completeExceptionally(failure); }
      });
      release.countDown();
      assertAll(
          () -> assertFalse(inherited.get(5, TimeUnit.SECONDS), "A completed turn's interrupt must not reach the next capture"),
          () -> assertDoesNotThrow(() -> written.get(5, TimeUnit.SECONDS), "Independent filesystem work must not fail with ClosedByInterruptException"),
          () -> assertArrayEquals(new byte[] {11, 22, 33}, Files.readAllBytes(target)));
    } finally {
      release.countDown(); queue.shutdown(); assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}
