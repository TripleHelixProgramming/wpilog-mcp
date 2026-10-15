/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem.Code;

class VisualizationReadFailureTest {
  @Test void movingCatalogRetainsItsRetryFlagWhileChangedHistoryStops() {
    var moving = VisualizationReadFailure.of(new ReadException(Code.FILE_CHANGED, "retry this inventory", true));
    assertEquals(409, moving.status()); assertEquals("FILE_CHANGED", moving.code()); assertTrue(moving.retryable());
    assertEquals("retry this inventory", moving.message());
    var history = VisualizationReadFailure.of(new ReadException(Code.FILE_CHANGED, "writer changed", false));
    assertEquals(409, history.status()); assertEquals("HISTORY_UNAVAILABLE", history.code()); assertFalse(history.retryable());
  }
  @Test void admissionLimitsClosureAndAccessRetainTheirNativeCodes() {
    var codes = new Code[] {Code.RESOURCE_LIMIT, Code.ACCESS_DENIED, Code.CLOSED, Code.FOLLOW_ENDED, Code.BUSY, Code.MALFORMED_RECORD};
    var statuses = new int[] {413, 403, 410, 410, 503, 400};
    for (int i = 0; i < codes.length; i++) for (boolean retryable : new boolean[] {false, true}) {
      var failure = VisualizationReadFailure.of(new ReadException(codes[i], "explained", retryable));
      assertEquals(statuses[i], failure.status()); assertEquals(codes[i].name(), failure.code());
      assertEquals("explained", failure.message()); assertEquals(retryable, failure.retryable());
    }
  }
}
