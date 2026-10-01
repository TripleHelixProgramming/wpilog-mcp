/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.ParsedLog;
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

      // Create very low quality data: all NaN with a large gap to push score below 0.5
      var values = List.of(
          new TimestampedValue(0.0, Double.NaN),
          new TimestampedValue(0.02, Double.NaN),
          new TimestampedValue(10.0, Double.NaN)); // large gap drives score down further
      var quality = DataQuality.fromValues(values);
      var directives = AnalysisDirectives.fromQuality(quality);

      ToolUtils.appendQualityToResult(result, quality, directives);

      assertTrue(quality.qualityScore() < 0.5,
          "Test precondition: quality should be low (score=" + quality.qualityScore() + ")");
      assertTrue(result.has("warnings"));
      var warnings = result.getAsJsonArray("warnings");
      assertTrue(warnings.size() > 0);
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

  // ==================== percentile ====================

  @Nested
  @DisplayName("percentile")
  class PercentileTests {

    @Test
    @DisplayName("returns 0.0 for empty array")
    void emptyArray() {
      assertEquals(0.0, ToolUtils.percentile(new double[]{}, 0.5));
    }

    @Test
    @DisplayName("returns the value for single-element array")
    void singleElement() {
      assertEquals(42.0, ToolUtils.percentile(new double[]{42.0}, 0.0));
      assertEquals(42.0, ToolUtils.percentile(new double[]{42.0}, 0.5));
      assertEquals(42.0, ToolUtils.percentile(new double[]{42.0}, 1.0));
    }

    @Test
    @DisplayName("returns min at 0th percentile")
    void zerothPercentile() {
      assertEquals(1.0, ToolUtils.percentile(new double[]{1, 2, 3, 4, 5}, 0.0));
    }

    @Test
    @DisplayName("returns max at 100th percentile")
    void hundredthPercentile() {
      assertEquals(5.0, ToolUtils.percentile(new double[]{1, 2, 3, 4, 5}, 1.0));
    }

    @Test
    @DisplayName("returns median at 50th percentile for odd-length array")
    void medianOddLength() {
      assertEquals(3.0, ToolUtils.percentile(new double[]{1, 2, 3, 4, 5}, 0.5));
    }

    @Test
    @DisplayName("interpolates at 50th percentile for even-length array")
    void medianEvenLength() {
      assertEquals(2.5, ToolUtils.percentile(new double[]{1, 2, 3, 4}, 0.5));
    }

    @Test
    @DisplayName("computes Q1 (25th percentile) correctly")
    void q1() {
      // NIST Type 7: index = 0.25 * 4 = 1.0 → sortedData[1] = 2.0
      assertEquals(2.0, ToolUtils.percentile(new double[]{1, 2, 3, 4, 5}, 0.25));
    }

    @Test
    @DisplayName("computes Q3 (75th percentile) correctly")
    void q3() {
      // NIST Type 7: index = 0.75 * 4 = 3.0 → sortedData[3] = 4.0
      assertEquals(4.0, ToolUtils.percentile(new double[]{1, 2, 3, 4, 5}, 0.75));
    }

    @Test
    @DisplayName("interpolates between values correctly")
    void interpolatesBetween() {
      // index = 0.1 * 4 = 0.4 → 1.0 * 0.6 + 2.0 * 0.4 = 1.4
      assertEquals(1.4, ToolUtils.percentile(new double[]{1, 2, 3, 4, 5}, 0.1), 0.001);
    }

    @Test
    @DisplayName("rejects negative percentile")
    void rejectsNegativePercentile() {
      assertThrows(IllegalArgumentException.class,
          () -> ToolUtils.percentile(new double[]{1, 2, 3}, -0.1));
    }

    @Test
    @DisplayName("rejects percentile > 1.0")
    void rejectsPercentileAboveOne() {
      assertThrows(IllegalArgumentException.class,
          () -> ToolUtils.percentile(new double[]{1, 2, 3}, 1.1));
    }
  }

  // ==================== getValueAtTimeZoh ====================

  @Nested
  @DisplayName("getValueAtTimeZoh")
  class GetValueAtTimeZohTests {

    @Test
    @DisplayName("returns null for empty list")
    void emptyList() {
      assertNull(ToolUtils.getValueAtTimeZoh(List.of(), 1.0));
    }

    @Test
    @DisplayName("returns null for null list")
    void nullList() {
      assertNull(ToolUtils.getValueAtTimeZoh(null, 1.0));
    }

    @Test
    @DisplayName("returns null for timestamp before first value")
    void beforeFirst() {
      var values = List.of(new TimestampedValue(5.0, true));
      assertNull(ToolUtils.getValueAtTimeZoh(values, 4.0));
    }

    @Test
    @DisplayName("returns value at exact timestamp")
    void exactTimestamp() {
      var values = List.of(
          new TimestampedValue(1.0, "hello"),
          new TimestampedValue(2.0, "world"));
      assertEquals("hello", ToolUtils.getValueAtTimeZoh(values, 1.0));
    }

    @Test
    @DisplayName("holds previous value between timestamps")
    void holdsBetween() {
      var values = List.of(
          new TimestampedValue(1.0, true),
          new TimestampedValue(3.0, false));
      assertEquals(true, ToolUtils.getValueAtTimeZoh(values, 2.0));
    }

    @Test
    @DisplayName("returns last value for timestamp after all values")
    void afterLast() {
      var values = List.of(
          new TimestampedValue(1.0, 10.0),
          new TimestampedValue(2.0, 20.0));
      assertEquals(20.0, ToolUtils.getValueAtTimeZoh(values, 99.0));
    }
  }

  // ==================== toDouble ====================

  @Nested
  @DisplayName("toDouble")
  class ToDoubleTests {

    @Test
    @DisplayName("returns null for null input")
    void nullInput() {
      assertNull(ToolUtils.toDouble(null));
    }

    @Test
    @DisplayName("converts Integer")
    void convertsInteger() {
      assertEquals(42.0, ToolUtils.toDouble(42));
    }

    @Test
    @DisplayName("converts Long")
    void convertsLong() {
      assertEquals(123.0, ToolUtils.toDouble(123L));
    }

    @Test
    @DisplayName("converts Float")
    void convertsFloat() {
      assertEquals(1.5, ToolUtils.toDouble(1.5f), 0.001);
    }

    @Test
    @DisplayName("converts Double")
    void convertsDouble() {
      assertEquals(3.14, ToolUtils.toDouble(3.14));
    }

    @Test
    @DisplayName("converts Boolean true to 1.0")
    void convertsBooleanTrue() {
      assertEquals(1.0, ToolUtils.toDouble(true));
    }

    @Test
    @DisplayName("converts Boolean false to 0.0")
    void convertsBooleanFalse() {
      assertEquals(0.0, ToolUtils.toDouble(false));
    }

    @Test
    @DisplayName("returns null for String")
    void returnsNullForString() {
      assertNull(ToolUtils.toDouble("not a number"));
    }

    @Test
    @DisplayName("handles NaN")
    void handlesNaN() {
      assertTrue(Double.isNaN(ToolUtils.toDouble(Double.NaN)));
    }

    @Test
    @DisplayName("handles Infinity")
    void handlesInfinity() {
      assertEquals(Double.POSITIVE_INFINITY, ToolUtils.toDouble(Double.POSITIVE_INFINITY));
    }
  }

  // ==================== isNumericType ====================

  @Nested
  @DisplayName("isNumericType")
  class IsNumericTypeTests {

    @Test
    @DisplayName("double is numeric")
    void doubleIsNumeric() {
      assertTrue(ToolUtils.isNumericType("double"));
    }

    @Test
    @DisplayName("float is numeric")
    void floatIsNumeric() {
      assertTrue(ToolUtils.isNumericType("float"));
    }

    @Test
    @DisplayName("int64 is numeric")
    void int64IsNumeric() {
      assertTrue(ToolUtils.isNumericType("int64"));
    }

    @Test
    @DisplayName("boolean is not numeric")
    void booleanIsNotNumeric() {
      assertFalse(ToolUtils.isNumericType("boolean"));
    }

    @Test
    @DisplayName("string is not numeric")
    void stringIsNotNumeric() {
      assertFalse(ToolUtils.isNumericType("string"));
    }

    @Test
    @DisplayName("array types are not numeric")
    void arrayNotNumeric() {
      assertFalse(ToolUtils.isNumericType("double[]"));
      assertFalse(ToolUtils.isNumericType("int64[]"));
    }
  }

  // ==================== Validation methods ====================

  @Nested
  @DisplayName("validateRange")
  class ValidateRangeTests {

    @Test
    @DisplayName("accepts value within range")
    void withinRange() {
      assertEquals(5, ToolUtils.validateRange(5, 1, 10, "test"));
    }

    @Test
    @DisplayName("accepts value at min boundary")
    void atMin() {
      assertEquals(1, ToolUtils.validateRange(1, 1, 10, "test"));
    }

    @Test
    @DisplayName("accepts value at max boundary")
    void atMax() {
      assertEquals(10, ToolUtils.validateRange(10, 1, 10, "test"));
    }

    @Test
    @DisplayName("throws for value below range")
    void belowRange() {
      assertThrows(IllegalArgumentException.class,
          () -> ToolUtils.validateRange(0, 1, 10, "test"));
    }

    @Test
    @DisplayName("throws for value above range")
    void aboveRange() {
      assertThrows(IllegalArgumentException.class,
          () -> ToolUtils.validateRange(11, 1, 10, "test"));
    }
  }

  @Nested
  @DisplayName("validatePositive")
  class ValidatePositiveTests {

    @Test
    @DisplayName("accepts positive value")
    void positive() {
      assertEquals(5, ToolUtils.validatePositive(5, "test"));
    }

    @Test
    @DisplayName("throws for zero")
    void zero() {
      assertThrows(IllegalArgumentException.class,
          () -> ToolUtils.validatePositive(0, "test"));
    }

    @Test
    @DisplayName("throws for negative")
    void negative() {
      assertThrows(IllegalArgumentException.class,
          () -> ToolUtils.validatePositive(-1, "test"));
    }
  }

  @Nested
  @DisplayName("validateNonNegative")
  class ValidateNonNegativeTests {

    @Test
    @DisplayName("accepts positive value")
    void positive() {
      assertEquals(5, ToolUtils.validateNonNegative(5, "test"));
    }

    @Test
    @DisplayName("accepts zero")
    void zero() {
      assertEquals(0, ToolUtils.validateNonNegative(0, "test"));
    }

    @Test
    @DisplayName("throws for negative")
    void negative() {
      assertThrows(IllegalArgumentException.class,
          () -> ToolUtils.validateNonNegative(-1, "test"));
    }
  }

  // ==================== Argument extraction ====================

  @Nested
  @DisplayName("getOptDouble")
  class GetOptDoubleTests {

    @Test
    @DisplayName("returns value when present")
    void returnsValue() {
      var args = new JsonObject();
      args.addProperty("key", 3.14);
      assertEquals(3.14, ToolUtils.getOptDouble(args, "key"));
    }

    @Test
    @DisplayName("returns null when missing")
    void returnsNullWhenMissing() {
      assertNull(ToolUtils.getOptDouble(args(), "missing"));
    }

    @Test
    @DisplayName("returns default when missing")
    void returnsDefault() {
      assertEquals(99.0, ToolUtils.getOptDouble(args(), "missing", 99.0));
    }
  }

  @Nested
  @DisplayName("getOptInt")
  class GetOptIntTests {

    @Test
    @DisplayName("returns value when present")
    void returnsValue() {
      var args = new JsonObject();
      args.addProperty("key", 42);
      assertEquals(42, ToolUtils.getOptInt(args, "key", 0));
    }

    @Test
    @DisplayName("returns default when missing")
    void returnsDefault() {
      assertEquals(10, ToolUtils.getOptInt(args(), "missing", 10));
    }
  }

  @Nested
  @DisplayName("getRequiredString")
  class GetRequiredStringTests {

    @Test
    @DisplayName("returns value when present")
    void returnsValue() {
      var args = new JsonObject();
      args.addProperty("name", "hello");
      assertEquals("hello", ToolUtils.getRequiredString(args, "name"));
    }

    @Test
    @DisplayName("throws for missing parameter")
    void throwsWhenMissing() {
      assertThrows(IllegalArgumentException.class,
          () -> ToolUtils.getRequiredString(args(), "missing"));
    }
  }

  @Nested
  @DisplayName("getRequiredInt")
  class GetRequiredIntTests {

    @Test
    @DisplayName("returns value when present")
    void returnsValue() {
      var args = new JsonObject();
      args.addProperty("year", 2026);
      assertEquals(2026, ToolUtils.getRequiredInt(args, "year"));
    }

    @Test
    @DisplayName("names the missing parameter instead of a NullPointerException")
    void throwsWhenMissing() {
      var e = assertThrows(IllegalArgumentException.class,
          () -> ToolUtils.getRequiredInt(args(), "year"));
      assertEquals("Missing required parameter: year", e.getMessage());
      var explicitNull = new JsonObject();
      explicitNull.add("year", com.google.gson.JsonNull.INSTANCE);
      assertThrows(IllegalArgumentException.class,
          () -> ToolUtils.getRequiredInt(explicitNull, "year"));
    }
  }

  @Nested
  @DisplayName("validateTimeRange")
  class ValidateTimeRangeTests {

    @Test
    @DisplayName("accepts open, ordered, and point ranges")
    void accepts() {
      assertDoesNotThrow(() -> ToolUtils.validateTimeRange(null, null));
      assertDoesNotThrow(() -> ToolUtils.validateTimeRange(5.0, null));
      assertDoesNotThrow(() -> ToolUtils.validateTimeRange(null, 5.0));
      assertDoesNotThrow(() -> ToolUtils.validateTimeRange(1.0, 5.0));
      assertDoesNotThrow(() -> ToolUtils.validateTimeRange(5.0, 5.0));
    }

    @Test
    @DisplayName("rejects start_time after end_time, naming both")
    void rejectsInverted() {
      var e = assertThrows(IllegalArgumentException.class,
          () -> ToolUtils.validateTimeRange(10.0, 5.0));
      assertTrue(e.getMessage().contains("start_time=10.0"), e.getMessage());
      assertTrue(e.getMessage().contains("end_time=5.0"), e.getMessage());
    }
  }

  private static JsonObject args() {
    return new JsonObject();
  }
}
