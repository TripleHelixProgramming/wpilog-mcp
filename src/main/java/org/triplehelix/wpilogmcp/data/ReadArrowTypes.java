/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;
import org.triplehelix.wpilogmcp.data.ArrowType.Field;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.log.struct.UnsignedEnumValue;

/**
 * One mapping from declared log types and decoded schemas to Arrow, shared by all readers.
 * The legacy adapter supplies a lazy view of its values; bounded readers supply their first
 * batch. Struct fields follow decoded schema order, including documented derived fields.
 */
public final class ReadArrowTypes {
  private ReadArrowTypes() {}

  /**
   * Native streams derive a complete shape even when their first batch has no values. A zeroed
   * prototype asks the shared struct decoder for enum wrappers and canonical derived fields;
   * it is used only for types, never returned as an observation. The allocation is bounded
   * before decoding. Legacy empty-series representation stays in {@link #type}.
   */
  public static ArrowType recordedType(String declaredType, StructSchemas schemas, int maxPrototypeBytes) {
    if (maxPrototypeBytes <= 0 || maxPrototypeBytes > (64 << 20))
      throw new IllegalArgumentException("Arrow schema prototype limit must be in 1..67108864 bytes");
    var name = StructSchemas.structName(declaredType);
    if (name == null) return type(declaredType, schemas, List.of());
    var info = schemas.info(name).orElseThrow(() -> new IllegalArgumentException("No schema for " + name));
    if (!info.valid()) throw new IllegalArgumentException("Invalid schema for " + name + ": " + info.error());
    if (info.size() < 0 || info.size() > maxPrototypeBytes)
      throw new IllegalArgumentException("Arrow schema prototype exceeds " + maxPrototypeBytes + " bytes: " + name);
    var prototype = schemas.decodeRecorded("struct:" + name, new byte[info.size()]);
    var element = typeOfValue(prototype);
    return StructSchemas.isArrayType(declaredType) ? list(element) : element;
  }

