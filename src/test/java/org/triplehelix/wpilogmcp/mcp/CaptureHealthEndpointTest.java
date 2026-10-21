/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Public process health must expose bounded recorder facts without joining a capture owner. */
class CaptureHealthEndpointTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
  @Test void healthAlwaysNamesItsBoundedCapturePublication() throws Exception {
    var transport = new HttpTransport(new ToolRegistry(), 0, "127.0.0.1", null, null);
    try {
      transport.start();
      var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + transport.getPort() + "/health"))
          .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var health = JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals("ok", health.get("status").getAsString());
      assertTrue(health.has("capture"), "/health must publish capture progress even when capture is absent");
      var capture = health.getAsJsonObject("capture");
      assertEquals(0, capture.get("configured_count").getAsInt());
      assertEquals(0, capture.getAsJsonArray("captures").size());
      assertTrue(response.body().length() < 4096, "Idle health remains a short bounded reply");
    } finally { transport.stop(); }
  }

  @Test void aStoppedCaptureRemainsHealthyWithoutExposingItsStoreOrRobot() throws Exception {
    var logs = org.triplehelix.wpilogmcp.log.LogManager.getInstance();
    var allowed = logs.getAllowedDirectories();
    directory = directory.toRealPath(); logs.addAllowedDirectory(directory);
    var config = new org.triplehelix.wpilogmcp.config.CaptureConfig(java.util.List.of(
        org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("synthetic-private-robot.invalid", 9, "private-client")),
        directory.resolve("private-store"), 0.02, org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, 0);
    var transport = new HttpTransport(new ToolRegistry(), 0, "127.0.0.1", null, null);
    try (var service = new org.triplehelix.wpilogmcp.capture.CaptureService(config, logs)) {
      // Constructing a service publishes its state; do not connect to an NT4 peer.
      transport.useConfiguredCapture(service); transport.start();
      var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + transport.getPort() + "/health"))
          .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var health = JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals("ok", health.get("status").getAsString(), "A stopped capture is not a dead process");
      assertTrue(health.has("capture"), "/health must report a configured capture before its first write");
      var capture = health.getAsJsonObject("capture");
      assertEquals(1, capture.get("configured_count").getAsInt());
      assertEquals(1, capture.getAsJsonArray("captures").size());
      var row = capture.getAsJsonArray("captures").get(0).getAsJsonObject();
      assertFalse(row.has("capture_id"), "The public row needs no recorder identifier");
      assertEquals("stopped", row.get("state").getAsString());
      assertEquals("not_started", row.get("reason").getAsString());
      assertTrue(row.get("last_write_age_ms").isJsonNull());
      assertEquals(0, row.get("longest_listener_loop_stall_us").getAsLong());
      assertTrue(row.get("longest_store_queue_turn_us").getAsLong() >= 0);
      for (String privateText : java.util.List.of(directory.toString(), "private-store", "synthetic-private-robot", "private-client"))
        assertFalse(response.body().contains(privateText), "Public health leaked a configured identity or path");
      assertTrue(response.body().length() < 4096, "Capture health remains a short bounded reply");
    } finally {
      transport.stop(); logs.release(directory); logs.clearAllowedDirectories(); allowed.forEach(logs::addAllowedDirectory);
    }
  }

  @Test void startupFailureIsReportedWithoutTurningProcessHealthIntoAnError() throws Exception {
    var logs = org.triplehelix.wpilogmcp.log.LogManager.getInstance();
    var allowed = logs.getAllowedDirectories();
    directory = directory.toRealPath(); logs.addAllowedDirectory(directory);
    var config = new org.triplehelix.wpilogmcp.config.CaptureConfig(java.util.List.of(
        org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("private-startup-robot.invalid", 9, "private-client")),
        directory.resolve("private-failed-store"), 0.02, org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, 0);
    var transport = new HttpTransport(new ToolRegistry(), 0, "127.0.0.1", null, null);
    try (var service = new org.triplehelix.wpilogmcp.capture.CaptureService(config, logs)) {
      transport.useConfiguredCapture(service); transport.start();
      logs.stores().store(config.store()).close();
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> service.start().get(5, java.util.concurrent.TimeUnit.SECONDS));
      var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + transport.getPort() + "/health"))
          .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var health = JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals("ok", health.get("status").getAsString());
      assertTrue(health.has("capture"), "Failed startup is visible as capture state, not missing health");
      var row = health.getAsJsonObject("capture").getAsJsonArray("captures").get(0).getAsJsonObject();
      assertEquals("stopped", row.get("state").getAsString());
      assertEquals("startup_failed", row.get("reason").getAsString());
      assertTrue(row.get("last_write_age_ms").isJsonNull());
      assertFalse(response.body().contains("private-startup-robot"));
      assertFalse(response.body().contains("private-failed-store"));
    } finally {
      transport.stop(); logs.release(directory); logs.clearAllowedDirectories(); allowed.forEach(logs::addAllowedDirectory);
    }
  }

}
