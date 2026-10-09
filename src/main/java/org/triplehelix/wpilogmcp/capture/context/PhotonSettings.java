/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only projection of v2026.3.4 UIPhotonConfiguration, UICameraConfiguration and pipeline
 * fields. This is our schema, not copied backend code. No camera, mode or calibration is guessed
 * when the private wire shape changes. The pinned source links are in the pit plan.
 */
public final class PhotonSettings {
  public static final String RELEASE = "v2026.3.4";
  public static final String EXPORT_PATH = "/api/settings/photonvision_config.zip";
  public static final String SOCKET_PATH = "/websocket_data";
  private static final List<String> MODES = List.of("FocusCamera", "Calib3d", "DriverMode", "Reflective",
      "ColoredShape", "AprilTag", "Aruco", "ObjectDetection");
  private PhotonSettings() {}
  public record Camera(String name, JsonObject settings) {}

  public static List<Camera> snapshot(JsonObject message) {
    var settings = object(message, "settings"); var general = object(settings, "general");
    String version = string(general, "version");
    if (!version.equals(RELEASE)) throw bad("settings.general.version", "expected " + RELEASE);
    var layout = object(settings, "atfl"); array(layout, "tags");
    var field = object(layout, "field"); number(field, "length"); number(field, "width");
    String model = string(general, "hardwareModel"), platform = string(general, "hardwarePlatform");
    String arch = string(general, "wpilibArch"), acceleration = string(general, "gpuAcceleration");
    boolean mrCal = bool(general, "mrCalWorking");
    var cameras = array(message, "cameraSettings");
    if (cameras.size() > 64) throw bad("cameraSettings", "more than 64 cameras");
    var result = new ArrayList<Camera>(); var names = new java.util.HashSet<String>();
    for (var item : cameras) {
      var camera = requireObject(item, "cameraSettings[]");
      String name = string(camera, "nickname"), unique = string(camera, "uniqueName");
      if (name.isBlank() || name.contains("/") || name.chars().anyMatch(Character::isISOControl) || !names.add(name)) {
        throw bad("cameraSettings.nickname", "must be a unique nonempty NT camera component");
      }
      var pipeline = object(camera, "currentPipelineSettings");
      int ordinal = integer(pipeline, "pipelineType");
      if (ordinal < 0 || ordinal >= MODES.size()) throw bad("pipelineType", "unknown enum ordinal");
      int mode = integer(pipeline, "cameraVideoModeIndex");
      var resolution = object(object(camera, "videoFormatList"), Integer.toString(mode));
      number(resolution, "width"); number(resolution, "height"); number(resolution, "fps"); string(resolution, "pixelFormat");
      var active = new JsonObject(); active.addProperty("type", MODES.get(ordinal));
      active.addProperty("index", integer(camera, "currentPipelineIndex"));
      active.addProperty("name", string(pipeline, "pipelineNickname"));
      active.add("resolution", resolution.deepCopy());
      active.addProperty("exposure_raw", number(pipeline, "cameraExposureRaw"));
      active.addProperty("auto_exposure", bool(pipeline, "cameraAutoExposure"));
      active.addProperty("gain", number(pipeline, "cameraGain"));
      active.add("mode_3d", ordinal >= 3 ? new com.google.gson.JsonPrimitive(bool(pipeline, "solvePNPEnabled")) : com.google.gson.JsonNull.INSTANCE);
      active.add("multi_tag", ordinal == 5 || ordinal == 6 ? new com.google.gson.JsonPrimitive(bool(pipeline, "doMultiTarget")) : com.google.gson.JsonNull.INSTANCE);
      active.addProperty("not_applicable_basis", "3D is absent on focus/calibration/driver pipelines; multi-tag is absent outside AprilTag/Aruco");
      active.add("field_layout", layout.deepCopy());
      var calibrations = new JsonArray();
      for (var entry : array(camera, "calibrations")) {
        var calibration = requireObject(entry, "calibrations[]");
        var size = object(calibration, "resolution"); number(size, "width"); number(size, "height");
        matrix(object(calibration, "cameraIntrinsics")); matrix(object(calibration, "distCoeffs"));
        var errors = array(calibration, "meanErrors");
        for (var error : errors) finite(error, "calibrations.meanErrors[]");
        int snapshots = integer(calibration, "numSnapshots");
        if (snapshots != errors.size()) throw bad("calibrations.meanErrors", "count differs from numSnapshots");
        var projected = new JsonObject(); projected.add("resolution", size.deepCopy());
        projected.add("camera_intrinsics", calibration.get("cameraIntrinsics").deepCopy());
        projected.add("distortion_coefficients", calibration.get("distCoeffs").deepCopy());
        projected.addProperty("lens_model", string(calibration, "lensmodel"));
        projected.add("mean_reprojection_errors_px", errors.deepCopy());
        projected.addProperty("snapshot_count", snapshots); calibrations.add(projected);
      }
      var state = new JsonObject();
      for (String key : List.of("isConnected", "hasConnected", "mismatch", "deactivated")) state.addProperty(key, bool(camera, key));
      state.addProperty("gpu_acceleration", acceleration); state.addProperty("mrcal_working", mrCal);
      state.addProperty("platform", platform); state.addProperty("architecture", arch);
      var out = new JsonObject(); out.addProperty("camera", name); out.addProperty("unique_name", unique);
      out.addProperty("software_version", version); out.addProperty("device_type", model);
      out.add("hardware_status", state); out.add("calibrations", calibrations); out.add("pipeline", active);
      result.add(new Camera(name, out));
    }
    return List.copyOf(result);
  }
  private static void matrix(JsonObject matrix) {
    int rows = integer(matrix, "rows"), cols = integer(matrix, "cols"); integer(matrix, "type");
    var data = array(matrix, "data");
    if (rows < 1 || cols < 1 || (long) rows * cols != data.size()) throw bad("calibration matrix", "dimensions differ from data");
    for (var value : data) finite(value, "calibration matrix data");
  }
  static JsonObject requireObject(JsonElement value, String name) {
    if (value == null || !value.isJsonObject()) throw bad(name, "missing object"); return value.getAsJsonObject();
  }
  private static JsonObject object(JsonObject parent, String key) { return requireObject(parent.get(key), key); }
  private static JsonArray array(JsonObject parent, String key) {
    var value = parent.get(key); if (value == null || !value.isJsonArray()) throw bad(key, "missing array"); return value.getAsJsonArray();
  }
  private static String string(JsonObject parent, String key) {
    var value = parent.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw bad(key, "missing string");
    return value.getAsString();
  }
  private static boolean bool(JsonObject parent, String key) {
    var value = parent.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw bad(key, "missing boolean");
    return value.getAsBoolean();
  }
  private static double number(JsonObject parent, String key) { return finite(parent.get(key), key); }
  private static double finite(JsonElement value, String key) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw bad(key, "missing finite number");
    return value.getAsDouble();
  }
  private static int integer(JsonObject parent, String key) {
    double value = number(parent, key);
    if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw bad(key, "expected integer");
    return (int) value;
  }
  static IllegalArgumentException bad(String key, String reason) { return new IllegalArgumentException("PhotonVision " + RELEASE + " shape: " + key + ": " + reason); }
}
