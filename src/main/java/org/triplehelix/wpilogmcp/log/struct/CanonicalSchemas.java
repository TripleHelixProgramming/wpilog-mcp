/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log.struct;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Struct schemas used only when a log does not carry its own ({@code /.schema/struct:<Name>}).
 *
 * <p>WPILib's geometry and kinematics schemas are fixed by WPILib and identical in every log that
 * records them. The team-template schemas ({@code PoseObservation}, {@code TargetObservation} from
 * the AdvantageKit vision template, and Choreo's {@code SwerveSample}) are assumptions: teams edit
 * these structs, which is why a logged schema always wins and results say which source was used.
 *
 * @since 0.9.0
 */
public final class CanonicalSchemas {

  private CanonicalSchemas() {}

  /** WPILib's own schemas, as its struct serializers write them. */
  public static final Map<String, String> WPILIB = ordered(
      "Rotation2d", "double value",
      "Translation2d", "double x;double y",
      "Pose2d", "Translation2d translation;Rotation2d rotation",
      "Transform2d", "Translation2d translation;Rotation2d rotation",
      "Twist2d", "double dx;double dy;double dtheta",
      "Quaternion", "double w;double x;double y;double z",
      "Rotation3d", "Quaternion q",
      "Translation3d", "double x;double y;double z",
      "Pose3d", "Translation3d translation;Rotation3d rotation",
      "Transform3d", "Translation3d translation;Rotation3d rotation",
      "Twist3d", "double dx;double dy;double dz;double rx;double ry;double rz",
      "ChassisSpeeds", "double vx;double vy;double omega",
      "SwerveModuleState", "double speed;Rotation2d angle",
      "SwerveModulePosition", "double distance;Rotation2d angle");

  /** Team-template schemas, used only as a stated assumption. */
  public static final Map<String, String> ASSUMED = ordered(
      "PoseObservation", "double timestamp;Pose3d pose;double ambiguity;int32 tagCount;"
          + "double averageTagDistance;enum {MEGATAG_1=0, MEGATAG_2=1, PHOTONVISION=2} int32 type",
      "TargetObservation",
          "Rotation2d yaw;Rotation2d pitch;Rotation2d skew;double area;float confidence;int32 objectID",
      "SwerveSample", "double t;double x;double y;double heading;double vx;double vy;double omega;"
          + "double ax;double ay;double alpha;double fx[4];double fy[4]");

  private static Map<String, String> ordered(String... pairs) {
    var map = new LinkedHashMap<String, String>();
    for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
    return java.util.Collections.unmodifiableMap(map);
  }

  /** Canonical form of a schema for comparison: no spaces, no trailing semicolon. */
  public static String normalize(String schema) {
    var s = schema.replaceAll("\\s+", " ").replaceAll("\\s*;\\s*", ";")
        .replaceAll("\\s*([{}=,])\\s*", "$1").strip();
    while (s.endsWith(";")) s = s.substring(0, s.length() - 1);
    return s;
  }
}
