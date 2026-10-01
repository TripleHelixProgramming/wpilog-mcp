/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An argument that names a choice the tool does not offer is an error that says what the choices
 * are, never a silent default: an agent that misspells a value must not get a different analysis
 * from the one it asked for.
 */
@DisplayName("argument checks on fixture logs")
class ArgumentChecksFixtureTest extends FixtureToolTestBase {

  static final String ARM_ANGLE = "/RealOutputs/Arm/State.angle.value";

  @Test
  @DisplayName("find_peaks: type is max, min, or both; anything else is an error")
  void findPeaksType() {
    for (var type : new String[] {"max", "min", "both"}) {
      var r = call("find_peaks", "struct_custom", "name", ARM_ANGLE, "type", type);
      assertEquals("ok", r.get("status").getAsString(), type + ": " + r);
      assertEquals(!type.equals("min"), r.has("maxima"), type);
      assertEquals(!type.equals("max"), r.has("minima"), type);
    }
    // "maximum" used to behave as "both"
    for (var type : new String[] {"maximum", "MAX", "peaks", ""}) {
      var r = call("find_peaks", "struct_custom", "name", ARM_ANGLE, "type", type);
      assertEquals("error", r.get("status").getAsString(), "'" + type + "': " + r);
      var error = r.get("error").getAsString();
      assertTrue(error.contains("type must be 'max', 'min', or 'both'"), error);
      assertFalse(r.has("maxima") || r.has("minima"), r.toString());
    }
  }

  @Test
  @DisplayName("resolve_signals: roles is an array of role names; anything else is an error")
  void resolveSignalsRoles() {
    var one = new JsonArray();
    one.add("battery_voltage");
    var ok = call("resolve_signals", "akit_match", "roles", one);
    assertEquals("ok", ok.get("status").getAsString(), ok.toString());
    assertEquals(1, ok.getAsJsonObject("roles").size());

    // A bare string used to be read as "all roles"
    var bare = call("resolve_signals", "akit_match", "roles", "battery_voltage");
    assertEquals("error", bare.get("status").getAsString(), bare.toString());
    assertTrue(bare.get("error").getAsString().contains("roles must be an array of role names"),
        bare.toString());
    assertFalse(bare.has("roles"), bare.toString());

    var object = call("resolve_signals", "akit_match", "roles", new JsonObject());
    assertEquals("error", object.get("status").getAsString(), object.toString());

    var number = new JsonArray();
    number.add(new JsonPrimitive(3));
    var notNames = call("resolve_signals", "akit_match", "roles", number);
    assertEquals("error", notNames.get("status").getAsString(), notNames.toString());
    assertTrue(notNames.get("error").getAsString().contains("roles must be an array of role names"),
        notNames.toString());

    var nested = new JsonArray();
    nested.add(new JsonArray());
    assertEquals("error", call("resolve_signals", "akit_match", "roles", nested)
        .get("status").getAsString());

    // An empty list asks for nothing: say so instead of returning an empty mapping
    var empty = call("resolve_signals", "akit_match", "roles", new JsonArray());
    assertEquals("error", empty.get("status").getAsString(), empty.toString());
    assertTrue(empty.get("error").getAsString().contains("omit roles for all of them"),
        empty.toString());

    // Omitting it, or passing null, still means every role
    var all = call("resolve_signals", "akit_match");
    assertEquals(SignalResolver.Role.values().length, all.getAsJsonObject("roles").size());
    var nul = call("resolve_signals", "akit_match", "roles", com.google.gson.JsonNull.INSTANCE);
    assertEquals(SignalResolver.Role.values().length, nul.getAsJsonObject("roles").size());
  }
}
