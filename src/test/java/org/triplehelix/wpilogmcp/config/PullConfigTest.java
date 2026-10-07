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

class PullConfigTest {
  @TempDir Path temp;
  @Test void defaultsAndYamlKeysIncludeSecretInterpolationWithoutPrintingIt() throws Exception {
    var defaults = PullConfig.parse(null, p -> p, p -> p);
    assertFalse(defaults.ssh().acceptChangedHostKey()); assertFalse(defaults.enabled()); assertEquals(List.of("/home/lvuser/logs", "/u/logs", "/U/logs"), defaults.directories());
    assertEquals(5_000_000, defaults.settleUs()); assertEquals(1_000_000, defaults.rateBytes());
    assertEquals("lvuser", defaults.ssh().user()); assertEquals("", defaults.ssh().password()); assertNull(defaults.ssh().key());
    var yaml = temp.resolve("servers.yaml");
    Files.writeString(yaml, """
        defaults:
          transport: http
          capture:
            robot: {host: 127.0.0.1}
            store: store
            pull:
              enabled: true
              directories: [/u/logs/, /u/logs, /custom]
              settle_sec: 1.25
              rate_bytes: 65536
              ssh: {user: '${PULL_USER}', password: '${PULL_PASSWORD}', accept_changed_host_key: true}
        servers:
          pit: {}
        """);
    var config = new ConfigLoader(n -> switch (n) { case "PULL_USER" -> "synthetic-user"; case "PULL_PASSWORD" -> "synthetic-secret"; default -> null; }).load("pit", yaml).capture().pull();
    assertTrue(config.ssh().acceptChangedHostKey()); assertTrue(config.enabled()); assertEquals(List.of("/u/logs", "/custom"), config.directories());
    assertEquals(1_250_000, config.settleUs()); assertEquals(65536, config.rateBytes());
    assertEquals("synthetic-user", config.ssh().user()); assertEquals("synthetic-secret", config.ssh().password());
    assertFalse(config.toString().contains("synthetic-secret"));
    var key = PullConfig.parse(JsonParser.parseString("{ssh:{key:'relative-key'},directories:[],settle_sec:0}"), p -> temp.resolve(p).toString(), p -> p);
    assertEquals(temp.resolve("relative-key"), key.ssh().key()); assertTrue(key.directories().isEmpty()); assertEquals(0, key.settleUs());
  }

  @ParameterizedTest @CsvSource(delimiter = '|', value = {
      "[]|capture.pull", "{typo:1}|capture.pull.typo", "{enabled:1}|capture.pull.enabled",
      "{directories:'x'}|capture.pull.directories", "{directories:[4]}|capture.pull.directories",
      "{directories:['../x']}|capture.pull.directories", "{directories:['/u/../etc']}|capture.pull.directories",
      "{settle_sec:-1}|capture.pull.settle_sec", "{settle_sec:'NaN'}|capture.pull.settle_sec",
      "{settle_sec:1e20}|capture.pull.settle_sec", "{rate_bytes:0}|capture.pull.rate_bytes",
      "{rate_bytes:1.5}|capture.pull.rate_bytes", "{rate_bytes:2147483648}|capture.pull.rate_bytes",
      "{ssh:[]}|capture.pull.ssh", "{ssh:{accept_changed_host_key:1}}|capture.pull.ssh.accept_changed_host_key", "{ssh:{typo:1}}|capture.pull.ssh.typo",
      "{ssh:{user:''}}|capture.pull.ssh.user", "{ssh:{password:1}}|capture.pull.ssh.password",
      "{ssh:{key:''}}|capture.pull.ssh.key", "{ssh:{password:'',key:'x'}}|capture.pull.ssh"})
  void invalidConfigurationNamesItsKey(String json, String key) {
    assertTrue(assertThrows(ConfigException.class, () -> PullConfig.parse(JsonParser.parseString(json), p -> p, p -> p)).getMessage().contains(key));
  }
}
