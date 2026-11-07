/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Literal turns pin the existing rule independently of any tool or stream implementation. */
class AngleUnwrapperTest {
  @Test void firstValueKeepsItsRecordedTurnAndLaterValuesTakeTheShortestStep() {
    var angles = new AngleUnwrapper(360);
    assertEquals(540, angles.apply(540));
    assertEquals(541, angles.apply(-179));
    assertEquals(539, angles.apply(179));
  }

  @Test void exactHalfPeriodsRetainNearestEvenWholeTurnTies() {
    var angles = new AngleUnwrapper(360);
    assertEquals(0, angles.apply(0));
    assertEquals(180, angles.apply(180));
    assertEquals(0, angles.apply(0));
    assertEquals(-180, angles.apply(540));
  }

  @Test void gapsRemainNonFiniteWithoutResettingContinuity() {
    var angles = new AngleUnwrapper(360);
    assertEquals(179, angles.apply(179));
    assertTrue(Double.isNaN(angles.apply(Double.NaN)));
    assertEquals(Double.POSITIVE_INFINITY, angles.apply(Double.POSITIVE_INFINITY));
    assertEquals(Double.NEGATIVE_INFINITY, angles.apply(Double.NEGATIVE_INFINITY));
    assertEquals(181, angles.apply(-179));
    assertEquals(183, angles.apply(-177));
  }

  @Test void aNewTraversalStartsAtItsOwnFirstValue() {
    var first = new AngleUnwrapper(360);
    assertEquals(179, first.apply(179));
    assertEquals(181, first.apply(-179));
    var next = new AngleUnwrapper(360);
    assertEquals(-179, next.apply(-179));
    assertEquals(-177, next.apply(-177));
  }

  @Test void invalidPeriodsCannotCreateSilentNonFiniteOutput() {
    for (double period : new double[] {0, -360, Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(IllegalArgumentException.class, () -> new AngleUnwrapper(period));
  }
}
