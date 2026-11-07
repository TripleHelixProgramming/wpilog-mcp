/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.IntToLongFunction;

/** One permutation per entry and fixed source version, shared by concurrent snapshots/cursors.
 * SharedTimeOrders closes this cache after its final lease, so no sort races budget release.
 * Primitive heapsort uses twelve bytes per record at peak and retains four, without boxing. */
public final class TimeOrderCache implements AutoCloseable {
  private final SortBudget budget;
  private final ConcurrentHashMap<String, CompletableFuture<int[]>> orders = new ConcurrentHashMap<>();
  private long retainedBytes;

  public TimeOrderCache(SortBudget budget) { this.budget = budget; }

  public int[] order(String name, int size, IntToLongFunction timestamp) throws IOException {
    var candidate = new CompletableFuture<int[]>();
    var existing = orders.putIfAbsent(name, candidate);
    if (existing == null) {
      long workspace = size * 12L;
      boolean reserved = false;
      try {
        budget.reserve(workspace); reserved = true;
        long[] timestamps = new long[size]; int[] ordinals = new int[size];
        for (int i = 0; i < size; i++) {
          ordinals[i] = i; timestamps[i] = timestamp.applyAsLong(i);
        }
        sort(ordinals, timestamps);
        synchronized (this) { retainedBytes += size * 4L; }
        budget.release(size * 8L); reserved = false;
        candidate.complete(ordinals); return ordinals;
      } catch (Throwable e) {
        if (reserved) budget.release(workspace);
        candidate.completeExceptionally(e); orders.remove(name, candidate); throw e;
      }
    }
    try { return existing.get(); }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt(); throw new IOException("Interrupted while ordering " + name, e);
    } catch (ExecutionException e) {
      var cause = e.getCause();
      if (cause instanceof IOException io) throw io;
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new IOException("Could not order " + name, cause);
    }
  }

  private static void sort(int[] ordinals, long[] timestamps) {
    for (int i = ordinals.length / 2 - 1; i >= 0; i--) sift(ordinals, timestamps, i, ordinals.length);
    for (int end = ordinals.length - 1; end > 0; end--) {
      int value = ordinals[0]; ordinals[0] = ordinals[end]; ordinals[end] = value;
      sift(ordinals, timestamps, 0, end);
    }
  }
  private static void sift(int[] a, long[] times, int root, int end) {
    while (root < end / 2) {
      int child = root * 2 + 1;
      if (child + 1 < end && compare(a[child], a[child + 1], times) < 0) child++;
      if (compare(a[root], a[child], times) >= 0) return;
      int value = a[root]; a[root] = a[child]; a[child] = value; root = child;
    }
  }
  private static int compare(int a, int b, long[] times) {
    int time = Long.compare(times[a], times[b]); return time == 0 ? Integer.compare(a, b) : time;
  }

  @Override public void close() {
    orders.clear();
    long bytes;
    synchronized (this) { bytes = retainedBytes; retainedBytes = 0; }
    budget.release(bytes);
  }
}
