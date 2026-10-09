/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.harness;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class PhotonBackendSetupTest {
  @Test void networkModeUsesTheReleasesNumericEnumWithoutManagingTheHost() {
    var config = JsonParser.parseString(PhotonBackend.networkConfiguration()).getAsJsonObject();
    assertTrue(config.getAsJsonPrimitive("connectionType").isNumber(), "NetworkMode's JSON values are 0 and 1, not DHCP and Static");
    assertEquals(0, config.get("connectionType").getAsInt());
    assertEquals("127.0.0.1", config.get("ntServerAddress").getAsString());
    assertFalse(config.get("runNTServer").getAsBoolean());
    assertFalse(config.get("shouldManage").getAsBoolean());
  }

  @Test void settingsRestartMustFinishBeforeTheAuditSocketConnects() {
    String initial = "Listening on http://localhost:5800/\n";
    String stopping = initial + "Web server going down for restart\n";
    assertFalse(PhotonBackend.restarted(initial, initial.length()), "The original listener is not the restarted one");
    assertFalse(PhotonBackend.restarted(stopping, initial.length()), "The asynchronous restart has not bound yet");
    assertTrue(PhotonBackend.restarted(stopping + initial, initial.length()));
    assertFalse(PhotonBackend.restarted(stopping + initial, (stopping + initial).length()), "Ignore an earlier restart");
  }
}
