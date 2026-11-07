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
  @Test void jvmRequiresAnExplicitPortAndDefaultsToOneSecondWithoutEnablingSsh() throws Exception {
    var file = temp.resolve("jvm.yaml");
    Files.writeString(file, """
        defaults:
          transport: http
          capture: {robot: {host: 127.0.0.1}, store: synthetic}
          context: {jvm: {port: 5809}}
        servers:
          pit: {}
          disabled: {context: {}}
          slow: {context: {jvm: {port: 5811, period_sec: 2.5}}}
        """);
    var loader = new ConfigLoader(key -> null);
    assertEquals(new ContextConfig.Jvm(5809, 1_000_000), loader.load("pit", file).effectiveCapture().providers().jvm());
    assertFalse(loader.load("pit", file).effectiveCapture().providers().robotSsh());
    assertNull(loader.load("disabled", file).effectiveCapture().providers().jvm());
    assertEquals(new ContextConfig.Jvm(5811, 2_500_000), loader.load("slow", file).effectiveCapture().providers().jvm());
    for (String bad : java.util.List.of("null", "{}", "{port:0}", "{port:65536}", "{port:1.5}", "{port:'1'}",
        "{port:1,period_sec:0}", "{port:1,period_sec:3601}", "{port:1,typo:true}"))
      assertTrue(assertThrows(ConfigException.class, () -> ContextConfig.parse(com.google.gson.JsonParser.parseString("{jvm:" + bad + "}"), v -> v))
          .getMessage().contains("context.jvm"));
    Files.writeString(file, "servers: {pit: {transport: http, context: {jvm: {port: 5809}}}}");
    assertTrue(assertThrows(ConfigException.class, () -> loader.load("pit", file)).getMessage().contains("context.jvm requires capture"));
  }
  @Test void badShapesAddressesAndCredentialsNameTheContextKey() {
    for (String value : java.util.List.of("[]", "{typo:[]}", "{photonvision:1}", "{photonvision:[1]}",
        "{photonvision:['user:secret@host']}", "{photonvision:['http://host']}", "{photonvision:['host/path']}",
        "{photonvision:['host:0']}", "{photonvision:['host:65536']}", "{photonvision:['host','host']}")) {
      assertTrue(assertThrows(ConfigException.class, () -> ContextConfig.parse(com.google.gson.JsonParser.parseString(value), s -> s)).getMessage().contains("context"), value);
    }
  }
}
