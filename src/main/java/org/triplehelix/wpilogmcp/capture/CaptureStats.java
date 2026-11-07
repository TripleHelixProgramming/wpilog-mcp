/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.util.Map;
import java.util.List;

/** Recorder facts, frozen on its thread at the flush tick, not recomputed by tool requests. */
public record CaptureStats(int topicCount, long records, long bytes,
    Map<String, TopicCost.Snapshot> costs, List<String> excluded, Map<String, Long> thinnedUs,
    List<org.triplehelix.wpilogmcp.capture.context.ProviderStatus> providers,
    org.triplehelix.wpilogmcp.capture.context.ProviderStatus.KernelClock kernelClock) {
  public CaptureStats(int topicCount, long records, long bytes, Map<String, TopicCost.Snapshot> costs,
      List<String> excluded, Map<String, Long> thinnedUs) {
    this(topicCount, records, bytes, costs, excluded, thinnedUs, List.of(), null);
  }
  public CaptureStats {
    providers = providers == null ? List.of() : List.copyOf(providers);
    costs = Map.copyOf(costs); excluded = List.copyOf(excluded); thinnedUs = Map.copyOf(thinnedUs);
  }
}
