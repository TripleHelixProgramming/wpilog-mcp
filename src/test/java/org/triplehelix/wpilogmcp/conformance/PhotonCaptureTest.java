/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.context.PhotonFixture;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.config.ProviderConfig;

class PhotonCaptureTest {
  @TempDir Path temp;
  @Test void settingsReachCaptureLiveToolsManifestAndMetricsWithoutJoiningAStoreQueue() throws Exception {
    Path path;
    String front = "/Daemon/PhotonVision/front/Settings", rear = "/Daemon/PhotonVision/rear/Settings";
    try (var backend = new PhotonFixture(); var rig = new LiveToolRig(temp.resolve("store"), CapturePolicy.ALL, base ->
        new CaptureConfig(base.addresses(), base.store(), base.periodSeconds(), base.policy(), base.hotWindowUs(),
            base.maxFileBytes(), base.pull(), 0, new ProviderConfig(false, ProviderConfig.DISABLED.stats(), List.of(), List.of(backend.address()))))) {
      rig.pump(() -> rig.service.live().timeEstimate().isPresent());
      rig.announce("/photonvision/front/hasTarget", "boolean"); rig.value("/photonvision/front/hasTarget", 10_000_000, 0, true);
      rig.pump(() -> rig.service.live().latest().containsKey(front) && rig.service.live().latest().containsKey(rear));
      assertEquals(Math.round(rig.service.live().robotNowUs()), rig.service.live().latest().get(front).serverTimestampUs());
      rig.flush(); path = rig.service.live().current().path();
      var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
      var blocked = rig.manager.stores().store(rig.root).importPaths(new org.triplehelix.wpilogmcp.store.LogStore.Request(List.of(), false, null), progress -> {
        if (progress.phase().equals("starting")) { entered.countDown(); try { assertTrue(release.await(30, java.util.concurrent.TimeUnit.SECONDS)); }
          catch (InterruptedException e) { throw new AssertionError(e); } }
      });
      try {
        assertTrue(entered.await(30, java.util.concurrent.TimeUnit.SECONDS));
        rig.robot.addAndGet(2_000_000); rig.loop.advance(2_000_000);
        long expected = Math.round(rig.service.live().robotNowUs()); backend.change(26);
        rig.pump(() -> rig.service.live().latest().get(front).value().toString().contains("\"exposure_raw\":26.0"));
        assertEquals(expected, rig.service.live().latest().get(front).serverTimestampUs());
        rig.flush(); assertFalse(blocked.isDone());
      } finally { release.countDown(); blocked.get(30, java.util.concurrent.TimeUnit.SECONDS); }
      var latest = rig.call("get_latest_values", "{entries:['" + front + "']}");
      assertEquals("photonvision", latest.getAsJsonArray("values").get(0).getAsJsonObject().get("source").getAsString());
      var vision = rig.call("analyze_vision", "{path:" + new com.google.gson.Gson().toJson(path.toString()) + "}");
      assertEquals("captured", vision.getAsJsonObject("camera_settings").getAsJsonObject("front").get("status").getAsString());
      try (var use = rig.manager.acquire(path.toString())) {
        for (String name : List.of(front, rear)) assertEquals(2, use.log().values().get(name).size(), "One record per camera per snapshot");
        assertTrue(use.log().entries().get(front).metadata().contains("v2026.3.4"));
      }
      backend.close(); rig.pump(() -> rig.service.live().providers().stream().anyMatch(p -> p.state().equals("stand_down")));
      assertTrue(rig.service.live().latest().containsKey(front));
      var providers = rig.session().getAsJsonArray("providers");
      assertEquals("stand_down", providers.get(0).getAsJsonObject().get("state").getAsString());
      assertEquals(4, providers.get(0).getAsJsonObject().get("records").getAsInt());
      String metrics = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
          java.net.URI.create("http://127.0.0.1:" + rig.transport.getPort() + "/metrics")).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body();
      assertTrue(metrics.contains("wpilog_provider_state{provider=\"photonvision/127.0.0.1:" + backend.address().getPort() + "\",state=\"stand_down\"} 1\n"), metrics);
      var closed = java.util.concurrent.CompletableFuture.runAsync(rig.service::close); rig.pump(closed::isDone); closed.get();
      var manifest = com.google.gson.JsonParser.parseString(Files.readString(path.getParent().resolve("session.json"))).getAsJsonObject();
      assertEquals("stand_down", manifest.getAsJsonObject("capture_stats").getAsJsonArray("providers").get(0).getAsJsonObject().get("state").getAsString());
      assertEquals(List.of(), DifferentialChecks.compare(rig.tools, path).findings());
    }
  }
  @Test void unconfiguredPhotonTopicsSuggestConfigurationOnlyOnce() throws Exception {
    var before = System.err; var messages = new java.io.ByteArrayOutputStream();
    try (var err = new java.io.PrintStream(messages, true, java.nio.charset.StandardCharsets.UTF_8)) {
      System.setErr(err);
      try (var rig = new LiveToolRig(temp.resolve("unconfigured"), CapturePolicy.ALL)) {
        rig.announce("/photonvision/front/hasTarget", "boolean"); rig.announce("/photonvision/rear/hasTarget", "boolean");
        assertTrue(rig.service.live().providers().isEmpty());
      }
    } finally { System.setErr(before); }
    assertEquals(1, messages.toString(java.nio.charset.StandardCharsets.UTF_8).lines().filter(l -> l.contains("configure context.photonvision")).count());
  }
}
