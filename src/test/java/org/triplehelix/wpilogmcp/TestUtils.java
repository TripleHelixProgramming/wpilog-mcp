package org.triplehelix.wpilogmcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility methods for testing WPILOG MCP components.
 */
public final class TestUtils {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private TestUtils() {}

  // ============================================================
  // JSON Utilities
  // ============================================================

  /**
   * Creates a JSON object with the given key-value pairs.
   *
   * @param pairs Alternating key-value pairs
   * @return A JsonObject
   */
  public static JsonObject jsonObject(Object... pairs) {
    if (pairs.length % 2 != 0) {
      throw new IllegalArgumentException("Must provide key-value pairs");
    }

    JsonObject obj = new JsonObject();
    for (int i = 0; i < pairs.length; i += 2) {
      String key = (String) pairs[i];
      Object value = pairs[i + 1];

      if (value instanceof String) {
        obj.addProperty(key, (String) value);
      } else if (value instanceof Number) {
        obj.addProperty(key, (Number) value);
      } else if (value instanceof Boolean) {
        obj.addProperty(key, (Boolean) value);
      } else if (value instanceof JsonElement) {
        obj.add(key, (JsonElement) value);
      } else if (value == null) {
        obj.add(key, null);
      } else {
        obj.add(key, GSON.toJsonTree(value));
      }
    }
    return obj;
  }

  /**
   * Pretty-prints a JsonElement for debugging.
   */
  public static String prettyPrint(JsonElement element) {
    return GSON.toJson(element);
  }

  // ============================================================
  // Byte Array Utilities for Struct Testing
  // ============================================================

  /**
   * Creates a little-endian byte array containing the given doubles.
   */
  public static byte[] doublesToBytes(double... values) {
    ByteBuffer buffer = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
    for (double value : values) {
      buffer.putDouble(value);
    }
    return buffer.array();
  }

  /**
   * Creates bytes for a Pose2d struct.
   */
  public static byte[] createPose2dBytes(double x, double y, double rotationRadians) {
    return doublesToBytes(x, y, rotationRadians);
  }

  /**
   * Creates bytes for a Pose3d struct.
   */
  public static byte[] createPose3dBytes(
      double x, double y, double z, double qw, double qx, double qy, double qz) {
    return doublesToBytes(x, y, z, qw, qx, qy, qz);
  }

  /**
   * Creates bytes for a Translation2d struct.
   */
  public static byte[] createTranslation2dBytes(double x, double y) {
    return doublesToBytes(x, y);
  }

  /**
   * Creates bytes for a Translation3d struct.
   */
  public static byte[] createTranslation3dBytes(double x, double y, double z) {
    return doublesToBytes(x, y, z);
  }

  /**
   * Creates bytes for a Rotation2d struct.
   */
  public static byte[] createRotation2dBytes(double radians) {
    return doublesToBytes(radians);
  }

  /**
   * Creates bytes for a Rotation3d struct (quaternion).
   */
  public static byte[] createRotation3dBytes(double qw, double qx, double qy, double qz) {
    return doublesToBytes(qw, qx, qy, qz);
  }

  /**
   * Creates bytes for a ChassisSpeeds struct.
   */
  public static byte[] createChassisSpeedsBytes(double vxMps, double vyMps, double omegaRadps) {
    return doublesToBytes(vxMps, vyMps, omegaRadps);
  }

  /**
   * Creates bytes for a SwerveModuleState struct.
   */
  public static byte[] createSwerveModuleStateBytes(double speedMps, double angleRadians) {
    return doublesToBytes(speedMps, angleRadians);
  }

  /**
   * Creates bytes for a SwerveModulePosition struct.
   */
  public static byte[] createSwerveModulePositionBytes(double distanceMeters, double angleRadians) {
    return doublesToBytes(distanceMeters, angleRadians);
  }

  /**
   * Creates bytes for a Twist2d struct.
   */
  public static byte[] createTwist2dBytes(double dx, double dy, double dtheta) {
    return doublesToBytes(dx, dy, dtheta);
  }

  /**
   * Creates bytes for a Twist3d struct.
   */
  public static byte[] createTwist3dBytes(
      double dx, double dy, double dz, double rx, double ry, double rz) {
    return doublesToBytes(dx, dy, dz, rx, ry, rz);
  }

  /**
   * Creates bytes for an array of Pose2d structs.
   */
  public static byte[] createPose2dArrayBytes(double[]... poses) {
    ByteBuffer buffer = ByteBuffer.allocate(poses.length * 24).order(ByteOrder.LITTLE_ENDIAN);
    for (double[] pose : poses) {
      if (pose.length != 3) {
        throw new IllegalArgumentException("Each pose must have [x, y, rotation]");
      }
      buffer.putDouble(pose[0]).putDouble(pose[1]).putDouble(pose[2]);
    }
    return buffer.array();
  }

  /**
   * Creates bytes for an array of SwerveModuleState structs (typically 4 modules).
   */
  public static byte[] createSwerveModuleStateArrayBytes(double[]... modules) {
    ByteBuffer buffer = ByteBuffer.allocate(modules.length * 16).order(ByteOrder.LITTLE_ENDIAN);
    for (double[] module : modules) {
      if (module.length != 2) {
        throw new IllegalArgumentException("Each module must have [speed, angle]");
      }
      buffer.putDouble(module[0]).putDouble(module[1]);
    }
    return buffer.array();
  }

  // ============================================================
  // Time Utilities
  // ============================================================

  /**
   * Converts seconds to microseconds (WPILOG timestamp format).
   */
  public static long secondsToMicros(double seconds) {
    return (long) (seconds * 1_000_000);
  }

  /**
   * Converts microseconds to seconds.
   */
  public static double microsToSeconds(long micros) {
    return micros / 1_000_000.0;
  }

  // ============================================================
  // Assertion Helpers
  // ============================================================

  /**
   * Asserts that a JsonObject represents a successful result.
   */
  public static void assertSuccess(JsonObject result) {
    if (!result.has("success") || !result.get("success").getAsBoolean()) {
      String error = result.has("error") ? result.get("error").getAsString() : "Unknown error";
      throw new AssertionError("Expected success but got error: " + error);
    }
  }

  /**
   * Asserts that a JsonObject represents an error result.
   */
  public static void assertError(JsonObject result) {
    if (result.has("success") && result.get("success").getAsBoolean()) {
      throw new AssertionError("Expected error but got success: " + prettyPrint(result));
    }
  }

  /**
   * Asserts that a JsonObject contains an error message matching the pattern.
   */
  public static void assertErrorContains(JsonObject result, String pattern) {
    assertError(result);
    if (!result.has("error")) {
      throw new AssertionError("Expected error field in result");
    }
    String error = result.get("error").getAsString();
    if (!error.contains(pattern)) {
      throw new AssertionError(
          "Expected error to contain '" + pattern + "' but got: " + error);
    }
  }
}
