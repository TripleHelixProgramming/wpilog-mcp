package org.triplehelix.wpilogmcp.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Tests for SignalPair.
 */
class SignalPairTest {

  @Test
  void testSignalPairBasic() {
    List<TimestampedValue> wpiValues = List.of(
        new TimestampedValue(0.0, 0.5),
        new TimestampedValue(0.02, 0.6),
        new TimestampedValue(0.04, 0.7)
    );

    List<TimestampedValue> revValues = List.of(
        new TimestampedValue(0.0, 0.5),
        new TimestampedValue(0.02, 0.6)
    );

    SignalPair pair = new SignalPair(
        "/drive/output",
        "SparkMax_1/appliedOutput",
        wpiValues,
        revValues,
        "appliedOutput",
        0.9
    );

    assertEquals("/drive/output", pair.wpilogEntry());
    assertEquals("SparkMax_1/appliedOutput", pair.revlogSignal());
    assertEquals(3, pair.wpilogSampleCount());
    assertEquals(2, pair.revlogSampleCount());
    assertEquals(2, pair.minSampleCount());
    assertEquals("appliedOutput", pair.signalType());
    assertEquals(0.9, pair.matchScore(), 0.001);
  }

  @Test
  void testSignalPairHasSufficientSamples() {
    // Create lists with enough samples
    List<TimestampedValue> enough = new java.util.ArrayList<>();
    for (int i = 0; i < 15; i++) {
      enough.add(new TimestampedValue(i * 0.01, i * 0.1));
    }

    SignalPair sufficient = new SignalPair("e", "s", enough, enough, "test", 1.0);
    assertTrue(sufficient.hasSufficientSamples());

    // Not enough samples
    List<TimestampedValue> few = List.of(
        new TimestampedValue(0, 1),
        new TimestampedValue(1, 2)
    );
    SignalPair insufficient = new SignalPair("e", "s", few, few, "test", 1.0);
    assertFalse(insufficient.hasSufficientSamples());
  }

  @Test
  void testSignalPairTimeOverlap() {
    // Overlapping time ranges
    List<TimestampedValue> wpi = List.of(
        new TimestampedValue(0.0, 1),
        new TimestampedValue(10.0, 2)
    );
    List<TimestampedValue> rev = List.of(
        new TimestampedValue(5.0, 1),
        new TimestampedValue(15.0, 2)
    );

    SignalPair pair = new SignalPair("e", "s", wpi, rev, "test", 1.0);
    // Overlap is from 5.0 to 10.0 = 5 seconds
    assertEquals(5.0, pair.getTimeOverlap(), 0.001);
  }

  @Test
  void testSignalPairNoOverlap() {
    List<TimestampedValue> wpi = List.of(
        new TimestampedValue(0.0, 1),
        new TimestampedValue(5.0, 2)
    );
    List<TimestampedValue> rev = List.of(
        new TimestampedValue(10.0, 1),
        new TimestampedValue(15.0, 2)
    );

    SignalPair pair = new SignalPair("e", "s", wpi, rev, "test", 1.0);
    assertEquals(0.0, pair.getTimeOverlap(), 0.001);
  }

  @Test
  void testSignalPairEmptyValues() {
    SignalPair pair = new SignalPair("e", "s", List.of(), List.of(), "test", 1.0);

    assertEquals(0, pair.wpilogSampleCount());
    assertEquals(0, pair.revlogSampleCount());
    assertEquals(0, pair.minSampleCount());
    assertEquals(0.0, pair.getTimeOverlap());
    assertFalse(pair.hasSufficientSamples());
  }

  @Test
  void testSignalPairStaticOf() {
    SignalPair pair = SignalPair.of("/entry", "signal", "velocity");

    assertEquals("/entry", pair.wpilogEntry());
    assertEquals("signal", pair.revlogSignal());
    assertEquals("velocity", pair.signalType());
    assertEquals(1.0, pair.matchScore());
    assertTrue(pair.wpilogValues().isEmpty());
    assertTrue(pair.revlogValues().isEmpty());
  }
}
