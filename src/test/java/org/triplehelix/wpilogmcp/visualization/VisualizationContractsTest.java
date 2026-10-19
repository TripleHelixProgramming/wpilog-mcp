/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VisualizationContractsTest {
  @ParameterizedTest @ValueSource(strings = {"{\"x\":1,\"x\":2}", "{\"a\":1e999}", "{\"a\":\"\\ud800\"}", "{x:1}", "{\"a\":1} {}"})
  void strictParsingRejectsAmbiguousOrUnrepresentableInput(String text) {
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.parse(text));
  }
  @Test void rejectsDepthSizeUnknownVersionAndTransientFields() {
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.parse("{\"x\":".repeat(34) + "0" + "}".repeat(34)));
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.parse("{\"x\":\"" + "a".repeat(1024 * 1024) + "\"}"));
    var blank = VisualizationContracts.blank(); assertEquals(blank, VisualizationContracts.configuration(blank));
    blank.addProperty("schema_version", 2); assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.configuration(blank));
    for (var key : java.util.List.of("samples", "url", "connection_id", "credentials", "path")) {
      var config = VisualizationContracts.blank(); config.addProperty(key, "forbidden");
      assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.configuration(config));
      var source = VisualizationServiceTest.source(); source.addProperty(key, "forbidden");
      assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.source(source));
    }
  }
  @Test void layoutCannotReferToMissingPanelsOrExceedColumns() {
    var config = VisualizationContracts.blank();
    config.getAsJsonObject("layout").getAsJsonArray("items").add(JsonParser.parseString("{\"panel_id\":\"ghost\",\"x\":11,\"y\":0,\"w\":2,\"h\":1}"));
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.configuration(config));
  }
  @Test void sourceIdentityHasNoConnectionOrLocator() {
    var source = VisualizationServiceTest.source();
    assertEquals(source, VisualizationContracts.source(source));
    source.addProperty("source_id", "not-a-uuid");
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.source(source));
  }

  @Test void qualifiedSourceRetainsOwnerScopeAndStrictLegacyContent() {
    var source = JsonParser.parseString("""
        {"schema_version":2,"server_instance_id":"11111111-1111-4111-8111-111111111111",
         "scope_id":"22222222-2222-4222-8222-222222222222",
         "content":{"kind":"unmanaged","source_id":"33333333-3333-4333-8333-333333333333"}}
        """).getAsJsonObject();
    assertEquals(source, assertDoesNotThrow(() -> VisualizationContracts.source(source)));
    var extra = source.deepCopy(); extra.addProperty("url", "http://other.invalid");
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.source(extra));
    var nested = source.deepCopy(); nested.add("content", source.deepCopy());
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.source(nested));
    var future = source.deepCopy(); future.addProperty("schema_version", 3);
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.source(future));
  }

  @Test void sessionAnchorReportsInvalidClockAsAContractError() {
    var source = JsonParser.parseString("""
        {"kind":"session","origin_store_id":"00000000-0000-4000-8000-000000000001",
         "session_id":"00000000-0000-4000-8000-000000000002",
         "anchor_member":{"original_name":"fixture.wpilog","recording_started_at":"not-a-clock"}}
        """).getAsJsonObject();
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.source(source));
  }

  @Test void panelReferencesAndGeometryAreValidatedAgainstAnIndependentlyBuiltConfiguration() {
    var config = VisualizationContracts.blank();
    config.getAsJsonArray("requirements").add(JsonParser.parseString("""
        {"id":"value","selector_kind":"entry","entry":"Fixture/Value","field_path":null,
         "accepted_entry_types":["double"],"value_type":"number","unit":null}
        """));
    config.getAsJsonArray("panels").add(JsonParser.parseString("""
        {"panel_id":"plot","type":"time_series","title":"Synthetic values",
         "series":[{"requirement_id":"value","axis_id":"left","color":"#123456"}],
         "axes":[{"axis_id":"left","label":"","scale":"linear","range":"auto"}],
         "display":{"legend":true,"line_width":1.5}}
        """));
    config.getAsJsonObject("layout").getAsJsonArray("items").add(JsonParser.parseString("""
        {"panel_id":"plot","x":0,"y":0,"w":12,"h":4}
        """));
    assertEquals(config, VisualizationContracts.configuration(config));
    var badReference = config.deepCopy();
    badReference.getAsJsonArray("panels").get(0).getAsJsonObject().getAsJsonArray("series").get(0).getAsJsonObject().addProperty("requirement_id", "absent");
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.configuration(badReference));
    var badGeometry = config.deepCopy();
    badGeometry.getAsJsonObject("layout").getAsJsonArray("items").get(0).getAsJsonObject().addProperty("x", 1);
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.configuration(badGeometry));
    var duplicate = config.deepCopy(); duplicate.getAsJsonArray("requirements").add(config.getAsJsonArray("requirements").get(0));
    assertThrows(IllegalArgumentException.class, () -> VisualizationContracts.configuration(duplicate));
  }
}
