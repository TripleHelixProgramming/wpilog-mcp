/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.IntToDoubleFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/** Affine copies of an integer sequence have exactly +/-1 correlation at the
 * known time shift. Adding a constant must not erase that variance or change the best lag. */
class LagTranslationTest extends ToolTestBase {
  @TempDir Path temp;
  @Override protected void registerTools(ToolRegistry registry) { StatisticsTools.registerAll(registry); }
  static Stream<Arguments> cases() {
    return Stream.of(0.0, 1e6, 1e9, 1e10).flatMap(offset -> Stream.of(-1, 1).flatMap(sign -> Stream.of(-2, 0, 2).map(lag -> Arguments.of(offset, sign, lag))));
  }
  @ParameterizedTest @MethodSource("cases")
  void lagAndCorrelationAreTranslationInvariant(double offset, int sign, int lag) throws Exception {
    double[] time = new double[100], shifted = new double[100], x = new double[100], y = new double[100];
    for (int i = 0; i < 100; i++) {
      int v = (7 * i * i + 3 * i) % 23;
      time[i] = i; shifted[i] = i + lag; x[i] = offset + v; y[i] = -offset + sign * 2 * v;
    }
    var log = new MockLogBuilder().setPath("/test/lag-translation.wpilog").addNumericEntry("/x", time, x).addNumericEntry("/y", shifted, y).build();
    putLogInCache(log);
    var args = new JsonObject(); args.addProperty("path", log.path()); args.addProperty("name1", "/x"); args.addProperty("name2", "/y");
    args.addProperty("max_lag_sec", 3); args.addProperty("lag_step_sec", 1);
    var result = findTool("time_correlate").execute(args).getAsJsonObject();
    assertEquals("ok", result.get("status").getAsString(), result.toString());
    var search = result.getAsJsonObject("lag_search"); assertTrue(search.has("best_lag_sec"), search.toString());
    assertEquals(lag, search.get("best_lag_sec").getAsDouble());
    assertEquals(sign, search.get("correlation_at_best_lag").getAsDouble(), 1e-12);
    assertEquals(100, search.get("samples_at_best_lag").getAsInt());
    if (lag == 0) {
      assertEquals(sign, result.get("correlation").getAsDouble(), 1e-12);
      assertEquals(sign, search.get("correlation_at_zero_lag").getAsDouble(), 1e-12);
    }
  }

  private JsonObject recordedCorrelation(int n, IntToDoubleFunction x, IntToDoubleFunction y) throws Exception {
    var path = temp.resolve(UUID.randomUUID() + ".wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int first = writer.start("/x", "double", "", 0);
      int second = writer.start("/y", "double", "", 0);
      for (int i = 0; i < n; i++) {
        writer.append(first, i * 20_000L, WpilogWriter.encodeDouble(x.applyAsDouble(i)));
        writer.append(second, i * 20_000L, WpilogWriter.encodeDouble(y.applyAsDouble(i)));
      }
    }
    LogManager.getInstance().addAllowedDirectory(temp);
    var args = new JsonObject(); args.addProperty("path", path.toString());
    args.addProperty("name1", "/x"); args.addProperty("name2", "/y"); args.addProperty("max_lag_sec", 0.1);
    try {
      var result = findTool("time_correlate").execute(args).getAsJsonObject();
      assertEquals("ok", result.get("status").getAsString(), result.toString());
      assertEquals(n, result.get("sample_count").getAsInt());
      return result;
    } finally { LogManager.getInstance().release(path); }
  }

  @ParameterizedTest
  @CsvSource({"1,1", "1e-8,1", "1e-160,1", "1e160,1", "1e80,1e80",
      "1,1e-8", "1,1e-160", "1,1e160"})
  void correlationAndSignificanceAreScaleInvariant(double scaleX, double scaleY) throws Exception {
    IntToDoubleFunction sine = i -> Math.sin(0.1 * i);
    var unit = recordedCorrelation(500, sine, sine);
    var scaled = recordedCorrelation(500, i -> scaleX * sine.applyAsDouble(i), i -> scaleY * sine.applyAsDouble(i));
    // The finite sine sum has this closed form; compute neighbor products independently.
    double mean = Math.sin(25) * Math.sin(24.95) / (500 * Math.sin(0.05));
    double squares = 0, neighbors = 0;
    for (int i = 0; i < 500; i++) {
      double value = Math.sin(0.1 * i) - mean;
      squares += value * value;
      if (i < 499) neighbors += value * (Math.sin(0.1 * (i + 1)) - mean);
    }
    double lag1 = neighbors / squares;
    double effective = 500 * (1 - lag1 * lag1) / (1 + lag1 * lag1);
    for (var result : List.of(unit, scaled)) {
      closeTo(1, result.get("correlation"), "correlation");
      closeTo(0, result.get("p_value"), "p_value");
      closeTo(effective, result.get("effective_sample_size"), "effective_sample_size");
      closeTo(lag1, result.getAsJsonObject("lag1_autocorrelation").get("entry1"), "entry1 lag-1");
      closeTo(lag1, result.getAsJsonObject("lag1_autocorrelation").get("entry2"), "entry2 lag-1");
      var lag = result.getAsJsonObject("lag_search");
      closeTo(0, lag.get("best_lag_sec"), "best lag");
      closeTo(1, lag.get("correlation_at_best_lag"), "best correlation");
      closeTo(1, lag.get("correlation_at_zero_lag"), "zero-lag correlation");
      assertEquals(500, lag.get("samples_at_best_lag").getAsInt());
      assertFalse(result.toString().contains("could not be computed"), result.toString());
    }
    for (String field : List.of("correlation", "p_value", "effective_sample_size"))
      closeTo(unit.get(field).getAsDouble(), scaled.get(field), field);
    var expected = unit.getAsJsonObject("lag_search");
    assertEquals(expected.keySet(), scaled.getAsJsonObject("lag_search").keySet());
    for (var field : expected.entrySet()) {
      var actual = scaled.getAsJsonObject("lag_search").get(field.getKey());
      if (field.getValue().getAsJsonPrimitive().isNumber()) closeTo(field.getValue().getAsDouble(), actual, field.getKey());
      else assertEquals(field.getValue(), actual);
    }
  }

