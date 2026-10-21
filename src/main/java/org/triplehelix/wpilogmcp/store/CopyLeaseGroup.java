/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.triplehelix.wpilogmcp.sync.FileTransfer;

/**
 * A FileTransfer step owns the parent lease, but an interrupted store-queue waiter can return
 * before its admitted write does. Track the underlying store completions so parent close waits
 * for that actual I/O unwind. These futures are private and never canceled on a response timeout.
 */
final class CopyLeaseGroup {
  private final Set<CompletableFuture<?>> writes = ConcurrentHashMap.newKeySet();

  FileTransfer.Reservation own(DiskSpaceBudgets.Reservation physical) {
    var released = new AtomicBoolean();
    return () -> {
      if (!released.compareAndSet(false, true)) return;
      var admitted = List.copyOf(writes);
      CompletableFuture.allOf(admitted.toArray(CompletableFuture[]::new))
          .whenComplete((ignored, failure) -> physical.close());
    };
  }

  /**
   * Register before returning to the FileTransfer owner. Its busy-close protocol cannot release
   * the parent until this call returns, while completion of this uncancelled future means the
   * store operation and its cross-process lock have both unwound.
   */
  <T> T await(CompletableFuture<T> write) throws IOException {
    writes.add(write);
    write.whenComplete((ignored, failure) -> writes.remove(write));
    try { return write.get(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted copy placement", e); }
    catch (java.util.concurrent.ExecutionException e) {
      throw new IOException("Copy placement failed: " + e.getCause().getMessage(), e.getCause());
    }
  }
}
