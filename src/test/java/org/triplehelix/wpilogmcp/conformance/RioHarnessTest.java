/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.triplehelix.wpilogmcp.conformance.ShopHarnessTest.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.harness.RioContainer;
import org.triplehelix.wpilogmcp.harness.PhotonBackend;
import org.triplehelix.wpilogmcp.store.StoreJson;

/** The NI-like assumptions meet a real Linux shell, OpenSSH, procfs and management agent. */
@Tag("shop-harness")
class RioHarnessTest {
  @Test void containerProvidersPullAndFactsFollowTheTimeline() throws Exception {
    String image = System.getProperty("harness.rio");
    assumeTrue(image != null, "Run harness/rio/run with Docker on Linux for the image harness");
    long started = System.nanoTime();
    var timelinePath = Path.of(System.getProperty("harness.timeline"));
    var timeline = JsonParser.parseString(Files.readString(timelinePath)).getAsJsonObject();
    var run = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("harness.output"))), "rio-");
    Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxrwxrwx"));
    Files.copy(timelinePath, run.resolve("timeline.json"));
    System.out.println("Container harness artifacts: " + run);
    String photonJar = System.getProperty("harness.photon");
    int httpPort = port(), ntPort = photonJar == null ? port() : 5810, sshPort = port(), jmxPort = port();
    assertEquals(4, java.util.Set.of(httpPort, ntPort, sshPort, jmxPort).size());
    var home = Files.createDirectory(run.resolve("home")); var store = run.resolve("store");
    String javaExe = ProcessHandle.current().info().command().orElseThrow();
    Process server = null, robot = null, facts = null;
    try (var photon = photonJar == null ? null : new PhotonBackend(run, Path.of(photonJar));
        var rio = new RioContainer(image, run, timeline.get("serial_number").getAsString(),
        timeline.get("comments").getAsString(), sshPort, ntPort, jmxPort)) {
      var config = run.resolve("servers.yaml");
      Files.writeString(config, "servers:\n  harness:\n    transport: http\n    port: " + httpPort
          + "\n    context:\n      jvm: {port: " + jmxPort + ", period_sec: 1}"
          + (photon == null ? "" : "\n      photonvision: [127.0.0.1:5800]")
          + "\n    capture:\n      robot: {host: 127.0.0.1, port: " + ntPort + "}\n      store: " + StoreJson.JSON.toJson(store.toString())
          + "\n      period_sec: 0.01\n      stats: {enabled: true, period_sec: 2, budget_ms: 100}"
          + "\n      tail: [{path: /var/local/natinst/log/FRC_UserProgram.log, role: program_console}]"
          + "\n      pull:\n        enabled: true\n        settle_sec: " + timeline.get("settle_sec")
          + "\n        ssh: {port: " + sshPort + "}"
          + "\n        system: {enabled: true, kernel: dmesg, journal: false}\n");
      server = launch(List.of(javaExe, "-Xmx512m", "-Duser.home=" + home, "-jar", System.getProperty("harness.serverJar"),
          "run", "harness", "--config", config.toString()), run, run.resolve("server.log"),
          Map.of("WPILOG_DISK_CACHE_DIR", run.resolve("cache").toString()));
      var http = new HarnessHttp(httpPort);
      HarnessHttp.await("container HTTP initialization", 30, () -> { http.initialize(); return true; });
      for (int index = 0; index < timeline.getAsJsonArray("boots").size(); index++) {
        var boot = timeline.getAsJsonArray("boots").get(index).getAsJsonObject();
        var control = Files.createDirectory(run.resolve("boot-" + index));
        Files.setPosixFilePermissions(control, PosixFilePermissions.fromString("rwxrwxrwx"));
        robot = rio.robot(index, control.resolve("docker-exec.log")); var activeRobot = robot;
        HarnessHttp.await("container robot ready", 30, () -> {
          if (!activeRobot.isAlive()) fail(rio.console()); return Files.exists(control.resolve("ready"));
        });
        int expected = index + 1;
        HarnessHttp.await("container capture subscribed", 30, () -> {
          if (!activeRobot.isAlive()) fail(rio.console());
          var listing = http.call("list_available_logs", new JsonObject());
          return listing.has("logs") && listing.getAsJsonArray("logs").asList().stream().map(e -> e.getAsJsonObject())
              .filter(e -> e.get("path").getAsString().endsWith("capture.wpilog")).count() >= expected;
        });
        Files.writeString(control.resolve("go"), "go\n");
        HarnessHttp.await("SSH stats and tail, and JMX sampling", 30, () -> {
          var live = http.call("list_sessions", new JsonObject());
          Files.writeString(control.resolve("providers.json"), live.toString());
          return RioExpectations.providersFollowing(live);
        });
        if (photon != null) photon.verifyLive(http, httpPort, control);
        // Inspect while the deployed process exists; every command is the shipped collector's.
        // The explicit host keeps this independent CLI's pins in its own facts store. --server
        // would contend with the recorder's store lock, which intentionally refuses a second owner.
        facts = launch(List.of(javaExe, "-Duser.home=" + home, "-jar", System.getProperty("harness.serverJar"),
            "robot-facts", "127.0.0.1", "--port", Integer.toString(sshPort), "--out", control.resolve("facts.md").toString()),
            run, control.resolve("facts.log"), Map.of("WPILOG_DISK_CACHE_DIR", run.resolve("facts-cache").toString()));
        assertTrue(robot.waitFor(boot.get("end_us").getAsLong() / 1_000_000 + 30, TimeUnit.SECONDS), "Container timeline did not end");
        assertEquals(boot.get("reboot").getAsBoolean() ? 75 : 0, robot.exitValue(), rio.console());
        assertTrue(Files.exists(control.resolve("done")));
        assertTrue(facts.waitFor(30, TimeUnit.SECONDS), "robot-facts did not finish");
        assertEquals(0, facts.exitValue(), Files.readString(control.resolve("facts.log")));
        RioExpectations.facts(Files.readString(control.resolve("facts.md")), rio.modules());
      }
      HarnessHttp.await("container captures close", 30, () -> {
        try (var paths = Files.walk(store)) {
          var sessions = paths.filter(p -> p.getFileName().toString().equals("session.json")).toList();
          return sessions.size() >= timeline.getAsJsonArray("boots").size() && sessions.stream().allMatch(p -> {
            try { return JsonParser.parseString(Files.readString(p)).getAsJsonObject().get("open_capture").isJsonNull(); }
            catch (Exception e) { return false; }
          });
        }
      });
      var listing = http.call("list_available_logs", new JsonObject());
      Files.writeString(run.resolve("listing.json"), listing.toString());
      HarnessExpectations.verify(timeline, run, store, http, listing, null, false);
      RioExpectations.captures(timeline, listing, http);
      if (photon != null) {
        photon.check();
        for (var row : listing.getAsJsonArray("logs")) {
          var path = Path.of(row.getAsJsonObject().get("path").getAsString());
          if (!path.getFileName().toString().equals("capture.wpilog")) continue;
          var log = IndependentLog.read(path, java.util.Set.of(), java.util.Set.of(PhotonBackend.SETTINGS));
          assertNull(log.stopped); var settings = log.series.get(PhotonBackend.SETTINGS);
          assertNotNull(settings); assertEquals("json", settings.type); assertTrue(settings.records > 0);
          for (var value : settings.payloads) {
            var data = JsonParser.parseString(new String(value, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(PhotonBackend.CAMERA, data.get("camera").getAsString());
            assertEquals("v2026.3.4", data.get("software_version").getAsString());
          }
        }
      }
    } finally {
      for (var process : new Process[] {facts, robot}) if (process != null && process.isAlive()) {
        process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS);
      }
      if (server != null) { server.destroy(); if (!server.waitFor(35, TimeUnit.SECONDS)) { server.destroyForcibly(); server.waitFor(10, TimeUnit.SECONDS); } }
      Files.writeString(run.resolve("timing.json"), "{\"seconds\":" + (System.nanoTime() - started) / 1e9 + "}\n");
    }
  }
}
