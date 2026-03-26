package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Tests for DataQuality computation, gap detection, NaN handling, and score formula.
 */
class DataQualityTest {

  @Test
  @DisplayName("returns zero quality for empty values")
  void returnsZeroForEmpty() {
    var quality = DataQuality.fromValues(List.of());
    assertEquals(0, quality.sampleCount());
    assertEquals(0.0, quality.qualityScore());
  }

  @Test
  @DisplayName("returns zero quality for null values")
  void returnsZeroForNull() {
    var quality = DataQuality.fromValues(null);
    assertEquals(0, quality.sampleCount());
  }

  @Test
  @DisplayName("single sample gets low quality score")
  void singleSampleLowScore() {
    var quality = DataQuality.fromValues(List.of(
        new TimestampedValue(0.0, 42.0)));
    assertEquals(1, quality.sampleCount());
    assertTrue(quality.qualityScore() < 0.5, "Single sample should have low quality");
  }

  @Nested
  @DisplayName("Gap Detection")
  class GapDetection {

    @Test
    @DisplayName("detects no gaps in uniform data")
    void noGapsInUniformData() {
      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 100; i++) {
        values.add(new TimestampedValue(i * 0.02, (double) i));
      }
      var quality = DataQuality.fromValues(values);
      assertEquals(0, quality.gapCount());
    }

    @Test
    @DisplayName("detects gaps in data with interruptions")
    void detectsGaps() {
      var values = new ArrayList<TimestampedValue>();
      // 50 samples at 50Hz, then a 1-second gap, then 50 more
      for (int i = 0; i < 50; i++) {
        values.add(new TimestampedValue(i * 0.02, (double) i));
      }
      for (int i = 0; i < 50; i++) {
        values.add(new TimestampedValue(2.0 + i * 0.02, (double) (50 + i)));
      }

      var quality = DataQuality.fromValues(values);
      assertTrue(quality.gapCount() > 0, "Should detect the 1-second gap");
      assertTrue(quality.maxGapMs() > 900, "Max gap should be ~1000ms");
    }

