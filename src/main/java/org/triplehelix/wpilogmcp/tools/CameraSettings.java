/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/** Exact camera identity and recorded snapshots, never a calibration inferred from observations. */
final class CameraSettings {
  private CameraSettings() {}
  static String cameraOf(String observation) {
    String parent = observation.substring(0, Math.max(0, observation.lastIndexOf('/')));
    return parent.substring(parent.lastIndexOf('/') + 1);
  }
  static JsonObject forEntry(LogData log, String observation, Double start, Double end, ResponseBuilder builder) {
    String camera = cameraOf(observation);
    String wanted = "/Daemon/PhotonVision/" + camera + "/Settings";
    var result = new JsonObject(); result.addProperty("camera", camera);
    var entry = log.entries().get(wanted);
    if (entry == null || !entry.type().equals("json")) return none(result, "No settings entry was captured for this exact camera name");
    builder.addInput("camera_settings/" + camera, wanted);
    var snapshots = new JsonArray(); TimestampedValue held = null;
    try {
      for (var value : log.values().get(wanted)) {
        if (end != null && value.timestamp() > end) continue;
        if (start != null && value.timestamp() < start) { held = value; continue; }
        if (held != null) { snapshots.add(snapshot(held, camera)); held = null; }
        snapshots.add(snapshot(value, camera));
      }
      if (held != null) snapshots.add(snapshot(held, camera));
    } catch (RuntimeException e) {
      return none(result, "Captured settings are unreadable: " + e.getMessage());
    }
    if (snapshots.isEmpty()) return none(result, "No settings snapshot was captured at or before this window's end");
    result.addProperty("status", "captured"); result.addProperty("entry", wanted);
    result.addProperty("basis", "Recorded configuration, matched by exact camera name; valid from each receipt timestamp, never inferred from detections");
    result.add("snapshots", snapshots); return result;
  }
  private static JsonObject snapshot(TimestampedValue value, String camera) {
    var settings = JsonParser.parseString(String.valueOf(value.value())).getAsJsonObject();
    if (!settings.has("camera") || !settings.get("camera").getAsString().equals(camera)) {
      throw new IllegalArgumentException("snapshot names another camera");
    }
    if (!settings.has("calibrations") || !settings.has("pipeline")) throw new IllegalArgumentException("missing calibration or pipeline context");
    var result = new JsonObject(); result.addProperty("timestamp_sec", value.timestamp()); result.add("settings", settings); return result;
  }
  private static JsonObject none(JsonObject result, String reason) {
    result.addProperty("status", "none_captured"); result.addProperty("reason", reason); return result;
  }
}
