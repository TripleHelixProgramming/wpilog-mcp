/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Instant's optional fractional digits must not reverse the reported age of a pending receipt. */
class ReplicaPublicationOrderingTest {
  @ParameterizedTest
  @CsvSource({
      "2026-10-20T12:00:00Z, 2026-10-20T12:00:00.001Z",
      "2026-10-20T12:00:00.100Z, 2026-10-20T12:00:00.100001Z",
      "2026-10-20T12:00:00.100100Z, 2026-10-20T12:00:00.100100001Z"
  })
  void fractionalPrecisionDoesNotPutALaterFailureBeforeAnEarlierOne(String earlier, String later) {
    assertTrue(Instant.parse(earlier).isBefore(Instant.parse(later)),
        "The independent fixture timestamps differ by a positive millisecond, microsecond or nanosecond");
    var owner = new ReplicaPublications();
    owner.begin("11111111-1111-1111-1111-111111111111");
    // Insert later first: both insertion order and lexical ISO-string order give the wrong answer.
    owner.observed(event("later", later));
    owner.observed(event("earlier", earlier));
    owner.finish(true, null);

    var result = owner.snapshot();
    assertEquals(ReplicaPublications.State.READY, result.state());
    assertEquals(2, result.knownPendingCount());
    assertEquals(Integer.valueOf(2), result.totalPendingCount());
    assertTrue(result.oldestTimeComplete());
    assertEquals(earlier, result.oldestFailedAt(),
        "oldest_failed_at must name the earlier instant, regardless of ISO fractional precision");
    assertEquals(List.of("earlier", "later"),
        result.entries().stream().map(ReplicaPublications.Entry::sessionId).toList(),
        "Pending rows must sort chronologically before their identity tie-breakers");
  }

  private static ReplicaPublications.Event event(String session, String instant) {
    var cause = new ReplicaPublications.Cause("RESOURCE_LIMIT",
        "Replica catalog metadata capacity is exhausted.", instant, instant, true);
    return new ReplicaPublications.Event(Path.of(session).resolve("session.json"),
        new ReplicaPublications.Entry(session, cause));
  }
}
