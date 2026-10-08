/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

class RelativeTimeTest {
  @TempDir Path directory;
  @Test void closedWindowResolvesAtTheEndAndChecksNumbersAndConflicts() throws Exception {
    var path = directory.resolve("relative.wpilog");
    try (var writer = new WpilogOutput(path)) {
      int id = writer.start("/counter", "int64", "", 0);
      for (long second = 1; second <= 10; second++) writer.append(id, second * 1_000_000, WpilogOutput.payload(2, second));
    }
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(directory);
    var registry = new ToolRegistry(); WpilogTools.registerAll(registry);
    var args = new JsonObject(); args.addProperty("path", path.toString()); args.addProperty("name", "/counter"); args.addProperty("last_seconds", 2);
    try {
      var statistics = registry.getTool("get_statistics").execute(args).getAsJsonObject();
      assertEquals(3, statistics.get("count").getAsInt()); assertEquals(9, statistics.get("mean").getAsDouble());
      var window = statistics.getAsJsonObject("inputs").getAsJsonObject("window");
      assertEquals(8, window.get("start").getAsDouble()); assertEquals(10, window.get("end").getAsDouble());
      assertEquals(2, statistics.getAsJsonObject("inputs").get("last_seconds").getAsInt());
      var other = directory.resolve("other.wpilog");
      try (var writer = new WpilogOutput(other)) {
        int id = writer.start("/counter", "int64", "", 0);
        for (long second = 11; second <= 20; second++) writer.append(id, second * 1_000_000, WpilogOutput.payload(2, second));
      }
      args.addProperty("compare_path", other.toString());
      var compared = registry.getTool("compare_matches").execute(args).getAsJsonObject();
      assertEquals(10, compared.getAsJsonObject("differences").get("mean").getAsDouble());
      assertEquals(18, compared.getAsJsonObject("inputs").getAsJsonObject("windows").getAsJsonObject(other.toString()).get("start").getAsDouble());
      manager.unloadLog(other.toString()); args.remove("compare_path");
      assertFalse(args.has("start_time"), "Caller arguments are immutable across concurrent uses");
      var read = registry.getTool("read_entry").execute(args).getAsJsonObject();
      assertEquals(window, read.getAsJsonObject("inputs").getAsJsonObject("window"));
      for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
        args.addProperty("last_seconds", invalid);
        var error = registry.getTool("get_statistics").execute(args).getAsJsonObject();
        assertEquals("error", error.get("status").getAsString()); assertTrue(error.get("error").getAsString().contains("last_seconds"));
      }
      args.addProperty("last_seconds", 2);
      for (String bound : List.of("start_time", "end_time")) {
        args.addProperty(bound, 8);
        var error = registry.getTool("get_statistics").execute(args).getAsJsonObject();
        assertEquals("error", error.get("status").getAsString()); assertTrue(error.get("error").getAsString().contains(bound)); args.remove(bound);
      }
    } finally { manager.unloadLog(path.toString()); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
  @Test void everyTimeScopedLogToolAdvertisesTheRelativeWindowAndOthersDoNot() {
    var registry = new ToolRegistry(); WpilogTools.registerAll(registry);
    int scoped = 0;
    for (String name : registry.getToolNames()) {
      var tool = registry.getTool(name); var properties = tool.inputSchema().getAsJsonObject("properties");
      boolean accepts = properties != null && List.of("scope", "windows", "start_time", "end_time").stream().anyMatch(properties::has);
      if (accepts) scoped++;
      assertEquals(accepts, properties != null && properties.has("last_seconds"), name);
    }
    assertTrue(scoped > 10);
  }
}
