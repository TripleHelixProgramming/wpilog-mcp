package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for WPILib Transform2d struct (same layout as Pose2d).
 *
 * <p>Layout (24 bytes):
 * <ul>
 *   <li>double x (8 bytes)
 *   <li>double y (8 bytes)
 *   <li>double rotation (8 bytes)
 * </ul>
 *
 * @since 0.4.0
 */
public class Transform2dDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 24; // 3 doubles
  private final BinaryReader reader;

  public Transform2dDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "Transform2d";
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
