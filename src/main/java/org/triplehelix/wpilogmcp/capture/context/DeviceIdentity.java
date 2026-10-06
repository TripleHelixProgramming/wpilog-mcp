/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import com.google.gson.JsonObject;
import java.util.Map;

/** Device evidence travels with the capture; neither its address nor its SSH key is its identity. */
public record DeviceIdentity(String serialNumber, String comments, String address,
    String hostKeyFingerprint, Map<String, String> sources) {
  public DeviceIdentity {
    if (serialNumber == null || !serialNumber.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("Missing or invalid device serial number");
    }
    if (address == null || address.isBlank() || hostKeyFingerprint == null || hostKeyFingerprint.isBlank()) {
      throw new IllegalArgumentException("Device identity requires its address and host key fingerprint");
    }
    comments = comments == null ? "" : comments;
    sources = Map.copyOf(sources);
  }
  public JsonObject json() {
    var value = new JsonObject();
    value.addProperty("serial_number", serialNumber); value.addProperty("comments", comments);
    value.addProperty("address", address); value.addProperty("host_key_fingerprint", hostKeyFingerprint);
    value.addProperty("basis", "device");
    return value;
  }
  public JsonObject metadata() {
    var value = new JsonObject(); value.addProperty("source", "ssh");
    value.addProperty("host", address); value.addProperty("timestamp", "received");
    sources.forEach(value::addProperty); return value;
  }
}
