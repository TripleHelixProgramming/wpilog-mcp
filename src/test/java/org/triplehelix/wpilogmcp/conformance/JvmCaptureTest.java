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
import org.triplehelix.wpilogmcp.capture.context.JmxFixture;
import org.triplehelix.wpilogmcp.config.*;

class JvmCaptureTest {
  @TempDir Path temp;
  @Test void theSameJmxReadReachesCaptureToolsMetricsAndTheClosedManifest() throws Exception {
    String name = "/Daemon/JVM/heap/used_bytes";
    try (var target = new JmxFixture(); var rig = new LiveToolRig(temp.resolve("store"), CapturePolicy.ALL, base ->
        new CaptureConfig(base.addresses(), base.store(), base.periodSeconds(), base.policy(), base.hotWindowUs(), base.maxFileBytes(),
            base.pull(), 0, new ProviderConfig(false, ProviderConfig.DISABLED.stats(), List.of(), List.of(), new ContextConfig.Jvm(target.port(), 60_000_000))))) {
      rig.pump(() -> rig.service.live().timeEstimate().isPresent());
      rig.announce("/trigger", "int"); rig.value("/trigger", 10_000_000, 2, 1L);
      rig.pump(() -> rig.service.live().latest().containsKey(name)); rig.flush();
      var path = rig.service.live().current().path();
      var latest = rig.call("get_latest_values", "{entries:['" + name + "']}").getAsJsonArray("values").get(0).getAsJsonObject();
      assertEquals("jmx", latest.get("source").getAsString());
      var state = rig.session().getAsJsonArray("providers").get(0).getAsJsonObject();
      assertEquals("jvm", state.get("name").getAsString()); assertEquals("sampling", state.get("state").getAsString());
      assertTrue(state.get("sample_bytes").getAsLong() > 0); assertTrue(state.get("last_round_trip_ms").getAsDouble() > 0);
      var client = java.net.http.HttpClient.newHttpClient();
      String metrics = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + rig.transport.getPort() + "/metrics")).build(),
          java.net.http.HttpResponse.BodyHandlers.ofString()).body();
      var parsed = org.triplehelix.wpilogmcp.fixtures.PrometheusText.parse(metrics);
      assertEquals(1., parsed.samples().get(new org.triplehelix.wpilogmcp.fixtures.PrometheusText.Key("wpilog_provider_state", java.util.Map.of("provider", "jvm", "state", "sampling"))));
      assertEquals(rig.service.live().latest().get(name).value(), parsed.samples().get(new org.triplehelix.wpilogmcp.fixtures.PrometheusText.Key("nt_value", java.util.Map.of("topic", name))).longValue());
      try (var use = rig.manager.acquire(path.toString())) {
        assertTrue(use.log().entries().get(name).metadata().contains("\"clock\":\"measured\""));
        assertEquals(1, use.log().values().get(name).size());
      }
      var closed = java.util.concurrent.CompletableFuture.runAsync(rig.service::close); rig.pump(closed::isDone); closed.get();
      var manifest = com.google.gson.JsonParser.parseString(Files.readString(path.getParent().resolve("session.json"))).getAsJsonObject();
      assertEquals(state.get("sample_bytes"), manifest.getAsJsonObject("capture_stats").getAsJsonArray("providers").get(0).getAsJsonObject().get("sample_bytes"));
      assertEquals(List.of(), DifferentialChecks.compare(rig.tools, path).findings());
    }
  }
}
