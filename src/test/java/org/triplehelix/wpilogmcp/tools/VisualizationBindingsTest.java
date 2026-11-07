/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.*;
import org.triplehelix.wpilogmcp.visualization.*;

class VisualizationBindingsTest {
  static JsonObject requirement(String selector, String name, String declared, String valueType) {
    var r = new JsonObject(); r.addProperty("id", "signal"); r.addProperty("selector_kind", selector); r.addProperty(selector, name);
    r.add("accepted_entry_types", JsonParser.parseString("[\"" + declared + "\"]")); r.addProperty("value_type", valueType);
    r.add("field_path", JsonNull.INSTANCE); r.add("unit", JsonNull.INSTANCE); return r;
  }
  static JsonObject binding(LogData log, JsonObject requirement) {
    var config = VisualizationContracts.blank(); config.getAsJsonArray("requirements").add(requirement);
    return VisualizationBindings.check(log, config).getAsJsonArray("bindings").get(0).getAsJsonObject();
  }
  @Test void exactMissingNamesDoNotFallbackAndCandidatesAreNeverBound() {
    var log = new MockLogBuilder().addNumericEntry("/powerVoltageMaybe", new double[]{0}, new double[]{2}).build();
    var exact = binding(log, requirement("entry", "/missing", "double", "number")); assertEquals("unresolved", exact.get("state").getAsString()); assertTrue(exact.get("reason").getAsString().contains("missing"));
    var role = binding(log, requirement("role", "battery_voltage", "double", "number")); assertEquals("unresolved", role.get("state").getAsString()); assertTrue(role.get("entry").isJsonNull());
  }
  @Test void publishedRolesReportUnitsAndTypeOrUnitMismatchesRemainUnresolved() {
    var log = new MockLogBuilder().addNumericEntry("/SystemStats/BatteryVoltage", new double[]{0}, new double[]{2}).build();
    var requirement = requirement("role", "battery_voltage", "double", "number"); var bound = binding(log, requirement);
    assertEquals("resolved", bound.get("state").getAsString()); assertEquals("V", bound.getAsJsonObject("unit").get("symbol").getAsString());
    requirement.add("unit", JsonParser.parseString("{\"symbol\":\"A\",\"basis\":\"user_declared\"}"));
    var mismatch = binding(log, requirement); assertEquals("unresolved", mismatch.get("state").getAsString()); assertTrue(mismatch.get("reason").getAsString().contains("Unit differs"));
    requirement.add("unit", JsonNull.INSTANCE); requirement.add("accepted_entry_types", JsonParser.parseString("[\"boolean\"]"));
    assertEquals("unresolved", binding(log, requirement).get("state").getAsString());
  }
  @Test void loggedUnitsAreReportedWhileUnspecifiedUnitsStayUnknown() {
    var samples = List.of(new TimestampedValue(0, 2.0));
    var log = new ParsedLog("/synthetic", Map.of("/value", new EntryInfo(1, "/value", "double", "{\"unit\":\"m\"}")), Map.of("/value", samples), 0, 0);
    var r = requirement("entry", "/value", "double", "number"); assertEquals("m", binding(log, r).getAsJsonObject("unit").get("symbol").getAsString());
    var unknown = new MockLogBuilder().addEntry("/value", "double", samples).build();
    assertTrue(binding(unknown, r).getAsJsonObject("unit").get("symbol").isJsonNull()); assertFalse(binding(unknown, r).getAsJsonArray("warnings").isEmpty());
  }
  @Test void numericFieldValidationUsesTheSharedProjectionOwner() {
    var log = new MockLogBuilder().addEntry("/array", "double[]", List.of(new TimestampedValue(0, new double[]{2,4}))).build();
    var r = requirement("entry", "/array", "double[]", "number"); r.addProperty("field_path", "[1]");
    assertEquals("resolved", binding(log, r).get("state").getAsString());
    r.addProperty("field_path", "[9]"); assertEquals("unresolved", binding(log, r).get("state").getAsString());
    r.addProperty("field_path", "[*]"); assertEquals("unresolved", binding(log, r).get("state").getAsString());
  }
  @Test void bundledTimingNeverSubstitutesTheFullLoopClockAndDefinitionsAreImmutable() {
    var timing = BundledTemplates.all().get(2); var log = new MockLogBuilder().addNumericEntry("/Timestamp", new double[]{0,1}, new double[]{0,1000000}).build();
    assertEquals("incompatible", VisualizationBindings.check(log, timing.getAsJsonObject("configuration")).get("compatibility").getAsString());
    var user = new MockLogBuilder().addNumericEntry("/RealOutputs/LoggedRobot/UserCodeMS", new double[]{0}, new double[]{2}).build();
    assertEquals("compatible", VisualizationBindings.check(user, timing.getAsJsonObject("configuration")).get("compatibility").getAsString());
    timing.addProperty("name", "Mutation"); assertEquals("User-code timing", BundledTemplates.all().get(2).get("name").getAsString());
  }
}
