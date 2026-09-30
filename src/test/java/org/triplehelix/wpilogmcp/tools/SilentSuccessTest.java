/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/** A result that found nothing to analyze or list is not a success (review 6, section 4.4). */
class SilentSuccessTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    WpilogTools.registerAll(registry);
  }

  private JsonObject call(String tool, String path, Object... keyValues) throws Exception {
    var args = new JsonObject();
    args.addProperty("path", path);
    for (int i = 0; i < keyValues.length; i += 2) {
      var key = (String) keyValues[i];
      var value = keyValues[i + 1];
      if (value instanceof Number n) args.addProperty(key, n);
      else if (value instanceof Boolean b) args.addProperty(key, b);
      else args.addProperty(key, value.toString());
    }
    return findTool(tool).execute(args).getAsJsonObject();
  }

  private static ParsedLog bareLog(String path) {
    return new MockLogBuilder().setPath(path)
        .addNumericEntry("/Motor/Speed", new double[]{0, 1, 2}, new double[]{1, 2, 3}).build();
  }

  @Test
  @DisplayName("can_health without text or counters is not_applicable, not GOOD")
  void canHealthNeedsEvidence() throws Exception {
    var log = bareLog("/mock/bare_can.wpilog");
    putLogInCache(log);
    var r = call("can_health", log.path());
    assertEquals("not_applicable", r.get("status").getAsString(), r.toString());
    assertFalse(r.has("health_assessment"));
    assertTrue(r.getAsJsonArray("looked_for").size() >= 2);
  }

  @Test
  @DisplayName("get_ds_timeline without any of its inputs is not_applicable")
  void timelineNeedsInputs() throws Exception {
    var log = bareLog("/mock/bare_ds.wpilog");
    putLogInCache(log);
    var r = call("get_ds_timeline", log.path());
    assertEquals("not_applicable", r.get("status").getAsString(), r.toString());
    assertTrue(r.get("reason").getAsString().contains("no DriverStation state"));
  }

  @Test
  @DisplayName("listings and exports that match nothing are no_match")
  void emptyListingsAreNoMatch() throws Exception {
    var log = bareLog("/mock/bare_list.wpilog");
    putLogInCache(log);
    var entries = call("list_entries", log.path(), "pattern", "zzz");
    assertEquals("no_match", entries.get("status").getAsString(), entries.toString());
    assertTrue(entries.get("reason").getAsString().contains("zzz"));

    var align = call("align_entries", log.path(), "start_time", 100.0);
    // names is an array: build it by hand
    var args = new JsonObject();
    args.addProperty("path", log.path());
    var names = new com.google.gson.JsonArray();
    names.add("/Motor/Speed");
    args.add("names", names);
    args.addProperty("start_time", 100.0);
    align = findTool("align_entries").execute(args).getAsJsonObject();
    assertEquals("no_match", align.get("status").getAsString(), align.toString());

    var csv = call("export_csv", log.path(), "name", "/Motor/Speed", "start_time", 100.0,
        "inline", true);
    assertEquals("no_match", csv.get("status").getAsString(), csv.toString());
    var file = call("export_csv", log.path(), "name", "/Motor/Speed", "start_time", 100.0);
    assertEquals("no_match", file.get("status").getAsString(), file.toString());
    assertFalse(file.has("output_path"), "nothing is written");
  }
}
