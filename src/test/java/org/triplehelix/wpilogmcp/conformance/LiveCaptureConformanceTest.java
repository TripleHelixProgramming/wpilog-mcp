/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonElement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CaptureIndex;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LiveLog;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/** All existing log tools see the writer's index through their ordinary manager/acquire path. */
class LiveCaptureConformanceTest {
  @TempDir Path directory;
  record Call(ToolRegistry.Tool tool, ToolArguments.Variant variant, JsonElement live) {}

  @TestFactory Stream<DynamicTest> everyFixtureAnswersToolsExactlyAsItsFinishedFile() throws Exception {
    var fixtures = FixtureLogs.generateAll(FixtureLogs.defaultDirectory());
    var registry = new ToolRegistry(); WpilogTools.registerAll(registry);
    var tools = registry.getToolNames().stream().sorted().map(registry::getTool).filter(ToolArguments::takesPath).toList();
    return fixtures.stream().map(f -> DynamicTest.dynamicTest(f.id(), () -> {
      var manager = LogManager.getInstance(); var saved = manager.getAllowedDirectories();
      var savedExport = ExportTools.getExportDirectory();
      var capture = directory.resolve(f.id() + ".wpilog"); var export = Files.createDirectories(directory.resolve("exports"));
      manager.addAllowedDirectory(directory); manager.addAllowedDirectory(FixtureLogs.defaultDirectory());
      ExportTools.setExportDirectory(export.toString());
      var fixture = new FixtureLogs.Fixture(f.id(), capture, f.description(), f.exercises());
      var index = new CaptureIndex(new CaptureWriter.Observer() {
        @Override public Path create(String address, Instant start) { return capture; }
      }, manager, 2_000_000);
      var calls = new ArrayList<Call>();
      try {
        CaptureFidelityTest.capture(f.path(), capture, index, () -> {
          try (var use = manager.acquire(capture.toString())) {
            assertInstanceOf(LiveLog.View.class, use.log());
            for (var entry : use.log().entries().values()) {
              assertTrue(entry.metadata().contains("live_metadata_check"), entry.name());
            }
            for (var tool : tools) for (var variant : ToolArguments.variants(tool, fixture, use.log(), fixtures, export)) {
              var result = tool.execute(variant.args());
              Integer limit = variant.args().has("limit") ? variant.args().get("limit").getAsInt() : null;
              assertEquals(List.of(), ConformanceChecks.check(result, limit, true, tool.name(), variant.args()), tool.name() + "/" + variant.label());
              if (result.isJsonObject() && result.getAsJsonObject().has("inputs")) {
                var range = result.getAsJsonObject().getAsJsonObject("inputs").getAsJsonObject("session_time_range");
                if (tool.name().equals("compare_matches")) range = result.getAsJsonObject().getAsJsonObject("inputs")
                    .getAsJsonObject("session_time_ranges").getAsJsonObject(capture.toString());
                assertNotNull(range, tool.name()); assertEquals(use.log().minTimestamp(), range.get("start_sec").getAsDouble());
                assertEquals(use.log().maxTimestamp(), range.get("end_sec").getAsDouble());
              }
              calls.add(new Call(tool, variant, normalized(result)));
            }
          }
        });
        assertFalse(calls.isEmpty());
        manager.unloadLog(capture.toString());
        try (var use = manager.acquire(capture.toString())) { assertInstanceOf(LazyParsedLog.class, use.log()); }
        for (var call : calls) assertEquals(call.live(), normalized(call.tool().execute(call.variant().args())), call.tool().name() + "/" + call.variant().label());
        var report = Files.createDirectories(Path.of("build/reports/live-capture"));
        Files.write(report.resolve(f.id() + ".txt"), calls.stream().map(call -> call.tool().name() + " / " + call.variant().label()).toList());
      } finally {
        manager.release(capture); manager.clearAllowedDirectories(); saved.forEach(manager::addAllowedDirectory);
        ExportTools.setExportDirectory(savedExport.toString());
      }
    }));
  }

  private static JsonElement normalized(JsonElement result) {
    var copy = ConformanceChecks.normalize(result);
    if (copy.isJsonObject() && copy.getAsJsonObject().has("inputs")) {
      copy.getAsJsonObject().getAsJsonObject("inputs").remove("session_time_range");
      copy.getAsJsonObject().getAsJsonObject("inputs").remove("session_time_ranges");
    }
    return copy;
  }
}
