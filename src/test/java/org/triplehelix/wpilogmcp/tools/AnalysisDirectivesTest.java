package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Tests for AnalysisDirectives LLM guidance generation.
 */
@DisplayName("AnalysisDirectives")
class AnalysisDirectivesTest {

  @Nested
  @DisplayName("fromQuality auto-generated guidance")
  class FromQualityTests {

    @Test
    @DisplayName("low sample count triggers guidance")
    void lowSampleCount() {
      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 20; i++) {
        values.add(new TimestampedValue(i * 0.02, (double) i));
      }
      var quality = DataQuality.fromValues(values);
      assertTrue(quality.sampleCount() < 100, "Test data should have <100 samples");

      var directives = AnalysisDirectives.fromQuality(quality);
      var json = directives.toJson();
      var guidance = json.getAsJsonArray("interpretation_guidance");
      assertNotNull(guidance);
      boolean hasLowSampleWarning = false;
      for (int i = 0; i < guidance.size(); i++) {
        if (guidance.get(i).getAsString().contains("Low sample count")) {
          hasLowSampleWarning = true;
        }
      }
      assertTrue(hasLowSampleWarning, "Should warn about low sample count");
    }

    @Test
    @DisplayName("many gaps triggers guidance")
    void manyGaps() {
      // Create data with many large gaps
      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 100; i++) {
        // Every 10th sample has a big gap
        double t = i * 0.02 + (i % 10 == 0 ? 0.5 : 0);
        values.add(new TimestampedValue(t, (double) i));
      }
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality);
      var json = directives.toJson();

      if (quality.gapCount() > 5) {
        var guidance = json.getAsJsonArray("interpretation_guidance");
        boolean hasGapWarning = false;
        for (int i = 0; i < guidance.size(); i++) {
          if (guidance.get(i).getAsString().contains("gaps detected")) {
            hasGapWarning = true;
          }
        }
        assertTrue(hasGapWarning, "Should warn about data gaps");
      }
    }

    @Test
    @DisplayName("NaN values trigger guidance")
    void nanValues() {
      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 500; i++) {
        double val = (i % 5 == 0) ? Double.NaN : (double) i;
        values.add(new TimestampedValue(i * 0.02, val));
      }
      var quality = DataQuality.fromValues(values);
      assertTrue(quality.nanFiltered() > 0);

      var directives = AnalysisDirectives.fromQuality(quality);
      var json = directives.toJson();
      var guidance = json.getAsJsonArray("interpretation_guidance");
      boolean hasNanWarning = false;
      for (int i = 0; i < guidance.size(); i++) {
        if (guidance.get(i).getAsString().contains("non-finite values")) {
          hasNanWarning = true;
        }
      }
      assertTrue(hasNanWarning, "Should warn about filtered NaN values");
    }

    @Test
    @DisplayName("short time span triggers guidance")
    void shortTimeSpan() {
      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 200; i++) {
        values.add(new TimestampedValue(i * 0.01, (double) i)); // 2 seconds total
      }
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality);
      var json = directives.toJson();

      var guidance = json.getAsJsonArray("interpretation_guidance");
      boolean hasShortSpanWarning = false;
      for (int i = 0; i < guidance.size(); i++) {
        if (guidance.get(i).getAsString().contains("Short time span")) {
          hasShortSpanWarning = true;
        }
      }
      assertTrue(hasShortSpanWarning, "Should warn about short time span (<10s)");
    }

    @Test
    @DisplayName("high quality data produces no warnings")
    void highQualityNoWarnings() {
      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 5000; i++) {
        values.add(new TimestampedValue(i * 0.02, Math.sin(i * 0.1)));
      }
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality);
      var json = directives.toJson();

      assertEquals("high", json.get("confidence_level").getAsString());
      // High-quality data should have no auto-generated guidance
      assertFalse(json.has("interpretation_guidance"),
          "High quality data should not generate warnings");
    }

    @Test
    @DisplayName("sample context includes count and duration")
    void sampleContextFormat() {
      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 100; i++) {
        values.add(new TimestampedValue(i * 0.02, (double) i));
      }
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality);
      var json = directives.toJson();

      String context = json.get("sample_context").getAsString();
      assertTrue(context.contains("100"), "Should mention sample count");
      assertTrue(context.contains("seconds"), "Should mention time span");
    }
  }

  @Nested
  @DisplayName("Builder methods")
  class BuilderTests {

    @Test
    @DisplayName("addGuidance adds custom guidance")
    void addGuidance() {
      var values = List.of(
          new TimestampedValue(0.0, 1.0),
          new TimestampedValue(100.0, 2.0));
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addGuidance("Custom guidance message");

      var json = directives.toJson();
      var guidance = json.getAsJsonArray("interpretation_guidance");
      boolean found = false;
      for (int i = 0; i < guidance.size(); i++) {
        if (guidance.get(i).getAsString().equals("Custom guidance message")) found = true;
      }
      assertTrue(found);
    }

    @Test
    @DisplayName("addFollowup adds suggested followup")
    void addFollowup() {
      var values = List.of(
          new TimestampedValue(0.0, 1.0),
          new TimestampedValue(100.0, 2.0));
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addFollowup("Use detect_anomalies");

      var json = directives.toJson();
      var followup = json.getAsJsonArray("suggested_followup");
      assertNotNull(followup);
      assertEquals("Use detect_anomalies", followup.get(0).getAsString());
    }

    @Test
    @DisplayName("addSingleMatchCaveat adds standard warning")
    void singleMatchCaveat() {
      var values = List.of(
          new TimestampedValue(0.0, 1.0),
          new TimestampedValue(100.0, 2.0));
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality)
          .addSingleMatchCaveat();

      var json = directives.toJson();
      var guidance = json.getAsJsonArray("interpretation_guidance");
      boolean found = false;
      for (int i = 0; i < guidance.size(); i++) {
        if (guidance.get(i).getAsString().contains("single log")) found = true;
      }
      assertTrue(found, "Should include single-match caveat");
    }
  }
}
