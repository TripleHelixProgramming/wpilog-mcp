/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.*;
import java.util.*;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.LogData;
import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/** One numerical recipe for image charts and visualization source operations. Inputs are
 * finite original samples in stable timestamp order, never downsampled plot points. */
final class ChartData {
  private ChartData() {}
  record Prepared(List<List<TimestampedValue>> parts, List<TimestampedValue> values, DataQuality quality) {}
  static Prepared prepare(LogData log, NumericSignal signal, TimeScope scope) {
    var parts = StatisticsTools.finiteWindows(signal, scope, signal.isAngle());
    return new Prepared(parts, StatisticsTools.flatten(parts), DataQuality.fromSegments(log, signal.entry(), scope.split(signal.values())));
  }
  static JsonObject summary(String name, List<TimestampedValue> values, TimeScope scope, DataQuality quality) {
    var stats = values.stream().mapToDouble(tv -> toDouble(tv.value())).summaryStatistics();
    var json = new JsonObject(); json.addProperty("name", name); json.addProperty("count", stats.getCount());
    json.addProperty("min", values.isEmpty() ? null : stats.getMin()); json.addProperty("max", values.isEmpty() ? null : stats.getMax());
    json.addProperty("mean", values.isEmpty() ? null : stats.getAverage()); json.add("window", scope.toJson());
    json.addProperty("non_finite_count", quality.nanFiltered()); json.add("data_quality", quality.toJson()); return json;
  }
  /**
   * Equal-width bins, ceil(sqrt(n)) capped at 64, [low, high) with the last including the maximum,
   * and constant data in one unit-wide bin. A range wider than a double holds (from -1e308 to 1e308)
   * is divided before it is subtracted, so its edges stay finite. Edges that cannot be finite and
   * distinct, as a unit-wide bin around 1e308 cannot, are refused rather than returned as NaN.
   */
  static JsonArray histogram(List<TimestampedValue> values) {
    var out = new JsonArray(); if (values.isEmpty()) return out;
    var stats = values.stream().mapToDouble(tv -> toDouble(tv.value())).summaryStatistics();
    int n = stats.getMin() == stats.getMax() ? 1 : Math.min(64, (int) Math.ceil(Math.sqrt(values.size())));
    double low = n == 1 ? stats.getMin() - 0.5 : stats.getMin(), high = n == 1 ? stats.getMax() + 0.5 : stats.getMax();
    double step = (high - low) / n; var counts = new int[n];
    boolean wide = !Double.isFinite(step);
    if (wide) step = high / n - low / n;
    for (var tv : values) {
      double v = toDouble(tv.value());
      double position = wide ? (v / 2 - low / 2) / (step / 2) : (v - low) / step;
      counts[Math.min(n - 1, Math.max(0, (int) position))]++;
    }
    for (int i = 0; i < n; i++) {
      double from = edge(low, high, step, n, i, wide), to = edge(low, high, step, n, i + 1, wide);
      if (!Double.isFinite(from) || !Double.isFinite(to) || from >= to) {
        throw new IllegalArgumentException("Histogram bin edges are not representable as finite distinct numbers at this "
            + "magnitude; read the values with read_entry or get_statistics instead");
      }
      var bin = new JsonObject(); bin.addProperty("low", from); bin.addProperty("high", to); bin.addProperty("count", counts[i]); out.add(bin);
    }
    return out;
  }
  private static double edge(double low, double high, double step, int n, int i, boolean wide) {
    if (!wide) return low + i * step;
    return i == n ? high : low / n * (n - i) + high / n * i;
  }
  static JsonObject pairs(List<TimestampedValue> x, List<TimestampedValue> y, int offset, int limit) {
    var points = new JsonArray(); int a = 0, b = 0, count = 0;
    while (a < x.size() && b < y.size()) {
      var first = x.get(a); var second = y.get(b);
      if (first.timestamp() < second.timestamp()) a++;
      else if (first.timestamp() > second.timestamp()) b++;
      else { if (count >= offset && count - offset < limit) { var point = new JsonArray(); point.add(toDouble(first.value())); point.add(toDouble(second.value())); point.add(first.timestamp()); points.add(point); } count++; a++; b++; }
    }
    var out = new JsonObject(); ResultContract.addLimitedList(out, "points", points, Math.max(0, count - offset), limit);
    out.addProperty("matched", count); out.addProperty("unpaired_x", x.size() - count); out.addProperty("unpaired_y", y.size() - count); return out;
  }
}
