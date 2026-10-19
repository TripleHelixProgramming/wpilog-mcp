/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.util.*;

/** Library identity is independent of a recording store and is never a source permission. */
public final class LibraryReferences {
  private LibraryReferences() {}
  public static JsonObject saved(JsonObject ref) {
    var fields = ref.has("store_id") ? Set.of("store_id", "saved_view_id", "revision")
        : Set.of("server_instance_id", "library_id", "saved_view_id", "revision");
    if (!ref.keySet().equals(fields)) throw new IllegalArgumentException("Invalid saved-view reference fields");
    for (var key : fields) if (!key.equals("revision")) uuid(ref, key);
    TemplateDefinition.revision(ref, "revision", false); return ref.deepCopy();
  }
  static UUID uuid(JsonObject object, String key) {
    String value = TemplateDefinition.text(object, key, 36, false); UUID id = UUID.fromString(value);
    if (!id.toString().equals(value)) throw new IllegalArgumentException("Invalid canonical UUID: " + key);
    return id;
  }
  public static void version(JsonObject object, int version) {
    if (version != 1) return;
    for (var key : List.of("saved_view_ref", "template_ref")) {
      var ref = object.get(key);
      if (ref != null && ref.isJsonObject() && ref.getAsJsonObject().has("library_id"))
        throw new IllegalArgumentException("Library references require visualization API version 2");
    }
    for (var key : List.of("views", "working_views", "saved_views")) if (object.has(key) && object.get(key).isJsonArray())
      for (var row : object.getAsJsonArray(key)) if (row.isJsonObject()) version(row.getAsJsonObject(), version);
  }
}
