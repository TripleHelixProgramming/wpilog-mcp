/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

class ReadArrowTypesTest {
  @Test void declaredTypesKeepIntegersArraysAndOpaqueBytesDistinct() {
    var schemas = StructSchemas.fallbackOnly();
    assertEquals(new ArrowType.Int64(), ReadArrowTypes.type("int64", schemas, List.of()));
    assertEquals(new ArrowType.Float64(), ReadArrowTypes.type("float", schemas, List.of()));
    assertEquals(new ArrowType.Bool(), ReadArrowTypes.type("boolean", schemas, List.of()));
    assertEquals(new ArrowType.Utf8(), ReadArrowTypes.type("json", schemas, List.of()));
    assertEquals(new ArrowType.Binary(), ReadArrowTypes.type("custom:unknown", schemas, List.of()));
    assertEquals(new ArrowType.ListOf(new ArrowType.Field("item", new ArrowType.Int64(), true)),
        ReadArrowTypes.type("int64[]", schemas, List.of()));
  }

  @Test void decodedStructFieldsKeepTheirNamesAndEnumLabels() {
    var value = new LinkedHashMap<String,Object>();
    value.put("reading", 2.5);
    value.put("mode", new EnumValue(2, "ready"));
    var actual = (ArrowType.Struct) ReadArrowTypes.type("struct:Sample", StructSchemas.fallbackOnly(), List.of(value));
    assertEquals(List.of("reading", "mode"), actual.fields().stream().map(ArrowType.Field::name).toList());
    assertEquals(new ArrowType.Float64(), actual.fields().get(0).type());
    assertEquals(new ArrowType.Struct(List.of(new ArrowType.Field("value", new ArrowType.Int64(), true),
        new ArrowType.Field("label", new ArrowType.Utf8(), true))), actual.fields().get(1).type());
  }

  @Test void emptyStructArraysUseRecordedSchema() {
    var schemas = StructSchemas.recordedOnly(Map.of("Sample", "int64 count; bool ready;"), Map.of());
    var actual = ReadArrowTypes.type("struct:Sample[]", schemas, List.of(List.of()));
    assertEquals(new ArrowType.ListOf(new ArrowType.Field("item", new ArrowType.Struct(List.of(
        new ArrowType.Field("count", new ArrowType.Int64(), true),
        new ArrowType.Field("ready", new ArrowType.Bool(), true))), true)), actual);
  }

  @Test void nativeEmptyPrefixesStillDeclareEnumLabelsAndRotationDerivedFields() {
    var schemas = StructSchemas.of(Map.of("Sample", "enum {off=0,on=1} int32 mode; Rotation2d heading;"), Map.of());
    var type = (ArrowType.ListOf) ReadArrowTypes.recordedType("struct:Sample[]", schemas, 1024);
    var element = (ArrowType.Struct) type.child().type();
    var mode = (ArrowType.Struct) element.fields().get(0).type();
    assertEquals(List.of("value", "label"), mode.fields().stream().map(ArrowType.Field::name).toList());
    var heading = (ArrowType.Struct) element.fields().get(1).type();
    assertEquals(List.of("value", "_derived"), heading.fields().stream().map(ArrowType.Field::name).toList());
    var derived = (ArrowType.Struct) heading.fields().get(1).type();
    assertEquals(List.of("degrees"), derived.fields().stream().map(ArrowType.Field::name).toList());
  }

  @Test void nativeShapeCompilationBoundsItsPrototypeBeforeAllocation() {
    var schemas = StructSchemas.recordedOnly(Map.of("Large", "double values[1000];"), Map.of());
    var error = assertThrows(IllegalArgumentException.class,
        () -> ReadArrowTypes.recordedType("struct:Large", schemas, 128));
    assertTrue(error.getMessage().contains("128"), error.getMessage());
  }

  @Test void legacyAdapterDelegatesInsteadOfOwningAnotherTypeMapping() throws Exception {
    var code = Files.readString(Path.of("src/main/java/org/triplehelix/wpilogmcp/tools/EntryData.java"));
    assertTrue(code.contains("ReadArrowTypes.type("), "legacy Arrow must use the same type owner");
    assertFalse(code.contains("private static ArrowType"), "type decisions belong to ReadArrowTypes");
  }
}
