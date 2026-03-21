package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

@DisplayName("ToolUtils")
class ToolUtilsTest {

  // ==================== getValueAtTimeLinear Binary Search ====================

  @Nested
  @DisplayName("getValueAtTimeLinear")
  class GetValueAtTimeLinearTests {

    @Test
    @DisplayName("returns null for empty list")
    void emptyList() {
      assertNull(ToolUtils.getValueAtTimeLinear(List.of(), 1.0));
    }

    @Test
    @DisplayName("returns null for null list")
    void nullList() {
      assertNull(ToolUtils.getValueAtTimeLinear(null, 1.0));
    }

    @Test
    @DisplayName("returns value for single-element list at exact timestamp")
    void singleElementExact() {
      var values = List.of(new TimestampedValue(5.0, 42.0));
      assertEquals(42.0, ToolUtils.getValueAtTimeLinear(values, 5.0), 0.001);
    }

    @Test
    @DisplayName("returns null for timestamp before first element")
    void beforeFirst() {
      var values = List.of(new TimestampedValue(5.0, 42.0), new TimestampedValue(6.0, 43.0));
      assertNull(ToolUtils.getValueAtTimeLinear(values, 4.0));
    }

    @Test
    @DisplayName("returns null for timestamp after last element")
    void afterLast() {
      var values = List.of(new TimestampedValue(5.0, 42.0), new TimestampedValue(6.0, 43.0));
      assertNull(ToolUtils.getValueAtTimeLinear(values, 7.0));
    }

    @Test
    @DisplayName("returns exact value at first timestamp")
    void exactFirst() {
      var values = List.of(new TimestampedValue(1.0, 10.0), new TimestampedValue(2.0, 20.0));
      assertEquals(10.0, ToolUtils.getValueAtTimeLinear(values, 1.0), 0.001);
    }

    @Test
    @DisplayName("returns exact value at last timestamp")
    void exactLast() {
      var values = List.of(new TimestampedValue(1.0, 10.0), new TimestampedValue(2.0, 20.0));
      assertEquals(20.0, ToolUtils.getValueAtTimeLinear(values, 2.0), 0.001);
    }

    @Test
    @DisplayName("interpolates midpoint correctly")
    void interpolatesMidpoint() {
      var values = List.of(new TimestampedValue(0.0, 0.0), new TimestampedValue(1.0, 10.0));
      assertEquals(5.0, ToolUtils.getValueAtTimeLinear(values, 0.5), 0.001);
    }

    @Test
    @DisplayName("interpolates at quarter point")
    void interpolatesQuarter() {
      var values = List.of(new TimestampedValue(0.0, 0.0), new TimestampedValue(1.0, 100.0));
      assertEquals(25.0, ToolUtils.getValueAtTimeLinear(values, 0.25), 0.001);
    }

    @Test
    @DisplayName("handles duplicate timestamps without division by zero")
    void duplicateTimestamps() {
      var values = List.of(
          new TimestampedValue(1.0, 10.0),
          new TimestampedValue(1.0, 20.0),
          new TimestampedValue(2.0, 30.0));
      // Should return a value without crashing
      var result = ToolUtils.getValueAtTimeLinear(values, 1.0);
      assertNotNull(result);
    }

    @Test
    @DisplayName("handles large list efficiently (binary search)")
    void largeList() {
      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 100000; i++) {
        values.add(new TimestampedValue(i * 0.001, (double) i));
      }
      // Midpoint should return ~50000
      Double result = ToolUtils.getValueAtTimeLinear(values, 50.0);
      assertNotNull(result);
      assertEquals(50000.0, result, 1.0);
    }
  }

  // ==================== appendQualityToResult ====================

  @Nested
  @DisplayName("appendQualityToResult")
  class AppendQualityTests {

    @Test
    @DisplayName("adds data_quality and directives to result")
    void addsQualityFields() {
      var result = new JsonObject();
      result.addProperty("success", true);

      var values = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 500; i++) {
        values.add(new TimestampedValue(i * 0.02, Math.sin(i * 0.1)));
      }
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality);

      ToolUtils.appendQualityToResult(result, quality, directives);

      assertTrue(result.has("data_quality"));
      assertTrue(result.has("server_analysis_directives"));
    }

    @Test
    @DisplayName("low quality score adds warning")
    void lowQualityAddsWarning() {
      var result = new JsonObject();
      result.addProperty("success", true);

      // Create very low quality data
      var values = List.of(
          new TimestampedValue(0.0, Double.NaN),
          new TimestampedValue(0.02, Double.NaN));
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality);

      ToolUtils.appendQualityToResult(result, quality, directives);

      if (quality.qualityScore() < 0.5) {
        assertTrue(result.has("warnings"));
        var warnings = result.getAsJsonArray("warnings");
        assertTrue(warnings.size() > 0);
      }
    }

    @Test
    @DisplayName("merges with existing warnings array")
    void mergesWithExistingWarnings() {
      var result = new JsonObject();
      result.addProperty("success", true);
      var existingWarnings = new JsonArray();
      existingWarnings.add("Existing warning");
      result.add("warnings", existingWarnings);

      // Use very poor quality to trigger additional warning
      var values = List.of(new TimestampedValue(0.0, 1.0));
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality);

      ToolUtils.appendQualityToResult(result, quality, directives);

      var warnings = result.getAsJsonArray("warnings");
      // Should still contain the original warning
      boolean hasOriginal = false;
      for (int i = 0; i < warnings.size(); i++) {
        if ("Existing warning".equals(warnings.get(i).getAsString())) hasOriginal = true;
      }
      assertTrue(hasOriginal, "Should preserve existing warnings");
    }
  }
}
