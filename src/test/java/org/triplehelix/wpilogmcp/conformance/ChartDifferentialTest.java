/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.tools.RenderChartTool;

/** Numeric answers are recomputed from the independent reader, using only reported inputs. */
class ChartDifferentialTest {
  @TempDir Path temp;
  @Test void summariesAgreeWithRawRecordsNamedByTheInputsInsideTheirWindow() throws Exception {
    var path = temp.resolve("functions.wpilog");
    try (var w = new FixtureWriter(path, "")) {
      for (int t = 0; t <= 6000; t++) { w.dbl("/Square", t, t * t); w.dbl("/Ramp", t, 3 * t - 2); }
    }
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    try {
      var args = JsonParser.parseString("{\"entries\":[\"/Square\",\"/Ramp\"],\"start_time\":4,\"end_time\":5003,\"width\":320,\"height\":180}").getAsJsonObject(); args.addProperty("path", path.toString());
      var result = new RenderChartTool().execute(args).getAsJsonObject(); assertEquals("ok", result.get("status").getAsString(), result::toString);
      var entries = result.getAsJsonObject("inputs").getAsJsonObject("entries");
      var names = new HashSet<String>(); for (var e : entries.entrySet()) if (e.getValue().isJsonPrimitive()) names.add(e.getValue().getAsString());
      assertEquals(Set.of("/Square", "/Ramp"), names);
      var raw = IndependentLog.read(path, names);
      for (var item : result.getAsJsonArray("summary")) {
        var summary = item.getAsJsonObject(); var source = raw.series.get(summary.get("name").getAsString());
        assertNotNull(source); var window = summary.getAsJsonObject("window").getAsJsonArray("windows").get(0).getAsJsonArray();
        double begin = window.get(0).getAsDouble(), end = window.get(1).getAsDouble();
        long n = 0; double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < source.n; i++) if (source.times[i] >= begin && source.times[i] <= end && Double.isFinite(source.values[i])) {
          n++; sum += source.values[i]; min = Math.min(min, source.values[i]); max = Math.max(max, source.values[i]);
        }
        assertEquals(n, summary.get("count").getAsLong()); assertEquals(min, summary.get("min").getAsDouble()); assertEquals(max, summary.get("max").getAsDouble()); assertEquals(sum / n, summary.get("mean").getAsDouble(), 1e-12);
      }
    } finally { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
}
