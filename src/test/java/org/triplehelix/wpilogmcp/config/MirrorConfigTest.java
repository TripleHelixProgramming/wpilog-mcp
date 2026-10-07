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

class MirrorConfigTest {
  @TempDir Path temp;
  @Test void defaultsAndExplicitKeysAreAppliedAndTheFolderIsAdmitted() throws Exception {
    var file = temp.resolve("servers.yaml");
    Files.writeString(file, "defaults:\n  transport: http\n  mirror: {origin: 'http://127.0.0.1:2363', folder: '" + temp.resolve("mirror").toString().replace("\\", "/")
        + "'}\nservers:\n  local: {}\n  other:\n    mirror: {origin: 'http://example.test:2363', folder: other, days: 3, max_size_gb: 2.5, robots: [RIO], events: [District], interval_sec: 8, rate_bytes: 500}\n");
    var config = new ConfigLoader().load("local", file); var mirror = config.mirror();
    assertEquals(14, mirror.days()); assertEquals(20_000_000_000L, mirror.maxSizeBytes()); assertEquals(30, mirror.intervalSec()); assertEquals(0, mirror.rateBytes());
    assertEquals(List.of(), mirror.robots()); assertEquals(List.of(), mirror.events()); assertTrue(config.effectiveLogdirs().contains(temp.resolve("mirror").toString()));
    mirror = new ConfigLoader().load("other", file).mirror();
    assertEquals(3, mirror.days()); assertEquals(2_500_000_000L, mirror.maxSizeBytes()); assertEquals(8, mirror.intervalSec()); assertEquals(500, mirror.rateBytes());
    assertEquals(List.of("RIO"), mirror.robots()); assertEquals(List.of("District"), mirror.events());
    Files.writeString(file, "servers: {local: {transport: stdio, mirror: {origin: 'http://example.test', folder: mirror}}}");
    assertTrue(assertThrows(ConfigException.class, () -> new ConfigLoader().load("local", file)).getMessage().contains("mirror"));
    Files.writeString(file, "servers: {local: {transport: http}}"); assertNull(new ConfigLoader().load("local", file).mirror());
  }
  @ParameterizedTest @CsvSource(delimiter = '|', value = {
      "origin:'ssh://host'|origin", "origin:'http://user:pass@host'|origin", "folder:''|folder", "days:-1|days", "days:1.5|days",
      "max_size_gb:0|max_size_gb", "max_size_gb:1e30|max_size_gb", "robots:1|robots", "events:[1]|events",
      "interval_sec:0|interval_sec", "rate_bytes:-1|rate_bytes", "typo:0|typo"})
  void rejectsBadValuesWithTheKey(String change, String key) {
    var json = JsonParser.parseString("{origin:'http://example.test',folder:'mirror'}").getAsJsonObject();
    JsonParser.parseString("{" + change + "}").getAsJsonObject().entrySet().forEach(e -> json.add(e.getKey(), e.getValue()));
    assertTrue(assertThrows(ConfigException.class, () -> MirrorConfig.parse(json, s -> s)).getMessage().contains("mirror." + key));
  }
  @Test void standaloneDocumentsEveryMirrorKeyAndTheLocalControls() throws Exception {
    String guide = Files.readString(Path.of("doc/STANDALONE.md"));
    for (String key : MirrorConfig.KEYS) assertTrue(guide.contains("`mirror." + key + "`"), key);
    for (String route : List.of("/store/mirror/configure", "/store/mirror/sync", "/store/mirror/pin_session", "/store/mirror/unpin_session")) assertTrue(guide.contains(route), route);
    assertTrue(guide.contains("not assistant tools")); assertTrue(guide.contains("default `0` means unlimited"));
  }
}
