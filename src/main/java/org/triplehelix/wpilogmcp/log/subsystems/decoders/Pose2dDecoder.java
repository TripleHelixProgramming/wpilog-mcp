package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for WPILib Pose2d struct (x, y, rotation).
 *
 * <p>Layout (24 bytes):
 * <ul>
 *   <li>double x (8 bytes) - X coordinate in meters
 *   <li>double y (8 bytes) - Y coordinate in meters
 *   <li>double rotation (8 bytes) - Rotation in radians
 * </ul>
 *
 * @since 0.4.0
 */
public class Pose2dDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 24; // 3 doubles
  private final BinaryReader reader;

  public Pose2dDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "Pose2d";
  }

  @Override
  public int getStructSize() {
    return STRUCT_SIZE;
  }

  @Override
  public Map<String, Object> decode(byte[] data, int offset) {
    if (data.length < offset + STRUCT_SIZE) {
      return Map.of("error", "insufficient data");
    }

    double x = reader.readDouble(data, offset);
    double y = reader.readDouble(data, offset + 8);
    double rotation = reader.readDouble(data, offset + 16);

    return Map.of(
        "x", x,
        "y", y,
        "rotation_rad", rotation,
        "rotation_deg", Math.toDegrees(rotation));
  }
}
