/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.fixtures.WpiStructs;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * analyze_vision on a log with no vision at all, laid out like CTRE's swerve template in
 * simulation: a planned path as a Pose2d[] is not vision data, and the odometry reset when
 * autonomous starts is flagged as near an enable, not left to read as a vision correction.
 */
@DisplayName("analyze_vision without vision data (CTRE template layout)")
class VisionNonVisionPosesTest {

  @TempDir Path dir;

  @AfterEach
  void unload() {
    LogManager.getInstance().unloadAllLogs();
  }

  JsonObject analyzeVision(Path log) throws Exception {
    LogManager.getInstance().addAllowedDirectory(dir);
    var registry = new ToolRegistry();
    FrcDomainTools.registerAll(registry);
    var args = new JsonObject();
    args.addProperty("path", log.toString());
    return registry.getTool("analyze_vision").execute(args).getAsJsonObject();
  }

  @Test
  @DisplayName("a Pose2d[] path is not a pose set; a jump at enable carries near_enable_sec")
  void pathAndOdometryReset() throws Exception {
    var path = dir.resolve("ctre_like.wpilog");
    try (var w = new FixtureWriter(path, "")) {
      w.schema(WpiStructs.POSE2D, 0.0);
      w.bool("DS:enabled", 0.0, false).bool("DS:autonomous", 0.0, true);
      w.bool("DS:enabled", 5.0, true).bool("DS:enabled", 20.0, false);
      for (int i = 0; i <= 1000; i++) {
        double t = i * 0.02;
        // At the origin until PathPlanner resets odometry to the auto's start pose at 5.02 s
        double x = t < 5.02 ? 0.0 : 6.0 + 0.1 * (t - 5.02);
        double y = t < 5.02 ? 0.0 : 2.5;
        w.struct("NT:/DriveState/Pose", WpiStructs.POSE2D, t, WpiStructs.pose2d(x, y, 0.0));
      }
      w.structArr("NT:/PathPlanner/activePath", WpiStructs.POSE2D, 5.0,
          WpiStructs.pose2d(6.0, 2.5, 0.0), WpiStructs.pose2d(7.0, 2.5, 0.0),
          WpiStructs.pose2d(8.0, 2.5, 0.0));
    }

    var r = analyzeVision(path);

    assertEquals(0, r.getAsJsonArray("pose_sets").size(), r.toString());
    assertEquals("partial", r.get("status").getAsString(), "no vision data: only jumps checked");
    assertEquals(1, r.get("jump_count").getAsInt());
    var jump = r.getAsJsonArray("pose_jumps").get(0).getAsJsonObject();
    assertEquals("NT:/DriveState/Pose", jump.get("entry").getAsString());
    assertEquals(0.02, jump.get("near_enable_sec").getAsDouble(), 1e-6);
  }

  @Test
  @DisplayName("the vision template's pose arrays are pose sets; others under a vision path "
      + "are candidates; a Pose3d[] elsewhere is not vision data")
  void visionPoseArrays() throws Exception {
    var path = dir.resolve("vision_arrays.wpilog");
    try (var w = new FixtureWriter(path, "")) {
      w.schema(WpiStructs.POSE2D, 0.0);
      for (int i = 0; i < 50; i++) {
        double t = i * 0.02;
        w.structArr("/RealOutputs/Vision/Summary/RobotPoses", WpiStructs.POSE3D, t,
            WpiStructs.pose3d(1.0, 1.0, 0.0, 0.0));
        w.structArr("NT:/Photon/Accepted", WpiStructs.POSE2D, t, WpiStructs.pose2d(1.0, 1.0, 0.0));
        w.structArr("NT:/Auto/Trajectory", WpiStructs.POSE2D, t, WpiStructs.pose2d(2.0, 2.0, 0.0));
        // It used to be a vision pose set for being a Pose3d[]
        w.structArr("/RealOutputs/Mechanism/ComponentPoses", WpiStructs.POSE3D, t,
            WpiStructs.pose3d(0.1, 0.0, 0.5, 0.0));
      }
    }

    var r = analyzeVision(path);

    var sets = r.getAsJsonArray("pose_sets");
    assertEquals(1, sets.size(), r.toString());
    assertEquals("/RealOutputs/Vision/Summary/RobotPoses",
        sets.get(0).getAsJsonObject().get("entry").getAsString());
    assertEquals("[\"NT:/Photon/Accepted\"]",
        r.getAsJsonObject("candidates").get("pose_sets").toString());
    assertTrue(r.get("needs_confirmation").getAsBoolean());
    assertFalse(r.toString().contains("ComponentPoses") || r.toString().contains("Trajectory"),
        r.toString());

    // Passed, the candidate is a pose set
    LogManager.getInstance().addAllowedDirectory(dir);
    var registry = new ToolRegistry();
    FrcDomainTools.registerAll(registry);
    var args = new JsonObject();
    args.addProperty("path", path.toString());
    var passed = new com.google.gson.JsonArray();
    passed.add("NT:/Photon/Accepted");
    args.add("vision_entries", passed);
    var explicit = registry.getTool("analyze_vision").execute(args).getAsJsonObject();
    assertEquals(2, explicit.getAsJsonArray("pose_sets").size(), explicit.toString());
    assertFalse(explicit.has("candidates"), explicit.toString());
  }
}
