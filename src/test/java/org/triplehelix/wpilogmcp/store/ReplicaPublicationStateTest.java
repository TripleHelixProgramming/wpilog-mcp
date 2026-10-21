/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** An incomplete bounded inventory cannot turn unknown pending receipts into a false zero. */
class ReplicaPublicationStateTest {
  @Test void anUnaddressableSessionIdMakesTheInventoryIncompleteWithoutRetainingTheRow() {
    var owner = new ReplicaPublications(); owner.begin("store");
    owner.observed(new ReplicaPublications.Event(Path.of("receipt"),
        new ReplicaPublications.Entry("s".repeat(1025), ReplicaPublications.unknown())));
    owner.finish(true, null);
    assertEquals(ReplicaPublications.State.UNAVAILABLE, owner.snapshot().state());
    assertEquals("RESOURCE_LIMIT", owner.snapshot().failureCode());
    assertEquals(0, owner.snapshot().knownPendingCount());
    assertNull(owner.snapshot().totalPendingCount());
  }

  @Test void anInventoryBoundDoesNotInventACatalogCapacityFailure() {
    var owner = new ReplicaPublications();
    owner.begin("store");
    owner.finish(false, "RESOURCE_LIMIT");
    assertEquals(ReplicaPublications.State.UNAVAILABLE, owner.snapshot().state());
    assertNull(owner.snapshot().totalPendingCount());
    assertEquals("Pending publication inventory exceeds its metadata bound.", owner.snapshot().failureMessage());
    assertEquals("Replica catalog metadata capacity is exhausted.", ReplicaPublications.message("RESOURCE_LIMIT"));
  }

  @Test void unavailableAndLegacyCausesKeepTheirUnknownTimeAndIgnoreDiskText() {
    var owner = new ReplicaPublications(); owner.begin("store");
    var known = new ReplicaPublications.Cause("RESOURCE_LIMIT", ReplicaPublications.message("RESOURCE_LIMIT"),
        "2026-10-20T12:00:00Z", "2026-10-20T12:00:30Z", true);
    owner.observed(new ReplicaPublications.Event(Path.of("known"), new ReplicaPublications.Entry("known", known)));
    owner.observed(new ReplicaPublications.Event(Path.of("legacy"), new ReplicaPublications.Entry("legacy",
        ReplicaPublications.recorded("/private/secret robot identity", "bad", "bad"))));
    owner.finish(false, "SOURCE_UNAVAILABLE");
    assertEquals(2, owner.snapshot().knownPendingCount());
    assertNull(owner.snapshot().totalPendingCount());
    assertNull(owner.snapshot().oldestFailedAt());
    assertFalse(owner.snapshot().oldestTimeComplete());
    assertEquals("CAUSE_NOT_RECORDED", owner.snapshot().entries().get(0).cause().code());
    assertEquals("The failure cause was not recorded.", owner.snapshot().entries().get(0).cause().message());
  }

  @Test void theObservationCountBoundIncludesSuccessfulRemovalTombstones() {
    var owner = new ReplicaPublications(); owner.begin("store");
    for (int i = 0; i <= 32768; i++) owner.observed(new ReplicaPublications.Event(Path.of("r" + i), null));
    owner.finish(true, null);
    assertEquals(ReplicaPublications.State.UNAVAILABLE, owner.snapshot().state());
    assertEquals("RESOURCE_LIMIT", owner.snapshot().failureCode());
    assertEquals(0, owner.snapshot().knownPendingCount());
    assertNull(owner.snapshot().totalPendingCount());
  }

  @Test void theAccountedByteBoundKeepsKnownRowsAndNeverClaimsACompleteSmallerTotal() {
    var owner = new ReplicaPublications(); owner.begin("store");
    String id = "s".repeat(1024);
    long budget = 32L * 1024 * 1024;
    int expected = 0;
    for (int i = 0; i < 12000; i++) {
      String path = "r" + i;
      // The design charges each retained entry and each seen-key independently, with UTF-16 characters.
      long bytes = 768L + 128L + 4L * path.length() + 2L * id.length();
      if (bytes <= budget) { expected++; budget -= bytes; } else break;
    }
    for (int i = 0; i < 12000; i++) owner.observed(new ReplicaPublications.Event(Path.of("r" + i),
        new ReplicaPublications.Entry(id, ReplicaPublications.unknown())));
    owner.finish(true, null);
    assertEquals(expected, owner.snapshot().knownPendingCount());
    assertEquals(ReplicaPublications.State.UNAVAILABLE, owner.snapshot().state());
    assertEquals("RESOURCE_LIMIT", owner.snapshot().failureCode());
    assertNull(owner.snapshot().totalPendingCount());
  }
}
