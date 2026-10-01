/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

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
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.conformance.ConformanceChecks.Check;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs.Fixture;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * Runs every log-reading tool against every {@code .wpilog} under a directory of real logs and
 * checks each result against the same robustness rules as {@link ToolConformanceTest} (status,
 * no non-finite numbers, no silent empties, inputs, limits, and determinism under a reversed
 * entry order), with the same argument variants.
 *
 * <p>Opt-in, because it needs real logs:
 * <pre>
 * ./gradlew test --tests '*RealLogConformanceTest*' -PconformanceLogDir=/path/to/logs
 * </pre>
 * {@code -PconformanceMaxLogs=N} limits the run to the first N logs (path order). There is no
 * known-failures list: every violation fails the test. A report of every call is written to
 * {@code build/reports/conformance/real-logs.txt}.
 */
@DisplayName("Tool conformance on real logs (opt-in)")
class RealLogConformanceTest {

  static final Path REPORT = Path.of("build", "reports", "conformance", "real-logs.txt");
  static final long CALL_TIMEOUT_SECONDS = 180;
  static final int SCAN_DEPTH = 5;
  static final java.util.Set<String> REVLOG_TOOLS = java.util.Set.of("list_revlog_signals",
      "get_revlog_data", "sync_status", "set_revlog_offset", "wait_for_sync");

  static Path logDir;
  static List<Fixture> logs;
  static List<Tool> tools;
  static Path exportDir;
  static Path savedExportDir;
  static java.util.List<Path> savedLogDirs;
  static ExecutorService executor;

  @BeforeAll
  static void setUp() throws IOException {
    var property = System.getProperty("conformance.logdir");
    Assumptions.assumeTrue(property != null && !property.isBlank(),
        "conformance.logdir not set; run with -PconformanceLogDir=/path/to/logs");
    logDir = Path.of(property).toAbsolutePath().normalize();
    Assumptions.assumeTrue(Files.isDirectory(logDir), "not a directory: " + logDir);

    int maxLogs = Integer.getInteger("conformance.maxlogs", Integer.MAX_VALUE);
    try (Stream<Path> files = Files.walk(logDir, SCAN_DEPTH)) {
      logs = files.filter(p -> p.getFileName().toString().endsWith(".wpilog"))
          .sorted()
          .limit(maxLogs)
          .map(p -> new Fixture(logDir.relativize(p).toString(), p, "real log", List.of()))
          .toList();
    }
    Assumptions.assumeFalse(logs.isEmpty(), "no .wpilog files under " + logDir);

    var logManager = LogManager.getInstance();
    logManager.unloadAllLogs();
    logManager.addAllowedDirectory(logDir);
    exportDir = Path.of("build", "real-log-conformance-export").toAbsolutePath();
    Files.createDirectories(exportDir);
    savedExportDir = ExportTools.getExportDirectory();
    ExportTools.setExportDirectory(exportDir.toString());
    savedLogDirs = LogDirectory.getInstance().getLogDirectories();
    LogDirectory.getInstance().setLogDirectory(logDir.toString());

    var captured = new ArrayList<Tool>();
    WpilogTools.registerAll(new ToolRegistry() {
      @Override
      public void registerTool(Tool tool) {
        captured.add(tool);
        super.registerTool(tool);
      }
    });
    captured.sort(Comparator.comparing(Tool::name));
    // -PconformanceTools=a,b limits the sweep to those tools
    var only = System.getProperty("conformance.tools", "");
    var selected = java.util.Arrays.stream(only.split(",")).map(String::trim)
        .filter(n -> !n.isEmpty()).collect(java.util.stream.Collectors.toSet());
    tools = captured.stream().filter(ToolArguments::takesPath)
        .filter(t -> selected.isEmpty() || selected.contains(t.name())).toList();
    Assumptions.assumeFalse(tools.isEmpty(), "no log-reading tool named " + only);
    executor = Executors.newSingleThreadExecutor(r -> {
      var t = new Thread(r, "real-log-conformance-call");
      t.setDaemon(true);
      return t;
    });
  }

  @AfterAll
  static void tearDown() {
    if (executor == null) return;
    executor.shutdownNow();
    ExportTools.setExportDirectory(savedExportDir.toString());
    LogDirectory.getInstance().setLogDirectories(
        savedLogDirs.stream().map(Path::toString).toList());
    LogManager.getInstance().unloadAllLogs();
  }

