/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas.Source;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas.StructInfo;

/**
 * JSON descriptions of a log's struct types, shared by {@code list_struct_types} and
 * {@code get_entry_info}.
 *
 * @since 0.9.0
 */
final class StructDescriptions {

  private StructDescriptions() {}

  /** Why a struct's source matters to whoever reads its values. */
  static String sourceNote(Source source) {
    return switch (source) {
      case LOGGED -> "decoded by the schema this log records";
      case WPILIB -> "this log records no schema for it; decoded by WPILib's schema, which is the "
          + "same in every WPILib version that logs it";
      case ASSUMED -> "this log records no schema for it; decoded by a common template layout, "
          + "which may not match this team's struct (a record of another size fails to decode)";
    };
  }

  /** A struct type: source, size, schema, fields, and numeric leaf paths. */
  static JsonObject describe(StructSchemas schemas, StructInfo info) {
    var o = new JsonObject();
    o.addProperty("name", info.name());
    o.addProperty("source", info.source().wire());
    o.addProperty("source_note", sourceNote(info.source()));
    o.addProperty("valid", info.valid());
    if (info.error() != null) o.addProperty("error", info.error());
    if (info.valid()) o.addProperty("size_bytes", info.size());
    o.addProperty("schema", info.schema());
    if (info.schemaEntry() != null) o.addProperty("schema_entry", info.schemaEntry());
    var fields = new JsonArray();
    for (var f : info.fields()) {
      var field = new JsonObject();
      field.addProperty("name", f.name());
      field.addProperty("type", f.type());
      if (f.isArray()) field.addProperty("array_size", f.arraySize());
      if (f.bitWidth() > 0) field.addProperty("bit_width", f.bitWidth());
      if (!f.enumValues().isEmpty()) {
        var values = new JsonObject();
        f.enumValues().entrySet().stream().sorted(Map.Entry.comparingByValue())
            .forEach(e -> values.addProperty(e.getKey(), e.getValue()));
        field.add("enum", values);
      }
      fields.add(field);
    }
    o.add("fields", fields);
    if (info.valid()) o.add("numeric_leaf_paths", strings(schemas.numericLeafPaths(info.name())));
    return o;
  }

  /** A struct type an entry uses but that no schema describes. */
  static JsonObject missing(String name) {
    var o = new JsonObject();
    o.addProperty("name", name);
    o.addProperty("source", "missing");
    o.addProperty("valid", false);
    o.addProperty("error", "no schema for struct " + name + " in this log (no /.schema/struct:"
        + name + " entry, and not a WPILib or template struct); its entries cannot be decoded");
    return o;
  }

  static JsonArray strings(List<String> values) {
    var array = new JsonArray();
    values.forEach(array::add);
    return array;
  }
}