    @Test
    @DisplayName("tolerates normal CAN bus jitter without flagging gaps (§5.1 fix)")
    void toleratesNormalJitter() {
      // Simulate 50Hz data with realistic jitter (±5ms = ±25% of 20ms period)
      // With 5× threshold, gaps < 100ms should not be flagged
      var values = new ArrayList<TimestampedValue>();
      var rng = new java.util.Random(42);
      for (int i = 0; i < 200; i++) {
        double jitter = (rng.nextDouble() - 0.5) * 0.010; // ±5ms jitter
        values.add(new TimestampedValue(i * 0.02 + jitter, (double) i));
      }

      var quality = DataQuality.fromValues(values);
      assertEquals(0, quality.gapCount(),
          "Normal 50Hz jitter (±5ms) should not trigger gap detection with 5× threshold");
    }
  }

  @Nested
  @DisplayName("NaN Handling")
  class NaNHandling {

    @Test
    @DisplayName("counts NaN values")
    void countsNaN() {
      var values = List.of(
          new TimestampedValue(0.0, 1.0),
          new TimestampedValue(0.02, Double.NaN),
          new TimestampedValue(0.04, 3.0),
          new TimestampedValue(0.06, Double.POSITIVE_INFINITY),
          new TimestampedValue(0.08, 5.0));

      var quality = DataQuality.fromValues(values);
      assertEquals(2, quality.nanFiltered(), "Should count NaN and Infinity");
    }

    @Test
    @DisplayName("NaN ratio reduces quality score")
    void nanReducesScore() {
      var clean = new ArrayList<TimestampedValue>();
      var dirty = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 200; i++) {
        clean.add(new TimestampedValue(i * 0.02, (double) i));
        dirty.add(new TimestampedValue(i * 0.02, i % 3 == 0 ? Double.NaN : (double) i));
      }

      var cleanQuality = DataQuality.fromValues(clean);
      var dirtyQuality = DataQuality.fromValues(dirty);

      assertTrue(cleanQuality.qualityScore() > dirtyQuality.qualityScore(),
          "Data with NaN values should have lower quality score");
    }
  }

  @Nested
  @DisplayName("Quality Score")
  class QualityScoreTests {

    @Test
    @DisplayName("high quality for clean data with many samples")
    void highQualityForCleanData() {
      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 5000; i++) {
        values.add(new TimestampedValue(i * 0.02, Math.sin(i * 0.1)));
      }
      var quality = DataQuality.fromValues(values);

      assertTrue(quality.qualityScore() > 0.8,
          "Clean data with 5000 samples should have high quality, got: " + quality.qualityScore());
      assertEquals("high", quality.confidenceLevel());
    }

    @Test
    @DisplayName("low quality for very few samples")
    void lowQualityForFewSamples() {
      var values = List.of(
          new TimestampedValue(0.0, 1.0),
          new TimestampedValue(0.02, 2.0),
          new TimestampedValue(0.04, 3.0));

      var quality = DataQuality.fromValues(values);
      assertTrue(quality.qualityScore() < 0.8,
          "Only 3 samples should have reduced quality score");
    }

    @Test
    @DisplayName("score is bounded between 0 and 1")
    void scoreBounded() {
      // Worst case: 2 samples, both NaN, with a gap
      var values = List.of(
          new TimestampedValue(0.0, Double.NaN),
          new TimestampedValue(10.0, Double.NaN));

      var quality = DataQuality.fromValues(values);
      assertTrue(quality.qualityScore() >= 0.0);
      assertTrue(quality.qualityScore() <= 1.0);
    }
  }

  @Nested
  @DisplayName("Gap Ratio Confidence Capping")
  class GapRatioConfidence {

    @Test
    @DisplayName("high gap duration ratio caps confidence at medium")
    void testHighGapDurationRatioCapsConfidenceAtMedium() {
      // Construct DataQuality where qualityScore > 0.8 but gap duration > 10% of time span.
      // totalGapMs = 1500ms out of 10s time span = 15% gap duration.
      var quality = new DataQuality(100, 10.0, 3, 500.0, 1500.0, 0, 50.0, 0.85);
      assertEquals("medium", quality.confidenceLevel(),
          "Gap duration 15% (> 10%) should cap confidence at medium despite quality 0.85");
    }

    @Test
    @DisplayName("gap duration exactly at 10% allows high")
    void testGapDurationExactlyAtTenPercentAllowsHigh() {
      // totalGapMs = 1000ms out of 10s = exactly 10%. The check is > 0.10, not >=.
      var quality = new DataQuality(100, 10.0, 2, 500.0, 1000.0, 0, 50.0, 0.85);
      assertEquals("high", quality.confidenceLevel(),
          "Gap duration exactly 10% (not > 10%) should allow high confidence");
    }

    @Test
    @DisplayName("gap duration just above 10% caps at medium")
    void testGapDurationJustAboveTenPercentCapsMedium() {
      // totalGapMs = 1100ms out of 10s = 11% gap duration.
      var quality = new DataQuality(100, 10.0, 2, 600.0, 1100.0, 0, 50.0, 0.85);
      assertEquals("medium", quality.confidenceLevel(),
          "Gap duration 11% (> 10%) should cap confidence at medium");
    }

    @Test
    @DisplayName("zero samples does not divide by zero")
    void testZeroSamplesDoesNotDivideByZero() {
      var quality = new DataQuality(0, 0.0, 0, 0.0, 0.0, 0, 0.0, 0.0);
      assertDoesNotThrow(quality::confidenceLevel);
      assertEquals("insufficient", quality.confidenceLevel(),
          "Zero samples with zero quality should be insufficient");
    }
  }

  @Nested
  @DisplayName("JSON Serialization")
  class JsonTests {

    @Test
    @DisplayName("toJson includes all fields")
    void toJsonIncludesAllFields() {
      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 100; i++) {
        values.add(new TimestampedValue(i * 0.02, (double) i));
      }
      var quality = DataQuality.fromValues(values);
      var json = quality.toJson();

      assertTrue(json.has("sample_count"));
      assertTrue(json.has("time_span_seconds"));
      assertTrue(json.has("effective_sample_rate_hz"));
      assertTrue(json.has("quality_score"));
    }

    @Test
    @DisplayName("toJson omits gap and nan fields when zero")
    void toJsonOmitsZeroFields() {
      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 100; i++) {
        values.add(new TimestampedValue(i * 0.02, (double) i));
      }
      var quality = DataQuality.fromValues(values);
      var json = quality.toJson();

      // No gaps or NaN in uniform data
      assertFalse(json.has("max_gap_ms"), "Should omit max_gap_ms when no gaps");
      assertFalse(json.has("nan_filtered"), "Should omit nan_filtered when zero");
    }
  }
}
