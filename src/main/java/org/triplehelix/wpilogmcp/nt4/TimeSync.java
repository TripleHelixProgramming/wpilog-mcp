/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Optional;

/** Cristian's clock estimate: minimum RTT in a time window, newest measurement winning a tie. */
public final class TimeSync {
  public record Sample(long receivedUs, long roundTripUs, long offsetUs) {}
  private final long windowUs;
  private volatile List<Sample> samples = List.of();

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
    var next = new ArrayList<>(samples.stream().filter(s -> receivedUs - s.receivedUs() < windowUs).toList());
    next.add(sample);
    samples = List.copyOf(next);
    return best(receivedUs).orElseThrow();
  }

  /** Readers use a published window; a scrape cannot wait for the NT4 writer's monitor. */
  public Optional<Sample> best(long nowUs) {
    return samples.stream().filter(s -> nowUs - s.receivedUs() < windowUs).min(Comparator.comparingLong(Sample::roundTripUs)
        .thenComparing(Comparator.comparingLong(Sample::receivedUs).reversed()));
  }

  public synchronized void clear() { samples = List.of(); }
}
