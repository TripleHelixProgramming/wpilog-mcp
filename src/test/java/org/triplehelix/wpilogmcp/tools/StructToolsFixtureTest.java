/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Struct-aware tools on the struct fixtures (review issues C1-C5, R4). */
@DisplayName("Struct tools on fixture logs")
class StructToolsFixtureTest extends FixtureToolTestBase {

  static List<String> strings(JsonArray array) {
    var out = new ArrayList<String>();
    array.forEach(e -> out.add(e.getAsString()));
    return out;
  }

  @Test
  @DisplayName("get_entry_info describes a custom struct: schema, source, fields, leaf paths")
  void entryInfoCustomStruct() {
    var r = call("get_entry_info", "struct_custom", "name", "/RealOutputs/Arm/State");
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    var struct = r.getAsJsonObject("struct");
    assertEquals("ArmState", struct.get("name").getAsString());
    assertEquals("logged", struct.get("source").getAsString());
    assertEquals(30, struct.get("size_bytes").getAsInt());
    assertEquals("/.schema/struct:ArmState", struct.get("schema_entry").getAsString());
    assertFalse(struct.get("is_array").getAsBoolean());
    var mode = struct.getAsJsonArray("fields").get(2).getAsJsonObject();
    assertEquals("mode", mode.get("name").getAsString());
    assertEquals(2, mode.getAsJsonObject("enum").get("INTAKE").getAsInt());
    assertEquals(3, struct.getAsJsonArray("fields").get(3).getAsJsonObject()
        .get("bit_width").getAsInt());
    assertEquals(List.of(".angle.value", ".angle._derived.degrees", ".currents[0]",
        ".currents[1]", ".mode", ".flags", ".homed", ".temperature"),
        strings(r.getAsJsonArray("numeric_leaf_paths")));
    assertFalse(r.has("decode_problem"));
    var sample = r.getAsJsonArray("sample_values").get(0).getAsJsonObject()
        .getAsJsonObject("value");
    assertEquals("STOWED", sample.getAsJsonObject("mode").get("label").getAsString());
  }

  @Test
  @DisplayName("get_entry_info on a struct array addresses elements with [*]")
  void entryInfoStructArray() {
    var r = call("get_entry_info", "struct_custom", "name", "/RealOutputs/Arm/States");
    assertTrue(r.getAsJsonObject("struct").get("is_array").getAsBoolean());
    var paths = strings(r.getAsJsonArray("numeric_leaf_paths"));
    assertEquals("[*].angle.value", paths.get(0));
    assertTrue(paths.contains("[*].currents[1]"), paths.toString());
    assertEquals(r.get("sample_count").getAsInt(), r.get("non_empty_sample_count").getAsInt());
  }

  @Test
  @DisplayName("get_entry_info on a struct with no schema reports why nothing decoded")
  void entryInfoMystery() {
    var r = call("get_entry_info", "struct_custom", "name", "/RealOutputs/Mystery");
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    assertEquals(0, r.get("sample_count").getAsInt());
    var problem = r.getAsJsonObject("decode_problem");
    assertEquals(1, problem.get("failed_records").getAsInt());
    assertEquals(1, problem.get("total_records").getAsInt());
    assertTrue(problem.get("reason").getAsString().contains("no schema for struct Mystery"));
    assertEquals("missing", r.getAsJsonObject("struct").get("source").getAsString());
    assertTrue(r.getAsJsonArray("warnings").get(0).getAsString()
        .contains("none of its 1 records could be decoded"), r.toString());
  }

  @Test
  @DisplayName("read_entry refuses an entry none of whose records decode, saying why")
  void readEntryUndecodable() {
    var r = call("read_entry", "struct_custom", "name", "/RealOutputs/Mystery");
    assertEquals("error", r.get("status").getAsString(), r.toString());
    var error = r.get("error").getAsString();
    assertTrue(error.contains("has 1 records but none could be decoded"), error);
    assertTrue(error.contains("no schema for struct Mystery"), error);
  }