  /** Query shape follows recorded schema and explicit field steps, never a sample's contents. */
  public static ArrowType queryType(String declaredType, StructSchemas schemas,
      org.triplehelix.wpilogmcp.read.ReadQuery query, int maxPrototypeBytes) {
    ArrowType selected = recordedType(declaredType, schemas, maxPrototypeBytes);
    var path = org.triplehelix.wpilogmcp.read.FieldPath.parse(query.field());
    for (var step : path.steps()) {
      if (step instanceof org.triplehelix.wpilogmcp.read.FieldPath.Field field && selected instanceof ArrowType.Struct struct) {
        selected = struct.fields().stream().filter(f -> f.name().equals(field.name())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No declared field " + field.name())).type();
      } else if (!(step instanceof org.triplehelix.wpilogmcp.read.FieldPath.Field) && selected instanceof ArrowType.ListOf array) {
        selected = array.child().type();
      } else throw new IllegalArgumentException("Field step " + step + " does not apply to " + selected);
    }
    if (path.hasWildcard()) selected = list(selected);
    if (query.reduction() == null) return selected;
    if (!(selected instanceof ArrowType.Float64 || selected instanceof ArrowType.Int64 || selected instanceof ArrowType.UInt64))
      throw new IllegalArgumentException("Reduction requires a scalar numeric field");
    var fields = new ArrayList<Field>();
    for (var name : List.of("start_us", "end_us", "count", "finite_count")) fields.add(new Field(name, new ArrowType.Int64(), false));
    for (var name : List.of("min", "max", "mean", "first", "last")) fields.add(new Field(name, new ArrowType.Float64(), true));
    return new ArrowType.Struct(fields);
  }

  /** A struct uses its first decoded value, or its recorded schema when no value is present. */
  public static ArrowType type(String declaredType, StructSchemas schemas, Iterable<?> values) {
    var type = declaredType;
    switch (type) {
      case "double", "float", "revlog" -> {
        return new ArrowType.Float64();
      }
      case "int64" -> {
        return new ArrowType.Int64();
      }
      case "boolean" -> {
        return new ArrowType.Bool();
      }
      case "string", "json" -> {
        return new ArrowType.Utf8();
      }
      case "raw" -> {
        return new ArrowType.Binary();
      }
      case "double[]", "float[]" -> {
        return list(new ArrowType.Float64());
      }
      case "int64[]" -> {
        return list(new ArrowType.Int64());
      }
      case "boolean[]" -> {
        return list(new ArrowType.Bool());
      }
      case "string[]" -> {
        return list(new ArrowType.Utf8());
      }
      default -> {
        var structName = StructSchemas.structName(type);
        if (structName == null) return new ArrowType.Binary();
        boolean array = StructSchemas.isArrayType(type);
        var element = structType(schemas, structName, values, array);
        return array ? list(element) : element;
      }
    }
  }

  private static ArrowType list(ArrowType element) {
    return new ArrowType.ListOf(new Field("item", element, true));
  }

  /** The struct's Arrow type from its first decoded value, else from its schema's fields. */
  private static ArrowType structType(StructSchemas schemas, String structName,
      Iterable<?> values, boolean array) {
    for (Object sample : values) {
      if (array) {
        var elements = sample instanceof List<?> l ? l : null;
        if (elements == null || elements.isEmpty()) continue;
        sample = elements.get(0);
      }
      if (sample instanceof Map<?, ?> map) return typeOfValue(map);
    }
    return fromSchema(schemas, structName, 0);
  }

  /** The Arrow type of a decoded value, recursively. */
  private static ArrowType typeOfValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      var fields = new ArrayList<Field>();
      for (var e : map.entrySet()) {
        fields.add(new Field(String.valueOf(e.getKey()), typeOfValue(e.getValue()), true));
      }
      return new ArrowType.Struct(fields);
    }
    if (value instanceof EnumValue) {
      return new ArrowType.Struct(List.of(new Field("value", new ArrowType.Int64(), true),
          new Field("label", new ArrowType.Utf8(), true)));
    }
    if (value instanceof UnsignedEnumValue) {
      return new ArrowType.Struct(List.of(new Field("value", new ArrowType.UInt64(), true),
          new Field("label", new ArrowType.Utf8(), true)));
    }
    if (value instanceof BigInteger) return new ArrowType.UInt64();
    if (value instanceof Boolean) return new ArrowType.Bool();
    if (value instanceof Double || value instanceof Float) return new ArrowType.Float64();
    if (value instanceof Number) return new ArrowType.Int64();
    if (value instanceof String) return new ArrowType.Utf8();
    if (value instanceof byte[]) return new ArrowType.Binary();
    if (value instanceof double[] || value instanceof float[]) return list(new ArrowType.Float64());
    if (value instanceof long[] || value instanceof int[]) return list(new ArrowType.Int64());
    if (value instanceof boolean[]) return list(new ArrowType.Bool());
    if (value instanceof String[]) return list(new ArrowType.Utf8());
    if (value instanceof List<?> l) {
      return list(l.isEmpty() ? new ArrowType.Float64() : typeOfValue(l.get(0)));
    }
    if (value == null) return new ArrowType.Float64();
    return new ArrowType.Utf8();
  }

  /** The struct's Arrow type from the schema alone: when no record of it was decoded. */
  private static ArrowType fromSchema(StructSchemas schemas, String structName, int depth) {
    var info = schemas.info(structName).orElse(null);
    if (info == null || depth > 8) return new ArrowType.Struct(List.of());
    var fields = new ArrayList<Field>();
    for (var f : info.fields()) {
      ArrowType type = switch (f.type()) {
        case "double", "float" -> new ArrowType.Float64();
        case "bool" -> new ArrowType.Bool();
        case "char" -> new ArrowType.Utf8();
        case "int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64" ->
            new ArrowType.Int64();
        default -> fromSchema(schemas, f.type(), depth + 1);
      };
      if (f.isArray() && !"char".equals(f.type())) type = list(type);
      fields.add(new Field(f.name(), type, true));
    }
    return new ArrowType.Struct(fields);
  }

}
