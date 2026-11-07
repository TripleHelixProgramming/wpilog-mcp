/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.util.*;

/** Immutable portable definitions; role choices are interpreted only by SignalResolver. */
public final class BundledTemplates {
  private BundledTemplates() {}
  private static final List<JsonObject> ALL = List.of(
      definition("0788b5fb-061d-4a1c-872f-a0585b904e10", "Robot power", new String[]{"battery_voltage", "total_current", "brownout_flag"}, new String[]{"time_series", "time_series", "latest"}),
      definition("0788b5fb-061d-4a1c-872f-a0585b904e11", "Driver Station state", new String[]{"robot_enabled", "autonomous", "test_mode", "fms_attached"}, new String[]{"latest", "latest", "latest", "latest"}),
      definition("0788b5fb-061d-4a1c-872f-a0585b904e12", "User-code timing", new String[]{"loop_time_user", "loop_time_user"}, new String[]{"time_series", "statistics"}));
  public static List<JsonObject> all() { return ALL.stream().map(JsonObject::deepCopy).toList(); }
  private static JsonObject definition(String id, String name, String[] roles, String[] types) {
    var config = VisualizationContracts.blank();
    for (int i = 0; i < roles.length; i++) {
      String key = "r" + i;
      var r = new JsonObject(); r.addProperty("id", key); r.addProperty("selector_kind", "role"); r.addProperty("role", roles[i]);
      r.add("field_path", JsonNull.INSTANCE); boolean flag = types[i].equals("latest");
      r.add("accepted_entry_types", JsonParser.parseString(flag ? "[\"boolean\"]" : "[\"double\",\"float\",\"int64\"]"));
      r.addProperty("value_type", flag ? "boolean" : "number"); r.add("unit", JsonNull.INSTANCE); config.getAsJsonArray("requirements").add(r);
      var p = JsonParser.parseString("{\"panel_id\":\"" + key + "\",\"type\":\"" + types[i] + "\",\"title\":\"" + roles[i] + "\",\"series\":[{\"requirement_id\":\"" + key + "\",\"axis_id\":\"y\",\"color\":\"#66bbee\"}],\"axes\":[{\"axis_id\":\"y\",\"label\":\"Logged units (see binding)\",\"scale\":\"linear\",\"range\":\"auto\"}],\"display\":{\"legend\":true,\"line_width\":1.5}}");
      config.getAsJsonArray("panels").add(p);
      var geometry = new JsonObject(); geometry.addProperty("panel_id", key); geometry.addProperty("x", 0); geometry.addProperty("y", i * 5); geometry.addProperty("w", 12); geometry.addProperty("h", 5);
      config.getAsJsonObject("layout").getAsJsonArray("items").add(geometry);
    }
    var t = new JsonObject(); t.addProperty("schema_version", 1); t.addProperty("template_id", id); t.addProperty("revision", 1);
    t.addProperty("name", name); t.addProperty("description", "Shared resolver roles; unresolved signals remain visible. Units are reported with their basis; no conversion."); t.add("configuration", config);
    return TemplateDefinition.validate(t);
  }
}