  private static void closeTo(double expected, com.google.gson.JsonElement actual, String label) {
    assertNotNull(actual, label); assertFalse(actual.isJsonNull(), label + " must be computed");
    assertTrue(Double.isFinite(actual.getAsDouble()), label);
    assertEquals(expected, actual.getAsDouble(), Math.abs(expected) > 1e-6 ? Math.abs(expected) * 1e-9 : 1e-9, label);
  }

  @ParameterizedTest
  @CsvSource({"5,true", "11978360223.146097,true", "5,false", "11978360223.146097,false"})
  void constantValuesStayUndefinedAtEveryMagnitude(double constant, boolean first) throws Exception {
    IntToDoubleFunction sine = i -> Math.sin(0.1 * i);
    var result = recordedCorrelation(50, first ? i -> constant : sine, first ? sine : i -> constant);
    assertTrue(result.get("correlation").isJsonNull(), result.toString());
    assertTrue(result.get("p_value").isJsonNull(), result.toString());
    assertTrue(result.getAsJsonArray("warnings").toString().contains("Correlation undefined"));
    assertTrue(result.getAsJsonArray("warnings").toString().contains(first ? "first entry" : "second entry"));
    var lag = result.getAsJsonObject("lag_search");
    assertFalse(lag.has("best_lag_sec"), lag.toString());
    assertTrue(lag.get("note").getAsString().startsWith("No lag had at least 3 overlapping samples"));
    var control = recordedCorrelation(50, first ? i -> 5 : sine, first ? sine : i -> 5);
    assertEquals(control.get("lag_search"), lag);
  }

  @ParameterizedTest @CsvSource({"true", "false"})
  void fourUlpRangeIsConstantInTheMainResultAndEveryLag(boolean first) throws Exception {
    double base = 11978360223.146097;
    IntToDoubleFunction flat = i -> base + (i % 2) * 4 * Math.ulp(base);
    IntToDoubleFunction sine = i -> Math.sin(0.1 * i);
    var result = recordedCorrelation(50, first ? flat : sine, first ? sine : flat);
    assertTrue(result.get("correlation").isJsonNull(), result.toString());
    assertTrue(result.get("p_value").isJsonNull(), result.toString());
    assertTrue(result.getAsJsonArray("warnings").toString().contains("range within 4 ulps"));
    var control = recordedCorrelation(50, first ? i -> 5 : sine, first ? sine : i -> 5);
    assertEquals(control.get("lag_search"), result.get("lag_search"));
  }

  @Test void nonFiniteInterpolatedCorrelationIsExplainedWithoutAPValue() throws Exception {
    var log = new MockLogBuilder().setPath("/test/interpolation-overflow.wpilog")
        .addNumericEntry("/x", new double[]{0.5,1.5,2.5}, new double[]{1,2,3})
        .addNumericEntry("/y", new double[]{0,1,2,3}, new double[]{1e308,-1e308,1e308,-1e308}).build();
    putLogInCache(log);
    var args = new JsonObject(); args.addProperty("path", log.path());
    args.addProperty("name1", "/x"); args.addProperty("name2", "/y"); args.addProperty("max_lag_sec", 0.1);
    var result = findTool("time_correlate").execute(args).getAsJsonObject();
    assertTrue(result.get("correlation").isJsonNull(), result.toString());
    assertTrue(result.get("p_value").isJsonNull(), result.toString());
    assertTrue(result.getAsJsonArray("warnings").toString().contains("Correlation undefined: non-finite"), result.toString());
    assertFalse(result.toString().contains("could not be computed"), result.toString());
  }
}
