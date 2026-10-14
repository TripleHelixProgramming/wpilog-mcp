/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.context.ProcFixture;
import org.triplehelix.wpilogmcp.capture.context.ProcStats;
import org.triplehelix.wpilogmcp.capture.context.StatsCommand;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;
import org.triplehelix.wpilogmcp.ssh.JschConnection;

class RobotFactsStatsTimingTest {
  @TempDir Path temp;
  private static final String DISCOVERY = ProcFixture.sample(0)
      + "WPILOG_STATS_1:disk_paths\n/home/lvuser\n/media/usb\n/media/usb\n";

  @Test void reportTimesThreeRealSteadyCommandsProcReadsAndEachResolvedDfPath() throws Exception {
    try (var rio = new FakeRoboRio(temp, "SYNTHETIC-TIMING", "")) {
      for (var probe : RobotFacts.commands()) rio.reply(probe.command(), 127, "", "synthetic absent");
      rio.reply(StatsCommand.lookup(true), 0, DISCOVERY, "");
      String steady = StatsCommand.sample(List.of("/home/lvuser", "/media/usb"), ProcStats.configuration(DISCOVERY, null));
      rio.reply(steady, 0, ProcFixture.sample(0), "");
      for (String command : StatsCommand.timingCommands(ProcStats.configuration(DISCOVERY, null)).values())
        rio.reply(command, 0, ProcFixture.sample(0), "");
      var config = new PullConfig(false, PullConfig.DISABLED.directories(), 0, 1_000_000,
          new PullConfig.Ssh("lvuser", "", null, false, rio.port()));
      try (var connection = JschConnection.connect("127.0.0.1", config.ssh(), null)) {
        var report = RobotFacts.collect(connection, "127.0.0.1", config, Clock.systemUTC());
        var timings = report.observations().stream().filter(o -> o.probe().id().startsWith("stats-")).toList();
        assertEquals(List.of("stats-discovery", "stats-steady-1", "stats-steady-2", "stats-steady-3",
            "stats-proc", "stats-df:/home/lvuser", "stats-df:/media/usb"), timings.stream().map(o -> o.probe().id()).toList());
        for (var observation : timings) {
          assertEquals(0, observation.reply().exitStatus(), observation.probe().id());
          assertTrue(observation.reply().elapsedNanos() > 0);
          if (observation.probe().id().startsWith("stats-steady")) assertEquals(steady, observation.probe().command());
          if (observation.probe().id().equals("stats-proc")) {
            assertFalse(observation.probe().command().contains("df -Pk"));
            assertTrue(observation.probe().command().contains("emit /proc/42/stat"));
          }
          if (observation.probe().id().startsWith("stats-df:"))
            assertEquals("export LC_ALL=C; df -Pk '" + observation.probe().id().substring("stats-df:".length()) + "' 2>/dev/null", observation.probe().command());
        }
        String markdown = RobotFactsReport.markdown(report, RobotFactsReport.redactor(config.ssh()));
        for (var observation : timings) {
          String section = markdown.substring(markdown.indexOf("### " + observation.probe().id() + "\n"));
          assertTrue(section.contains("elapsed_ms: " + observation.reply().elapsedNanos() / 1_000_000.));
        }
      }
    }
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"failed", "truncated", "missing-paths"})
  void incompleteDiscoveryCannotProduceGuessedTimingCommands(String failure) throws Exception {
    try (var rio = new FakeRoboRio(temp, "SYNTHETIC-INCOMPLETE", "")) {
      for (var probe : RobotFacts.commands()) rio.reply(probe.command(), 127, "", "synthetic absent");
      String text = failure.equals("missing-paths") ? ProcFixture.sample(0) : DISCOVERY;
      if (failure.equals("truncated")) text += "\n".repeat(40_000);
      rio.reply(StatsCommand.lookup(true), failure.equals("failed") ? 1 : 0, text, "");
      var config = new PullConfig(false, PullConfig.DISABLED.directories(), 0, 1_000_000,
          new PullConfig.Ssh("lvuser", "", null, false, rio.port()));
      try (var connection = JschConnection.connect("127.0.0.1", config.ssh(), null)) {
        var report = RobotFacts.collect(connection, "127.0.0.1", config, Clock.systemUTC());
        var timings = report.observations().stream().filter(o -> o.probe().id().startsWith("stats-")).toList();
        assertEquals(List.of("stats-discovery", "stats-timing"), timings.stream().map(o -> o.probe().id()).toList());
        assertEquals(-1, timings.get(1).reply().exitStatus());
        assertFalse(timings.get(1).reply().stderr().isBlank());
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"stats-proc", "stats-unknown"})
  void aFixedProbeCannotMasqueradeAsADifferentStatsMeasurement(String id) throws Exception {
    try (var rio = new FakeRoboRio(temp, "SYNTHETIC-PART", "")) {
      rio.reply("df -Pk /", 0, "synthetic df", "");
      var known = ProcStats.configuration(DISCOVERY, null);
      try (var connection = JschConnection.connect("127.0.0.1",
          new PullConfig.Ssh("lvuser", "", null, false, rio.port()), null)) {
        var result = RobotFacts.inspectStats(connection, new RobotFacts.Probe(id, "df -Pk /", 0), known);
        assertEquals(-1, result.reply().exitStatus());
        assertTrue(result.reply().stderr().contains("outside read-only allowlist"));
        assertEquals(0, rio.commands.get());
      }
    }
  }

  @Test void timingAllowlistRejectsChangedCommandsAndMismatchedPartsBeforeOpeningAChannel() throws Exception {
    try (var rio = new FakeRoboRio(temp, "SYNTHETIC-ALLOWLIST", "")) {
      var known = ProcStats.configuration(DISCOVERY, null);
      var approved = StatsCommand.timingCommands(known);
      var config = new PullConfig.Ssh("lvuser", "", null, false, rio.port());
      try (var connection = JschConnection.connect("127.0.0.1", config, null)) {
        for (String command : List.of(approved.get("stats-proc") + "; touch /tmp/forbidden",
            "df -Pk /; touch /tmp/forbidden", approved.get("stats-df:/media/usb"))) {
          var result = RobotFacts.inspectStats(connection, new RobotFacts.Probe("stats-proc", command, 0), known);
          assertEquals(-1, result.reply().exitStatus());
          assertTrue(result.reply().stderr().contains("outside read-only allowlist"));
        }
        assertEquals(0, rio.commands.get()); assertEquals(0, rio.refusedCommands.get());
      }
    }
  }
}
