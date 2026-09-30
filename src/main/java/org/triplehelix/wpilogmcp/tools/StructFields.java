/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.util.List;
import java.util.Map;

/**
 * Reads numbers out of decoded struct values by field path ({@code "angle.value"}), trying each
 * candidate path in turn, so domain tools do not depend on one decoder's key names.
 *
 * @since 0.9.0
 */
final class StructFields {

  private StructFields() {}

  /**
   * The finite number at the first candidate path that resolves, or null.
   *
   * @param value A decoded struct (nested maps)
   * @param paths Dot-separated field paths, e.g. {@code "speed"}, {@code "angle.value"}
   */
  static Double number(Object value, String... paths) {
    for (var path : paths) {
      Object current = value;
      for (var part : path.split("\\.")) {
        if (!(current instanceof Map<?, ?> map)) {
          current = null;
          break;
        }
        current = map.get(part);
      }
      if (current instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
    }
    return null;
  }

  /** The records of a struct array value; a single struct is a one-element list. */
  static List<?> elements(Object value) {
    if (value instanceof List<?> list) return list;
    if (value instanceof Map<?, ?>) return List.of(value);
    return List.of();
  }

  // SwerveModuleState: WPILib schema "double speed;Rotation2d angle"
  static Double moduleSpeed(Object state) {
    return number(state, "speed", "speed_mps");
  }

  static Double moduleAngle(Object state) {
    return number(state, "angle.value", "angle_rad", "angle.radians");
  }

  // Pose2d/Pose3d: WPILib schemas nest a translation
  static Double poseX(Object pose) {
    return number(pose, "translation.x", "x", "pose_x");
  }

  static Double poseY(Object pose) {
    return number(pose, "translation.y", "y", "pose_y");
  }

  /** Planar distance between two poses, or null when either translation cannot be read. */
  static Double planarDistance(Object a, Object b) {
    Double ax = poseX(a);
    Double ay = poseY(a);
    Double bx = poseX(b);
    Double by = poseY(b);
    if (ax == null || ay == null || bx == null || by == null) return null;
    return Math.hypot(ax - bx, ay - by);
  }
}
