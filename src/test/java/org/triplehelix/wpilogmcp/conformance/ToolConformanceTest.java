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
  static java.util.List<Path> savedLogDirs;
  static ExecutorService executor;

  static Path createSystemFixture(Path systemRoot) throws Exception {
    FixtureLogs.freshDirectory(systemRoot);
    var logManager = LogManager.getInstance();
    var systemCapture = org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture.create(systemRoot, "synthetic", true, 42);
    var systemStore = logManager.stores().store(systemRoot);
    var systemPull = systemStore.systemPulls(org.triplehelix.wpilogmcp.capture.pull.FakeRobot.device(
        org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture.SERIAL, "SHA256:fixture"),
        org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture.WALL);
    if (!systemPull.beginPass()) throw new IOException("Missing system fixture session");
    systemPull.kernel(List.of("[105.0] warning fixture", "[110.0] error fixture", "unknown clock"), 120);
    systemPull.journal(List.of("1772893358.25 host service: warning fixture"), "fixture-cursor", "fixture-boot");
    var systemRemote = new org.triplehelix.wpilogmcp.capture.pull.FakeRobot();
    systemRemote.files.put("/home/lvuser/hs_err_pid42.log", "synthetic crash report\n".getBytes(StandardCharsets.UTF_8));
    systemRemote.files.put("/var/local/natinst/log/program.log", "synthetic program line\n".getBytes(StandardCharsets.UTF_8));
    systemPull.sources(java.util.Map.of("/home/lvuser/hs_err_pid42.log", "jvm_crash", "/var/local/natinst/log/program.log", "program"));
    var systemClock = new java.util.concurrent.atomic.AtomicLong();
    var systemTransfer = new org.triplehelix.wpilogmcp.sync.FileTransfer(systemRemote, systemPull, systemPull.manifest(), 1_000_000, systemClock::get, () -> true);
    for (int i = 0; i < 20; i++) {
      var result = systemTransfer.step(); systemClock.addAndGet(Math.max(1, result.waitUs()));
      if (result.status() == org.triplehelix.wpilogmcp.sync.FileTransfer.Status.IDLE) break;
      if (result.status() == org.triplehelix.wpilogmcp.sync.FileTransfer.Status.REFUSED) throw new IOException(result.detail());
    }
    return systemCapture;
  }

  @BeforeAll
  static void setUp() throws Exception {
    var dir = FixtureLogs.defaultDirectory();
    fixtures = FixtureLogs.generateAll(dir);
    // Listing-only identity examples exercise its evidence fields without changing any tool's
    // telemetry fixture or teaching the server a robot identity from a fingerprint.
    for (boolean identified : List.of(true, false)) {
      try (var writer = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(
          dir.resolve("identity-" + identified + ".wpilog"), "synthetic listing identity")) {
        int team = writer.start("/SystemStats/TeamNumber", "int64", "", 0);
        writer.append(team, 1_000_000, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeInt64(9998));
        if (identified) {
          int serial = writer.start("/SystemStats/SerialNumber", "string", "", 0);
          writer.append(serial, 1_000_000, "SYNTHETIC-LISTING".getBytes(StandardCharsets.UTF_8));
        }
      }
    }
    var logManager = LogManager.getInstance();
    logManager.unloadAllLogs();
    logManager.addAllowedDirectory(dir);
    var systemCapture = createSystemFixture(dir.resolve("system-store"));
    fixtures = java.util.stream.Stream.concat(fixtures.stream(), java.util.stream.Stream.of(
        new Fixture("system-session", systemCapture, "Synthetic pulled text and paired clocks", List.of("search_system_logs")))).toList();
    var identityStore = logManager.stores().store(FixtureLogs.freshDirectory(dir.resolve("listing-store")));
    var identityImport = identityStore.importPaths(new org.triplehelix.wpilogmcp.store.LogStore.Request(
        List.of(dir.resolve("identity-true.wpilog"), dir.resolve("identity-false.wpilog")), false, null), ignored -> {})
        .get(30, TimeUnit.SECONDS);
    if (identityImport.files().stream().anyMatch(f -> f.status().equals("refused"))) throw new IOException(identityImport.toString());
    // A verified but uncorrelated pull makes the listing's refusal explanation observable too.
    var remote = new org.triplehelix.wpilogmcp.capture.pull.FakeRobot();
    remote.device = org.triplehelix.wpilogmcp.capture.pull.FakeRobot.device("SYNTHETIC-LISTING", "SHA256:fixture");
    remote.files.put("/home/lvuser/logs/pulled.wpilog", Files.readAllBytes(dir.resolve("identity-true.wpilog")));
    var local = identityStore.pulls(remote.device, java.time.Clock.systemUTC());
    var transferClock = new java.util.concurrent.atomic.AtomicLong();
    var transfer = new org.triplehelix.wpilogmcp.sync.FileTransfer(remote, local, local.manifest(), 1_000_000,
        transferClock::get, () -> true);
    for (int step = 0; step < 16; step++) {
      var result = transfer.step(); transferClock.addAndGet(Math.max(1, result.waitUs()));
      if (result.status() == org.triplehelix.wpilogmcp.sync.FileTransfer.Status.VERIFIED) break;
      if (result.status() == org.triplehelix.wpilogmcp.sync.FileTransfer.Status.REFUSED) throw new IOException(result.detail());
    }
    if (transfer.manifest().files().stream().noneMatch(org.triplehelix.wpilogmcp.sync.PullManifest.Entry::verified)) throw new IOException("Listing fixture did not verify");
    // A real mirror makes its freshness fields observable to the same description checks.
    var mirrorRoot = FixtureLogs.freshDirectory(dir.resolve("listing-mirror"));
    var originHttp = new org.triplehelix.wpilogmcp.mcp.HttpTransport(new ToolRegistry(), 0);
    originHttp.setStoreDirectories(java.util.Set.of(identityStore.root())); originHttp.start();
    try {
      var mirror = logManager.stores().store(mirrorRoot);
      var config = new org.triplehelix.wpilogmcp.config.MirrorConfig("http://127.0.0.1:" + originHttp.getPort(),
          mirror.root(), 365_000, 20_000_000_000L, List.of(), List.of(), 30, 0);
      var copied = mirror.mirror(config, ignored -> {}).get(30, TimeUnit.SECONDS);
      if (!copied.state().equals("synchronized")) throw new IOException(copied.toString());
    } finally { originHttp.stop(); }
    exportDir = dir.resolve("export").toAbsolutePath();
    FixtureLogs.freshDirectory(exportDir);
    savedExportDir = ExportTools.getExportDirectory();
    ExportTools.setExportDirectory(exportDir.toString());
    // list_available_logs lists the fixture directory
    savedLogDirs = LogDirectory.getInstance().getLogDirectories();
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
    LogDirectory.getInstance().setLogDirectories(
        savedLogDirs.stream().map(Path::toString).toList());
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

    for (var tool : tools) if (!ToolArguments.takesPath(tool)) {
      for (var variant : ToolArguments.variants(tool, null, null, fixtures, exportDir)) {
        calls.add(evaluate(tool, "-", variant, false));
      }
    }
    for (var fixture : fixtures) {
      String path = fixture.path().toString();
      var log = logManager.getOrLoad(path);
      logManager.waitForRevLogSync(path, 30_000);
      for (var name : log.entries().keySet()) if (log.sampleCount(name) > 0) {
        assertFalse(log.values().get(name).isEmpty(), "harness decode: " + fixture.id() + " / " + name);
        break;
      }
      record Baseline(Tool tool, ToolArguments.Variant variant, Call call) {}
      var baseline = new ArrayList<Baseline>();
      for (var tool : tools) if (ToolArguments.takesPath(tool)) {
        for (var variant : ToolArguments.variants(tool, fixture, log, fixtures, exportDir)) {
          var call = evaluate(tool, fixture.id(), variant, log.truncated()); calls.add(call);
          if (call.result() != null && !RealLogConformanceTest.REVLOG_TOOLS.contains(tool.name())) {
            baseline.add(new Baseline(tool, variant, call));
          }
        }
      }
      // One disk scan per fixture. Freeze the decoded values before the manager retires its
      // mapping; every order is a view of that same data, including schemas and decode failures.
      var frozen = FrozenLogData.copy(log);
      try {
        for (long order : PermutedLogData.ORDERS) {
          logManager.testPutLog(path, new PermutedLogData(frozen, order));
          assertInstanceOf(PermutedLogData.class, logManager.getOrLoad(path));
          for (var expected : baseline) {
            var permuted = run(expected.tool(), expected.variant().args());
            var a = ConformanceChecks.normalize(expected.call().result());
            var b = ConformanceChecks.normalize(permuted);
            if (a == null || !a.equals(b)) calls.add(new Call(expected.tool().name(), fixture.id(),
                expected.variant().label(), permuted, List.of(Check.NONDETERMINISTIC)));
          }
        }
      } finally { logManager.unloadLog(path); }
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

  Call evaluate(Tool tool, String fixtureId, ToolArguments.Variant variant,
      boolean logTruncated) throws Exception {
    var result = run(tool, variant.args());
    Integer limit = variant.args().has("limit") ? variant.args().get("limit").getAsInt() : null;
    List<Check> failed = result == null ? List.of(Check.TIMEOUT)
        : ConformanceChecks.check(result, limit, ToolArguments.takesPath(tool), tool.name(),
            variant.args());
    // A result computed from a log that was not read to its end says so
    if (logTruncated && result != null && result.isJsonObject()
        && !ConformanceChecks.reportsTruncation(result.getAsJsonObject())) {
      failed = new ArrayList<>(failed);
      failed.add(Check.TRUNCATION_UNREPORTED);
    }
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
