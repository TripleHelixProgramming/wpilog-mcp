/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/** The voltage facts and brownout risk rule the power tools share. */
@DisplayName("PowerFacts voltage and risk")
class PowerFactsTest {

  static List<TimestampedValue> series(double... voltsAtWholeSeconds) {
    var out = new ArrayList<TimestampedValue>();
    for (int i = 0; i < voltsAtWholeSeconds.length; i++) {
      out.add(new TimestampedValue(i, voltsAtWholeSeconds[i]));
    }
    return out;
  }

  static final PowerFacts.Threshold THRESHOLD = new PowerFacts.Threshold(6.8, "default", null);

  static TimeScope all(double end) {
    var log = new MockLogBuilder().addNumericEntry("/x", new double[]{0, end}, new double[]{0, 0})
        .build();
    return TimeScope.resolve(log, null, "all", null, null);
  }

  @Test
  @DisplayName("a noisy dip is one crossing: it ends only 0.2 V above the threshold")
  void hysteresis() {
    var v = PowerFacts.voltage(series(12, 6.7, 6.9, 6.7, 7.1, 12, 6.5), all(6), 6.8).orElseThrow();
    assertEquals(7, v.samples());
    assertEquals(3, v.samplesBelow());
    assertEquals(2, v.crossings().size(), v.crossings().toString());
    assertEquals(1.0, v.crossings().get(0).start());
    assertEquals(4.0, v.crossings().get(0).end(), "recovered at 7.1 V >= 7.0 V");
    assertEquals(6.7, v.crossings().get(0).minVolts());
    assertEquals(6.0, v.crossings().get(1).end(), "open at the scope's last sample");
    assertEquals(6.5, v.min());
    assertEquals(6.0, v.minTime());
  }

  @Test
  @DisplayName("samples outside the scope and non-finite samples are not counted")
  void scopeAndNonFinite() {
    var values = series(12, Double.NaN, 5.0, 12);
    var log = new MockLogBuilder().addNumericEntry("/x", new double[]{0, 3}, new double[]{0, 0})
        .build();
    var scope = TimeScope.resolve(log, null, "all", 2.5, 3.0);
    var v = PowerFacts.voltage(values, scope, 6.8).orElseThrow();
    assertEquals(1, v.samples());
    assertEquals(0, v.crossings().size());
    assertTrue(PowerFacts.voltage(series(Double.NaN), all(0), 6.8).isEmpty());
  }

  @Test
  @DisplayName("risk: only the roboRIO flag confirms a brownout; a crossing without it is HIGH")
  void risk() {
    var dipped = PowerFacts.voltage(series(12, 6.5, 12), all(2), 6.8).orElseThrow();
    var noFlag = PowerFacts.risk(dipped, THRESHOLD, null, List.of());
    assertEquals("HIGH", noFlag.level());
    assertTrue(noFlag.basis().contains("whether outputs were disabled is unknown"));

    var flagFalse = PowerFacts.risk(dipped, THRESHOLD, "/SystemStats/BrownedOut", List.of());
    assertEquals("MODERATE", flagFalse.level(), "the flag stayed false");

    var flagged = PowerFacts.risk(dipped, THRESHOLD, "/SystemStats/BrownedOut",
        List.of(new PowerFacts.Brownout(1.0, 1.1, false)));
    assertEquals("HIGH", flagged.level());
    assertTrue(flagged.basis().contains("outputs were disabled"));

    var close = PowerFacts.voltage(series(12, 7.5, 12), all(2), 6.8).orElseThrow();
    assertEquals("MODERATE", PowerFacts.risk(close, THRESHOLD, null, List.of()).level());
    var healthy = PowerFacts.voltage(series(12, 11, 12), all(2), 6.8).orElseThrow();
    assertEquals("LOW", PowerFacts.risk(healthy, THRESHOLD, null, List.of()).level());
  }
}
