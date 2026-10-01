/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

/**
 * Every tool that reads a log says when the log was not read to its end. Only list_entries and
 * generate_report used to; any other result on a truncated log said nothing.
 *
 * <p>Most real robot logs end inside their last record, because the robot is switched off while
 * logging. That is noted ({@code _metadata.log_truncation}) without a warning: a warning on
 * nearly every result would teach an agent to ignore warnings. A log that lost more than its
 * cut-off final record (garbage at the end, records set aside) gets the warning too.
 */
@DisplayName("a truncated log is reported by every tool that reads it")
class TruncatedLogReportingTest extends FixtureToolTestBase {

  static final String VOLTAGE = "/SystemStats/BatteryVoltage";

  static String truncation(JsonObject result) {
    if (!result.has("_metadata")) return null;
    var note = result.getAsJsonObject("_metadata").get("log_truncation");
    return note == null ? null : note.getAsString();
  }

  static String warnings(JsonObject result) {
    return result.has("warnings") ? result.get("warnings").toString() : "";
  }

  /** Calls of several kinds on one log: statistics, raw reads, searches, timelines. */
  static List<JsonObject> representativeCalls(String path) throws Exception {
    return List.of(
        execute("get_statistics", path, "name", VOLTAGE),
        execute("read_entry", path, "name", VOLTAGE, "limit", 3),
        execute("search_entries", path, "pattern", "Battery"),
        execute("get_entry_info", path, "name", VOLTAGE),
        execute("get_types", path),
        execute("find_condition", path, "name", VOLTAGE, "operator", "gt", "threshold", 0),
        execute("resolve_signals", path));
  }

  static JsonObject execute(String tool, String path, Object... keyValues) throws Exception {
    var args = new JsonObject();
    args.addProperty("path", path);
    for (int i = 0; i < keyValues.length; i += 2) {
      var value = keyValues[i + 1];
      if (value instanceof Number n) args.addProperty((String) keyValues[i], n);
      else args.addProperty((String) keyValues[i], value.toString());
    }
    return tools.get(tool).execute(args).getAsJsonObject();
  }

  @Test
  @DisplayName("a log cut off inside its last record: noted in every result, with no warning")
  void cutOffLog() throws Exception {
    for (var r : representativeCalls(fixturePath("truncated").toString())) {
      assertTrue(r.get("success").getAsBoolean(), r.toString());
      var note = truncation(r);
      assertNotNull(note, "no _metadata.log_truncation: " + r);
      assertTrue(note.startsWith("Log file is truncated or damaged: the file ends inside a "
          + "record"), note);
      assertFalse(warnings(r).contains("truncated or damaged"),
          "an ordinary cut-off log must not raise a warning on every result: " + warnings(r));
    }
  }

  @Test
  @DisplayName("a complete log carries no truncation note")
  void completeLog() throws Exception {
    for (var r : representativeCalls(fixturePath("akit_match").toString())) {
      assertNull(truncation(r), r.toString());
    }
  }

  @Test
  @DisplayName("a log with garbage at its end: noted, and warned about, in every result")
  void damagedLog() throws Exception {
    Path path = FixtureLogs.defaultDirectory().resolve("damaged_tail.wpilog");
    Files.createDirectories(path.getParent());
    try (var w = new WpilogWriter(path, "")) {
      int v = w.start(VOLTAGE, "double", "", 0);
      for (int i = 1; i <= 200; i++) {
        w.append(v, i * 20_000L, WpilogWriter.encodeDouble(12.0 + 0.001 * i));
      }
      // what a write cut off mid-file leaves behind: a record for an entry never declared
      w.append(1007427528, 2_384_800_000_000_000L, new byte[151]);
    }
    for (var r : representativeCalls(path.toString())) {
      var note = truncation(r);
      assertNotNull(note, "no _metadata.log_truncation: " + r);
      assertTrue(note.contains("which the log never declared"), note);
      assertTrue(warnings(r).contains("which the log never declared"),
          "a damaged log must be warned about: " + r);
    }
  }

  @Test
  @DisplayName("tools that load logs themselves report it too")
  void toolsThatLoadLogsThemselves() throws Exception {
    var truncated = fixturePath("truncated").toString();
    var structs = execute("list_struct_types", truncated);
    assertNotNull(truncation(structs), structs.toString());

    var args = new JsonObject();
    args.addProperty("path", truncated);
    args.addProperty("compare_path", fixturePath("akit_match").toString());
    args.addProperty("name", VOLTAGE);
    var compared = tools.get("compare_matches").execute(args).getAsJsonObject();
    assertTrue(compared.get("success").getAsBoolean(), compared.toString());
    var comparisons = objects(compared.getAsJsonArray("comparisons"));
    assertEquals(2, comparisons.size());
    assertTrue(comparisons.get(0).has("log_truncation"), comparisons.get(0).toString());
    assertTrue(comparisons.get(0).get("log_truncation").getAsString()
        .contains("the file ends inside a record"));
    assertFalse(comparisons.get(1).has("log_truncation"), comparisons.get(1).toString());
  }
}
