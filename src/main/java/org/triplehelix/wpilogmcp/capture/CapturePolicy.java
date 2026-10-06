/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.util.List;
import java.util.Map;

/** Exclusion wins; the longest matching thinning prefix wins. No sampling is implicit. */
public record CapturePolicy(List<String> exclude, Map<String, Long> thinUs) {
  public static final CapturePolicy ALL = new CapturePolicy(List.of(), Map.of());
  public CapturePolicy {
    exclude = List.copyOf(exclude); thinUs = Map.copyOf(thinUs);
    if (thinUs.values().stream().anyMatch(v -> v <= 0)) throw new IllegalArgumentException("Thinning period must be positive");
  }
  public boolean excluded(String topic) { return exclude.stream().anyMatch(topic::startsWith); }
  public long periodUs(String topic) {
    return thinUs.entrySet().stream().filter(e -> topic.startsWith(e.getKey()))
        .max(java.util.Comparator.comparingInt(e -> e.getKey().length())).map(Map.Entry::getValue).orElse(0L);
  }
}
