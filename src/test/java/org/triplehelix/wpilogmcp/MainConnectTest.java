/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code connect} verb's command line. The bridge itself is tested in its own class. */
@DisplayName("connect")
class MainConnectTest {

  @TempDir
  Path tempDir;

  @Test
  @DisplayName("needs a name or a URL, not both and not neither")
  void usage() {
    assertEquals(2, Main.runConnect(new String[] {"connect"}));
    assertEquals(2, Main.runConnect(new String[] {"connect", "x", "--url", "http://h/"}));
    assertEquals(2, Main.runConnect(new String[] {"connect", "--bogus"}));
    assertEquals(2, Main.runConnect(new String[] {"connect", "--url"}));
  }

  @Test
  @DisplayName("refuses a stdio server: there is nothing to connect to")
  void refusesStdioServer() throws Exception {
    var config = tempDir.resolve("servers.yaml");
    Files.writeString(config, """
        servers:
          dev:
            transport: stdio
        """);
    assertEquals(1, Main.runConnect(new String[] {"connect", "dev", "--config", config.toString()}));
  }

  @Test
  @DisplayName("a configuration that does not exist is an error, not a crash")
  void missingConfiguration() {
    assertEquals(1, Main.runConnect(new String[] {"connect", "nope", "--config",
        tempDir.resolve("missing.yaml").toString()}));
  }
}
