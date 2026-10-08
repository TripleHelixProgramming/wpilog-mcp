/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.context.ProcFixture;
import org.triplehelix.wpilogmcp.capture.context.StatsCommand;
import org.triplehelix.wpilogmcp.capture.context.TailCommand;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.config.ProviderConfig;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.SignalResolver;

class ProviderCaptureTest {
  @TempDir Path temp;
  @Test void enabledRobotStatsAndTailReachFileLiveToolsManifestMetricsAndEveryLogTool() throws Exception {
    var phase = new AtomicInteger(); var tailLines = new LinkedBlockingQueue<String>();
    var values = new ArrayList<Map.Entry<String, com.google.gson.JsonElement>>(); Path capture;
    try (var rio = new FakeRoboRio(temp.resolve("remote"), "PROVIDER-FIXTURE", "synthetic comments")) {
      rio.script(StatsCommand.sample(PullConfig.DISABLED.directories()), out -> out.write(ProcFixture.sample(phase.get()).getBytes(StandardCharsets.UTF_8)));
      rio.script(TailCommand.open(ProviderConfig.CONSOLE, "program_console"), out -> {
        out.write((TailCommand.FOLLOW + "\n").getBytes(StandardCharsets.UTF_8)); out.flush();
        while (true) { String line = tailLines.take(); if (line.equals("STOP")) return; out.write((line + "\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }
      });
      var ssh = new PullConfig.Ssh("lvuser", "", null, false, rio.port());
      var pull = new PullConfig(false, PullConfig.DISABLED.directories(), 5_000_000, 1_000_000, ssh);
      var providers = new ProviderConfig(true, new ProviderConfig.Stats(true, 2_000_000, 100_000),
          List.of(new ProviderConfig.Tail(null, ssh, ProviderConfig.CONSOLE, "program_console")));
      try (var rig = new LiveToolRig(temp.resolve("store"), CapturePolicy.ALL, base -> new CaptureConfig(base.addresses(), base.store(),
          base.periodSeconds(), base.policy(), 0, base.maxFileBytes(), pull, 0, providers))) {
        rig.pump(() -> rig.service.live().timeEstimate().isPresent());
        rig.pump(() -> rig.service.live().providers().stream().anyMatch(p -> p.name().contains("program_console") && p.state().equals("following")));
        tailLines.add("buffered startup line");
        rig.pump(() -> rig.service.live().providers().stream().anyMatch(p -> p.linesPerSec() == 1));
        assertNull(rig.service.live().current(), "No announce means no capture session yet");
        // Provider workers are independent of the disabled-only pull gate.
        rig.announce("/FMSInfo/FMSControlData", "int"); rig.value("/FMSInfo/FMSControlData", 10_000_000, 2, 1L);
        rig.pump(() -> rig.service.live().providers().stream().anyMatch(p -> p.name().equals("roboRIO") && p.lastRoundTripMs() != null));
        if (!rig.service.live().latest().containsKey("/Daemon/roboRIO/uptime_sec")) {
          rig.robot.addAndGet(2_000_000); rig.loop.advance(2_000_000);
        }
        rig.pump(() -> rig.service.live().latest().containsKey("/Daemon/roboRIO/uptime_sec"));
        rig.manager.stores().awaitImports();
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var blocked = rig.manager.stores().store(rig.root).importPaths(new org.triplehelix.wpilogmcp.store.LogStore.Request(List.of(), false, null), progress -> {
          if (progress.phase().equals("starting")) {
            entered.countDown();
            try { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
          }
        });
        try {
          assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
          phase.set(1); rig.robot.addAndGet(2_000_000); rig.loop.advance(2_000_000);
          rig.pump(() -> rig.service.live().latest().containsKey("/Daemon/roboRIO/cpu_busy_fraction"));
          rig.flush(); assertFalse(blocked.isDone(), "Provider writes and flushes do not join store work");
        } finally { release.countDown(); blocked.get(10, java.util.concurrent.TimeUnit.SECONDS); }
        tailLines.add("synthetic program line");
        String console = "/Daemon/Tail/127.0.0.1/program_console";
        rig.pump(() -> rig.service.live().latest().containsKey(console) && rig.service.live().latest().get(console).value().equals("synthetic program line")); rig.flush();
        var latest = rig.call("get_latest_values", "{entries:['/Daemon/roboRIO/cpu_busy_fraction','/Daemon/roboRIO/mem_available_bytes','" + console + "']}");
        assertEquals("ok", latest.get("status").getAsString());
        var rows = latest.getAsJsonArray("values");
        assertEquals(.6, rows.get(0).getAsJsonObject().get("value").getAsDouble());
        assertEquals(512_000, rows.get(1).getAsJsonObject().get("value").getAsLong());
        assertEquals("ssh", rows.get(0).getAsJsonObject().get("source").getAsString());
        assertEquals("tail", rows.get(2).getAsJsonObject().get("source").getAsString());
        assertEquals("synthetic program line", rows.get(2).getAsJsonObject().get("value").getAsString());
        assertEquals(List.of(), ConformanceChecks.check(latest, null, false, "get_latest_values", new JsonObject()));
        var quality = rig.call("get_statistics", "{path:" + new com.google.gson.Gson().toJson(rig.service.live().current().path().toString())
            + ",name:'/Daemon/roboRIO/cpu_busy_fraction'}");
        assertEquals("periodic", quality.getAsJsonObject("data_quality").get("sampling").getAsString());
        var session = rig.session(); assertEquals(2, session.getAsJsonArray("providers").size());
        var stats = session.getAsJsonArray("providers").get(0).getAsJsonObject();
        assertTrue(stats.keySet().containsAll(java.util.Set.of("name", "state", "reason", "period_sec", "last_round_trip_ms", "robot_cpu_sec",
            "lines_per_sec", "dropped_lines", "dropped_before_sync", "records", "bytes", "sample_bytes")));
        assertEquals(1.2, stats.get("robot_cpu_sec").getAsDouble()); assertTrue(stats.get("bytes").getAsLong() > 0);
        assertEquals(1, rio.authentications.get(), "Identity, samples and follow share the one SSH connection");
        capture = Path.of(session.get("path").getAsString());
        String metrics = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
            java.net.URI.create("http://127.0.0.1:" + rig.transport.getPort() + "/metrics")).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(metrics.contains("nt_value{topic=\"/Daemon/roboRIO/cpu_busy_fraction\"} 0.6\n"));
        assertTrue(metrics.contains("wpilog_provider_robot_cpu_seconds{provider=\"roboRIO\"} 1.2\n"));
        assertFalse(metrics.contains("nt_value{topic=\"" + console));
        var oldExport = ExportTools.getExportDirectory(); var export = Files.createDirectories(temp.resolve("export"));
        ExportTools.setExportDirectory(export.toString());
        try (var use = rig.manager.acquire(capture.toString())) {
          var log = use.log();
          assertEquals(List.of(console), SignalResolver.followedText(log).get("program_console"));
          assertEquals(.6, ((Number) log.values().get("/Daemon/roboRIO/cpu_busy_fraction").get(0).value()).doubleValue());
          assertTrue(log.entries().get(console).metadata().contains("\"timestamp\":\"received\""));
          assertTrue(log.entries().get(console).metadata().contains("buffered_before_session"));
          assertEquals("buffered startup line", log.values().get(console).get(0).value());
          assertEquals(10.0, log.values().get(console).get(0).timestamp());
          var fixture = new FixtureLogs.Fixture("providers", capture, "synthetic SSH context", List.of());
          for (String name : rig.tools.getToolNames().stream().sorted().toList()) {
            var tool = rig.tools.getTool(name); if (!ToolArguments.takesPath(tool)) continue;
            for (var variant : ToolArguments.variants(tool, fixture, log, List.of(fixture), export)) {
              var result = tool.execute(variant.args());
              Integer limit = variant.args().has("limit") ? variant.args().get("limit").getAsInt() : null;
              assertEquals(List.of(), ConformanceChecks.check(result, limit, true, name, variant.args()), name);
              values.add(Map.entry(name + "\n" + variant.args(), normalized(result)));
            }
          }
        } finally { ExportTools.setExportDirectory(oldExport.toString()); }
        // End the writer through the same ordered callback used on a real connection loss.
        rig.gateway.dropClients().get(); rig.pump(() -> !rig.service.live().connected());
        rig.pump(() -> rig.service.live().current() != null && !rig.service.live().current().open());
        // A catalog refresh can overtake a coalesced close update that has not dispatched yet.
        // Join the writer's actual final-manifest barrier before resolving its promoted path.
        var closed = java.util.concurrent.CompletableFuture.runAsync(rig.service::close);
        rig.pump(closed::isDone); closed.get(10, java.util.concurrent.TimeUnit.SECONDS);
        String beforeMove = capture.toString(); capture = rig.service.live().resolve(capture);
        assertTrue(capture.startsWith(rig.root.resolve("robots").resolve("PROVIDER-FIXTURE")),
            "The close barrier includes promotion to the device serial");
        var manifest = com.google.gson.JsonParser.parseString(Files.readString(capture.getParent().resolve("session.json"))).getAsJsonObject();
        var summary = manifest.getAsJsonObject("capture_stats");
        assertEquals(2, summary.getAsJsonArray("providers").size());
        assertEquals(102, summary.getAsJsonObject("kernel_clock").get("uptime_sec").getAsDouble());
        var independent = IndependentLog.read(capture, java.util.Set.of("/Daemon/roboRIO/cpu_busy_fraction", console));
        try (var fresh = new LazyParsedLog(capture.toString(), new edu.wpi.first.util.datalog.DataLogReader(java.nio.ByteBuffer.wrap(Files.readAllBytes(capture))), 1 << 20)) {
          assertEquals(independent.series.size(), fresh.entries().size());
          assertEquals(.6, ((Number) fresh.values().get("/Daemon/roboRIO/cpu_busy_fraction").get(0).value()).doubleValue());
          assertEquals("synthetic program line", fresh.values().get(console).get(1).value());
        }
        var difference = DifferentialChecks.compare(rig.tools, capture);
        assertEquals(List.of(), difference.findings());
        rig.manager.unloadLog(capture.toString()); ExportTools.setExportDirectory(export.toString());
        try {
          for (var before : values) {
            String[] parts = before.getKey().split("\n", 2);
            assertEquals(com.google.gson.JsonParser.parseString(before.getValue().toString().replace(
                new com.google.gson.Gson().toJson(beforeMove), new com.google.gson.Gson().toJson(capture.toString()))), normalized(rig.tools.getTool(parts[0]).execute(
                com.google.gson.JsonParser.parseString(parts[1]).getAsJsonObject())), parts[0]);
          }
        } finally { ExportTools.setExportDirectory(oldExport.toString()); }
      } finally { tailLines.add("STOP"); }
    }
  }
  private static com.google.gson.JsonElement normalized(com.google.gson.JsonElement value) {
    var copy = ConformanceChecks.normalize(value);
    if (copy.isJsonObject() && copy.getAsJsonObject().has("inputs")) {
      copy.getAsJsonObject().getAsJsonObject("inputs").remove("session_time_range");
      copy.getAsJsonObject().getAsJsonObject("inputs").remove("session_time_ranges");
    }
    return copy;
  }
}
