/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.store.StoreJson;

/** Opt-in, external WPILib robot + packaged server; expected telemetry comes only from the script. */
@Tag("shop-harness")
class ShopHarnessTest {
  private static int port() throws Exception {
    try (var socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", 0)); return socket.getLocalPort(); }
  }
  private static Process launch(List<String> command, Path directory, Path output, java.util.Map<String, String> env) throws Exception {
    var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
    for (String key : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "TBA_API_KEY", "WPILOG_DIR", "WPILOG_HTTP_BIND", "WPILOG_HTTP_PATH", "HALSIM_EXTENSIONS")) builder.environment().remove(key);
    builder.environment().putAll(env); return builder.start();
  }

  @Test void timelineSurvivesCapturePullRenameRebootAndHttpTools() throws Exception {
    var timelinePath = Path.of(System.getProperty("harness.timeline"));
    var timeline = JsonParser.parseString(Files.readString(timelinePath)).getAsJsonObject();
    var output = Files.createDirectories(Path.of(System.getProperty("harness.output")));
    var run = Files.createTempDirectory(output, timeline.get("name").getAsString() + "-");
    Files.copy(timelinePath, run.resolve("timeline.json"));
    System.out.println("Shop harness artifacts: " + run);
    var store = run.resolve("store"); var home = Files.createDirectory(run.resolve("home"));
    String javaExe = ProcessHandle.current().info().command().orElseThrow();
    var robots = new ArrayList<Process>(); Process server = null;
    try (var rio = new FakeRoboRio(run.resolve("rio"), timeline.get("serial_number").getAsString(), timeline.get("comments").getAsString())) {
      int httpPort = port(), ntPort; do { ntPort = port(); } while (ntPort == httpPort);
      int gatewayPort; do { gatewayPort = port(); } while (gatewayPort == httpPort || gatewayPort == ntPort);
      var probes = Files.createDirectory(run.resolve("gateway-probes"));
      var config = run.resolve("servers.yaml");
      Files.writeString(config, "servers:\n  harness:\n    transport: http\n    port: " + httpPort
          + "\n    logdir: [" + StoreJson.JSON.toJson(probes.toString()) + "]"
          + "\n    capture:\n      robot: {host: 127.0.0.1, port: " + ntPort + "}\n      store: " + StoreJson.JSON.toJson(store.toString())
          + "\n      gateway: {port: " + gatewayPort + "}"
          + "\n      period_sec: 0.01\n      pull:\n        enabled: true\n        rate_bytes: 65536\n        settle_sec: " + timeline.get("settle_sec")
          + "\n        ssh: {port: " + rio.port() + "}\n");
      server = launch(List.of(javaExe, "-Xmx512m", "-Duser.home=" + home, "-jar", System.getProperty("harness.serverJar"),
          "--internal-daemon", "harness", "--config", config.toString()), run, run.resolve("server.log"),
          java.util.Map.of("WPILOG_DISK_CACHE_DIR", run.resolve("cache").toString()));
      var http = new HarnessHttp(httpPort);
      HarnessHttp.await("HTTP initialization", 30, () -> { http.initialize(); return true; });
      for (int index = 0; index < timeline.getAsJsonArray("boots").size(); index++) {
        var control = Files.createDirectory(run.resolve("boot-" + index));
        var boot = timeline.getAsJsonArray("boots").get(index).getAsJsonObject();
        var robot = launch(List.of(javaExe, "-Xmx256m", "-Djava.library.path=" + System.getProperty("harness.natives"),
            "-jar", System.getProperty("harness.robotJar"), timelinePath.toString(), Integer.toString(index),
            rio.logs().toString(), control.toString(), Integer.toString(ntPort), Integer.toString(gatewayPort),
            probes.resolve("boot-" + index + ".wpilog").toString()), run, control.resolve("robot.log"),
            java.util.Map.of("serialnum", timeline.get("serial_number").getAsString(),
                "LD_LIBRARY_PATH", System.getProperty("harness.natives"), "DYLD_LIBRARY_PATH", System.getProperty("harness.natives")));
        robots.add(robot);
        HarnessHttp.await("robot ready", 30, () -> {
          assertTrue(robot.isAlive(), "robot stopped: " + Files.readString(control.resolve("robot.log")));
          return Files.exists(control.resolve("ready"));
        });
        int expected = index + 1;
        HarnessHttp.await("capture subscribed for boot " + index, 30, () -> {
          var listing = http.call("list_available_logs", new JsonObject());
          Files.writeString(control.resolve("subscribing-listing.json"), listing.toString());
          if (!listing.has("logs")) return false;
          return listing.getAsJsonArray("logs").asList().stream().map(e -> e.getAsJsonObject())
              .filter(e -> e.get("path").getAsString().endsWith("capture.wpilog")).count() >= expected;
        });
        HarnessHttp.await("ntcore subscribed through gateway", 30, () -> {
          assertTrue(robot.isAlive(), "robot stopped: " + Files.readString(control.resolve("robot.log")));
          return Files.exists(control.resolve("gateway-ready"));
        });
        Files.writeString(control.resolve("go"), "go\n");
        long endSeconds = Math.floorDiv(boot.get("end_us").getAsLong(), 1_000_000);
        assertTrue(robot.waitFor(Math.addExact(endSeconds, 30), TimeUnit.SECONDS), "timeline did not end");
        String robotOutput = Files.readString(control.resolve("robot.log"));
        assertEquals(boot.get("reboot").getAsBoolean() ? 75 : 0, robot.exitValue(), robotOutput);
        var listeners = robotOutput.lines().filter(line -> line.contains("Listening on NT3 port")).toList();
        assertFalse(listeners.isEmpty(), robotOutput);
        for (String line : listeners) assertTrue(line.contains("NT3 port 0, NT4 port " + ntPort),
            "The simulation must use only its selected port: " + line);
        assertTrue(Files.exists(control.resolve("done")));
      }
      HarnessHttp.await("capture session closes", 15, () -> {
        try (var paths = Files.walk(store)) {
          var manifests = paths.filter(p -> p.getFileName().toString().equals("session.json")).toList();
          return !manifests.isEmpty() && manifests.stream().allMatch(p -> {
            try { return JsonParser.parseString(Files.readString(p)).getAsJsonObject().get("open_capture").isJsonNull(); }
            catch (Exception e) { return false; }
          });
        }
      });
      Files.writeString(run.resolve("ssh-reads.json"), StoreJson.JSON.toJson(rio.reads()));
      var listing = http.call("list_available_logs", new JsonObject()); Files.writeString(run.resolve("listing.json"), listing.toString());
      HarnessExpectations.verify(timeline, run, store, http, listing, rio.reads());
    } finally {
      for (var robot : robots) if (robot.isAlive()) { robot.destroyForcibly(); robot.waitFor(10, TimeUnit.SECONDS); }
      if (server != null) { server.destroy(); if (!server.waitFor(35, TimeUnit.SECONDS)) { server.destroyForcibly(); server.waitFor(10, TimeUnit.SECONDS); } }
    }
  }
}
