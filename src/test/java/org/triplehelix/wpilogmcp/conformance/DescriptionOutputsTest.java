/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

/** The G4 checker itself: it must catch a promised output that never appears. */
@DisplayName("DescriptionOutputs")
class DescriptionOutputsTest {

  static Tool tool(String name, String description, String... params) {
    return new Tool() {
      @Override public String name() { return name; }
      @Override public String description() { return description; }
      @Override public JsonObject inputSchema() {
        var props = new JsonObject();
        for (var p : params) props.add(p, new JsonObject());
        var schema = new JsonObject();
        schema.add("properties", props);
        return schema;
      }
      @Override public JsonElement execute(JsonObject arguments) { return null; }
    };
  }

  @Test
  @DisplayName("flags a promised output that no result has; accepts keys, values, and parameters")
  void flagsMissing() {
    var a = tool("tool_a", "Returns peak_value and spike_count, filtered by min_height, with "
        + "basis rio_flag; see tool_b for other_output.", "min_height");
    var b = tool("tool_b", "Returns other_output.");
    var results = List.<Map.Entry<String, JsonElement>>of(
        Map.entry("tool_a", JsonParser.parseString(
            "{\"peak_value\": 1, \"items\": [{\"basis\": \"rio_flag\"}]}")),
        Map.entry("tool_b", JsonParser.parseString("{\"other_output\": 2}")));
    assertEquals(java.util.Set.of("tool_a | spike_count"),
        DescriptionOutputs.missing(List.of(a, b), results));
  }
}
