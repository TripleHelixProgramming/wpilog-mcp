/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mixing an exact zero with derived signed zeros must not break chronological ordering. */
class TimestampedValueProvenanceTest {
  @Test void mixedProvenanceRetainsConsistentSignedZeroOrder() {
    var negative = new TimestampedValue(-0.0, "negative");
    var positive = new TimestampedValue(+0.0, "positive");
    var exact = TimestampedValue.atMicros(0, "exact");
    var cases = List.of(List.of(positive, exact, negative), List.of(exact, positive, negative),
        List.of(positive, negative, exact), List.of(exact, negative, positive),
        List.of(negative, positive, exact), List.of(negative, exact, positive));
    for (var input : cases) {
      // Negative zero precedes both positive zeros. The two positive zeros retain file order.
      var expected = new ArrayList<TimestampedValue>();
      expected.add(negative);
      input.stream().filter(value -> value != negative).forEach(expected::add);
      assertEquals(expected, TimestampedValue.inTimeOrder(input),
          "Exact zero must not equate derived -0.0 with +0.0 inconsistently: " + input);
    }
  }
}
