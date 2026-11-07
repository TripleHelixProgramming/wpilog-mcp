/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.config.MetricsConfig;
import org.triplehelix.wpilogmcp.store.StoreRegistry;

/** Pending publication gauges expose complete counts with only opaque store identifiers. */
class ReplicaPublicationMetricsTest {
  private static final String METRIC = "wpilog_replica_pending_publications";
  private static final String FIRST = "11111111-1111-4111-8111-111111111111";
  private static final String SECOND = "22222222-2222-4222-8222-222222222222";

  @Test void knownPendingCountsAreExactAndZeroIsNotConfusedWithUnknown() {
    String text = render(List.of(new StoreRegistry.PendingCount(FIRST, 2),
        new StoreRegistry.PendingCount(SECOND, 0)));
    assertEquals(List.of(METRIC + "{store_id=\"" + FIRST + "\"} 2",
        METRIC + "{store_id=\"" + SECOND + "\"} 0"),
        text.lines().filter(line -> line.startsWith(METRIC + "{")).toList());
    assertEquals(1, text.lines().filter(line -> line.equals("# TYPE " + METRIC + " gauge")).count());
    assertEquals(1, text.lines().filter(line -> line.startsWith("# HELP " + METRIC + " ")).count());
  }

  @Test void unknownCountsHaveNoFabricatedMetricOrStoreLabel() {
    String text = render(List.of());
    assertFalse(text.contains(METRIC), "No complete inventory is not a zero pending count");
    assertFalse(text.contains(FIRST));
    assertFalse(text.contains(SECOND));
  }

  @Test void publicationSamplesCarryOnlyTheOpaqueStoreLabel() {
    String text = render(List.of(new StoreRegistry.PendingCount(FIRST, 2)));
    var samples = text.lines().filter(line -> line.startsWith(METRIC + "{")).toList();
    assertEquals(1, samples.size());
    assertEquals(METRIC + "{store_id=\"" + FIRST + "\"} 2", samples.get(0),
        "Paths, session identities, causes and robot facts do not belong in the aggregate gauge");
  }

  @Test void copiedStoreIdsOmitOnlyTheAmbiguousSeriesWithoutSummingOrChoosing() {
    String text = render(List.of(new StoreRegistry.PendingCount(FIRST, 1),
        new StoreRegistry.PendingCount(FIRST, 2), new StoreRegistry.PendingCount(SECOND, 3)));
    assertEquals(List.of(METRIC + "{store_id=\"" + SECOND + "\"} 3"),
        text.lines().filter(line -> line.startsWith(METRIC + "{")).toList(),
        "Two configured copies of one store id cannot produce duplicate, summed or arbitrarily selected series");
    assertFalse(text.contains(FIRST), "An ambiguous store id must have no pending-count sample");
  }

  private static String render(List<StoreRegistry.PendingCount> pending) {
    return MetricsEndpoint.render(MetricsConfig.DEFAULT, null, MetricsEndpoint.Components.NONE,
        new MetricsEndpoint.Jvm(List.of(), List.of()), new StoreRegistry.QueueMetrics(0, 0),
        List.of(), List.of(), pending);
  }
}
