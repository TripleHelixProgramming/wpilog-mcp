/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.*;
import java.util.Set;
import org.triplehelix.wpilogmcp.visualization.InventoryFailure;
import org.triplehelix.wpilogmcp.visualization.RecordingInventories;

/** Transport classification and bounded refusal envelopes. Workspace operations own admission. */
final class InventoryRequests {
  /** Raw bytes cover the whole HTTP exchange, including whitespace and batch syntax. Only
   * inventory operations consult this limit; ordinary controls still use normal dispatch. */
  record Admission(int rawBytes, boolean dataAdmitted) {
    static final Admission DIRECT = new Admission(-1, true);
    InventoryFailure refusal(JsonObject message) {
      if (!operation(message, true)) return null;
      try { CallerContext.current().requireLocal(); }
      catch (java.nio.file.AccessDeniedException denied) { return new InventoryFailure(403, "ACCESS_DENIED", denied.getMessage(), false); }
      if (rawBytes > RecordingInventories.MAX_REQUEST_BYTES) return oversized();
      if (rawBytes < 0) try { RecordingInventories.checkRequest(message); }
      catch (java.io.IOException tooLarge) { return oversized(); }
      return !dataAdmitted && operation(message, false) ? busy() : null;
    }
  }
  static InventoryFailure oversized() { return new InventoryFailure(413, "RESOURCE_LIMIT", "Inventory request exceeds 64 KiB of raw input; send a smaller message or batch", false); }
  static InventoryFailure busy() { return new InventoryFailure(413, "RESOURCE_LIMIT", "Inventory request capacity is busy; retry after 1000 ms", true); }
  static boolean containsOperation(JsonElement message) {
    if (message.isJsonObject()) return operation(message.getAsJsonObject(), true);
    if (message.isJsonArray()) for (var item : message.getAsJsonArray()) if (item.isJsonObject() && operation(item.getAsJsonObject(), true)) return true;
    return false;
  }
  static JsonElement responseId(JsonObject message) {
    var id = message.get("id");
    return id != null && id.toString().length() > 65536 ? JsonNull.INSTANCE : id;
  }
  private static final Set<String> READS = Set.of("visualization_source_entries_list", "visualization_source_entries_get");
  static boolean operation(JsonObject message, boolean includeClose) {
    if (!"tools/call".equals(McpMessageHandler.methodName(message)) || !message.has("params") || !message.get("params").isJsonObject()) return false;
    var params = message.getAsJsonObject("params"); var name = params.get("name");
    if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) return false;
    String tool = name.getAsString();
    if (READS.contains(tool) || includeClose && tool.equals("visualization_source_inventory_close")) return true;
    if (!tool.equals("visualization_source_inspect") || !params.has("arguments") || !params.get("arguments").isJsonObject()) return false;
    var mode = params.getAsJsonObject("arguments").get("inventory_mode");
    return mode != null && !new JsonPrimitive("full").equals(mode);
  }
  static boolean reads(JsonElement message) {
    if (message.isJsonObject()) return operation(message.getAsJsonObject(), false);
    if (message.isJsonArray()) for (var item : message.getAsJsonArray()) if (item.isJsonObject() && operation(item.getAsJsonObject(), false)) return true;
    return false;
  }
  static boolean httpRead(String path) {
    return Set.of("/visualizations/v1/source/describe", "/visualizations/v1/source/entries/list", "/visualizations/v1/source/entries/get").contains(path);
  }
}
