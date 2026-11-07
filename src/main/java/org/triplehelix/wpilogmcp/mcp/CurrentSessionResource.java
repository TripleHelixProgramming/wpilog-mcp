/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.nt4.server.GatewayStatus;
import org.triplehelix.wpilogmcp.tools.ToolUtils;

/** A discoverable read of published capture facts; never joins the recorder or store queue. */
public final class CurrentSessionResource {
  private CurrentSessionResource() {}
  public static final String URI = "pit://session/current";
  static JsonObject descriptor() {
    var item = new JsonObject(); item.addProperty("uri", URI); item.addProperty("name", "Current pit session");
    item.addProperty("mimeType", "application/json");
    item.addProperty("description", "Current session identity and capture file, connection, gateway status and providers. not_applicable when no capture is open.");
    return item;
  }
  public static JsonObject read(LiveCapture live) {
    var result = new JsonObject(); var current = live == null ? null : live.current();
    boolean open = current != null && current.open();
    result.addProperty("status", open ? "ok" : "not_applicable");
    result.add("gateway", live == null ? GatewayStatus.DISABLED.json() : live.gateway().json());
    result.add("providers", live == null ? new JsonArray() : ToolUtils.GSON.toJsonTree(live.providers()));
    if (!open) {
      result.addProperty("reason", live == null ? "Capture is not enabled" : "No capture session is open");
      result.add("session", com.google.gson.JsonNull.INSTANCE); return result;
    }
    var session = new JsonObject();
    session.addProperty("id", live.sessionId(current.path()));
    session.addProperty("file", live.resolve(current.path()).toString());
    session.addProperty("started_at", current.startedAt().toString());
    session.addProperty("connected", live.connected());
    var identity = current.identity() == null ? new JsonObject() : current.identity().json();
    if (current.identity() == null) { identity.addProperty("address", current.address()); identity.addProperty("basis", "address"); }
    session.add("identity", identity); result.add("session", session); return result;
  }
}
