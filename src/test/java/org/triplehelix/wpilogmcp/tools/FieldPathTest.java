/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;

/** Field paths into decoded values (review issue D1). */
@DisplayName("FieldPath")
class FieldPathTest {

  static final Map<String, Object> POSE = Map.of(
      "translation", Map.of("x", 1.5, "y", -2.0),
      "rotation", Map.of("value", 0.5, "_derived", Map.of("degrees", 28.6)));

  static final List<Object> OBSERVATIONS = List.of(
      Map.of("tagCount", 1L, "type", new EnumValue(2, "PHOTONVISION"), "pose", POSE),
      Map.of("tagCount", 3L, "type", new EnumValue(0, "MEGATAG_1"), "pose", POSE));

  @Test
  @DisplayName("parses fields, indexes, wildcards, and a leading dot")
  void parse() {
    assertEquals(".translation.x", FieldPath.parse("translation.x").toString());
    assertEquals(".translation.x", FieldPath.parse(".translation.x").toString());
    assertEquals("[*].pose.translation.x", FieldPath.parse("[*].pose.translation.x").toString());
    assertEquals(".currents[1]", FieldPath.parse("currents[1]").toString());
    assertEquals("[2][0]", FieldPath.parse("[2][0]").toString());
    assertEquals(".a[0].b", FieldPath.parse("a[0].b").toString());
    assertTrue(FieldPath.parse("").isRoot());
    assertTrue(FieldPath.parse(null).isRoot());
    assertTrue(FieldPath.parse("[*].x").hasWildcard());
    assertFalse(FieldPath.parse("[1].x").hasWildcard());
    assertEquals(FieldPath.parse("a.b"), FieldPath.parse(".a.b"));
  }

  @Test
  @DisplayName("rejects malformed paths with a message naming the path")
  void malformed() {
    for (var bad : List.of("a..b", "a.", "[", "[x]", "[-1]", "a[1", "..")) {
      var e = assertThrows(IllegalArgumentException.class, () -> FieldPath.parse(bad), bad);
      assertTrue(e.getMessage().contains(bad), e.getMessage());
    }
  }

  @Test
  @DisplayName("resolves one leaf; missing branches are null")
  void resolveOne() {
    assertEquals(1.5, FieldPath.parse("translation.x").resolveOne(POSE));
    assertEquals(28.6, FieldPath.parse("rotation._derived.degrees").resolveOne(POSE));
    assertNull(FieldPath.parse("translation.z").resolveOne(POSE));
    assertNull(FieldPath.parse("translation.x.y").resolveOne(POSE));
    assertEquals(3L, FieldPath.parse("[1].tagCount").resolveOne(OBSERVATIONS));
    assertNull(FieldPath.parse("[5].tagCount").resolveOne(OBSERVATIONS));
    assertEquals(20.0, FieldPath.parse("[1]").resolveOne(new double[] {10, 20}));
    assertNull(FieldPath.parse("[2]").resolveOne(new double[] {10, 20}));
    assertEquals(true, FieldPath.parse("[0]").resolveOne(new boolean[] {true}));
    assertSame(POSE, FieldPath.ROOT.resolveOne(POSE));
    assertThrows(IllegalStateException.class, () -> FieldPath.parse("[*]").resolveOne(POSE));
  }

  @Test
  @DisplayName("a wildcard yields every element's leaf, in order")
  void resolveAll() {
    assertEquals(List.of(1L, 3L), FieldPath.parse("[*].tagCount").resolveAll(OBSERVATIONS));
    assertEquals(List.of(1.5, 1.5),
        FieldPath.parse("[*].pose.translation.x").resolveAll(OBSERVATIONS));
    assertEquals(List.of(1L, 2L, 3L), FieldPath.parse("[*]").resolveAll(new long[] {1, 2, 3}));
    assertEquals(List.of(), FieldPath.parse("[*].tagCount").resolveAll(List.of()));
    assertEquals(List.of(), FieldPath.parse("[*].missing").resolveAll(OBSERVATIONS));
    assertEquals(List.of(), FieldPath.parse("[*]").resolveAll(new byte[] {1, 2}));
  }

  @Test
  @DisplayName("numbers, booleans, and enum values read as numbers; nothing else does")
  void toNumber() {
    assertEquals(2.0, FieldPath.toNumber(new EnumValue(2, "PHOTONVISION")));
    assertEquals(7.0, FieldPath.toNumber(new EnumValue(7, null)));
    assertEquals(1.0, FieldPath.toNumber(true));
    assertEquals(0.0, FieldPath.toNumber(false));
    assertEquals(0.25, FieldPath.toNumber(0.25f), 1e-9);
    assertEquals(4.0, FieldPath.toNumber(4L));
    assertNull(FieldPath.toNumber("4"));
    assertNull(FieldPath.toNumber(POSE));
    assertNull(FieldPath.toNumber(null));
    assertTrue(Double.isNaN(FieldPath.toNumber(Double.NaN)));
  }
}
