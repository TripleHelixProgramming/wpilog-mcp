/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import edu.wpi.first.util.datalog.DataLogReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.conformance.ConformanceChecks.Check;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs.Fixture;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * Runs every registered tool against every fixture log and checks each result against the
 * robustness rules (see {@link ConformanceChecks}).
 *
 * <p>Violations that are known and not yet fixed are listed in
 * {@code src/test/resources/conformance/known-failures.txt}, one per line as
 * {@code tool | fixture | variant | check}. The test fails on any violation not in that list (a
 * regression) and on any listed violation that no longer occurs (so the list only shrinks). Run
 * with {@code ./gradlew test --tests '*ToolConformanceTest' -PconformanceUpdate} to rewrite the
 * list from the current results after deliberately fixing (or accepting) violations.
 *
 * <p>A report of every call is written to {@code build/reports/conformance/report.txt}.
 */
@DisplayName("Tool conformance on the fixture corpus")
class ToolConformanceTest {

  static final Path KNOWN_FAILURES =
      Path.of("src", "test", "resources", "conformance", "known-failures.txt");
  static final Path REPORT = Path.of("build", "reports", "conformance", "report.txt");
  static final long CALL_TIMEOUT_SECONDS = 60;

  static List<Fixture> fixtures;
  static List<Tool> tools;
  static Path exportDir;
  static Path savedExportDir;
  static Path savedLogDir;
  static ExecutorService executor;

  @BeforeAll
  static void setUp() throws IOException {
    var dir = FixtureLogs.defaultDirectory();
    fixtures = FixtureLogs.generateAll(dir);
    var logManager = LogManager.getInstance();
    logManager.unloadAllLogs();
    logManager.addAllowedDirectory(dir);
    exportDir = dir.resolveSibling("test-fixtures-export").toAbsolutePath();
    Files.createDirectories(exportDir);
    savedExportDir = ExportTools.getExportDirectory();
    ExportTools.setExportDirectory(exportDir.toString());
    // list_available_logs lists the fixture directory
    savedLogDir = LogDirectory.getInstance().getLogDirectory();
    LogDirectory.getInstance().setLogDirectory(dir.toString());

    var captured = new ArrayList<Tool>();
    WpilogTools.registerAll(new ToolRegistry() {
      @Override
      public void registerTool(Tool tool) {
        captured.add(tool);
        super.registerTool(tool);
      }
    });
    captured.sort(Comparator.comparing(Tool::name));
    tools = List.copyOf(captured);
    executor = Executors.newSingleThreadExecutor(r -> {
      var t = new Thread(r, "conformance-call");
      t.setDaemon(true);
      return t;
    });
  }

  @AfterAll
  static void tearDown() {
    executor.shutdownNow();
    ExportTools.setExportDirectory(savedExportDir.toString());
    LogDirectory.getInstance().setLogDirectory(savedLogDir == null ? null : savedLogDir.toString());
    LogManager.getInstance().unloadAllLogs();
  }

  record Call(String tool, String fixture, String variant, JsonElement result, List<Check> failed) {}

  JsonElement run(Tool tool, com.google.gson.JsonObject args) throws Exception {
    var future = executor.submit(() -> tool.execute(args));
    try {
      return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      return null;
    }
  }

