/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * The server's counts, time ranges, statistics, and enabled windows on every fixture, against a
 * reader that shares no code with it ({@link IndependentLog}).
 */
@DisplayName("Differential check on the fixture corpus")
class FixtureDifferentialTest {

  @Test
  @DisplayName("the tools and an independent reader agree on every fixture")
  void everyFixtureAgrees() throws Exception {
    var dir = FixtureLogs.defaultDirectory();
    var fixtures = FixtureLogs.generateAll(dir);
    var logManager = LogManager.getInstance();
    logManager.unloadAllLogs();
    logManager.addAllowedDirectory(dir);
    var registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    var report = new ArrayList<String>();
    int findings = 0;
    int statistics = 0;
    for (var fixture : fixtures) {
      var outcome = DifferentialChecks.compare(registry, fixture.path());
      report.addAll(DifferentialChecks.describe(fixture.id(), outcome));
      findings += outcome.findings().size();
      statistics += outcome.statisticsCompared();
    }
    logManager.unloadAllLogs();
    assertEquals(0, findings, String.join("\n", report));
    assertTrue(statistics > 500, "the check compared too little to mean anything: " + statistics);
  }
}
