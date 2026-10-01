/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** analyze_vision on the fixture corpus (review issues A1, B4, G4). */
@DisplayName("analyze_vision on fixture logs")
class VisionFixtureTest extends FixtureToolTestBase {

  static JsonObject stream(JsonObject r, String camera) {
    return objects(r.getAsJsonArray("observation_streams")).stream()
        .filter(s -> s.get("camera").getAsString().equals(camera)).findFirst()
        .orElseThrow(() -> new AssertionError("no stream for " + camera + ": " + r));
  }

  @Test
  @DisplayName("PhotonVision PoseObservation[] streams: counts, tags, latency, residuals (B4)")
  void photonStreams() {
    var r = call("analyze_vision", "vision_photon_akit");
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    assertEquals(2, r.getAsJsonArray("observation_streams").size());
    var cam0 = stream(r, "Camera0");
    assertEquals("/Vision/Camera0/PoseObservations", cam0.get("entry").getAsString());
    // camera inputs every other loop over 1..60 s: 1476 records, one observation each
    assertEquals(1476, cam0.get("records").getAsInt());
    assertEquals(1476, cam0.get("observation_count").getAsInt());
    assertEquals(1476, cam0.getAsJsonObject("tag_count_distribution").get("1").getAsInt());
    // observation timestamp = log time - 0.06 s
    assertEquals(60.0, cam0.getAsJsonObject("latency").get("median_ms").getAsDouble(), 1e-6);
    // camera 0 reports the pose at log time t stamped t - 0.06 s, so against the robot pose at
    // its own timestamp the residual is the robot's motion over 60 ms: at most
    // |v| * 0.06 = 0.05 m/s * 0.06 s = 3 mm
    var residual = cam0.getAsJsonObject("residual_vs_robot_pose");
    assertEquals("/RealOutputs/Drive/Pose", residual.get("robot_pose_entry").getAsString());
    assertTrue(residual.get("max_m").getAsDouble() <= 0.0031, residual.toString());
    // camera 1 is offset by (0.02, -0.01) and disconnected for 30-40 s
    var cam1 = stream(r, "Camera1");
    assertTrue(cam1.get("observation_count").getAsInt() < 1476);
    assertEquals(Math.hypot(0.02, 0.01),
        cam1.getAsJsonObject("residual_vs_robot_pose").get("median_m").getAsDouble(), 1e-3);
  }

  @Test
  @DisplayName("vision_prefix limits vision entries only; the robot pose outside it is still used (A1)")
  void prefixDoesNotHidePose() {
    var r = call("analyze_vision", "vision_photon_akit", "vision_prefix", "/vision");
    assertEquals(2, r.getAsJsonArray("observation_streams").size(), "prefix is case-insensitive");
    assertEquals("/RealOutputs/Drive/Pose",
        r.getAsJsonObject("inputs").getAsJsonObject("entries").get("robot_pose").getAsString());
    assertTrue(r.has("pose_jumps"), "pose_jumps is always present, even when empty");
    assertEquals(0, r.get("jump_count").getAsInt());
  }

  @Test
  @DisplayName("a prefix that matches no vision data: partial, saying so, with pose jumps checked")
  void prefixMatchesNothing() {
    var r = call("analyze_vision", "vision_photon_akit", "vision_prefix", "/Limelight");
    assertEquals("partial", r.get("status").getAsString());
    assertTrue(r.getAsJsonArray("skipped").toString().contains("/Limelight"));
    assertEquals("/RealOutputs/Drive/Pose", strings(r.getAsJsonArray("pose_entries_checked")).get(0));
  }

  @Test
  @DisplayName("Limelight tv: acquisition rate from the change-held boolean")
  void limelight() {
    var r = call("analyze_vision", "vision_limelight");
    var acq = objects(r.getAsJsonArray("target_acquisition")).get(0);
    assertEquals("NT:/limelight-front/tv", acq.get("entry").getAsString());
    assertTrue(acq.get("total_samples").getAsInt() > 0);
  }

  @Test
  @DisplayName("a scalar Pose3d vision estimate with one real jump")
  void pose3dJump() {
    var r = call("analyze_vision", "vision_pose3d", "jump_threshold", 1.0);
    assertEquals(2, r.get("jump_count").getAsInt()); // out to +1.5 m and back
    var jump = objects(r.getAsJsonArray("pose_jumps")).get(0);
    assertEquals("/RealOutputs/Vision/Camera0/EstimatedPose", jump.get("entry").getAsString());
  }

  @Test
  @DisplayName("no vision data and no poses: no_match with what was searched")
  void noMatch() {
    var r = call("analyze_vision", "canivore");
    assertEquals("no_match", r.get("status").getAsString());
    assertEquals(5, r.getAsJsonArray("looked_for").size());
  }

  @Test
  @DisplayName("target streams: TargetObservation found by its yaw and pitch fields")
  void targetStreams() {
    var r = call("analyze_vision", "vision_photon_akit");
    var streams = objects(r.getAsJsonArray("target_streams"));
    assertEquals(2, streams.size(), r.toString());
    var camera1 = streams.stream().filter(s -> s.get("camera").getAsString().equals("Camera1"))
        .findFirst().orElseThrow();
    assertEquals("/Vision/Camera1/LatestTargetObservation", camera1.get("entry").getAsString());
    // yaw 0.1 rad (5.73 deg), confidence 0.95, object id 8 in every record
    assertEquals(Math.toDegrees(0.1), camera1.getAsJsonObject("yaw").get("median_deg")
        .getAsDouble(), 1e-9);
    assertEquals(0.95, camera1.getAsJsonObject("confidence").get("median").getAsDouble(), 1e-6);
    assertEquals(camera1.get("observation_count").getAsInt(),
        camera1.getAsJsonObject("object_ids").get("8").getAsInt());
  }

  @Test
  @DisplayName("pose sets: Pose3d[] per loop, with how often they hold a pose")
  void poseSets() {
    var r = call("analyze_vision", "vision_photon_akit");
    var sets = objects(r.getAsJsonArray("pose_sets"));
    assertEquals(1, sets.size(), r.toString());
    var set = sets.get(0);
    assertEquals("/RealOutputs/Vision/Summary/RobotPosesAccepted", set.get("entry").getAsString());
    // one empty record at the start, then one pose per loop
    assertEquals(set.get("records").getAsInt() - 1, set.get("records_non_empty").getAsInt());
    assertEquals(1, set.get("max_poses_per_record").getAsInt());
  }

  @Test
  @DisplayName("observation streams report the fraction of records with an observation and logged latency")
  void streamExtras() {
    var r = call("analyze_vision", "vision_photon_akit");
    for (var stream : objects(r.getAsJsonArray("observation_streams"))) {
      assertEquals(1.0, stream.get("fraction_with_observations").getAsDouble(), 1e-9);
      var logged = stream.getAsJsonObject("logged_latency");
      assertTrue(logged.get("entry").getAsString().endsWith("/LatencyMs"), logged.toString());
      double expected = stream.get("camera").getAsString().equals("Camera0") ? 61.0 : 62.0;
      assertEquals(expected, logged.get("median").getAsDouble(), 1e-9);
    }
  }
}
