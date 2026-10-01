/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * The documented guarantee: get_ds_timeline's text_event_counts and search_strings' level totals
 * come from the same classifier and therefore agree, for the whole log and for any window.
 */
class TextEventAgreementTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    FrcDomainTools.registerAll(registry);
    QueryTools.registerAll(registry);
  }

  @Test
  @DisplayName("timeline counts equal search_strings level totals, whole log and windowed")
  void countsAgree() throws Exception {
    var console = new ArrayList<TimestampedValue>();
    for (int i = 0; i < 120; i++) {
      console.add(new TimestampedValue(10 + i * 0.5, i % 9 == 0 ? "CAN error dev " + (i % 3) : "Loop time of 0.0" + (20 + i % 5) + "s overrun"));
    }
    console.add(new TimestampedValue(100.0, "DefaultDrive scheduled"));
    console.add(new TimestampedValue(101.0, "Shuffleboard.update(): 0.0001s\n\tLoop time of 0.02s overrun\nCAN error device 5"));
    var alerts = new ArrayList<TimestampedValue>();
    alerts.add(new TimestampedValue(50.0, "PhotonVision exception"));
    alerts.add(new TimestampedValue(60.0, "Warning: joystick 0 disconnected"));
    alerts.add(new TimestampedValue(70.0, "Watchdog not fed"));
    var log = new MockLogBuilder()
        .setPath("/test/agreement.wpilog")
        .addEntry("/RealOutputs/Console", "string", console)
        .addEntry("/RealOutputs/Alerts", "string", alerts)
        .build();
    putLogInCache(log);

    for (var window : List.of(new double[]{Double.NaN, Double.NaN}, new double[]{30.0, 65.0})) {
      var args = new JsonObject();
      args.addProperty("path", log.path());
      if (!Double.isNaN(window[0])) {
        args.addProperty("start_time", window[0]);
        args.addProperty("end_time", window[1]);
      }
      var counts = findTool("get_ds_timeline").execute(args).getAsJsonObject().getAsJsonObject("text_event_counts");

      args.addProperty("limit", 1000);
      args.addProperty("level", "error");
      var errors = findTool("search_strings").execute(args).getAsJsonObject();
      args.addProperty("level", "warning");
      var warnings = findTool("search_strings").execute(args).getAsJsonObject();

      assertEquals(counts.get("error").getAsInt(), errors.get("total_matches").getAsInt(), "errors " + args);
      assertEquals(counts.get("warning").getAsInt(), warnings.get("total_matches").getAsInt(), "warnings " + args);
      assertTrue(counts.get("error").getAsInt() > 0 && counts.get("warning").getAsInt() > 0, counts.toString());
      // Every listed match carries the level that was asked for
      for (var m : errors.getAsJsonArray("matches")) {
        assertEquals("error", m.getAsJsonObject().get("level").getAsString());
      }
    }
  }
}
