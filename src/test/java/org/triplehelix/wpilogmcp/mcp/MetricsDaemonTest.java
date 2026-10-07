/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.PrometheusText;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;

class MetricsDaemonTest {
  @TempDir Path temp;
  @Test void packagedDaemonAppliesMetricsScopeToItsCaptureSnapshot() throws Exception {
    int port;
    try (var reserve = new java.net.ServerSocket()) {
      reserve.bind(new InetSocketAddress("127.0.0.1", 0)); port = reserve.getLocalPort();
    }
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000, List.of(Nt4Client.V40))) {
      gateway.start().get(10, TimeUnit.SECONDS);
      var config = temp.resolve("servers.yaml"); var output = temp.resolve("server.txt");
      Files.writeString(config, "servers:\n  metrics:\n    transport: http\n    port: " + port
          + "\n    metrics: {include: ['/keep/'], max_array_length: 1}\n    capture:\n      robot: {host: 127.0.0.1, port: " + gateway.port()
          + "}\n      store: " + new com.google.gson.Gson().toJson(temp.resolve("store").toString()) + "\n");
      var builder = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-Xmx256m",
          "-Duser.home=" + temp, "-jar", System.getProperty("install.testJar"), "--internal-daemon", "metrics", "--config", config.toString())
          .redirectErrorStream(true).redirectOutput(output.toFile());
      builder.environment().put("WPILOG_DISK_CACHE_DIR", temp.resolve("cache").toString());
      var child = builder.start();
      try {
        var properties = new com.google.gson.JsonObject();
        gateway.announce("/keep/array", "double[]", properties).get();
        gateway.value("/keep/array", 10_000_000, 17, List.of(3.0, 5.0)).get();
        gateway.announce("/omit", "int", properties).get(); gateway.value("/omit", 10_000_000, 2, 7L).get();
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).timeout(Duration.ofSeconds(2)).build();
        var ready = new java.util.concurrent.atomic.AtomicReference<PrometheusText.Parsed>();
        org.triplehelix.wpilogmcp.harness.HarnessHttp.await("packaged capture metrics", 10, () -> {
          assertTrue(child.isAlive(), Files.readString(output));
          var response = client.send(request, HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode()); var parsed = PrometheusText.parse(response.body());
          ready.set(parsed); return parsed.samples().containsKey(new PrometheusText.Key("nt_value", Map.of("topic", "/keep/array", "index", "0")));
        });
        var parsed = ready.get();
        assertEquals(3, parsed.value("nt_value", Map.of("topic", "/keep/array", "index", "0")));
        assertEquals(1, parsed.samples().keySet().stream().filter(k -> k.name().equals("nt_value")).count());
        assertEquals(1, parsed.value("wpilog_nt_connected", Map.of()));
      } finally {
        child.destroy(); if (!child.waitFor(35, TimeUnit.SECONDS)) { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); }
      }
    }
  }
}
