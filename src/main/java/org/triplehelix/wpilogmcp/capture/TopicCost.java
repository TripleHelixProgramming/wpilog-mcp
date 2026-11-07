/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

/** Fixed sixty one-second buckets bound accounting memory even for an all-changes subscription. */
public final class TopicCost {
  public record Snapshot(long records, long bytes, long dropped, long thinned, double recordsPerSecond, double bytesPerSecond) {}
  private final long[] seconds = new long[60], counts = new long[60], sizes = new long[60];
  private long records, bytes, dropped, thinned;
  void drop() { dropped++; }
  void thin() { thinned++; }
  void add(long nowUs, int size) {
    long second = nowUs / 1_000_000;
    int slot = Math.floorMod(second, 60);
    if (seconds[slot] != second) { seconds[slot] = second; counts[slot] = 0; sizes[slot] = 0; }
    counts[slot]++; sizes[slot] += size; records++; bytes += size;
  }
  Snapshot snapshot(long nowUs) {
    long second = nowUs / 1_000_000, count = 0, size = 0;
    for (int i = 0; i < 60; i++) if (seconds[i] <= second && seconds[i] > second - 60) {
      count += counts[i]; size += sizes[i];
    }
    return new Snapshot(records, bytes, dropped, thinned, count / 60.0, size / 60.0);
  }
}
