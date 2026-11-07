/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MetricsConfigTest {
  @TempDir Path temp;
  @Test void defaultsOverridesAndUnconfiguredServers() throws Exception {
    assertEquals(new MetricsConfig(List.of(), 16), MetricsConfig.parse(JsonParser.parseString("{}")));
    var file = temp.resolve("servers.yaml");
    Files.writeString(file, """
        defaults:
          transport: http
          metrics: {include: ['/Signals/'], max_array_length: 4}
        servers:
          inherited: {}
          explicit:
            metrics: {include: ['/Drive/', '/Power/'], max_array_length: 0}
        """);
    assertEquals(new MetricsConfig(List.of("/Signals/"), 4), new ConfigLoader().load("inherited", file).metrics());
    var explicit = new ConfigLoader().load("explicit", file).metrics();
    assertEquals(new MetricsConfig(List.of("/Drive/", "/Power/"), 0), explicit);
    assertTrue(explicit.includes("/Drive/position")); assertFalse(explicit.includes("/Else/Drive/position"));
    Files.writeString(file, "servers: {plain: {transport: http}}\n");
    assertNull(new ConfigLoader().load("plain", file).metrics());
  }
  @ParameterizedTest @CsvSource(delimiter = '|', value = {
      "[]|metrics", "{typo:1}|metrics.typo", "{include:1}|metrics.include", "{include:[1]}|metrics.include",
      "{include:['']}|metrics.include", "{max_array_length:-1}|metrics.max_array_length",
      "{max_array_length:1.5}|metrics.max_array_length", "{max_array_length:2147483648}|metrics.max_array_length",
      "{max_array_length:'3'}|metrics.max_array_length", "{max_array_length:null}|metrics.max_array_length"})
  void badSettingsNameTheirKey(String json, String key) {
    assertTrue(assertThrows(ConfigException.class, () -> MetricsConfig.parse(JsonParser.parseString(json))).getMessage().contains(key));
  }
}
