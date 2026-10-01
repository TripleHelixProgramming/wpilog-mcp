/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * WPILib's struct types, packed by hand.
 *
 * <p>wpimath's own serializers cannot be used here: its geometry classes pull in the protobuf and
 * units libraries during class initialization. The schema strings below are WPILib's canonical
 * ones, byte-for-byte as they appear in real robot logs (checked against a 2026 AdvantageKit log).
 */
public final class WpiStructs {

  private WpiStructs() {}

  /**
   * A struct type: its name, its schema, and the struct types its fields use.
   *
   * @param name Type name (the part after {@code struct:})
   * @param schema WPILib schema text
   * @param size Serialized size in bytes
   * @param nested Struct types referenced by the schema, registered after this one (as WPILib does)
   */
  public record Type(String name, String schema, int size, List<Type> nested) {}

  public static final Type ROTATION2D = new Type("Rotation2d", "double value", 8, List.of());
  public static final Type TRANSLATION2D = new Type("Translation2d", "double x;double y", 16,
      List.of());
  public static final Type POSE2D = new Type("Pose2d",
      "Translation2d translation;Rotation2d rotation", 24, List.of(TRANSLATION2D, ROTATION2D));
  public static final Type SWERVE_MODULE_STATE = new Type("SwerveModuleState",
      "double speed;Rotation2d angle", 16, List.of(ROTATION2D));
  public static final Type CHASSIS_SPEEDS = new Type("ChassisSpeeds",
      "double vx;double vy;double omega", 24, List.of());
  public static final Type QUATERNION = new Type("Quaternion",
      "double w;double x;double y;double z", 32, List.of());
  public static final Type ROTATION3D = new Type("Rotation3d", "Quaternion q", 32,
      List.of(QUATERNION));
  public static final Type TRANSLATION3D = new Type("Translation3d", "double x;double y;double z",
      24, List.of());
  public static final Type POSE3D = new Type("Pose3d",
      "Translation3d translation;Rotation3d rotation", 56, List.of(TRANSLATION3D, ROTATION3D));

  static ByteBuffer le(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  public static byte[] rotation2d(double radians) {
    return le(8).putDouble(radians).array();
  }

  public static byte[] pose2d(double x, double y, double radians) {
    return le(24).putDouble(x).putDouble(y).putDouble(radians).array();
  }

  public static byte[] swerveModuleState(double speed, double angleRadians) {
    return le(16).putDouble(speed).putDouble(angleRadians).array();
  }

  public static byte[] chassisSpeeds(double vx, double vy, double omega) {
    return le(24).putDouble(vx).putDouble(vy).putDouble(omega).array();
  }

  /** A Pose3d with rotation about Z only (yaw), as a quaternion. */
  public static byte[] pose3d(double x, double y, double z, double yawRadians) {
    return le(56).putDouble(x).putDouble(y).putDouble(z)
        .putDouble(Math.cos(yawRadians / 2)).putDouble(0).putDouble(0)
        .putDouble(Math.sin(yawRadians / 2)).array();
  }

  /** Concatenates struct records into an array payload. */
  public static byte[] concat(byte[]... records) {
    int total = 0;
    for (var r : records) total += r.length;
    var buffer = ByteBuffer.allocate(total);
    for (var r : records) buffer.put(r);
    return buffer.array();
  }
}
