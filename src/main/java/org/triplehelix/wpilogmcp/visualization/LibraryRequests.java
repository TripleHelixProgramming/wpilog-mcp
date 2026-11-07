/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.util.Set;

/** The transports share request classes; configuration/import envelopes retain their larger cap. */
public final class LibraryRequests {
  private LibraryRequests() {}
  private static final Set<String> TOOLS = Set.of("visualization_library_list", "visualization_library_migrate", "visualization_saved_view_open",
      "visualization_template_list", "visualization_template_get", "visualization_template_create", "visualization_template_update",
      "visualization_template_import", "visualization_template_export", "visualization_view_save");
  public static boolean tool(String name, JsonObject args) { return TOOLS.contains(name) && new JsonPrimitive(2).equals(args.get("api_version")); }
  public static boolean message(JsonObject message) {
    if (!new JsonPrimitive("tools/call").equals(message.get("method")) || !message.has("params") || !message.get("params").isJsonObject()) return false;
    var params = message.getAsJsonObject("params");
    return params.has("name") && params.get("name").isJsonPrimitive() && params.getAsJsonPrimitive("name").isString()
        && params.has("arguments") && params.get("arguments").isJsonObject() && tool(params.get("name").getAsString(), params.getAsJsonObject("arguments"));
  }
  public static int requestBytes(JsonObject message) {
    String name = message.getAsJsonObject("params").get("name").getAsString();
    return Set.of("visualization_template_create", "visualization_template_update", "visualization_template_import").contains(name) ? 1 << 20 : 64 << 10;
  }
  public static boolean http(String path) {
    if (!path.startsWith("/visualizations/v2/")) return false;
    String route = path.substring("/visualizations/v2".length());
    return route.startsWith("/library/") || route.startsWith("/templates/") && !route.equals("/templates/check")
        || Set.of("/source/views", "/saved/open").contains(route) || route.startsWith("/views/") && route.endsWith("/save");
  }
  public static int requestBytes(String route) {
    return Set.of("/templates/create", "/templates/update", "/templates/import", "/templates/import-preview").contains(route) ? 1 << 20 : 64 << 10;
  }
}
