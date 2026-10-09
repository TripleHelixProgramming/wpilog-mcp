/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextConfigTest {
  @TempDir Path temp;
  @Test void photonAddressesAreExplicitInheritedAndWiredIntoCaptureWithoutSsh() throws Exception {
    var file = temp.resolve("servers.yaml");
    Files.writeString(file, """
        defaults:
          transport: http
          capture:
            robot: {host: 127.0.0.1}
            store: synthetic
          context:
            photonvision: ["${PHOTON_HOST}", "[::1]:5801"]
        servers:
          pit: {}
          offline: {context: {photonvision: []}}
        """);
    var loader = new ConfigLoader(key -> "camera.local");
    var config = loader.load("pit", file);
    assertEquals(java.util.List.of(java.net.URI.create("http://camera.local:5800"), java.net.URI.create("http://[::1]:5801")), config.effectiveCapture().providers().photonvision());
    assertFalse(config.effectiveCapture().providers().robotSsh());
    assertTrue(loader.load("offline", file).effectiveCapture().providers().photonvision().isEmpty());
    Files.writeString(file, "servers: {pit: {transport: http, context: {photonvision: [camera.local]}}}");
    assertTrue(assertThrows(ConfigException.class, () -> loader.load("pit", file)).getMessage().contains("context.photonvision requires capture"));
  }
  @Test void badShapesAddressesAndCredentialsNameTheContextKey() {
    for (String value : java.util.List.of("[]", "{typo:[]}", "{photonvision:1}", "{photonvision:[1]}",
        "{photonvision:['user:secret@host']}", "{photonvision:['http://host']}", "{photonvision:['host/path']}",
        "{photonvision:['host:0']}", "{photonvision:['host:65536']}", "{photonvision:['host','host']}")) {
      assertTrue(assertThrows(ConfigException.class, () -> ContextConfig.parse(com.google.gson.JsonParser.parseString(value), s -> s)).getMessage().contains("context"), value);
    }
  }
}
