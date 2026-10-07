/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.fixtures.PrometheusText;
import org.triplehelix.wpilogmcp.fixtures.WpiStructs;
import org.triplehelix.wpilogmcp.store.LogStore;

class LiveMetricsTest {
  @TempDir Path directory;
  static PrometheusText.Parsed scrape(LiveToolRig rig) throws Exception {
    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
        + rig.transport.getPort() + "/metrics")).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode()); return PrometheusText.parse(response.body());
  }
  private static void schema(LiveToolRig rig, WpiStructs.Type type) throws Exception {
    for (var child : type.nested()) schema(rig, child);
    String name = "/.schema/struct:" + type.name();
    if (rig.service.live().topics().containsKey(name)) return;
    rig.announce(name, "structschema"); rig.value(name, 10_000_000, 5, type.schema().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  @Test void httpScrapeReadsKnownFunctionsSchemasAndTheSameRecorderCostsAsLiveTools() throws Exception {
    try (var rig = new LiveToolRig(directory, CapturePolicy.ALL)) {
      schema(rig, WpiStructs.POSE2D); schema(rig, WpiStructs.SWERVE_MODULE_STATE);
      rig.announce("/sine", "double"); rig.announce("/counter", "int"); rig.announce("/toggle", "boolean");
      rig.announce("/text", "string"); rig.announce("/array", "double[]");
      rig.announce("/pose", "struct:Pose2d"); rig.announce("/modules", "struct:SwerveModuleState[]");
      for (int t = 0; t < 3; t++) {
        long timestamp = 10_000_000 + t * 20_000;
        rig.value("/sine", timestamp, 1, Math.sin(t)); rig.value("/counter", timestamp, 2, (long) t);
        rig.value("/toggle", timestamp, 0, t % 2 == 0); rig.value("/text", timestamp, 4, "synthetic phase " + t);
        rig.value("/array", timestamp, 17, List.of((double) t, t + 1.0, t + 2.0));
        rig.value("/pose", timestamp, 5, WpiStructs.pose2d(t, 2 * t, t / 4.0));
        byte[] modules = java.nio.ByteBuffer.allocate(32).put(WpiStructs.swerveModuleState(t, t / 4.0))
            .put(WpiStructs.swerveModuleState(2 * t, -t / 4.0)).array();
        rig.value("/modules", timestamp, 5, modules);
      }
      rig.flush(); rig.calendar.addAndGet(86_400_000);
      var scrape = scrape(rig);
      assertEquals(Math.sin(2), scrape.value("nt_value", Map.of("topic", "/sine")));
      assertEquals(2, scrape.value("nt_value", Map.of("topic", "/counter")));
      assertEquals(1, scrape.value("nt_value", Map.of("topic", "/toggle")));
      for (int i = 0; i < 3; i++) assertEquals(2 + i, scrape.value("nt_value", Map.of("topic", "/array", "index", Integer.toString(i))));
      assertEquals(2, scrape.value("nt_value", Map.of("topic", "/pose", "field", "translation.x")));
      assertEquals(4, scrape.value("nt_value", Map.of("topic", "/pose", "field", "translation.y")));
      assertEquals(.5, scrape.value("nt_value", Map.of("topic", "/pose", "field", "rotation.value")));
      for (int i = 0; i < 2; i++) {
        assertEquals(i == 0 ? 2 : 4, scrape.value("nt_value", Map.of("topic", "/modules", "index", Integer.toString(i), "field", "speed")));
        assertEquals(i == 0 ? .5 : -.5, scrape.value("nt_value", Map.of("topic", "/modules", "index", Integer.toString(i), "field", "angle.value")));
      }
      assertEquals(13, scrape.samples().keySet().stream().filter(k -> k.name().equals("nt_value")).count());
      for (String topic : List.of("/sine", "/counter", "/toggle", "/array", "/pose", "/modules")) {
        assertEquals(.210, scrape.value("nt_age_seconds", Map.of("topic", topic)), 1e-12);
      }
      assertFalse(scrape.samples().keySet().stream().anyMatch(k -> "/text".equals(k.labels().get("topic"))));
      assertEquals(1, scrape.value("wpilog_nt_connected", Map.of()));
      assertEquals(1, scrape.value("wpilog_nt_address_info", Map.of("address", "127.0.0.1")));
      var row = rig.session(); var labels = Map.of("session_started_at", row.get("started_at").getAsString());
      assertEquals(row.get("records").getAsLong(), scrape.value("wpilog_capture_records_total", labels));
      assertEquals(row.get("bytes").getAsLong(), scrape.value("wpilog_capture_bytes_total", labels));
      assertEquals(row.get("topic_count").getAsInt(), scrape.value("wpilog_capture_topics", labels));
      rig.gateway.unannounce("/counter").get(); rig.pump(() -> !rig.service.live().topics().containsKey("/counter"));
      assertFalse(scrape(rig).samples().keySet().stream().anyMatch(k -> "/counter".equals(k.labels().get("topic"))));
      rig.gateway.dropClients().get(); rig.pump(() -> !rig.service.live().connected());
      assertEquals(0, scrape(rig).value("wpilog_nt_connected", Map.of()));
    }
  }
  @Test void scrapeDoesNotJoinABlockedStoreOrTheNt4Loop() throws Exception {
    try (var rig = new LiveToolRig(directory, CapturePolicy.ALL)) {
      rig.announce("/x", "int"); rig.value("/x", 10_000_000, 2, 7L); rig.flush();
      rig.manager.stores().awaitImports();
      var queued = new CountDownLatch(1); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
      var job = rig.manager.stores().store(rig.root).importPaths(new LogStore.Request(List.of(), false, null), progress -> {
        if (progress.phase().equals("starting")) { queued.countDown(); await(release); }
      });
      rig.loop.execute(() -> { entered.countDown(); await(release); });
      var blockedLoop = java.util.concurrent.CompletableFuture.runAsync(rig.loop::drain);
      try {
        assertTrue(queued.await(10, TimeUnit.SECONDS)); assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertEquals(7, scrape(rig).value("nt_value", Map.of("topic", "/x")));
        assertFalse(job.isDone()); assertFalse(blockedLoop.isDone());
      } finally { release.countDown(); job.get(10, TimeUnit.SECONDS); blockedLoop.get(10, TimeUnit.SECONDS); }
    }
  }
  private static void await(CountDownLatch release) {
    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
  }
}
