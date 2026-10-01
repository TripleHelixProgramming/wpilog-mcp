/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs.Fixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

/**
 * Base class for tests that call tools on the fixture corpus through the real server path
 * (LogManager loading the file, {@code ToolBase.execute} enforcing the result contract).
 */
abstract class FixtureToolTestBase {

  static Map<String, Fixture> fixtures;
  static Map<String, Tool> tools;

  @BeforeAll
  static void setUpFixtures() throws IOException {
    if (fixtures == null) {
      var dir = FixtureLogs.defaultDirectory();
      var map = new TreeMap<String, Fixture>();
      for (var f : FixtureLogs.generateAll(dir)) map.put(f.id(), f);
      fixtures = map;
      LogManager.getInstance().addAllowedDirectory(dir);
    }
    if (tools == null) {
      var captured = new TreeMap<String, Tool>();
      WpilogTools.registerAll(new ToolRegistry() {
        @Override
        public void registerTool(Tool tool) {
          captured.put(tool.name(), tool);
          super.registerTool(tool);
        }
      });
      tools = captured;
    }
  }

  @AfterAll
  static void unloadLogs() {
    LogManager.getInstance().unloadAllLogs();
  }

  static Path fixturePath(String id) {
    var f = fixtures.get(id);
    assertNotNull(f, "no fixture " + id);
    return f.path();
  }

  /** Calls a tool on a fixture with extra key/value arguments. */
  static JsonObject call(String tool, String fixtureId, Object... keyValues) {
    var args = new JsonObject();
    if (fixtureId != null) args.addProperty("path", fixturePath(fixtureId).toString());
    for (int i = 0; i < keyValues.length; i += 2) {
      var key = (String) keyValues[i];
      var value = keyValues[i + 1];
      if (value instanceof Number n) args.addProperty(key, n);
      else if (value instanceof Boolean b) args.addProperty(key, b);
      else if (value instanceof JsonElement e) args.add(key, e);
      else args.addProperty(key, value.toString());
    }
    try {
      return tools.get(tool).execute(args).getAsJsonObject();
    } catch (Exception e) {
      throw new IllegalStateException(tool + " threw", e);
    }
  }

  static List<String> strings(JsonArray array) {
    var out = new ArrayList<String>();
    array.forEach(e -> out.add(e.getAsString()));
    return out;
  }

  static List<JsonObject> objects(JsonArray array) {
    var out = new ArrayList<JsonObject>();
    array.forEach(e -> out.add(e.getAsJsonObject()));
    return out;
  }

  static void unchecked(IOException e) {
    throw new UncheckedIOException(e);
  }
}
