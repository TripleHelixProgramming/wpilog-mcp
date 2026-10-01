/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.config.ServerConfig;
import org.triplehelix.wpilogmcp.tba.TbaClient;
import org.triplehelix.wpilogmcp.tba.TbaConfig;

/**
 * Applying a configuration configures the subsystems, the TBA client included. The key used to
 * be stored and handed to the client only by the server's own start, so the stress tests, which
 * apply a configuration without starting a server, ran every TBA call as "not configured".
 */
class ApplyConfigTest {

  private static ServerConfig configWithKey(String key) {
    return new ServerConfig("made-up", null, null, key, "stdio", null, null, null, null, null,
        null, null);
  }

  @Test
  @DisplayName("a TBA key in the configuration reaches the TBA client")
  void tbaKeyReachesTheClient() {
    var tbaConfig = TbaConfig.getInstance();
    var client = TbaClient.getInstance();
    var savedKey = tbaConfig.getApiKey();
    try {
      tbaConfig.setApiKey(null);
      client.configure(null);
      assertFalse(client.isAvailable());

      Main.applyConfig(configWithKey("a-made-up-key"));

      assertEquals("a-made-up-key", tbaConfig.getApiKey());
      assertTrue(client.isAvailable(),
          "the key was read into the configuration and never given to the client");
    } finally {
      tbaConfig.setApiKey(savedKey);
      client.configure(savedKey);
    }
  }
}
