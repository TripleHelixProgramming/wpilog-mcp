/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.util.Map;
import java.util.List;

/** Recorder facts, frozen on its thread at the flush tick, not recomputed by tool requests. */
public record CaptureStats(int topicCount, long records, long bytes,
    Map<String, TopicCost.Snapshot> costs, List<String> excluded, Map<String, Long> thinnedUs) {
  public CaptureStats {
    costs = Map.copyOf(costs); excluded = List.copyOf(excluded); thinnedUs = Map.copyOf(thinnedUs);
  }
}
