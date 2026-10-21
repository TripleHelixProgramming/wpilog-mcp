/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Import callbacks stage off the owner now. Lifecycle tests that require an occupied store
 * therefore hold a real turn, without adding a production blocking hook or assuming its lane.
 */
public final class StoreQueueTestBarrier {
  private StoreQueueTestBarrier() { }

  public static CompletableFuture<Void> hold(LogStore store, CountDownLatch entered,
      CountDownLatch release) {
    return store.captureAsync(io -> {
      entered.countDown();
      try {
        if (!release.await(30, TimeUnit.SECONDS)) throw new IOException("Store test barrier was not released");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt(); throw new IOException("Store test barrier interrupted", interrupted);
      }
      return null;
    });
  }
}
