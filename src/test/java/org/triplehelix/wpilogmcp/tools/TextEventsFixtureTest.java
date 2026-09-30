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
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/** Text from string[] alerts and json entries, not only string entries (review issue F1). */
@DisplayName("Text events: alerts and json")
class TextEventsFixtureTest extends FixtureToolTestBase {

  static List<JsonObject> matches(JsonObject result) {
    var out = new ArrayList<JsonObject>();
    result.getAsJsonArray("matches").forEach(m -> out.add(m.getAsJsonObject()));
    return out;
  }

  @Test
  @DisplayName("an alert is one match from when it appears to when it clears")
  void alertEpisode() {
    var r = call("search_strings", "alerts_console", "pattern", "camera 3");
    var ms = matches(r);
    assertEquals(1, ms.size(), r.toString());
    var alert = ms.get(0);
    assertEquals("/RealOutputs/Alerts/errors", alert.get("entry").getAsString());
    assertEquals("alert", alert.get("source").getAsString());
    assertEquals("error", alert.get("level").getAsString());
    assertEquals(50.0, alert.get("timestamp_sec").getAsDouble(), 1e-9);
    assertEquals(96.0, alert.get("end_sec").getAsDouble(), 1e-9);
    assertEquals(46.0, alert.get("duration_sec").getAsDouble(), 1e-9);
  }

  @Test
  @DisplayName("an alert still present at the end of the log says so; info is a level")
  void activeAtEnd() {
    var r = call("search_strings", "alerts_console", "level", "info");
    var ms = matches(r);
    assertEquals(1, ms.size(), r.toString());
    assertEquals("Robot code version abc1234", ms.get(0).get("value").getAsString());
    assertTrue(ms.get(0).get("active_at_log_end").getAsBoolean());
    assertFalse(ms.get(0).has("end_sec"));
  }

  @Test
  @DisplayName("json string values are searchable, with their source")
  void jsonText() {
    var r = call("search_strings", "alerts_console", "pattern", "radio link lost");
    var ms = matches(r);
    assertEquals(1, ms.size(), r.toString());
    assertEquals("json", ms.get(0).get("source").getAsString());
    assertEquals(70.0, ms.get(0).get("timestamp_sec").getAsDouble(), 1e-9);
    assertEquals("radio link lost", ms.get(0).get("line").getAsString());
  }

  @Test
  @DisplayName("a time range selects the alerts present in it, whenever they appeared")
  void alertsInRange() {
    var r = call("search_strings", "alerts_console", "entry_pattern", "Alerts",
        "start_time", 60, "end_time", 90);
    var texts = matches(r).stream().map(m -> m.get("value").getAsString()).toList();
    assertTrue(texts.contains("Vision camera 3 is disconnected."), texts.toString());
    assertTrue(texts.contains("PhotonCamera 'OV2311_TH_7' is disconnected."), texts.toString());
    assertTrue(texts.contains("Robot code version abc1234"), texts.toString());
    assertFalse(texts.contains("Low battery voltage."), texts.toString()); // 30-40 s
  }

  @Test
  @DisplayName("get_ds_timeline places each alert on the timeline once, with when it cleared")
  void timelineAlerts() {
    var r = call("get_ds_timeline", "alerts_console");
    var raised = new ArrayList<JsonObject>();
    for (var e : r.getAsJsonArray("events")) {
      if (e.getAsJsonObject().get("type").getAsString().equals("ALERT_RAISED")) {
        raised.add(e.getAsJsonObject());
      }
    }
    assertEquals(4, raised.size(), raised.toString());
    var battery = raised.stream().filter(a -> a.get("message").getAsString()
        .equals("Low battery voltage.")).findFirst().orElseThrow();
    assertEquals(30.0, battery.get("timestamp").getAsDouble(), 1e-9);
    assertEquals(40.0, battery.get("cleared_at").getAsDouble(), 1e-9);
    assertEquals("warning", battery.get("level").getAsString());
    assertTrue(raised.stream().anyMatch(a -> a.has("active_at_log_end")));
    // alert errors count with console and json errors
    var counts = r.getAsJsonObject("text_event_counts").getAsJsonObject("by_source");
    assertEquals(1, counts.getAsJsonObject("/RealOutputs/Alerts/errors").get("error").getAsInt());
    assertEquals(1, counts.getAsJsonObject("/RadioStatus/Status").get("error").getAsInt());
  }

  @Test
  @DisplayName("generate_report counts alert and json errors once each")
  void reportErrors() {
    var r = call("generate_report", "alerts_console");
    var errors = r.getAsJsonObject("errors");
    var messages = new ArrayList<String>();
    errors.getAsJsonArray("top_messages")
        .forEach(m -> messages.add(m.getAsJsonObject().get("example").getAsString()));
    assertTrue(messages.contains("Vision camera 3 is disconnected."), messages.toString());
    assertTrue(messages.contains("PhotonCamera 'OV2311_TH_7' is disconnected."),
        messages.toString());
  }

  @Test
  @DisplayName("alert episodes: reappearance is a new episode; duplicates in one record are one")
  void episodes() {
    var values = List.of(
        new TimestampedValue(1.0, new String[] {"A", "A", "B"}),
        new TimestampedValue(2.0, new String[] {"B"}),
        new TimestampedValue(3.0, new String[] {"A", "B"}),
        new TimestampedValue(4.0, new String[] {}),
        new TimestampedValue(5.0, new String[] {" ", "C"}));
    var log = new MockLogBuilder().setPath("/test/alerts.wpilog")
        .addEntry("/Alerts/warnings", "string[]", values).build();
    var events = TextEvents.of(log, new EntryInfo(1, "/Alerts/warnings", "string[]", ""));
    assertEquals(4, events.size(), events.toString());
    assertEquals("A", events.get(0).text());
    assertEquals(2.0, events.get(0).end());
    assertEquals("B", events.get(1).text());
    assertEquals(4.0, events.get(1).end());
    assertEquals("A", events.get(2).text());
    assertEquals(3.0, events.get(2).timestamp());
    assertEquals("C", events.get(3).text());
    assertNull(events.get(3).end());
    assertEquals("warning", TextEvents.level(events.get(3)));
  }

  @Test
  @DisplayName("json text: string values in document order; unparseable json is used as is")
  void jsonExtraction() {
    assertEquals("ok\nnested\nx", TextEvents.jsonText(
        "{\"a\":\"ok\",\"b\":{\"c\":[\"nested\",1,true,null]},\"d\":\"x\"}"));
    assertEquals("{not json", TextEvents.jsonText("{not json"));
    assertEquals("", TextEvents.jsonText("{\"n\": 3}"));
  }
}
