/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * The differential check on real logs: every {@code .wpilog} under a directory, the server's
 * answers against {@link IndependentLog}. A file neither side can read as a log is counted, not
 * failed; a file only one side can read is a finding.
 *
 * <p>Opt-in, because it needs real logs:
 * <pre>
 * ./gradlew test --tests '*RealLogDifferentialTest*' -PconformanceLogDir=/path/to/logs
 * </pre>
 * {@code -PconformanceMaxLogs=N} limits it to the first N logs. The report is
 * {@code build/reports/conformance/differential.txt}.
 */
@DisplayName("Differential check on real logs (opt-in)")
class RealLogDifferentialTest {
  static final Path REPORT = Path.of("build", "reports", "conformance", "differential.txt");

  @Test
  @DisplayName("the tools and an independent reader agree on every real log")
  void everyLogAgrees() throws Exception {
    var property = System.getProperty("conformance.logdir");
    Assumptions.assumeTrue(property != null && !property.isBlank(),
        "conformance.logdir not set; run with -PconformanceLogDir=/path/to/logs");
    var logDir = Path.of(property).toAbsolutePath().normalize();
    Assumptions.assumeTrue(Files.isDirectory(logDir), "not a directory: " + logDir);
    int maxLogs = Integer.getInteger("conformance.maxlogs", Integer.MAX_VALUE);
    java.util.List<Path> logs;
    try (Stream<Path> walk = Files.walk(logDir, 6)) {
      logs = walk.filter(p -> p.toString().toLowerCase().endsWith(".wpilog")).sorted()
          .limit(maxLogs).toList();
    }
    Assumptions.assumeTrue(!logs.isEmpty(), "no .wpilog under " + logDir);

    var logManager = LogManager.getInstance();
    var savedAllowed = logManager.getAllowedDirectories();
    logManager.unloadAllLogs();
    logManager.addAllowedDirectory(logDir);
    var registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    var report = new ArrayList<String>();
    int findings = 0;
    int unreadable = 0;
    long statistics = 0;
    try {
      for (var log : logs) {
        var id = logDir.relativize(log).toString();
        DifferentialChecks.Outcome outcome;
        try {
          outcome = DifferentialChecks.compare(registry, log);
        } catch (java.io.IOException notALog) {
          // The independent reader cannot read it as a log: the server must say the same
          var listed = DifferentialChecks.call(registry, "list_entries", log);
          if ("error".equals(DifferentialChecks.status(listed))
              && !listed.get("error").getAsString().startsWith("Internal error")) {
            unreadable++;
            report.add(id + ": not a readable log, and the server says so: " + listed.get("error"));
          } else {
            findings++;
            report.add(id + ": DISAGREE the file is not a readable log, but list_entries says "
                + listed);
          }
          continue;
        }
        report.addAll(DifferentialChecks.describe(id, outcome));
        findings += outcome.findings().size();
        statistics += outcome.statisticsCompared();
        logManager.unloadAllLogs();
        System.out.println("[differential] " + report.get(report.size() - 1 - outcome.findings().size()
            - outcome.notes().size()));
      }
    } finally {
      logManager.unloadAllLogs();
      logManager.clearAllowedDirectories();
      savedAllowed.forEach(logManager::addAllowedDirectory);
      report.add(0, logs.size() + " logs, " + unreadable + " unreadable, " + statistics
          + " statistics compared, " + findings + " disagreements");
      Files.createDirectories(REPORT.getParent());
      Files.write(REPORT, report, StandardCharsets.UTF_8);
    }
    assertEquals(0, findings, "The server and the independent reader disagree; see "
        + REPORT.toAbsolutePath() + "\n" + String.join("\n", report.stream()
            .filter(l -> l.contains("DISAGREE")).limit(40).toList()));
  }
}
