/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;

/** resolve_signals on a log that follows no convention: candidates to confirm, no guesses. */
@DisplayName("resolve_signals does not guess")
class ResolveSignalsNoGuessTest {

  @AfterEach
  void unload() {
    LogManager.getInstance().unloadAllLogs();
  }

  @Test
  @DisplayName("name-only matches: match heuristic, no entry, needs_confirmation, candidates")
  void heuristicRoles() throws Exception {
    var log = new MockLogBuilder()
        .setPath("/test/no_conventions.wpilog")
        .addNumericEntry("/Power/InputVoltage", new double[]{0, 1}, new double[]{12.4, 12.2})
        .addNumericEntry("/Robot/LoopTimeSec", new double[]{0, 1}, new double[]{0.02, 0.021})
        .build();
    LogManager.getInstance().testPutLog(log.path(), log);

    var args = new JsonObject();
    args.addProperty("path", log.path());
    var r = new CoreTools.ResolveSignalsTool().execute(args).getAsJsonObject();

    var battery = r.getAsJsonObject("roles").getAsJsonObject("battery_voltage");
    assertTrue(battery.get("entry").isJsonNull(), battery.toString());
    assertEquals("heuristic", battery.get("match").getAsString());
    assertTrue(battery.get("needs_confirmation").getAsBoolean());
    assertEquals("/Power/InputVoltage", battery.getAsJsonArray("candidates").get(0).getAsString());
    var loop = r.getAsJsonObject("roles").getAsJsonObject("loop_time_full");
    assertEquals("/Robot/LoopTimeSec", loop.getAsJsonArray("candidates").get(0).getAsString());
    var confirm = r.getAsJsonArray("needs_confirmation").toString();
    assertTrue(confirm.contains("battery_voltage") && confirm.contains("loop_time_full"), confirm);
  }
}
