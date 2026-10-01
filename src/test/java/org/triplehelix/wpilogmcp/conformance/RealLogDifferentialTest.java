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
import java.util.List;
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
 * failed; a file only one side can read is a finding, and so is a log the check cannot finish.
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

  /** Whether the server declines the file with an explanation (not an internal error). */
  private static boolean declines(ToolRegistry registry, Path log, List<String> said)
      throws Exception {
    var listed = DifferentialChecks.call(registry, "list_entries", log);
    said.add(listed.has("error") ? listed.get("error").getAsString() : listed.toString());
    return "error".equals(DifferentialChecks.status(listed)) && listed.has("error")
        && !listed.get("error").getAsString().startsWith("Internal error");
  }

  @Test
  @DisplayName("the tools and an independent reader agree on every real log")
  void everyLogAgrees() throws Exception {
    var property = System.getProperty("conformance.logdir");
    Assumptions.assumeTrue(property != null && !property.isBlank(),
        "conformance.logdir not set; run with -PconformanceLogDir=/path/to/logs");
    var logDir = Path.of(property).toAbsolutePath().normalize();
    Assumptions.assumeTrue(Files.isDirectory(logDir), "not a directory: " + logDir);
    int maxLogs = Integer.getInteger("conformance.maxlogs", Integer.MAX_VALUE);
    List<Path> logs;
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
    int reached = 0;
    int compared = 0;
    int unreadable = 0;
    int tooLarge = 0;
    int findings = 0;
    long statistics = 0;
    try {
      for (var log : logs) {
        var id = logDir.relativize(log).toString();
        var lines = new ArrayList<String>();
        reached++;
        try {
          var outcome = DifferentialChecks.compare(registry, log);
          compared++;
          lines.addAll(DifferentialChecks.describe(id, outcome));
          findings += outcome.findings().size();
          statistics += outcome.statisticsCompared();
        } catch (IndependentLog.NotALog | IndependentLog.TooLarge unreadableHere) {
          // The independent reader cannot read it: the server must decline it too, and say why
          var said = new ArrayList<String>();
          boolean large = unreadableHere instanceof IndependentLog.TooLarge;
          if (declines(registry, log, said)) {
            if (large) tooLarge++;
            else unreadable++;
            lines.add(id + ": " + (large ? "over 2 GB" : "not a readable log")
                + ", and the server says so: " + said.get(0));
          } else {
            findings++;
            lines.add(id + ": DISAGREE " + unreadableHere.getMessage()
                + ", but list_entries says " + said.get(0));
          }
        } catch (Throwable failure) {
          // An OutOfMemoryError included: the report must name the log, and the run go on
          findings++;
          lines.add(id + ": DISAGREE the check could not finish: " + failure);
        } finally {
          logManager.unloadAllLogs();
        }
        report.addAll(lines);
        System.out.println("[differential] " + lines.get(0));
      }
    } finally {
      logManager.unloadAllLogs();
      logManager.clearAllowedDirectories();
      savedAllowed.forEach(logManager::addAllowedDirectory);
      report.add(0, logs.size() + " logs" + (reached < logs.size() ? " (the run stopped after "
          + reached + ")" : "") + ": " + compared + " compared, " + unreadable
          + " not readable as logs, " + tooLarge + " over 2 GB; " + statistics
          + " statistics compared; " + findings + " disagreements");
      Files.createDirectories(REPORT.getParent());
      Files.write(REPORT, report, StandardCharsets.UTF_8);
    }
    assertEquals(0, findings, "The server and the independent reader disagree; see "
        + REPORT.toAbsolutePath() + "\n" + String.join("\n", report.stream()
            .filter(l -> l.contains("DISAGREE")).limit(40).toList()));
  }
}
