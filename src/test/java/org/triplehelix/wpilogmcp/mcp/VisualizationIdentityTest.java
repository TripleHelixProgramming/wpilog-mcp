/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.tools.DiscoveryTools;

class VisualizationIdentityTest {
  @Test void healthAndGuideDescribeTheSameEphemeralOwner() throws Exception {
    var registry = new ToolRegistry(); DiscoveryTools.registerAll(registry);
    var transport = new HttpTransport(registry, 0);
    try {
      transport.start();
      var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + transport.getPort() + "/health")).build(),
          HttpResponse.BodyHandlers.ofString());
      var health = JsonParser.parseString(response.body()).getAsJsonObject();
      var guide = registry.getTool("get_server_guide").execute(new JsonObject()).getAsJsonObject();
      assertTrue(health.has("server_instance_id"), "health must identify the owner, not just its port/PID");
      assertDoesNotThrow(() -> UUID.fromString(health.get("server_instance_id").getAsString()));
      assertEquals(health.get("server_instance_id"), guide.get("server_instance_id"));
      assertEquals("ephemeral", health.get("working_state").getAsString());
      assertEquals(health.get("working_state"), guide.get("working_state"));
    } finally { transport.stop(); }
  }
}
