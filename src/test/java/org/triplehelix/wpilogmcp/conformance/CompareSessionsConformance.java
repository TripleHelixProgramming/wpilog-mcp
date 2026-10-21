/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;
import org.triplehelix.wpilogmcp.tools.SessionStoreFixture;
import org.triplehelix.wpilogmcp.tools.SessionStoreFixture.SessionSpec;

/**
 * compare_sessions reads only catalogued stores, and the fixture corpus holds none, so the schema
 * corpus sees only its refusals. This adds one comparison of two finished sessions of a
 * synthetic store, with every signal role, a named entry and pooling, so the outputs its
 * description names are checked against a result it really returned.
 */
final class CompareSessionsConformance {
  private CompareSessionsConformance() {}

  static List<ToolConformanceTest.Call> exercise(Path root, Tool tool) throws Exception {
    var manager = LogManager.getInstance();
    var previous = manager.getAllowedDirectories();
    var store = SessionStoreFixture.store(root, "compare-sessions-store", "conformance-compare-store");
    // Two sessions with different code versions; no published battery, current or loop entry,
    // so each role is an explained omission and the result is partial.
    SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.all(
        SessionStoreFixture.doubles("/X", 1, 2, 3),
        SessionStoreFixture.strings("/RealMetadata/GitSHA", new double[] {0}, "a".repeat(40)))));
    SessionStoreFixture.write(store, new SessionSpec("s1").member("m.wpilog", 0, 2, SessionStoreFixture.all(
        SessionStoreFixture.doubles("/X", 4, 5, 6),
        SessionStoreFixture.strings("/RealMetadata/GitSHA", new double[] {0}, "b".repeat(40)))));
    manager.addAllowedDirectory(store.root());
    try {
      var arguments = new JsonObject();
      arguments.addProperty("scope_id", store.id());
      var sessions = new JsonArray();
      for (var id : List.of("s0", "s1")) {
        var session = new JsonObject();
        session.addProperty("session_id", id);
        sessions.add(session);
      }
      arguments.add("sessions", sessions);
      var metrics = new JsonArray();
      metrics.add(metric("x", "name", "/X", "mean"));
      metrics.add(metric("battery", "role", "battery_voltage", "mean"));
      metrics.add(metric("current", "role", "total_current", "max"));
      metrics.add(metric("loop", "role", "loop_time_full", "p95"));
      metrics.add(metric("user", "role", "loop_time_user", "median"));
      arguments.add("metrics", metrics);
      arguments.addProperty("pool_samples", true);
      var result = tool.execute(arguments).getAsJsonObject();
      if (!"partial".equals(result.get("status").getAsString())) {
        throw new AssertionError("compare_sessions on the synthetic store: " + result);
      }
      return List.of(new ToolConformanceTest.Call(tool.name(), "synthetic-store", "every-role-pooled", result,
          ConformanceChecks.check(result, null, ToolArguments.takesPath(tool), tool.name(), arguments)));
    } finally {
      manager.clearAllowedDirectories();
      previous.forEach(manager::addAllowedDirectory);
    }
  }

  private static JsonObject metric(String id, String kind, String value, String statistic) {
    var metric = new JsonObject();
    metric.addProperty("id", id);
    metric.addProperty(kind, value);
    metric.addProperty("statistic", statistic);
    return metric;
  }
}