  @Test
  @DisplayName("every tool on every fixture meets the robustness rules (or is a known failure)")
  void conformance() throws Exception {
    var logManager = LogManager.getInstance();
    var calls = new ArrayList<Call>();

    for (var tool : tools) {
      if (!ToolArguments.takesPath(tool)) {
        for (var variant : ToolArguments.variants(tool, null, null, fixtures, exportDir)) {
          calls.add(evaluate(tool, "-", variant));
        }
        continue;
      }
      for (var fixture : fixtures) {
        var path = fixture.path().toString();
        var log = logManager.getOrLoad(path);
        // Revlog tools answer from the synchronized revlogs: let the background sync finish so
        // every call sees the same state
        logManager.waitForRevLogSync(path, 30_000);
        // Harness self-check: the log the tools will see must decode (an empty decode would
        // make every check below pass vacuously)
        for (var name : log.entries().keySet()) {
          if (log.sampleCount(name) > 0) {
            assertFalse(log.values().get(name).isEmpty(),
                "harness bug: " + fixture.id() + " entry " + name + " decodes to no values");
            break;
          }
        }
        var variants = ToolArguments.variants(tool, fixture, log, fixtures, exportDir);
        for (var variant : variants) {
          var call = evaluate(tool, fixture.id(), variant);
          calls.add(call);
          if (call.result() == null) continue;
          // Revlog tools depend on the load-time sync, which the reordered view below is not
          if (RealLogConformanceTest.REVLOG_TOOLS.contains(tool.name())) continue;
          // Determinism: the same call with the entries iterating in other orders. Each view
          // wraps its own LazyParsedLog because the cache closes whatever log it replaces; the
          // path is unloaded afterwards so the next call reloads a fresh log.
          for (long order : PermutedLogData.ORDERS) {
            var inner = new LazyParsedLog(path, new DataLogReader(path), 256L * 1024 * 1024);
            logManager.testPutLog(path, new PermutedLogData(inner, order));
            try {
              assertInstanceOf(PermutedLogData.class, logManager.getOrLoad(path));
              var permuted = run(tool, variant.args());
              var a = ConformanceChecks.normalize(call.result());
              var b = ConformanceChecks.normalize(permuted);
              if (a == null || !a.equals(b)) {
                calls.add(new Call(tool.name(), fixture.id(), variant.label(), permuted,
                    List.of(Check.NONDETERMINISTIC)));
                break;
              }
            } finally {
              logManager.unloadLog(path);
              inner.close();
            }
          }
        }
      }
    }

    writeReport(calls);
    var results = calls.stream()
        .map(c -> java.util.Map.entry(c.tool(), c.result() == null
            ? (JsonElement) com.google.gson.JsonNull.INSTANCE : c.result())).toList();
    var promised = DescriptionOutputs.missing(tools, results);
    // The server guidance names fields, events, and bases the agent should look for: each must
    // be something a tool produces
    var guidanceMissing = DescriptionOutputs.missingInGuidance(tools, results,
        org.triplehelix.wpilogmcp.tools.AnalysisGuidance.analysisPrinciples().toString()
            + org.triplehelix.wpilogmcp.tools.AnalysisGuidance.SERVER_INSTRUCTIONS);

    var observed = new TreeSet<String>();
    for (var call : calls) {
      for (var check : call.failed()) {
        observed.add(key(call.tool(), call.fixture(), call.variant(), check));
      }
    }
    // Every tool must have been exercised at least once
    for (var tool : tools) {
      assertTrue(calls.stream().anyMatch(c -> c.tool().equals(tool.name())),
          "tool never exercised: " + tool.name());
    }

    if (Boolean.getBoolean("conformance.update")) {
      Files.createDirectories(KNOWN_FAILURES.getParent());
      var lines = new ArrayList<String>();
      lines.add("# Known conformance failures: tool | fixture | variant | check");
      lines.add("# Generated by ToolConformanceTest with -PconformanceUpdate. Each line is a");
      lines.add("# violation still to fix; the test fails if a new one appears or a listed one");
      lines.add("# disappears. See doc/ROBUSTNESS_PLAN.md.");
      lines.addAll(observed);
      Files.write(KNOWN_FAILURES, lines, StandardCharsets.UTF_8);
      return;
    }

    var known = readKnown();
    var unexpected = new TreeSet<>(observed);
    unexpected.removeAll(known);
    var stale = new TreeSet<>(known);
    stale.removeAll(observed);
    if (!unexpected.isEmpty() || !stale.isEmpty()) {
      var msg = new StringBuilder();
      if (!unexpected.isEmpty()) {
        msg.append("New conformance violations (fix them, or if deliberate add to ")
            .append(KNOWN_FAILURES).append("):\n");
        unexpected.forEach(k -> msg.append("  ").append(k).append('\n'));
      }
      if (!stale.isEmpty()) {
        msg.append("Known failures that no longer occur (remove them from ")
            .append(KNOWN_FAILURES).append("):\n");
        stale.forEach(k -> msg.append("  ").append(k).append('\n'));
      }
      msg.append("Report: ").append(REPORT.toAbsolutePath());
      fail(msg.toString());
    }
    assertFalse(calls.isEmpty());
    // G4: every output a description names appears in at least one result of that tool
    assertTrue(promised.isEmpty(), "Descriptions name outputs no fixture result contains "
        + "(tool | term): " + promised);
    assertTrue(guidanceMissing.isEmpty(), "The guidance names outputs no fixture result "
        + "contains: " + guidanceMissing);
  }

  Call evaluate(Tool tool, String fixtureId, ToolArguments.Variant variant) throws Exception {
    var result = run(tool, variant.args());
    Integer limit = variant.args().has("limit") ? variant.args().get("limit").getAsInt() : null;
    List<Check> failed = result == null ? List.of(Check.TIMEOUT)
        : ConformanceChecks.check(result, limit, ToolArguments.takesPath(tool), tool.name(),
            variant.args());
    return new Call(tool.name(), fixtureId, variant.label(), result, failed);
  }

  static String key(String tool, String fixture, String variant, Check check) {
    return tool + " | " + fixture + " | " + variant + " | " + check.label();
  }

  static TreeSet<String> readKnown() throws IOException {
    var known = new TreeSet<String>();
    if (!Files.exists(KNOWN_FAILURES)) return known;
    for (var line : Files.readAllLines(KNOWN_FAILURES, StandardCharsets.UTF_8)) {
      var trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
      known.add(trimmed);
    }
    return known;
  }

  static void writeReport(List<Call> calls) throws IOException {
    Files.createDirectories(REPORT.getParent());
    var gson = new GsonBuilder().serializeSpecialFloatingPointValues().create();
    var sb = new StringBuilder();
    for (var call : calls) {
      sb.append(call.tool()).append(" | ").append(call.fixture()).append(" | ")
          .append(call.variant()).append(" | ")
          .append(call.failed().isEmpty() ? "ok" : call.failed().toString()).append('\n');
      if (call.result() != null) {
        var text = gson.toJson(call.result());
        sb.append("    ").append(text.length() > 600 ? text.substring(0, 600) + "..." : text)
            .append('\n');
      }
    }
    Files.writeString(REPORT, sb.toString(), StandardCharsets.UTF_8);
  }
}
