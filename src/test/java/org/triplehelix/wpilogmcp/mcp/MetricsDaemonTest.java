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
    int port, gatewayPort;
    try (var reserve = new java.net.ServerSocket()) {
      reserve.bind(new InetSocketAddress("127.0.0.1", 0)); port = reserve.getLocalPort();
    }
    do {
      try (var reserve = new java.net.ServerSocket()) {
        reserve.bind(new InetSocketAddress("127.0.0.1", 0)); gatewayPort = reserve.getLocalPort();
      }
    } while (gatewayPort == port);
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000, List.of(Nt4Client.V40))) {
      gateway.start().get(10, TimeUnit.SECONDS);
      var config = temp.resolve("servers.yaml"); var output = temp.resolve("server.txt");
      Files.writeString(config, "servers:\n  metrics:\n    transport: http\n    port: " + port
          + "\n    metrics: {include: ['/keep/'], max_array_length: 1}\n    capture:\n      robot: {host: 127.0.0.1, port: " + gateway.port()
          + "}\n      store: " + new com.google.gson.Gson().toJson(temp.resolve("store").toString())
          + "\n      gateway: {port: " + gatewayPort + "}\n");
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
        assertEquals(0, parsed.value("wpilog_gateway_clients", Map.of()));
        var barrier = new java.util.concurrent.CompletableFuture<Long>();
        var controls = new java.util.concurrent.ConcurrentLinkedQueue<org.triplehelix.wpilogmcp.nt4.ControlMessage>();
        var downstream = client.newWebSocketBuilder().subprotocols(Nt4Client.V40)
            .buildAsync(URI.create("ws://127.0.0.1:" + gatewayPort + "/nt/metrics-test"), new java.net.http.WebSocket.Listener() {
              final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
              final StringBuilder text = new StringBuilder();
              @Override public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket socket, CharSequence part, boolean last) {
                text.append(part);
                if (last) { controls.addAll(org.triplehelix.wpilogmcp.nt4.ControlMessage.decode(text.toString())); text.setLength(0); }
                socket.request(1); return null;
              }
              @Override public java.util.concurrent.CompletionStage<?> onBinary(java.net.http.WebSocket socket, java.nio.ByteBuffer data, boolean last) {
                var part = new byte[data.remaining()]; data.get(part); bytes.writeBytes(part);
                if (last) {
                  barrier.complete((Long) org.triplehelix.wpilogmcp.nt4.ValueFrame.decode(bytes.toByteArray()).get(0).value()); bytes.reset();
                }
                socket.request(1); return null;
              }
            }).get(5, TimeUnit.SECONDS);
        try {
          org.triplehelix.wpilogmcp.harness.HarnessHttp.await("gateway metric", 5, () -> {
            var samples = PrometheusText.parse(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(1, samples.value("wpilog_nt_connected", Map.of()));
            return samples.value("wpilog_gateway_clients", Map.of()) == 1;
          });
          downstream.sendText("""
              [{"method":"publish","params":{"name":"/private","pubuid":1,"type":"int","properties":{}}},
               {"method":"publish","params":{"name":"/private2","pubuid":2,"type":"int","properties":{}}},
               {"method":"setproperties","params":{"name":"/keep/array","update":{"retained":true}}}]
              """, true).get(5, TimeUnit.SECONDS);
          downstream.sendBinary(java.nio.ByteBuffer.wrap(new org.triplehelix.wpilogmcp.nt4.ValueFrame(-1, 0, 2, 17L).encode()), true).get();
          assertEquals(17, barrier.get(5, TimeUnit.SECONDS));
          assertEquals(List.of(1, 2), controls.stream().filter(org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce.class::isInstance)
              .map(org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce.class::cast).map(org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce::pubuid).toList());
          // A second metrics request and connection close also exercise independent HTTP work.
          assertEquals(200, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        } finally { downstream.abort(); }
        var messages = Files.readAllLines(output);
        assertEquals(1, messages.stream().filter(line -> line.contains("NT4 gateway is read-only")).count());
        assertEquals(1, messages.stream().filter(line -> line.contains("NT4 gateway client connected: metrics-test")).count());
      } finally {
        child.destroy(); if (!child.waitFor(35, TimeUnit.SECONDS)) { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); }
      }
    }
  }
}