  @Test
  @DisplayName("read_entry returns schema-shaped values: nested, enums with labels, derived angles")
  void readEntryShapes() {
    var r = call("read_entry", "struct_custom", "name", "/RealOutputs/Shots/Last", "limit", 1);
    var value = r.getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonObject("value");
    assertEquals(List.of("arm", "distanceMeters", "shotId"), List.copyOf(value.keySet()));
    var arm = value.getAsJsonObject("arm");
    assertEquals(0, arm.getAsJsonObject("mode").get("value").getAsInt());
    assertEquals("STOWED", arm.getAsJsonObject("mode").get("label").getAsString());
    assertTrue(arm.getAsJsonObject("angle").getAsJsonObject("_derived").has("degrees"));
    assertEquals(2, arm.getAsJsonArray("currents").size());
    assertTrue(arm.get("homed").getAsBoolean());
  }

  @Test
  @DisplayName("any tool reading a partly undecodable entry says so, with counts and reason")
  void partialDecodeWarned() {
    var read = call("read_entry", "struct_custom", "name", "/RealOutputs/Arm/Partial");
    assertEquals("ok", read.get("status").getAsString(), read.toString());
    assertEquals(12, read.get("total_in_range").getAsInt());
    var warning = read.getAsJsonArray("warnings").get(0).getAsString();
    assertTrue(warning.contains("3 of 15 records could not be decoded"), warning);
    assertTrue(warning.contains("29 bytes"), warning);
    var problem = read.getAsJsonObject("_metadata").getAsJsonArray("decode_problems").get(0)
        .getAsJsonObject();
    assertEquals("/RealOutputs/Arm/Partial", problem.get("entry").getAsString());
    assertEquals(3, problem.get("failed_records").getAsInt());

    // a tool that knows nothing about decoding gets the same warning
    var export = call("export_csv", "struct_custom", "name", "/RealOutputs/Arm/Partial",
        "inline", true);
    assertTrue(export.getAsJsonArray("warnings").toString()
        .contains("3 of 15 records could not be decoded"), export.toString());
    // and a tool that reads only healthy entries does not
    var healthy = call("read_entry", "struct_custom", "name", "/RealOutputs/Arm/State",
        "limit", 1);
    assertFalse(healthy.has("warnings"), healthy.toString());
  }

  @Test
  @DisplayName("export_csv flattens enum fields into value and label columns")
  void exportEnums() {
    var r = call("export_csv", "struct_custom", "name", "/RealOutputs/Arm/State", "inline", true,
        "max_rows", 2);
    var columns = strings(r.getAsJsonArray("columns"));
    for (var c : List.of("angle.value", "angle._derived.degrees", "currents[0]", "currents[1]",
        "mode", "mode.label", "flags", "homed", "temperature")) {
      assertTrue(columns.contains(c), c + " in " + columns);
    }
    assertFalse(r.toString().contains("EnumValue["), r.toString());
  }

  @Test
  @DisplayName("list_struct_types lists a log's structs in declaration order, missing ones last")
  void listStructTypes() {
    var r = call("list_struct_types", "struct_custom");
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    var names = new ArrayList<String>();
    JsonObject arm = null;
    for (var t : r.getAsJsonArray("struct_types")) {
      var o = t.getAsJsonObject();
      names.add(o.get("name").getAsString());
      if (o.get("name").getAsString().equals("ArmState")) arm = o;
    }
    assertEquals(List.of("ShotRecord", "ArmState", "Rotation2d", "Mystery"), names);
    assertNotNull(arm);
    assertEquals(3, arm.get("entry_count").getAsInt());
    assertEquals(List.of("/RealOutputs/Arm/State", "/RealOutputs/Arm/Partial",
        "/RealOutputs/Arm/States"), strings(arm.getAsJsonArray("entries")));
    assertTrue(r.getAsJsonArray("warnings").get(0).getAsString()
        .contains("struct Mystery cannot be decoded"), r.toString());
  }

  @Test
  @DisplayName("list_struct_types reports a team's own PoseObservation layout, not the template")
  void listStructTypesLayoutMismatch() {
    var r = call("list_struct_types", "struct_layout_mismatch");
    for (var t : r.getAsJsonArray("struct_types")) {
      var o = t.getAsJsonObject();
      if (o.get("name").getAsString().equals("PoseObservation")) {
        assertEquals("logged", o.get("source").getAsString());
        assertEquals(96, o.get("size_bytes").getAsInt());
        assertTrue(strings(o.getAsJsonArray("numeric_leaf_paths")).contains("stdDevXY"));
        return;
      }
    }
    fail("no PoseObservation in " + r);
  }
}