  record Call(String tool, String log, String variant, JsonElement result, List<Check> failed,
      long millis) {}

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
  @DisplayName("every log-reading tool on every real log meets the robustness rules")
  void conformance() throws Exception {
    var logManager = LogManager.getInstance();
    var calls = new ArrayList<Call>();

    for (var logFixture : logs) {
      var path = logFixture.path().toString();
      System.out.println("[conformance] " + logFixture.id());
      org.triplehelix.wpilogmcp.log.LogData log;
      try {
        log = logManager.getOrLoad(path);
      } catch (Exception e) {
        // A file that is not a readable log (empty, zero-filled, cut before its header): there is
        // nothing to analyze, and every tool must say so with an explained error, never a
        // success and never an internal error
        System.out.println("[conformance]   not loadable (" + e.getMessage() + ")");
        for (var tool : tools) {
          var args = new com.google.gson.JsonObject();
          args.addProperty("path", path);
          var result = run(tool, args);
          var failed = new ArrayList<Check>(result == null ? List.of(Check.TIMEOUT)
              : ConformanceChecks.check(result));
          if (result != null && result.isJsonObject()
              && ConformanceChecks.succeeded(result.getAsJsonObject())) {
            failed.add(Check.SHAPE);
          }
          calls.add(new Call(tool.name(), logFixture.id(), "unloadable", result, failed, 0));
        }
        continue;
      }
      var firstVariants = new java.util.LinkedHashMap<Tool, ToolArguments.Variant>();
      for (var tool : tools) {
        var variants = ToolArguments.variants(tool, logFixture, log, logs, exportDir);
        if (!variants.isEmpty()) firstVariants.put(tool, variants.get(0));
        for (var variant : variants) {
          long start = System.nanoTime();
          var result = run(tool, variant.args());
          long millis = (System.nanoTime() - start) / 1_000_000;
          Integer limit = variant.args().has("limit")
              ? variant.args().get("limit").getAsInt() : null;
          List<Check> failed = result == null ? List.of(Check.TIMEOUT)
              : ConformanceChecks.check(result, limit, true, tool.name(), variant.args());
          // Most real logs end inside their last record: every result must say so
          if (log.truncated() && result != null && result.isJsonObject()
              && !ConformanceChecks.reportsTruncation(result.getAsJsonObject())) {
            failed = new ArrayList<>(failed);
            failed.add(Check.TRUNCATION_UNREPORTED);
          }
          calls.add(new Call(tool.name(), logFixture.id(), variant.label(), result, failed,
              millis));
        }
      }
      // Determinism: the same first calls, with the same arguments, with the entries iterating
      // in reverse order.
      // Revlog tools are left out: their results depend on whether the wpilog was synchronized
      // with its revlogs when loaded, which the replacement view is not.
      var inner = new LazyParsedLog(path, new DataLogReader(path), 256L * 1024 * 1024);
      logManager.testPutLog(path, new PermutedLogData(inner, 0));
      try {
        for (var first : firstVariants.entrySet()) {
          var tool = first.getKey();
          if (REVLOG_TOOLS.contains(tool.name())) continue;
          var variant = first.getValue();
          var original = calls.stream().filter(c -> c.tool().equals(tool.name())
              && c.log().equals(logFixture.id()) && c.variant().equals(variant.label()))
              .findFirst().orElseThrow();
          if (original.result() == null) continue;
          var permuted = run(tool, variant.args());
          if (!ConformanceChecks.normalize(original.result())
              .equals(ConformanceChecks.normalize(permuted))) {
            calls.add(new Call(tool.name(), logFixture.id(), variant.label(), permuted,
                List.of(Check.NONDETERMINISTIC), 0));
          }
        }
      } finally {
        logManager.unloadLog(path);
        inner.close();
      }
      logManager.unloadAllLogs();
    }

    writeReport(calls);
    var violations = new TreeMap<String, List<String>>();
    for (var call : calls) {
      for (var check : call.failed()) {
        violations.computeIfAbsent(call.tool() + " | " + check.label(), k -> new ArrayList<>())
            .add(call.log() + " [" + call.variant() + "]");
      }
    }
    if (!violations.isEmpty()) {
      var msg = new StringBuilder("Conformance violations on real logs (tool | check: logs):\n");
      for (Map.Entry<String, List<String>> e : violations.entrySet()) {
        msg.append("  ").append(e.getKey()).append(" (").append(e.getValue().size())
            .append("): ").append(String.join(", ", e.getValue().stream().limit(5).toList()))
            .append(e.getValue().size() > 5 ? ", ..." : "").append('\n');
      }
      msg.append("Report: ").append(REPORT.toAbsolutePath());
      fail(msg.toString());
    }
  }

  static void writeReport(List<Call> calls) throws IOException {
    Files.createDirectories(REPORT.getParent());
    var gson = new GsonBuilder().serializeSpecialFloatingPointValues().create();
    var sb = new StringBuilder();
    for (var call : calls) {
      sb.append(call.tool()).append(" | ").append(call.log()).append(" | ")
          .append(call.variant()).append(" | ")
          .append(call.failed().isEmpty() ? "ok" : call.failed().toString())
          .append(" | ").append(call.millis()).append(" ms\n");
      if (call.result() != null) {
        var text = gson.toJson(call.result());
        sb.append("    ").append(text.length() > 800 ? text.substring(0, 800) + "..." : text)
            .append('\n');
      }
    }
    Files.writeString(REPORT, sb.toString(), StandardCharsets.UTF_8);
  }
}
