/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs.Fixture;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.subsystems.LogParser;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.CoreTools;

/** Optional presentation arguments need real sweep calls, not only schema declarations. */
class PresentationAngleCoverageTest {
  @TempDir Path directory;

  @Test void conformanceExercisesBothUnitsWithAndWithoutResolution() throws Exception {
    var path = directory.resolve("numeric.wpilog");
    try (var writer = new WpilogWriter(path, "angle argument coverage")) {
      int entry = writer.start("/Numeric", "double", "", 0);
      writer.append(entry, 0, WpilogWriter.encodeDouble(179));
      writer.append(entry, 1_000_000, WpilogWriter.encodeDouble(-179));
    }
    var log = new LogParser().parse(path);
    var fixture = new Fixture("angle-coverage", path, "synthetic scalar", List.of());
    var registry = new ToolRegistry();
    CoreTools.registerAll(registry);
    var variants = ToolArguments.variants(registry.getTool("read_entry"), fixture, log,
        List.of(fixture), directory);
    var modes = new TreeSet<String>();
    for (var variant : variants) {
      var args = variant.args();
      if (args.has("angle")) {
        assertEquals("/Numeric", args.get("name").getAsString());
        modes.add(args.get("angle").getAsString()
            + (args.has("max_points") ? "-resolution" : "-samples"));
      }
    }
    assertEquals(new TreeSet<>(List.of("degrees-samples", "degrees-resolution",
        "radians-samples", "radians-resolution")), modes,
        "The conformance sweep must exercise each explicit unit and both presentation branches");
  }

  @Test void responseScenariosIncludeDeclaredAndExplicitHeadingReads() throws Exception {
    var calls = JsonParser.parseString(Files.readString(
        Path.of("src/test/resources/tool-responses/scenarios.json")))
        .getAsJsonObject().getAsJsonArray("calls");
    boolean declared = false;
    boolean explicit = false;
    for (var call : calls) {
      var object = call.getAsJsonObject();
      if (!object.get("tool").getAsString().equals("read_entry")) continue;
      var args = object.getAsJsonObject("args");
      if (!args.get("name").getAsString()
          .equals("/RealOutputs/Drive/Pose.rotation._derived.degrees")) continue;
      declared |= args.has("max_points") && !args.has("angle");
      explicit |= args.has("angle") && args.get("angle").getAsString().equals("degrees");
    }
    assertTrue(declared && explicit,
        "Response scenarios must document declared and explicit heading presentation reads");
  }
}
