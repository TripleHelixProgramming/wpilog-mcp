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

class CaptureConfigTest {
  @TempDir Path directory;
  private ServerConfig load(String block) throws Exception {
    var file = directory.resolve("servers.yaml");
    Files.writeString(file, "servers:\n  pit:\n    transport: http\n" + block);
    return new ConfigLoader(name -> name.equals("CAPTURE_STORE") ? directory.resolve("store").toString() : null).load("pit", file);
  }

  @Test void defaultsAndAllKeysRoundTripThroughYamlAndExpandTheStore() throws Exception {
    var config = load("    capture:\n      robot: {team: 2363}\n      store: ${CAPTURE_STORE}\n");
    assertEquals(List.of("roboRIO-2363-FRC.local", "10.23.63.2"), config.capture().addresses().stream().map(u -> u.getHost()).toList());
    assertEquals(5810, config.capture().addresses().get(0).getPort());
    assertEquals(0.01, config.capture().periodSeconds()); assertEquals(600_000_000, config.capture().hotWindowUs());
    assertEquals(List.of(directory.resolve("store").toString()), config.effectiveLogdirs());
    var all = load("    logdir: [logs]\n    capture:\n      robot: {usb: true, port: 5811}\n"
        + "      store: ${CAPTURE_STORE}\n      period_sec: 0.02\n      exclude: ['/camera']\n"
        + "      thin: {'/Drive': 0.5}\n      hot_window_sec: 0\n");
    assertEquals("172.22.11.2", all.capture().addresses().get(0).getHost());
    assertEquals(5811, all.capture().addresses().get(0).getPort()); assertEquals(0.02, all.capture().periodSeconds());
    assertEquals(0, all.capture().hotWindowUs()); assertTrue(all.capture().policy().excluded("/camera/x"));
    assertEquals(500_000, all.capture().policy().periodUs("/Drive/x"));
    assertTrue(all.effectiveLogdirs().contains(directory.resolve("store").toString()));
  }

  @Test void captureIsOptionalAndACompleteBlockCanBeInheritedOrReplaced() throws Exception {
    assertNull(load("").capture()); assertTrue(load("").effectiveLogdirs().isEmpty());
    var file = directory.resolve("inherit.yaml");
    Files.writeString(file, "defaults:\n  transport: http\n  capture: {robot: {host: 127.0.0.1}, store: inherited}\n"
        + "servers:\n  pit: {}\n  other:\n    capture: {robot: {host: '::1'}, store: other}\n");
    var loader = new ConfigLoader();
    assertEquals("127.0.0.1", loader.load("pit", file).capture().addresses().get(0).getHost());
    assertEquals("[::1]", loader.load("other", file).capture().addresses().get(0).getHost());
    assertEquals(Path.of("other").toAbsolutePath(), loader.load("other", file).capture().store());
  }

  @ParameterizedTest @CsvSource(delimiter = '|', value = {
      "{}|capture.robot", "{robot:{team:0},store:'x'}|capture.robot.team",
      "{robot:{team:1.5},store:'x'}|capture.robot.team", "{robot:{usb:false},store:'x'}|capture.robot.usb",
      "{robot:{host:''},store:'x'}|capture.robot.host", "{robot:{host:'http://robot'},store:'x'}|capture.robot.host",
      "{robot:{host:'x',port:65536},store:'x'}|capture.robot.port", "{robot:{team:1,usb:true},store:'x'}|capture.robot",
      "{robot:{host:'x'}}|capture.store", "{robot:{host:'x'},store:''}|capture.store",
      "{robot:{host:'x'},store:'x',period_sec:0}|capture.period_sec",
      "{robot:{host:'x'},store:'x',hot_window_sec:-1}|capture.hot_window_sec",
      "{robot:{host:'x'},store:'x',exclude:1}|capture.exclude",
      "{robot:{host:'x'},store:'x',exclude:[1]}|capture.exclude",
      "{robot:{host:'x'},store:'x',thin:[]}|capture.thin",
      "{robot:{host:'x'},store:'x',thin:{x:0}}|capture.thin.x",
      "{robot:{host:'x'},store:'x',typo:1}|capture.typo",
      "{robot:{hostname:'x'},store:'x'}|capture.robot.hostname"})
  void invalidKeysNameTheKey(String json, String key) {
    var error = assertThrows(ConfigException.class, () -> CaptureConfig.parse(JsonParser.parseString(json), p -> p));
    assertTrue(error.getMessage().contains(key), error.getMessage());
  }

  @Test void captureRequiresHttpAndMustOutliveMcpSessions() throws Exception {
    var file = directory.resolve("invalid.yaml");
    for (String extra : List.of("transport: stdio", "transport: http, idle_exit_minutes: 1")) {
      Files.writeString(file, "servers: {pit: {" + extra + ", capture: {robot: {host: x}, store: x}}}");
      assertTrue(assertThrows(ConfigException.class, () -> new ConfigLoader().load("pit", file)).getMessage().contains("capture"));
    }
  }

  @Test void standaloneDocumentsEveryAcceptedCaptureKey() throws Exception {
    String guide = Files.readString(Path.of("doc/STANDALONE.md"));
    for (var key : CaptureConfig.KEYS) assertTrue(guide.contains("`capture." + key + "`"), key);
    for (var key : CaptureConfig.ROBOT_KEYS) assertTrue(guide.contains("`capture.robot." + key + "`"), key);
  }
}
