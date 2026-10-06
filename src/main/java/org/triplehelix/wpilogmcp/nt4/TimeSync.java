/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Optional;

/** Cristian's clock estimate: minimum RTT in a time window, newest measurement winning a tie. */
public final class TimeSync {
  public record Sample(long receivedUs, long roundTripUs, long offsetUs) {}
  private final long windowUs;
  private final ArrayDeque<Sample> samples = new ArrayDeque<>();

  public TimeSync(long windowUs) {
    if (windowUs <= 0) throw new IllegalArgumentException("Time window must be positive");
    this.windowUs = windowUs;
  }

  public static Sample measure(long sentUs, long receivedUs, long serverUs) {
    long rtt = Math.subtractExact(receivedUs, sentUs);
    if (rtt < 0) throw new IllegalArgumentException("Reply preceded request");
    long offset = Math.subtractExact(serverUs, Math.subtractExact(receivedUs, rtt / 2));
    return new Sample(receivedUs, rtt, offset);
  }

  public synchronized Sample add(long sentUs, long receivedUs, long serverUs) {
    var sample = measure(sentUs, receivedUs, serverUs);
    samples.addLast(sample);
    return best(receivedUs).orElseThrow();
  }

  public synchronized Optional<Sample> best(long nowUs) {
    samples.removeIf(s -> nowUs - s.receivedUs() >= windowUs);
    return samples.stream().min(Comparator.comparingLong(Sample::roundTripUs)
        .thenComparing(Comparator.comparingLong(Sample::receivedUs).reversed()));
  }

  public synchronized void clear() { samples.clear(); }
}
