/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code read_entry} with {@code max_points} on the match fixture, whose battery voltage is a
 * known function of time (see FixtureLogs.akitMatch), so each bucket's count, extremes, and mean
 * are recomputed here from the formula and the loop times, independently of the server.
 */
class ReadEntryResolutionFixtureTest extends FixtureToolTestBase {

  private static final double LOOP = 0.02;
  private static final double START = 6.0;

  /** The fixture's battery voltage at loop {@code i}: see FixtureLogs.akitMatch. */
  private static double battery(double t) {
    boolean enabled = (t >= 20.0 && t < 40.0) || (t >= 43.0 && t < 183.0);
    double load = enabled ? 1.5 * Math.abs(Math.sin(0.4 * t)) : 0.0;
    double v = enabled ? 12.2 - load : 12.6;
    if (t >= 150.0 && t < 150.06) v = 7.1;
    return v;
  }

  private static double loopTime(int i) {
    return Math.round((START + i * LOOP) * 1e6) / 1e6;
  }

  /** The loop times in [start, end]. */
  private static List<Double> loopTimes(double start, double end) {
    var out = new ArrayList<Double>();
    for (int i = 0; ; i++) {
      double t = loopTime(i);
      if (t > end) break;
      if (t >= start) out.add(t);
    }
    return out;
  }

  @Test
  @DisplayName("over a window, the buckets' counts, extremes, and means equal the formula's")
  void bucketsOverWindow() {
    var result = call("read_entry", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "start_time", 20, "end_time", 40, "max_points", 4);
    assertEquals("ok", result.get("status").getAsString(), result.toString());
    assertTrue(result.get("bucketed").getAsBoolean());
    assertEquals(5.0, result.get("bucket_sec").getAsDouble(), 1e-12);
    var times = loopTimes(20, 40);
    assertEquals(times.size(), result.get("total_in_range").getAsInt());
    var samples = objects(result.getAsJsonArray("samples"));
    assertEquals(4, samples.size());
    assertEquals(4, result.get("bucket_count").getAsInt());
    for (int b = 0; b < 4; b++) {
      double bucketStart = 20 + 5 * b;
      double bucketEnd = b == 3 ? 40.0 : bucketStart + 5;
      boolean last = b == 3;
      var inBucket = times.stream()
          .filter(t -> t >= bucketStart && (last ? t <= bucketEnd : t < bucketEnd))
          .toList();
      double min = inBucket.stream().mapToDouble(ReadEntryResolutionFixtureTest::battery).min().orElseThrow();
      double max = inBucket.stream().mapToDouble(ReadEntryResolutionFixtureTest::battery).max().orElseThrow();
      double mean = inBucket.stream().mapToDouble(ReadEntryResolutionFixtureTest::battery).average().orElseThrow();
      var s = samples.get(b);
      assertEquals(bucketStart, s.get("timestamp_sec").getAsDouble(), 1e-9, "bucket " + b);
      assertEquals(inBucket.size(), s.get("count").getAsInt(), "bucket " + b);
      assertEquals(min, s.get("min").getAsDouble(), 1e-9, "bucket " + b);
      assertEquals(max, s.get("max").getAsDouble(), 1e-9, "bucket " + b);
      assertEquals(mean, s.get("mean").getAsDouble(), 1e-9, "bucket " + b);
      assertEquals(battery(inBucket.get(0)), s.get("first").getAsDouble(), 1e-9);
      assertEquals(battery(inBucket.get(inBucket.size() - 1)), s.get("last").getAsDouble(), 1e-9);
    }
    assertEquals(4, result.getAsJsonObject("limits").getAsJsonObject("samples").get("total").getAsInt());
  }

  @Test
  @DisplayName("a dip three samples wide survives bucketing as its bucket's minimum")
  void dipKept() {
    var result = call("read_entry", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "max_points", 10);
    assertTrue(result.get("bucketed").getAsBoolean());
    var samples = objects(result.getAsJsonArray("samples"));
    double bucketSec = result.get("bucket_sec").getAsDouble();
    var holding = samples.stream()
        .filter(s -> s.get("timestamp_sec").getAsDouble() <= 150.0
            && 150.0 < s.get("timestamp_sec").getAsDouble() + bucketSec)
        .findFirst().orElseThrow();
    assertEquals(7.1, holding.get("min").getAsDouble(), 1e-9);
    assertTrue(holding.get("mean").getAsDouble() > 10, "the mean alone would hide the dip");
  }

  @Test
  @DisplayName("no more samples than max_points: the read is exact and says so")
  void exactWhenFew() {
    var result = call("read_entry", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "start_time", 20, "end_time", 20.1, "max_points", 100);
    assertEquals("ok", result.get("status").getAsString());
    assertFalse(result.get("bucketed").getAsBoolean());
    var samples = objects(result.getAsJsonArray("samples"));
    assertEquals(loopTimes(20, 20.1).size(), samples.size());
    assertEquals(battery(20.0), samples.get(0).get("value").getAsDouble(), 1e-9);
    assertFalse(samples.get(0).has("count"));
  }

  @Test
  @DisplayName("a field path is bucketed as the exact read decodes it")
  void fieldPath() {
    var result = call("read_entry", "akit_match", "name", "/RealOutputs/Drive/Pose.translation.x",
        "start_time", 43, "end_time", 63, "max_points", 2);
    assertEquals("ok", result.get("status").getAsString(), result.toString());
    assertTrue(result.get("bucketed").getAsBoolean());
    var samples = objects(result.getAsJsonArray("samples"));
    assertEquals(2, samples.size());
    // x = 3 + 2 sin(0.05 t): over [43, 53) it rises from 3 + 2 sin(2.15) toward 3 + 2 sin(2.65)...
    // both within [1, 5], and the first sample is the formula at 43 s
    assertEquals(3.0 + 2.0 * Math.sin(0.05 * 43.0), samples.get(0).get("first").getAsDouble(), 1e-6);
    assertEquals("struct:Pose2d", result.get("type").getAsString());
  }

  @Test
  @DisplayName("a non-numeric entry is read exactly, with max_points in skipped")
  void nonNumericSkipped() {
    var result = call("read_entry", "akit_match", "name", "/RealOutputs/Console",
        "max_points", 2);
    assertEquals("partial", result.get("status").getAsString(), result.toString());
    assertFalse(result.get("bucketed").getAsBoolean());
    var skipped = objects(result.getAsJsonArray("skipped"));
    assertEquals("max_points", skipped.get(0).get("section").getAsString());
    assertTrue(skipped.get(0).get("reason").getAsString().contains("string"));
    assertTrue(result.getAsJsonArray("samples").size() > 0);
  }

  @Test
  @DisplayName("an unknown entry and a bad max_points are errors")
  void errors() {
    var missing = call("read_entry", "akit_match", "name", "/Nope", "max_points", 5);
    assertEquals("error", missing.get("status").getAsString());
    var zero = call("read_entry", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "max_points", 0);
    assertEquals("error", zero.get("status").getAsString());
    assertTrue(zero.get("error").getAsString().contains("max_points"));
  }
}
