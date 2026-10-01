/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log.struct;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.util.struct.DynamicStruct;
import edu.wpi.first.util.struct.StructDescriptorDatabase;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.EntryInfo;

/** Schema-driven struct decoding (review issues C1-C5). */
@DisplayName("StructSchemas")
class StructSchemasTest {

  static ByteBuffer le(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  /** Schemas as a log would record them, in declaration order. */
  static StructSchemas logged(String... namesAndSchemas) {
    var map = new LinkedHashMap<String, String>();
    var entries = new LinkedHashMap<String, String>();
    for (int i = 0; i < namesAndSchemas.length; i += 2) {
      map.put(namesAndSchemas[i], namesAndSchemas[i + 1]);
      entries.put(namesAndSchemas[i], "/.schema/struct:" + namesAndSchemas[i]);
    }
    return StructSchemas.of(map, entries);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object value) {
    assertInstanceOf(Map.class, value);
    return (Map<String, Object>) value;
  }

  static byte[] pose2d(double x, double y, double rad) {
    return le(24).putDouble(x).putDouble(y).putDouble(rad).array();
  }

  @Nested
  @DisplayName("WPILib and template fallbacks")
  class Fallbacks {

    @Test
    @DisplayName("Pose2d without a logged schema decodes by WPILib's, nested and in field order")
    void pose2d() {
      var s = StructSchemas.fallbackOnly();
      var pose = map(s.decode("struct:Pose2d", StructSchemasTest.pose2d(1.5, -2.0, Math.PI / 2)));
      assertEquals(List.of("translation", "rotation"), List.copyOf(pose.keySet()));
      var translation = map(pose.get("translation"));
      assertEquals(1.5, translation.get("x"));
      assertEquals(-2.0, translation.get("y"));
      var rotation = map(pose.get("rotation"));
      assertEquals(Math.PI / 2, rotation.get("value"));
      assertEquals(90.0, (Double) map(rotation.get("_derived")).get("degrees"), 1e-12);
      assertEquals(StructSchemas.Source.WPILIB, s.source("Pose2d"));
    }

    @Test
    @DisplayName("arrays decode every record; an empty array is an empty list")
    void arrays() {
      var s = StructSchemas.fallbackOnly();
      var bytes = ByteBuffer.allocate(48).put(StructSchemasTest.pose2d(1, 2, 0))
          .put(StructSchemasTest.pose2d(3, 4, 0)).array();
      var list = (List<?>) s.decode("struct:Pose2d[]", bytes);
      assertEquals(2, list.size());
      assertEquals(3.0, map(map(list.get(1)).get("translation")).get("x"));
      assertEquals(List.of(), s.decode("struct:Pose2d[]", new byte[0]));
      // the legacy "structarray:" spelling is an array too
      assertEquals(2, ((List<?>) s.decode("structarray:Pose2d", bytes)).size());
    }

    @Test
    @DisplayName("Rotation3d gains roll, pitch and yaw, matching WPILib's conversions")
    void rotation3d() {
      var s = StructSchemas.fallbackOnly();
      double half = Math.toRadians(30) / 2;
      var yaw30 = le(32).putDouble(Math.cos(half)).putDouble(0).putDouble(0)
          .putDouble(Math.sin(half)).array();
      var derived = map(map(s.decode("struct:Rotation3d", yaw30)).get("_derived"));
      assertEquals(30.0, (Double) derived.get("yaw_deg"), 1e-9);
      assertEquals(0.0, (Double) derived.get("roll_deg"), 1e-9);
      assertEquals(0.0, (Double) derived.get("pitch_deg"), 1e-9);

      var roll20 = le(32).putDouble(Math.cos(Math.toRadians(10))).putDouble(
          Math.sin(Math.toRadians(10))).putDouble(0).putDouble(0).array();
      assertEquals(20.0,
          (Double) map(map(s.decode("struct:Rotation3d", roll20)).get("_derived")).get("roll_deg"),
          1e-9);

      // Gimbal lock: pitch of exactly 90 degrees stays finite everywhere
      double q = Math.sqrt(0.5);
      var pitch90 = le(32).putDouble(q).putDouble(0).putDouble(q).putDouble(0).array();
      var locked = map(map(s.decode("struct:Rotation3d", pitch90)).get("_derived"));
      assertEquals(90.0, (Double) locked.get("pitch_deg"), 1e-6);
      for (var v : locked.values()) assertTrue(Double.isFinite((Double) v), locked.toString());
    }

    @Test
    @DisplayName("Pose3d nests the Rotation3d enrichment")
    void pose3dNested() {
      var s = StructSchemas.fallbackOnly();
      var bytes = le(56).putDouble(1).putDouble(2).putDouble(3).putDouble(1).putDouble(0)
          .putDouble(0).putDouble(0).array();
      var pose = map(s.decode("struct:Pose3d", bytes));
      var rotation = map(pose.get("rotation"));
      assertTrue(rotation.containsKey("_derived"));
      assertEquals(1.0, map(rotation.get("q")).get("w"));
    }

    @Test
    @DisplayName("template structs decode as a stated assumption")
    void templates() {
      var s = StructSchemas.fallbackOnly();
      assertEquals(StructSchemas.Source.ASSUMED, s.source("PoseObservation"));
      var bytes = le(88).putDouble(9.5).putDouble(1).putDouble(2).putDouble(0).putDouble(1)
          .putDouble(0).putDouble(0).putDouble(0).putDouble(0.1).putInt(2).putDouble(2.8)
          .putInt(2).array();
      var obs = map(s.decode("struct:PoseObservation", bytes));
      assertEquals(9.5, obs.get("timestamp"));
      assertEquals(2L, obs.get("tagCount"));
      assertEquals(new EnumValue(2, "PHOTONVISION"), obs.get("type"));

      var sample = map(s.decode("struct:SwerveSample", new byte[144]));
      assertEquals(List.of(0.0, 0.0, 0.0, 0.0), sample.get("fx"));
    }
  }

  @Nested
  @DisplayName("size checks")
  class SizeChecks {

    @Test
    @DisplayName("a single record must be exactly the schema's size")
    void single() {
      var s = StructSchemas.fallbackOnly();
      var e = assertThrows(StructDecodeException.class,
          () -> s.decode("struct:Pose2d", new byte[23]));
      assertTrue(e.getMessage().contains("23 bytes"), e.getMessage());
      assertTrue(e.getMessage().contains("24 bytes"), e.getMessage());
      assertTrue(e.getMessage().contains("wpilib"), e.getMessage());
      assertThrows(StructDecodeException.class, () -> s.decode("struct:Pose2d", new byte[32]));
    }

    @Test
    @DisplayName("an array must be a whole number of records")
    void array() {
      var s = StructSchemas.fallbackOnly();
      var e = assertThrows(StructDecodeException.class,
          () -> s.decode("struct:Pose2d[]", new byte[50]));
      assertTrue(e.getMessage().contains("not a multiple"), e.getMessage());
    }

    @Test
    @DisplayName("a struct with no schema anywhere names what is missing")
    void unknown() {
      var e = assertThrows(StructDecodeException.class,
          () -> StructSchemas.fallbackOnly().decode("struct:Mystery", new byte[4]));
      assertTrue(e.getMessage().contains("no schema for struct Mystery"), e.getMessage());
      assertTrue(e.getMessage().contains("/.schema/struct:Mystery"), e.getMessage());
    }

    @Test
    @DisplayName("a non-struct type is refused")
    void notStruct() {
      assertThrows(StructDecodeException.class,
          () -> StructSchemas.fallbackOnly().decode("double", new byte[8]));
    }
  }

  @Nested
  @DisplayName("logged schemas")
  class Logged {

    static final String WIDE_OBSERVATION = "double timestamp;Pose3d pose;double ambiguity;"
        + "double stdDevXY;int32 tagCount;double averageTagDistance;"
        + "enum {MEGATAG_1=0, MEGATAG_2=1, PHOTONVISION=2} int32 type;";

    @Test
    @DisplayName("a team's edited template struct decodes by its own schema")
    void loggedWinsOverTemplate() {
      var s = logged("PoseObservation", WIDE_OBSERVATION);
      assertEquals(StructSchemas.Source.LOGGED, s.source("PoseObservation"));
      var bytes = le(96).putDouble(1.0).putDouble(3).putDouble(4).putDouble(0).putDouble(1)
          .putDouble(0).putDouble(0).putDouble(0).putDouble(0.1).putDouble(0.25).putInt(2)
          .putDouble(3.0).putInt(1).array();
      var obs = map(s.decode("struct:PoseObservation", bytes));
      assertEquals(0.25, obs.get("stdDevXY"));
      assertEquals(2L, obs.get("tagCount"));
      assertEquals(new EnumValue(1, "MEGATAG_2"), obs.get("type"));
      // the 88-byte template layout no longer fits
      var e = assertThrows(StructDecodeException.class,
          () -> s.decode("struct:PoseObservation", new byte[88]));
      assertTrue(e.getMessage().contains("(logged)"), e.getMessage());
    }

    @Test
    @DisplayName("a non-WPILib Rotation2d is decoded by its schema and not enriched")
    void nonCanonicalRotation() {
      var s = logged("Rotation2d", "float value");
      var rotation = map(s.decode("struct:Rotation2d", le(4).putFloat(1.5f).array()));
      assertEquals(1.5f, rotation.get("value"));
      assertFalse(rotation.containsKey("_derived"));
      // Pose2d (from WPILib) nests the log's Rotation2d: 8 + 8 + 4 bytes
      assertEquals(20, s.info("Pose2d").orElseThrow().size());
    }

    @Test
    @DisplayName("a logged WPILib schema with trailing semicolons is still WPILib's")
    void trailingSemicolons() {
      var s = logged("Rotation2d", "double value;");
      var rotation = map(s.decode("struct:Rotation2d", le(8).putDouble(Math.PI).array()));
      assertEquals(180.0, (Double) map(rotation.get("_derived")).get("degrees"), 1e-12);
    }

    @Test
    @DisplayName("schemas may reference structs declared later")
    void outOfOrder() {
      var s = logged("Outer", "Inner a;Inner b[2]", "Inner", "int16 v;uint8 w");
      var bytes = le(9).putShort((short) -7).put((byte) 200).putShort((short) 1).put((byte) 2)
          .putShort((short) 3).put((byte) 4).array();
      var outer = map(s.decode("struct:Outer", bytes));
      assertEquals(-7L, map(outer.get("a")).get("v"));
      assertEquals(200L, map(outer.get("a")).get("w"));
      var b = (List<?>) outer.get("b");
      assertEquals(3L, map(b.get(1)).get("v"));
    }

    @Test
    @DisplayName("an invalid schema is reported, and so is a struct that depends on it")
    void invalid() {
      var s = logged("Broken", "double x;notatype y", "UsesBroken", "Broken b;double z");
      var info = s.info("Broken").orElseThrow();
      assertFalse(info.valid());
      assertNotNull(info.error());
      var e = assertThrows(StructDecodeException.class,
          () -> s.decode("struct:Broken", new byte[16]));
      assertTrue(e.getMessage().contains("Broken"), e.getMessage());
      var dependent = s.info("UsesBroken").orElseThrow();
      assertFalse(dependent.valid());
      assertTrue(dependent.error().contains("references a struct"), dependent.error());
      assertThrows(StructDecodeException.class,
          () -> s.decode("struct:UsesBroken", new byte[24]));

      // a schema WPILib's parser rejects outright
      var bad = logged("Bad", "double x:3");
      var badInfo = bad.info("Bad").orElseThrow();
      assertFalse(badInfo.valid());
      assertTrue(badInfo.error().startsWith("invalid schema for Bad"), badInfo.error());
      var badDecode = assertThrows(StructDecodeException.class,
          () -> bad.decode("struct:Bad", new byte[8]));
      assertEquals(badInfo.error(), badDecode.getMessage());
    }

    @Test
    @DisplayName("a reference to a struct with no schema leaves the referrer undecodable")
    void missingReference() {
      var s = logged("Outer", "Missing m;double x");
      assertFalse(s.info("Outer").orElseThrow().valid());
      assertThrows(StructDecodeException.class, () -> s.decode("struct:Outer", new byte[8]));
    }
  }

  @Nested
  @DisplayName("field types")
  class FieldTypes {

    @Test
    @DisplayName("integers: sign by type, uint64 beyond Long.MAX_VALUE as a double")
    void integers() {
      var s = logged("Ints", "int8 a;uint8 b;int16 c;uint16 d;int32 e;uint32 f;int64 g;uint64 h");
      var bytes = le(30).put((byte) -5).put((byte) 250).putShort((short) -30000)
          .putShort((short) 65000).putInt(-2_000_000_000).putInt((int) 4_000_000_000L)
          .putLong(Long.MIN_VALUE).putLong(-1L).array();
      var v = map(s.decode("struct:Ints", bytes));
      assertEquals(-5L, v.get("a"));
      assertEquals(250L, v.get("b"));
      assertEquals(-30000L, v.get("c"));
      assertEquals(65000L, v.get("d"));
      assertEquals(-2_000_000_000L, v.get("e"));
      assertEquals(4_000_000_000L, v.get("f"));
      assertEquals(Long.MIN_VALUE, v.get("g"));
      assertEquals(1.8446744073709552E19, v.get("h"));
    }

    @Test
    @DisplayName("float stays float; bool is boolean")
    void floatsAndBools() {
      var s = logged("Mixed", "float f;double d;bool b;bool c");
      var bytes = le(14).putFloat(0.1f).putDouble(0.2).put((byte) 1).put((byte) 0).array();
      var v = map(s.decode("struct:Mixed", bytes));
      assertEquals(0.1f, v.get("f"));
      assertEquals(0.2, v.get("d"));
      assertEquals(true, v.get("b"));
      assertEquals(false, v.get("c"));
    }

    @Test
    @DisplayName("char arrays are strings without trailing NULs")
    void chars() {
      var s = logged("Named", "char name[8];int32 n");
      var bytes = le(12).put("abc\0\0\0\0\0".getBytes(StandardCharsets.UTF_8)).putInt(3).array();
      assertEquals("abc", map(s.decode("struct:Named", bytes)).get("name"));
      var full = le(12).put("abcdefgh".getBytes(StandardCharsets.UTF_8)).putInt(3).array();
      assertEquals("abcdefgh", map(s.decode("struct:Named", full)).get("name"));
    }

    @Test
    @DisplayName("fixed arrays are lists, of numbers or of structs")
    void fixedArrays() {
      var s = logged("Arrays", "double v[3];int16 w[2];Translation2d pts[2]");
      var bytes = le(60).putDouble(1).putDouble(2).putDouble(3).putShort((short) -1)
          .putShort((short) 2).putDouble(5).putDouble(6).putDouble(7).putDouble(8).array();
      var v = map(s.decode("struct:Arrays", bytes));
      assertEquals(List.of(1.0, 2.0, 3.0), v.get("v"));
      assertEquals(List.of(-1L, 2L), v.get("w"));
      assertEquals(7.0, map(((List<?>) v.get("pts")).get(1)).get("x"));
    }

    @Test
    @DisplayName("enums carry the label, or null for a value the schema does not name")
    void enums() {
      var s = logged("Kinded", "enum {A=1, B=2} uint8 kind");
      assertEquals(new EnumValue(2, "B"),
          map(s.decode("struct:Kinded", new byte[] {2})).get("kind"));
      assertEquals(new EnumValue(7, null),
          map(s.decode("struct:Kinded", new byte[] {7})).get("kind"));
    }

    @Test
    @DisplayName("bit-fields match WPILib's own packing, including signed and bool ones")
    void bitFieldsMatchWpilib() throws Exception {
      String schema = "uint8 a:3;uint8 b:5;int16 c:4;int16 d:12;bool e:1;uint32 f:20;bool g:1;"
          + "int8 h;enum {X=0, Y=5} int32 k:4";
      var db = new StructDescriptorDatabase();
      db.add("Bits", schema);
      var desc = db.find("Bits");
      var struct = DynamicStruct.allocate(desc);
      struct.setIntField(desc.findFieldByName("a"), 6);
      struct.setIntField(desc.findFieldByName("b"), 21);
      struct.setIntField(desc.findFieldByName("c"), -3);
      struct.setIntField(desc.findFieldByName("d"), -1000);
      struct.setBoolField(desc.findFieldByName("e"), true);
      struct.setIntField(desc.findFieldByName("f"), 0xABCDE);
      struct.setBoolField(desc.findFieldByName("g"), true);
      struct.setIntField(desc.findFieldByName("h"), -128);
      struct.setIntField(desc.findFieldByName("k"), 5);
      var bytes = new byte[desc.getSize()];
      struct.getBuffer().get(0, bytes);

      var v = map(logged("Bits", schema).decode("struct:Bits", bytes));
      assertEquals(6L, v.get("a"));
      assertEquals(21L, v.get("b"));
      assertEquals(-3L, v.get("c"));
      assertEquals(-1000L, v.get("d"));
      assertEquals(true, v.get("e"));
      assertEquals(0xABCDEL, v.get("f"));
      assertEquals(true, v.get("g"));
      assertEquals(-128L, v.get("h"));
      assertEquals(new EnumValue(5, "Y"), v.get("k"));
      // and every field agrees with WPILib's reader
      var reader = DynamicStruct.wrap(desc, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
      for (var name : List.of("a", "b", "c", "d", "f", "h")) {
        assertEquals(reader.getIntField(desc.findFieldByName(name)), v.get(name), name);
      }
    }
  }

  @Nested
  @DisplayName("field paths and schema information")
  class Paths {

    @Test
    @DisplayName("numeric leaf paths follow nesting, arrays, and enrichment")
    void leafPaths() {
      var s = logged("ArmState", "Rotation2d angle;double currents[2];"
          + "enum {STOWED=0, SCORING=1} int8 mode;uint8 flags:3;bool homed:1;float temperature;"
          + "char tag[4];double history[20]");
      assertEquals(List.of("angle.value", "angle._derived.degrees", "currents[0]", "currents[1]",
          "mode", "flags", "homed", "temperature", "history[*]"), s.numericLeafPaths("ArmState"));
      assertEquals(List.of("translation.x", "translation.y", "rotation.value",
          "rotation._derived.degrees"), s.numericLeafPaths("Pose2d"));
      assertEquals(List.of(), s.numericLeafPaths("Mystery"));
    }

    @Test
    @DisplayName("info describes size, fields, source, and the schema entry")
    void info() {
      var s = logged("ArmState", "Rotation2d angle;double currents[2];"
          + "enum {STOWED=0, SCORING=1} int8 mode;uint8 flags:3;bool homed:1;float temperature");
      var info = s.info("ArmState").orElseThrow();
      assertEquals(30, info.size());
      assertTrue(info.valid());
      assertEquals(StructSchemas.Source.LOGGED, info.source());
      assertEquals("/.schema/struct:ArmState", info.schemaEntry());
      var fields = info.fields();
      assertEquals("Rotation2d", fields.get(0).structType());
      assertEquals(2, fields.get(1).arraySize());
      assertEquals(Map.of("STOWED", 0L, "SCORING", 1L), fields.get(2).enumValues());
      assertEquals(3, fields.get(3).bitWidth());
      assertEquals(0, fields.get(5).bitWidth());
      assertTrue(s.info("Nope").isEmpty());
      assertEquals(List.of("ArmState"), s.loggedStructs());
    }
  }

  @Nested
  @DisplayName("finding a log's schemas")
  class Discovery {

    @Test
    @DisplayName("schema entry names, with or without a prefix; only structschema type")
    void schemaEntryNames() {
      assertEquals("Pose2d", StructSchemas.schemaEntryStruct("/.schema/struct:Pose2d",
          "structschema"));
      assertEquals("Pose2d", StructSchemas.schemaEntryStruct("NT:/.schema/struct:Pose2d",
          "structschema"));
      assertNull(StructSchemas.schemaEntryStruct("/.schema/struct:Pose2d", "string"));
      assertNull(StructSchemas.schemaEntryStruct("/.schema/proto:Pose2d", "structschema"));
      assertNull(StructSchemas.schemaEntryStruct("/Drive/Pose", "structschema"));
    }

    @Test
    @DisplayName("the DataLog schema wins over a NetworkTables copy, whatever the order")
    void dataLogWins() {
      var entries = new LinkedHashMap<String, EntryInfo>();
      entries.put("NT:/.schema/struct:Thing",
          new EntryInfo(1, "NT:/.schema/struct:Thing", "structschema", ""));
      entries.put("/.schema/struct:Thing",
          new EntryInfo(2, "/.schema/struct:Thing", "structschema", ""));
      var values = Map.<String, Object>of("NT:/.schema/struct:Thing", "float a",
          "/.schema/struct:Thing", "double a".getBytes(StandardCharsets.UTF_8));
      var s = StructSchemas.fromLog(entries, values::get);
      assertEquals(8, s.info("Thing").orElseThrow().size());
      assertEquals("/.schema/struct:Thing", s.info("Thing").orElseThrow().schemaEntry());
    }

    @Test
    @DisplayName("a log with no schema entries uses the shared fallback")
    void noneLogged() {
      assertSame(StructSchemas.fallbackOnly(), StructSchemas.fromLog(Map.of(), name -> null));
    }

    @Test
    @DisplayName("canonical comparison ignores spacing and trailing semicolons")
    void normalize() {
      assertEquals(CanonicalSchemas.normalize("double x;double y"),
          CanonicalSchemas.normalize(" double x ; double y ; "));
      assertEquals(CanonicalSchemas.normalize("enum {A=0, B=1} int8 m"),
          CanonicalSchemas.normalize("enum{A = 0,B = 1} int8 m;"));
    }
  }

  @Test
  @DisplayName("decoded structs are compact immutable maps that behave like any Map")
  void structMaps() {
    var pose = map(StructSchemas.fallbackOnly().decode("struct:Pose2d", pose2d(1, 2, 0.5)));
    assertInstanceOf(StructMap.class, pose);
    var expected = new LinkedHashMap<String, Object>();
    expected.put("translation", Map.of("x", 1.0, "y", 2.0));
    expected.put("rotation", Map.of("value", 0.5, "_derived", Map.of("degrees",
        Math.toDegrees(0.5))));
    assertEquals(expected, pose);
    assertEquals(pose, expected);
    assertEquals(expected.hashCode(), pose.hashCode());
    assertEquals(2, pose.size());
    assertTrue(pose.containsKey("rotation"));
    assertFalse(pose.containsKey("heading"));
    assertNull(pose.get("heading"));
    assertThrows(UnsupportedOperationException.class, () -> pose.put("x", 1.0));
    assertThrows(UnsupportedOperationException.class, () -> pose.remove("translation"));
    assertThrows(UnsupportedOperationException.class, pose::clear);
    var it = pose.entrySet().iterator();
    it.next();
    it.next();
    assertThrows(java.util.NoSuchElementException.class, it::next);
    // arrays are immutable lists
    var list = (List<?>) StructSchemas.fallbackOnly().decode("struct:Pose2d[]", pose2d(1, 2, 0));
    assertThrows(UnsupportedOperationException.class, () -> list.remove(0));
  }

  @Test
  @DisplayName("concurrent decoding from many threads gives identical results")
  void concurrentDecoding() throws Exception {
    var s = logged("Outer", "Inner a;Inner b[2];Rotation2d r", "Inner", "int16 v;uint8 w");
    var bytes = le(17).putShort((short) -7).put((byte) 200).putShort((short) 1).put((byte) 2)
        .putShort((short) 3).put((byte) 4).putDouble(1.0).array();
    var expected = s.decode("struct:Outer", bytes);
    var pool = Executors.newFixedThreadPool(8);
    try {
      var tasks = new ArrayList<Callable<Boolean>>();
      for (int t = 0; t < 8; t++) {
        tasks.add(() -> {
          for (int i = 0; i < 2000; i++) {
            if (!expected.equals(s.decode("struct:Outer", bytes))) return false;
          }
          return true;
        });
      }
      for (var f : pool.invokeAll(tasks)) assertTrue(f.get());
    } finally {
      pool.shutdownNow();
    }
  }
}
