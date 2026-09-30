/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogDirectory.MatchType;

/** The match type codes list_available_logs documents, and the ones file names carry. */
class MatchTypeCodesTest {

  @Test
  @DisplayName("every documented code and friendly name maps to its type")
  void codes() {
    assertEquals(MatchType.PRACTICE, MatchType.fromString("p"));
    assertEquals(MatchType.QUALIFICATION, MatchType.fromString("q"));
    assertEquals(MatchType.QUALIFICATION, MatchType.fromString("qm"), "TBA's code, as in qm42");
    assertEquals(MatchType.QUALIFICATION, MatchType.fromString("Qualification"));
    assertEquals(MatchType.QUARTERFINAL, MatchType.fromString("qf"));
    assertEquals(MatchType.SEMIFINAL, MatchType.fromString("sf"));
    assertEquals(MatchType.FINAL, MatchType.fromString("f"), "the finals code");
    assertEquals(MatchType.FINAL, MatchType.fromString("Final"));
    assertEquals(MatchType.ELIMINATION, MatchType.fromString("e"));
    assertEquals(MatchType.ELIMINATION, MatchType.fromString("Elimination"));
    assertNull(MatchType.fromString("x"));
    assertNull(MatchType.fromString(""));
  }
}
