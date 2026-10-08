/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GatewayConfigTest {
  @Test void gatewayBlockIsOptInAndAccepted() {
    assertDoesNotThrow(() -> CaptureConfig.parse(JsonParser.parseString("""
        {"robot":{"host":"127.0.0.1"},"store":"store","gateway":{}}
        """), s -> s));
  }
  private CaptureConfig config(String extra) throws Exception {
    return CaptureConfig.parse(JsonParser.parseString("{robot:{host:'127.0.0.1'},store:'store'" + extra + "}"), s -> s);
  }
  @Test void omittedGatewayAndZeroDisableItAndAnEmptyBlockUsesTheStandardPort() throws Exception {
    assertEquals(0, config("").gatewayPort());
    assertEquals(0, config(",gateway:{port:0}").gatewayPort());
    assertEquals(5810, config(",gateway:{}").gatewayPort());
    assertEquals(5812, config(",gateway:{port:5812}").gatewayPort());
  }
  @ParameterizedTest @CsvSource(delimiter = '|', value = {
      "null|capture.gateway", "[]|capture.gateway", "{typo:1}|capture.gateway.typo",
      "{port:-1}|capture.gateway.port", "{port:65536}|capture.gateway.port",
      "{port:2.5}|capture.gateway.port", "{port:'5810'}|capture.gateway.port",
      "{port:true}|capture.gateway.port", "{port:null}|capture.gateway.port"})
  void malformedConfigurationNamesTheKey(String value, String key) {
    assertTrue(assertThrows(ConfigException.class, () -> config(",gateway:" + value)).getMessage().contains(key));
  }
}
