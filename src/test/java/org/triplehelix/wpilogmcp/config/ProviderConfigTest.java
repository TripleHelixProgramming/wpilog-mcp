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

class ProviderConfigTest {
  private CaptureConfig parse(String extra) throws Exception {
    return CaptureConfig.parse(JsonParser.parseString("{robot:{host:'127.0.0.1'},store:'synthetic'" + (extra.isEmpty() ? "" : ",") + extra + "}"),
        p -> p.replace("${KEY_FILE}", "synthetic-key"), p -> p.replace("${PASSWORD}", "synthetic-secret"));
  }
  @Test void sshEnablesProvidersWithoutEnablingPullAndExplicitBlocksCanDisableOrTuneThem() throws Exception {
    assertFalse(parse("").providers().stats().enabled());
    for (String ssh : List.of("pull:{ssh:{}}", "pull:{enabled:true}")) {
      var providers = parse(ssh).providers(); assertTrue(providers.stats().enabled());
      assertEquals(2_000_000, providers.stats().periodUs()); assertEquals(100_000, providers.stats().budgetUs());
      assertEquals(1, providers.tails().size()); assertEquals(ProviderConfig.CONSOLE, providers.tails().get(0).path());
      assertEquals("program_console", providers.tails().get(0).role());
    }
    assertFalse(parse("pull:{ssh:{}}").pull().enabled());
    var disabled = parse("pull:{ssh:{}},stats:{enabled:false},tail:[]").providers();
    assertFalse(disabled.stats().enabled()); assertTrue(disabled.tails().isEmpty());
    var tuned = parse("stats:{enabled:true,period_sec:3,budget_ms:80},tail:[{path:'/var/log/messages',role:syslog},"
        + "{host:'camera.local',user:service,password:'${PASSWORD}',files:[{path:'/tmp/messages',role:program_console}]}]").providers();
    assertEquals(3_000_000, tuned.stats().periodUs()); assertEquals(80_000, tuned.stats().budgetUs());
    assertEquals(2, tuned.tails().size()); assertEquals("synthetic-secret", tuned.tails().get(1).ssh().password());
    assertEquals("camera.local", tuned.tails().get(1).host());
    var key = parse("tail:[{host:x,key:'${KEY_FILE}',path:'/log',role:kernel}]").providers().tails().get(0);
    assertEquals(Path.of("synthetic-key").toAbsolutePath(), key.ssh().key());
    assertEquals("lvuser", key.ssh().user());
  }
  @Test void errorsNameTheKeyAndSecretsRequireEnvironmentReferences() {
    for (var pair : List.of(
        new String[] {"stats:{period_sec:0}", "capture.stats.period_sec"},
        new String[] {"stats:{budget_ms:-1}", "capture.stats.budget_ms"},
        new String[] {"stats:{enabled:2}", "capture.stats.enabled"},
        new String[] {"stats:{typo:true}", "capture.stats.typo"},
        new String[] {"tail:{}", "capture.tail"},
        new String[] {"tail:[{host:'127.0.0.1',user:different,path:'/log',role:kernel}]", "capture.tail"},
        new String[] {"tail:[{path:'relative',role:kernel}]", "capture.tail[0].path"},
        new String[] {"tail:[{path:'/log',role:'a/b'}]", "capture.tail[0].role"},
        new String[] {"tail:[{path:'/log',role:kernel,password:'${PASSWORD}'}]", "capture.tail[0]"},
        new String[] {"tail:[{host:x,path:'/log',role:kernel,password:literal}]", "capture.tail[0].password"},
        new String[] {"tail:[{host:x,path:'/log',role:kernel,key:literal}]", "capture.tail[0].key"},
        new String[] {"tail:[{host:x,path:'/log',role:kernel,password:'${UNSET}'}]", "capture.tail[0].password"},
        new String[] {"tail:[{path:'/a',role:kernel},{path:'/b',role:kernel}]", "capture.tail"},
        new String[] {"tail:[{host:x,user:a,path:'/a',role:kernel},{host:x,user:b,path:'/b',role:syslog}]", "capture.tail"})) {
      assertTrue(assertThrows(ConfigException.class, () -> parse(pair[0])).getMessage().contains(pair[1]), pair[0]);
    }
  }
  @Test void guideNamesEveryStatsAndTailKeyAndTheConventions() throws Exception {
    String text = Files.readString(Path.of("doc/STANDALONE.md"));
    for (var key : ProviderConfig.STATS_KEYS) assertTrue(text.contains("`capture.stats." + key + "`"), key);
    for (var key : ProviderConfig.TAIL_KEYS) assertTrue(text.contains("`capture.tail[]." + key + "`"), key);
    for (String role : List.of("program_console", "kernel", "syslog", "journal")) {
      assertTrue(Files.readString(Path.of("doc/TOOLS.md")).contains("`" + role + "`"));
    }
    assertTrue(text.contains(ProviderConfig.CONSOLE));
  }
}
