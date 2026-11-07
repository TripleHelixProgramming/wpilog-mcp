/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * The timeline's voltage-threshold events follow the one hysteresis rule the power tools share: a
 * crossing starts at the first sample below the threshold and ends at the first at or above it
 * plus 0.2 V. The expected events are worked out by hand from the samples below.
 */
class BrownoutTimelineTest {
  @TempDir Path temp;
  Path log; Set<Path> allowed;
  final LogManager manager = LogManager.getInstance();
  final ToolRegistry registry = new ToolRegistry();

  /** Threshold 7 V, so a crossing ends at 7.2 V. */
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); log = temp.resolve("2026-brownout-timeline.wpilog");
    allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    double[][] samples = {{0, 12}, {1, 6.9}, {2, 7.1}, {3, 6.5}, {4, 7.2}, {5, Double.NaN}, {6, 7.0}, {7, 6.99}, {8, 12}, {9, 5}};
    try (var w = new FixtureWriter(log, "")) { for (var s : samples) w.dbl("/Battery/Volts", s[0], s[1]); }
    FrcDomainTools.registerAll(registry);
  }
  @AfterEach void cleanup() throws Exception { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }

  List<String> crossings(Double start, Double end) throws Exception {
    var args = new JsonObject(); args.addProperty("path", log.toString());
    args.addProperty("voltage_entry", "/Battery/Volts"); args.addProperty("brownout_threshold", 7.0);
    if (start != null) args.addProperty("start_time", start);
    if (end != null) args.addProperty("end_time", end);
    var result = registry.getTool("get_ds_timeline").execute(args).getAsJsonObject();
    return result.getAsJsonArray("events").asList().stream().map(e -> e.getAsJsonObject())
        .filter(e -> e.has("basis") && e.get("basis").getAsString().equals("voltage_threshold"))
        .map(e -> e.get("type").getAsString() + "@" + e.get("timestamp").getAsDouble() + " " + e.get("voltage").getAsDouble()
            + " " + e.get("source").getAsString() + " " + e.get("category").getAsString()).toList();
  }

  @Test void noiseAboveTheThresholdButBelowItsHysteresisDoesNotEndACrossing() throws Exception {
    // 7.1 V at 2 s is above 7 V but below 7.2 V; 7.2 V at 4 s ends it; NaN is skipped; 7.0 V is not
    // below 7 V; the last crossing is still open when the log ends, so it has no end.
    assertEquals(List.of("BROWNOUT_START@1.0 6.9 /Battery/Volts power", "BROWNOUT_END@4.0 7.2 /Battery/Volts power",
        "BROWNOUT_START@7.0 6.99 /Battery/Volts power", "BROWNOUT_END@8.0 12.0 /Battery/Volts power",
        "BROWNOUT_START@9.0 5.0 /Battery/Volts power"), crossings(null, null));
  }

  @Test void aRangeIncludesBothEndsAndStartsOutsideAnyCrossing() throws Exception {
    // From 2 s the crossing that began at 1 s is not seen; the sample at exactly 8 s ends one.
    assertEquals(List.of("BROWNOUT_START@3.0 6.5 /Battery/Volts power", "BROWNOUT_END@4.0 7.2 /Battery/Volts power",
        "BROWNOUT_START@7.0 6.99 /Battery/Volts power", "BROWNOUT_END@8.0 12.0 /Battery/Volts power"), crossings(2.0, 8.0));
    // Ending at 7.5 s, the crossing that began at 7 s is still open: a start and no end.
    assertEquals(List.of("BROWNOUT_START@3.0 6.5 /Battery/Volts power", "BROWNOUT_END@4.0 7.2 /Battery/Volts power",
        "BROWNOUT_START@7.0 6.99 /Battery/Volts power"), crossings(2.0, 7.5));
    assertEquals(List.of("BROWNOUT_START@7.0 6.99 /Battery/Volts power", "BROWNOUT_END@8.0 12.0 /Battery/Volts power",
        "BROWNOUT_START@9.0 5.0 /Battery/Volts power"), crossings(6.0, null));
  }
}
